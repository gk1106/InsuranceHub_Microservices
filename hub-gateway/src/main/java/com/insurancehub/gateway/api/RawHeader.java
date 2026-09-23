package com.insurancehub.gateway.api;

import com.insurancehub.gateway.domain.Claim;
import com.insurancehub.gateway.domain.ClaimStatus;
import com.insurancehub.gateway.domain.NewPolicy;
import com.insurancehub.gateway.domain.Renewal;
import jakarta.validation.constraints.NotBlank;

// api-contract.md §3: header fields are required for every code, so each field carries all four
// groups rather than an unconditional @NotBlank - HubRequestValidator always validates with
// exactly one already-resolved group, and this keeps that the only validation entry point (no
// separate "always-on" pass to keep in sync with the group-specific ones).
public record RawHeader(
    @NotBlank(groups = {NewPolicy.class, Renewal.class, Claim.class, ClaimStatus.class})
        String reqId,
    @NotBlank(groups = {NewPolicy.class, Renewal.class, Claim.class, ClaimStatus.class})
        String serviceType,
    @NotBlank(groups = {NewPolicy.class, Renewal.class, Claim.class, ClaimStatus.class})
        String appStatusCode,
    @NotBlank(groups = {NewPolicy.class, Renewal.class, Claim.class, ClaimStatus.class})
        String inspId,
    String inspName) {}
