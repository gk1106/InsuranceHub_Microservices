package com.insurancehub.common;

import com.insurancehub.common.web.CorrelationFilter;
import com.insurancehub.common.web.CorrelationProperties;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

// Registering CorrelationFilter here means any service that adds hub-common as a dependency
// gets it wired automatically, instead of every service hand-writing the same @Bean.
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter.class)
@EnableConfigurationProperties(CorrelationProperties.class)
public class HubCommonAutoConfiguration {

  @Bean
  // Bean method now takes a parameter, so return-type deduction for @ConditionalOnMissingBean
  // fails (Boot 4.1.1) - name it explicitly. By name, not by FilterRegistrationBean.class,
  // so this doesn't accidentally match some other filter's registration a consumer defines.
  @ConditionalOnMissingBean(name = "correlationFilterRegistration")
  public FilterRegistrationBean<CorrelationFilter> correlationFilterRegistration(
      CorrelationProperties properties) {
    FilterRegistrationBean<CorrelationFilter> registration =
        new FilterRegistrationBean<>(new CorrelationFilter(properties.trustInboundHeaders()));
    // Runs before everything else (security included) so even a rejected request's logs carry
    // a txnId.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
