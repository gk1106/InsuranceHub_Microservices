package com.insurancehub.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

class ExternalDateConverterTest {

  @Test
  void parsesAWellFormedDate() {
    assertThat(ExternalDateConverter.parse("15/05/2024")).isEqualTo(LocalDate.of(2024, 5, 15));
  }

  @Test
  void blankAndNullParseToNull() {
    assertThat(ExternalDateConverter.parse(null)).isNull();
    assertThat(ExternalDateConverter.parse("")).isNull();
    assertThat(ExternalDateConverter.parse("  ")).isNull();
  }

  @Test
  void rejectsAPatternMatchingButImpossibleDate() {
    // 30/02/2024 matches dd/MM/yyyy but February never has a 30th.
    assertThatThrownBy(() -> ExternalDateConverter.parse("30/02/2024"))
        .isInstanceOf(DateTimeParseException.class);
  }

  @Test
  void rejectsTheWrongFormat() {
    assertThatThrownBy(() -> ExternalDateConverter.parse("2024-05-15"))
        .isInstanceOf(DateTimeParseException.class);
  }

  @Test
  void formatIsTheInverseOfParse() {
    assertThat(ExternalDateConverter.format(LocalDate.of(2024, 5, 15))).isEqualTo("15/05/2024");
  }

  @Test
  void formatOfNullIsNull() {
    assertThat(ExternalDateConverter.format(null)).isNull();
  }
}
