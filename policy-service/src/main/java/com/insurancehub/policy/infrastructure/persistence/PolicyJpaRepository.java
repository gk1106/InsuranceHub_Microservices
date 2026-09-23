package com.insurancehub.policy.infrastructure.persistence;

import com.insurancehub.policy.application.PolicyRepository;
import com.insurancehub.policy.domain.Policy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// Adapter: Spring Data implements this at runtime (query derivation for existsByPolicyNum),
// and the proxy is injected wherever the PolicyRepository port is required.
// findByPolicyNum is not on the port - it's only used by tests, so it stays adapter-only.
public interface PolicyJpaRepository extends JpaRepository<Policy, Long>, PolicyRepository {

  Optional<Policy> findByPolicyNum(String policyNum);
}
