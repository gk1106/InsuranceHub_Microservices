package com.insurancehub.gateway.api;

import static com.insurancehub.common.error.HubErrorCode.INTERNAL_ERROR;
import static com.insurancehub.common.error.HubErrorCode.INVALID_SERVICE_CODE;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubResponse;
import com.insurancehub.gateway.audit.AuditContext;
import com.insurancehub.gateway.domain.HubEndpoint;
import com.insurancehub.gateway.domain.HubServiceCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// Resolves the code first, before anything else touches the body (an unknown/mismatched pair,
// or a real code on the wrong endpoint, is rejected here - never reaches HubRequestValidator,
// so validation can never run with a guessed or wrong group). Populates AuditContext as it
// learns reqId/serviceType; never writes an audit row itself (RequestAuditFilter is the only
// thing that does that).
@Component
public class HubDispatcher {

  private final Map<HubServiceCode, CodeHandler> handlers;

  public HubDispatcher(List<CodeHandler> handlers) {
    this.handlers =
        handlers.stream().collect(Collectors.toMap(CodeHandler::code, Function.identity()));
  }

  public HubResponse dispatch(
      RawHubRequestBody body, HubEndpoint endpoint, String txnId, AuditContext auditContext) {
    var header = body.header();
    HubServiceCode code =
        HubServiceCode.resolve(header.serviceType(), header.appStatusCode(), endpoint)
            .orElseThrow(
                () ->
                    new HubBusinessException(
                        INVALID_SERVICE_CODE, header.serviceType() + "/" + header.appStatusCode()));

    auditContext.setReqId(header.reqId());
    auditContext.setServiceType(header.serviceType());

    CodeHandler handler = handlers.get(code);
    if (handler == null) {
      // Should never happen once every HubServiceCode has a registered CodeHandler bean - a
      // safety net for programming error, not a user-facing outcome to design tests around.
      throw new HubBusinessException(INTERNAL_ERROR, "no handler registered for " + code);
    }
    return handler.handle(body, txnId);
  }
}
