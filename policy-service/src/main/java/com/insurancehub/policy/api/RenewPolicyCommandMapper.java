package com.insurancehub.policy.api;

import com.insurancehub.policy.application.RenewPolicyCommand;
import org.mapstruct.Mapper;

// Lives in api/, not application/: RenewPolicyCommand (application) must not depend on
// RenewPolicyRequest (api). Field names match across RenewPolicyRequest, policyNum/reqId/
// inspId/txnId, and RenewPolicyCommand, so MapStruct's multi-source-parameter matching needs no
// explicit @Mapping.
@Mapper(componentModel = "spring")
public interface RenewPolicyCommandMapper {

  RenewPolicyCommand toCommand(
      RenewPolicyRequest request,
      String policyNum,
      String reqId,
      String inspId,
      String txnId,
      String traceparent);
}
