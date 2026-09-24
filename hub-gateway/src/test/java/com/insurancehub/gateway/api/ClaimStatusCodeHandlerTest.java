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
import com.insurancehub.gateway.mapping.ClaimStatusRequestMapper;
import org.junit.jupiter.api.Test;

class ClaimStatusCodeHandlerTest {

  @Test
  void codeIsClaimStatusUpdate() {
    var handler =
        new ClaimStatusCodeHandler(
            new ClaimStatusRequestMapper(), mock(ClaimsServiceGateway.class));

    assertThat(handler.code()).isEqualTo(HubServiceCode.CLAIM_STATUS_UPDATE);
  }

  @Test
  void successReflectsTheDownstreamResult() {
    ClaimsServiceGateway gateway = mock(ClaimsServiceGateway.class);
    when(gateway.updateClaimStatus(any()))
        .thenReturn(new ClaimsServiceResult("TXN-ORIGINAL", false, "CLM1"));
    var handler = new ClaimStatusCodeHandler(new ClaimStatusRequestMapper(), gateway);
    RawHubRequestBody body =
        new RawHubRequestBody(
            new RawHeader("REQ1", "ClaimStatusService", "04", "INSP001", "universalsompo"),
            null,
            minimalClaimDetails());

    var response = handler.handle(body);

    assertThat(response.txnId()).isEqualTo("TXN-ORIGINAL");
    assertThat(response.status()).isEqualTo("S");
  }

  private static RawClaimDetails minimalClaimDetails() {
    return new RawClaimDetails(
        "POL1",
        "CLM1",
        "",
        null,
        null,
        null,
        "",
        "",
        "",
        "UNDER_PROCESS",
        "",
        "",
        "",
        "",
        "",
        "",
        "");
  }
}
