package com.insurancehub.common.logging;

// Single generic masking algorithm applied uniformly to cif/accountNum/loanAcctNum/mobileNum/
// name/address before logging (CLAUDE.md rule 4). Deliberately field-agnostic - no per-field
// methods - so this stays a utility, not domain logic (rule 2).
public final class PiiMasker {

  private static final int VISIBLE_PREFIX = 2;
  private static final int VISIBLE_SUFFIX = 2;
  private static final String MASK_CHAR = "*";

  private PiiMasker() {}

  // "9876543210" -> "98******10". Values too short to leave a masked middle are masked
  // entirely, so a short value never leaks more than its length.
  public static String mask(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    int length = value.length();
    if (length <= VISIBLE_PREFIX + VISIBLE_SUFFIX) {
      return MASK_CHAR.repeat(length);
    }
    String prefix = value.substring(0, VISIBLE_PREFIX);
    String suffix = value.substring(length - VISIBLE_SUFFIX);
    String middle = MASK_CHAR.repeat(length - VISIBLE_PREFIX - VISIBLE_SUFFIX);
    return prefix + middle + suffix;
  }
}
