package com.insurancehub.claims.domain;

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

// The idempotency guard: unique (insp_id, req_id) is the real safeguard against duplicate
// registrations/status updates, not application-level checks alone (CLAUDE.md rule 3).
// Duplicated from policy-service's ProcessedRequest, not shared via hub-common - see
// docs/adr/0003-ports-and-adapters.md.
@Entity
@Table(name = "processed_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedRequest {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "insp_id", nullable = false, length = 20)
  private String inspId;

  @Column(name = "req_id", nullable = false, length = 64)
  private String reqId;

  @Column(name = "service_type", nullable = false, length = 30)
  private String serviceType;

  @Column(name = "txn_id", nullable = false, columnDefinition = "CHAR(26)")
  private String txnId;

  @Column(name = "resource_key", length = 100)
  private String resourceKey;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private ProcessedRequest(
      String inspId, String reqId, String serviceType, String txnId, String resourceKey) {
    this.inspId = inspId;
    this.reqId = reqId;
    this.serviceType = serviceType;
    this.txnId = txnId;
    this.resourceKey = resourceKey;
  }

  public static ProcessedRequest of(
      String inspId, String reqId, String serviceType, String txnId, String resourceKey) {
    return new ProcessedRequest(inspId, reqId, serviceType, txnId, resourceKey);
  }
}
