package com.insurancehub.claims.api;

public record UpdateClaimStatusResponse(String txnId, boolean replayed, String claimNum) {}
