package com.insurancehub.gateway.mapping;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

// api-contract.md §3: dd/MM/yyyy, both directions. parse() is also the single source of truth
// for "is this a real calendar date" - reused by ValidExternalDate's ConstraintValidator so the
// same check runs at validation time (reject early) and mapping time (parse for real, guaranteed
// to succeed since validation already passed).
//
// Two things confirmed empirically, not assumed: (1) DateTimeFormatter's default SMART resolver
// does NOT reject a pattern-matching-but-impossible date like 30/02/2024 - it silently CLAMPS
// day-of-month to the nearest valid value instead (parses as 29/02/2024), so ResolverStyle.STRICT
// is required to actually reject it. (2) STRICT mode combined with the "yyyy" (year-of-era)
// pattern letter then fails to parse even an ordinary valid date, since year-of-era needs an era
// STRICT won't infer on its own - "uuuu" (proleptic year) is required instead. Both confirmed by
// a standalone reproduction before relying on either.
public final class ExternalDateConverter {

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

  private ExternalDateConverter() {}

  public static LocalDate parse(String value) throws DateTimeParseException {
    if (value == null || value.isBlank()) {
      return null;
    }
    return LocalDate.parse(value, FORMAT);
  }

  public static String format(LocalDate date) {
    return date == null ? null : FORMAT.format(date);
  }
}
