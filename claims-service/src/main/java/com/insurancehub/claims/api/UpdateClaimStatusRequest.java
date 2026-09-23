package com.insurancehub.claims.api;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;

// Scoped to what 04 actually updates (service-design.md §3), not the full external
// claimDetails envelope: policyNum/claimStatus are required (used to find and validate the
// transition), everything else is an optional settlement field applied as a partial update
// (see Claim.applyStatusUpdate). claimNum comes from the path, not the body.
public record UpdateClaimStatusRequest(
    @NotBlank String policyNum,
    @NotBlank String claimStatus,
    BigDecimal settledAmt,
    String claimCode,
    LocalDate finalizationDate,
    Integer osAgeing,
    String requireDetails,
    LocalDate repuCancelDate,
    String reasonOfClosure) {}
