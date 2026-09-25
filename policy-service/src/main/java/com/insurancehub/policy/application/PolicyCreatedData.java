package com.insurancehub.policy.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// service-design.md §5: identifiers, dates, amounts, statuses only - never insuredName,
// mobileNum, cif, accountNum, loanAcctNum or address (SKILL.md rule 4's exact PII list).
public record PolicyCreatedData(
    String policyNum,
    int termNo,
    String insuranceType,
    String applicationStatus,
    LocalDate issueDate,
    LocalDate startDate,
    LocalDate expiryDate,
    BigDecimal netPremium,
    BigDecimal grossPremium,
    BigDecimal sumInsured) {}
