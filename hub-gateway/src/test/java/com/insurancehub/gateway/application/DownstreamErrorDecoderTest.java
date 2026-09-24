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

  // decode(HttpStatusCodeException) itself isn't unit-testable in isolation:
  // getResponseBodyAs's message-converter extractor is only wired up by RestClient's own
  // internal error handling when it builds the exception from a real HTTP response - a
  // hand-constructed HttpServerErrorException.create(...) throws IllegalStateException("Function
  // to convert body not set") instead of decoding anything. This was equally true of the logic
  // this method now replaces (previously duplicated privately in PolicyServiceClient/
  // ClaimsServiceClient) - real coverage lives in HubGatewayErrorMappingIT, which exercises this
  // exact call path through a real WireMock-stubbed HTTP response.
}
