package com.insurancehub.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.common.web.CorrelationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class HubCommonAutoConfigurationTest {

  @Test
  void registersCorrelationFilterInAServletWebContext() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HubCommonAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasSingleBean(FilterRegistrationBean.class);
              FilterRegistrationBean<?> registration =
                  context.getBean(FilterRegistrationBean.class);
              assertThat(registration.getFilter()).isInstanceOf(CorrelationFilter.class);
            });
  }

  @Test
  void defaultsToNotTrustingInboundHeaders() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HubCommonAutoConfiguration.class))
        .run(
            context -> {
              FilterRegistrationBean<?> registration =
                  context.getBean(FilterRegistrationBean.class);
              CorrelationFilter filter = (CorrelationFilter) registration.getFilter();
              assertThat(filter.trustInboundHeaders()).isFalse();
            });
  }

  @Test
  void trustsInboundHeadersWhenExplicitlyEnabled() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HubCommonAutoConfiguration.class))
        .withPropertyValues("hub.correlation.trust-inbound-headers=true")
        .run(
            context -> {
              FilterRegistrationBean<?> registration =
                  context.getBean(FilterRegistrationBean.class);
              CorrelationFilter filter = (CorrelationFilter) registration.getFilter();
              assertThat(filter.trustInboundHeaders()).isTrue();
            });
  }

  @Test
  void doesNotRegisterInANonWebContext() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(HubCommonAutoConfiguration.class))
        .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
  }
}
