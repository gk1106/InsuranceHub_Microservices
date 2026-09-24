package com.insurancehub.gateway.infrastructure.client;

import java.math.BigDecimal;
import java.time.LocalDate;

// Gateway-owned copy of claims-service's own api.RegisterClaimRequest.
public record RegisterClaimRequest(
    String policyNum,
    String claimNum,
    String claimType,
    String lossDesc,
    String natureOfLoss,
    String lossCity,
    LocalDate dateOfLoss,
    LocalDate intimationDate,
    BigDecimal claimedAmt,
    String claimStatus,
    String claimCode) {}
