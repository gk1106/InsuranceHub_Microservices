package com.insurancehub.gateway.application;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

// Reads a downstream ProblemDetail's "code" property (service-design.md §1: internal error is
// RFC 9457 ProblemDetail with an extra "code" = a HubErrorCode name) and rebuilds the matching
// HubErrorCode - never re-derives it from the HTTP status alone, since several codes share a
// status (e.g. three different 422s). An unrecognised/missing code never crashes the gateway -
// INTERNAL_ERROR, same as any other unmapped failure. Connection failures, timeouts and an open
// circuit breaker don't come through here at all: infrastructure/client's adapters catch those
// exception types directly and map them to DOWNSTREAM_UNAVAILABLE themselves (the same pattern
// claims-service's own PolicyCoverageClient already uses) - this class only ever sees a real,
// parsed ProblemDetail body.
@Component
public class DownstreamErrorDecoder {

  public HubBusinessException decode(ProblemDetail problemDetail) {
    HubErrorCode code = resolveCode(problemDetail);
    if (code == null) {
      return new HubBusinessException(
          HubErrorCode.INTERNAL_ERROR, HubErrorCode.INTERNAL_ERROR.errorDesc());
    }
    String detail = problemDetail.getDetail();
    return new HubBusinessException(code, detail != null ? detail : code.errorDesc());
  }

  private static HubErrorCode resolveCode(ProblemDetail problemDetail) {
    Object codeProperty =
        problemDetail.getProperties() == null ? null : problemDetail.getProperties().get("code");
    if (!(codeProperty instanceof String codeName)) {
      return null;
    }
    try {
      return HubErrorCode.valueOf(codeName);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
