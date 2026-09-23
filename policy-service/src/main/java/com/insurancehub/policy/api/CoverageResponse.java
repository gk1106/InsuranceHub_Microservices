package com.insurancehub.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CoverageResponse(
    String policyNum,
    boolean active,
    LocalDate termStart,
    LocalDate termExpiry,
    BigDecimal sumInsured,
    String insuranceType) {}
