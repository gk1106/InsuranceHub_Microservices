package com.insurancehub.gateway.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// Application-owned - deliberately not policy-service's own wire DTO. PolicyServiceGateway (a
// port) may not depend on infrastructure/client's wire DTOs (ArchUnit: infrastructure may not be
// accessed by any of the other three layers), so the port speaks in its own typed terms and the
// infrastructure adapter translates to/from the wire shape privately.
public record CreatePolicyCommand(
    String policyNum,
    String applicationNum,
    String cif,
    String accountNum,
    String insuredName,
    String mobileNum,
    String address,
    String insuranceType,
    String insuranceName,
    String regionCode,
    String regionName,
    String branchCode,
    String branchName,
    String loanAcctNum,
    String specPerNum,
    String specPerName,
    String applicationStatus,
    LocalDate issueDate,
    LocalDate startDate,
    LocalDate expiryDate,
    BigDecimal netPremium,
    BigDecimal gstAmt,
    BigDecimal grossPremium,
    BigDecimal sumInsured,
    BigDecimal commissionPer,
    BigDecimal commissionAmt) {}
