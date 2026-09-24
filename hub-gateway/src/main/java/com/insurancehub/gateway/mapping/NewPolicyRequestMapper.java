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
    return new CreatePolicyCommand(
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
