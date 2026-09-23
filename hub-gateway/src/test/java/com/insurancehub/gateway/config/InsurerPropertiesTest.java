package com.insurancehub.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InsurerPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void bindsAllThreeKnownInsurersFromTheBaseConfig() {
    contextRunner
        .withPropertyValues(
            "hub.insurers[0].insp-id=INSP001",
            "hub.insurers[0].insp-name=universalsompo",
            "hub.insurers[0].oauth-client-id=insp001-client",
            "hub.insurers[0].allowed-cidrs[0]=0.0.0.0/0",
            "hub.insurers[1].insp-id=INSP002",
            "hub.insurers[1].insp-name=sbigeneral",
            "hub.insurers[1].oauth-client-id=insp002-client",
            "hub.insurers[1].allowed-cidrs[0]=0.0.0.0/0",
            "hub.insurers[2].insp-id=INSP003",
            "hub.insurers[2].insp-name=nivabupa",
            "hub.insurers[2].oauth-client-id=insp003-client",
            "hub.insurers[2].allowed-cidrs[0]=0.0.0.0/0")
        .run(
            context -> {
              InsurerProperties properties = context.getBean(InsurerProperties.class);
              assertThat(properties.insurers()).hasSize(3);
              assertThat(properties.insurers())
                  .extracting(InsurerProperties.InsurerConfig::inspId)
                  .containsExactly("INSP001", "INSP002", "INSP003");
              assertThat(properties.insurers().get(0).oauthClientId()).isEqualTo("insp001-client");
              assertThat(properties.insurers().get(0).allowedCidrs()).containsExactly("0.0.0.0/0");
            });
  }

  @Configuration
  @EnableConfigurationProperties(InsurerProperties.class)
  static class Config {}
}
