package com.insurancehub.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "policy_term")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PolicyTerm {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "policy_id", nullable = false)
  private Policy policy;

  @Column(name = "term_no", nullable = false)
  private Integer termNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "term_type", nullable = false, length = 10)
  private TermType termType;

  @Column(name = "application_status", length = 30)
  private String applicationStatus;

  @Column(name = "issue_date")
  private LocalDate issueDate;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "expiry_date", nullable = false)
  private LocalDate expiryDate;

  @Column(name = "net_premium", nullable = false, precision = 15, scale = 2)
  private BigDecimal netPremium;

  @Column(name = "gst_amt", precision = 15, scale = 2)
  private BigDecimal gstAmt;

  @Column(name = "gross_premium", nullable = false, precision = 15, scale = 2)
  private BigDecimal grossPremium;

  @Column(name = "sum_insured", nullable = false, precision = 15, scale = 2)
  private BigDecimal sumInsured;

  @Column(name = "commission_per", precision = 5, scale = 2)
  private BigDecimal commissionPer;

  @Column(name = "commission_amt", precision = 15, scale = 2)
  private BigDecimal commissionAmt;

  @Column(name = "req_id", nullable = false, length = 64)
  private String reqId;

  @Column(name = "txn_id", nullable = false, columnDefinition = "CHAR(26)")
  private String txnId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
