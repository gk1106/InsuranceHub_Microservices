package com.insurancehub.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.AbstractHubGatewayIT;
import com.insurancehub.gateway.domain.RawClaimDetails;
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

// HubServiceCode resolution happens before validation ever runs (HubDispatcher) - these prove
// that ordering, not just that each case eventually fails.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewayServiceCodeValidationIT extends AbstractHubGatewayIT {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void resetWireMock() {
    POLICY_SERVICE.resetAll();
    CLAIMS_SERVICE.resetAll();
  }

  @Test
  void aKnownServiceTypeWithTheWrongAppStatusCodeIsRejected() {
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ1", "RenewalService", "03", "INSP001", "universalsompo"),
            minimalPolicyDetails(),
            null);

    var response = submit(body, "/v1/policydetail");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(decrypt(response.getBody()).get("respCode")).isEqualTo("400");
  }

  @Test
  void anUnknownServiceTypeIsRejected() {
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ1", "SomethingElseService", "01", "INSP001", "universalsompo"),
            minimalPolicyDetails(),
            null);

    var response = submit(body, "/v1/policydetail");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aRealCodeSubmittedToTheWrongEndpointIsRejected() {
    // 04 is only valid on /v1/policydetail/claimupdatestatus, not /v1/policydetail.
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ1", "ClaimStatusService", "04", "INSP001", "universalsompo"),
            null,
            minimalClaimDetails());

    var response = submit(body, "/v1/policydetail");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aMismatchedCodeWinsOverAlsoMissingRequiredFields() {
    // Mismatched code AND missing every required field - proves INVALID_SERVICE_CODE is
    // resolved before field validation ever runs, not just that both would eventually fail.
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ1", "RenewalService", "03", "INSP001", "universalsompo"), null, null);

    var response = submit(body, "/v1/policydetail");

    var decrypted = decrypt(response.getBody());
    assertThat(decrypted.get("errorDesc")).isEqualTo("Invalid serviceType/appStatusCode");
  }

  @Test
  void the04TrailingSlashVariantIsAccepted() {
    CLAIMS_SERVICE.stubFor(
        patch(urlPathEqualTo("/internal/claims/CLM1/status"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"txnId\":\"TXN1\",\"replayed\":false,\"claimNum\":\"CLM1\"}")));
    var body =
        new RawHubRequestBody(
            new RawHeader("REQ1", "ClaimStatusService", "04", "INSP001", "universalsompo"),
            null,
            minimalClaimDetails());

    var response = submit(body, "/v1/policydetail/claimupdatestatus/");

    // 200, not Spring MVC's own 404 for an unmapped route - proves the trailing-slash variant
    // is accepted, not just "didn't 404 for some other reason".
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private static RawPolicyDetails minimalPolicyDetails() {
    return new RawPolicyDetails(
        "", "", "", "", "", "", "", "", "", "POL1", "", "", "", "", "", "", "", "", "", "", "", "",
        "", "", "", "");
  }

  private static RawClaimDetails minimalClaimDetails() {
    return new RawClaimDetails(
        "POL1",
        "CLM1",
        "",
        null,
        null,
        null,
        "",
        "",
        "",
        "UNDER_PROCESS",
        "",
        "",
        "",
        "",
        "",
        "",
        "");
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
