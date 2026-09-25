package com.insurancehub.claims.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.insurancehub.common.web.HubHeaders;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// Shortened connect/read timeouts (vs. the 2s/5s production defaults in application.yml) so the
// timeout and circuit-open scenarios run in seconds, not tens of seconds. Retry backoff (200ms
// x2) is already fast enough to leave at its production value.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"policy-service.connect-timeout=200ms", "policy-service.read-timeout=500ms"})
@AutoConfigureTestRestTemplate
@Testcontainers
class ClaimRegistrationResilienceIT {

  private static final String INSP_ID = "INSP001";
  private static final String POLICY_NUM = "POL-RESILIENCE-1";
  private static final LocalDate DATE_OF_LOSS = LocalDate.of(2026, 6, 1);

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  private static final WireMockServer WIRE_MOCK = new WireMockServer(0);

  @DynamicPropertySource
  static void policyServiceBaseUrl(DynamicPropertyRegistry registry) {
    WIRE_MOCK.start();
    registry.add("policy-service.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
  }

  @AfterAll
  static void stopWireMock() {
    WIRE_MOCK.stop();
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  @BeforeEach
  void resetWireMockAndCircuitBreaker() {
    WIRE_MOCK.resetAll();
    // All @Test methods in this class share one Spring context (and so one circuit breaker
    // instance) - without this reset, an earlier test's failures would leak into a later test's
    // sliding window regardless of method execution order.
    circuitBreakerRegistry.circuitBreaker("policyService").reset();
  }

  @Test
  void coverageAlwaysReturning503MapsToDownstreamUnavailable() {
    stubCoverageAlways(503);

    ResponseEntity<ProblemDetail> response = register("CLM-503-1", "REQ-503-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().getProperties()).containsEntry("code", "DOWNSTREAM_UNAVAILABLE");
  }

  @Test
  void coverageTimingOutMapsToDownstreamUnavailableNotAServerError() {
    // Delay exceeds the 500ms test read-timeout above.
    WIRE_MOCK.stubFor(
        get(urlPathEqualTo("/internal/policies/" + POLICY_NUM + "/coverage"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"active\":true}")
                    .withFixedDelay(2000)));

    ResponseEntity<ProblemDetail> response = register("CLM-TIMEOUT-1", "REQ-TIMEOUT-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().getProperties()).containsEntry("code", "DOWNSTREAM_UNAVAILABLE");
  }

  @Test
  void circuitOpensAfterRepeatedFailuresAndThenFailsFastWithoutCallingWireMock() {
    stubCoverageAlways(503);

    int maxLogicalCalls = 20; // generous - each failing call contributes up to 3 window entries
    boolean openedBeforeCap = false;
    int previousRequestCount = 0;
    ResponseEntity<ProblemDetail> lastResponse = null;

    for (int i = 0; i < maxLogicalCalls; i++) {
      lastResponse = register("CLM-CB-" + i, "REQ-CB-" + i);
      int currentRequestCount = WIRE_MOCK.getAllServeEvents().size();
      if (currentRequestCount == previousRequestCount) {
        // WireMock received no new request for this logical call - the circuit is open and
        // Resilience4j is short-circuiting before any network call is attempted.
        openedBeforeCap = true;
        break;
      }
      previousRequestCount = currentRequestCount;
    }

    assertThat(openedBeforeCap)
        .as("circuit breaker should have opened within %d logical calls", maxLogicalCalls)
        .isTrue();
    assertThat(lastResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(lastResponse.getBody().getProperties())
        .containsEntry("code", "DOWNSTREAM_UNAVAILABLE");

    int requestCountAtTrip = WIRE_MOCK.getAllServeEvents().size();
    register("CLM-CB-FINAL", "REQ-CB-FINAL");
    assertThat(WIRE_MOCK.getAllServeEvents().size())
        .as("a call while the circuit is open must never reach policy-service")
        .isEqualTo(requestCountAtTrip);
  }

  @Test
  void a404IsNeverRetried() {
    WIRE_MOCK.stubFor(
        get(urlPathEqualTo("/internal/policies/" + POLICY_NUM + "/coverage"))
            .willReturn(aResponse().withStatus(404)));

    ResponseEntity<ProblemDetail> response = register("CLM-404-1", "REQ-404-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getProperties()).containsEntry("code", "POLICY_NOT_FOUND");
    WIRE_MOCK.verify(
        1, getRequestedFor(urlPathEqualTo("/internal/policies/" + POLICY_NUM + "/coverage")));
  }

  private void stubCoverageAlways(int status) {
    WIRE_MOCK.stubFor(
        get(urlPathEqualTo("/internal/policies/" + POLICY_NUM + "/coverage"))
            .willReturn(aResponse().withStatus(status)));
  }

  private ResponseEntity<ProblemDetail> register(String claimNum, String reqId) {
    RegisterClaimRequest request =
        new RegisterClaimRequest(
            POLICY_NUM,
            claimNum,
            "ACCIDENT",
            "Front bumper damage",
            "Collision",
            "Chennai",
            DATE_OF_LOSS,
            DATE_OF_LOSS.plusDays(2),
            new BigDecimal("85000.00"),
            null,
            null);
    HttpHeaders headers = new HttpHeaders();
    headers.set(HubHeaders.REQ_ID, reqId);
    headers.set(HubHeaders.INSP_ID, INSP_ID);
    headers.set(HubHeaders.TXN_ID, "TXN-" + reqId);
    headers.set(HubHeaders.INTERNAL_AUTH, "local-dev-internal-secret-CHANGE-ME");
    return restTemplate.exchange(
        "/internal/claims",
        HttpMethod.POST,
        new HttpEntity<>(request, headers),
        ProblemDetail.class);
  }
}
