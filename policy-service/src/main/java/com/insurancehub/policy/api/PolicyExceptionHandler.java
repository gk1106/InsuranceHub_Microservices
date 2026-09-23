package com.insurancehub.policy.api;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// cross-cutting.md §1: internal error = RFC 9457 ProblemDetail with an extra "code" property.
// Never a stack trace to the client.
@RestControllerAdvice
public class PolicyExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(PolicyExceptionHandler.class);
  private static final int MAX_FIELDS_REPORTED = 5;

  @ExceptionHandler(HubBusinessException.class)
  public ProblemDetail handleBusinessException(HubBusinessException ex) {
    log.warn("business rejection: {} - {}", ex.code(), ex.safeDetail());
    return problemDetail(ex.code(), ex.safeDetail());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidationFailure(MethodArgumentNotValidException ex) {
    // Field names only, never the rejected values - they can be PII.
    String fields =
        ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getField)
            .distinct()
            .limit(MAX_FIELDS_REPORTED)
            .collect(Collectors.joining(", "));
    log.warn("validation failed: {}", fields);
    String detail = HubErrorCode.VALIDATION_FAILED.errorDesc().replace("<field>", fields);
    return problemDetail(HubErrorCode.VALIDATION_FAILED, detail);
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex) {
    log.error("unexpected error", ex);
    return problemDetail(HubErrorCode.INTERNAL_ERROR, HubErrorCode.INTERNAL_ERROR.errorDesc());
  }

  private static ProblemDetail problemDetail(HubErrorCode code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.httpStatus(), detail);
    problem.setProperty("code", code.name());
    return problem;
  }
}
