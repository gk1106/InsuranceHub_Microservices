package com.insurancehub.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

// A plain property-binding check, not a real 15s+ wait: proves the bound claims-service.
// read-timeout in the shipped application.yml exceeds claims-service's own documented 15.6s
// worst case (that file's own comment, application.yml here), so the gateway can never time out
// and answer the insurer before claims-service's own retry budget could possibly finish.
class ClaimsServiceClientPropertiesTest {

  @Test
  void readTimeoutExceedsClaimsServicesOwn156SecondWorstCase() throws Exception {
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    List<PropertySource<?>> sources =
        loader.load("application", new ClassPathResource("application.yml"));
    MutablePropertySources propertySources = new MutablePropertySources();
    sources.forEach(propertySources::addLast);
    Binder binder =
        new Binder(
            org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(
                propertySources));

    ClaimsServiceClientProperties properties =
        binder.bind("claims-service", ClaimsServiceClientProperties.class).get();

    assertThat(properties.readTimeout()).isGreaterThan(Duration.ofMillis(15_600));
  }
}
