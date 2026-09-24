package com.insurancehub.gateway.api;

import com.insurancehub.common.error.HubResponse;
import com.insurancehub.gateway.application.PolicyServiceGateway;
import com.insurancehub.gateway.domain.HubServiceCode;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.mapping.NewPolicyRequestMapper;
import org.springframework.stereotype.Component;

@Component
public class NewPolicyCodeHandler implements CodeHandler {

  private final NewPolicyRequestMapper mapper;
  private final PolicyServiceGateway policyServiceGateway;

  public NewPolicyCodeHandler(
      NewPolicyRequestMapper mapper, PolicyServiceGateway policyServiceGateway) {
    this.mapper = mapper;
    this.policyServiceGateway = policyServiceGateway;
  }

  @Override
  public HubServiceCode code() {
    return HubServiceCode.NEW_POLICY;
  }

  @Override
  public HubResponse handle(RawHubRequestBody body) {
    var command = mapper.toCommand(body.policyDetails());
    var result = policyServiceGateway.createPolicy(command);
    // The ORIGINAL txnId on a replay, not this attempt's - api-contract.md §4.
    return HubResponse.success(result.txnId(), body.header().reqId());
  }
}
