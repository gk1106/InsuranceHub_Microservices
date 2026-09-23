package com.insurancehub.policy.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

// Same required fields as CreatePolicyRequest (api-contract.md §3 "Required for 01/02") minus
// policyNum - the renewal endpoint takes it from the path, not the body.
public record RenewPolicyRequest(
    String applicationNum,
    @NotBlank String cif,
    String accountNum,
    @NotBlank String insuredName,
    String mobileNum,
    String address,
    @NotBlank String insuranceType,
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
    @NotNull LocalDate startDate,
    @NotNull LocalDate expiryDate,
    @NotNull BigDecimal netPremium,
    BigDecimal gstAmt,
    @NotNull BigDecimal grossPremium,
    @NotNull BigDecimal sumInsured,
    BigDecimal commissionPer,
    BigDecimal commissionAmt) {}
