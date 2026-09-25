package com.insurancehub.claims.infrastructure.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

// Raw @HttpExchange interface - HttpServiceProxyFactory creates the implementing JDK proxy
// (see config.PolicyServiceClientConfig). @CircuitBreaker/@Retry apply directly here: Spring
// AOP matches annotations declared on the interfaces a bean's proxy implements, a standard
// pattern for @HttpExchange/Feign-style clients (confirmed by ClaimRegistrationResilienceIT,
// not merely assumed). retryExceptions/recordExceptions are allow-lists configured in
// application.yml to exactly {HttpServerErrorException.ServiceUnavailable,
// ResourceAccessException} - a 404 matches neither, so it's never retried and never counts
// toward the circuit breaker's failure rate (cross-cutting.md §6: "never retry on 4xx").
//
// Aspect order, confirmed by decompiling resilience4j-spring6 2.4.0 (not assumed): Retry's
// default aspect order is 2147483642, CircuitBreaker's is 2147483643 - lower runs outermost in
// Spring AOP, so Retry wraps CircuitBreaker. Each of Retry's up-to-3 attempts therefore
// re-enters the CircuitBreaker aspect separately, so the 50%-failure-rate-over-a-20-call
// sliding window (application.yml) counts individual HTTP attempts, not logical
// registration calls - one logical call that exhausts all 3 retries contributes 3 window
// entries, not 1. See application.yml for the worst-case latency this implies.
public interface PolicyServiceHttpApi {

  @CircuitBreaker(name = "policyService")
  @Retry(name = "policyService")
  // Phase 8 gap-fill: hub-gateway's own downstream clients already had a bulkhead
  // (cross-cutting.md §6); this one didn't. Bounds concurrent in-flight coverage calls so one
  // slow policy-service instance can't exhaust claims-service's own request-handling capacity.
  @Bulkhead(name = "policyService")
  @GetExchange("/internal/policies/{policyNum}/coverage")
  PolicyCoverageResponse getCoverage(
      @PathVariable String policyNum,
      // Without an explicit format, LocalDate serialized to a query param falls back to a
      // locale-specific dd/MM/yy - found at runtime (WireMock logged the literal request URL),
      // not assumed. policy-service's coverage endpoint expects ISO yyyy-MM-dd.
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate onDate);
}
