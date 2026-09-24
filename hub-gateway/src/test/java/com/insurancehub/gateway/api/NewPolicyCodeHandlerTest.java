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
import com.insurancehub.gateway.mapping.NewPolicyRequestMapper;
import org.junit.jupiter.api.Test;

class NewPolicyCodeHandlerTest {

  @Test
  void codeIsNewPolicy() {
    var handler =
        new NewPolicyCodeHandler(new NewPolicyRequestMapper(), mock(PolicyServiceGateway.class));

    assertThat(handler.code()).isEqualTo(HubServiceCode.NEW_POLICY);
  }

  @Test
  void successReflectsTheDownstreamResultsTxnId() {
    PolicyServiceGateway gateway = mock(PolicyServiceGateway.class);
    // Downstream replay returns the ORIGINAL txnId - handle() has no attempt txnId of its own to
    // fall back to (CodeHandler carries no such parameter), so this is the only source.
    when(gateway.createPolicy(any()))
        .thenReturn(new PolicyServiceResult("TXN-ORIGINAL", true, "POL1", null));
    var handler = new NewPolicyCodeHandler(new NewPolicyRequestMapper(), gateway);
    RawHubRequestBody body =
        new RawHubRequestBody(
            new RawHeader("REQ1", "NewPolicyService", "01", "INSP001", "universalsompo"),
            minimalPolicyDetails(),
            null);

    var response = handler.handle(body);

    assertThat(response.txnId()).isEqualTo("TXN-ORIGINAL");
    assertThat(response.reqId()).isEqualTo("REQ1");
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
