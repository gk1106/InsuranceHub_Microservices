package com.insurancehub.claims.infrastructure.persistence;

import com.insurancehub.claims.application.ClaimRepository;
import com.insurancehub.claims.domain.Claim;
import org.springframework.data.jpa.repository.JpaRepository;

// Adapter: Spring Data implements every ClaimRepository port method at runtime via query
// derivation (existsByClaimNum, findByClaimNum).
public interface ClaimJpaRepository extends JpaRepository<Claim, Long>, ClaimRepository {}
