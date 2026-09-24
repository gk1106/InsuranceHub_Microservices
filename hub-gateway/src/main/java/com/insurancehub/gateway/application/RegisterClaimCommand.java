package com.insurancehub.gateway.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RegisterClaimCommand(
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
