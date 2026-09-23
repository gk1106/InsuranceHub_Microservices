package com.insurancehub.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import com.insurancehub.policy.domain.Policy;
import com.insurancehub.policy.domain.PolicyTerm;
import com.insurancehub.policy.domain.ProcessedRequest;
import com.insurancehub.policy.domain.TermType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class PolicyRenewalServiceTest {

  private static final String INSP_ID = "INSP001";
  private static final String REQ_ID = "REQ1";
  private static final String TXN_ID = "01JTXNAAAAAAAAAAAAAAAAAAAA";
  private static final String POLICY_NUM = "POL1";
  private static final Long POLICY_ID = 42L;

  private PolicyRepository policies;
  private PolicyTermRepository policyTerms;
  private ProcessedRequestRepository processedRequests;
  private PolicyRenewalService service;

  @BeforeEach
  void setUp() {
    policies = mock(PolicyRepository.class);
    policyTerms = mock(PolicyTermRepository.class);
    processedRequests = mock(ProcessedRequestRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    service =
        new PolicyRenewalService(policies, policyTerms, processedRequests, transactionManager);

    when(policies.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));
    when(policyTerms.save(any(PolicyTerm.class))).thenAnswer(inv -> inv.getArgument(0));
    when(processedRequests.save(any(ProcessedRequest.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void rejectsARenewalForAnUnknownPolicy() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.renew(command(LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 1))))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.POLICY_NOT_FOUND));
    verify0Save();
  }

  @Test
  void replaysWhenTheRequestWasAlreadyProcessed() {
    ProcessedRequest existing =
        ProcessedRequest.of(INSP_ID, REQ_ID, "RenewalService", "ORIGINAL-TXN", "2");
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.of(existing));

    RenewPolicyResult result =
        service.renew(command(LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 1)));

    assertThat(result.replayed()).isTrue();
    assertThat(result.txnId()).isEqualTo("ORIGINAL-TXN");
    assertThat(result.termNo()).isEqualTo(2);
    verify0Save();
  }

  @Test
  void rejectsStartDateAfterExpiryDate() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.of(existingPolicy()));

    assertThatThrownBy(
            () -> service.renew(command(LocalDate.of(2027, 6, 1), LocalDate.of(2027, 1, 1))))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.VALIDATION_FAILED));
  }

  @Test
  void rejectsANewTermNotAfterTheLatestTermsStartDate() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.of(existingPolicy()));
    when(policyTerms.findByPolicyId(POLICY_ID))
        .thenReturn(List.of(existingTerm(1, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))));

    // startDate equal to the latest term's startDate - not "after" it.
    assertThatThrownBy(
            () -> service.renew(command(LocalDate.of(2026, 1, 1), LocalDate.of(2028, 1, 1))))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.RENEWAL_NOT_ALLOWED));
  }

  @Test
  void rejectsANewTermThatOverlapsAnExistingTerm() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.of(existingPolicy()));
    when(policyTerms.findByPolicyId(POLICY_ID))
        .thenReturn(List.of(existingTerm(1, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))));

    // Starts after term 1's startDate (passes the ordering rule) but still overlaps its range.
    assertThatThrownBy(
            () -> service.renew(command(LocalDate.of(2026, 6, 1), LocalDate.of(2027, 6, 1))))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.RENEWAL_NOT_ALLOWED));
    verify0Save();
  }

  @Test
  void allowsARenewalArrivingOverAYearAfterThePolicyLapsed() {
    // Explicit lapse behavior (docs/open-questions.md Q9): a renewal is accepted no matter how
    // long ago the previous term expired - there's no "too late to renew" rule and no stored
    // LAPSED status. The stretch between 2025-12-31 and the new term's 2027-01-01 start (over a
    // year) is simply a period PolicyCoverageService reports as active=false - see
    // PolicyCoverageServiceTest.returnsInactiveCoverageForADateInAGapBetweenTerms - not
    // something this service tracks or restricts.
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.of(existingPolicy()));
    when(policyTerms.findByPolicyId(POLICY_ID))
        .thenReturn(List.of(existingTerm(1, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))));

    RenewPolicyResult result =
        service.renew(command(LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 1)));

    assertThat(result.replayed()).isFalse();
    assertThat(result.termNo()).isEqualTo(2);
    assertThat(result.txnId()).isEqualTo(TXN_ID);
  }

  @Test
  void propagatesOptimisticLockingFailureWithoutSwallowingIt() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.findByPolicyNum(POLICY_NUM)).thenReturn(Optional.of(existingPolicy()));
    when(policyTerms.findByPolicyId(POLICY_ID))
        .thenReturn(List.of(existingTerm(1, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))));
    when(policies.save(any(Policy.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(Policy.class, POLICY_ID));

    // Not a DataIntegrityViolationException, so the create-style recovery path must not catch
    // it - it has to reach the controller advice unchanged, where CONCURRENT_UPDATE applies.
    assertThatThrownBy(
            () -> service.renew(command(LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 1))))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  private void verify0Save() {
    verify(policies, never()).save(any());
    verify(policyTerms, never()).save(any());
  }

  private static Policy existingPolicy() {
    return Policy.builder().id(POLICY_ID).policyNum(POLICY_NUM).inspId(INSP_ID).build();
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

  private static RenewPolicyCommand command(LocalDate startDate, LocalDate expiryDate) {
    return new RenewPolicyCommand(
        REQ_ID,
        INSP_ID,
        TXN_ID,
        POLICY_NUM,
        "APP1",
        "CIF1",
        "ACC1",
        "Ravi Kumar",
        "9876543210",
        "12 MG Road",
        "GENERAL",
        "Motor Insurance",
        "RC01",
        "South",
        "BR1",
        "Chennai",
        "LN1",
        "SP1",
        "Agent Suresh",
        "ACTIVE",
        LocalDate.of(2026, 12, 25),
        startDate,
        expiryDate,
        new BigDecimal("15000.00"),
        new BigDecimal("2700.00"),
        new BigDecimal("17700.00"),
        new BigDecimal("500000.00"),
        new BigDecimal("5.00"),
        new BigDecimal("750.00"));
  }
}
