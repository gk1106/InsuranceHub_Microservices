package com.insurancehub.claims.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// service-design.md §5: identifiers, dates, amounts, statuses only - never lossDesc/lossCity
// (free text, not in SKILL.md rule 4's PII list by name, but excluded anyway - only fields
// api-contract.md itself would call identifying/business data belong here).
public record ClaimRegisteredData(
    String claimNum,
    String policyNum,
    String claimType,
    LocalDate dateOfLoss,
    LocalDate intimationDate,
    BigDecimal claimedAmt,
    String claimStatus) {}
