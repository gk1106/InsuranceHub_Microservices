package com.insurancehub.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationFilterTest {

  private static final String GENERATED_TXN_ID = "generated-txn-id";

  @Test
  void trustedModeCopiesAllThreeHeadersIntoMdc() throws Exception {
    CorrelationFilter filter = new CorrelationFilter(true, () -> GENERATED_TXN_ID);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HubHeaders.REQ_ID, "REQ1");
    request.addHeader(HubHeaders.INSP_ID, "INSP001");
    request.addHeader(HubHeaders.TXN_ID, "TXN1");

    Map<String, String> seen = captureMdcDuring(filter, request);

    assertThat(seen)
        .containsEntry("reqId", "REQ1")
        .containsEntry("inspId", "INSP001")
        .containsEntry("txnId", "TXN1");
  }

  @Test
  void trustedModeGeneratesTxnIdOnlyWhenHeaderAbsent() throws Exception {
    CorrelationFilter filter = new CorrelationFilter(true, () -> GENERATED_TXN_ID);
    MockHttpServletRequest request = new MockHttpServletRequest();

    Map<String, String> seen = captureMdcDuring(filter, request);

    assertThat(seen.get("reqId")).isNull();
    assertThat(seen.get("inspId")).isNull();
    assertThat(seen.get("txnId")).isEqualTo(GENERATED_TXN_ID);
  }

  @Test
  void untrustedModeIgnoresInboundHeadersEntirely() throws Exception {
    // hub-gateway's own entry point: even if a caller sends X-Req-Id/X-Insp-Id, they must not
    // be trusted - reqId/inspId only become known later, after decrypting the body and
    // validating the JWT.
    CorrelationFilter filter = new CorrelationFilter(false, () -> GENERATED_TXN_ID);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HubHeaders.REQ_ID, "SPOOFED_REQ");
    request.addHeader(HubHeaders.INSP_ID, "SPOOFED_INSP");
    request.addHeader(HubHeaders.TXN_ID, "SPOOFED_TXN");

    Map<String, String> seen = captureMdcDuring(filter, request);

    assertThat(seen.get("reqId")).isNull();
    assertThat(seen.get("inspId")).isNull();
    assertThat(seen.get("txnId")).isEqualTo(GENERATED_TXN_ID);
  }

  @Test
  void sanitizesCrlfAndOtherDisallowedCharactersOutOfHeaderValues() throws Exception {
    CorrelationFilter filter = new CorrelationFilter(true, () -> GENERATED_TXN_ID);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HubHeaders.REQ_ID, "REQ1\r\nFAKE-LOG-LINE: admin logged in");

    Map<String, String> seen = captureMdcDuring(filter, request);

    assertThat(seen.get("reqId")).doesNotContain("\r").doesNotContain("\n").doesNotContain(" ");
    // CRLF, the colon, and every space are stripped; only [A-Za-z0-9._-] survives.
    assertThat(seen.get("reqId")).isEqualTo("REQ1FAKE-LOG-LINEadminloggedin");
  }

  @Test
  void truncatesValuesLongerThan64Characters() throws Exception {
    CorrelationFilter filter = new CorrelationFilter(true, () -> GENERATED_TXN_ID);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HubHeaders.REQ_ID, "A".repeat(100));

    Map<String, String> seen = captureMdcDuring(filter, request);

    assertThat(seen.get("reqId")).hasSize(64);
  }

  @Test
  void clearsMdcAfterTheRequestCompletes() throws Exception {
    CorrelationFilter filter = new CorrelationFilter(true, () -> GENERATED_TXN_ID);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HubHeaders.REQ_ID, "REQ1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(MDC.get(HubHeaders.MDC_REQ_ID)).isNull();
    assertThat(MDC.get(HubHeaders.MDC_TXN_ID)).isNull();
  }

  @Test
  void mdcDoesNotLeakBetweenSequentialRequestsOnTheSameThread() throws Exception {
    CorrelationFilter filter = new CorrelationFilter(true, () -> GENERATED_TXN_ID);

    MockHttpServletRequest first = new MockHttpServletRequest();
    first.addHeader(HubHeaders.REQ_ID, "REQ1");
    first.addHeader(HubHeaders.INSP_ID, "INSP001");
    filter.doFilter(first, new MockHttpServletResponse(), (req, res) -> {});

    // Second request on the same thread, with no headers at all - if the filter's finally
    // block didn't clear MDC, this would still see the first request's values.
    MockHttpServletRequest second = new MockHttpServletRequest();
    Map<String, String> seenInSecondRequest = captureMdcDuring(filter, second);

    assertThat(seenInSecondRequest.get("reqId")).isNull();
    assertThat(seenInSecondRequest.get("inspId")).isNull();
  }

  private Map<String, String> captureMdcDuring(
      CorrelationFilter filter, MockHttpServletRequest request) throws Exception {
    Map<String, String> seen = new HashMap<>();
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, res) -> {
          seen.put("reqId", MDC.get(HubHeaders.MDC_REQ_ID));
          seen.put("inspId", MDC.get(HubHeaders.MDC_INSP_ID));
          seen.put("txnId", MDC.get(HubHeaders.MDC_TXN_ID));
        });
    return seen;
  }
}
