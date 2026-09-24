package com.insurancehub.gateway.api;

import static com.insurancehub.common.error.HubErrorCode.TOKEN_EXPIRED;
import static com.insurancehub.common.error.HubErrorCode.TOKEN_INVALID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Objects;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// Replaces Spring Security's default 401 body (a WWW-Authenticate header, no matching JSON
// shape) with the plain HubResponse.preTrustFailure() shape api-contract.md §4 requires for
// token failures.
//
// "Expired" vs. "otherwise invalid" is decided from JwtValidationException.getErrors(), never
// by matching authException's own top-level message - that message is generic and unreliable
// (confirmed while debugging HubGatewaySecurityIT.expiredTokenIsRejectedAsTokenExpired against
// real Keycloak: SecurityConfig's original 60s JWT clock-skew tolerance was masking real expiry
// entirely, and separately, the exception commence() first sees can be a wrapper with no
// JWT-specific detail at all). JwtValidationException is the right, safe signal instead: Nimbus
// only throws it AFTER a token has already decoded and signature-verified successfully and THEN
// failed one of the configured OAuth2TokenValidators (issuer, timestamp) - a bad signature or
// malformed token throws plain BadJwtException instead, never reaching here. Confirmed against
// real Keycloak for all three cases: missing token -> generic InsufficientAuthenticationException
// with no JWT info; malformed token -> BadJwtException/ParseException, no JwtValidationException
// in the chain; expired token -> JwtValidationException with
// errors=[[invalid_token] Jwt expired at ...]. So looking for a JwtValidationException at all
// already rules out signature/decode failures; only its own errors' text still needs reading,
// since JwtTimestampValidator uses the same "invalid_token" error code for both expiry and
// not-yet-valid, distinguished only by description text.
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

  private static boolean isExpired(Throwable authException) {
    JwtValidationException validationException = findJwtValidationException(authException);
    if (validationException == null) {
      return false;
    }
    return validationException.getErrors().stream()
        .map(OAuth2Error::getDescription)
        .filter(Objects::nonNull)
        .anyMatch(description -> description.toLowerCase(Locale.ROOT).contains("expired"));
  }

  private static JwtValidationException findJwtValidationException(Throwable t) {
    while (t != null) {
      if (t instanceof JwtValidationException jwtValidationException) {
        return jwtValidationException;
      }
      t = t.getCause();
    }
    return null;
  }
}
