package com.insurancehub.claims.application;

import static com.insurancehub.common.error.HubErrorCode.CLAIM_ALREADY_EXISTS;
import static com.insurancehub.common.error.HubErrorCode.POLICY_NOT_ACTIVE;
import static com.insurancehub.common.error.HubErrorCode.VALIDATION_FAILED;

import com.insurancehub.claims.domain.Claim;
import com.insurancehub.claims.domain.ClaimStatusHistory;
import com.insurancehub.claims.domain.ProcessedRequest;
import com.insurancehub.common.error.HubBusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ClaimRegistrationService {

  private static final String SERVICE_TYPE = "ClaimService";
  private static final String DEFAULT_STATUS = "REGISTERED";

  private final ClaimRepository claims;
  private final ClaimStatusHistoryRepository claimStatusHistory;
  private final ProcessedRequestRepository processedRequests;
  private final PolicyCoverageGateway policyCoverageGateway;
  private final TransactionTemplate transactionTemplate;
  private final TransactionTemplate recoveryTransactionTemplate;

  public ClaimRegistrationService(
      ClaimRepository claims,
      ClaimStatusHistoryRepository claimStatusHistory,
      ProcessedRequestRepository processedRequests,
      PolicyCoverageGateway policyCoverageGateway,
      PlatformTransactionManager transactionManager) {
    this.claims = claims;
    this.claimStatusHistory = claimStatusHistory;
    this.processedRequests = processedRequests;
    this.policyCoverageGateway = policyCoverageGateway;
    // TransactionTemplate, not @Transactional: calling a @Transactional method on `this` from
    // within this same class would bypass Spring's proxy entirely (same reasoning as
    // PolicyCreationService).
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.recoveryTransactionTemplate = new TransactionTemplate(transactionManager);
    this.recoveryTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public RegisterClaimResult register(RegisterClaimCommand cmd) {
    var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
    if (existing.isPresent()) {
      return RegisterClaimResult.replay(existing.get().getTxnId(), existing.get().getResourceKey());
    }

    if (cmd.dateOfLoss().isAfter(cmd.intimationDate())) {
      throw new HubBusinessException(
          VALIDATION_FAILED, "dateOfLoss must be on or before intimationDate");
    }

    // Coverage check happens here, before any transaction opens - a transaction is never held
    // open across a network call (service-design.md §3).
    var coverage = policyCoverageGateway.checkCoverage(cmd.policyNum(), cmd.dateOfLoss());
    if (!coverage.active()) {
      throw new HubBusinessException(POLICY_NOT_ACTIVE, cmd.policyNum());
    }

    try {
      return transactionTemplate.execute(status -> doRegister(cmd));
    } catch (DataIntegrityViolationException e) {
      return recoverFromRace(cmd, e);
    }
  }

  // Same safety net as PolicyCreationService/PolicyRenewalService: by the time this runs, the
  // transaction that hit the violation has already rolled back, so REQUIRES_NEW makes "no
  // current transaction to join" explicit rather than incidental.
  private RegisterClaimResult recoverFromRace(
      RegisterClaimCommand cmd, DataIntegrityViolationException original) {
    return recoveryTransactionTemplate.execute(
        status -> {
          var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
          if (existing.isPresent()) {
            return RegisterClaimResult.replay(
                existing.get().getTxnId(), existing.get().getResourceKey());
          }
          throw original;
        });
  }

  private RegisterClaimResult doRegister(RegisterClaimCommand cmd) {
    var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
    if (existing.isPresent()) {
      return RegisterClaimResult.replay(existing.get().getTxnId(), existing.get().getResourceKey());
    }
    if (claims.existsByClaimNum(cmd.claimNum())) {
      throw new HubBusinessException(CLAIM_ALREADY_EXISTS, cmd.claimNum());
    }

    String status =
        (cmd.claimStatus() == null || cmd.claimStatus().isBlank())
            ? DEFAULT_STATUS
            : cmd.claimStatus();

    var claim =
        claims.save(
            Claim.builder()
                .claimNum(cmd.claimNum())
                .policyNum(cmd.policyNum())
                .inspId(cmd.inspId())
                .claimType(cmd.claimType())
                .lossDesc(cmd.lossDesc())
                .natureOfLoss(cmd.natureOfLoss())
                .lossCity(cmd.lossCity())
                .dateOfLoss(cmd.dateOfLoss())
                .intimationDate(cmd.intimationDate())
                .claimedAmt(cmd.claimedAmt())
                .claimStatus(status)
                .claimCode(cmd.claimCode())
                .build());

    claimStatusHistory.save(
        ClaimStatusHistory.builder()
            .claim(claim)
            .fromStatus(null)
            .toStatus(status)
            .claimCode(cmd.claimCode())
            .settledAmt(null)
            .reqId(cmd.reqId())
            .txnId(cmd.txnId())
            .build());

    processedRequests.save(
        ProcessedRequest.of(
            cmd.inspId(), cmd.reqId(), SERVICE_TYPE, cmd.txnId(), claim.getClaimNum()));

    return RegisterClaimResult.created(cmd.txnId(), claim.getClaimNum());
  }
}
