package com.insurancehub.claims.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.insurancehub.claims.infrastructure.persistence.ClaimJpaRepository;
import com.insurancehub.claims.infrastructure.persistence.ClaimStatusHistoryJpaRepository;
import com.insurancehub.claims.infrastructure.persistence.ProcessedRequestJpaRepository;
import com.insurancehub.common.web.HubHeaders;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Real MySQL, not H2. policy-service is stubbed with WireMock, not the real service. Each test
// uses its own claimNum/reqId so tests sharing the one Testcontainer/WireMock instance don't
// collide.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class ClaimRegistrationIT {

  private static final String INSP_ID = "INSP001";

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

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
  @Autowired private ClaimJpaRepository claimJpaRepository;
  @Autowired private ClaimStatusHistoryJpaRepository claimStatusHistoryJpaRepository;
  @Autowired private ProcessedRequestJpaRepository processedRequestJpaRepository;

  @BeforeEach
  void resetWireMock() {
    WIRE_MOCK.resetAll();
  }

  @Test
  void happyPathRegistersAClaimAndReturns201() {
    stubCoverage("POL-HAPPY-1", LocalDate.of(2026, 6, 1), 200, true);

    ResponseEntity<RegisterClaimResponse> response =
        post(registerRequest("POL-HAPPY-1", "CLM-HAPPY-1"), "REQ-HAPPY-1", "TXN-HAPPY-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().replayed()).isFalse();
    assertThat(response.getBody().claimNum()).isEqualTo("CLM-HAPPY-1");
    assertThat(response.getBody().txnId()).isEqualTo("TXN-HAPPY-1");

    assertThat(claimJpaRepository.findByClaimNum("CLM-HAPPY-1")).isPresent();
    assertThat(claimStatusHistoryJpaRepository.findByClaimClaimNumOrderByIdAsc("CLM-HAPPY-1"))
        .hasSize(1);
    assertThat(processedRequestJpaRepository.findByInspIdAndReqId(INSP_ID, "REQ-HAPPY-1"))
        .isPresent();
  }

  @Test
  void replayReturnsOriginalTxnIdWithNoNewRowsAndNoSecondCoverageCall() {
    stubCoverage("POL-REPLAY-1", LocalDate.of(2026, 6, 1), 200, true);

    ResponseEntity<RegisterClaimResponse> first =
        post(registerRequest("POL-REPLAY-1", "CLM-REPLAY-1"), "REQ-REPLAY-1", "TXN-REPLAY-A");
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    long processedRequestCountBefore = processedRequestJpaRepository.count();

    // Same (inspId, reqId), different txnId - a real gateway retry generates a fresh txnId.
    ResponseEntity<RegisterClaimResponse> replay =
        post(registerRequest("POL-REPLAY-1", "CLM-REPLAY-1"), "REQ-REPLAY-1", "TXN-REPLAY-B");

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody().replayed()).isTrue();
    assertThat(replay.getBody().txnId()).isEqualTo("TXN-REPLAY-A");
    assertThat(processedRequestJpaRepository.count()).isEqualTo(processedRequestCountBefore);
    // The replay must skip the network call entirely - only ONE coverage request total.
    WIRE_MOCK.verify(
        1, getRequestedFor(urlPathEqualTo("/internal/policies/POL-REPLAY-1/coverage")));
  }

  @Test
  void duplicateClaimNumWithDifferentReqIdIsRejected() {
    stubCoverage("POL-DUP-1", LocalDate.of(2026, 6, 1), 200, true);
    post(registerRequest("POL-DUP-1", "CLM-DUP-1"), "REQ-DUP-A", "TXN-DUP-A");

    ResponseEntity<ProblemDetail> conflict =
        restTemplate.exchange(
            "/internal/claims",
            HttpMethod.POST,
            entity(registerRequest("POL-DUP-1", "CLM-DUP-1"), "REQ-DUP-B", "TXN-DUP-B"),
            ProblemDetail.class);

    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().getProperties()).containsEntry("code", "CLAIM_ALREADY_EXISTS");
  }

  @Test
  void unknownPolicyMapsToPolicyNotFound() {
    stubCoverageNotFound("POL-UNKNOWN-1", LocalDate.of(2026, 6, 1));

    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/claims",
            HttpMethod.POST,
            entity(
                registerRequest("POL-UNKNOWN-1", "CLM-UNKNOWN-1"),
                "REQ-UNKNOWN-1",
                "TXN-UNKNOWN-1"),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getProperties()).containsEntry("code", "POLICY_NOT_FOUND");
  }

  @Test
  void inactivePolicyMapsToPolicyNotActive() {
    stubCoverage("POL-INACTIVE-1", LocalDate.of(2026, 6, 1), 200, false);

    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/claims",
            HttpMethod.POST,
            entity(
                registerRequest("POL-INACTIVE-1", "CLM-INACTIVE-1"),
                "REQ-INACTIVE-1",
                "TXN-INACTIVE-1"),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(response.getBody().getProperties()).containsEntry("code", "POLICY_NOT_ACTIVE");
  }

  @Test
  void dateOfLossExactlyOnTermStartIsAccepted() {
    LocalDate termStart = LocalDate.of(2026, 1, 1);
    stubCoverage("POL-BOUNDARY-START-1", termStart, 200, true);

    ResponseEntity<RegisterClaimResponse> response =
        post(
            registerRequestOnDate("POL-BOUNDARY-START-1", "CLM-BOUNDARY-START-1", termStart),
            "REQ-BOUNDARY-START-1",
            "TXN-BOUNDARY-START-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void dateOfLossExactlyOnExpiryDateIsAccepted() {
    LocalDate termExpiry = LocalDate.of(2026, 12, 31);
    stubCoverage("POL-BOUNDARY-EXPIRY-1", termExpiry, 200, true);

    ResponseEntity<RegisterClaimResponse> response =
        post(
            registerRequestOnDate("POL-BOUNDARY-EXPIRY-1", "CLM-BOUNDARY-EXPIRY-1", termExpiry),
            "REQ-BOUNDARY-EXPIRY-1",
            "TXN-BOUNDARY-EXPIRY-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private void stubCoverage(String policyNum, LocalDate onDate, int status, boolean active) {
    WIRE_MOCK.stubFor(
        get(urlPathEqualTo("/internal/policies/" + policyNum + "/coverage"))
            .withQueryParam("onDate", equalTo(onDate.toString()))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"active\":" + active + "}")));
  }

  private void stubCoverageNotFound(String policyNum, LocalDate onDate) {
    WIRE_MOCK.stubFor(
        get(urlPathEqualTo("/internal/policies/" + policyNum + "/coverage"))
            .withQueryParam("onDate", equalTo(onDate.toString()))
            .willReturn(aResponse().withStatus(404)));
  }

  private ResponseEntity<RegisterClaimResponse> post(
      RegisterClaimRequest request, String reqId, String txnId) {
    return restTemplate.exchange(
        "/internal/claims",
        HttpMethod.POST,
        entity(request, reqId, txnId),
        RegisterClaimResponse.class);
  }

  private HttpEntity<RegisterClaimRequest> entity(
      RegisterClaimRequest request, String reqId, String txnId) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HubHeaders.REQ_ID, reqId);
    headers.set(HubHeaders.INSP_ID, INSP_ID);
    headers.set(HubHeaders.TXN_ID, txnId);
    return new HttpEntity<>(request, headers);
  }

  private static RegisterClaimRequest registerRequest(String policyNum, String claimNum) {
    return registerRequestOnDate(policyNum, claimNum, LocalDate.of(2026, 6, 1));
  }

  private static RegisterClaimRequest registerRequestOnDate(
      String policyNum, String claimNum, LocalDate dateOfLoss) {
    return new RegisterClaimRequest(
        policyNum,
        claimNum,
        "ACCIDENT",
        "Front bumper damage",
        "Collision",
        "Chennai",
        dateOfLoss,
        dateOfLoss.plusDays(2),
        new BigDecimal("85000.00"),
        null,
        null);
  }
}
