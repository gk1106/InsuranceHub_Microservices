package com.insurancehub.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insurancehub.policy.domain.OutboxEvent;
import com.insurancehub.policy.infrastructure.persistence.OutboxEventJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// Real MySQL, not H2. Calls PolicyCreationService/OutboxAppender directly (not over HTTP) so
// the "rolled back transaction writes no row" test can force a failure at a precise point
// inside the same transaction the append happens in - something an HTTP-level test has no clean
// way to do. Kafka is not needed here: these tests only care about what lands in outbox_event,
// never about publishing it.
@SpringBootTest
@Testcontainers
class OutboxAppenderIT {

  private static final String INSP_ID = "INSP001";

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  @Autowired private PolicyCreationService policyCreationService;
  @Autowired private OutboxEventJpaRepository outboxEvents;
  @Autowired private OutboxAppender outboxAppender;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void aSuccessfulPolicyCreationWritesExactlyOneOutboxRowInTheSameTransaction() {
    String policyNum = "POL-OUTBOX-1";
    policyCreationService.create(command(policyNum, "REQ-OUTBOX-1", "TXN-OUTBOX-1"));

    var rows =
        outboxEvents.findAll().stream().filter(e -> e.getAggregateId().equals(policyNum)).toList();
    assertThat(rows).hasSize(1);
    OutboxEvent row = rows.get(0);
    assertThat(row.getAggregateType()).isEqualTo("Policy");
    assertThat(row.getEventType()).isEqualTo("PolicyCreated");
    assertThat(row.getPublishedAt()).isNull();
    assertThat(row.getAttempts()).isZero();
    // Not exact-substring matching the spacing Jackson happens to emit (Boot's autoconfigured
    // Jackson 3 mapper writes "key": "value" with a space after the colon, unlike Jackson 2's
    // classic compact default) - just that both the field name and its value are present.
    assertThat(row.getPayload())
        .contains(policyNum)
        .contains("eventType")
        .contains("PolicyCreated");
  }

  @Test
  void aReplayedRequestNeverAppendsASecondOutboxRow() {
    String policyNum = "POL-OUTBOX-REPLAY-1";
    policyCreationService.create(command(policyNum, "REQ-OUTBOX-REPLAY-1", "TXN-A"));
    // Same (inspId, reqId) - a real gateway retry generates a fresh txnId per HTTP attempt.
    policyCreationService.create(command(policyNum, "REQ-OUTBOX-REPLAY-1", "TXN-B"));

    var rows =
        outboxEvents.findAll().stream().filter(e -> e.getAggregateId().equals(policyNum)).toList();
    assertThat(rows).hasSize(1);
  }

  @Test
  void aRolledBackTransactionWritesNoOutboxRow() {
    String policyNum = "POL-OUTBOX-ROLLBACK-1";
    var transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    status -> {
                      outboxAppender.append(
                          "Policy",
                          policyNum,
                          "PolicyCreated",
                          new PolicyCreatedData(
                              policyNum,
                              1,
                              "GENERAL",
                              "ACTIVE",
                              LocalDate.of(2025, 12, 25),
                              LocalDate.of(2026, 1, 1),
                              LocalDate.of(2027, 1, 1),
                              new BigDecimal("15000.00"),
                              new BigDecimal("17700.00"),
                              new BigDecimal("500000.00")),
                          "TXN1",
                          "REQ1",
                          INSP_ID,
                          null);
                      throw new IllegalStateException("forced rollback after outbox append");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(outboxEvents.findAll().stream().anyMatch(e -> e.getAggregateId().equals(policyNum)))
        .isFalse();
  }

  private static CreatePolicyCommand command(String policyNum, String reqId, String txnId) {
    return new CreatePolicyCommand(
        reqId,
        INSP_ID,
        txnId,
        null,
        policyNum,
        "APP1",
        "CIF456789",
        "ACC99887766",
        "Ravi Kumar",
        "9876543210",
        "12, MG Road, Chennai",
        "GENERAL",
        "Motor Insurance",
        "RC01",
        "South Region",
        "BR102",
        "Chennai Main Branch",
        "LN22334455",
        "SP7890",
        "Agent Suresh",
        "ACTIVE",
        LocalDate.of(2025, 12, 25),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1),
        new BigDecimal("15000.00"),
        new BigDecimal("2700.00"),
        new BigDecimal("17700.00"),
        new BigDecimal("500000.00"),
        new BigDecimal("5.00"),
        new BigDecimal("750.00"));
  }
}
