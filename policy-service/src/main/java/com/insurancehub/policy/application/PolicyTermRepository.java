package com.insurancehub.policy.application;

import com.insurancehub.policy.domain.PolicyTerm;
import java.util.List;

public interface PolicyTermRepository {

  List<PolicyTerm> findByPolicyId(Long policyId);

  PolicyTerm save(PolicyTerm term);
}
