package com.insurancehub.gateway.infrastructure.client;

public record UpdateClaimStatusResponse(String txnId, boolean replayed, String claimNum) {}
