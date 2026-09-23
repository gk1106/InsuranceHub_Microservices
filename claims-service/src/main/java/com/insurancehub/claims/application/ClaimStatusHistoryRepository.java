package com.insurancehub.claims.application;

import com.insurancehub.claims.domain.ClaimStatusHistory;

public interface ClaimStatusHistoryRepository {

  ClaimStatusHistory save(ClaimStatusHistory history);
}
