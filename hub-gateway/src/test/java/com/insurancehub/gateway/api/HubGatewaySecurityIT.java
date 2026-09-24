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
  // Spring's relaxed binder picks ONE property source as authoritative for the whole
  // hub.insurers list (the first, by priority, that has an index [0] entry) - it does not merge
  // a single overridden field from a higher-priority source into the lower-priority source's
  // array. So narrowing just INSP003's CIDR means restating all three insurers here, not adding
  // one key.
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
    // realm client's access.token.lifespan is 2s; SecurityConfig's JwtDecoder tolerates a 5s
    // clock skew on top of that (docs/open-questions.md Q14) - sleep past both with margin.
    Thread.sleep(9000);

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
  void validTokenFromAnUnregisteredClientIsRejectedAsTokenInvalid() {
    // Signed, unexpired, carries the Insurance scope - passes authentication and authorization
    // cleanly. Rejected only because its azp isn't any configured insurer's oauth-client-id.
    String token = fetchToken("test-unregistered-client", "test-unregistered-secret");

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/policydetail", HttpMethod.POST, entity(validBody(), token), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    var body = parsePlain(response.getBody());
    assertThat(body.get("respCode")).isEqualTo("401");
    assertThat(body.get("errorDesc")).isEqualTo("Invalid token");
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

    // Compared as a raw code, not the HttpStatus enum constant: Spring Framework renamed 413's
    // canonical entry to CONTENT_TOO_LARGE and HttpStatus.valueOf(413) now resolves to that, not
    // the deprecated PAYLOAD_TOO_LARGE alias - respCode is what the contract actually mirrors.
    assertThat(response.getStatusCode().value()).isEqualTo(413);
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
