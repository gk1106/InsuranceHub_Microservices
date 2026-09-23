package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.AbstractHubGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

// Real Keycloak (AbstractHubGatewayIT), no downstream services involved - these scenarios never
// reach HubDispatcher's business logic except to prove that a valid, allowed request *does*
// reach it (observed via a clean INVALID_SERVICE_CODE, since no CodeHandler is registered until
// commit 2 - proves the security layer let the request through without needing real dispatch).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HubGatewaySecurityIT extends AbstractHubGatewayIT {

  // INSP003's allowlist is narrowed just for this class, so the IP-not-allowed test has an
  // insurer to use without touching INSP001/002 (which other tests here rely on staying open).
  @DynamicPropertySource
  static void narrowInsp003Allowlist(DynamicPropertyRegistry registry) {
    registry.add("hub.insurers[2].allowed-cidrs[0]", () -> "10.99.99.0/24");
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void missingTokenIsRejectedAsTokenInvalidPlainJson() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail", HttpMethod.POST, entity(validBody(), null), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    var body = parsePlain(response.getBody());
    assertThat(body.get("respCode")).isEqualTo("401");
    assertThat(body.get("status")).isEqualTo("F");
    // Plain, not the crypto envelope - no "enc" key at all.
    assertThat(body).doesNotContainKey("enc");
  }

  @Test
  void malformedTokenIsRejectedAsTokenInvalid() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail",
            HttpMethod.POST,
            entity(validBody(), "not-a-real-jwt"),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(parsePlain(response.getBody()).get("respCode")).isEqualTo("401");
  }

  @Test
  void expiredTokenIsRejectedAsTokenExpired() throws InterruptedException {
    String token = fetchToken("test-shortlived-client", "test-shortlived-secret");
    Thread.sleep(3000); // realm client's access.token.lifespan is 2s

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail", HttpMethod.POST, entity(validBody(), token), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    var body = parsePlain(response.getBody());
    assertThat(body.get("respCode")).isEqualTo("401");
    assertThat(body.get("errorDesc")).isEqualTo("Token Expired");
  }

  @Test
  void tokenMissingInsuranceScopeIsRejectedWithMatchingStatusAndBody() {
    String token = fetchToken("test-no-scope-client", "test-no-scope-secret");

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail", HttpMethod.POST, entity(validBody(), token), String.class);

    // The exact bug being fixed: status and body must agree (api-contract.md §4).
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    var body = parsePlain(response.getBody());
    assertThat(body.get("respCode")).isEqualTo("403");
  }

  @Test
  void validTokenReachesTheDispatcher() {
    String token = fetchToken("insp001-client", "insp001-secret");

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail", HttpMethod.POST, entity(mismatchedCodeBody(), token), String.class);

    // Not a 401/403 - the request cleared auth and IP allowlist and reached dispatch, which
    // rejected the mismatched code on its own merits.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var body = decryptEnvelope(response.getBody());
    assertThat(body.get("respCode")).isEqualTo("400");
  }

  @Test
  void ipNotOnTheInsurersAllowlistIsRejected() {
    String token = fetchToken("insp003-client", "insp003-secret");

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail", HttpMethod.POST, entity(validBody(), token), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    var body = parsePlain(response.getBody());
    assertThat(body.get("errorDesc")).isEqualTo("IP not allowed");
  }

  @Test
  void oversizedBodyIsRejected() {
    String token = fetchToken("insp001-client", "insp001-secret");
    String hugeEnc = "x".repeat(300 * 1024);

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail",
            HttpMethod.POST,
            entity("{\"enc\":\"" + hugeEnc + "\"}", token),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(parsePlain(response.getBody()).get("respCode")).isEqualTo("413");
  }

  private HttpEntity<String> entity(String jsonBody, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return new HttpEntity<>(jsonBody, headers);
  }

  private static String validBody() {
    return "{\"enc\":\""
        + "{\\\"header\\\":{\\\"reqId\\\":\\\"REQ1\\\",\\\"serviceType\\\":\\\"NewPolicyService\\\","
        + "\\\"appStatusCode\\\":\\\"01\\\",\\\"inspId\\\":\\\"INSP001\\\",\\\"inspName\\\":\\\"universalsompo\\\"}}"
        + "\"}";
  }

  private static String mismatchedCodeBody() {
    return "{\"enc\":\""
        + "{\\\"header\\\":{\\\"reqId\\\":\\\"REQ1\\\",\\\"serviceType\\\":\\\"RenewalService\\\","
        + "\\\"appStatusCode\\\":\\\"03\\\",\\\"inspId\\\":\\\"INSP001\\\",\\\"inspName\\\":\\\"universalsompo\\\"}}"
        + "\"}";
  }

  @SuppressWarnings("unchecked")
  private java.util.Map<String, Object> parsePlain(String responseBody) {
    return objectMapper.readValue(responseBody, java.util.Map.class);
  }

  @SuppressWarnings("unchecked")
  private java.util.Map<String, Object> decryptEnvelope(String responseBody) {
    var envelope = objectMapper.readValue(responseBody, java.util.Map.class);
    return objectMapper.readValue((String) envelope.get("enc"), java.util.Map.class);
  }
}
