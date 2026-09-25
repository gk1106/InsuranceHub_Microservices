package com.insurancehub.claims.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.insurancehub.claims.domain.OutboxEvent;
import com.insurancehub.claims.infrastructure.persistence.OutboxEventJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// Real MySQL, not H2; policy-service is stubbed with WireMock (same pattern as
// ClaimRegistrationIT). Calls ClaimRegistrationService/OutboxAppender directly (not over HTTP)
// so the "rolled back transaction writes no row" test can force a failure at a precise point
// inside the same transaction the append happens in. Kafka is not needed here: these tests only
// care about what lands in outbox_event, never about publishing it.
@SpringBootTest
@Testcontainers
class OutboxAppenderIT {

  private static final String INSP_ID = "INSP001";

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  private static final WireMockServer WIRE_MOCK = new WireMockServer(0);

  @DynamicPropertySource
  static void policyServiceBaseUrl(DynamicPropertyRegistry registry) {
    WIRE_MOCK.start();
    registry.add("policy-service.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
  }

  @AfterAll
  static void stopWireMock() {
    WIRE_MOCK.stop();
  }

  @Autowired private ClaimRegistrationService claimRegistrationService;
  @Autowired private OutboxEventJpaRepository outboxEvents;
  @Autowired private OutboxAppender outboxAppender;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void resetWireMock() {
    WIRE_MOCK.resetAll();
  }

  @Test
  void aSuccessfulClaimRegistrationWritesExactlyOneOutboxRowInTheSameTransaction() {
    String claimNum = "CLM-OUTBOX-1";
    stubCoverage("POL-OUTBOX-1", LocalDate.of(2026, 6, 1), true);
    claimRegistrationService.register(command("POL-OUTBOX-1", claimNum, "REQ-OUTBOX-1", "TXN-1"));

    var rows =
        outboxEvents.findAll().stream().filter(e -> e.getAggregateId().equals(claimNum)).toList();
    assertThat(rows).hasSize(1);
    OutboxEvent row = rows.get(0);
    assertThat(row.getAggregateType()).isEqualTo("Claim");
    assertThat(row.getEventType()).isEqualTo("ClaimRegistered");
    assertThat(row.getPublishedAt()).isNull();
    assertThat(row.getAttempts()).isZero();
    assertThat(row.getPayload())
        .contains(claimNum)
        .contains("eventType")
        .contains("ClaimRegistered");
  }

  @Test
  void aReplayedRequestNeverAppendsASecondOutboxRow() {
    String claimNum = "CLM-OUTBOX-REPLAY-1";
    stubCoverage("POL-OUTBOX-REPLAY-1", LocalDate.of(2026, 6, 1), true);
    claimRegistrationService.register(
        command("POL-OUTBOX-REPLAY-1", claimNum, "REQ-OUTBOX-REPLAY-1", "TXN-A"));
    claimRegistrationService.register(
        command("POL-OUTBOX-REPLAY-1", claimNum, "REQ-OUTBOX-REPLAY-1", "TXN-B"));

    var rows =
        outboxEvents.findAll().stream().filter(e -> e.getAggregateId().equals(claimNum)).toList();
    assertThat(rows).hasSize(1);
  }

  @Test
  void aRolledBackTransactionWritesNoOutboxRow() {
    String claimNum = "CLM-OUTBOX-ROLLBACK-1";
    var transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    status -> {
                      outboxAppender.append(
                          "Claim",
                          claimNum,
                          "ClaimRegistered",
                          new ClaimRegisteredData(
                              claimNum,
                              "POL1",
                              "ACCIDENT",
                              LocalDate.of(2026, 6, 1),
                              LocalDate.of(2026, 6, 2),
                              new BigDecimal("85000.00"),
                              "REGISTERED"),
                          "TXN1",
                          "REQ1",
                          INSP_ID,
                          null);
                      throw new IllegalStateException("forced rollback after outbox append");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(outboxEvents.findAll().stream().anyMatch(e -> e.getAggregateId().equals(claimNum)))
        .isFalse();
  }

  private void stubCoverage(String policyNum, LocalDate onDate, boolean active) {
    WIRE_MOCK.stubFor(
        get(urlPathEqualTo("/internal/policies/" + policyNum + "/coverage"))
            .withQueryParam("onDate", equalTo(onDate.toString()))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"active\":" + active + "}")));
  }

  private static RegisterClaimCommand command(
      String policyNum, String claimNum, String reqId, String txnId) {
    return new RegisterClaimCommand(
        reqId,
        INSP_ID,
        txnId,
        null,
        policyNum,
        claimNum,
        "ACCIDENT",
        "Minor collision",
        "Collision",
        "Chennai",
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 6, 2),
        new BigDecimal("85000.00"),
        "",
        "");
  }
}
