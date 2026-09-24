package com.insurancehub.gateway.infrastructure.client;

import java.math.BigDecimal;
import java.time.LocalDate;

// Gateway-owned copy of policy-service's own api.RenewPolicyRequest. policyNum isn't a field
// here either - PolicyServiceClient sends it as the path variable, read off the Command.
public record RenewPolicyRequest(
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
