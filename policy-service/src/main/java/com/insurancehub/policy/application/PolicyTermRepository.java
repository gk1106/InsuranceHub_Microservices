package com.insurancehub.policy.application;

import com.insurancehub.policy.domain.PolicyTerm;

public interface PolicyTermRepository {

  PolicyTerm save(PolicyTerm term);
}
