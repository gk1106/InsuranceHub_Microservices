package com.insurancehub.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.insurancehub.gateway.AbstractHubGatewayIT;
import com.insurancehub.gateway.application.AuditRepository;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

// A separate class from HubGatewayAuditIT (its own Spring context, since @MockitoBean overrides
// the AuditRepository bean for the whole context) - proves that an audit write failing after a
// successful downstream call still returns the real, correct business response to the client,
// and increments the failure counter, per RequestAuditFilter's finally-block design.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewayAuditWriteFailureIT extends AbstractHubGatewayIT {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MeterRegistry meterRegistry;

  @MockitoBean private AuditRepository auditRepository;

  @BeforeEach
  void resetWireMockAndStubAuditFailure() {
    POLICY_SERVICE.resetAll();
    when(auditRepository.save(any())).thenThrow(new RuntimeException("db is down"));
  }

  @Test
  void aFailingAuditWriteStillReturnsTheRealResponseAndIncrementsTheFailureCounter() {
    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"TXN-AWF-1\",\"replayed\":false,\"policyNum\":\"POL1\"}")));
    double before = counterValue();

    var response = submitNewPolicy("REQ-AWF-1");

    // The real business response - the audit write failing later, in the filter's finally
    // block, happens strictly after the response is already committed.
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    var body = decrypt(response.getBody());
    assertThat(body.get("respCode")).isEqualTo("200");
    assertThat(body.get("txnId")).isEqualTo("TXN-AWF-1");

    assertThat(counterValue()).isEqualTo(before + 1.0);
  }

  private double counterValue() {
    var counter = meterRegistry.find("hub_gateway.audit.write.failures").counter();
    return counter == null ? 0.0 : counter.count();
  }

  private ResponseEntity<String> submitNewPolicy(String reqId) {
    var body =
        new RawHubRequestBody(
            new RawHeader(reqId, "NewPolicyService", "01", "INSP001", "universalsompo"),
            validPolicyDetails(),
            null);
    String json = objectMapper.writeValueAsString(body);
    String envelope = objectMapper.writeValueAsString(Map.of("enc", json));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(fetchToken("insp001-client", "insp001-secret"));
    return restTemplate.exchange(
        "/v1/policydetail", HttpMethod.POST, new HttpEntity<>(envelope, headers), String.class);
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> decrypt(String responseBody) {
    var wrapper = objectMapper.readValue(responseBody, Map.class);
    return objectMapper.readValue((String) wrapper.get("enc"), Map.class);
  }
}
