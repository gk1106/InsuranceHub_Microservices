package com.insurancehub.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import com.insurancehub.policy.domain.Policy;
import com.insurancehub.policy.domain.PolicyTerm;
import com.insurancehub.policy.domain.TermType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyCoverageServiceTest {

  private static final String POLICY_NUM = "POL1";
  private static final Long POLICY_ID = 42L;

  private PolicyRepository policies;
  private PolicyTermRepository policyTerms;
  private PolicyCoverageService service;

  @BeforeEach
  void setUp() {
    policies = mock(PolicyRepository.class);
    policyTerms = mock(PolicyTermRepository.class);
    service = new PolicyCoverageService(policies, policyTerms);
  }

  @Test
  void returnsActiveCoverageWhenATermCoversTheDate() {
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.of(existingPolicy()));
    when(policyTerms.findByPolicyId(POLICY_ID))
        .thenReturn(List.of(existingTerm(1, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))));

    CoverageResult result = service.coverage(POLICY_NUM, LocalDate.of(2026, 6, 15));

    assertThat(result.active()).isTrue();
    assertThat(result.termStart()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(result.termExpiry()).isEqualTo(LocalDate.of(2027, 1, 1));
    assertThat(result.sumInsured()).isEqualTo(new BigDecimal("500000.00"));
    assertThat(result.insuranceType()).isEqualTo("GENERAL");
  }

  @Test
  void returnsInactiveCoverageForADateInAGapBetweenTerms() {
    // This is what "lapsed" means in this system (docs/open-questions.md Q9): no stored
    // status, just active=false for any date no term covers. Nulls, not the nearest term's
    // values - a value that means nothing is a value someone eventually uses.
    // PolicyRenewalServiceTest.allowsARenewalArrivingOverAYearAfterThePolicyLapsed confirms a
    // renewal is still accepted after a gap this long.
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.of(existingPolicy()));
    when(policyTerms.findByPolicyId(POLICY_ID))
        .thenReturn(
            List.of(
                existingTerm(1, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)),
                existingTerm(2, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 12, 31))));

    CoverageResult result = service.coverage(POLICY_NUM, LocalDate.of(2026, 1, 15));

    assertThat(result.active()).isFalse();
    assertThat(result.termStart()).isNull();
    assertThat(result.termExpiry()).isNull();
    assertThat(result.sumInsured()).isNull();
    assertThat(result.insuranceType()).isEqualTo("GENERAL"); // still populated when inactive
  }

  @Test
  void returnsTheEarlierTermWhenTheDateFallsInsideItEvenIfALaterTermExists() {
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.of(existingPolicy()));
    when(policyTerms.findByPolicyId(POLICY_ID))
        .thenReturn(
            List.of(
                existingTerm(1, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
                existingTerm(2, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))));

    CoverageResult result = service.coverage(POLICY_NUM, LocalDate.of(2024, 6, 15));

    assertThat(result.active()).isTrue();
    assertThat(result.termStart()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(result.termExpiry()).isEqualTo(LocalDate.of(2024, 12, 31));
  }

  @Test
  void termBoundariesAreInclusiveOnBothEnds() {
    // A policy covers a claim filed exactly on its start or expiry date - both are ordinary,
    // valid dates of loss (phase 4 will call this endpoint with dateOfLoss). Getting either
    // boundary wrong silently rejects a legitimate claim.
    LocalDate termStart = LocalDate.of(2026, 1, 1);
    LocalDate termExpiry = LocalDate.of(2026, 12, 31);
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.of(existingPolicy()));
    when(policyTerms.findByPolicyId(POLICY_ID))
        .thenReturn(List.of(existingTerm(1, termStart, termExpiry)));

    assertThat(service.coverage(POLICY_NUM, termStart).active())
        .as("exact start date is covered")
        .isTrue();
    assertThat(service.coverage(POLICY_NUM, termExpiry).active())
        .as("exact expiry date is covered")
        .isTrue();
    assertThat(service.coverage(POLICY_NUM, termStart.minusDays(1)).active())
        .as("one day before start is not covered")
        .isFalse();
    assertThat(service.coverage(POLICY_NUM, termExpiry.plusDays(1)).active())
        .as("one day after expiry is not covered")
        .isFalse();
  }

  @Test
  void rejectsCoverageLookupForAnUnknownPolicy() {
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.coverage(POLICY_NUM, LocalDate.of(2026, 1, 1)))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.POLICY_NOT_FOUND));
  }

  private static Policy existingPolicy() {
    return Policy.builder()
        .id(POLICY_ID)
        .policyNum(POLICY_NUM)
        .inspId("INSP001")
        .insuranceType("GENERAL")
        .build();
  }

  private static PolicyTerm existingTerm(int termNo, LocalDate startDate, LocalDate expiryDate) {
    return PolicyTerm.builder()
        .termNo(termNo)
        .termType(TermType.NEW)
        .startDate(startDate)
        .expiryDate(expiryDate)
        .netPremium(new BigDecimal("15000.00"))
        .grossPremium(new BigDecimal("17700.00"))
        .sumInsured(new BigDecimal("500000.00"))
        .reqId("REQ0")
        .txnId("01JTXNORIGINAL0000000000AA")
        .build();
  }
}
