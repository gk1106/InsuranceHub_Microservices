package com.insurancehub.gateway.api;

import com.insurancehub.common.error.HubResponse;
import com.insurancehub.gateway.application.ClaimsServiceGateway;
import com.insurancehub.gateway.domain.HubServiceCode;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.mapping.ClaimStatusRequestMapper;
import org.springframework.stereotype.Component;

@Component
public class ClaimStatusCodeHandler implements CodeHandler {

  private final ClaimStatusRequestMapper mapper;
  private final ClaimsServiceGateway claimsServiceGateway;

  public ClaimStatusCodeHandler(
      ClaimStatusRequestMapper mapper, ClaimsServiceGateway claimsServiceGateway) {
    this.mapper = mapper;
    this.claimsServiceGateway = claimsServiceGateway;
  }

  @Override
  public HubServiceCode code() {
    return HubServiceCode.CLAIM_STATUS_UPDATE;
  }

  @Override
  public HubResponse handle(RawHubRequestBody body, String txnId) {
    var command = mapper.toCommand(body.claimDetails());
    var result = claimsServiceGateway.updateClaimStatus(command);
    return HubResponse.success(result.txnId(), body.header().reqId());
  }
}
