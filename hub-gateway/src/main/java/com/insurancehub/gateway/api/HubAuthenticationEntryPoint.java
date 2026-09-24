package com.insurancehub.gateway.api;

import static com.insurancehub.common.error.HubErrorCode.TOKEN_EXPIRED;
import static com.insurancehub.common.error.HubErrorCode.TOKEN_INVALID;

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Date;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// Replaces Spring Security's default 401 body (a WWW-Authenticate header, no matching JSON
// shape) with the plain HubResponse.preTrustFailure() shape api-contract.md §4 requires for
// token failures.
//
// "Expired" vs. "otherwise invalid" is decided by re-parsing the token's own exp claim, not by
// matching authException's message - Spring Security doesn't expose a distinct exception type
// for this, and the message text reaching commence() isn't a reliable signal to depend on
// (confirmed while debugging HubGatewaySecurityIT.expiredTokenIsRejectedAsTokenExpired against
// real Keycloak: the actual bug turned out to be SecurityConfig's default 60s JWT clock-skew
// tolerance masking real expiry entirely - see that class - but the exception's message/type
// still isn't something worth coupling to once expiry genuinely is rejected). Re-parsing here
// doesn't re-verify anything: the token already failed real verification by the time we get
// here, this only picks which error message to show.
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
    responseWriter.write(response, isExpired(request) ? TOKEN_EXPIRED : TOKEN_INVALID);
  }

  private static boolean isExpired(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return false;
    }
    try {
      Date exp = JWTParser.parse(header.substring(7)).getJWTClaimsSet().getExpirationTime();
      return exp != null && exp.toInstant().isBefore(Instant.now());
    } catch (Exception e) {
      return false;
    }
  }
}
