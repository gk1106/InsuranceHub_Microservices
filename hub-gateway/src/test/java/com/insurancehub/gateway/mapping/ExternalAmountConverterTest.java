package com.insurancehub.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExternalAmountConverterTest {

  @Test
  void parsesAWholeNumber() {
    assertThat(ExternalAmountConverter.parse("15000")).isEqualByComparingTo("15000");
  }

  @Test
  void parsesADecimalAmount() {
    assertThat(ExternalAmountConverter.parse("15000.50")).isEqualByComparingTo("15000.50");
  }

  @Test
  void blankAndNullParseToNull() {
    assertThat(ExternalAmountConverter.parse(null)).isNull();
    assertThat(ExternalAmountConverter.parse("")).isNull();
  }

  @Test
  void formatIsThePlainStringForm() {
    assertThat(ExternalAmountConverter.format(new BigDecimal("15000.50"))).isEqualTo("15000.50");
  }

  @Test
  void formatOfNullIsNull() {
    assertThat(ExternalAmountConverter.format(null)).isNull();
  }
}
