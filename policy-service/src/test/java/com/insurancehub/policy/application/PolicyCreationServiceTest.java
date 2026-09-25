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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class PolicyCreationServiceTest {

  private static final String INSP_ID = "INSP001";
  private static final String REQ_ID = "REQ1";
  private static final String TXN_ID = "01JTXNAAAAAAAAAAAAAAAAAAAA";
  private static final String POLICY_NUM = "POL1";

  private PolicyRepository policies;
  private PolicyTermRepository policyTerms;
  private ProcessedRequestRepository processedRequests;
  private OutboxAppender outboxAppender;
  private PolicyCreationService service;

  @BeforeEach
  void setUp() {
    policies = mock(PolicyRepository.class);
    policyTerms = mock(PolicyTermRepository.class);
    processedRequests = mock(ProcessedRequestRepository.class);
    outboxAppender = mock(OutboxAppender.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    service =
        new PolicyCreationService(
            policies, policyTerms, processedRequests, outboxAppender, transactionManager);

    when(policies.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));
    when(policyTerms.save(any(PolicyTerm.class))).thenAnswer(inv -> inv.getArgument(0));
    when(processedRequests.save(any(ProcessedRequest.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void createsANewPolicyWhenNothingExistsYet() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.existsByPolicyNum(POLICY_NUM)).thenReturn(false);

    CreatePolicyResult result = service.create(command());

    assertThat(result.replayed()).isFalse();
    assertThat(result.txnId()).isEqualTo(TXN_ID);
    assertThat(result.policyNum()).isEqualTo(POLICY_NUM);
    verify(policies).save(any(Policy.class));
    verify(policyTerms).save(any(PolicyTerm.class));
    verify(processedRequests).save(any(ProcessedRequest.class));
  }

  @Test
  void replaysWhenTheRequestWasAlreadyProcessed() {
    ProcessedRequest existing =
        ProcessedRequest.of(INSP_ID, REQ_ID, "NewPolicyService", "ORIGINAL-TXN", POLICY_NUM);
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.of(existing));

    CreatePolicyResult result = service.create(command());

    assertThat(result.replayed()).isTrue();
    assertThat(result.txnId()).isEqualTo("ORIGINAL-TXN");
    assertThat(result.policyNum()).isEqualTo(POLICY_NUM);
    verify(policies, never()).save(any());
  }

  @Test
  void rejectsADuplicatePolicyNumForANewRequest() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.existsByPolicyNum(POLICY_NUM)).thenReturn(true);

    assertThatThrownBy(() -> service.create(command()))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.POLICY_ALREADY_EXISTS));
    verify(policies, never()).save(any());
  }

  @Test
  void rejectsStartDateAfterExpiryDate() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    CreatePolicyCommand cmd = command(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1));

    assertThatThrownBy(() -> service.create(cmd))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.VALIDATION_FAILED));
    verify(policies, never()).existsByPolicyNum(any());
  }

  @Test
  void losingTheIdempotencyRaceReturnsTheWinnersReplayResultInsteadOfThrowing() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID))
        .thenReturn(Optional.empty()) // first look, before the race is lost
        .thenReturn( // second look, after catching the constraint violation
            Optional.of(
                ProcessedRequest.of(
                    INSP_ID, REQ_ID, "NewPolicyService", "WINNER-TXN", POLICY_NUM)));
    when(policies.existsByPolicyNum(POLICY_NUM)).thenReturn(false);
    when(processedRequests.save(any(ProcessedRequest.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate",
                new ConstraintViolationException(
                    "duplicate", new SQLException("dup"), "uk_processed_request")));

    CreatePolicyResult result = service.create(command());

    assertThat(result.replayed()).isTrue();
    assertThat(result.txnId()).isEqualTo("WINNER-TXN");
  }

  @Test
  void losingToADifferentReqIdOnTheSamePolicyNumReturnsPolicyAlreadyExists() {
    // No processed_request row for MY reqId either before or after - this genuinely is a
    // different request that happened to collide on policyNum, not a retry of my own request.
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.existsByPolicyNum(POLICY_NUM))
        .thenReturn(false) // initial check, before the race is lost
        .thenReturn(true); // recovery check: someone else's request won it first
    when(processedRequests.save(any(ProcessedRequest.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate",
                new ConstraintViolationException(
                    "duplicate", new SQLException("dup"), "uk_policy_policy_num")));

    assertThatThrownBy(() -> service.create(command()))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.POLICY_ALREADY_EXISTS));
  }

  @Test
  void rethrowsAConstraintViolationThatIsNotTheIdempotencyRace() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policies.existsByPolicyNum(POLICY_NUM)).thenReturn(false);
    DataIntegrityViolationException unrelated =
        new DataIntegrityViolationException(
            "duplicate",
            new ConstraintViolationException(
                "duplicate", new SQLException("dup"), "uk_policy_policy_num"));
    when(processedRequests.save(any(ProcessedRequest.class))).thenThrow(unrelated);

    assertThatThrownBy(() -> service.create(command())).isSameAs(unrelated);
  }

  private static CreatePolicyCommand command() {
    return command(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
  }

  private static CreatePolicyCommand command(LocalDate startDate, LocalDate expiryDate) {
    return new CreatePolicyCommand(
        REQ_ID,
        INSP_ID,
        TXN_ID,
        null, // traceparent
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
        LocalDate.of(2025, 12, 25),
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
