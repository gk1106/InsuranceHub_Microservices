package com.insurancehub.policy.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// termStart/termExpiry/sumInsured are null when active is false - they're term-specific and
// no term covers onDate in that case. insuranceType comes from the policy row (constant across
// terms) so it's always populated.
public record CoverageResult(
    String policyNum,
    boolean active,
    LocalDate termStart,
    LocalDate termExpiry,
    BigDecimal sumInsured,
    String insuranceType) {

  public static CoverageResult active(
      String policyNum,
      LocalDate termStart,
      LocalDate termExpiry,
      BigDecimal sumInsured,
      String insuranceType) {
    return new CoverageResult(policyNum, true, termStart, termExpiry, sumInsured, insuranceType);
  }

  public static CoverageResult inactive(String policyNum, String insuranceType) {
    return new CoverageResult(policyNum, false, null, null, null, insuranceType);
  }
}
