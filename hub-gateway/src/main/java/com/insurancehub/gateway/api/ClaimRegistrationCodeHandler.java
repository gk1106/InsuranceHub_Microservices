package com.insurancehub.gateway.api;

import com.insurancehub.common.error.HubResponse;
import com.insurancehub.gateway.application.ClaimsServiceGateway;
import com.insurancehub.gateway.domain.HubServiceCode;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.mapping.ClaimRegistrationRequestMapper;
import org.springframework.stereotype.Component;

@Component
public class ClaimRegistrationCodeHandler implements CodeHandler {

  private final ClaimRegistrationRequestMapper mapper;
  private final ClaimsServiceGateway claimsServiceGateway;

  public ClaimRegistrationCodeHandler(
      ClaimRegistrationRequestMapper mapper, ClaimsServiceGateway claimsServiceGateway) {
    this.mapper = mapper;
    this.claimsServiceGateway = claimsServiceGateway;
  }

  @Override
  public HubServiceCode code() {
    return HubServiceCode.CLAIM_REGISTER;
  }

  @Override
  public HubResponse handle(RawHubRequestBody body, String txnId) {
    var command = mapper.toCommand(body.policyDetails(), body.claimDetails());
    var result = claimsServiceGateway.registerClaim(command);
    return HubResponse.success(result.txnId(), body.header().reqId());
  }
}
