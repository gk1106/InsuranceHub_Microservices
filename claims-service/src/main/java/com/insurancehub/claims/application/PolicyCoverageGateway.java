package com.insurancehub.claims.application;

import java.time.LocalDate;

// Port: infrastructure provides the resilience-wrapped HTTP adapter
// (infrastructure.client.PolicyCoverageClient). Throws HubBusinessException(POLICY_NOT_FOUND)
// if policy-service 404s, HubBusinessException(DOWNSTREAM_UNAVAILABLE) if the circuit is open
// or the call fails after retries - never a raw HTTP exception type, so application code never
// needs to know this is backed by HTTP.
public interface PolicyCoverageGateway {

  CoverageStatus checkCoverage(String policyNum, LocalDate onDate);
}
