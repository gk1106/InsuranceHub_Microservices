package com.insurancehub.policy.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// Same shape as PolicyCreatedData (termNo now > 1) - kept as its own type, not reused, since the
// two events are named for the actual use case they represent (docs/progress.md phase 3 notes:
// the same "mirror the use case, don't couple the payload shape" reasoning already applied to
// CreatePolicyCommand/RenewPolicyCommand).
public record PolicyRenewedData(
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
