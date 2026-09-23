package com.insurancehub.common;

import com.insurancehub.common.web.CorrelationFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

// Registering CorrelationFilter here means any service that adds hub-common as a dependency
// gets it wired automatically, instead of every service hand-writing the same @Bean.
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter.class)
public class HubCommonAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public FilterRegistrationBean<CorrelationFilter> correlationFilterRegistration() {
    FilterRegistrationBean<CorrelationFilter> registration =
        new FilterRegistrationBean<>(new CorrelationFilter());
    // Runs before everything else (security included) so even a rejected request's logs carry
    // a txnId.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
