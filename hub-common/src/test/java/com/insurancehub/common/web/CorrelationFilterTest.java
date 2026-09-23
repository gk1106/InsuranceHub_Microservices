package com.insurancehub.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationFilterTest {

  private final CorrelationFilter filter = new CorrelationFilter(() -> "generated-txn-id");

  @Test
  void copiesHeadersIntoMdcForTheDurationOfTheRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HubHeaders.REQ_ID, "REQ1");
    request.addHeader(HubHeaders.INSP_ID, "INSP001");
    request.addHeader(HubHeaders.TXN_ID, "TXN1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    Map<String, String> seen = captureMdcDuring(request, response);

    assertThat(seen)
        .containsEntry("reqId", "REQ1")
        .containsEntry("inspId", "INSP001")
        .containsEntry("txnId", "TXN1");
  }

  @Test
  void generatesTxnIdWhenAbsentButLeavesReqIdAndInspIdUnset() throws Exception {
    // The gateway's own entry point: no internal headers exist yet on the insurer's raw
    // request, since reqId/inspId only become known after decrypting the body.
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    Map<String, String> seen = captureMdcDuring(request, response);

    assertThat(seen.get("reqId")).isNull();
    assertThat(seen.get("inspId")).isNull();
    assertThat(seen.get("txnId")).isEqualTo("generated-txn-id");
  }

  @Test
  void clearsMdcAfterTheRequestCompletes() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HubHeaders.REQ_ID, "REQ1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(MDC.get(HubHeaders.MDC_REQ_ID)).isNull();
    assertThat(MDC.get(HubHeaders.MDC_TXN_ID)).isNull();
  }

  private Map<String, String> captureMdcDuring(
      MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
    Map<String, String> seen = new HashMap<>();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          seen.put("reqId", MDC.get(HubHeaders.MDC_REQ_ID));
          seen.put("inspId", MDC.get(HubHeaders.MDC_INSP_ID));
          seen.put("txnId", MDC.get(HubHeaders.MDC_TXN_ID));
        });
    return seen;
  }
}
