package com.insurancehub.gateway.mapping;

import com.insurancehub.gateway.domain.RawPolicyDetails;
import java.math.BigDecimal;
import java.time.LocalDate;

// Shared field-parsing logic behind NewPolicyRequestMapper and RenewalRequestMapper - their
// target Command types (CreatePolicyCommand/RenewPolicyCommand) stay deliberately distinct
// (docs/progress.md phase 3 notes: mirrors PolicyCreationService's shape rather than reusing its
// types, "naming the actual use case, not coupling renewal's payload shape to create's"), but the
// RawPolicyDetails -> parsed-field conversion itself is identical, so it lives here once.
record PolicyDetailsFields(
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
    BigDecimal commissionAmt) {

  static PolicyDetailsFields from(RawPolicyDetails details) {
    return new PolicyDetailsFields(
        details.policyNum(),
        details.applicationNum(),
        details.cif(),
        details.accountNum(),
        details.name(),
        details.mobileNum(),
        details.address(),
        details.insuranceType(),
        details.insuranceName(),
        details.regionCode(),
        details.regionName(),
        details.branchCode(),
        details.branchName(),
        details.loanAcctNum(),
        details.specPerNum(),
        details.specPerName(),
        details.applicationStatus(),
        ExternalDateConverter.parse(details.issueDate()),
        ExternalDateConverter.parse(details.startDate()),
        ExternalDateConverter.parse(details.expiryDate()),
        ExternalAmountConverter.parse(details.netPremium()),
        ExternalAmountConverter.parse(details.gstAmt()),
        ExternalAmountConverter.parse(details.grossPremium()),
        ExternalAmountConverter.parse(details.sumInsured()),
        ExternalAmountConverter.parse(details.commissionPer()),
        ExternalAmountConverter.parse(details.commissionAmt()));
  }
}
