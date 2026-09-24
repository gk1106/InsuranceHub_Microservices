package com.insurancehub.gateway.api;

import com.insurancehub.common.error.HubResponse;
import com.insurancehub.gateway.application.PolicyServiceGateway;
import com.insurancehub.gateway.domain.HubServiceCode;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.mapping.RenewalRequestMapper;
import org.springframework.stereotype.Component;

@Component
public class RenewalCodeHandler implements CodeHandler {

  private final RenewalRequestMapper mapper;
  private final PolicyServiceGateway policyServiceGateway;

  public RenewalCodeHandler(
      RenewalRequestMapper mapper, PolicyServiceGateway policyServiceGateway) {
    this.mapper = mapper;
    this.policyServiceGateway = policyServiceGateway;
  }

  @Override
  public HubServiceCode code() {
    return HubServiceCode.RENEWAL;
  }

  @Override
  public HubResponse handle(RawHubRequestBody body, String txnId) {
    var command = mapper.toCommand(body.policyDetails());
    var result = policyServiceGateway.renewPolicy(command);
    return HubResponse.success(result.txnId(), body.header().reqId());
  }
}
