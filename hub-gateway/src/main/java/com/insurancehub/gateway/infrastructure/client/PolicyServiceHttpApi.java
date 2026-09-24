package com.insurancehub.gateway.infrastructure.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

// @CircuitBreaker/@Retry/@Bulkhead apply directly on the @HttpExchange interface, matching
// claims-service's PolicyServiceHttpApi pattern from phase 4a/4b - Spring AOP matches these
// annotations on the interface a bean's proxy implements. @RequestBody is required here, not
// inferred - confirmed at runtime (IllegalStateException: "Could not resolve parameter... No
// suitable resolver") that HttpServiceProxyFactory does NOT treat an unannotated parameter as
// the body automatically, unlike Spring MVC controller methods.
public interface PolicyServiceHttpApi {

  @CircuitBreaker(name = "policyService")
  @Retry(name = "policyService")
  @Bulkhead(name = "policyService")
  @PostExchange("/internal/policies")
  CreatePolicyResponse createPolicy(@RequestBody CreatePolicyRequest request);

  @CircuitBreaker(name = "policyService")
  @Retry(name = "policyService")
  @Bulkhead(name = "policyService")
  @PostExchange("/internal/policies/{policyNum}/renewals")
  RenewPolicyResponse renewPolicy(
      @PathVariable String policyNum, @RequestBody RenewPolicyRequest request);
}
