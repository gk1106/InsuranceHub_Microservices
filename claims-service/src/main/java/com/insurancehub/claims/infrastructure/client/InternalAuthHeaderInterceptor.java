package com.insurancehub.claims.infrastructure.client;

import static com.insurancehub.common.web.HubHeaders.INTERNAL_AUTH;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

// Attaches the shared internal-auth secret to every outbound call to policy-service
// (docs/adr/0007-internal-service-auth.md). Registered on the same RestClient.Builder as
// CorrelationPropagationInterceptor.
public class InternalAuthHeaderInterceptor implements ClientHttpRequestInterceptor {

  private final String secret;

  public InternalAuthHeaderInterceptor(String secret) {
    this.secret = secret;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    request.getHeaders().set(INTERNAL_AUTH, secret);
    return execution.execute(request, body);
  }
}
