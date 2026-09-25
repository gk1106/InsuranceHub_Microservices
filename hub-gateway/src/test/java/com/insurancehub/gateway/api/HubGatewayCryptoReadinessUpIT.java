package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.AbstractHubGatewayCryptoIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;

// The other half of HubGatewayCryptoReadinessIT's own check: with the shared base class's real,
// successfully-loaded throwaway keys (not a bad path), readiness must report UP - proving the
// management.endpoint.health.group.readiness.include wiring surfaces both directions, not just
// the failure one.
//
// Phase 8: see HubGatewayCryptoReadinessIT's own comment on management.server.port=0 /
// @LocalManagementPort - actuator no longer lives on the app's own random port.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@AutoConfigureTestRestTemplate
class HubGatewayCryptoReadinessUpIT extends AbstractHubGatewayCryptoIT {

  @Autowired private TestRestTemplate restTemplate;
  @LocalManagementPort private int managementPort;

  @Test
  void readinessIsUpWhenTheKeyRegistryLoadedSuccessfully() {
    var response =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/health/readiness", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("UP");
  }
}
