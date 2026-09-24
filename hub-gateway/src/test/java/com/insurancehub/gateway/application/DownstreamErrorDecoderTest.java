package com.insurancehub.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.common.error.HubErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class DownstreamErrorDecoderTest {

  private final DownstreamErrorDecoder decoder = new DownstreamErrorDecoder();

  @Test
  void decodesARecognisedCodeFromTheProblemDetail() {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "already there");
    problem.setProperty("code", "POLICY_ALREADY_EXISTS");

    var exception = decoder.decode(problem);

    assertThat(exception.code()).isEqualTo(HubErrorCode.POLICY_ALREADY_EXISTS);
    assertThat(exception.safeDetail()).isEqualTo("already there");
  }

  @Test
  void fallsBackToTheCodesOwnDescriptionWhenDetailIsMissing() {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
    problem.setProperty("code", "DOWNSTREAM_UNAVAILABLE");

    var exception = decoder.decode(problem);

    assertThat(exception.safeDetail()).isEqualTo(HubErrorCode.DOWNSTREAM_UNAVAILABLE.errorDesc());
  }

  @Test
  void anUnrecognisedCodeNameFallsBackToInternalErrorInsteadOfCrashing() {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "??");
    problem.setProperty("code", "SOMETHING_CLAIMS_SERVICE_INVENTED_LATER");

    var exception = decoder.decode(problem);

    assertThat(exception.code()).isEqualTo(HubErrorCode.INTERNAL_ERROR);
  }

  @Test
  void aMissingCodePropertyFallsBackToInternalError() {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "no code");

    var exception = decoder.decode(problem);

    assertThat(exception.code()).isEqualTo(HubErrorCode.INTERNAL_ERROR);
  }
}
