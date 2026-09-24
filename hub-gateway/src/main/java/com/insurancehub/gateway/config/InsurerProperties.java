package com.insurancehub.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// api-contract.md §1: "Keep this in config (hub.insurers[]), not code. Each entry also holds
// the insurer's OAuth client id, public key reference and IP allowlist."
@ConfigurationProperties(prefix = "hub")
public record InsurerProperties(List<InsurerConfig> insurers) {

  // publicKeyPath is nullable - an insurer can be onboarded for OAuth/IP before its signing
  // certificate is - crypto.KeyRegistry.insurerPublicKey() returns empty for one, not a
  // startup failure (docs/open-questions.md).
  public record InsurerConfig(
      String inspId,
      String inspName,
      String oauthClientId,
      List<String> allowedCidrs,
      String publicKeyPath) {}
}
