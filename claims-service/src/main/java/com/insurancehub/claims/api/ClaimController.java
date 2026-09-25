package com.insurancehub.claims.api;

import com.insurancehub.claims.application.ClaimRegistrationService;
import com.insurancehub.claims.application.ClaimStatusUpdateService;
import com.insurancehub.common.web.HubHeaders;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/claims")
public class ClaimController {

  private final ClaimRegistrationService claimRegistrationService;
  private final ClaimStatusUpdateService claimStatusUpdateService;
  private final ClaimRegistrationCommandMapper registrationMapper;
  private final ClaimStatusUpdateCommandMapper statusUpdateMapper;

  public ClaimController(
      ClaimRegistrationService claimRegistrationService,
      ClaimStatusUpdateService claimStatusUpdateService,
      ClaimRegistrationCommandMapper registrationMapper,
      ClaimStatusUpdateCommandMapper statusUpdateMapper) {
    this.claimRegistrationService = claimRegistrationService;
    this.claimStatusUpdateService = claimStatusUpdateService;
    this.registrationMapper = registrationMapper;
    this.statusUpdateMapper = statusUpdateMapper;
  }

  @PostMapping
  public ResponseEntity<RegisterClaimResponse> register(
      @RequestBody @Valid RegisterClaimRequest request,
      @RequestHeader(HubHeaders.REQ_ID) String reqId,
      @RequestHeader(HubHeaders.INSP_ID) String inspId,
      @RequestHeader(HubHeaders.TXN_ID) String txnId,
      @RequestHeader(value = "traceparent", required = false) String traceparent) {
    var result =
        claimRegistrationService.register(
            registrationMapper.toCommand(request, reqId, inspId, txnId, traceparent));
    var body = new RegisterClaimResponse(result.txnId(), result.replayed(), result.claimNum());
    // 201 for a real registration, 200 for a replay (nothing new was created).
    var status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(body);
  }

  @PatchMapping("/{claimNum}/status")
  public ResponseEntity<UpdateClaimStatusResponse> updateStatus(
      @PathVariable String claimNum,
      @RequestBody @Valid UpdateClaimStatusRequest request,
      @RequestHeader(HubHeaders.REQ_ID) String reqId,
      @RequestHeader(HubHeaders.INSP_ID) String inspId,
      @RequestHeader(HubHeaders.TXN_ID) String txnId,
      @RequestHeader(value = "traceparent", required = false) String traceparent) {
    var result =
        claimStatusUpdateService.updateStatus(
            statusUpdateMapper.toCommand(request, claimNum, reqId, inspId, txnId, traceparent));
    var body = new UpdateClaimStatusResponse(result.txnId(), result.replayed(), result.claimNum());
    // Always 200 - a status update never creates anything, so there's no 200/201 split.
    return ResponseEntity.ok(body);
  }
}
