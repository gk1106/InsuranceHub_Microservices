package com.insurancehub.gateway.mapping;

import com.insurancehub.gateway.application.RegisterClaimCommand;
import com.insurancehub.gateway.domain.RawClaimDetails;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import org.springframework.stereotype.Component;

// 03's policyNum comes from policyDetails, not claimDetails (api-contract.md §3/§7) - the only
// field this mapper reads off RawPolicyDetails at all.
@Component
public class ClaimRegistrationRequestMapper {

  public RegisterClaimCommand toCommand(RawPolicyDetails policyDetails, RawClaimDetails claim) {
    return new RegisterClaimCommand(
        policyDetails.policyNum(),
        claim.claimNum(),
        claim.claimType(),
        claim.lossDisc(),
        claim.natureOfLoss(),
        claim.lossCity(),
        ExternalDateConverter.parse(claim.dateOfLoss()),
        ExternalDateConverter.parse(claim.intimationDate()),
        ExternalAmountConverter.parse(claim.claimedAmt()),
        claim.claimStatus(),
        claim.claimCode());
  }
}
