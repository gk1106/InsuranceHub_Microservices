package com.insurancehub.policy.api;

import com.insurancehub.common.web.HubHeaders;
import com.insurancehub.policy.application.PolicyCreationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/policies")
public class PolicyController {

  private final PolicyCreationService policyCreationService;
  private final CreatePolicyCommandMapper mapper;

  public PolicyController(
      PolicyCreationService policyCreationService, CreatePolicyCommandMapper mapper) {
    this.policyCreationService = policyCreationService;
    this.mapper = mapper;
  }

  @PostMapping
  public ResponseEntity<CreatePolicyResponse> create(
      @RequestBody @Valid CreatePolicyRequest request,
      @RequestHeader(HubHeaders.REQ_ID) String reqId,
      @RequestHeader(HubHeaders.INSP_ID) String inspId,
      @RequestHeader(HubHeaders.TXN_ID) String txnId) {
    var result = policyCreationService.create(mapper.toCommand(request, reqId, inspId, txnId));
    var body = new CreatePolicyResponse(result.txnId(), result.replayed(), result.policyNum());
    // 201 for a real creation, 200 for a replay (nothing new was created).
    var status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(body);
  }
}
