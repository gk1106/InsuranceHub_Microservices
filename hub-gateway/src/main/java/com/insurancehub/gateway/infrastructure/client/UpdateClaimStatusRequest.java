package com.insurancehub.gateway.infrastructure.client;

import java.math.BigDecimal;
import java.time.LocalDate;

// Gateway-owned copy of claims-service's own api.UpdateClaimStatusRequest. claimNum isn't a
// field here either - ClaimsServiceClient sends it as the path variable, read off the Command.
public record UpdateClaimStatusRequest(
    String policyNum,
    String claimStatus,
    BigDecimal settledAmt,
    String claimCode,
    LocalDate finalizationDate,
    Integer osAgeing,
    String requireDetails,
    LocalDate repuCancelDate,
    String reasonOfClosure) {}
