package com.insurancehub.common.error;

// Thrown by domain code for an expected business failure; each service's own
// @RestControllerAdvice catches this and maps it to a ProblemDetail. safeDetail must never
// contain PII or raw request values - it goes straight into the response body.
public class HubBusinessException extends RuntimeException {

  private final HubErrorCode code;
  private final String safeDetail;

  public HubBusinessException(HubErrorCode code, String safeDetail) {
    super(code.name() + ": " + safeDetail);
    this.code = code;
    this.safeDetail = safeDetail;
  }

  public HubErrorCode code() {
    return code;
  }

  public String safeDetail() {
    return safeDetail;
  }
}
