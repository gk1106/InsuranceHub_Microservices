package com.insurancehub.common.web;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

// Writes the current request's correlation MDC values onto an outgoing RestClient call, so the
// next hop's CorrelationFilter sees the same reqId/inspId/txnId (e.g. claims-service ->
// policy-service coverage lookup). Does not touch traceparent - Micrometer's instrumentation
// adds that independently.
public class CorrelationPropagationInterceptor implements ClientHttpRequestInterceptor {

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    copyIfPresent(request, HubHeaders.MDC_REQ_ID, HubHeaders.REQ_ID);
    copyIfPresent(request, HubHeaders.MDC_INSP_ID, HubHeaders.INSP_ID);
    copyIfPresent(request, HubHeaders.MDC_TXN_ID, HubHeaders.TXN_ID);
    return execution.execute(request, body);
  }

  private static void copyIfPresent(HttpRequest request, String mdcKey, String header) {
    String value = MDC.get(mdcKey);
    if (value != null) {
      request.getHeaders().set(header, value);
    }
  }
}
