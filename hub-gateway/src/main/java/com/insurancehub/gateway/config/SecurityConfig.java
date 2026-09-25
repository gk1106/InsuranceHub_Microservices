package com.insurancehub.gateway.config;

import com.insurancehub.gateway.api.HubAccessDeniedHandler;
import com.insurancehub.gateway.api.HubAuthenticationEntryPoint;
import com.insurancehub.gateway.api.PreTrustResponseWriter;
import com.insurancehub.gateway.security.ClientIpResolver;
import com.insurancehub.gateway.security.IpAllowlistFilter;
import com.insurancehub.gateway.security.RequestBodySizeFilter;
import java.time.Duration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

// service-design.md §4's pipeline: CorrelationFilter (hub-common, already auto-configured) runs
// first; then Spring Security's own JWT authentication filter; then Spring Security's own
// scope-based authorization (hasAuthority("SCOPE_Insurance")); IpAllowlistFilter runs after
// THAT, since it needs the resolved JWT to know which insurer's CIDRs to check, and a token
// whose azp isn't a recognised insurer client id should never get a chance to short-circuit a
// missing-scope 403 into a wrong-cause 401 - confirmed by a real-Keycloak IT
// (HubGatewaySecurityIT.tokenMissingInsuranceScopeIsRejectedWithMatchingStatusAndBody used a
// token from a client id no insurer maps to; with IpAllowlistFilter positioned right after
// authentication, it rejected as TOKEN_INVALID before authorization ever ran).
// RequestBodySizeFilter
// runs before authentication entirely - there's no reason to spend a JWT validation on a request
// that's already too large.
@Configuration
public class SecurityConfig {

  // Spring Boot's autoconfigured JwtDecoder defaults JwtTimestampValidator's clock skew to 60
  // seconds - a token is still accepted for a full minute after its own exp claim. Confirmed
  // against real Keycloak (HubGatewaySecurityIT.expiredTokenIsRejectedAsTokenExpired, initially
  // failing unexpectedly): a token that expired 3 seconds earlier was still authenticating
  // successfully, so the "expired" rejection was never even reaching our error mapping - it was
  // a different filter (IpAllowlistFilter, downstream) rejecting the test-only client's
  // unrecognised id instead. A 60s grace window is too loose for a financial integration where
  // callers are expected to fetch a fresh token per call; tightened to 5s (comfortable for
  // ordinary clock drift between the gateway and the issuer, not an invitation to reuse a stale
  // token). See docs/open-questions.md Q14 - not specified by the bank's own docs.
  private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

  // SupplierJwtDecoder (the same wrapper Boot's own autoconfiguration uses): NimbusJwtDecoder.
  // withIssuerLocation(...).build() fetches the issuer's /.well-known/openid-configuration
  // eagerly, at bean-creation time, which would fail application startup outright if the issuer
  // is briefly unreachable. Deferring that fetch to first actual token decode keeps this bean as
  // lazy as the one it replaces.
  @Bean
  JwtDecoder jwtDecoder(OAuth2ResourceServerProperties resourceServerProperties) {
    String issuerUri = resourceServerProperties.getJwt().getIssuerUri();
    return new SupplierJwtDecoder(
        () -> {
          NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
          OAuth2TokenValidator<Jwt> validator =
              new DelegatingOAuth2TokenValidator<>(
                  new JwtIssuerValidator(issuerUri), new JwtTimestampValidator(CLOCK_SKEW));
          decoder.setJwtValidator(validator);
          return decoder;
        });
  }

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      HubAuthenticationEntryPoint authenticationEntryPoint,
      HubAccessDeniedHandler accessDeniedHandler,
      InsurerProperties insurerProperties,
      ClientIpResolver clientIpResolver,
      PreTrustResponseWriter responseWriter)
      throws Exception {
    IpAllowlistFilter ipAllowlistFilter =
        new IpAllowlistFilter(insurerProperties, clientIpResolver, responseWriter);
    RequestBodySizeFilter requestBodySizeFilter = new RequestBodySizeFilter(responseWriter);

    http.authorizeHttpRequests(
            authorize ->
                authorize
                    // Phase 8 moved actuator to its own management.server.port
                    // (cross-cutting.md §4), so this main-context filter chain no longer even
                    // sees /actuator/** requests - left permitAll anyway as harmless, accurate
                    // documentation of intent, and so nothing breaks if management.server.port
                    // is ever removed again.
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .anyRequest()
                    .hasAuthority("SCOPE_Insurance"))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> {})
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .addFilterAfter(ipAllowlistFilter, AuthorizationFilter.class)
        .addFilterBefore(requestBodySizeFilter, BearerTokenAuthenticationFilter.class)
        .csrf(csrf -> csrf.disable());
    return http.build();
  }
}
