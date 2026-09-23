package com.insurancehub.policy.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

// Required fields per api-contract.md §3 "Required for 01/02", minus the header fields
// (reqId/inspId come from X-Req-Id/X-Insp-Id, not the body).
public record CreatePolicyRequest(
    @NotBlank String policyNum,
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
