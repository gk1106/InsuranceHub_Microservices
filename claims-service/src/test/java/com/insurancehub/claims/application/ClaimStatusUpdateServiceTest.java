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
import com.insurancehub.claims.domain.ClaimStatusPolicy;
import com.insurancehub.claims.domain.ProcessedRequest;
import com.insurancehub.common.error.HubBusinessException;
import com.insurancehub.common.error.HubErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class ClaimStatusUpdateServiceTest {

  private static final String INSP_ID = "INSP001";
  private static final String REQ_ID = "REQ1";
  private static final String TXN_ID = "01JTXNAAAAAAAAAAAAAAAAAAAA";
  private static final String POLICY_NUM = "POL1";
  private static final String CLAIM_NUM = "CLM1";

  private ClaimRepository claims;
  private ClaimStatusHistoryRepository claimStatusHistory;
  private ProcessedRequestRepository processedRequests;
  private OutboxAppender outboxAppender;
  private ClaimStatusUpdateService service;

  @BeforeEach
  void setUp() {
    claims = mock(ClaimRepository.class);
    claimStatusHistory = mock(ClaimStatusHistoryRepository.class);
    processedRequests = mock(ProcessedRequestRepository.class);
    outboxAppender = mock(OutboxAppender.class);
    ClaimStatusPolicy claimStatusPolicy =
        new ClaimStatusPolicy(
            Set.of("CLOSED", "REPUDIATED", "CANCELLED"),
            Map.of("REGISTERED", List.of("UNDER_PROCESS", "CLOSED", "REPUDIATED", "CANCELLED")));
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    service =
        new ClaimStatusUpdateService(
            claims,
            claimStatusHistory,
            processedRequests,
            claimStatusPolicy,
            outboxAppender,
            transactionManager);

    when(claims.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
    when(claimStatusHistory.save(any(ClaimStatusHistory.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(processedRequests.save(any(ProcessedRequest.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void updatesTheStatusWhenTheTransitionIsAllowed() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(claims.findByClaimNum(CLAIM_NUM)).thenReturn(Optional.of(existingClaim("REGISTERED")));

    UpdateClaimStatusResult result = service.updateStatus(command("UNDER_PROCESS"));

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
        ProcessedRequest.of(INSP_ID, REQ_ID, "ClaimStatusService", "ORIGINAL-TXN", CLAIM_NUM);
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.of(existing));

    UpdateClaimStatusResult result = service.updateStatus(command("UNDER_PROCESS"));

    assertThat(result.replayed()).isTrue();
    assertThat(result.txnId()).isEqualTo("ORIGINAL-TXN");
    verify(claims, never()).findByClaimNum(any());
  }

  @Test
  void rejectsAnUnknownClaimNum() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(claims.findByClaimNum(CLAIM_NUM)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateStatus(command("UNDER_PROCESS")))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.CLAIM_NOT_FOUND));
    verify(claims, never()).save(any());
  }

  @Test
  void rejectsAClaimThatExistsUnderADifferentPolicyAsIfItDidNotExist() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(claims.findByClaimNum(CLAIM_NUM))
        .thenReturn(Optional.of(existingClaimForPolicy("REGISTERED", "POL-DIFFERENT")));

    assertThatThrownBy(() -> service.updateStatus(command("UNDER_PROCESS")))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex -> {
              HubBusinessException hbe = (HubBusinessException) ex;
              assertThat(hbe.code()).isEqualTo(HubErrorCode.CLAIM_NOT_FOUND);
              // Never reveals the real policyNum or that a mismatch, not absence, is why.
              assertThat(hbe.safeDetail()).doesNotContain("POL-DIFFERENT", "policy", "Policy");
            });
  }

  @Test
  void rejectsATransitionNotAllowedByThePolicy() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(claims.findByClaimNum(CLAIM_NUM)).thenReturn(Optional.of(existingClaim("REGISTERED")));

    assertThatThrownBy(() -> service.updateStatus(command("REQUIREMENT_PENDING")))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.INVALID_STATUS_TRANSITION));
    verify(claims, never()).save(any());
  }

  @Test
  void rejectsAnyTransitionOutOfATerminalStatus() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID)).thenReturn(Optional.empty());
    when(claims.findByClaimNum(CLAIM_NUM)).thenReturn(Optional.of(existingClaim("CLOSED")));

    assertThatThrownBy(() -> service.updateStatus(command("UNDER_PROCESS")))
        .isInstanceOf(HubBusinessException.class)
        .satisfies(
            ex ->
                assertThat(((HubBusinessException) ex).code())
                    .isEqualTo(HubErrorCode.INVALID_STATUS_TRANSITION));
  }

  @Test
  void losingTheIdempotencyRaceReturnsTheWinnersReplayResultInsteadOfThrowing() {
    when(processedRequests.findByInspIdAndReqId(INSP_ID, REQ_ID))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                ProcessedRequest.of(
                    INSP_ID, REQ_ID, "ClaimStatusService", "WINNER-TXN", CLAIM_NUM)));
    when(claims.findByClaimNum(CLAIM_NUM)).thenReturn(Optional.of(existingClaim("REGISTERED")));
    when(processedRequests.save(any(ProcessedRequest.class)))
        .thenThrow(
            new org.springframework.dao.DataIntegrityViolationException(
                "duplicate",
                new org.hibernate.exception.ConstraintViolationException(
                    "duplicate", new java.sql.SQLException("dup"), "uk_processed_request")));

    UpdateClaimStatusResult result = service.updateStatus(command("UNDER_PROCESS"));

    assertThat(result.replayed()).isTrue();
    assertThat(result.txnId()).isEqualTo("WINNER-TXN");
  }

  private static Claim existingClaim(String claimStatus) {
    return existingClaimForPolicy(claimStatus, POLICY_NUM);
  }

  private static Claim existingClaimForPolicy(String claimStatus, String policyNum) {
    return Claim.builder()
        .claimNum(CLAIM_NUM)
        .policyNum(policyNum)
        .inspId(INSP_ID)
        .claimType("ACCIDENT")
        .claimStatus(claimStatus)
        .build();
  }

  private static UpdateClaimStatusCommand command(String claimStatus) {
    return new UpdateClaimStatusCommand(
        REQ_ID,
        INSP_ID,
        TXN_ID,
        null, // traceparent
        CLAIM_NUM,
        POLICY_NUM,
        claimStatus,
        new BigDecimal("50000.00"),
        "CLM-CODE-1",
        null,
        null,
        null,
        null,
        null);
  }
}
