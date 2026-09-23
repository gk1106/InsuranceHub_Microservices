package com.insurancehub.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HubErrorCodeTest {

  // respCode is a stored field, not derived from httpStatus (see HubErrorCode's class
  // comment - docs/open-questions.md Q1/Q2 may force them to diverge later). This test just
  // records that, as of today, every entry's respCode matches its httpStatus's numeric code -
  // if the bank's answer to Q1/Q2 makes one diverge, update the constant, and this test will
  // tell you exactly which one still needs to.
  @ParameterizedTest
  @EnumSource(HubErrorCode.class)
  void respCodeCurrentlyMatchesHttpStatus(HubErrorCode code) {
    assertThat(code.respCode()).isEqualTo(String.valueOf(code.httpStatus().value()));
  }

  @ParameterizedTest
  @EnumSource(HubErrorCode.class)
  void errorDescIsNeverBlank(HubErrorCode code) {
    assertThat(code.errorDesc()).isNotBlank();
  }
}
