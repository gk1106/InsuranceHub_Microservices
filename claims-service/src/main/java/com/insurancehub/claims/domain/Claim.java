package com.insurancehub.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

// No PII here on purpose - service-design.md's claim table never carries the insured's name,
// mobile, CIF or account number; claims-service only learns policyNum from the caller and
// active/insuranceType/etc. from policy-service's coverage lookup.
@Entity
@Table(name = "claim")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Claim {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "claim_num", nullable = false, length = 50)
  private String claimNum;

  @Column(name = "policy_num", nullable = false, length = 50)
  private String policyNum;

  @Column(name = "insp_id", nullable = false, length = 20)
  private String inspId;

  @Column(name = "claim_type", nullable = false, length = 50)
  private String claimType;

  @Column(name = "loss_desc", length = 1000)
  private String lossDesc;

  @Column(name = "nature_of_loss", length = 100)
  private String natureOfLoss;

  @Column(name = "loss_city", length = 100)
  private String lossCity;

  @Column(name = "date_of_loss", nullable = false)
  private LocalDate dateOfLoss;

  @Column(name = "intimation_date", nullable = false)
  private LocalDate intimationDate;

  @Column(name = "claimed_amt", nullable = false, precision = 15, scale = 2)
  private BigDecimal claimedAmt;

  @Column(name = "settled_amt", precision = 15, scale = 2)
  private BigDecimal settledAmt;

  @Column(name = "claim_status", nullable = false, length = 30)
  private String claimStatus;

  @Column(name = "claim_code", length = 50)
  private String claimCode;

  @Column(name = "finalization_date")
  private LocalDate finalizationDate;

  @Column(name = "os_ageing")
  private Integer osAgeing;

  @Column(name = "require_details", columnDefinition = "TEXT")
  private String requireDetails;

  @Column(name = "repu_cancel_date")
  private LocalDate repuCancelDate;

  @Column(name = "reason_of_closure", length = 500)
  private String reasonOfClosure;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  // 04 status update (phase 4b) is a PATCH: claimStatus always changes, but every settlement
  // field is optional in the request - a null here means "not provided this round," not "clear
  // it," so a caller only sending claimStatus never wipes settlement data a prior call set.
  public void applyStatusUpdate(
      String claimStatus,
      BigDecimal settledAmt,
      String claimCode,
      LocalDate finalizationDate,
      Integer osAgeing,
      String requireDetails,
      LocalDate repuCancelDate,
      String reasonOfClosure) {
    this.claimStatus = claimStatus;
    if (settledAmt != null) {
      this.settledAmt = settledAmt;
    }
    if (claimCode != null) {
      this.claimCode = claimCode;
    }
    if (finalizationDate != null) {
      this.finalizationDate = finalizationDate;
    }
    if (osAgeing != null) {
      this.osAgeing = osAgeing;
    }
    if (requireDetails != null) {
      this.requireDetails = requireDetails;
    }
    if (repuCancelDate != null) {
      this.repuCancelDate = repuCancelDate;
    }
    if (reasonOfClosure != null) {
      this.reasonOfClosure = reasonOfClosure;
    }
  }
}
