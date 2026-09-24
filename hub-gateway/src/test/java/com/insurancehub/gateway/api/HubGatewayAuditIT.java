package com.insurancehub.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.AbstractHubGatewayIT;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import com.insurancehub.gateway.domain.RequestAudit;
import com.insurancehub.gateway.infrastructure.persistence.RequestAuditJpaRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

// request_audit gets exactly one row per request that reaches the gateway - success, business
// rejection, or pre-trust rejection - never a payload column (RequestAuditTest proves the field
// list; this proves the runtime write, and that real PII never lands in any column).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewayAuditIT extends AbstractHubGatewayIT {

  // Spring's relaxed binder picks ONE property source as authoritative for the whole
  // hub.insurers list (see HubGatewaySecurityIT) - narrowing just INSP003's CIDR means
  // restating all three insurers here, not adding one key.
  @DynamicPropertySource
  static void narrowInsp003Allowlist(DynamicPropertyRegistry registry) {
    registry.add("hub.insurers[0].insp-id", () -> "INSP001");
    registry.add("hub.insurers[0].insp-name", () -> "universalsompo");
    registry.add("hub.insurers[0].oauth-client-id", () -> "insp001-client");
    registry.add("hub.insurers[0].allowed-cidrs[0]", () -> "0.0.0.0/0");
    registry.add("hub.insurers[1].insp-id", () -> "INSP002");
    registry.add("hub.insurers[1].insp-name", () -> "sbigeneral");
    registry.add("hub.insurers[1].oauth-client-id", () -> "insp002-client");
    registry.add("hub.insurers[1].allowed-cidrs[0]", () -> "0.0.0.0/0");
    registry.add("hub.insurers[2].insp-id", () -> "INSP003");
    registry.add("hub.insurers[2].insp-name", () -> "nivabupa");
    registry.add("hub.insurers[2].oauth-client-id", () -> "insp003-client");
    registry.add("hub.insurers[2].allowed-cidrs[0]", () -> "10.99.99.0/24");
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RequestAuditJpaRepository auditRepository;

  @BeforeEach
  void resetWireMockAndAudit() {
    POLICY_SERVICE.resetAll();
    auditRepository.deleteAll();
  }

  @Test
  void aSuccessfulRequestWritesOneRowWithTheFullContext() {
    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"TXN-AUDIT-1\",\"replayed\":false,\"policyNum\":\"POL1\"}")));

    var response = submitNewPolicy("REQ-AUDIT-1");
    assertThat(response.getStatusCode().value()).isEqualTo(200);

    List<RequestAudit> rows = auditRepository.findAll();
    assertThat(rows).hasSize(1);
    RequestAudit row = rows.get(0);
    assertThat(row.getTxnId()).isEqualTo("TXN-AUDIT-1");
    assertThat(row.getReqId()).isEqualTo("REQ-AUDIT-1");
    assertThat(row.getInspId()).isEqualTo("INSP001");
    assertThat(row.getServiceType()).isEqualTo("NewPolicyService");
    assertThat(row.getRespCode()).isEqualTo("200");
    assertThat(row.getLatencyMs()).isGreaterThanOrEqualTo(0);
    assertThat(row.getClientIp()).isNotBlank();
  }

  @Test
  void aBusinessRejectionStillWritesARowWithTheRealRespCode() {
    var response = submitNewPolicy("REQ-AUDIT-2", withCif(validPolicyDetails(), ""));
    assertThat(response.getStatusCode().value()).isEqualTo(400);

    List<RequestAudit> rows = auditRepository.findAll();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getRespCode()).isEqualTo("400");
    assertThat(rows.get(0).getReqId()).isEqualTo("REQ-AUDIT-2");
  }

  @Test
  void aPreTrustRejectionStillWritesARowWithNullReqIdInspIdAndServiceType() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail",
            HttpMethod.POST,
            new HttpEntity<>("{\"enc\":\"irrelevant\"}", jsonHeadersNoAuth()),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(401);

    List<RequestAudit> rows = auditRepository.findAll();
    assertThat(rows).hasSize(1);
    RequestAudit row = rows.get(0);
    assertThat(row.getRespCode()).isEqualTo("401");
    assertThat(row.getReqId()).isNull();
    assertThat(row.getInspId()).isNull();
    assertThat(row.getServiceType()).isNull();
    assertThat(row.getTxnId()).isNotBlank();
  }

  @Test
  void anIpNotAllowedRejectionWritesARowWithInspIdButNoReqIdOrServiceType() {
    String token = fetchToken("insp003-client", "insp003-secret");
    HttpHeaders headers = jsonHeadersNoAuth();
    headers.setBearerAuth(token);

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail",
            HttpMethod.POST,
            new HttpEntity<>("{\"enc\":\"irrelevant\"}", headers),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(403);

    List<RequestAudit> rows = auditRepository.findAll();
    assertThat(rows).hasSize(1);
    RequestAudit row = rows.get(0);
    // INSP003's allowlist isn't narrowed in this class (unlike HubGatewaySecurityIT) so this
    // only proves insp_id resolves before the IP check runs when the token itself is fine;
    // the real "narrowed to 403" scenario is HubGatewaySecurityIT's own concern. Here the token
    // is valid and scoped, so if it fails it's on IP, and insp_id must already be populated.
    assertThat(row.getInspId()).isEqualTo("INSP003");
    assertThat(row.getReqId()).isNull();
    assertThat(row.getServiceType()).isNull();
  }

  @Test
  void noRealPiiValueEverAppearsInAnyAuditColumn() {
    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"TXN-PII\",\"replayed\":false,\"policyNum\":\"POL-PII\"}")));
    RawPolicyDetails withPii =
        new RawPolicyDetails(
            "RC01",
            "South Region",
            "BR102",
            "Chennai Main Branch",
            "CIF-SECRET-987",
            "ACC99887766",
            "GENERAL",
            "Motor Insurance",
            "APP112233",
            "POL-PII",
            "Confidential Person Name",
            "9998887776",
            "42 Secret Street, Nowhere",
            "ACTIVE",
            "10/05/2024",
            "15/05/2024",
            "14/05/2025",
            "15000",
            "2700",
            "17700",
            "500000",
            "LN22334455",
            "SP7890",
            "Agent Suresh",
            "5",
            "750");

    submitNewPolicy("REQ-PII", withPii);

    RequestAudit row = auditRepository.findAll().get(0);
    String[] piiValues = {
      "CIF-SECRET-987", "Confidential Person Name", "9998887776", "42 Secret Street, Nowhere"
    };
    for (String pii : piiValues) {
      assertThat(row.getTxnId()).doesNotContain(pii);
      assertThat(row.getReqId()).doesNotContain(pii);
      assertThat(String.valueOf(row.getInspId())).doesNotContain(pii);
      assertThat(String.valueOf(row.getServiceType())).doesNotContain(pii);
      assertThat(row.getRespCode()).doesNotContain(pii);
      assertThat(row.getClientIp()).doesNotContain(pii);
    }
  }

  private static RawPolicyDetails validPolicyDetails() {
    return new RawPolicyDetails(
        "RC01",
        "South Region",
        "BR102",
        "Chennai Main Branch",
        "CIF456789",
        "ACC99887766",
        "GENERAL",
        "Motor Insurance",
        "APP112233",
        "POL445566",
        "Ravi Kumar",
        "9876543210",
        "12, MG Road, Chennai, TN",
        "ACTIVE",
        "10/05/2024",
        "15/05/2024",
        "14/05/2025",
        "15000",
        "2700",
        "17700",
        "500000",
        "LN22334455",
        "SP7890",
        "Agent Suresh",
        "5",
        "750");
  }

  private static RawPolicyDetails withCif(RawPolicyDetails d, String cif) {
    return new RawPolicyDetails(
        d.regionCode(),
        d.regionName(),
        d.branchCode(),
        d.branchName(),
        cif,
        d.accountNum(),
        d.insuranceType(),
        d.insuranceName(),
        d.applicationNum(),
        d.policyNum(),
        d.name(),
        d.mobileNum(),
        d.address(),
        d.applicationStatus(),
        d.issueDate(),
        d.startDate(),
        d.expiryDate(),
        d.netPremium(),
        d.gstAmt(),
        d.grossPremium(),
        d.sumInsured(),
        d.loanAcctNum(),
        d.specPerNum(),
        d.specPerName(),
        d.commissionPer(),
        d.commissionAmt());
  }

  private ResponseEntity<String> submitNewPolicy(String reqId) {
    return submitNewPolicy(reqId, validPolicyDetails());
  }

  private ResponseEntity<String> submitNewPolicy(String reqId, RawPolicyDetails details) {
    var body =
        new RawHubRequestBody(
            new RawHeader(reqId, "NewPolicyService", "01", "INSP001", "universalsompo"),
            details,
            null);
    String json = objectMapper.writeValueAsString(body);
    String envelope = objectMapper.writeValueAsString(Map.of("enc", json));
    HttpHeaders headers = jsonHeadersNoAuth();
    headers.setBearerAuth(fetchToken("insp001-client", "insp001-secret"));
    return restTemplate.exchange(
        "/v1/policydetail", HttpMethod.POST, new HttpEntity<>(envelope, headers), String.class);
  }

  private static HttpHeaders jsonHeadersNoAuth() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
