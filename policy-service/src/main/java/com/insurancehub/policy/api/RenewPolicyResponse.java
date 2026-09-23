package com.insurancehub.policy.api;

public record RenewPolicyResponse(String txnId, boolean replayed, String policyNum, int termNo) {}
