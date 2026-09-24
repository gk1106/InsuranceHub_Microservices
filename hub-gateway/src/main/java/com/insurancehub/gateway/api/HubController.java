package com.insurancehub.gateway.api;

import static com.insurancehub.common.error.HubErrorCode.INTERNAL_ERROR;
import static com.insurancehub.common.error.HubErrorCode.INVALID_JSON;
import static com.insurancehub.common.error.HubErrorCode.VALIDATION_FAILED;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubResponse;
import com.insurancehub.common.web.HubHeaders;
import com.insurancehub.gateway.audit.AuditContext;
import com.insurancehub.gateway.crypto.HubCryptoService;
import com.insurancehub.gateway.domain.HubEndpoint;
import com.insurancehub.gateway.domain.RawHubRequestBody;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// Kept thin per service-design.md §4: this class only wires the pipeline together (decrypt ->
// parse -> dispatch -> respond) - crypto, dispatch, mapping and audit logic each live in their
// own class. Two endpoints (api-contract.md §1); both funnel into the same pipeline via
// HubDispatcher, which is what actually enforces "only this code is valid on this endpoint".
@RestController
public class HubController {

  private static final Logger log = LoggerFactory.getLogger(HubController.class);

  private final HubCryptoService hubCryptoService;
  private final HubDispatcher hubDispatcher;
  private final ObjectMapper objectMapper;

  public HubController(
      HubCryptoService hubCryptoService, HubDispatcher hubDispatcher, ObjectMapper objectMapper) {
    this.hubCryptoService = hubCryptoService;
    this.hubDispatcher = hubDispatcher;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/v1/policydetail")
  public ResponseEntity<Object> policyDetail(
      @RequestBody EncryptedEnvelope envelope, HttpServletRequest request) {
    return process(envelope, HubEndpoint.POLICY_DETAIL, request);
  }

  @PostMapping({"/v1/policydetail/claimupdatestatus", "/v1/policydetail/claimupdatestatus/"})
  public ResponseEntity<Object> claimUpdateStatus(
      @RequestBody EncryptedEnvelope envelope, HttpServletRequest request) {
    return process(envelope, HubEndpoint.CLAIM_STATUS_UPDATE, request);
  }

  private ResponseEntity<Object> process(
      EncryptedEnvelope envelope, HubEndpoint endpoint, HttpServletRequest request) {
    String txnId = MDC.get(HubHeaders.MDC_TXN_ID);
    AuditContext auditContext = AuditContext.from(request);
    String inspId = auditContext.inspId();

    // Decrypt is its own try/catch, separate from the dispatch/business one below: its failure
    // must produce a plain, UNENVELOPED response (api-contract.md §4 - "undecryptable" is a
    // pre-trust failure like a bad token or disallowed IP), never
    // hubCryptoService.encryptAndSign'd. Never logs e.safeDetail() here - it's a fixed, generic
    // string by design (NimbusHubCryptoService), but this call site is exactly the boundary
    // where accidentally logging something JOSE-exception-derived would be easiest to introduce
    // later, so the discipline is enforced right at the one place it matters.
    String json;
    try {
      json = hubCryptoService.verifyAndDecrypt(envelope.enc(), inspId);
    } catch (HubBusinessException e) {
      log.warn("crypto rejection: {}", e.code());
      return ResponseEntity.status(e.code().httpStatus())
          .body(HubResponse.preTrustFailure(e.code()));
    }

    String reqId = null;
    try {
      RawHubRequestBody body = parse(json);
      reqId = body.header().reqId();

      HubResponse response = hubDispatcher.dispatch(body, endpoint, auditContext);
      // Reflects the txnId actually being returned (the ORIGINAL one on a replay), not the
      // attempt value generated before we knew - see docs/progress.md phase 5 notes.
      MDC.put(HubHeaders.MDC_TXN_ID, response.txnId());
      return respond(response, inspId);
    } catch (HubBusinessException e) {
      log.warn("business rejection: {} - {}", e.code(), e.safeDetail());
      // VALIDATION_FAILED's catalogue errorDesc is itself a template ("Validation failed:
      // <field>") - HubRequestValidator has already substituted the real field names into
      // safeDetail, so that's what the insurer needs to see. Every other code's safeDetail is an
      // internal identifier (a policyNum, an inspId), never meant to replace the catalogue's
      // fixed external wording.
      HubResponse response =
          e.code() == VALIDATION_FAILED
              ? HubResponse.failure(e.code(), e.safeDetail(), txnId, reqId)
              : HubResponse.failure(e.code(), txnId, reqId);
      return respond(response, inspId);
    } catch (Exception e) {
      log.error("unexpected error", e);
      return respond(HubResponse.failure(INTERNAL_ERROR, txnId, reqId), inspId);
    }
  }

  private RawHubRequestBody parse(String json) {
    // json is null whenever crypto is off (NoOpHubCryptoService echoes a null/missing `enc`
    // straight through) and the insurer sent no `enc` field at all - ObjectMapper.readValue
    // throws a plain IllegalArgumentException for a null String, not a JacksonException, so this
    // has to be checked explicitly rather than folded into the catch below.
    if (json == null) {
      throw new HubBusinessException(INVALID_JSON, "missing request body");
    }
    try {
      return objectMapper.readValue(json, RawHubRequestBody.class);
    } catch (JacksonException e) {
      throw new HubBusinessException(INVALID_JSON, "malformed request body");
    }
  }

  private ResponseEntity<Object> respond(HubResponse response, String inspId) {
    String json = objectMapper.writeValueAsString(response);
    String enc = hubCryptoService.encryptAndSign(json, inspId);
    // respCode is the numeric HTTP status as a string (api-contract.md §4: "HTTP status mirrors
    // respCode") - reading the status straight from it avoids threading the HubErrorCode enum
    // through the whole call chain just to answer "what HTTP status does this response get".
    int status = Integer.parseInt(response.respCode());
    return ResponseEntity.status(status).body(new EncryptedEnvelope(enc));
  }
}
