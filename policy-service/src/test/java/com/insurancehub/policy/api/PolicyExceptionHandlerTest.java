package com.insurancehub.policy.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.policy.domain.Policy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

// PolicyExceptionHandler's other branches (HubBusinessException, MethodArgumentNotValidException,
// the catch-all) are exercised through PolicyCreationIT's HTTP-level assertions; this one covers
// the ObjectOptimisticLockingFailureException -> CONCURRENT_UPDATE mapping directly, since
// nothing currently drives it through an HTTP round trip.
class PolicyExceptionHandlerTest {

  @Test
  void mapsOptimisticLockingFailureToConcurrentUpdate() {
    PolicyExceptionHandler handler = new PolicyExceptionHandler();

    ProblemDetail problem =
        handler.handleConcurrentUpdate(
            new ObjectOptimisticLockingFailureException(Policy.class, 1L));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getProperties()).containsEntry("code", "CONCURRENT_UPDATE");
  }
}
