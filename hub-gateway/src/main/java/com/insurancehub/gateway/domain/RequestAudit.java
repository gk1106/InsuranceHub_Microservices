package com.insurancehub.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// Every request that reaches the gateway writes exactly one row here, success or rejection,
// pre-trust or post-trust (docs/adr - audit-everything decision). reqId/inspId/serviceType are
// nullable because a pre-trust rejection (bad token, disallowed IP) never resolves them -
// txnId/respCode/latencyMs/clientIp are the only columns guaranteed non-null. No payload column
// exists - structurally, not just by convention (see RequestAuditTest's reflection check on the
// exact field list).
@Entity
@Table(name = "request_audit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestAudit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "txn_id", nullable = false, columnDefinition = "CHAR(26)")
  private String txnId;

  @Column(name = "req_id", length = 64)
  private String reqId;

  @Column(name = "insp_id", length = 20)
  private String inspId;

  @Column(name = "service_type", length = 30)
  private String serviceType;

  @Column(name = "resp_code", nullable = false, length = 10)
  private String respCode;

  @Column(name = "latency_ms", nullable = false)
  private long latencyMs;

  @Column(name = "client_ip", nullable = false, length = 45)
  private String clientIp;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private RequestAudit(
      String txnId,
      String reqId,
      String inspId,
      String serviceType,
      String respCode,
      long latencyMs,
      String clientIp) {
    this.txnId = txnId;
    this.reqId = reqId;
    this.inspId = inspId;
    this.serviceType = serviceType;
    this.respCode = respCode;
    this.latencyMs = latencyMs;
    this.clientIp = clientIp;
  }

  public static RequestAudit of(
      String txnId,
      String reqId,
      String inspId,
      String serviceType,
      String respCode,
      long latencyMs,
      String clientIp) {
    return new RequestAudit(txnId, reqId, inspId, serviceType, respCode, latencyMs, clientIp);
  }
}
