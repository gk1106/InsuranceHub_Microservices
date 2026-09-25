package com.insurancehub.common.web;

// service-design.md §1: every internal request carries these three headers, set by the
// gateway and propagated by CorrelationPropagationInterceptor. traceparent is deliberately
// absent here - Micrometer Tracing owns it, not hub-common.
public final class HubHeaders {

  public static final String REQ_ID = "X-Req-Id";
  public static final String INSP_ID = "X-Insp-Id";
  public static final String TXN_ID = "X-Txn-Id";

  // Phase 8: hub-gateway -> policy-service/claims-service defense-in-depth (cross-cutting.md
  // §4). Network isolation is the real control; this is a second layer in case that's ever
  // misconfigured. A shared secret, not a per-request token - the simpler of the two options
  // cross-cutting.md names, chosen over a Keycloak-issued service JWT (docs/adr/0007).
  public static final String INTERNAL_AUTH = "X-Internal-Auth";

  // MDC key names (cross-cutting.md §2) - intentionally not the same strings as the header
  // names above.
  public static final String MDC_REQ_ID = "reqId";
  public static final String MDC_INSP_ID = "inspId";
  public static final String MDC_TXN_ID = "txnId";
  // Not settable by CorrelationFilter (phase 1): serviceType is only known after hub-gateway
  // decrypts the body and resolves the code (HubDispatcher), well after this filter runs. Set
  // and cleared there instead - see HubDispatcher/RequestAuditFilter.
  public static final String MDC_SERVICE_TYPE = "serviceType";

  private HubHeaders() {}
}
