package com.insurancehub.gateway;

import com.insurancehub.gateway.testsupport.SampleEnvelopeCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Generates its own throwaway bank + 3-insurer RSA key pairs into a temp directory (once - a
// static initializer, shared by every crypto IT subclass, same reasoning as
// AbstractHubGatewayIT's own singleton Keycloak/MySQL containers: this never depends on a
// developer having run scripts/gen-dev-keys.sh, and RSA key generation isn't free enough to
// redo per test class). Turns crypto on and restates the FULL hub.insurers list (not just the
// new public-key-path field) - a partial per-source override of one field of a hub.insurers[]
// list element hits the same "one property source wins the whole list" binder behavior
// documented on HubGatewaySecurityIT's own CIDR-narrowing override.
public abstract class AbstractHubGatewayCryptoIT extends AbstractHubGatewayIT {

  protected static final String BANK_KID = "bank-2026";
  protected static final List<String> INSP_IDS = List.of("INSP001", "INSP002", "INSP003");

  private static final Path TEMP_DIR;
  private static final KeyPair BANK_KEY_PAIR;
  private static final Map<String, KeyPair> INSURER_KEY_PAIRS = new LinkedHashMap<>();

  static {
    try {
      TEMP_DIR = Files.createTempDirectory("hub-gateway-crypto-it");
      BANK_KEY_PAIR = SampleEnvelopeCodec.generateRsaKeyPair();
      SampleEnvelopeCodec.writePrivateKeyPem(
          TEMP_DIR.resolve("bank-private.pem"), (RSAPrivateKey) BANK_KEY_PAIR.getPrivate());
      for (String inspId : INSP_IDS) {
        KeyPair keyPair = SampleEnvelopeCodec.generateRsaKeyPair();
        INSURER_KEY_PAIRS.put(inspId, keyPair);
        SampleEnvelopeCodec.writePublicKeyPem(
            TEMP_DIR.resolve(inspId + "-public.pem"), (RSAPublicKey) keyPair.getPublic());
      }
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("failed to generate crypto IT test keys", e);
    }
  }

  @DynamicPropertySource
  static void cryptoProperties(DynamicPropertyRegistry registry) {
    registry.add("hub.crypto.enabled", () -> "true");
    registry.add("hub.crypto.bank-keys[0].kid", () -> BANK_KID);
    registry.add(
        "hub.crypto.bank-keys[0].private-key-path",
        () -> TEMP_DIR.resolve("bank-private.pem").toString());
    // keyRegistry only exists as a health contributor with crypto on - see application.yml's own
    // comment on why this can't be a static default there.
    registry.add(
        "management.endpoint.health.group.readiness.include", () -> "readinessState,keyRegistry");

    String[] names = {"universalsompo", "sbigeneral", "nivabupa"};
    for (int i = 0; i < INSP_IDS.size(); i++) {
      String inspId = INSP_IDS.get(i);
      String clientId = inspId.toLowerCase(java.util.Locale.ROOT) + "-client";
      int index = i;
      registry.add("hub.insurers[" + index + "].insp-id", () -> inspId);
      registry.add("hub.insurers[" + index + "].insp-name", () -> names[index]);
      registry.add("hub.insurers[" + index + "].oauth-client-id", () -> clientId);
      registry.add("hub.insurers[" + index + "].allowed-cidrs[0]", () -> "0.0.0.0/0");
      registry.add(
          "hub.insurers[" + index + "].public-key-path",
          () -> TEMP_DIR.resolve(inspId + "-public.pem").toString());
    }
  }

  protected static RSAPrivateKey bankPrivateKey() {
    return (RSAPrivateKey) BANK_KEY_PAIR.getPrivate();
  }

  protected static RSAPublicKey bankPublicKey() {
    return (RSAPublicKey) BANK_KEY_PAIR.getPublic();
  }

  protected static RSAPrivateKey insurerPrivateKey(String inspId) {
    return (RSAPrivateKey) INSURER_KEY_PAIRS.get(inspId).getPrivate();
  }

  protected static RSAPublicKey insurerPublicKey(String inspId) {
    return (RSAPublicKey) INSURER_KEY_PAIRS.get(inspId).getPublic();
  }
}
