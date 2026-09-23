package com.insurancehub.gateway.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// The decrypted plain-JSON payload shape (api-contract.md §3). Minimal for now (just the header,
// enough for HubServiceCode resolution) - commit 2 adds policyDetails/claimDetails once mapping
// exists to consume them. ignoreUnknown so a real request's policyDetails/claimDetails don't
// fail deserialization before commit 2 lands.
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawHubRequestBody(RawHeader header) {}
