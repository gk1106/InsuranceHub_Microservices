package com.insurancehub.gateway.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

// Fails the readiness probe (not liveness - Boot's default health group membership includes any
// HealthIndicator bean in both /actuator/health and the readiness group) when KeyRegistry never
// finished loading. Same condition as KeyRegistry: doesn't exist at all when crypto is off, so
// readiness is governed by the DB indicator alone in that case, unchanged from phase 5.
@Component
@ConditionalOnProperty(name = "hub.crypto.enabled", havingValue = "true")
public class KeyRegistryHealthIndicator implements HealthIndicator {

  private final KeyRegistry keyRegistry;

  public KeyRegistryHealthIndicator(KeyRegistry keyRegistry) {
    this.keyRegistry = keyRegistry;
  }

  @Override
  public Health health() {
    if (!keyRegistry.isLoaded()) {
      // Detail message only, never key content - KeyRegistry's own log line has the specifics.
      return Health.down().withDetail("keyRegistry", "not loaded - see hub-gateway logs").build();
    }
    return Health.up().build();
  }
}
