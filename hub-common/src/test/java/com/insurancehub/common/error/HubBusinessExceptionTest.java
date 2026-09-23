package com.insurancehub.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HubBusinessExceptionTest {

  @Test
  void carriesTheCodeAndSafeDetail() {
    HubBusinessException ex =
        new HubBusinessException(HubErrorCode.POLICY_ALREADY_EXISTS, "POL445566");

    assertThat(ex.code()).isEqualTo(HubErrorCode.POLICY_ALREADY_EXISTS);
    assertThat(ex.safeDetail()).isEqualTo("POL445566");
    assertThat(ex.getMessage()).contains("POLICY_ALREADY_EXISTS").contains("POL445566");
  }
}
