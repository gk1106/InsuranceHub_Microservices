package com.insurancehub.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

// No @OneToMany back-reference to PolicyTerm - nothing needs to navigate that direction yet;
// PolicyTermRepository is queried by policyId directly where needed.
@Entity
@Table(name = "policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Policy {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "policy_num", nullable = false, length = 50)
  private String policyNum;

  @Column(name = "insp_id", nullable = false, length = 20)
  private String inspId;

  @Column(name = "application_num", length = 50)
  private String applicationNum;

  @Column(name = "cif", nullable = false, length = 512)
  private String cif;

  @Column(name = "account_num", length = 512)
  private String accountNum;

  @Column(name = "insured_name", nullable = false, length = 512)
  private String insuredName;

  @Column(name = "mobile_num", length = 512)
  private String mobileNum;

  @Column(name = "address", length = 512)
  private String address;

  @Column(name = "insurance_type", nullable = false, length = 50)
  private String insuranceType;

  @Column(name = "insurance_name", length = 100)
  private String insuranceName;

  @Column(name = "region_code", length = 20)
  private String regionCode;

  @Column(name = "region_name", length = 100)
  private String regionName;

  @Column(name = "branch_code", length = 20)
  private String branchCode;

  @Column(name = "branch_name", length = 100)
  private String branchName;

  @Column(name = "loan_acct_num", length = 512)
  private String loanAcctNum;

  @Column(name = "spec_per_num", length = 50)
  private String specPerNum;

  @Column(name = "spec_per_name", length = 100)
  private String specPerName;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;
}
