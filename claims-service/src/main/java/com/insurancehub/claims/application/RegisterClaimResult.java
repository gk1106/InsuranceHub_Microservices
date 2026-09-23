package com.insurancehub.claims.application;

public record RegisterClaimResult(String txnId, boolean replayed, String claimNum) {

  public static RegisterClaimResult created(String txnId, String claimNum) {
    return new RegisterClaimResult(txnId, false, claimNum);
  }

  public static RegisterClaimResult replay(String txnId, String claimNum) {
    return new RegisterClaimResult(txnId, true, claimNum);
  }
}
