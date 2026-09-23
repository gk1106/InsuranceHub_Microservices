package com.insurancehub.claims.api;

import com.insurancehub.claims.application.RegisterClaimCommand;
import org.mapstruct.Mapper;

// Lives in api/, not application/: RegisterClaimCommand (application) must not depend on
// RegisterClaimRequest (api) - layering only allows api -> application, never the reverse.
// Field names match across RegisterClaimRequest, reqId/inspId/txnId, and RegisterClaimCommand,
// so MapStruct's multi-source-parameter matching needs no explicit @Mapping.
@Mapper(componentModel = "spring")
public interface ClaimRegistrationCommandMapper {

  RegisterClaimCommand toCommand(
      RegisterClaimRequest request, String reqId, String inspId, String txnId);
}
