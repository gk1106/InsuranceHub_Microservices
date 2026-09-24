package com.insurancehub.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import com.insurancehub.common.error.HubResponse;
import com.insurancehub.gateway.application.HubRequestValidator;
import com.insurancehub.gateway.audit.AuditContext;
import com.insurancehub.gateway.domain.HubEndpoint;
import com.insurancehub.gateway.domain.HubServiceCode;
import com.insurancehub.gateway.domain.RawHeader;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import com.insurancehub.gateway.domain.RawPolicyDetails;
import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HubDispatcherTest {

  // A real validator, not a mock - these tests only exercise routing/insurer-check behavior with
  // a fully-populated header and a minimal-but-present policyDetails (NewPolicy's group requires
  // the object itself be non-null, even though every individual field here is blank), which a
  // real validator passes cleanly. HubRequestValidatorTest covers the validator itself.
  private final HubRequestValidator requestValidator =
      new HubRequestValidator(Validation.buildDefaultValidatorFactory().getValidator());

  @Test
  void dispatchesToTheHandlerMatchingTheResolvedCode() {
    CodeHandler newPolicyHandler = mock(CodeHandler.class);
    when(newPolicyHandler.code()).thenReturn(HubServiceCode.NEW_POLICY);
    when(newPolicyHandler.handle(org.mockito.ArgumentMatchers.any()))
        .thenReturn(HubResponse.success("TXN1", "REQ1"));
    HubDispatcher dispatcher = new HubDispatcher(List.of(newPolicyHandler), requestValidator);

    HubResponse response =
        dispatcher.dispatch(
            body("NewPolicyService", "01"), HubEndpoint.POLICY_DETAIL, auditContextForInsp001());

    assertThat(response.status()).isEqualTo("S");
  }

  @Test
  void rejectsAMismatchedCodeBeforeReachingAnyHandler() {
    HubDispatcher dispatcher = new HubDispatcher(List.of(), requestValidator);

    assertThatThrownBy(
            () ->
                dispatcher.dispatch(
                    body("RenewalService", "03"),
                    HubEndpoint.POLICY_DETAIL,
                    AuditContext.attachTo(new MockHttpServletRequest())))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.INVALID_SERVICE_CODE));
  }

  @Test
  void rejectsAnInsurerMismatchBetweenTheTokenAndTheBody() {
    HubDispatcher dispatcher = new HubDispatcher(List.of(), requestValidator);
    AuditContext auditContext = AuditContext.attachTo(new MockHttpServletRequest());
    auditContext.setInspId("INSP002");

    assertThatThrownBy(
            () ->
                dispatcher.dispatch(
                    body("NewPolicyService", "01"), HubEndpoint.POLICY_DETAIL, auditContext))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.INSURER_MISMATCH));
  }

  @Test
  void populatesAuditContextWithReqIdAndServiceTypeOnceResolved() {
    CodeHandler handler = mock(CodeHandler.class);
    when(handler.code()).thenReturn(HubServiceCode.NEW_POLICY);
    when(handler.handle(org.mockito.ArgumentMatchers.any()))
        .thenReturn(HubResponse.success("TXN1", "REQ1"));
    HubDispatcher dispatcher = new HubDispatcher(List.of(handler), requestValidator);
    AuditContext auditContext = auditContextForInsp001();

    dispatcher.dispatch(body("NewPolicyService", "01"), HubEndpoint.POLICY_DETAIL, auditContext);

    assertThat(auditContext.reqId()).isEqualTo("REQ1");
    assertThat(auditContext.serviceType()).isEqualTo("NewPolicyService");
  }

  private static AuditContext auditContextForInsp001() {
    AuditContext auditContext = AuditContext.attachTo(new MockHttpServletRequest());
    auditContext.setInspId("INSP001");
    return auditContext;
  }

  private static RawHubRequestBody body(String serviceType, String appStatusCode) {
    return new RawHubRequestBody(
        new RawHeader("REQ1", serviceType, appStatusCode, "INSP001", "universalsompo"),
        minimalPolicyDetails(),
        null);
  }

  // Fully valid, not just policyNum-present: NewPolicy.class requires cif/insuranceType/name/
  // startDate/expiryDate/netPremium/grossPremium/sumInsured too (RawPolicyDetails' own @NotBlank
  // groups), unlike Claim.class (03), which only needs policyNum from this object.
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
