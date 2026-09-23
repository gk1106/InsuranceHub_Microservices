package com.insurancehub.claims.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

// Required fields per api-contract.md §3 "Required for 03", minus the header fields
// (reqId/inspId come from X-Req-Id/X-Insp-Id, not the body).
public record RegisterClaimRequest(
    @NotBlank String policyNum,
    @NotBlank String claimNum,
    @NotBlank String claimType,
    String lossDesc,
    String natureOfLoss,
    String lossCity,
    @NotNull LocalDate dateOfLoss,
    @NotNull LocalDate intimationDate,
    @NotNull BigDecimal claimedAmt,
    String claimStatus,
    String claimCode) {}
