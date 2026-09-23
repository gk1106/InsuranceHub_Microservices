package com.insurancehub.gateway.audit;

import jakarta.servlet.http.HttpServletRequest;

// Mutable, request-scoped (stored as a servlet request attribute, not a ThreadLocal - request
// attributes are naturally cleaned up per-request with no manual cleanup needed). Populated
// progressively as the pipeline learns things - inspId once the JWT resolves, reqId/serviceType
// once the body is decrypted - and read back once, at the very end, by RequestAuditFilter.
// Nothing else ever writes an audit row directly.
public final class AuditContext {

  private static final String ATTRIBUTE_NAME = AuditContext.class.getName();

  private String reqId;
  private String inspId;
  private String serviceType;

  public static AuditContext attachTo(HttpServletRequest request) {
    AuditContext context = new AuditContext();
    request.setAttribute(ATTRIBUTE_NAME, context);
    return context;
  }

  public static AuditContext from(HttpServletRequest request) {
    Object existing = request.getAttribute(ATTRIBUTE_NAME);
    return existing instanceof AuditContext context ? context : attachTo(request);
  }

  public void setReqId(String reqId) {
    this.reqId = reqId;
  }

  public void setInspId(String inspId) {
    this.inspId = inspId;
  }

  public void setServiceType(String serviceType) {
    this.serviceType = serviceType;
  }

  public String reqId() {
    return reqId;
  }

  public String inspId() {
    return inspId;
  }

  public String serviceType() {
    return serviceType;
  }
}
