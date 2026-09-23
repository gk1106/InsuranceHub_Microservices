package com.insurancehub.gateway.api;

import static com.insurancehub.common.error.HubErrorCode.TOKEN_EXPIRED;
import static com.insurancehub.common.error.HubErrorCode.TOKEN_INVALID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// Replaces Spring Security's default 401 body (a WWW-Authenticate header, no matching JSON
// shape) with the plain HubResponse.preTrustFailure() shape api-contract.md §4 requires for
// token failures. Spring Security doesn't expose a distinct exception type for "expired" vs.
// "otherwise invalid" - JwtTimestampValidator's failure message contains "expired", which is
// the most reliable signal available without re-parsing the token ourselves.
@Component
public class HubAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final PreTrustResponseWriter responseWriter;

  public HubAuthenticationEntryPoint(PreTrustResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws java.io.IOException {
    responseWriter.write(response, isExpired(authException) ? TOKEN_EXPIRED : TOKEN_INVALID);
  }

  private static boolean isExpired(AuthenticationException ex) {
    String message = ex.getMessage();
    return message != null && message.toLowerCase(Locale.ROOT).contains("expired");
  }
}
