package com.insurancehub.gateway.crypto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import com.insurancehub.gateway.config.HubCryptoProperties;
import com.insurancehub.gateway.config.InsurerProperties;
import com.insurancehub.gateway.testsupport.SampleEnvelopeCodec;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Focused, no HTTP/Spring context - NimbusHubCryptoServiceIT covers the full round trip and
// wire-level failure modes; this covers the one scenario that needs a KeyRegistry deliberately
// missing a key, which the shared crypto IT base class's fully-registered 3 insurers can't
// exercise without a fourth, unused insurer.
class NimbusHubCryptoServiceTest {

  @TempDir private Path tempDir;

  @Test
  void aRequestForAnInsurerWithNoRegisteredPublicKeyIsRejectedAsSignatureInvalid()
      throws Exception {
    KeyPair bankKeyPair = SampleEnvelopeCodec.generateRsaKeyPair();
    Path bankPrivatePath = tempDir.resolve("bank-private.pem");
    SampleEnvelopeCodec.writePrivateKeyPem(
        bankPrivatePath, (RSAPrivateKey) bankKeyPair.getPrivate());

    HubCryptoProperties cryptoProperties =
        new HubCryptoProperties(
            true,
            List.of(new HubCryptoProperties.BankKey("bank-2026", bankPrivatePath.toString())));
    // No publicKeyPath at all for INSP001 - "onboarded" for OAuth/IP but not crypto yet.
    InsurerProperties insurerProperties =
        new InsurerProperties(
            List.of(
                new InsurerProperties.InsurerConfig(
                    "INSP001", "universalsompo", "insp001-client", List.of(), null)));
    KeyRegistry keyRegistry = new KeyRegistry(cryptoProperties, insurerProperties);
    keyRegistry.load();
    NimbusHubCryptoService cryptoService = new NimbusHubCryptoService(keyRegistry);

    // A well-formed envelope (signed by SOME real insurer key - the content doesn't matter,
    // since KeyRegistry.insurerPublicKey("INSP001") returning empty rejects before verify()
    // ever runs).
    KeyPair someInsurerKeyPair = SampleEnvelopeCodec.generateRsaKeyPair();
    String enc =
        SampleEnvelopeCodec.encryptAndSign(
            "{}",
            (java.security.interfaces.RSAPublicKey) bankKeyPair.getPublic(),
            (RSAPrivateKey) someInsurerKeyPair.getPrivate(),
            "insp001");

    assertThatThrownBy(() -> cryptoService.verifyAndDecrypt(enc, "INSP001"))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                org.assertj.core.api.Assertions.assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.SIGNATURE_INVALID));
  }
}
