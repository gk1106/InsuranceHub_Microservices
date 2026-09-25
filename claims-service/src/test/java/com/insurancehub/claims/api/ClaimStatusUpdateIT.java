package com.insurancehub.claims.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.insurancehub.claims.infrastructure.persistence.ClaimStatusHistoryJpaRepository;
import com.insurancehub.common.web.HubHeaders;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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

// Real MySQL, not H2. 04 never calls policy-service itself, but each test registers its claim
// via the real 03 endpoint first (rather than inserting rows directly), which does need
// coverage stubbed - same WireMock setup as ClaimRegistrationIT.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class ClaimStatusUpdateIT {

  private static final String INSP_ID = "INSP001";
  private static final LocalDate DATE_OF_LOSS = LocalDate.of(2026, 6, 1);
  private static final AtomicInteger SETUP_TXN_COUNTER = new AtomicInteger();

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
  @Autowired private ClaimStatusHistoryJpaRepository claimStatusHistoryJpaRepository;

  @BeforeEach
  void resetWireMock() {
    WIRE_MOCK.resetAll();
  }

  @Test
  void happyTransitionUpdatesTheStatusAndWritesAHistoryRow() {
    registerClaim("POL-HAPPY-1", "CLM-STATUS-HAPPY-1");

    ResponseEntity<UpdateClaimStatusResponse> response =
        patch(
            "CLM-STATUS-HAPPY-1",
            statusRequest("POL-HAPPY-1", "UNDER_PROCESS"),
            "REQ-STATUS-HAPPY-1",
            "TXN-STATUS-HAPPY-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().replayed()).isFalse();
    assertThat(response.getBody().claimNum()).isEqualTo("CLM-STATUS-HAPPY-1");
    var history =
        claimStatusHistoryJpaRepository.findByClaimClaimNumOrderByIdAsc("CLM-STATUS-HAPPY-1");
    assertThat(history).hasSize(2);
    assertThat(history.get(1).getFromStatus()).isEqualTo("REGISTERED");
    assertThat(history.get(1).getToStatus()).isEqualTo("UNDER_PROCESS");
  }

  @Test
  void replayReturnsOriginalTxnIdWithNoNewHistoryRow() {
    registerClaim("POL-REPLAY-1", "CLM-STATUS-REPLAY-1");

    ResponseEntity<UpdateClaimStatusResponse> first =
        patch(
            "CLM-STATUS-REPLAY-1",
            statusRequest("POL-REPLAY-1", "UNDER_PROCESS"),
            "REQ-STATUS-REPLAY-1",
            "TXN-STATUS-REPLAY-A");
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    long historyCountBefore =
        claimStatusHistoryJpaRepository
            .findByClaimClaimNumOrderByIdAsc("CLM-STATUS-REPLAY-1")
            .size();

    ResponseEntity<UpdateClaimStatusResponse> replay =
        patch(
            "CLM-STATUS-REPLAY-1",
            statusRequest("POL-REPLAY-1", "UNDER_PROCESS"),
            "REQ-STATUS-REPLAY-1",
            "TXN-STATUS-REPLAY-B");

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody().replayed()).isTrue();
    assertThat(replay.getBody().txnId()).isEqualTo("TXN-STATUS-REPLAY-A");
    assertThat(
            claimStatusHistoryJpaRepository
                .findByClaimClaimNumOrderByIdAsc("CLM-STATUS-REPLAY-1")
                .size())
        .isEqualTo((int) historyCountBefore);
  }

  @Test
  void invalidTransitionIsRejected() {
    registerClaim("POL-INVALID-1", "CLM-STATUS-INVALID-1");

    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/claims/CLM-STATUS-INVALID-1/status",
            HttpMethod.PATCH,
            entity(
                statusRequest("POL-INVALID-1", "REQUIREMENT_PENDING"),
                "REQ-STATUS-INVALID-1",
                "TXN-STATUS-INVALID-1"),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(response.getBody().getProperties())
        .containsEntry("code", "INVALID_STATUS_TRANSITION");
  }

  @Test
  void anyChangeOutOfATerminalStateIsRejected() {
    registerClaim("POL-TERMINAL-1", "CLM-STATUS-TERMINAL-1");
    ResponseEntity<UpdateClaimStatusResponse> toClosed =
        patch(
            "CLM-STATUS-TERMINAL-1",
            statusRequest("POL-TERMINAL-1", "CLOSED"),
            "REQ-STATUS-TERMINAL-1",
            "TXN-STATUS-TERMINAL-1");
    assertThat(toClosed.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<ProblemDetail> afterClosed =
        restTemplate.exchange(
            "/internal/claims/CLM-STATUS-TERMINAL-1/status",
            HttpMethod.PATCH,
            entity(
                statusRequest("POL-TERMINAL-1", "UNDER_PROCESS"),
                "REQ-STATUS-TERMINAL-2",
                "TXN-STATUS-TERMINAL-2"),
            ProblemDetail.class);

    assertThat(afterClosed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(afterClosed.getBody().getProperties())
        .containsEntry("code", "INVALID_STATUS_TRANSITION");
  }

  @Test
  void unknownClaimNumIs404() {
    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/claims/CLM-DOES-NOT-EXIST/status",
            HttpMethod.PATCH,
            entity(
                statusRequest("POL-ANY-1", "UNDER_PROCESS"),
                "REQ-STATUS-UNKNOWN-1",
                "TXN-STATUS-UNKNOWN-1"),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getProperties()).containsEntry("code", "CLAIM_NOT_FOUND");
  }

  @Test
  void claimUnderADifferentPolicyIs404AndLeaksNothing() {
    registerClaim("POL-REAL-OWNER-1", "CLM-STATUS-XOWNER-1");

    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/claims/CLM-STATUS-XOWNER-1/status",
            HttpMethod.PATCH,
            entity(
                statusRequest("POL-WRONG-1", "UNDER_PROCESS"),
                "REQ-STATUS-XOWNER-1",
                "TXN-STATUS-XOWNER-1"),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getProperties()).containsEntry("code", "CLAIM_NOT_FOUND");
    String detail = response.getBody().getDetail();
    assertThat(detail).doesNotContainIgnoringCase("policy");
    assertThat(detail).doesNotContain("POL-REAL-OWNER-1", "POL-WRONG-1");
  }

  @Test
  void unknownStatusValueIsRejected() {
    registerClaim("POL-BADSTATUS-1", "CLM-STATUS-BADSTATUS-1");

    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/claims/CLM-STATUS-BADSTATUS-1/status",
            HttpMethod.PATCH,
            entity(
                statusRequest("POL-BADSTATUS-1", "FOOBAR"),
                "REQ-STATUS-BADSTATUS-1",
                "TXN-STATUS-BADSTATUS-1"),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(response.getBody().getProperties())
        .containsEntry("code", "INVALID_STATUS_TRANSITION");
  }

  @Test
  void sameStatusTransitionIsAllowedWhenConfigPermitsItAndWritesAHistoryRow() {
    // docs/open-questions.md Q3: UNDER_PROCESS lists itself as an allowed target.
    registerClaim("POL-SAMESTATUS-1", "CLM-STATUS-SAMESTATUS-1");
    patch(
        "CLM-STATUS-SAMESTATUS-1",
        statusRequest("POL-SAMESTATUS-1", "UNDER_PROCESS"),
        "REQ-STATUS-SAMESTATUS-1",
        "TXN-STATUS-SAMESTATUS-1");

    ResponseEntity<UpdateClaimStatusResponse> response =
        patch(
            "CLM-STATUS-SAMESTATUS-1",
            statusRequest("POL-SAMESTATUS-1", "UNDER_PROCESS"),
            "REQ-STATUS-SAMESTATUS-2",
            "TXN-STATUS-SAMESTATUS-2");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var history =
        claimStatusHistoryJpaRepository.findByClaimClaimNumOrderByIdAsc("CLM-STATUS-SAMESTATUS-1");
    assertThat(history).hasSize(3); // registration + REGISTERED->UNDER_PROCESS + this one
    var lastRow = history.get(2);
    assertThat(lastRow.getFromStatus()).isEqualTo("UNDER_PROCESS");
    assertThat(lastRow.getToStatus()).isEqualTo("UNDER_PROCESS");
  }

  @Test
  void historyRowsAreWrittenInOrderWithCorrectFromAndTo() {
    registerClaim("POL-HISTORY-1", "CLM-STATUS-HISTORY-1");
    patch(
        "CLM-STATUS-HISTORY-1",
        statusRequest("POL-HISTORY-1", "UNDER_PROCESS"),
        "REQ-STATUS-HISTORY-1",
        "TXN-STATUS-HISTORY-1");
    patch(
        "CLM-STATUS-HISTORY-1",
        statusRequest("POL-HISTORY-1", "CLOSED"),
        "REQ-STATUS-HISTORY-2",
        "TXN-STATUS-HISTORY-2");

    var history =
        claimStatusHistoryJpaRepository.findByClaimClaimNumOrderByIdAsc("CLM-STATUS-HISTORY-1");

    assertThat(history).hasSize(3);
    assertThat(history.get(0).getFromStatus()).isNull();
    assertThat(history.get(0).getToStatus()).isEqualTo("REGISTERED");
    assertThat(history.get(1).getFromStatus()).isEqualTo("REGISTERED");
    assertThat(history.get(1).getToStatus()).isEqualTo("UNDER_PROCESS");
    assertThat(history.get(2).getFromStatus()).isEqualTo("UNDER_PROCESS");
    assertThat(history.get(2).getToStatus()).isEqualTo("CLOSED");
  }

  @Test
  void twoConcurrentUpdatesOneSucceedsTheOtherGets409NotA500() throws Exception {
    registerClaim("POL-CONCURRENT-1", "CLM-STATUS-CONCURRENT-1");

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);
    AtomicReference<ResponseEntity<String>> resultA = new AtomicReference<>();
    AtomicReference<ResponseEntity<String>> resultB = new AtomicReference<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);

    pool.submit(
        () ->
            race(
                ready,
                start,
                done,
                () ->
                    patchRaw(
                        "CLM-STATUS-CONCURRENT-1",
                        "REQ-STATUS-CONCURRENT-A",
                        "TXN-STATUS-CONCURRENT-A"),
                resultA));
    pool.submit(
        () ->
            race(
                ready,
                start,
                done,
                () ->
                    patchRaw(
                        "CLM-STATUS-CONCURRENT-1",
                        "REQ-STATUS-CONCURRENT-B",
                        "TXN-STATUS-CONCURRENT-B"),
                resultB));
    ready.await(5, TimeUnit.SECONDS);
    start.countDown();
    boolean finished = done.await(10, TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(finished).isTrue();
    assertThat(List.of(resultA.get().getStatusCode(), resultB.get().getStatusCode()))
        .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
  }

  private ResponseEntity<String> patchRaw(String claimNum, String reqId, String txnId) {
    return restTemplate.exchange(
        "/internal/claims/" + claimNum + "/status",
        HttpMethod.PATCH,
        entity(statusRequest("POL-CONCURRENT-1", "UNDER_PROCESS"), reqId, txnId),
        String.class);
  }

  private void race(
      CountDownLatch ready,
      CountDownLatch start,
      CountDownLatch done,
      Supplier<ResponseEntity<String>> action,
      AtomicReference<ResponseEntity<String>> result) {
    try {
      ready.countDown();
      start.await(5, TimeUnit.SECONDS);
      result.set(action.get());
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      done.countDown();
    }
  }

  private void registerClaim(String policyNum, String claimNum) {
    WIRE_MOCK.stubFor(
        get(urlPathEqualTo("/internal/policies/" + policyNum + "/coverage"))
            .withQueryParam("onDate", equalTo(DATE_OF_LOSS.toString()))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"active\":true}")));
    RegisterClaimRequest request =
        new RegisterClaimRequest(
            policyNum,
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
    ResponseEntity<RegisterClaimResponse> response =
        restTemplate.exchange(
            "/internal/claims",
            HttpMethod.POST,
            // txn_id is CHAR(26) - a short counter-based value, not claimNum-derived, so long
            // claimNums in this file's test names don't overflow the column.
            entity(
                request,
                "REQ-SETUP-" + claimNum,
                "TXN-SETUP-" + SETUP_TXN_COUNTER.incrementAndGet()),
            RegisterClaimResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private ResponseEntity<UpdateClaimStatusResponse> patch(
      String claimNum, UpdateClaimStatusRequest request, String reqId, String txnId) {
    return restTemplate.exchange(
        "/internal/claims/" + claimNum + "/status",
        HttpMethod.PATCH,
        entity(request, reqId, txnId),
        UpdateClaimStatusResponse.class);
  }

  private static UpdateClaimStatusRequest statusRequest(String policyNum, String claimStatus) {
    return new UpdateClaimStatusRequest(
        policyNum, claimStatus, null, null, null, null, null, null, null);
  }

  private <T> HttpEntity<T> entity(T body, String reqId, String txnId) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HubHeaders.REQ_ID, reqId);
    headers.set(HubHeaders.INSP_ID, INSP_ID);
    headers.set(HubHeaders.TXN_ID, txnId);
    headers.set(HubHeaders.INTERNAL_AUTH, "local-dev-internal-secret-CHANGE-ME");
    return new HttpEntity<>(body, headers);
  }
}
