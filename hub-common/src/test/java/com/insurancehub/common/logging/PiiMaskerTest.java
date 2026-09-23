package com.insurancehub.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PiiMaskerTest {

  @ParameterizedTest
  @CsvSource({
    "9876543210, 98******10",
    "CIF456789, CI*****89",
    "AB, **",
    "A, *",
    "ABCD, ****",
    "ABCDE, AB*DE"
  })
  void masksAllButTheFirstAndLastTwoCharacters(String input, String expected) {
    assertThat(PiiMasker.mask(input)).isEqualTo(expected);
  }

  @Test
  void nullAndEmptyPassThroughUnchanged() {
    assertThat(PiiMasker.mask(null)).isNull();
    assertThat(PiiMasker.mask("")).isEmpty();
  }
}
