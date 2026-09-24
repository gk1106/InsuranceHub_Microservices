package com.insurancehub.gateway.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// Same shape as CreatePolicyCommand minus policyNum, which the mapper reads separately to build
// the path variable for policy-service's POST /internal/policies/{policyNum}/renewals.
public record RenewPolicyCommand(
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
