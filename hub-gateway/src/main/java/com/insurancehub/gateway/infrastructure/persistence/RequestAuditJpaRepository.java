package com.insurancehub.gateway.infrastructure.persistence;

import com.insurancehub.gateway.application.AuditRepository;
import com.insurancehub.gateway.domain.RequestAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestAuditJpaRepository
    extends JpaRepository<RequestAudit, Long>, AuditRepository {}
