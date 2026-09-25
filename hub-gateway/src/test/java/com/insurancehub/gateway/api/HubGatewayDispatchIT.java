package com.insurancehub.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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

// One happy path per code (01/02/03/04), crypto off - proves the whole commit-2 pipeline
// end-to-end: real auth, real dispatch, real mapping, a real (WireMock-stubbed) downstream call,
// and a correctly-shaped success response. policy-service/claims-service are stubbed, not real -
// resilience/error-mapping specifics belong to HubGatewayErrorMappingIT/HubGatewayResilienceIT.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewayDispatchIT extends AbstractHubGatewayIT {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void resetWireMock() {
    POLICY_SERVICE.resetAll();
    CLAIMS_SERVICE.resetAll();
  }

  @Test
  void code01NewPolicyHappyPath() {
    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"TXN-P01\",\"replayed\":false,\"policyNum\":\"POL001\"}")));

    var body =
        new RawHubRequestBody(
            new RawHeader("REQ01", "NewPolicyService", "01", "INSP001", "universalsompo"),
            newPolicyDetails("POL001"),
            null);

    ResponseEntity<String> response = submit(body, token());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var decrypted = decrypt(response.getBody());
    assertThat(decrypted.get("respCode")).isEqualTo("200");
    assertThat(decrypted.get("status")).isEqualTo("S");
    assertThat(decrypted.get("txnId")).isEqualTo("TXN-P01");
    assertThat(decrypted.get("reqId")).isEqualTo("REQ01");
  }

  @Test
  void policyServiceCallCarriesAnInternalAuthHeaderAndATraceparentHeader() {
    // Phase 8: proves two separate defense-in-depth/observability additions actually reach the
    // wire, not just that config exists. X-Internal-Auth is unconditional
    // (InternalAuthHeaderInterceptor);
    // traceparent depends on Micrometer Tracing's RestClient instrumentation actually being
    // attached to this client - the exact risk flagged and fixed in PolicyServiceClientConfig
    // (built from the injected RestClient.Builder bean, not a bare RestClient.builder()).
    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"TXN-P01\",\"replayed\":false,\"policyNum\":\"POL001\"}")));

    var body =
        new RawHubRequestBody(
            new RawHeader("REQ01", "NewPolicyService", "01", "INSP001", "universalsompo"),
            newPolicyDetails("POL001"),
            null);

    ResponseEntity<String> response = submit(body, token());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    var served = POLICY_SERVICE.findAll(postRequestedFor(urlPathEqualTo("/internal/policies")));
    assertThat(served).hasSize(1);
    var request = served.get(0);
    assertThat(request.getHeader("X-Internal-Auth")).isNotBlank();
    assertThat(request.getHeader("traceparent")).isNotBlank();
  }

  @Test
  void code02RenewalHappyPath() {
    POLICY_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/policies/POL002/renewals"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"TXN-P02\",\"replayed\":false,\"policyNum\":\"POL002\","
                            + "\"termNo\":2}")));

    var body =
        new RawHubRequestBody(
            new RawHeader("REQ02", "RenewalService", "02", "INSP001", "universalsompo"),
            newPolicyDetails("POL002"),
            null);

    ResponseEntity<String> response = submit(body, token());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var decrypted = decrypt(response.getBody());
    assertThat(decrypted.get("txnId")).isEqualTo("TXN-P02");
  }

  @Test
  void code03ClaimRegistrationHappyPath() {
    CLAIMS_SERVICE.stubFor(
        post(urlPathEqualTo("/internal/claims"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"TXN-C03\",\"replayed\":false,\"claimNum\":\"CLM003\"}")));

    var body =
        new RawHubRequestBody(
            new RawHeader("REQ03", "ClaimService", "03", "INSP001", "universalsompo"),
            newPolicyDetails("POL003"),
            claimDetailsForRegistration("CLM003"));

    ResponseEntity<String> response = submit(body, token());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var decrypted = decrypt(response.getBody());
    assertThat(decrypted.get("txnId")).isEqualTo("TXN-C03");
  }

  @Test
  void code04ClaimStatusHappyPath() {
    CLAIMS_SERVICE.stubFor(
        patch(urlPathEqualTo("/internal/claims/CLM004/status"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"txnId\":\"TXN-C04\",\"replayed\":false,\"claimNum\":\"CLM004\"}")));

    var body =
        new RawHubRequestBody(
            new RawHeader("REQ04", "ClaimStatusService", "04", "INSP001", "universalsompo"),
            null,
            claimDetailsForStatusUpdate("POL004", "CLM004"));

    ResponseEntity<String> response = submit(body, token(), "/v1/policydetail/claimupdatestatus");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var decrypted = decrypt(response.getBody());
    assertThat(decrypted.get("txnId")).isEqualTo("TXN-C04");
  }

  @Test
  void missingEncFieldIsRejectedAsInvalidJsonNeverA500() {
    // Crypto off here (this whole class): EncryptedEnvelope.enc has no @NotBlank any more (that
    // check now belongs to HubCryptoService per-implementation), so a request with no `enc` field
    // at all reaches NoOpHubCryptoService.verifyAndDecrypt(null, ...), which echoes null straight
    // through - HubController.parse must reject that as INVALID_JSON (same as any other
    // malformed body - enveloped via encryptAndSign like every other business rejection, identity
    // here since crypto is off), not let ObjectMapper's IllegalArgumentException-for-null fall
    // through to a generic 500.
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token());
    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail", HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var decrypted = decrypt(response.getBody());
    assertThat(decrypted.get("respCode")).isEqualTo("400");
    assertThat(decrypted.get("errorDesc")).isEqualTo("Invalid Json Format");
  }

  private static RawPolicyDetails newPolicyDetails(String policyNum) {
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
        policyNum,
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

  private static RawClaimDetails claimDetailsForRegistration(String claimNum) {
    return new RawClaimDetails(
        null,
        claimNum,
        "ACCIDENT",
        "Minor collision",
        "Collision",
        "Chennai",
        "01/08/2024",
        "05/08/2024",
        "85000",
        "",
        "",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static RawClaimDetails claimDetailsForStatusUpdate(String policyNum, String claimNum) {
    return new RawClaimDetails(
        policyNum,
        claimNum,
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

  private String token() {
    return fetchToken("insp001-client", "insp001-secret");
  }

  private ResponseEntity<String> submit(RawHubRequestBody body, String token) {
    return submit(body, token, "/v1/policydetail");
  }

  private ResponseEntity<String> submit(RawHubRequestBody body, String token, String path) {
    String json = objectMapper.writeValueAsString(body);
    String envelope = objectMapper.writeValueAsString(Map.of("enc", json));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token);
    return restTemplate.exchange(
        path, HttpMethod.POST, new HttpEntity<>(envelope, headers), String.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> decrypt(String responseBody) {
    var wrapper = objectMapper.readValue(responseBody, Map.class);
    return objectMapper.readValue((String) wrapper.get("enc"), Map.class);
  }
}
