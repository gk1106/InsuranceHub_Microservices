package com.insurancehub.policy.infrastructure.persistence;

import com.insurancehub.policy.application.PolicyRepository;
import com.insurancehub.policy.domain.Policy;
import org.springframework.data.jpa.repository.JpaRepository;

// Adapter: Spring Data implements every PolicyRepository port method at runtime via query
// derivation (existsByPolicyNum, findByPolicyNum), and the proxy is injected wherever the
// PolicyRepository port is required.
public interface PolicyJpaRepository extends JpaRepository<Policy, Long>, PolicyRepository {}
