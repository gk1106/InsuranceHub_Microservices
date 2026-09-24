package com.insurancehub.gateway.config;

import com.insurancehub.gateway.api.RequestAuditFilter;
import com.insurancehub.gateway.application.RequestAuditService;
import com.insurancehub.gateway.security.ClientIpResolver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

// RequestAuditFilter is a plain class, not a @Component (same reason IpAllowlistFilter/
// RequestBodySizeFilter aren't) - registered manually here, outside Spring Security's own
// filter chain entirely, so it wraps Spring Security's whole chain too (missing token, IP not
// allowed, oversized body all need an audit row - see that class). HIGHEST_PRECEDENCE + 1: right
// after hub-common's CorrelationFilter (HIGHEST_PRECEDENCE), so MDC's txnId is already set by
// the time this filter's own doFilter runs, and still before Spring Security's FilterChainProxy.
@Configuration
public class RequestAuditFilterConfig {

  @Bean
  FilterRegistrationBean<RequestAuditFilter> requestAuditFilterRegistration(
      RequestAuditService auditService,
      ClientIpResolver clientIpResolver,
      MeterRegistry meterRegistry) {
    FilterRegistrationBean<RequestAuditFilter> registration =
        new FilterRegistrationBean<>(
            new RequestAuditFilter(auditService, clientIpResolver, meterRegistry));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }
}
