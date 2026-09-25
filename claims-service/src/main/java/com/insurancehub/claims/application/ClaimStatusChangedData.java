package com.insurancehub.claims.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// service-design.md §5: identifiers, dates, amounts, statuses only.
public record ClaimStatusChangedData(
    String claimNum,
    String policyNum,
    String fromStatus,
    String toStatus,
    BigDecimal settledAmt,
    String claimCode,
    LocalDate finalizationDate) {}
