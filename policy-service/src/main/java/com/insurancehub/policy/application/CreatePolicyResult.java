package com.insurancehub.policy.application;

public record CreatePolicyResult(String txnId, boolean replayed, String policyNum) {

  public static CreatePolicyResult created(String txnId, String policyNum) {
    return new CreatePolicyResult(txnId, false, policyNum);
  }

  public static CreatePolicyResult replay(String txnId, String policyNum) {
    return new CreatePolicyResult(txnId, true, policyNum);
  }
}
