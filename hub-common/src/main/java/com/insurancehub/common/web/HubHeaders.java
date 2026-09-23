package com.insurancehub.common.web;

// service-design.md §1: every internal request carries these three headers, set by the
// gateway and propagated by CorrelationPropagationInterceptor. traceparent is deliberately
// absent here - Micrometer Tracing owns it, not hub-common.
public final class HubHeaders {

  public static final String REQ_ID = "X-Req-Id";
  public static final String INSP_ID = "X-Insp-Id";
  public static final String TXN_ID = "X-Txn-Id";

  // MDC key names (cross-cutting.md §2) - intentionally not the same strings as the header
  // names above.
  public static final String MDC_REQ_ID = "reqId";
  public static final String MDC_INSP_ID = "inspId";
  public static final String MDC_TXN_ID = "txnId";

  private HubHeaders() {}
}
