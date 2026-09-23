package com.insurancehub.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// HubStartupGuard already carries its own @Configuration + @EnableConfigurationProperties, so
// it can be used directly as the runner's user configuration - no separate test config needed.
class HubStartupGuardTest {

  private static final String[] INSURER_PROPS = {
    "hub.insurers[0].insp-id=INSP001",
    "hub.insurers[0].insp-name=universalsompo",
    "hub.insurers[0].oauth-client-id=insp001-client",
    "hub.insurers[0].allowed-cidrs[0]=0.0.0.0/0"
  };

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(HubStartupGuard.class);

  @Test
  void failsStartupWhenCryptoIsDisabledOutsideLocal() {
    contextRunner
        .withPropertyValues("hub.crypto.enabled=false")
        .withPropertyValues(INSURER_PROPS)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("hub.crypto.enabled=false");
            });
  }

  @Test
  void failsStartupWhenAnInsurerStillHasThePlaceholderCidrOutsideLocal() {
    contextRunner
        .withPropertyValues("hub.crypto.enabled=true")
        .withPropertyValues(INSURER_PROPS)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("0.0.0.0/0");
            });
  }

  @Test
  void allowsBothInLocalProfile() {
    contextRunner
        .withPropertyValues("hub.crypto.enabled=false")
        .withPropertyValues(INSURER_PROPS)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
        .run(context -> assertThat(context).hasNotFailed());
  }
}
