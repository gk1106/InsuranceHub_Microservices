package com.insurancehub.gateway.crypto;

import static com.insurancehub.common.error.HubErrorCode.DECRYPTION_FAILED;
import static com.insurancehub.common.error.HubErrorCode.INTERNAL_ERROR;
import static com.insurancehub.common.error.HubErrorCode.SIGNATURE_INVALID;

import com.insurancehub.common.error.HubBusinessException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Base64;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// cross-cutting.md §5: Base64-decode -> verify JWS (insurer public key) -> parse JWE -> decrypt
// (bank private key), and the reverse outbound. The algorithm allow-list (CryptoAlgorithms) is
// checked from the parsed header BEFORE verify()/decrypt() ever runs, on every inbound object -
// this is what blocks an algorithm-confusion attack, not the verify/decrypt call itself failing
// "naturally" on a wrong algorithm.
//
// Every failure path throws SIGNATURE_INVALID or DECRYPTION_FAILED with a fixed, generic
// safeDetail - never anything derived from the actual JOSE exception message, which could echo
// header/structural details about which stage or field failed. HubController writes these as
// plain, unenveloped JSON (never hubCryptoService.encryptAndSign'd) - see that class.
@Component
@ConditionalOnProperty(name = "hub.crypto.enabled", havingValue = "true")
public class NimbusHubCryptoService implements HubCryptoService {

  private final KeyRegistry keyRegistry;

  public NimbusHubCryptoService(KeyRegistry keyRegistry) {
    this.keyRegistry = keyRegistry;
  }

  @Override
  public String verifyAndDecrypt(String enc, String inspId) {
    if (enc == null || enc.isBlank()) {
      throw decryptionFailed();
    }

    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(enc);
    } catch (IllegalArgumentException e) {
      throw decryptionFailed();
    }

    JWSObject signed;
    try {
      signed = JWSObject.parse(new String(decoded, StandardCharsets.UTF_8));
    } catch (ParseException | IllegalArgumentException e) {
      throw signatureInvalid();
    }
    if (!CryptoAlgorithms.JWS_ALGORITHM.equals(signed.getHeader().getAlgorithm())) {
      throw signatureInvalid();
    }

    RSAPublicKey insurerPublicKey =
        keyRegistry.insurerPublicKey(inspId).orElseThrow(this::signatureInvalid);
    try {
      if (!signed.verify(new RSASSAVerifier(insurerPublicKey))) {
        throw signatureInvalid();
      }
    } catch (JOSEException e) {
      throw signatureInvalid();
    }

    JWEObject jwe;
    try {
      jwe = JWEObject.parse(signed.getPayload().toString());
    } catch (ParseException | IllegalArgumentException e) {
      throw decryptionFailed();
    }
    if (!CryptoAlgorithms.JWE_ALGORITHM.equals(jwe.getHeader().getAlgorithm())
        || !CryptoAlgorithms.ENCRYPTION_METHOD.equals(jwe.getHeader().getEncryptionMethod())) {
      throw decryptionFailed();
    }

    for (RSAPrivateKey bankPrivateKey : keyRegistry.bankPrivateKeysByKid().values()) {
      try {
        jwe.decrypt(new RSADecrypter(bankPrivateKey));
        return jwe.getPayload().toString();
      } catch (JOSEException e) {
        // Try the next bank key (rotation) - only after every key has failed is this a real
        // DECRYPTION_FAILED, not a mid-loop one.
      }
    }
    throw decryptionFailed();
  }

  @Override
  public String encryptAndSign(String json, String inspId) {
    // The insurer's key is already known to be registered - verifyAndDecrypt only succeeds
    // using it, so reaching here on the same request already proved it. This branch is a
    // defensive "should never happen" (HubDispatcher's own INTERNAL_ERROR pattern), not a
    // user-facing outcome to design tests around.
    RSAPublicKey insurerPublicKey =
        keyRegistry
            .insurerPublicKey(inspId)
            .orElseThrow(
                () -> new HubBusinessException(INTERNAL_ERROR, INTERNAL_ERROR.errorDesc()));
    try {
      JWEObject jwe =
          new JWEObject(
              new JWEHeader.Builder(
                      CryptoAlgorithms.JWE_ALGORITHM, CryptoAlgorithms.ENCRYPTION_METHOD)
                  .build(),
              new Payload(json));
      jwe.encrypt(new RSAEncrypter(insurerPublicKey));

      JWSObject jws =
          new JWSObject(
              new JWSHeader.Builder(CryptoAlgorithms.JWS_ALGORITHM)
                  .keyID(keyRegistry.activeBankKid())
                  .build(),
              new Payload(jwe.serialize()));
      jws.sign(new RSASSASigner(keyRegistry.activeBankPrivateKey()));

      return Base64.getEncoder().encodeToString(jws.serialize().getBytes(StandardCharsets.UTF_8));
    } catch (JOSEException e) {
      throw new HubBusinessException(INTERNAL_ERROR, INTERNAL_ERROR.errorDesc());
    }
  }

  private HubBusinessException signatureInvalid() {
    return new HubBusinessException(SIGNATURE_INVALID, SIGNATURE_INVALID.errorDesc());
  }

  private HubBusinessException decryptionFailed() {
    return new HubBusinessException(DECRYPTION_FAILED, DECRYPTION_FAILED.errorDesc());
  }
}
