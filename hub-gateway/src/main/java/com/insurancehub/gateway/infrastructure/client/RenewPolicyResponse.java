package com.insurancehub.gateway.infrastructure.client;

public record RenewPolicyResponse(String txnId, boolean replayed, String policyNum, int termNo) {}
