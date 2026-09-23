package com.insurancehub.claims.api;

public record RegisterClaimResponse(String txnId, boolean replayed, String claimNum) {}
