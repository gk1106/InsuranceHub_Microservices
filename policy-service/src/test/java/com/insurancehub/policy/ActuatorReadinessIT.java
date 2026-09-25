package com.insurancehub.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// cross-cutting.md §4: actuator lives on its own management.server.port, verified here by
// actually hitting it over HTTP rather than trusting the config. management.server.port=0 gives
// it a random port (matching the app's own RANDOM_PORT), read back via @LocalManagementPort -
// same pattern as hub-gateway's HubGatewayCryptoReadinessIT/-UpIT.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // show-details=always only for this test - production leaves it at the default "never"
    // (application.yml), but this test needs per-contributor detail to prove `db` is really
    // named in the readiness group, not just that the aggregate happens to be UP.
    properties = {"management.server.port=0", "management.endpoint.health.show-details=always"})
@AutoConfigureTestRestTemplate
@Testcontainers
class ActuatorReadinessIT {

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  @Autowired private TestRestTemplate restTemplate;
  @LocalManagementPort private int managementPort;

  @Test
  void readinessIncludesDbAndReportsUp() {
    var response =
        restTemplate.getForEntity(
            "http://localhost:" + managementPort + "/actuator/health/readiness", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    // Not just "UP" overall - the db contributor must actually be named in the response, proving
    // the readiness group really includes it rather than happening to pass with just
    // readinessState.
    assertThat(response.getBody()).contains("\"db\"").contains("UP");
  }

  @Test
  void appPortNeverServesActuator() {
    var response = restTemplate.getForEntity("/actuator/health/readiness", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
