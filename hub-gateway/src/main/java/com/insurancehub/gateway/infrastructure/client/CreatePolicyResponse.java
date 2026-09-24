package com.insurancehub.gateway.infrastructure.client;

public record CreatePolicyResponse(String txnId, boolean replayed, String policyNum) {}
