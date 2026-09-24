package com.insurancehub.gateway.mapping;

import com.insurancehub.gateway.application.RenewPolicyCommand;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import org.springframework.stereotype.Component;

@Component
public class RenewalRequestMapper {

  public RenewPolicyCommand toCommand(RawPolicyDetails details) {
    return new RenewPolicyCommand(
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
