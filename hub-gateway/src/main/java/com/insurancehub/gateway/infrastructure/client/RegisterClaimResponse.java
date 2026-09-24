package com.insurancehub.gateway.infrastructure.client;

public record RegisterClaimResponse(String txnId, boolean replayed, String claimNum) {}
