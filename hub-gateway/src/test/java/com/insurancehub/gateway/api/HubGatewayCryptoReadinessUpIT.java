package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.AbstractHubGatewayCryptoIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

// The other half of HubGatewayCryptoReadinessIT's own check: with the shared base class's real,
// successfully-loaded throwaway keys (not a bad path), readiness must report UP - proving the
// management.endpoint.health.group.readiness.include wiring surfaces both directions, not just
// the failure one.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewayCryptoReadinessUpIT extends AbstractHubGatewayCryptoIT {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void readinessIsUpWhenTheKeyRegistryLoadedSuccessfully() {
    var response = restTemplate.getForEntity("/actuator/health/readiness", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("UP");
  }
}
