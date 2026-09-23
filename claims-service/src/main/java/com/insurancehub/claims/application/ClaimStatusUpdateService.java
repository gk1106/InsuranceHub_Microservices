package com.insurancehub.claims.application;

import static com.insurancehub.common.error.HubErrorCode.CLAIM_NOT_FOUND;
import static com.insurancehub.common.error.HubErrorCode.INVALID_STATUS_TRANSITION;

import com.insurancehub.claims.domain.ClaimStatusHistory;
import com.insurancehub.claims.domain.ClaimStatusPolicy;
import com.insurancehub.claims.domain.ProcessedRequest;
import com.insurancehub.common.error.HubBusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ClaimStatusUpdateService {

  private static final String SERVICE_TYPE = "ClaimStatusService";

  private final ClaimRepository claims;
  private final ClaimStatusHistoryRepository claimStatusHistory;
  private final ProcessedRequestRepository processedRequests;
  private final ClaimStatusPolicy claimStatusPolicy;
  private final TransactionTemplate transactionTemplate;
  private final TransactionTemplate recoveryTransactionTemplate;

  public ClaimStatusUpdateService(
      ClaimRepository claims,
      ClaimStatusHistoryRepository claimStatusHistory,
      ProcessedRequestRepository processedRequests,
      ClaimStatusPolicy claimStatusPolicy,
      PlatformTransactionManager transactionManager) {
    this.claims = claims;
    this.claimStatusHistory = claimStatusHistory;
    this.processedRequests = processedRequests;
    this.claimStatusPolicy = claimStatusPolicy;
    // TransactionTemplate, not @Transactional: calling a @Transactional method on `this` from
    // within this same class would bypass Spring's proxy entirely (same reasoning as
    // ClaimRegistrationService).
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.recoveryTransactionTemplate = new TransactionTemplate(transactionManager);
    this.recoveryTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public UpdateClaimStatusResult updateStatus(UpdateClaimStatusCommand cmd) {
    var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
    if (existing.isPresent()) {
      return UpdateClaimStatusResult.replay(
          existing.get().getTxnId(), existing.get().getResourceKey());
    }

    // No DataIntegrityViolationException-catching wrapper is what lets
    // ObjectOptimisticLockingFailureException (two different reqIds racing on the same claim's
    // @Version) propagate unmodified to ClaimExceptionHandler -> CONCURRENT_UPDATE. It's a
    // different exception hierarchy branch (TransientDataAccessException, not
    // NonTransientDataAccessException), so the catch below never intercepts it.
    try {
      return transactionTemplate.execute(status -> doUpdateStatus(cmd));
    } catch (DataIntegrityViolationException e) {
      return recoverFromRace(cmd, e);
    }
  }

  // Same safety net as ClaimRegistrationService: by the time this runs, the transaction that
  // hit the violation has already rolled back, so REQUIRES_NEW makes "no current transaction to
  // join" explicit rather than incidental. No bespoke "someone else's update won" branch - not
  // in the required test list (that's what the CONCURRENT_UPDATE path above already covers for
  // a different-reqId race; this recovery only handles the same-reqId idempotency race).
  private UpdateClaimStatusResult recoverFromRace(
      UpdateClaimStatusCommand cmd, DataIntegrityViolationException original) {
    return recoveryTransactionTemplate.execute(
        status -> {
          var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
          if (existing.isPresent()) {
            return UpdateClaimStatusResult.replay(
                existing.get().getTxnId(), existing.get().getResourceKey());
          }
          throw original;
        });
  }

  private UpdateClaimStatusResult doUpdateStatus(UpdateClaimStatusCommand cmd) {
    var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
    if (existing.isPresent()) {
      return UpdateClaimStatusResult.replay(
          existing.get().getTxnId(), existing.get().getResourceKey());
    }

    // Never reveal that a claim exists under a different policy - same code, same message
    // shape as "doesn't exist at all" (service-design.md §3).
    var claim =
        claims
            .findByClaimNum(cmd.claimNum())
            .filter(c -> c.getPolicyNum().equals(cmd.policyNum()))
            .orElseThrow(() -> new HubBusinessException(CLAIM_NOT_FOUND, cmd.claimNum()));

    String fromStatus = claim.getClaimStatus();
    if (!claimStatusPolicy.isTransitionAllowed(fromStatus, cmd.claimStatus())) {
      throw new HubBusinessException(INVALID_STATUS_TRANSITION, cmd.claimNum());
    }

    claim.applyStatusUpdate(
        cmd.claimStatus(),
        cmd.settledAmt(),
        cmd.claimCode(),
        cmd.finalizationDate(),
        cmd.osAgeing(),
        cmd.requireDetails(),
        cmd.repuCancelDate(),
        cmd.reasonOfClosure());
    claims.save(claim);

    claimStatusHistory.save(
        ClaimStatusHistory.builder()
            .claim(claim)
            .fromStatus(fromStatus)
            .toStatus(cmd.claimStatus())
            .claimCode(cmd.claimCode())
            .settledAmt(cmd.settledAmt())
            .reqId(cmd.reqId())
            .txnId(cmd.txnId())
            .build());

    processedRequests.save(
        ProcessedRequest.of(
            cmd.inspId(), cmd.reqId(), SERVICE_TYPE, cmd.txnId(), claim.getClaimNum()));

    return UpdateClaimStatusResult.updated(cmd.txnId(), claim.getClaimNum());
  }
}
