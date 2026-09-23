package com.insurancehub.policy.api;

import com.insurancehub.common.web.HubHeaders;
import com.insurancehub.policy.application.PolicyCoverageService;
import com.insurancehub.policy.application.PolicyCreationService;
import com.insurancehub.policy.application.PolicyRenewalService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/policies")
public class PolicyController {

  private final PolicyCreationService policyCreationService;
  private final PolicyRenewalService policyRenewalService;
  private final PolicyCoverageService policyCoverageService;
  private final CreatePolicyCommandMapper createMapper;
  private final RenewPolicyCommandMapper renewMapper;

  public PolicyController(
      PolicyCreationService policyCreationService,
      PolicyRenewalService policyRenewalService,
      PolicyCoverageService policyCoverageService,
      CreatePolicyCommandMapper createMapper,
      RenewPolicyCommandMapper renewMapper) {
    this.policyCreationService = policyCreationService;
    this.policyRenewalService = policyRenewalService;
    this.policyCoverageService = policyCoverageService;
    this.createMapper = createMapper;
    this.renewMapper = renewMapper;
  }

  @PostMapping
  public ResponseEntity<CreatePolicyResponse> create(
      @RequestBody @Valid CreatePolicyRequest request,
      @RequestHeader(HubHeaders.REQ_ID) String reqId,
      @RequestHeader(HubHeaders.INSP_ID) String inspId,
      @RequestHeader(HubHeaders.TXN_ID) String txnId) {
    var result =
        policyCreationService.create(createMapper.toCommand(request, reqId, inspId, txnId));
    var body = new CreatePolicyResponse(result.txnId(), result.replayed(), result.policyNum());
    // 201 for a real creation, 200 for a replay (nothing new was created).
    var status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(body);
  }

  @PostMapping("/{policyNum}/renewals")
  public ResponseEntity<RenewPolicyResponse> renew(
      @PathVariable String policyNum,
      @RequestBody @Valid RenewPolicyRequest request,
      @RequestHeader(HubHeaders.REQ_ID) String reqId,
      @RequestHeader(HubHeaders.INSP_ID) String inspId,
      @RequestHeader(HubHeaders.TXN_ID) String txnId) {
    var result =
        policyRenewalService.renew(renewMapper.toCommand(request, policyNum, reqId, inspId, txnId));
    var body =
        new RenewPolicyResponse(
            result.txnId(), result.replayed(), result.policyNum(), result.termNo());
    // 201 for a real renewal (a new term row was created), 200 for a replay.
    var status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(body);
  }

  @GetMapping("/{policyNum}/coverage")
  public CoverageResponse coverage(
      @PathVariable String policyNum,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate onDate) {
    var result = policyCoverageService.coverage(policyNum, onDate);
    return new CoverageResponse(
        result.policyNum(),
        result.active(),
        result.termStart(),
        result.termExpiry(),
        result.sumInsured(),
        result.insuranceType());
  }
}
