package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import com.insurancehub.common.error.HubResponse;
import com.insurancehub.gateway.audit.AuditContext;
import com.insurancehub.gateway.domain.HubEndpoint;
import com.insurancehub.gateway.domain.HubServiceCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HubDispatcherTest {

  @Test
  void dispatchesToTheHandlerMatchingTheResolvedCode() {
    CodeHandler newPolicyHandler = mock(CodeHandler.class);
    when(newPolicyHandler.code()).thenReturn(HubServiceCode.NEW_POLICY);
    when(newPolicyHandler.handle(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(HubResponse.success("TXN1", "REQ1"));
    HubDispatcher dispatcher = new HubDispatcher(List.of(newPolicyHandler));

    HubResponse response =
        dispatcher.dispatch(
            body("NewPolicyService", "01"),
            HubEndpoint.POLICY_DETAIL,
            "TXN1",
            AuditContext.attachTo(new MockHttpServletRequest()));

    assertThat(response.status()).isEqualTo("S");
  }

  @Test
  void rejectsAMismatchedCodeBeforeReachingAnyHandler() {
    HubDispatcher dispatcher = new HubDispatcher(List.of());

    assertThatThrownBy(
            () ->
                dispatcher.dispatch(
                    body("RenewalService", "03"),
                    HubEndpoint.POLICY_DETAIL,
                    "TXN1",
                    AuditContext.attachTo(new MockHttpServletRequest())))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.INVALID_SERVICE_CODE));
  }

  @Test
  void populatesAuditContextWithReqIdAndServiceTypeOnceResolved() {
    CodeHandler handler = mock(CodeHandler.class);
    when(handler.code()).thenReturn(HubServiceCode.NEW_POLICY);
    when(handler.handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(HubResponse.success("TXN1", "REQ1"));
    HubDispatcher dispatcher = new HubDispatcher(List.of(handler));
    AuditContext auditContext = AuditContext.attachTo(new MockHttpServletRequest());

    dispatcher.dispatch(
        body("NewPolicyService", "01"), HubEndpoint.POLICY_DETAIL, "TXN1", auditContext);

    assertThat(auditContext.reqId()).isEqualTo("REQ1");
    assertThat(auditContext.serviceType()).isEqualTo("NewPolicyService");
  }

  private static RawHubRequestBody body(String serviceType, String appStatusCode) {
    return new RawHubRequestBody(
        new RawHeader("REQ1", serviceType, appStatusCode, "INSP001", "universalsompo"));
  }
}
