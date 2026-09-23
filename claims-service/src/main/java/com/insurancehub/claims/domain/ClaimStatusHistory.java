package com.insurancehub.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// fromStatus is null for a claim's first row (registration) - there's no "from" yet.
@Entity
@Table(name = "claim_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ClaimStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "claim_id", nullable = false)
  private Claim claim;

  @Column(name = "from_status", length = 30)
  private String fromStatus;

  @Column(name = "to_status", nullable = false, length = 30)
  private String toStatus;

  @Column(name = "claim_code", length = 50)
  private String claimCode;

  @Column(name = "settled_amt", precision = 15, scale = 2)
  private BigDecimal settledAmt;

  @CreationTimestamp
  @Column(name = "changed_at", nullable = false, updatable = false)
  private Instant changedAt;

  @Column(name = "req_id", nullable = false, length = 64)
  private String reqId;

  @Column(name = "txn_id", nullable = false, columnDefinition = "CHAR(26)")
  private String txnId;
}
