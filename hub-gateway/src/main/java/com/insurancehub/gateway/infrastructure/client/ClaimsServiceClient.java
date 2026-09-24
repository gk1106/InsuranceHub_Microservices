package com.insurancehub.gateway.infrastructure.client;

import static com.insurancehub.common.error.HubErrorCode.DOWNSTREAM_UNAVAILABLE;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.gateway.application.ClaimsServiceGateway;
import com.insurancehub.gateway.application.ClaimsServiceResult;
import com.insurancehub.gateway.application.DownstreamErrorDecoder;
import com.insurancehub.gateway.application.RegisterClaimCommand;
import com.insurancehub.gateway.application.UpdateClaimStatusCommand;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

// Same shape as PolicyServiceClient - see that class for why HttpStatusCodeException always
// decodes via DownstreamErrorDecoder while a bodyless failure (no response at all) falls back to
// DOWNSTREAM_UNAVAILABLE directly.
@Component
public class ClaimsServiceClient implements ClaimsServiceGateway {

  private final ClaimsServiceHttpApi claimsServiceHttpApi;
  private final DownstreamErrorDecoder errorDecoder;

  public ClaimsServiceClient(
      ClaimsServiceHttpApi claimsServiceHttpApi, DownstreamErrorDecoder errorDecoder) {
    this.claimsServiceHttpApi = claimsServiceHttpApi;
    this.errorDecoder = errorDecoder;
  }

  @Override
  public ClaimsServiceResult registerClaim(RegisterClaimCommand command) {
    try {
      RegisterClaimResponse response = claimsServiceHttpApi.registerClaim(toRequest(command));
      return new ClaimsServiceResult(response.txnId(), response.replayed(), response.claimNum());
    } catch (HttpStatusCodeException e) {
      throw decode(e);
    } catch (CallNotPermittedException | ResourceAccessException e) {
      throw new HubBusinessException(DOWNSTREAM_UNAVAILABLE, DOWNSTREAM_UNAVAILABLE.errorDesc());
    }
  }

  @Override
  public ClaimsServiceResult updateClaimStatus(UpdateClaimStatusCommand command) {
    try {
      UpdateClaimStatusResponse response =
          claimsServiceHttpApi.updateClaimStatus(command.claimNum(), toRequest(command));
      return new ClaimsServiceResult(response.txnId(), response.replayed(), response.claimNum());
    } catch (HttpStatusCodeException e) {
      throw decode(e);
    } catch (CallNotPermittedException | ResourceAccessException e) {
      throw new HubBusinessException(DOWNSTREAM_UNAVAILABLE, DOWNSTREAM_UNAVAILABLE.errorDesc());
    }
  }

  private HubBusinessException decode(HttpStatusCodeException e) {
    ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
    return problem != null
        ? errorDecoder.decode(problem)
        : new HubBusinessException(DOWNSTREAM_UNAVAILABLE, DOWNSTREAM_UNAVAILABLE.errorDesc());
  }

  private static RegisterClaimRequest toRequest(RegisterClaimCommand c) {
    return new RegisterClaimRequest(
        c.policyNum(),
        c.claimNum(),
        c.claimType(),
        c.lossDesc(),
        c.natureOfLoss(),
        c.lossCity(),
        c.dateOfLoss(),
        c.intimationDate(),
        c.claimedAmt(),
        c.claimStatus(),
        c.claimCode());
  }

  private static UpdateClaimStatusRequest toRequest(UpdateClaimStatusCommand c) {
    return new UpdateClaimStatusRequest(
        c.policyNum(),
        c.claimStatus(),
        c.settledAmt(),
        c.claimCode(),
        c.finalizationDate(),
        c.osAgeing(),
        c.requireDetails(),
        c.repuCancelDate(),
        c.reasonOfClosure());
  }
}
