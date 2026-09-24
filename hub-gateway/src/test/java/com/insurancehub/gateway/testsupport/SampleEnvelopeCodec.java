package com.insurancehub.gateway.testsupport;

import com.insurancehub.gateway.crypto.CryptoAlgorithms;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Base64;

// Shared by the ITs and scripts/send-sample.sh (via EnvelopeCli) - one implementation of the
// JOSE mechanics, never referenced from main/ (cross-cutting.md §5: "Put the encryption logic in
// a small test-support class... not in production code"). Role-agnostic: "encrypt to this public
// key, sign with this private key" / the reverse - equally usable playing the insurer's role
// (building a request) or asserting on what the gateway itself produced (its response).
public final class SampleEnvelopeCodec {

  private SampleEnvelopeCodec() {}

  public static String encryptAndSign(
      String json,
      RSAPublicKey recipientPublicKey,
      RSAPrivateKey signerPrivateKey,
      String signerKid)
      throws JOSEException {
    JWEObject jwe =
        new JWEObject(
            new JWEHeader.Builder(
                    CryptoAlgorithms.JWE_ALGORITHM, CryptoAlgorithms.ENCRYPTION_METHOD)
                .build(),
            new Payload(json));
    jwe.encrypt(new RSAEncrypter(recipientPublicKey));

    JWSObject jws =
        new JWSObject(
            new JWSHeader.Builder(CryptoAlgorithms.JWS_ALGORITHM).keyID(signerKid).build(),
            new Payload(jwe.serialize()));
    jws.sign(new RSASSASigner(signerPrivateKey));

    return Base64.getEncoder().encodeToString(jws.serialize().getBytes(StandardCharsets.UTF_8));
  }

  public static String verifyAndDecrypt(
      String enc, RSAPublicKey signerPublicKey, RSAPrivateKey recipientPrivateKey)
      throws JOSEException, ParseException {
    String jwsCompact = new String(Base64.getDecoder().decode(enc), StandardCharsets.UTF_8);
    JWSObject signed = JWSObject.parse(jwsCompact);
    if (!signed.verify(new RSASSAVerifier(signerPublicKey))) {
      throw new IllegalStateException("signature verification failed");
    }
    JWEObject jwe = JWEObject.parse(signed.getPayload().toString());
    jwe.decrypt(new RSADecrypter(recipientPrivateKey));
    return jwe.getPayload().toString();
  }

  public static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  public static void writePrivateKeyPem(Path path, RSAPrivateKey key) throws IOException {
    writePem(path, "PRIVATE KEY", key.getEncoded());
  }

  public static void writePublicKeyPem(Path path, RSAPublicKey key) throws IOException {
    writePem(path, "PUBLIC KEY", key.getEncoded());
  }

  private static void writePem(Path path, String label, byte[] der) throws IOException {
    String base64 =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
    String pem = "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    Files.writeString(path, pem);
  }
}
