package com.insurancehub.policy.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.common.web.HubHeaders;
import com.insurancehub.policy.api.CreatePolicyRequest;
import com.insurancehub.policy.api.CreatePolicyResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// logging-and-monitoring.md §10's own suggested test shape: capture real console output (JSON,
// via the forced logstash format below - the same format the json-logs profile turns on) for a
// real business call carrying real-looking PII, and assert the business-event log line
// (PolicyCreationService's own "policy created" line) contains txnId but never the raw PII
// values from the fixture.
//
// Goes over real HTTP (TestRestTemplate), not a direct service call - MDC's txnId/reqId/inspId
// are populated by hub-common's CorrelationFilter, a servlet filter that only runs for a real
// HTTP request. A direct bean-to-bean call (as OutboxAppenderIT deliberately does, for precise
// transaction control) bypasses that filter entirely, so txnId would never reach MDC or the log
// line - found by this test itself initially failing that way, not assumed.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "logging.structured.format.console=logstash")
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
class PolicyCreationLoggingIT {

  private static final String INSP_ID = "INSP001";
  private static final String CIF = "CIF998877665";
  private static final String MOBILE_NUM = "9123456780";
  private static final String INSURED_NAME = "Priya Sharma";
  private static final String ADDRESS = "42, Anna Salai, Chennai, TN";
  private static final String ACCOUNT_NUM = "ACC11223344";

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void businessEventLogLineCarriesTxnIdButNeverTheRawPiiFixtureValues(CapturedOutput output) {
    String txnId = "TXN-LOGGING-CAPTURE-1";
    CreatePolicyRequest request =
        new CreatePolicyRequest(
            "POL-LOGGING-CAPTURE-1",
            "APP1",
            CIF,
            ACCOUNT_NUM,
            INSURED_NAME,
            MOBILE_NUM,
            ADDRESS,
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
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2027, 1, 1),
            new BigDecimal("15000.00"),
            new BigDecimal("2700.00"),
            new BigDecimal("17700.00"),
            new BigDecimal("500000.00"),
            new BigDecimal("5.00"),
            new BigDecimal("750.00"));

    HttpHeaders headers = new HttpHeaders();
    headers.set(HubHeaders.REQ_ID, "REQ-LOGGING-CAPTURE-1");
    headers.set(HubHeaders.INSP_ID, INSP_ID);
    headers.set(HubHeaders.TXN_ID, txnId);
    headers.set(HubHeaders.INTERNAL_AUTH, "local-dev-internal-secret-CHANGE-ME");
    ResponseEntity<CreatePolicyResponse> response =
        restTemplate.exchange(
            "/internal/policies",
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            CreatePolicyResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String captured = output.getOut();
    assertThat(captured).contains(txnId).contains("POLICY_CREATED");
    assertThat(captured)
        .doesNotContain(CIF)
        .doesNotContain(MOBILE_NUM)
        .doesNotContain(INSURED_NAME)
        .doesNotContain(ADDRESS)
        .doesNotContain(ACCOUNT_NUM);
  }
}
