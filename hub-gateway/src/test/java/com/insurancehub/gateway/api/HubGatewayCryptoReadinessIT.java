package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.AbstractHubGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// crypto.KeyRegistryHealthIndicator fails readiness specifically (not liveness) when the
// registry never loaded (cross-cutting.md §2). A bad key path here, not the shared crypto IT
// base class's throwaway keys - this class deliberately points at a file that doesn't exist.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewayCryptoReadinessIT extends AbstractHubGatewayIT {

  @DynamicPropertySource
  static void badBankKey(DynamicPropertyRegistry registry) {
    registry.add("hub.crypto.enabled", () -> "true");
    registry.add("hub.crypto.bank-keys[0].kid", () -> "bank-2026");
    registry.add("hub.crypto.bank-keys[0].private-key-path", () -> "/no/such/file.pem");
    registry.add(
        "management.endpoint.health.group.readiness.include", () -> "readinessState,keyRegistry");
  }

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void readinessIsDownWhenTheKeyRegistryFailedToLoad() {
    var response = restTemplate.getForEntity("/actuator/health/readiness", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).contains("DOWN");
  }
}
