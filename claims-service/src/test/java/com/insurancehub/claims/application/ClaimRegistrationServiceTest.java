package com.insurancehub.claims.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insurancehub.claims.domain.Claim;
import com.insurancehub.claims.domain.ClaimStatusHistory;
import com.insurancehub.claims.domain.ProcessedRequest;
import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

class ClaimRegistrationServiceTest {

  private static final String INSP_ID = "INSP001";
  private static final String REQ_ID = "REQ1";
  private static final String TXN_ID = "01JTXNAAAAAAAAAAAAAAAAAAAA";
  private static final String POLICY_NUM = "POL1";
  private static final String CLAIM_NUM = "CLM1";

  private ClaimRepository claims;
  private ClaimStatusHistoryRepository claimStatusHistory;
  private ProcessedRequestRepository processedRequests;
  private PolicyCoverageGateway policyCoverageGateway;
  private OutboxAppender outboxAppender;
  private ClaimRegistrationService service;

  @BeforeEach
  void setUp() {
    claims = mock(ClaimRepository.class);
    claimStatusHistory = mock(ClaimStatusHistoryRepository.class);
    processedRequests = mock(ProcessedRequestRepository.class);
    policyCoverageGateway = mock(PolicyCoverageGateway.class);
    outboxAppender = mock(OutboxAppender.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    service =
        new ClaimRegistrationService(
            claims,
            claimStatusHistory,
            processedRequests,
            policyCoverageGateway,
            outboxAppender,
            transactionManager,
            new SimpleMeterRegistry());

    when(claims.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
    when(claimStatusHistory.save(any(ClaimStatusHistory.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(processedRequests.save(any(ProcessedRequest.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void registersANewClaimWhenNothingExistsYet() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(claims.existsByClaimNum(CLAIM_NUM)).thenReturn(false);
    when(policyCoverageGateway.checkCoverage(POLICY_NUM, LocalDate.of(2026, 6, 1)))
        .thenReturn(new CoverageStatus(true));

    RegisterClaimResult result = service.register(command());

    assertThat(result.replayed()).isFalse();
    assertThat(result.txnId()).isEqualTo(TXN_ID);
    assertThat(result.claimNum()).isEqualTo(CLAIM_NUM);
    verify(claims).save(any(Claim.class));
    verify(claimStatusHistory).save(any(ClaimStatusHistory.class));
    verify(processedRequests).save(any(ProcessedRequest.class));
  }

  @Test
  void replaysWhenTheRequestWasAlreadyProcessed() {
    ProcessedRequest existing =
        ProcessedRequest.of(INSP_ID, REQ_ID, "ClaimService", "ORIGINAL-TXN", CLAIM_NUM);
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.of(existing));

    RegisterClaimResult result = service.register(command());

    assertThat(result.replayed()).isTrue();
    assertThat(result.txnId()).isEqualTo("ORIGINAL-TXN");
    assertThat(result.claimNum()).isEqualTo(CLAIM_NUM);
    verify(claims, never()).save(any());
    verify(policyCoverageGateway, never()).checkCoverage(any(), any());
  }

  @Test
  void rejectsDateOfLossAfterIntimationDate() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    RegisterClaimCommand cmd = command(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 1));

    assertThatThrownBy(() -> service.register(cmd))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.VALIDATION_FAILED));
    verify(policyCoverageGateway, never()).checkCoverage(any(), any());
  }

  @Test
  void propagatesPolicyNotFoundFromTheCoverageGateway() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policyCoverageGateway.checkCoverage(POLICY_NUM, LocalDate.of(2026, 6, 1)))
        .thenThrow(new HubBusinessException(HubErrorCode.POLICY_NOT_FOUND, POLICY_NUM));

    assertThatThrownBy(() -> service.register(command()))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.POLICY_NOT_FOUND));
    verify(claims, never()).existsByClaimNum(any());
  }

  @Test
  void rejectsRegistrationWhenThePolicyIsNotActiveOnTheDateOfLoss() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policyCoverageGateway.checkCoverage(POLICY_NUM, LocalDate.of(2026, 6, 1)))
        .thenReturn(new CoverageStatus(false));

    assertThatThrownBy(() -> service.register(command()))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.POLICY_NOT_ACTIVE));
    verify(claims, never()).save(any());
  }

  @Test
  void rejectsADuplicateClaimNumForANewRequest() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(policyCoverageGateway.checkCoverage(POLICY_NUM, LocalDate.of(2026, 6, 1)))
        .thenReturn(new CoverageStatus(true));
    when(claims.existsByClaimNum(CLAIM_NUM)).thenReturn(true);

    assertThatThrownBy(() -> service.register(command()))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.CLAIM_ALREADY_EXISTS));
    verify(claims, never()).save(any());
  }

  @Test
  void losingTheIdempotencyRaceReturnsTheWinnersReplayResultInsteadOfThrowing() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID))
        .thenReturn(Optional.empty()) // first look, before the race is lost
        .thenReturn( // second look, after catching the constraint violation
            Optional.of(
                ProcessedRequest.of(INSP_ID, REQ_ID, "ClaimService", "WINNER-TXN", CLAIM_NUM)));
    when(policyCoverageGateway.checkCoverage(POLICY_NUM, LocalDate.of(2026, 6, 1)))
        .thenReturn(new CoverageStatus(true));
    when(claims.existsByClaimNum(CLAIM_NUM)).thenReturn(false);
    when(processedRequests.save(any(ProcessedRequest.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate",
                new ConstraintViolationException(
                    "duplicate", new SQLException("dup"), "uk_processed_request")));

    RegisterClaimResult result = service.register(command());

    assertThat(result.replayed()).isTrue();
    assertThat(result.txnId()).isEqualTo("WINNER-TXN");
  }

  private static RegisterClaimCommand command() {
    return command(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));
  }

  private static RegisterClaimCommand command(LocalDate dateOfLoss, LocalDate intimationDate) {
    return new RegisterClaimCommand(
        REQ_ID,
        INSP_ID,
        TXN_ID,
        null, // traceparent
        POLICY_NUM,
        CLAIM_NUM,
        "ACCIDENT",
        "Front bumper damage",
        "Collision",
        "Chennai",
        dateOfLoss,
        intimationDate,
        new BigDecimal("85000.00"),
        null,
        null);
  }
}
