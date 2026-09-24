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

  // For the rare code whose catalogue errorDesc is itself a template (VALIDATION_FAILED's
  // "Validation failed: <field>") rather than a fixed sentence - the caller supplies the
  // already-substituted text instead of the raw code.errorDesc(). Every other code's safeDetail
  // is an internal identifier (a policyNum, say), never meant to replace the catalogue's fixed
  // external wording - callers should use the three-arg overload for those.
  public static HubResponse failure(
      HubErrorCode code, String errorDesc, String txnId, String reqId) {
    return new HubResponse(errorDesc, code.respCode(), STATUS_FAILURE, txnId, reqId);
  }

  // Before trust is established (invalid/expired token, IP not allowed, bad signature,
  // undecryptable) - txnId/reqId aren't known yet.
  public static HubResponse preTrustFailure(HubErrorCode code) {
    return new HubResponse(code.errorDesc(), code.respCode(), STATUS_FAILURE, null, null);
  }
}
