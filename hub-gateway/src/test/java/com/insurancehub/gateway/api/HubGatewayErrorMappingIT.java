package com.insurancehub.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.AbstractHubGatewayIT;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

// Every downstream ProblemDetail code flows through DownstreamErrorDecoder unchanged - proves
// the mapping is data-driven (reads the "code" property), not a hand-written per-status switch
// that could silently miss one. Also proves the replay txnId rule: the RESPONSE carries the
// downstream's ORIGINAL txnId, which can legitimately differ from what the gateway itself sent.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewayErrorMappingIT extends AbstractHubGatewayIT {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void resetWireMock() {
    POLICY_SERVICE.resetAll();
  }

  @Test
  void policyAlreadyExistsFlowsThroughUnchanged() {
    // The downstream's own "detail" text is deliberately irrelevant here: every code except
    // VALIDATION_FAILED always surfaces the catalogue's own fixed errorDesc externally
    // (HubController), never a downstream-supplied string - api-contract.md §5's table is fixed
    // text, not a template, for every code but that one.
    stubCreatePolicyError(409, "POLICY_ALREADY_EXISTS", "POL445566");

    var response = submitNewPolicy("REQ-ERR-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    var body = decrypt(response.getBody());
    assertThat(body.get("respCode")).isEqualTo("409");
    assertThat(body.get("errorDesc")).isEqualTo("Policy already exists");
  }

  @Test
  void concurrentUpdateFlowsThroughUnchanged() {
    stubCreatePolicyError(409, "CONCURRENT_UPDATE", "Concurrent update, retry the request");

    var response = submitNewPolicy("REQ-ERR-2");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(decrypt(response.getBody()).get("respCode")).isEqualTo("409");
  }

  @Test
  void internalErrorFromADownstreamServiceFlowsThroughUnchanged() {
    stubCreatePolicyError(500, "INTERNAL_ERROR", "Internal error");

    var response = submitNewPolicy("REQ-ERR-3");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(decrypt(response.getBody()).get("respCode")).isEqualTo("500");
  }

  @Test
  void aDownstream503WithDownstreamUnavailableCodeFlowsThroughUnchanged() {
    // e.g. claims-service reporting that ITS OWN call to policy-service failed.
    stubCreatePolicyError(503, "DOWNSTREAM_UNAVAILABLE", "Service temporarily unavailable");

    var response = submitNewPolicy("REQ-ERR-4");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(decrypt(response.getBody()).get("respCode")).isEqualTo("503");
  }

  @Test
  void replayReturnsTheOriginalTxnIdWhichCanDifferFromWhatTheGatewaySent() {
    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"ORIGINAL-TXN\",\"replayed\":true,\"policyNum\":\"POL-REPLAY\"}")));

    var response = submitNewPolicy("REQ-REPLAY");

    var body = decrypt(response.getBody());
    assertThat(body.get("txnId")).isEqualTo("ORIGINAL-TXN");

    // What the gateway actually sent as X-Txn-Id is a freshly-generated value, captured via
    // WireMock's own request log - proving the two are allowed to differ, not asserting a
    // specific generated value. POLICY_SERVICE.findAll(...), not the static WireMock.findAll(...)
    // helper - the static helper talks to a default admin client (localhost:8080) that has
    // nothing to do with this server's actual (random) port.
    var sentTxnIds =
        POLICY_SERVICE.findAll(postRequestedFor(urlPathEqualTo("/internal/policies"))).stream()
            .map(r -> r.getHeader("X-Txn-Id"))
            .toList();
    assertThat(sentTxnIds).hasSize(1);
    assertThat(sentTxnIds.get(0)).isNotEqualTo("ORIGINAL-TXN");
  }

  private void stubCreatePolicyError(int status, String code, String detail) {
    String json =
        objectMapper.writeValueAsString(
            Map.of("code", code, "detail", detail, "status", status, "title", "error"));
    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(json)));
  }

  private ResponseEntity<String> submitNewPolicy(String reqId) {
    var body =
        new RawHubRequestBody(
            new RawHeader(reqId, "NewPolicyService", "01", "INSP001", "universalsompo"),
            validPolicyDetails(),
            null);
    return submit(body, "/v1/policydetail");
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

  private ResponseEntity<String> submit(RawHubRequestBody body, String path) {
    String json = objectMapper.writeValueAsString(body);
    String envelope = objectMapper.writeValueAsString(Map.of("enc", json));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(fetchToken("insp001-client", "insp001-secret"));
    return restTemplate.exchange(
        path, HttpMethod.POST, new HttpEntity<>(envelope, headers), String.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> decrypt(String responseBody) {
    var wrapper = objectMapper.readValue(responseBody, Map.class);
    return objectMapper.readValue((String) wrapper.get("enc"), Map.class);
  }
}
