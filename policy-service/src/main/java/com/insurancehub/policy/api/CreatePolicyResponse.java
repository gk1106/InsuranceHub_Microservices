package com.insurancehub.policy.api;

public record CreatePolicyResponse(String txnId, boolean replayed, String policyNum) {}
