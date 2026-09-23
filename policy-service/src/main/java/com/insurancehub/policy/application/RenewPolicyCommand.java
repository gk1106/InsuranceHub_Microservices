package com.insurancehub.policy.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// Same field shape as CreatePolicyCommand (the renewal payload carries the full policyDetails
// object too, api-contract.md §3) but its own type: PolicyRenewalService.renew(CreatePolicyCommand)
// would misname the operation and couple the two use cases' payloads together.
public record RenewPolicyCommand(
    String reqId,
    String inspId,
    String txnId,
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
