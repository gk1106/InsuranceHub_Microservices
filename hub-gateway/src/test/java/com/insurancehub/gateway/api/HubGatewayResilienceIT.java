package com.insurancehub.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.insurancehub.gateway.AbstractHubGatewayIT;
import com.insurancehub.gateway.domain.RawClaimDetails;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

// Shortened connect/read timeouts (vs. the 2s/20s production defaults) so the timeout scenario
// runs in ~1s, not 20s+ - same pattern claims-service's own ClaimRegistrationResilienceIT uses
// (phase 4b). Only claims-service's timeouts are overridden; policyService is untouched.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "claims-service.connect-timeout=200ms",
      "claims-service.read-timeout=500ms",
      // Matches the 20-call sliding window (application.yml's own claimsService config) instead
      // of resilience4j's library default of 100 - otherwise the breaker below would need 100
      // calls before it ever evaluates a failure rate, just to prove a 20-call window trips it.
      "resilience4j.circuitbreaker.instances.claimsService.minimum-number-of-calls=20"
    })
@AutoConfigureTestRestTemplate
class HubGatewayResilienceIT extends AbstractHubGatewayIT {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  @BeforeEach
  void resetWireMockAndCircuitBreaker() {
    CLAIMS_SERVICE.resetAll();
    // All @Test methods here share one Spring context (one circuit breaker instance) - without
    // this, an earlier test's failures would leak into a later test's sliding window.
    circuitBreakerRegistry.circuitBreaker("claimsService").reset();
  }

  @Test
  void claimsServiceUnreachableMapsToDownstreamUnavailableNotAServerError() {
    // A connection reset, not a 4xx/5xx - simulates "can't reach it at all" without needing a
    // second, genuinely-unbound port (this reuses the shared CLAIMS_SERVICE WireMock instance).
    CLAIMS_SERVICE.stubFor(
        patch(urlPathEqualTo("/internal/claims/CLM-UNREACHABLE/status"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    var response = submitStatusUpdate("CLM-UNREACHABLE", "REQ-UNREACHABLE");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(decrypt(response.getBody()).get("respCode")).isEqualTo("503");
  }

  @Test
  void claimsServiceTimingOutMapsToDownstreamUnavailableNeverA500() {
    // Delay exceeds the 500ms test read-timeout above.
    CLAIMS_SERVICE.stubFor(
        patch(urlPathEqualTo("/internal/claims/CLM-SLOW/status"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"txnId\":\"TXN1\",\"replayed\":false,\"claimNum\":\"CLM-SLOW\"}")
                    .withFixedDelay(2000)));

    var response = submitStatusUpdate("CLM-SLOW", "REQ-SLOW");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(decrypt(response.getBody()).get("respCode")).isEqualTo("503");
  }

  @Test
  void repeated500sFromClaimsServiceTripTheCircuitBreaker() {
    // record-exceptions must include HttpServerErrorException$InternalServerError, not just
    // $ServiceUnavailable - otherwise resilience4j's allow-list semantics mean a plain 500 is
    // never recorded as a failure at all (any exception not matching the allow-list counts as a
    // SUCCESSFUL call for the sliding window), and a genuinely broken downstream would never trip
    // the breaker.
    CLAIMS_SERVICE.stubFor(
        patch(urlPathMatching("/internal/claims/.*/status"))
            .willReturn(aResponse().withStatus(500)));

    for (int i = 0; i < 20; i++) {
      submitStatusUpdate("CLM-BROKEN-" + i, "REQ-BROKEN-" + i);
    }

    assertThat(circuitBreakerRegistry.circuitBreaker("claimsService").getState())
        .isEqualTo(CircuitBreaker.State.OPEN);
  }

  private ResponseEntity<String> submitStatusUpdate(String claimNum, String reqId) {
    var claimDetails =
        new RawClaimDetails(
            "POL1",
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
    var body =
        new RawHubRequestBody(
            new RawHeader(reqId, "ClaimStatusService", "04", "INSP001", "universalsompo"),
            null,
            claimDetails);
    String json = objectMapper.writeValueAsString(body);
    String envelope = objectMapper.writeValueAsString(Map.of("enc", json));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(fetchToken("insp001-client", "insp001-secret"));
    return restTemplate.exchange(
        "/v1/policydetail/claimupdatestatus",
        HttpMethod.POST,
        new HttpEntity<>(envelope, headers),
        String.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> decrypt(String responseBody) {
    var wrapper = objectMapper.readValue(responseBody, Map.class);
    return objectMapper.readValue((String) wrapper.get("enc"), Map.class);
  }
}
