package com.insurancehub.gateway.mapping;

import com.insurancehub.gateway.application.UpdateClaimStatusCommand;
import com.insurancehub.gateway.domain.RawClaimDetails;
import org.springframework.stereotype.Component;

@Component
public class ClaimStatusRequestMapper {

  public UpdateClaimStatusCommand toCommand(RawClaimDetails claim) {
    return new UpdateClaimStatusCommand(
        claim.claimNum(),
        claim.policyNum(),
        claim.claimStatus(),
        ExternalAmountConverter.parse(claim.settledAmt()),
        claim.claimCode(),
        ExternalDateConverter.parse(claim.finalizationDate()),
        parseIntOrNull(claim.osAgeing()),
        claim.requireDetails(),
        ExternalDateConverter.parse(claim.repuCancelDate()),
        claim.reasonOfClosure());
  }

  private static Integer parseIntOrNull(String value) {
    return value == null || value.isBlank() ? null : Integer.valueOf(value);
  }
}
