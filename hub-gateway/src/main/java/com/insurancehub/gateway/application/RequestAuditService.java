package com.insurancehub.gateway.application;

import com.insurancehub.gateway.domain.RequestAudit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

// Seven scalar parameters, never a request or response object - structurally impossible to pass
// a payload into this, not just a convention nobody violates yet. Called only by
// api.RequestAuditFilter, which wraps its call in its own try/catch (an audit-write failure must
// never turn an already-decided response into a 500 - see that class).
@Component
public class RequestAuditService {

  private final AuditRepository auditRepository;

  public RequestAuditService(AuditRepository auditRepository) {
    this.auditRepository = auditRepository;
  }

  public void record(
      String txnId,
      @Nullable String reqId,
      @Nullable String inspId,
      @Nullable String serviceType,
      int respCode,
      long latencyMs,
      String clientIp) {
    auditRepository.save(
        RequestAudit.of(
            txnId, reqId, inspId, serviceType, String.valueOf(respCode), latencyMs, clientIp));
  }
}
