package com.insurancehub.claims.infrastructure.persistence;

import com.insurancehub.claims.application.ProcessedRequestRepository;
import com.insurancehub.claims.domain.ProcessedRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedRequestJpaRepository
    extends JpaRepository<ProcessedRequest, Long>, ProcessedRequestRepository {}
