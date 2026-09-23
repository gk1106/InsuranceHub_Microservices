package com.insurancehub.policy.application;

import com.insurancehub.policy.domain.ProcessedRequest;
import java.util.Optional;

public interface ProcessedRequestRepository {

  Optional<ProcessedRequest> findByInspIdAndReqId(String inspId, String reqId);

  ProcessedRequest save(ProcessedRequest processedRequest);
}
