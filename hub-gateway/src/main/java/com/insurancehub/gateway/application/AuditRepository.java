package com.insurancehub.gateway.application;

import com.insurancehub.gateway.domain.RequestAudit;

public interface AuditRepository {

  RequestAudit save(RequestAudit requestAudit);
}
