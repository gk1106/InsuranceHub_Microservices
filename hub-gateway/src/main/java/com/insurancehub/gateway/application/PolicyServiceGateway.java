package com.insurancehub.gateway.application;

// Port - implemented by infrastructure/client/PolicyServiceClient. No fallbackMethod on either
// implementation call; resilience is declared on the underlying @HttpExchange interface, and
// failures surface here as HubBusinessException(DOWNSTREAM_UNAVAILABLE, ...) or the specific
// business code the ProblemDetail carried.
public interface PolicyServiceGateway {

  PolicyServiceResult createPolicy(CreatePolicyCommand command);

  PolicyServiceResult renewPolicy(RenewPolicyCommand command);
}
