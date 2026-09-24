package com.insurancehub.gateway.mapping;

import com.insurancehub.gateway.application.CreatePolicyCommand;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import org.springframework.stereotype.Component;

// Raw validated Strings -> the typed Command. Safe to parse without a try/catch here:
// HubRequestValidator has already guaranteed every field this reads is either well-formed or
// legitimately blank (empty-string-as-null, ExternalDateConverter/ExternalAmountConverter's own
// null-on-blank behavior).
@Component
public class NewPolicyRequestMapper {

  public CreatePolicyCommand toCommand(RawPolicyDetails details) {
    PolicyDetailsFields f = PolicyDetailsFields.from(details);
    return new CreatePolicyCommand(
        f.policyNum(),
        f.applicationNum(),
        f.cif(),
        f.accountNum(),
        f.insuredName(),
        f.mobileNum(),
        f.address(),
        f.insuranceType(),
        f.insuranceName(),
        f.regionCode(),
        f.regionName(),
        f.branchCode(),
        f.branchName(),
        f.loanAcctNum(),
        f.specPerNum(),
        f.specPerName(),
        f.applicationStatus(),
        f.issueDate(),
        f.startDate(),
        f.expiryDate(),
        f.netPremium(),
        f.gstAmt(),
        f.grossPremium(),
        f.sumInsured(),
        f.commissionPer(),
        f.commissionAmt());
  }
}
