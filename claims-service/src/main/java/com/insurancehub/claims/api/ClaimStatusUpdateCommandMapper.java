package com.insurancehub.claims.api;

import com.insurancehub.claims.application.UpdateClaimStatusCommand;
import org.mapstruct.Mapper;

// Lives in api/, not application/: UpdateClaimStatusCommand (application) must not depend on
// UpdateClaimStatusRequest (api). Field names match across UpdateClaimStatusRequest,
// claimNum/reqId/inspId/txnId, and UpdateClaimStatusCommand, so MapStruct's
// multi-source-parameter matching needs no explicit @Mapping.
@Mapper(componentModel = "spring")
public interface ClaimStatusUpdateCommandMapper {

  UpdateClaimStatusCommand toCommand(
      UpdateClaimStatusRequest request,
      String claimNum,
      String reqId,
      String inspId,
      String txnId,
      String traceparent);
}
