package com.insurancehub.gateway.infrastructure.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;

// The "claimsService" retry instance's own allow-list (application.yml) deliberately excludes
// HttpServerErrorException.ServiceUnavailable, unlike "policyService" - see
// config.ClaimsServiceClientConfig's neighbouring application.yml comment for why. @RequestBody
// is required, not inferred - see PolicyServiceHttpApi.
public interface ClaimsServiceHttpApi {

  @CircuitBreaker(name = "claimsService")
  @Retry(name = "claimsService")
  @Bulkhead(name = "claimsService")
  @PostExchange("/internal/claims")
  RegisterClaimResponse registerClaim(@RequestBody RegisterClaimRequest request);

  @CircuitBreaker(name = "claimsService")
  @Retry(name = "claimsService")
  @Bulkhead(name = "claimsService")
  @PatchExchange("/internal/claims/{claimNum}/status")
  UpdateClaimStatusResponse updateClaimStatus(
      @PathVariable String claimNum, @RequestBody UpdateClaimStatusRequest request);
}
