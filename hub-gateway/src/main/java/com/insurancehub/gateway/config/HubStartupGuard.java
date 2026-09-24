package com.insurancehub.gateway.config;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

// Two production-safety invariants, one guard: fail startup (not fail late on first request)
// if either would silently ship somewhere it never should. @PostConstruct throwing aborts
// SpringApplication.run() with a clear, intentional message instead of a confusing downstream
// symptom later.
@Configuration
@EnableConfigurationProperties({
  HubCryptoProperties.class,
  InsurerProperties.class,
  HubSecurityProperties.class
})
public class HubStartupGuard {

  private static final Set<String> NON_LOCAL_PROFILES = Set.of("dev", "prod");
  private static final String PLACEHOLDER_CIDR = "0.0.0.0/0";

  private final Environment environment;
  private final HubCryptoProperties cryptoProperties;
  private final InsurerProperties insurerProperties;

  @Autowired
  public HubStartupGuard(
      Environment environment,
      HubCryptoProperties cryptoProperties,
      InsurerProperties insurerProperties) {
    this.environment = environment;
    this.cryptoProperties = cryptoProperties;
    this.insurerProperties = insurerProperties;
  }

  @PostConstruct
  void checkProductionSafety() {
    if (!isNonLocalProfileActive()) {
      return;
    }
    if (!cryptoProperties.enabled()) {
      throw new IllegalStateException(
          "hub.crypto.enabled=false is not allowed outside the local profile "
              + "(CLAUDE.md rule 8) - active profiles: "
              + String.join(",", environment.getActiveProfiles()));
    }
    insurerProperties.insurers().stream()
        .filter(insurer -> insurer.allowedCidrs().contains(PLACEHOLDER_CIDR))
        .findFirst()
        .ifPresent(
            insurer -> {
              throw new IllegalStateException(
                  "hub.insurers["
                      + insurer.inspId()
                      + "].allowed-cidrs still contains the placeholder "
                      + PLACEHOLDER_CIDR
                      + " (docs/open-questions.md Q13) - not allowed outside the local profile");
            });
  }

  private boolean isNonLocalProfileActive() {
    for (String profile : environment.getActiveProfiles()) {
      if (NON_LOCAL_PROFILES.contains(profile)) {
        return true;
      }
    }
    return false;
  }
}
