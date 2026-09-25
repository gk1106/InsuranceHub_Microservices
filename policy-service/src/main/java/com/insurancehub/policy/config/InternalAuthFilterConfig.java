package com.insurancehub.policy.config;

import com.insurancehub.policy.infrastructure.security.InternalAuthFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

// Scoped to /internal/** only via addUrlPatterns - every endpoint this service exposes is under
// that prefix today, but scoping explicitly (rather than "/*" ) keeps actuator/springdoc
// reachable without the header, matching hub-gateway's own IpAllowlistFilter convention of never
// gating health/info endpoints.
@Configuration
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthFilterConfig {

  @Bean
  FilterRegistrationBean<InternalAuthFilter> internalAuthFilterRegistration(
      InternalAuthProperties properties, ObjectMapper objectMapper) {
    FilterRegistrationBean<InternalAuthFilter> registration =
        new FilterRegistrationBean<>(new InternalAuthFilter(properties.secret(), objectMapper));
    registration.addUrlPatterns("/internal/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }
}
