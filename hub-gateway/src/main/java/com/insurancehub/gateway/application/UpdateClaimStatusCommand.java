package com.insurancehub.gateway.application;

import java.math.BigDecimal;
import java.time.LocalDate;

// claimNum is included so the mapper can build the path variable for claims-service's PATCH
// /internal/claims/{claimNum}/status - not sent in the wire request body.
public record UpdateClaimStatusCommand(
    String claimNum,
    String policyNum,
    String claimStatus,
    BigDecimal settledAmt,
    String claimCode,
    LocalDate finalizationDate,
    Integer osAgeing,
    String requireDetails,
    LocalDate repuCancelDate,
    String reasonOfClosure) {}
