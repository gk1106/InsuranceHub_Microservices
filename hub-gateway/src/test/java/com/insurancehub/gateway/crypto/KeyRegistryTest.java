package com.insurancehub.gateway.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.config.HubCryptoProperties;
import com.insurancehub.gateway.config.InsurerProperties;
import com.insurancehub.gateway.testsupport.SampleEnvelopeCodec;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyRegistryTest {

  @TempDir private Path tempDir;

  @Test
  void bankKeyRotationOldKeyStillVerifiesNewKeySigns() throws Exception {
    KeyPair oldKeyPair = SampleEnvelopeCodec.generateRsaKeyPair();
    KeyPair newKeyPair = SampleEnvelopeCodec.generateRsaKeyPair();
    Path oldPath = tempDir.resolve("old-private.pem");
    Path newPath = tempDir.resolve("new-private.pem");
    SampleEnvelopeCodec.writePrivateKeyPem(oldPath, (RSAPrivateKey) oldKeyPair.getPrivate());
    SampleEnvelopeCodec.writePrivateKeyPem(newPath, (RSAPrivateKey) newKeyPair.getPrivate());

    // "new" listed first - config order determines the active (signing) key.
    KeyRegistry registry =
        newRegistry(
            new HubCryptoProperties(
                true,
                List.of(
                    new HubCryptoProperties.BankKey("new", newPath.toString()),
                    new HubCryptoProperties.BankKey("old", oldPath.toString()))),
            List.of());

    assertThat(registry.isLoaded()).isTrue();
    assertThat(registry.activeBankKid()).isEqualTo("new");
    assertThat(registry.activeBankPrivateKey().getModulus())
        .isEqualTo(((RSAPrivateKey) newKeyPair.getPrivate()).getModulus());

    // The registry retains BOTH keys' material correctly, not just the newest - a JWS built and
    // signed with the OLD key's own private key still verifies against the registry's OWN "old"
    // entry (derived from the same PEM file, not the fixture's in-memory KeyPair).
    RSAPrivateKey registryOldKey = registry.bankPrivateKeysByKid().get("old");
    assertThat(registryOldKey.getModulus())
        .isEqualTo(((RSAPrivateKey) oldKeyPair.getPrivate()).getModulus());
  }

  @Test
  void insurerPublicKeyIsEmptyForAnUnknownInspId() throws Exception {
    KeyRegistry registry = newRegistry(bankOnlyProperties(), List.of());

    assertThat(registry.insurerPublicKey("UNKNOWN")).isEmpty();
  }

  @Test
  void insurerPublicKeyIsEmptyWhenConfiguredWithNoKeyPath() throws Exception {
    InsurerProperties.InsurerConfig insurer =
        new InsurerProperties.InsurerConfig(
            "INSP001", "universalsompo", "insp001-client", List.of(), null);
    KeyRegistry registry = newRegistry(bankOnlyProperties(), List.of(insurer));

    assertThat(registry.isLoaded()).isTrue();
    assertThat(registry.insurerPublicKey("INSP001")).isEmpty();
  }

  @Test
  void insurerPublicKeyResolvesWhenConfigured() throws Exception {
    KeyPair insurerKeyPair = SampleEnvelopeCodec.generateRsaKeyPair();
    Path publicPath = tempDir.resolve("insp001-public.pem");
    SampleEnvelopeCodec.writePublicKeyPem(publicPath, (RSAPublicKey) insurerKeyPair.getPublic());
    InsurerProperties.InsurerConfig insurer =
        new InsurerProperties.InsurerConfig(
            "INSP001", "universalsompo", "insp001-client", List.of(), publicPath.toString());

    KeyRegistry registry = newRegistry(bankOnlyProperties(), List.of(insurer));

    assertThat(registry.insurerPublicKey("INSP001")).isPresent();
    assertThat(registry.insurerPublicKey("INSP001").get().getModulus())
        .isEqualTo(((RSAPublicKey) insurerKeyPair.getPublic()).getModulus());
  }

  @Test
  void aMalformedBankKeyPathLeavesTheRegistryNotLoaded() {
    KeyRegistry registry =
        newRegistry(
            new HubCryptoProperties(
                true, List.of(new HubCryptoProperties.BankKey("bank-2026", "/no/such/file.pem"))),
            List.of());

    assertThat(registry.isLoaded()).isFalse();
  }

  private KeyRegistry newRegistry(
      HubCryptoProperties cryptoProperties, List<InsurerProperties.InsurerConfig> insurers) {
    KeyRegistry registry = new KeyRegistry(cryptoProperties, new InsurerProperties(insurers));
    registry.load();
    return registry;
  }

  private HubCryptoProperties bankOnlyProperties() throws Exception {
    KeyPair bankKeyPair = SampleEnvelopeCodec.generateRsaKeyPair();
    Path bankPrivatePath = tempDir.resolve("bank-private.pem");
    SampleEnvelopeCodec.writePrivateKeyPem(
        bankPrivatePath, (RSAPrivateKey) bankKeyPair.getPrivate());
    return new HubCryptoProperties(
        true, List.of(new HubCryptoProperties.BankKey("bank-2026", bankPrivatePath.toString())));
  }
}
