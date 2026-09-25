package com.insurancehub.policy.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.common.web.HubHeaders;
import com.insurancehub.policy.infrastructure.persistence.PolicyJpaRepository;
import com.insurancehub.policy.infrastructure.persistence.PolicyTermJpaRepository;
import com.insurancehub.policy.infrastructure.persistence.ProcessedRequestJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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

// Real MySQL, not H2. Each test uses its own policyNum so tests sharing the one Testcontainer
// don't collide.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class PolicyRenewalIT {

  private static final String INSP_ID = "INSP001";

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private PolicyJpaRepository policyJpaRepository;
  @Autowired private PolicyTermJpaRepository policyTermJpaRepository;
  @Autowired private ProcessedRequestJpaRepository processedRequestJpaRepository;

  @Test
  void happyPathAddsARenewalTermAndReturns201() {
    createPolicy(
        "POL-REN-1",
        "REQ-REN-1-CREATE",
        "TXN-REN-1-CREATE",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1));

    ResponseEntity<RenewPolicyResponse> response =
        renew(
            "POL-REN-1",
            renewalRequest(
                "Ravi Kumar Updated", LocalDate.of(2027, 2, 1), LocalDate.of(2028, 2, 1)),
            "REQ-REN-1",
            "TXN-REN-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().replayed()).isFalse();
    assertThat(response.getBody().termNo()).isEqualTo(2);
    assertThat(response.getBody().txnId()).isEqualTo("TXN-REN-1");
    assertThat(policyTermJpaRepository.findByPolicyPolicyNum("POL-REN-1")).hasSize(2);
    assertThat(policyJpaRepository.findByPolicyNum("POL-REN-1"))
        .hasValueSatisfying(p -> assertThat(p.getInsuredName()).isEqualTo("Ravi Kumar Updated"));
  }

  @Test
  void replayReturnsOriginalTxnIdAndTermNoWithNoNewRows() {
    createPolicy(
        "POL-REN-REPLAY-1",
        "REQ-REN-REPLAY-CREATE",
        "TXN-REN-REPLAY-CREATE",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1));
    ResponseEntity<RenewPolicyResponse> first =
        renew(
            "POL-REN-REPLAY-1",
            renewalRequest("Ravi Kumar", LocalDate.of(2027, 2, 1), LocalDate.of(2028, 2, 1)),
            "REQ-REN-REPLAY-1",
            "TXN-REN-REPLAY-A");
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    long termRowsBefore = policyTermJpaRepository.findByPolicyPolicyNum("POL-REN-REPLAY-1").size();
    long processedRequestCountBefore = processedRequestJpaRepository.count();

    ResponseEntity<RenewPolicyResponse> replay =
        renew(
            "POL-REN-REPLAY-1",
            renewalRequest("Ravi Kumar", LocalDate.of(2027, 2, 1), LocalDate.of(2028, 2, 1)),
            "REQ-REN-REPLAY-1",
            "TXN-REN-REPLAY-B");

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody().replayed()).isTrue();
    assertThat(replay.getBody().txnId()).isEqualTo("TXN-REN-REPLAY-A");
    assertThat(replay.getBody().termNo()).isEqualTo(2);
    assertThat(policyTermJpaRepository.findByPolicyPolicyNum("POL-REN-REPLAY-1"))
        .hasSize((int) termRowsBefore);
    assertThat(processedRequestJpaRepository.count()).isEqualTo(processedRequestCountBefore);
  }

  @Test
  void overlappingTermIsRejected() {
    createPolicy(
        "POL-REN-OVERLAP-1",
        "REQ-REN-OVERLAP-CREATE",
        "TXN-REN-OVERLAP-CREATE",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1));

    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/policies/POL-REN-OVERLAP-1/renewals",
            HttpMethod.POST,
            entity(
                renewalRequest("Ravi Kumar", LocalDate.of(2026, 6, 1), LocalDate.of(2027, 6, 1)),
                "REQ-REN-OVERLAP-1",
                "TXN-REN-OVERLAP-1"),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(response.getBody().getProperties()).containsEntry("code", "RENEWAL_NOT_ALLOWED");
  }

  @Test
  void unknownPolicyIsRejected() {
    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/policies/POL-DOES-NOT-EXIST/renewals",
            HttpMethod.POST,
            entity(
                renewalRequest("Ravi Kumar", LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 1)),
                "REQ-REN-UNKNOWN-1",
                "TXN-REN-UNKNOWN-1"),
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getProperties()).containsEntry("code", "POLICY_NOT_FOUND");
  }

  private ResponseEntity<RenewPolicyResponse> renew(
      String policyNum, RenewPolicyRequest request, String reqId, String txnId) {
    return restTemplate.exchange(
        "/internal/policies/" + policyNum + "/renewals",
        HttpMethod.POST,
        entity(request, reqId, txnId),
        RenewPolicyResponse.class);
  }

  private void createPolicy(
      String policyNum, String reqId, String txnId, LocalDate startDate, LocalDate expiryDate) {
    CreatePolicyRequest request =
        new CreatePolicyRequest(
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
            startDate,
            expiryDate,
            new BigDecimal("15000.00"),
            new BigDecimal("2700.00"),
            new BigDecimal("17700.00"),
            new BigDecimal("500000.00"),
            new BigDecimal("5.00"),
            new BigDecimal("750.00"));
    ResponseEntity<CreatePolicyResponse> response =
        restTemplate.exchange(
            "/internal/policies",
            HttpMethod.POST,
            entity(request, reqId, txnId),
            CreatePolicyResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private static RenewPolicyRequest renewalRequest(
      String insuredName, LocalDate startDate, LocalDate expiryDate) {
    return new RenewPolicyRequest(
        "APP1",
        "CIF456789",
        "ACC99887766",
        insuredName,
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
        LocalDate.of(2027, 1, 20),
        startDate,
        expiryDate,
        new BigDecimal("16000.00"),
        new BigDecimal("2880.00"),
        new BigDecimal("18880.00"),
        new BigDecimal("550000.00"),
        new BigDecimal("5.00"),
        new BigDecimal("800.00"));
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
