package com.insurancehub.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

class CorrelationPropagationInterceptorTest {

  private final CorrelationPropagationInterceptor interceptor =
      new CorrelationPropagationInterceptor();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void forwardsCorrelationHeadersFromMdc() throws Exception {
    MDC.put(HubHeaders.MDC_REQ_ID, "REQ1");
    MDC.put(HubHeaders.MDC_INSP_ID, "INSP001");
    MDC.put(HubHeaders.MDC_TXN_ID, "TXN1");
    MockClientHttpRequest request =
        new MockClientHttpRequest(
            HttpMethod.GET, URI.create("http://policy-service/internal/policies/POL1/coverage"));
    ClientHttpResponse stubResponse = mock(ClientHttpResponse.class);

    interceptor.intercept(request, new byte[0], (req, body) -> stubResponse);

    assertThat(request.getHeaders().getFirst(HubHeaders.REQ_ID)).isEqualTo("REQ1");
    assertThat(request.getHeaders().getFirst(HubHeaders.INSP_ID)).isEqualTo("INSP001");
    assertThat(request.getHeaders().getFirst(HubHeaders.TXN_ID)).isEqualTo("TXN1");
    assertThat(request.getHeaders().get("traceparent")).isNull();
  }

  @Test
  void leavesHeadersUnsetWhenMdcIsEmpty() throws Exception {
    MockClientHttpRequest request =
        new MockClientHttpRequest(
            HttpMethod.GET, URI.create("http://policy-service/internal/policies/POL1/coverage"));
    ClientHttpResponse stubResponse = mock(ClientHttpResponse.class);

    interceptor.intercept(request, new byte[0], (req, body) -> stubResponse);

    assertThat(request.getHeaders().getFirst(HubHeaders.REQ_ID)).isNull();
    assertThat(request.getHeaders().getFirst(HubHeaders.INSP_ID)).isNull();
    assertThat(request.getHeaders().getFirst(HubHeaders.TXN_ID)).isNull();
  }
}
