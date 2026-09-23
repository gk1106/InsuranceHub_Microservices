package com.insurancehub.policy.infrastructure.persistence;

import com.insurancehub.policy.application.PolicyTermRepository;
import com.insurancehub.policy.domain.PolicyTerm;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

// findByPolicyPolicyNum is not on the port - it's only used by tests.
public interface PolicyTermJpaRepository
    extends JpaRepository<PolicyTerm, Long>, PolicyTermRepository {

  List<PolicyTerm> findByPolicyPolicyNum(String policyNum);
}
