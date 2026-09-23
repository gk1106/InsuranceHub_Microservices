package com.insurancehub.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HubErrorCodeTest {

  @ParameterizedTest
  @EnumSource(HubErrorCode.class)
  void respCodeIsDerivedFromHttpStatusAndCanNeverDrift(HubErrorCode code) {
    assertThat(code.respCode()).isEqualTo(String.valueOf(code.httpStatus().value()));
  }

  @ParameterizedTest
  @EnumSource(HubErrorCode.class)
  void errorDescIsNeverBlank(HubErrorCode code) {
    assertThat(code.errorDesc()).isNotBlank();
  }
}
