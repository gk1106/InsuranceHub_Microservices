package com.insurancehub.claims.application;

public record UpdateClaimStatusResult(String txnId, boolean replayed, String claimNum) {

  public static UpdateClaimStatusResult updated(String txnId, String claimNum) {
    return new UpdateClaimStatusResult(txnId, false, claimNum);
  }

  public static UpdateClaimStatusResult replay(String txnId, String claimNum) {
    return new UpdateClaimStatusResult(txnId, true, claimNum);
  }
}
