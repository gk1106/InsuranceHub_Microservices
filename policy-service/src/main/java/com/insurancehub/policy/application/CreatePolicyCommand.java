package com.insurancehub.policy.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// reqId/inspId/txnId come from the internal request's X-Req-Id/X-Insp-Id/X-Txn-Id headers
// (service-design.md §1), never from the body.
public record CreatePolicyCommand(
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
