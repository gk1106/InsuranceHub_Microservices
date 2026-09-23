package com.insurancehub.policy.application;

import static com.insurancehub.common.error.HubErrorCode.POLICY_NOT_FOUND;
import static com.insurancehub.common.error.HubErrorCode.RENEWAL_NOT_ALLOWED;
import static com.insurancehub.common.error.HubErrorCode.VALIDATION_FAILED;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.policy.domain.PolicyTerm;
import com.insurancehub.policy.domain.ProcessedRequest;
import com.insurancehub.policy.domain.TermType;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PolicyRenewalService {

  private static final String SERVICE_TYPE = "RenewalService";

  private final PolicyRepository policies;
  private final PolicyTermRepository policyTerms;
  private final ProcessedRequestRepository processedRequests;
  private final TransactionTemplate transactionTemplate;
  private final TransactionTemplate recoveryTransactionTemplate;

  public PolicyRenewalService(
      PolicyRepository policies,
      PolicyTermRepository policyTerms,
      ProcessedRequestRepository processedRequests,
      PlatformTransactionManager transactionManager) {
    this.policies = policies;
    this.policyTerms = policyTerms;
    this.processedRequests = processedRequests;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.recoveryTransactionTemplate = new TransactionTemplate(transactionManager);
    this.recoveryTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public RenewPolicyResult renew(RenewPolicyCommand cmd) {
    try {
      return transactionTemplate.execute(status -> doRenew(cmd));
    } catch (DataIntegrityViolationException e) {
      return recoverFromRace(cmd, e);
    }
  }

  // Same safety net as PolicyCreationService: by the time this runs, the transaction that hit
  // the violation has already rolled back, so there is no "current" transaction to join -
  // REQUIRES_NEW makes that explicit. Unlike create, no dedicated "a different reqId won"
  // outcome is built here (not in the required test list) - a processed_request row for MY
  // (inspId, reqId) means replay; anything else rethrows.
  private RenewPolicyResult recoverFromRace(
      RenewPolicyCommand cmd, DataIntegrityViolationException original) {
    return recoveryTransactionTemplate.execute(
        status -> {
          var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
          if (existing.isPresent()) {
            return RenewPolicyResult.replay(
                existing.get().getTxnId(),
                cmd.policyNum(),
                Integer.parseInt(existing.get().getResourceKey()));
          }
          throw original;
        });
  }

  private RenewPolicyResult doRenew(RenewPolicyCommand cmd) {
    var existing = processedRequests.findByInspIdAndReqId(cmd.inspId(), cmd.reqId());
    if (existing.isPresent()) {
      return RenewPolicyResult.replay(
          existing.get().getTxnId(),
          cmd.policyNum(),
          Integer.parseInt(existing.get().getResourceKey()));
    }

    var policy =
        policies
            .findByPolicyNum(cmd.policyNum())
            .orElseThrow(() -> new HubBusinessException(POLICY_NOT_FOUND, cmd.policyNum()));

    if (cmd.startDate().isAfter(cmd.expiryDate())) {
      throw new HubBusinessException(
          VALIDATION_FAILED, "startDate must be on or before expiryDate");
    }

    List<PolicyTerm> terms = policyTerms.findByPolicyId(policy.getId());
    PolicyTerm latestTerm =
        terms.stream().max(Comparator.comparing(PolicyTerm::getTermNo)).orElseThrow();

    if (!cmd.startDate().isAfter(latestTerm.getStartDate())) {
      throw new HubBusinessException(
          RENEWAL_NOT_ALLOWED, "startDate must be after the latest term's startDate");
    }
    boolean overlaps =
        terms.stream()
            .anyMatch(
                t ->
                    !cmd.expiryDate().isBefore(t.getStartDate())
                        && !cmd.startDate().isAfter(t.getExpiryDate()));
    if (overlaps) {
      throw new HubBusinessException(RENEWAL_NOT_ALLOWED, cmd.policyNum());
    }

    int newTermNo = latestTerm.getTermNo() + 1;

    policy.updateInsuredDetails(
        cmd.applicationNum(),
        cmd.cif(),
        cmd.accountNum(),
        cmd.insuredName(),
        cmd.mobileNum(),
        cmd.address(),
        cmd.insuranceType(),
        cmd.insuranceName(),
        cmd.regionCode(),
        cmd.regionName(),
        cmd.branchCode(),
        cmd.branchName(),
        cmd.loanAcctNum(),
        cmd.specPerNum(),
        cmd.specPerName());
    policies.save(policy);

    policyTerms.save(
        PolicyTerm.builder()
            .policy(policy)
            .termNo(newTermNo)
            .termType(TermType.RENEWAL)
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
            cmd.inspId(), cmd.reqId(), SERVICE_TYPE, cmd.txnId(), String.valueOf(newTermNo)));

    return RenewPolicyResult.created(cmd.txnId(), cmd.policyNum(), newTermNo);
  }
}
