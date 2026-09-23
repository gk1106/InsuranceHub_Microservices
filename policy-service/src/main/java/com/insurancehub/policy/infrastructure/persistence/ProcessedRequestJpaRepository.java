package com.insurancehub.policy.infrastructure.persistence;

import com.insurancehub.policy.application.ProcessedRequestRepository;
import com.insurancehub.policy.domain.ProcessedRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedRequestJpaRepository
    extends JpaRepository<ProcessedRequest, Long>, ProcessedRequestRepository {}
