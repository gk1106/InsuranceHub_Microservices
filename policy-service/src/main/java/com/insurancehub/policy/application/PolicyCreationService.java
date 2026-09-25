package com.insurancehub.policy.application;

import static com.insurancehub.common.error.HubErrorCode.POLICY_ALREADY_EXISTS;
import static com.insurancehub.common.error.HubErrorCode.VALIDATION_FAILED;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.policy.domain.Policy;
import com.insurancehub.policy.domain.PolicyTerm;
import com.insurancehub.policy.domain.ProcessedRequest;
import com.insurancehub.policy.domain.TermType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PolicyCreationService {

  private static final String SERVICE_TYPE = "NewPolicyService";

  private final PolicyRepository policies;
  private final PolicyTermRepository policyTerms;
  private final ProcessedRequestRepository processedRequests;
  private final OutboxAppender outboxAppender;
  private final TransactionTemplate transactionTemplate;
  private final TransactionTemplate recoveryTransactionTemplate;

  public PolicyCreationService(
      PolicyRepository policies,
      PolicyTermRepository policyTerms,
      ProcessedRequestRepository processedRequests,
      OutboxAppender outboxAppender,
      PlatformTransactionManager transactionManager) {
    this.policies = policies;
    this.policyTerms = policyTerms;
    this.processedRequests = processedRequests;
    this.outboxAppender = outboxAppender;
    // TransactionTemplate, not @Transactional on doCreate(): calling a @Transactional method
    // on `this` from within this same class would bypass Spring's proxy entirely.
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    // PROPAGATION_REQUIRES_NEW, explicit: by the time create()'s catch block runs, the
    // transaction that hit the constraint violation has already been rolled back and
    // completed, so there is no "current" transaction to join anyway - REQUIRES_NEW makes that
    // fact unambiguous in code rather than relying on there happening to be none active.
    this.recoveryTransactionTemplate = new TransactionTemplate(transactionManager);
    this.recoveryTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public CreatePolicyResult create(CreatePolicyCommand cmd) {
    try {
      return transactionTemplate.execute(status -> doCreate(cmd));
    } catch (DataIntegrityViolationException e) {
      return recoverFromRace(cmd, e);
    }
  }

  // Two concurrent attempts at the SAME (inspId, reqId) can race on either
  // uk_processed_request OR uk_policy_policy_num (the policy insert happens first) - which
  // constraint actually fires depends on timing, not something worth hardcoding. And two
  // DIFFERENT reqIds can also collide on uk_policy_policy_num legitimately. Distinguish the
  // three outcomes by re-reading state, in a fresh transaction:
  //   1. a processed_request row for MY (inspId, reqId) now exists -> this exact request was
  //      already completed elsewhere -> replay it.
  //   2. no such row, but the policyNum now exists -> a genuinely different request won it
  //      first -> POLICY_ALREADY_EXISTS.
  //   3. neither -> something else caused the violation -> rethrow, unhandled.
  private CreatePolicyResult recoverFromRace(
      CreatePolicyCommand cmd, DataIntegrityViolationException original) {
    return recoveryTransactionTemplate.execute(
        status -> {
          var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
          if (existing.isPresent()) {
            return CreatePolicyResult.replay(
                existing.get().getTxnId(), existing.get().getResourceKey());
          }
          if (policies.existsByPolicyNum(cmd.policyNum())) {
            throw new HubBusinessException(POLICY_ALREADY_EXISTS, cmd.policyNum());
          }
          throw original;
        });
  }

  private CreatePolicyResult doCreate(CreatePolicyCommand cmd) {
    var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
    if (existing.isPresent()) {
      return CreatePolicyResult.replay(existing.get().getTxnId(), existing.get().getResourceKey());
    }
    if (cmd.startDate().isAfter(cmd.expiryDate())) {
      throw new HubBusinessException(
          VALIDATION_FAILED, "startDate must be on or before expiryDate");
    }
    if (policies.existsByPolicyNum(cmd.policyNum())) {
      throw new HubBusinessException(POLICY_ALREADY_EXISTS, cmd.policyNum());
    }

    var policy =
        policies.save(
            Policy.builder()
                .policyNum(cmd.policyNum())
                .inspId(cmd.inspId())
                .applicationNum(cmd.applicationNum())
                .cif(cmd.cif())
                .accountNum(cmd.accountNum())
                .insuredName(cmd.insuredName())
                .mobileNum(cmd.mobileNum())
                .address(cmd.address())
                .insuranceType(cmd.insuranceType())
                .insuranceName(cmd.insuranceName())
                .regionCode(cmd.regionCode())
                .regionName(cmd.regionName())
                .branchCode(cmd.branchCode())
                .branchName(cmd.branchName())
                .loanAcctNum(cmd.loanAcctNum())
                .specPerNum(cmd.specPerNum())
                .specPerName(cmd.specPerName())
                .build());

    policyTerms.save(
        PolicyTerm.builder()
            .policy(policy)
            .termNo(1)
            .termType(TermType.NEW)
            .applicationStatus(cmd.applicationStatus())
            .issueDate(cmd.issueDate())
            .startDate(cmd.startDate())
            .expiryDate(cmd.expiryDate())
            .netPremium(cmd.netPremium())
            .gstAmt(cmd.gstAmt())
            .grossPremium(cmd.grossPremium())
            .sumInsured(cmd.sumInsured())
            .commissionPer(cmd.commissionPer())
            .commissionAmt(cmd.commissionAmt())
            .reqId(cmd.reqId())
            .txnId(cmd.txnId())
            .build());

    processedRequests.save(
        ProcessedRequest.of(
            cmd.inspId(), cmd.reqId(), SERVICE_TYPE, cmd.txnId(), policy.getPolicyNum()));

    outboxAppender.append(
        "Policy",
        policy.getPolicyNum(),
        "PolicyCreated",
        new PolicyCreatedData(
            policy.getPolicyNum(),
            1,
            cmd.insuranceType(),
            cmd.applicationStatus(),
            cmd.issueDate(),
            cmd.startDate(),
            cmd.expiryDate(),
            cmd.netPremium(),
            cmd.grossPremium(),
            cmd.sumInsured()),
        cmd.txnId(),
        cmd.reqId(),
        cmd.inspId(),
        cmd.traceparent());

    return CreatePolicyResult.created(cmd.txnId(), policy.getPolicyNum());
  }
}
