package com.insurancehub.claims.infrastructure.client;

import static com.insurancehub.common.error.HubErrorCode.DOWNSTREAM_UNAVAILABLE;
import static com.insurancehub.common.error.HubErrorCode.POLICY_NOT_FOUND;

import com.insurancehub.claims.application.CoverageStatus;
import com.insurancehub.claims.application.PolicyCoverageGateway;
import com.insurancehub.common.error.HubBusinessException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

// Plain, non-annotated adapter - deliberately not itself carrying @CircuitBreaker/@Retry
// (those live on PolicyServiceHttpApi). Calling policyServiceHttpApi.getCoverage(...) here is a
// real bean-to-bean call, so that bean's AOP proxy actually applies (the same self-invocation
// lesson as PolicyCreationService's TransactionTemplate: annotations on `this` don't take
// effect from within the same class). No fallbackMethod - a plain try/catch at this real call
// site is simpler to reason about than Resilience4j's fallback-on-a-dynamic-proxy resolution.
@Component
public class PolicyCoverageClient implements PolicyCoverageGateway {

  private final PolicyServiceHttpApi policyServiceHttpApi;

  public PolicyCoverageClient(PolicyServiceHttpApi policyServiceHttpApi) {
    this.policyServiceHttpApi = policyServiceHttpApi;
  }

  @Override
  public CoverageStatus checkCoverage(String policyNum, LocalDate onDate) {
    try {
      PolicyCoverageResponse response = policyServiceHttpApi.getCoverage(policyNum, onDate);
      return new CoverageStatus(response.active());
    } catch (HttpClientErrorException.NotFound e) {
      throw new HubBusinessException(POLICY_NOT_FOUND, policyNum);
    } catch (CallNotPermittedException | HttpServerErrorException | ResourceAccessException e) {
      throw new HubBusinessException(DOWNSTREAM_UNAVAILABLE, policyNum);
    }
  }
}
