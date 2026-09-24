package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.AbstractHubGatewayCryptoIT;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import com.insurancehub.gateway.domain.RequestAudit;
import com.insurancehub.gateway.infrastructure.persistence.RequestAuditJpaRepository;
import com.insurancehub.gateway.testsupport.SampleEnvelopeCodec;
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
import tools.jackson.databind.ObjectMapper;

// A crypto rejection still writes exactly one request_audit row (RequestAuditFilter/ADR-0005,
// unchanged from phase 5 - crypto failures are pre-trust rejections like any other).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewayCryptoAuditIT extends AbstractHubGatewayCryptoIT {

  private static final String INSP_ID = "INSP001";

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RequestAuditJpaRepository auditRepository;

  @BeforeEach
  void resetAudit() {
    auditRepository.deleteAll();
  }

  @Test
  void aTamperedCiphertextRequestWritesOneAuditRow() throws Exception {
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ-AUDIT-CRYPTO", "NewPolicyService", "01", INSP_ID, "universalsompo"),
            validPolicyDetails(),
            null);
    String json = objectMapper.writeValueAsString(body);
    String enc =
        SampleEnvelopeCodec.encryptAndSign(
            json, bankPublicKey(), insurerPrivateKey(INSP_ID), INSP_ID);
    // Corrupt well inside the outer Base64 - guaranteed to break decode/parse, still a clean
    // pre-trust rejection (not a 500), regardless of exactly which crypto code it maps to.
    char[] chars = enc.toCharArray();
    chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
    String tampered = new String(chars);

    var response = submit(tampered);

    List<RequestAudit> rows = auditRepository.findAll();
    assertThat(rows).hasSize(1);
    RequestAudit row = rows.get(0);
    assertThat(row.getRespCode()).isEqualTo(String.valueOf(response.getStatusCode().value()));
    assertThat(row.getReqId()).isNull();
    assertThat(row.getServiceType()).isNull();
    assertThat(row.getTxnId()).isNotBlank();
  }

  @Test
  void aWrongSignerRequestWritesOneAuditRow() throws Exception {
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ-AUDIT-SIG", "NewPolicyService", "01", INSP_ID, "universalsompo"),
            validPolicyDetails(),
            null);
    String json = objectMapper.writeValueAsString(body);
    String enc =
        SampleEnvelopeCodec.encryptAndSign(
            json, bankPublicKey(), insurerPrivateKey("INSP002"), "INSP002");

    var response = submit(enc);

    List<RequestAudit> rows = auditRepository.findAll();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getRespCode())
        .isEqualTo(String.valueOf(response.getStatusCode().value()));
    assertThat(rows.get(0).getReqId()).isNull();
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
        "POL1",
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

  private org.springframework.http.ResponseEntity<String> submit(String enc) {
    String envelope = objectMapper.writeValueAsString(Map.of("enc", enc));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(fetchToken("insp001-client", "insp001-secret"));
    return restTemplate.exchange(
        "/v1/policydetail", HttpMethod.POST, new HttpEntity<>(envelope, headers), String.class);
  }
}
