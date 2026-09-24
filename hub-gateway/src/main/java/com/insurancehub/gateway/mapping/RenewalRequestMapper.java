package com.insurancehub.gateway.mapping;

import com.insurancehub.gateway.application.RenewPolicyCommand;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import org.springframework.stereotype.Component;

@Component
public class RenewalRequestMapper {

  public RenewPolicyCommand toCommand(RawPolicyDetails details) {
    PolicyDetailsFields f = PolicyDetailsFields.from(details);
    return new RenewPolicyCommand(
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
