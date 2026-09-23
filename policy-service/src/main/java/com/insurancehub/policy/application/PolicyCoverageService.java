package com.insurancehub.policy.application;

import static com.insurancehub.common.error.HubErrorCode.POLICY_NOT_FOUND;

import com.insurancehub.common.error.HubBusinessException;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyCoverageService {

  private final PolicyRepository policies;
  private final PolicyTermRepository policyTerms;

  public PolicyCoverageService(PolicyRepository policies, PolicyTermRepository policyTerms) {
    this.policies = policies;
    this.policyTerms = policyTerms;
  }

  // Called externally by PolicyController, not via internal self-invocation, so the
  // @Transactional proxy applies normally - no TransactionTemplate needed for a plain read.
  @Transactional(readOnly = true)
  public CoverageResult coverage(String policyNum, LocalDate onDate) {
    var policy =
        policies
            .findByPolicyNum(policyNum)
            .orElseThrow(() -> new HubBusinessException(POLICY_NOT_FOUND, policyNum));

    return policyTerms.findByPolicyId(policy.getId()).stream()
        .filter(t -> !onDate.isBefore(t.getStartDate()) && !onDate.isAfter(t.getExpiryDate()))
        .findFirst()
        .map(
            t ->
                CoverageResult.active(
                    policyNum,
                    t.getStartDate(),
                    t.getExpiryDate(),
                    t.getSumInsured(),
                    policy.getInsuranceType()))
        .orElseGet(() -> CoverageResult.inactive(policyNum, policy.getInsuranceType()));
  }
}
