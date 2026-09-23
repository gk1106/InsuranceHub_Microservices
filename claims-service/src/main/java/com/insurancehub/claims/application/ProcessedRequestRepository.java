package com.insurancehub.claims.application;

import com.insurancehub.claims.domain.ProcessedRequest;
import java.util.Optional;

public interface ProcessedRequestRepository {

  Optional<ProcessedRequest> findByInspIdAndReqId(String inspId, String reqId);

  ProcessedRequest save(ProcessedRequest processedRequest);
}
