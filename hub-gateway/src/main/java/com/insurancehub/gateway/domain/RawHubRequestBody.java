package com.insurancehub.gateway.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// The decrypted plain-JSON payload shape (api-contract.md §3): { header, policyDetails,
// claimDetails } for 01-03, { header, claimDetails } for 04 - both optional-details fields are
// simply absent/null on whichever code doesn't send them. HubRequestValidator validates
// whichever of policyDetails/claimDetails is present against the resolved code's group; each
// mapper then only reads the object(s) its own code actually needs.
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawHubRequestBody(
    RawHeader header, RawPolicyDetails policyDetails, RawClaimDetails claimDetails) {}
