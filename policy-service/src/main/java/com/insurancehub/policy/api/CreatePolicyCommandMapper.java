package com.insurancehub.policy.api;

import com.insurancehub.policy.application.CreatePolicyCommand;
import org.mapstruct.Mapper;

// Lives in api/, not application/: CreatePolicyCommand (application) must not depend on
// CreatePolicyRequest (api) - the layering only allows api -> application, never the reverse.
// Field names match across CreatePolicyRequest, reqId/inspId/txnId/traceparent, and
// CreatePolicyCommand, so MapStruct's multi-source-parameter matching needs no explicit
// @Mapping.
@Mapper(componentModel = "spring")
public interface CreatePolicyCommandMapper {

  CreatePolicyCommand toCommand(
      CreatePolicyRequest request, String reqId, String inspId, String txnId, String traceparent);
}
