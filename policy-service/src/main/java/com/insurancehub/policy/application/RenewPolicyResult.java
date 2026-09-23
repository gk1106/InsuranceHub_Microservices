package com.insurancehub.policy.application;

public record RenewPolicyResult(String txnId, boolean replayed, String policyNum, int termNo) {

  public static RenewPolicyResult created(String txnId, String policyNum, int termNo) {
    return new RenewPolicyResult(txnId, false, policyNum, termNo);
  }

  public static RenewPolicyResult replay(String txnId, String policyNum, int termNo) {
    return new RenewPolicyResult(txnId, true, policyNum, termNo);
  }
}
