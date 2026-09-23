package com.insurancehub.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// api-contract.md §1: "Keep this in config (hub.insurers[]), not code. Each entry also holds
// the insurer's OAuth client id, public key reference and IP allowlist." (publicKeyRef isn't
// here yet - phase 6, no crypto to reference a key for in phase 5.)
@ConfigurationProperties(prefix = "hub")
public record InsurerProperties(List<InsurerConfig> insurers) {

  public record InsurerConfig(
      String inspId, String inspName, String oauthClientId, List<String> allowedCidrs) {}
}
