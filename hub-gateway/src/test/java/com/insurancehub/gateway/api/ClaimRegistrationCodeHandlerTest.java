package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insurancehub.gateway.application.ClaimsServiceGateway;
import com.insurancehub.gateway.application.ClaimsServiceResult;
import com.insurancehub.gateway.domain.HubServiceCode;
import com.insurancehub.gateway.domain.RawClaimDetails;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import com.insurancehub.gateway.mapping.ClaimRegistrationRequestMapper;
import org.junit.jupiter.api.Test;

class ClaimRegistrationCodeHandlerTest {

  @Test
  void codeIsClaimRegister() {
    var handler =
        new ClaimRegistrationCodeHandler(
            new ClaimRegistrationRequestMapper(), mock(ClaimsServiceGateway.class));

    assertThat(handler.code()).isEqualTo(HubServiceCode.CLAIM_REGISTER);
  }

  @Test
  void successReflectsTheDownstreamResult() {
    ClaimsServiceGateway gateway = mock(ClaimsServiceGateway.class);
    when(gateway.registerClaim(any()))
        .thenReturn(new ClaimsServiceResult("TXN-ORIGINAL", true, "CLM1"));
    var handler = new ClaimRegistrationCodeHandler(new ClaimRegistrationRequestMapper(), gateway);
    RawHubRequestBody body =
        new RawHubRequestBody(
            new RawHeader("REQ1", "ClaimService", "03", "INSP001", "universalsompo"),
            minimalPolicyDetails(),
            minimalClaimDetails());

    var response = handler.handle(body, "TXN-ATTEMPT");

    assertThat(response.txnId()).isEqualTo("TXN-ORIGINAL");
    assertThat(response.status()).isEqualTo("S");
  }

  private static RawPolicyDetails minimalPolicyDetails() {
    return new RawPolicyDetails(
        "", "", "", "", "", "", "", "", "", "POL1", "", "", "", "", "", "", "", "", "", "", "", "",
        "", "", "", "");
  }

  private static RawClaimDetails minimalClaimDetails() {
    return new RawClaimDetails(
        null,
        "CLM1",
        "ACCIDENT",
        "",
        "",
        "",
        "01/08/2024",
        "05/08/2024",
        "85000",
        "",
        "",
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
