package com.insurancehub.common.error;

import org.springframework.http.HttpStatus;

// Provisional catalogue - see docs/open-questions.md Q1 (official respCodes for crypto/IP/HMAC
// failures are not yet confirmed by the bank).
public enum HubErrorCode {
  SUCCESS(HttpStatus.OK, "SUCCESS"),
  INVALID_JSON(HttpStatus.BAD_REQUEST, "Invalid Json Format"),
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed: <field>"),
  INVALID_SERVICE_CODE(HttpStatus.BAD_REQUEST, "Invalid serviceType/appStatusCode"),
  TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Invalid token"),
  TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token Expired"),
  IP_NOT_ALLOWED(HttpStatus.FORBIDDEN, "IP not allowed"),
  INSURER_MISMATCH(HttpStatus.FORBIDDEN, "Insurer not authorised for inspId"),
  SIGNATURE_INVALID(HttpStatus.BAD_REQUEST, "Signature verification failed"),
  DECRYPTION_FAILED(HttpStatus.BAD_REQUEST, "Decryption failed"),
  POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "Policy not found"),
  CLAIM_NOT_FOUND(HttpStatus.NOT_FOUND, "Claim not found"),
  POLICY_ALREADY_EXISTS(HttpStatus.CONFLICT, "Policy already exists"),
  CLAIM_ALREADY_EXISTS(HttpStatus.CONFLICT, "Claim already exists"),
  POLICY_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Policy not active on date of loss"),
  INVALID_STATUS_TRANSITION(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid claim status transition"),
  RENEWAL_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "Renewal term overlaps existing term"),
  DOWNSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

  private final HttpStatus httpStatus;
  private final String errorDesc;

  HubErrorCode(HttpStatus httpStatus, String errorDesc) {
    this.httpStatus = httpStatus;
    this.errorDesc = errorDesc;
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }

  // Derived, not stored separately, so it can never drift from httpStatus (api-contract.md §4:
  // "HTTP status mirrors respCode").
  public String respCode() {
    return String.valueOf(httpStatus.value());
  }

  // Static template only - e.g. VALIDATION_FAILED's "<field>" placeholder is filled in by the
  // caller, not here, to keep this enum free of string-formatting logic.
  public String errorDesc() {
    return errorDesc;
  }
}
