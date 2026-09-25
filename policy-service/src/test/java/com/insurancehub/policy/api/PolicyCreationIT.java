package com.insurancehub.policy.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import com.insurancehub.common.web.HubHeaders;
import com.insurancehub.policy.application.CreatePolicyCommand;
import com.insurancehub.policy.application.CreatePolicyResult;
import com.insurancehub.policy.application.PolicyCreationService;
import com.insurancehub.policy.infrastructure.persistence.PolicyJpaRepository;
import com.insurancehub.policy.infrastructure.persistence.PolicyTermJpaRepository;
import com.insurancehub.policy.infrastructure.persistence.ProcessedRequestJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// Real MySQL, not H2 (see testing-and-deploy.md). Each test uses its own policyNum/reqId so
// tests sharing the one Testcontainer don't collide.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class PolicyCreationIT {

  private static final String INSP_ID = "INSP001";

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private PolicyCreationService policyCreationService;
  @Autowired private PolicyJpaRepository policyJpaRepository;
  @Autowired private PolicyTermJpaRepository policyTermJpaRepository;
  @Autowired private ProcessedRequestJpaRepository processedRequestJpaRepository;

  @Test
  void happyPathCreatesAPolicyAndReturns201() {
    ResponseEntity<CreatePolicyResponse> response =
        post(validRequest("POL-HAPPY-1"), "REQ-HAPPY-1", "TXN-HAPPY-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().replayed()).isFalse();
    assertThat(response.getBody().policyNum()).isEqualTo("POL-HAPPY-1");
    assertThat(response.getBody().txnId()).isEqualTo("TXN-HAPPY-1");

    assertThat(policyJpaRepository.findByPolicyNum("POL-HAPPY-1")).isPresent();
    assertThat(policyTermJpaRepository.findByPolicyPolicyNum("POL-HAPPY-1")).hasSize(1);
    assertThat(processedRequestJpaRepository.findByInspIdAndReqId(INSP_ID, "REQ-HAPPY-1"))
        .isPresent();
  }

  @Test
  void replayReturnsOriginalTxnIdWithNoNewRows() {
    ResponseEntity<CreatePolicyResponse> first =
        post(validRequest("POL-REPLAY-1"), "REQ-REPLAY-1", "TXN-REPLAY-A");
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    long policyRowsBefore = policyTermJpaRepository.findByPolicyPolicyNum("POL-REPLAY-1").size();
    long processedRequestCountBefore = processedRequestJpaRepository.count();

    // Same (inspId, reqId) as the first call, but a different txnId - a real gateway retry
    // generates a fresh txnId per HTTP attempt.
    ResponseEntity<CreatePolicyResponse> replay =
        post(validRequest("POL-REPLAY-1"), "REQ-REPLAY-1", "TXN-REPLAY-B");

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody().replayed()).isTrue();
    assertThat(replay.getBody().txnId()).isEqualTo("TXN-REPLAY-A"); // the ORIGINAL txnId, not B
    assertThat(policyTermJpaRepository.findByPolicyPolicyNum("POL-REPLAY-1"))
        .hasSize((int) policyRowsBefore);
    assertThat(processedRequestJpaRepository.count()).isEqualTo(processedRequestCountBefore);
  }

  @Test
  void duplicatePolicyNumWithDifferentReqIdIsRejected() {
    post(validRequest("POL-DUP-1"), "REQ-DUP-A", "TXN-DUP-A");

    ResponseEntity<ProblemDetail> conflict =
        restTemplate.exchange(
            "/internal/policies",
            HttpMethod.POST,
            entity(validRequest("POL-DUP-1"), "REQ-DUP-B", "TXN-DUP-B"),
            ProblemDetail.class);

    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().getProperties()).containsEntry("code", "POLICY_ALREADY_EXISTS");
  }

  @Test
  void validationFailureMapsToBadRequestNamingTheField() {
    CreatePolicyRequest missingCif =
        new CreatePolicyRequest(
            "POL-INVALID-1", // policyNum
            null, // applicationNum
            null, // cif - required, deliberately missing
            null, // accountNum
            "Ravi Kumar", // insuredName
            null, // mobileNum
            null, // address
            "GENERAL", // insuranceType
            null, // insuranceName
            null, // regionCode
            null, // regionName
            null, // branchCode
            null, // branchName
            null, // loanAcctNum
            null, // specPerNum
            null, // specPerName
            null, // applicationStatus
            null, // issueDate
            LocalDate.of(2026, 1, 1), // startDate
            LocalDate.of(2027, 1, 1), // expiryDate
            new BigDecimal("1000.00"), // netPremium
            null, // gstAmt
            new BigDecimal("1000.00"), // grossPremium
            new BigDecimal("50000.00"), // sumInsured
            null, // commissionPer
            null); // commissionAmt

    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/policies",
            HttpMethod.POST,
            entity(missingCif, "REQ-INVALID-1", "TXN-INVALID-1"),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    assertThat(response.getBody().getDetail()).contains("cif");
  }

  @Test
  void concurrentRetriesWithTheSameReqIdBothReturnTheWinnersTxnId() throws Exception {
    String reqId = "REQ-RACE-1";
    String policyNum = "POL-RACE-1";
    CreatePolicyCommand cmdA = command(policyNum, reqId, "TXN-RACE-A");
    CreatePolicyCommand cmdB = command(policyNum, reqId, "TXN-RACE-B");

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);
    AtomicReference<CreatePolicyResult> resultA = new AtomicReference<>();
    AtomicReference<CreatePolicyResult> resultB = new AtomicReference<>();
    AtomicReference<Throwable> errorA = new AtomicReference<>();
    AtomicReference<Throwable> errorB = new AtomicReference<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);

    pool.submit(
        () -> race(ready, start, done, () -> policyCreationService.create(cmdA), resultA, errorA));
    pool.submit(
        () -> race(ready, start, done, () -> policyCreationService.create(cmdB), resultB, errorB));
    ready.await(5, TimeUnit.SECONDS);
    start.countDown();
    boolean finished = done.await(10, TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(finished).isTrue();
    assertThat(errorA.get()).isNull();
    assertThat(errorB.get()).isNull();
    assertThat(resultA.get().txnId()).isEqualTo(resultB.get().txnId());
    assertThat(processedRequestJpaRepository.findByInspIdAndReqId(INSP_ID, reqId))
        .hasValueSatisfying(pr -> assertThat(pr.getTxnId()).isEqualTo(resultA.get().txnId()));
  }

  @Test
  void concurrentDifferentReqIdsOnTheSamePolicyNumTheLoserGetsPolicyAlreadyExists()
      throws Exception {
    String policyNum = "POL-RACE-2";
    CreatePolicyCommand cmdA = command(policyNum, "REQ-RACE-2A", "TXN-RACE-2A");
    CreatePolicyCommand cmdB = command(policyNum, "REQ-RACE-2B", "TXN-RACE-2B");

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);
    AtomicReference<CreatePolicyResult> resultA = new AtomicReference<>();
    AtomicReference<CreatePolicyResult> resultB = new AtomicReference<>();
    AtomicReference<Throwable> errorA = new AtomicReference<>();
    AtomicReference<Throwable> errorB = new AtomicReference<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);

    pool.submit(
        () -> race(ready, start, done, () -> policyCreationService.create(cmdA), resultA, errorA));
    pool.submit(
        () -> race(ready, start, done, () -> policyCreationService.create(cmdB), resultB, errorB));
    ready.await(5, TimeUnit.SECONDS);
    start.countDown();
    boolean finished = done.await(10, TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(finished).isTrue();
    // Exactly one thread succeeds, the other gets a real HubBusinessException - never a 500-
    // shaped unchecked exception, and never a silently-wrong "replay" of someone else's txnId.
    boolean aSucceeded = errorA.get() == null;
    boolean bSucceeded = errorB.get() == null;
    assertThat(aSucceeded).isNotEqualTo(bSucceeded);

    Throwable loserError = aSucceeded ? errorB.get() : errorA.get();
    CreatePolicyResult winnerResult = aSucceeded ? resultA.get() : resultB.get();
    assertThat(winnerResult.replayed()).isFalse();
    assertThat(loserError)
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.POLICY_ALREADY_EXISTS));
  }

  private void race(
      CountDownLatch ready,
      CountDownLatch start,
      CountDownLatch done,
      java.util.function.Supplier<CreatePolicyResult> action,
      AtomicReference<CreatePolicyResult> result,
      AtomicReference<Throwable> error) {
    try {
      ready.countDown();
      start.await(5, TimeUnit.SECONDS);
      result.set(action.get());
    } catch (Throwable t) {
      error.set(t);
    } finally {
      done.countDown();
    }
  }

  private ResponseEntity<CreatePolicyResponse> post(
      CreatePolicyRequest request, String reqId, String txnId) {
    return restTemplate.exchange(
        "/internal/policies",
        HttpMethod.POST,
        entity(request, reqId, txnId),
        CreatePolicyResponse.class);
  }

  private HttpEntity<CreatePolicyRequest> entity(
      CreatePolicyRequest request, String reqId, String txnId) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HubHeaders.REQ_ID, reqId);
    headers.set(HubHeaders.INSP_ID, INSP_ID);
    headers.set(HubHeaders.TXN_ID, txnId);
    headers.set(HubHeaders.INTERNAL_AUTH, "local-dev-internal-secret-CHANGE-ME");
    return new HttpEntity<>(request, headers);
  }

  private static CreatePolicyRequest validRequest(String policyNum) {
    return new CreatePolicyRequest(
        policyNum,
        "APP1",
        "CIF456789",
        "ACC99887766",
        "Ravi Kumar",
        "9876543210",
        "12, MG Road, Chennai",
        "GENERAL",
        "Motor Insurance",
        "RC01",
        "South Region",
        "BR102",
        "Chennai Main Branch",
        "LN22334455",
        "SP7890",
        "Agent Suresh",
        "ACTIVE",
        LocalDate.of(2025, 12, 25),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1),
        new BigDecimal("15000.00"),
        new BigDecimal("2700.00"),
        new BigDecimal("17700.00"),
        new BigDecimal("500000.00"),
        new BigDecimal("5.00"),
        new BigDecimal("750.00"));
  }

  private static CreatePolicyCommand command(String policyNum, String reqId, String txnId) {
    return new CreatePolicyCommand(
        reqId,
        INSP_ID,
        txnId,
        null, // traceparent
        policyNum,
        "APP1",
        "CIF456789",
        "ACC99887766",
        "Ravi Kumar",
        "9876543210",
        "12, MG Road, Chennai",
        "GENERAL",
        "Motor Insurance",
        "RC01",
        "South Region",
        "BR102",
        "Chennai Main Branch",
        "LN22334455",
        "SP7890",
        "Agent Suresh",
        "ACTIVE",
        LocalDate.of(2025, 12, 25),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1),
        new BigDecimal("15000.00"),
        new BigDecimal("2700.00"),
        new BigDecimal("17700.00"),
        new BigDecimal("500000.00"),
        new BigDecimal("5.00"),
        new BigDecimal("750.00"));
  }
}
