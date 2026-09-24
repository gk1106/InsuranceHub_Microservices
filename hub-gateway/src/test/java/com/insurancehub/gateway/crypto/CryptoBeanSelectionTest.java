package com.insurancehub.gateway.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.gateway.config.HubCryptoProperties;
import com.insurancehub.gateway.config.InsurerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

// NoOpHubCryptoService's condition (havingValue = "false", matchIfMissing = true) and
// NimbusHubCryptoService's (havingValue = "true", no matchIfMissing) are complementary by
// construction - every value of hub.crypto.enabled, including absent, should match exactly one.
// Registers the REAL classes (withUserConfiguration honors their own @ConditionalOnProperty
// annotations directly - no re-declaring the condition strings here, which would silently drift
// from the real ones), proving it rather than trusting the reasoning: never zero candidates (the
// original phase-5 bug this guards against), never two.
class CryptoBeanSelectionTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              Config.class,
              NoOpHubCryptoService.class,
              KeyRegistry.class,
              NimbusHubCryptoService.class)
          .withPropertyValues(
              "hub.insurers[0].insp-id=INSP001",
              "hub.insurers[0].insp-name=universalsompo",
              "hub.insurers[0].oauth-client-id=insp001-client",
              "hub.insurers[0].allowed-cidrs[0]=0.0.0.0/0");

  @Test
  void enabledTrueSelectsTheRealImplementation() {
    contextRunner
        .withPropertyValues("hub.crypto.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(HubCryptoService.class);
              assertThat(context.getBean(HubCryptoService.class))
                  .isInstanceOf(NimbusHubCryptoService.class);
            });
  }

  @Test
  void enabledFalseSelectsTheNoOpImplementation() {
    contextRunner
        .withPropertyValues("hub.crypto.enabled=false")
        .run(
            context -> {
              assertThat(context).hasSingleBean(HubCryptoService.class);
              assertThat(context.getBean(HubCryptoService.class))
                  .isInstanceOf(NoOpHubCryptoService.class);
            });
  }

  @Test
  void aMissingPropertyStillLeavesExactlyOneBeanNotZero() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(HubCryptoService.class);
          assertThat(context.getBean(HubCryptoService.class))
              .isInstanceOf(NoOpHubCryptoService.class);
        });
  }

  @Configuration
  @EnableConfigurationProperties({HubCryptoProperties.class, InsurerProperties.class})
  static class Config {}
}
