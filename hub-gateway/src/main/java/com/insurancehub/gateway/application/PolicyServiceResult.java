package com.insurancehub.gateway.application;

// Shared by createPolicy and renewPolicy - termNo is null for a create (renewal-only field).
public record PolicyServiceResult(
    String txnId, boolean replayed, String policyNum, Integer termNo) {}
