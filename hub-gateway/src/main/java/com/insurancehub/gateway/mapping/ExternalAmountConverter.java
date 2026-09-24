package com.insurancehub.gateway.mapping;

import java.math.BigDecimal;

// api-contract.md §3: ^\d{1,13}(\.\d{1,2})?$, both directions. Empty-string-as-null is handled
// here too (parse("")==null) - the per-group Bean Validation pass already guarantees a required
// field isn't blank by the time this runs, and an irrelevant field being "" (e.g. claimDetails
// on 01/02) is exactly what parse() turns into a plain null instead of a parse failure.
public final class ExternalAmountConverter {

  private ExternalAmountConverter() {}

  public static BigDecimal parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return new BigDecimal(value);
  }

  public static String format(BigDecimal amount) {
    return amount == null ? null : amount.toPlainString();
  }
}
