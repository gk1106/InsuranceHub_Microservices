package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insurancehub.gateway.application.PolicyServiceGateway;
import com.insurancehub.gateway.application.PolicyServiceResult;
import com.insurancehub.gateway.domain.HubServiceCode;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import com.insurancehub.gateway.mapping.RenewalRequestMapper;
import org.junit.jupiter.api.Test;

class RenewalCodeHandlerTest {

  @Test
  void codeIsRenewal() {
    var handler =
        new RenewalCodeHandler(new RenewalRequestMapper(), mock(PolicyServiceGateway.class));

    assertThat(handler.code()).isEqualTo(HubServiceCode.RENEWAL);
  }

  @Test
  void successReflectsTheDownstreamResult() {
    PolicyServiceGateway gateway = mock(PolicyServiceGateway.class);
    when(gateway.renewPolicy(any()))
        .thenReturn(new PolicyServiceResult("TXN-ORIGINAL", false, "POL1", 2));
    var handler = new RenewalCodeHandler(new RenewalRequestMapper(), gateway);
    RawHubRequestBody body =
        new RawHubRequestBody(
            new RawHeader("REQ1", "RenewalService", "02", "INSP001", "universalsompo"),
            minimalPolicyDetails(),
            null);

    var response = handler.handle(body, "TXN-ATTEMPT");

    assertThat(response.txnId()).isEqualTo("TXN-ORIGINAL");
    assertThat(response.status()).isEqualTo("S");
  }

  private static RawPolicyDetails minimalPolicyDetails() {
    return new RawPolicyDetails(
        "",
        "",
        "",
        "",
        "CIF1",
        "",
        "GEN",
        "",
        "",
        "POL1",
        "Name",
        "",
        "",
        "",
        "",
        "01/01/2024",
        "01/01/2025",
        "1000",
        "",
        "1000",
        "1000",
        "",
        "",
        "",
        "",
        "");
  }
}
