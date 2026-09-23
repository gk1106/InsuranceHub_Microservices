package com.insurancehub.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

// The external bank-facing envelope shape (api-contract.md §4). @JsonInclude(NON_NULL) is
// load-bearing: a pre-trust failure omits txnId/reqId entirely, not as null keys, and this is
// the only way one record serializes both shapes correctly.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HubResponse(
    String errorDesc, String respCode, String status, String txnId, String reqId) {

  private static final String STATUS_SUCCESS = "S";
  private static final String STATUS_FAILURE = "F";

  public static HubResponse success(String txnId, String reqId) {
    return new HubResponse(
        HubErrorCode.SUCCESS.errorDesc(),
        HubErrorCode.SUCCESS.respCode(),
        STATUS_SUCCESS,
        txnId,
        reqId);
  }

  public static HubResponse failure(HubErrorCode code, String txnId, String reqId) {
    return new HubResponse(code.errorDesc(), code.respCode(), STATUS_FAILURE, txnId, reqId);
  }

  // Before trust is established (invalid/expired token, IP not allowed, bad signature,
  // undecryptable) - txnId/reqId aren't known yet.
  public static HubResponse preTrustFailure(HubErrorCode code) {
    return new HubResponse(code.errorDesc(), code.respCode(), STATUS_FAILURE, null, null);
  }
}
