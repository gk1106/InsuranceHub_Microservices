package com.insurancehub.policy.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.common.web.HubHeaders;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

// Real MySQL, not H2. Each test uses its own policyNum so tests sharing the one Testcontainer
// don't collide.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class PolicyCoverageIT {

  private static final String INSP_ID = "INSP001";

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void coverageIsActiveOnADateInsideATerm() {
    createPolicy(
        "POL-COV-1",
        "REQ-COV-1-CREATE",
        "TXN-COV-1-CREATE",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1));

    ResponseEntity<CoverageResponse> response = coverage("POL-COV-1", LocalDate.of(2026, 6, 15));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    CoverageResponse body = response.getBody();
    assertThat(body.active()).isTrue();
    assertThat(body.termStart()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(body.termExpiry()).isEqualTo(LocalDate.of(2027, 1, 1));
    assertThat(body.sumInsured()).isEqualByComparingTo(new BigDecimal("500000.00"));
    assertThat(body.insuranceType()).isEqualTo("GENERAL");
  }

  @Test
  void coverageIsInactiveInAGapBetweenTerms() {
    createPolicy(
        "POL-COV-GAP-1",
        "REQ-COV-GAP-CREATE",
        "TXN-COV-GAP-CREATE",
        LocalDate.of(2025, 1, 1),
        LocalDate.of(2025, 12, 31));
    renew(
        "POL-COV-GAP-1",
        LocalDate.of(2026, 2, 1),
        LocalDate.of(2026, 12, 31),
        "REQ-COV-GAP-RENEW",
        "TXN-COV-GAP-RENEW");

    // 2026-01-15 falls between term 1's expiry (2025-12-31) and term 2's start (2026-02-01).
    ResponseEntity<CoverageResponse> response =
        coverage("POL-COV-GAP-1", LocalDate.of(2026, 1, 15));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    CoverageResponse body = response.getBody();
    assertThat(body.active()).isFalse();
    assertThat(body.termStart()).isNull();
    assertThat(body.termExpiry()).isNull();
    assertThat(body.sumInsured()).isNull();
    assertThat(body.insuranceType()).isEqualTo("GENERAL");
  }

  @Test
  void coverageForAnUnknownPolicyIs404() {
    ResponseEntity<ProblemDetail> response =
        restTemplate.exchange(
            "/internal/policies/POL-COV-DOES-NOT-EXIST/coverage?onDate=2026-01-01",
            HttpMethod.GET,
            null,
            ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getProperties()).containsEntry("code", "POLICY_NOT_FOUND");
  }

  @Test
  void multiTermPolicyMatchesTheEarlierTermWhenTheDateFallsInsideIt() {
    createPolicy(
        "POL-COV-MULTI-1",
        "REQ-COV-MULTI-CREATE",
        "TXN-COV-MULTI-CREATE",
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2024, 12, 31));
    renew(
        "POL-COV-MULTI-1",
        LocalDate.of(2025, 1, 1),
        LocalDate.of(2025, 12, 31),
        "REQ-COV-MULTI-RENEW",
        "TXN-COV-MULTI-RENEW");

    // onDate falls inside term 1 even though term 2 also exists by now.
    ResponseEntity<CoverageResponse> response =
        coverage("POL-COV-MULTI-1", LocalDate.of(2024, 6, 15));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    CoverageResponse body = response.getBody();
    assertThat(body.active()).isTrue();
    assertThat(body.termStart()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(body.termExpiry()).isEqualTo(LocalDate.of(2024, 12, 31));
  }

  @Test
  void termBoundariesAreInclusiveOnBothEndsOverTheWire() {
    // A claim filed exactly on the term's start or expiry date is an ordinary, valid date of
    // loss (phase 4 calls this endpoint with dateOfLoss) - an off-by-one here would silently
    // reject a legitimate claim.
    LocalDate termStart = LocalDate.of(2026, 1, 1);
    LocalDate termExpiry = LocalDate.of(2026, 12, 31);
    createPolicy(
        "POL-COV-BOUNDARY-1",
        "REQ-COV-BOUNDARY-CREATE",
        "TXN-COV-BOUNDARY-CREATE",
        termStart,
        termExpiry);

    assertThat(coverage("POL-COV-BOUNDARY-1", termStart).getBody().active())
        .as("exact start date is covered")
        .isTrue();
    assertThat(coverage("POL-COV-BOUNDARY-1", termExpiry).getBody().active())
        .as("exact expiry date is covered")
        .isTrue();
    assertThat(coverage("POL-COV-BOUNDARY-1", termStart.minusDays(1)).getBody().active())
        .as("one day before start is not covered")
        .isFalse();
    assertThat(coverage("POL-COV-BOUNDARY-1", termExpiry.plusDays(1)).getBody().active())
        .as("one day after expiry is not covered")
        .isFalse();
  }

  @Test
  void coverageResponseContainsExactlyTheDocumentedFields() throws Exception {
    // service-design.md §2: coverage response is exactly {policyNum, active, termStart,
    // termExpiry, sumInsured, insuranceType} - no insured name, mobile, CIF or account number.
    // Claims doesn't need them, so it doesn't get them. Asserting the exact key set (not just
    // that the documented fields are present) is what stops a later phase from "helpfully"
    // adding one of those fields back in because it was convenient.
    createPolicy(
        "POL-COV-CONTRACT-1",
        "REQ-COV-CONTRACT-CREATE",
        "TXN-COV-CONTRACT-CREATE",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1));

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/internal/policies/POL-COV-CONTRACT-1/coverage?onDate=2026-06-15",
            HttpMethod.GET,
            null,
            String.class);

    Map<String, Object> body = objectMapper.readValue(response.getBody(), Map.class);
    assertThat(body.keySet())
        .isEqualTo(
            Set.of(
                "policyNum", "active", "termStart", "termExpiry", "sumInsured", "insuranceType"));
  }

  private ResponseEntity<CoverageResponse> coverage(String policyNum, LocalDate onDate) {
    return restTemplate.exchange(
        "/internal/policies/" + policyNum + "/coverage?onDate=" + onDate,
        HttpMethod.GET,
        null,
        CoverageResponse.class);
  }

  private void renew(
      String policyNum, LocalDate startDate, LocalDate expiryDate, String reqId, String txnId) {
    RenewPolicyRequest request =
        new RenewPolicyRequest(
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
            startDate.minusDays(10),
            startDate,
            expiryDate,
            new BigDecimal("16000.00"),
            new BigDecimal("2880.00"),
            new BigDecimal("18880.00"),
            new BigDecimal("550000.00"),
            new BigDecimal("5.00"),
            new BigDecimal("800.00"));
    ResponseEntity<RenewPolicyResponse> response =
        restTemplate.exchange(
            "/internal/policies/" + policyNum + "/renewals",
            HttpMethod.POST,
            entity(request, reqId, txnId),
            RenewPolicyResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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
            LocalDate.of(2023, 12, 25),
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

  private <T> HttpEntity<T> entity(T body, String reqId, String txnId) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HubHeaders.REQ_ID, reqId);
    headers.set(HubHeaders.INSP_ID, INSP_ID);
    headers.set(HubHeaders.TXN_ID, txnId);
    return new HttpEntity<>(body, headers);
  }
}
