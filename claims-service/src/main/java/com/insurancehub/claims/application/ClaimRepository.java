package com.insurancehub.claims.application;

import com.insurancehub.claims.domain.Claim;
import java.util.Optional;

// Port: infrastructure provides the Spring Data JPA adapter (ClaimJpaRepository). Only the
// methods this service actually calls - not a generic CRUD surface.
public interface ClaimRepository {

  boolean existsByClaimNum(String claimNum);

  Optional<Claim> findByClaimNum(String claimNum);

  Claim save(Claim claim);
}
