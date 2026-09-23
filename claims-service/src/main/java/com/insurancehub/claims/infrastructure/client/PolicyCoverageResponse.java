package com.insurancehub.claims.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// policy-service's coverage response also carries policyNum/termStart/termExpiry/sumInsured/
// insuranceType (see policy-service's CoverageResponse) - claims-service doesn't need any of
// them for registration, so it doesn't duplicate fields it has no use for. ignoreUnknown so a
// field policy-service adds later doesn't break deserialization here.
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyCoverageResponse(boolean active) {}
