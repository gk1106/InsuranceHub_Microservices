package com.insurancehub.gateway.application;

// Shared by registerClaim and updateClaimStatus - both claims-service responses carry the same
// three fields.
public record ClaimsServiceResult(String txnId, boolean replayed, String claimNum) {}
