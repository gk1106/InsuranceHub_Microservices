package com.insurancehub.gateway.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class KeyRegistryHealthIndicatorTest {

  @Test
  void downWhenTheRegistryNeverLoaded() {
    KeyRegistry keyRegistry = mock(KeyRegistry.class);
    when(keyRegistry.isLoaded()).thenReturn(false);

    var health = new KeyRegistryHealthIndicator(keyRegistry).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void upWhenTheRegistryLoaded() {
    KeyRegistry keyRegistry = mock(KeyRegistry.class);
    when(keyRegistry.isLoaded()).thenReturn(true);

    var health = new KeyRegistryHealthIndicator(keyRegistry).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }
}
