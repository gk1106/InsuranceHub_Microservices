package com.insurancehub.gateway.config;

import com.insurancehub.gateway.api.HubAccessDeniedHandler;
import com.insurancehub.gateway.api.HubAuthenticationEntryPoint;
import com.insurancehub.gateway.api.PreTrustResponseWriter;
import com.insurancehub.gateway.security.ClientIpResolver;
import com.insurancehub.gateway.security.IpAllowlistFilter;
import com.insurancehub.gateway.security.RequestBodySizeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

// service-design.md §4's pipeline: CorrelationFilter (hub-common, already auto-configured) runs
// first; then Spring Security's own JWT authentication filter; IpAllowlistFilter runs right
// after it, since it needs the resolved JWT to know which insurer's CIDRs to check.
// RequestBodySizeFilter runs before authentication entirely - there's no reason to spend a JWT
// validation on a request that's already too large.
@Configuration
public class SecurityConfig {

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
        .addFilterAfter(ipAllowlistFilter, BearerTokenAuthenticationFilter.class)
        .addFilterBefore(requestBodySizeFilter, BearerTokenAuthenticationFilter.class)
        .csrf(csrf -> csrf.disable());
    return http.build();
  }
}
