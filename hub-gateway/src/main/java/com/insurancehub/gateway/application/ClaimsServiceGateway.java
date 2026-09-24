package com.insurancehub.gateway.application;

public interface ClaimsServiceGateway {

  ClaimsServiceResult registerClaim(RegisterClaimCommand command);

  ClaimsServiceResult updateClaimStatus(UpdateClaimStatusCommand command);
}
