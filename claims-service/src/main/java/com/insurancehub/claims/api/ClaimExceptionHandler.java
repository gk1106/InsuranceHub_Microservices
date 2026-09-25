package com.insurancehub.claims.api;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// cross-cutting.md §1: internal error = RFC 9457 ProblemDetail with an extra "code" property.
// Never a stack trace to the client.
@RestControllerAdvice
public class ClaimExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ClaimExceptionHandler.class);
  private static final int MAX_FIELDS_REPORTED = 5;

  @ExceptionHandler(HubBusinessException.class)
  public ProblemDetail handleBusinessException(HubBusinessException ex) {
    log.warn("business rejection: {} - {}", ex.code(), ex.safeDetail());
    return problemDetail(ex.code(), ex.safeDetail());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidationFailure(MethodArgumentNotValidException ex) {
    // Field names only, never the rejected values.
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

  // ConcurrencyFailureException, not just ObjectOptimisticLockingFailureException: two
  // concurrent UPDATEs racing the same @Version-ed row don't always surface as a clean
  // version-mismatch (0 rows affected) - found via the concurrent-update IT, MySQL/InnoDB can
  // instead detect a genuine deadlock between the two transactions and roll one back with
  // CannotAcquireLockException. Both are ConcurrencyFailureException (the common superclass of
  // OptimisticLockingFailureException and PessimisticLockingFailureException) and both mean the
  // same thing to the caller: retry the request.
  @ExceptionHandler(ConcurrencyFailureException.class)
  public ProblemDetail handleConcurrentUpdate(ConcurrencyFailureException ex) {
    log.warn("concurrent update conflict: {}", ex.getMessage());
    return problemDetail(
        HubErrorCode.CONCURRENT_UPDATE, HubErrorCode.CONCURRENT_UPDATE.errorDesc());
  }

  // Phase 8 finding: see policy-service's PolicyExceptionHandler's own comment - a genuinely
  // unmapped path throws this, and without an explicit handler the broad Exception.class
  // catch-all below turned it into a 500 INTERNAL_ERROR instead of a clean 404.
  @ExceptionHandler(NoResourceFoundException.class)
  public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No such endpoint");
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
