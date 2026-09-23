package com.insurancehub.claims.infrastructure.persistence;

import com.insurancehub.claims.application.ClaimStatusHistoryRepository;
import com.insurancehub.claims.domain.ClaimStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

// findByClaimClaimNumOrderByIdAsc is not on the port - it's only used by tests.
public interface ClaimStatusHistoryJpaRepository
    extends JpaRepository<ClaimStatusHistory, Long>, ClaimStatusHistoryRepository {

  List<ClaimStatusHistory> findByClaimClaimNumOrderByIdAsc(String claimNum);
}
