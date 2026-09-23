package com.insurancehub.policy.application;

import com.insurancehub.policy.domain.Policy;

// Port: infrastructure provides the Spring Data JPA adapter (PolicyJpaRepository). Only the
// methods this service actually calls - not a generic CRUD surface.
public interface PolicyRepository {

  boolean existsByPolicyNum(String policyNum);

  Policy save(Policy policy);
}
