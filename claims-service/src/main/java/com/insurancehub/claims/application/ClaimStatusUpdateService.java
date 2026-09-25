package com.insurancehub.claims.application;

import static com.insurancehub.common.error.HubErrorCode.CLAIM_NOT_FOUND;
import static com.insurancehub.common.error.HubErrorCode.INVALID_STATUS_TRANSITION;

import com.insurancehub.claims.domain.ClaimStatusHistory;
import com.insurancehub.claims.domain.ClaimStatusPolicy;
import com.insurancehub.claims.domain.ProcessedRequest;
import com.insurancehub.common.error.HubBusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ClaimStatusUpdateService {

  private static final Logger log = LoggerFactory.getLogger(ClaimStatusUpdateService.class);
  private static final String SERVICE_TYPE = "ClaimStatusService";

  private final ClaimRepository claims;
  private final ClaimStatusHistoryRepository claimStatusHistory;
  private final ProcessedRequestRepository processedRequests;
  private final ClaimStatusPolicy claimStatusPolicy;
  private final OutboxAppender outboxAppender;
  private final TransactionTemplate transactionTemplate;
  private final TransactionTemplate recoveryTransactionTemplate;
  // cross-cutting.md §2: claim_status_changed_total{to} - the tag value varies per call, so a
  // single pre-built Counter field (like the other services' policy_created_total) doesn't fit;
  // MeterRegistry.counter(name, tag...) below looks up/creates the right tagged Counter per call.
  private final MeterRegistry meterRegistry;

  public ClaimStatusUpdateService(
      ClaimRepository claims,
      ClaimStatusHistoryRepository claimStatusHistory,
      ProcessedRequestRepository processedRequests,
      ClaimStatusPolicy claimStatusPolicy,
      OutboxAppender outboxAppender,
      PlatformTransactionManager transactionManager,
      MeterRegistry meterRegistry) {
    this.claims = claims;
    this.claimStatusHistory = claimStatusHistory;
    this.processedRequests = processedRequests;
    this.claimStatusPolicy = claimStatusPolicy;
    this.outboxAppender = outboxAppender;
    this.meterRegistry = meterRegistry;
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

    outboxAppender.append(
        "Claim",
        claim.getClaimNum(),
        "ClaimStatusChanged",
        new ClaimStatusChangedData(
            claim.getClaimNum(),
            claim.getPolicyNum(),
            fromStatus,
            cmd.claimStatus(),
            cmd.settledAmt(),
            cmd.claimCode(),
            cmd.finalizationDate()),
        cmd.txnId(),
        cmd.reqId(),
        cmd.inspId(),
        cmd.traceparent());

    log.atInfo()
        .addKeyValue("event", "CLAIM_STATUS_CHANGED")
        .addKeyValue("claimNum", claim.getClaimNum())
        .addKeyValue("fromStatus", fromStatus)
        .addKeyValue("toStatus", cmd.claimStatus())
        .log("claim status changed");
    meterRegistry.counter("claim_status_changed_total", "to", cmd.claimStatus()).increment();

    return UpdateClaimStatusResult.updated(cmd.txnId(), claim.getClaimNum());
  }
}
