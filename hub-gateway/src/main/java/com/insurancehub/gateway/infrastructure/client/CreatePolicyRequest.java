package com.insurancehub.gateway.infrastructure.client;

import java.math.BigDecimal;
import java.time.LocalDate;

// Gateway-owned copy of policy-service's own api.CreatePolicyRequest - duplicated, not a compile
// dependency on policy-service (docs/adr/0003-ports-and-adapters.md rule 2, same reasoning
// claims-service already applies to PolicyCoverageResponse).
public record CreatePolicyRequest(
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
