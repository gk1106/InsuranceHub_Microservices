package com.insurancehub.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UlidTest {

  private static final byte[] ZERO_RANDOMNESS = new byte[10];

  @Test
  void allZeroInputEncodesToAllZeroCharacters() {
    assertThat(Ulid.encode(0L, ZERO_RANDOMNESS)).isEqualTo("0".repeat(26));
  }

  @Test
  void isAlways26CrockfordBase32Characters() {
    String id = Ulid.generate();

    assertThat(id).hasSize(26);
    assertThat(id).matches("[0-9A-HJKMNP-TV-Z]{26}");
  }

  @Test
  void isLexicographicallySortableByTimestamp() {
    String earlier = Ulid.encode(1_000_000L, ZERO_RANDOMNESS);
    String later = Ulid.encode(2_000_000L, ZERO_RANDOMNESS);

    assertThat(earlier.compareTo(later)).isLessThan(0);
  }

  @Test
  void differsOnlyInRandomnessWhenTimestampIsTheSame() {
    byte[] randomnessA = new byte[10];
    byte[] randomnessB = new byte[10];
    randomnessB[9] = 1;

    String a = Ulid.encode(1_000_000L, randomnessA);
    String b = Ulid.encode(1_000_000L, randomnessB);

    assertThat(a).isNotEqualTo(b);
    assertThat(a.substring(0, 10)).isEqualTo(b.substring(0, 10)); // same 48-bit timestamp prefix
  }

  @Test
  void rejectsWrongSizedRandomness() {
    assertThatThrownBy(() -> Ulid.encode(0L, new byte[9]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
