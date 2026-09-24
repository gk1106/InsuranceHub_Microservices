package com.insurancehub.common.error;

import org.springframework.http.HttpStatus;

// Provisional catalogue - see docs/open-questions.md Q1 (official respCodes for crypto/IP/HMAC
// failures are not yet confirmed by the bank) and Q2 (HTTP status vs. always-200 with a body
// code). respCode is stored explicitly, not derived from httpStatus - today they match, but
// Q1/Q2 may force them to diverge once the bank answers, and deriving one from the other would
// make that divergence impossible to express.
public enum HubErrorCode {
  SUCCESS(HttpStatus.OK, "200", "SUCCESS"),
  INVALID_JSON(HttpStatus.BAD_REQUEST, "400", "Invalid Json Format"),
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "400", "Validation failed: <field>"),
  INVALID_SERVICE_CODE(HttpStatus.BAD_REQUEST, "400", "Invalid serviceType/appStatusCode"),
  TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "401", "Invalid token"),
  TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "401", "Token Expired"),
  IP_NOT_ALLOWED(HttpStatus.FORBIDDEN, "403", "IP not allowed"),
  INSURER_MISMATCH(HttpStatus.FORBIDDEN, "403", "Insurer not authorised for inspId"),
  // Not in the bank's spec. A token that's otherwise valid but lacks the Insurance scope is
  // semantically "authenticated, not authorized" (RFC 6750's insufficient_scope, conventionally
  // 403) - distinct from TOKEN_INVALID/TOKEN_EXPIRED (401, "not authenticated at all"). Folding
  // it into TOKEN_INVALID would mean returning HTTP 403 with a body claiming respCode 401,
  // which api-contract.md §4's "HTTP status mirrors respCode" rule forbids.
  INSUFFICIENT_SCOPE(HttpStatus.FORBIDDEN, "403", "Insufficient token scope"),
  SIGNATURE_INVALID(HttpStatus.BAD_REQUEST, "400", "Signature verification failed"),
  DECRYPTION_FAILED(HttpStatus.BAD_REQUEST, "400", "Decryption failed"),
  POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "404", "Policy not found"),
  CLAIM_NOT_FOUND(HttpStatus.NOT_FOUND, "404", "Claim not found"),
  POLICY_ALREADY_EXISTS(HttpStatus.CONFLICT, "409", "Policy already exists"),
  CLAIM_ALREADY_EXISTS(HttpStatus.CONFLICT, "409", "Claim already exists"),
  // HttpStatus.UNPROCESSABLE_ENTITY is deprecated in Spring 7 (Boot 4) in favor of
  // UNPROCESSABLE_CONTENT - RFC 9110 renamed 422's reason phrase, and Spring kept both as
  // distinct enum constants for source compatibility. They're both code 422, but
  // HttpStatus.valueOf(422) resolves to UNPROCESSABLE_CONTENT, so a client comparing by enum
  // identity (not just .value()) against the deprecated constant would see a mismatch.
  POLICY_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "422", "Policy not active on date of loss"),
  INVALID_STATUS_TRANSITION(
      HttpStatus.UNPROCESSABLE_CONTENT, "422", "Invalid claim status transition"),
  RENEWAL_NOT_ALLOWED(
      HttpStatus.UNPROCESSABLE_CONTENT, "422", "Renewal term overlaps existing term"),
  // Not in the bank's spec - project-defined, like RENEWAL_NOT_ALLOWED. Maps
  // ObjectOptimisticLockingFailureException (cross-cutting.md §1: "409 with a retryable hint")
  // for any service mutating a @Version-tracked row concurrently.
  CONCURRENT_UPDATE(HttpStatus.CONFLICT, "409", "Concurrent update, retry the request"),
  // Not in the bank's spec. cross-cutting.md §4's 256 KB request body limit (hub-gateway only).
  // HttpStatus.PAYLOAD_TOO_LARGE is deprecated in Spring 7 (Boot 4) in favor of
  // CONTENT_TOO_LARGE, same RFC 9110 rename as UNPROCESSABLE_CONTENT above.
  PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "413", "Request payload too large"),
  DOWNSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "503", "Service temporarily unavailable"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "500", "Internal error");

  private final HttpStatus httpStatus;
  private final String respCode;
  private final String errorDesc;

  HubErrorCode(HttpStatus httpStatus, String respCode, String errorDesc) {
    this.httpStatus = httpStatus;
    this.respCode = respCode;
    this.errorDesc = errorDesc;
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }

  public String respCode() {
    return respCode;
  }

  // Static template only - e.g. VALIDATION_FAILED's "<field>" placeholder is filled in by the
  // caller, not here, to keep this enum free of string-formatting logic.
  public String errorDesc() {
    return errorDesc;
  }
}
