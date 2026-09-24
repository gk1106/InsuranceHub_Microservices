package com.insurancehub.gateway.security;

import static com.insurancehub.common.error.HubErrorCode.IP_NOT_ALLOWED;
import static com.insurancehub.common.error.HubErrorCode.TOKEN_INVALID;

import com.insurancehub.gateway.api.PreTrustResponseWriter;
import com.insurancehub.gateway.audit.AuditContext;
import com.insurancehub.gateway.config.InsurerProperties;
import com.insurancehub.gateway.config.InsurerProperties.InsurerConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

// service-design.md §4 step 2+3, combined: resolving inspId from the token's client id and
// checking the client IP against that insurer's allowlist are tightly coupled (the CIDR list to
// check against IS the resolution's result), so one filter does both. Registered after
// BearerTokenAuthenticationFilter (SecurityConfig) - the JWT is already validated and in the
// SecurityContext by the time this runs. Writes its own response directly (PreTrustResponseWriter)
// rather than going through Spring Security's AccessDeniedHandler, since this isn't a Spring
// Security authorization decision - it's a plain custom filter.
public class IpAllowlistFilter extends OncePerRequestFilter {

  private final InsurerProperties insurerProperties;
  private final ClientIpResolver clientIpResolver;
  private final PreTrustResponseWriter responseWriter;

  public IpAllowlistFilter(
      InsurerProperties insurerProperties,
      ClientIpResolver clientIpResolver,
      PreTrustResponseWriter responseWriter) {
    this.insurerProperties = insurerProperties;
    this.clientIpResolver = clientIpResolver;
    this.responseWriter = responseWriter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
      // No authenticated JWT (e.g. an actuator health request permitAll'd in SecurityConfig) -
      // nothing for this filter to check.
      filterChain.doFilter(request, response);
      return;
    }

    Jwt jwt = jwtAuth.getToken();
    String clientId = jwt.getClaimAsString("azp");
    Optional<InsurerConfig> insurer = resolveInsurer(clientId);
    if (insurer.isEmpty()) {
      // Deliberately TOKEN_INVALID (401), not a distinct code: the token is validly signed and
      // scoped, but its azp isn't any configured insurer's oauth-client-id - an identity the
      // gateway has never heard of, which is exactly what TOKEN_INVALID's "not usable" meaning
      // already covers. Not INSURER_MISMATCH (403) either - that code is reserved for a
      // different, phase-6 check: a decrypted body whose header.inspId disagrees with the
      // token's already-resolved insurer (api-contract.md §4/cross-cutting.md §5), which
      // presupposes the token DID resolve to a real insurer. Covered by
      // HubGatewaySecurityIT.validTokenFromAnUnregisteredClientIsRejectedAsTokenInvalid.
      responseWriter.write(response, TOKEN_INVALID);
      return;
    }

    AuditContext.from(request).setInspId(insurer.get().inspId());

    String clientIp = clientIpResolver.resolve(request);
    if (!isAllowed(clientIp, insurer.get().allowedCidrs())) {
      responseWriter.write(response, IP_NOT_ALLOWED);
      return;
    }

    filterChain.doFilter(request, response);
  }

  private Optional<InsurerConfig> resolveInsurer(String clientId) {
    return insurerProperties.insurers().stream()
        .filter(insurer -> insurer.oauthClientId().equals(clientId))
        .findFirst();
  }

  private static boolean isAllowed(String clientIp, List<String> allowedCidrs) {
    return allowedCidrs.stream().anyMatch(cidr -> new IpAddressMatcher(cidr).matches(clientIp));
  }
}
