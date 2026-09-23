package com.insurancehub.gateway.api;

import static com.insurancehub.common.error.HubErrorCode.INSUFFICIENT_SCOPE;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

// Fires when a token is otherwise valid but lacks the Insurance scope (SecurityConfig requires
// SCOPE_Insurance on every route) - the only AccessDeniedException source in this app, since
// IpAllowlistFilter writes its own response directly rather than going through Spring
// Security's authorization machinery. docs/open-questions.md Q11.
@Component
public class HubAccessDeniedHandler implements AccessDeniedHandler {

  private final PreTrustResponseWriter responseWriter;

  public HubAccessDeniedHandler(PreTrustResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws java.io.IOException {
    responseWriter.write(response, INSUFFICIENT_SCOPE);
  }
}
