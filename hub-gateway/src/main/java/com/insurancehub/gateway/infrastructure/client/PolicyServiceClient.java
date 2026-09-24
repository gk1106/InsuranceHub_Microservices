package com.insurancehub.gateway.infrastructure.client;

import static com.insurancehub.common.error.HubErrorCode.DOWNSTREAM_UNAVAILABLE;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.gateway.application.CreatePolicyCommand;
import com.insurancehub.gateway.application.DownstreamErrorDecoder;
import com.insurancehub.gateway.application.PolicyServiceGateway;
import com.insurancehub.gateway.application.PolicyServiceResult;
import com.insurancehub.gateway.application.RenewPolicyCommand;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

// Plain, non-annotated adapter - @CircuitBreaker/@Retry/@Bulkhead live on PolicyServiceHttpApi,
// not here (same self-invocation reasoning as claims-service's PolicyCoverageClient: calling
// policyServiceHttpApi.xxx(...) here is a real bean-to-bean call, so that bean's AOP proxy
// applies). A real HTTP error response (4xx/5xx, any body) goes through DownstreamErrorDecoder,
// which reads the ProblemDetail's own "code" - a claims-service 503 with code=
// DOWNSTREAM_UNAVAILABLE decodes to exactly that, no separate status-based special case needed.
// Only a response with no body at all (couldn't reach it, or the circuit is open) falls back to
// DOWNSTREAM_UNAVAILABLE directly.
@Component
public class PolicyServiceClient implements PolicyServiceGateway {

  private final PolicyServiceHttpApi policyServiceHttpApi;
  private final DownstreamErrorDecoder errorDecoder;

  public PolicyServiceClient(
      PolicyServiceHttpApi policyServiceHttpApi, DownstreamErrorDecoder errorDecoder) {
    this.policyServiceHttpApi = policyServiceHttpApi;
    this.errorDecoder = errorDecoder;
  }

  @Override
  public PolicyServiceResult createPolicy(CreatePolicyCommand command) {
    try {
      CreatePolicyResponse response = policyServiceHttpApi.createPolicy(toRequest(command));
      return new PolicyServiceResult(
          response.txnId(), response.replayed(), response.policyNum(), null);
    } catch (HttpStatusCodeException e) {
      throw errorDecoder.decode(e);
    } catch (CallNotPermittedException | ResourceAccessException e) {
      throw new HubBusinessException(DOWNSTREAM_UNAVAILABLE, DOWNSTREAM_UNAVAILABLE.errorDesc());
    }
  }

  @Override
  public PolicyServiceResult renewPolicy(RenewPolicyCommand command) {
    try {
      RenewPolicyResponse response =
          policyServiceHttpApi.renewPolicy(command.policyNum(), toRequest(command));
      return new PolicyServiceResult(
          response.txnId(), response.replayed(), response.policyNum(), response.termNo());
    } catch (HttpStatusCodeException e) {
      throw errorDecoder.decode(e);
    } catch (CallNotPermittedException | ResourceAccessException e) {
      throw new HubBusinessException(DOWNSTREAM_UNAVAILABLE, DOWNSTREAM_UNAVAILABLE.errorDesc());
    }
  }

  private static CreatePolicyRequest toRequest(CreatePolicyCommand c) {
    return new CreatePolicyRequest(
        c.policyNum(),
        c.applicationNum(),
        c.cif(),
        c.accountNum(),
        c.insuredName(),
        c.mobileNum(),
        c.address(),
        c.insuranceType(),
        c.insuranceName(),
        c.regionCode(),
        c.regionName(),
        c.branchCode(),
        c.branchName(),
        c.loanAcctNum(),
        c.specPerNum(),
        c.specPerName(),
        c.applicationStatus(),
        c.issueDate(),
        c.startDate(),
        c.expiryDate(),
        c.netPremium(),
        c.gstAmt(),
        c.grossPremium(),
        c.sumInsured(),
        c.commissionPer(),
        c.commissionAmt());
  }

  private static RenewPolicyRequest toRequest(RenewPolicyCommand c) {
    return new RenewPolicyRequest(
        c.applicationNum(),
        c.cif(),
        c.accountNum(),
        c.insuredName(),
        c.mobileNum(),
        c.address(),
        c.insuranceType(),
        c.insuranceName(),
        c.regionCode(),
        c.regionName(),
        c.branchCode(),
        c.branchName(),
        c.loanAcctNum(),
        c.specPerNum(),
        c.specPerName(),
        c.applicationStatus(),
        c.issueDate(),
        c.startDate(),
        c.expiryDate(),
        c.netPremium(),
        c.gstAmt(),
        c.grossPremium(),
        c.sumInsured(),
        c.commissionPer(),
        c.commissionAmt());
  }
}
