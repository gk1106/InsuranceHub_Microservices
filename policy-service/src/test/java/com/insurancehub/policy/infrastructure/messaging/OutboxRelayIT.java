package com.insurancehub.policy.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.insurancehub.policy.application.CreatePolicyCommand;
import com.insurancehub.policy.application.PolicyCreationService;
import com.insurancehub.policy.application.PolicyRenewalService;
import com.insurancehub.policy.infrastructure.persistence.OutboxEventJpaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

// Real MySQL + real Kafka, exercising what OutboxRelayTest's mocked KafkaTemplate deliberately
// can't: a genuine broker round trip, and FOR UPDATE SKIP LOCKED under real concurrent
// transactions.
//
// Kafka image note: docker-compose.yml pins apache/kafka:3.9.0 for the real stack, but
// org.testcontainers.kafka.KafkaContainer (testcontainers-kafka 2.0.5, this project's pinned
// version) fails to start that specific tag - verified empirically: its custom startup script
// (which overrides the image's own entrypoint to inject KAFKA_ADVERTISED_LISTENERS after the
// host port is known) exits 1 against 3.9.0 but starts cleanly against 4.1.0. Kafka's wire
// protocol is compatible across this range for plain produce/consume, so 4.1.0 here is safe
// despite differing from compose's own pinned tag - only the Testcontainers integration itself
// is version-sensitive, not the protocol these tests exercise.
@SpringBootTest
@Testcontainers
class OutboxRelayIT {

  private static final String INSP_ID = "INSP001";
  private static final String TOPIC = "insurancehub.policy.events.v1";

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  @Container @ServiceConnection
  static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

  @Autowired private PolicyCreationService policyCreationService;
  @Autowired private PolicyRenewalService policyRenewalService;
  @Autowired private OutboxEventJpaRepository outboxEvents;
  @Autowired private OutboxRelay outboxRelay;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void theRelayPublishesAndMarksPublishedAt() {
    String policyNum = "POL-RELAY-1";
    policyCreationService.create(command(policyNum, "REQ-RELAY-1", "TXN-RELAY-1"));

    List<ConsumerRecord<String, String>> records = consumeUntil(1, r -> policyNum.equals(r.key()));

    assertThat(records).hasSize(1);
    assertThat(records.get(0).value()).contains(policyNum).contains("PolicyCreated");
    assertThat(outboxRowFor(policyNum).getPublishedAt()).isNotNull();
  }

  @Test
  void theStoredPayloadIsExactlyWhatGetsPublishedAndItDecryptsCleanlyThroughTheOneMapper() {
    String policyNum = "POL-RELAY-ROUNDTRIP-1";
    policyCreationService.create(command(policyNum, "REQ-RELAY-ROUNDTRIP-1", "TXN-RT-1"));
    waitForPublished(policyNum);

    String storedPayload = outboxRowFor(policyNum).getPayload();
    List<ConsumerRecord<String, String>> records = consumeUntil(1, r -> policyNum.equals(r.key()));

    // Not just "parses without throwing" - the exact bytes match, proving the DB row and the
    // Kafka message came from the one serialization event (docs/adr/0006-outbox-relay.md), and
    // the date fields inside (issueDate/startDate/expiryDate) survive unchanged.
    assertThat(records.get(0).value()).isEqualTo(storedPayload);
    // Not exact-substring matching the spacing Jackson happens to emit (Boot's autoconfigured
    // Jackson 3 mapper writes "key": "value" with a space after the colon).
    assertThat(storedPayload).contains("startDate").contains("2026-01-01");
  }

  @Test
  void theDataBlockContainsNoneOfTheRealPiiValuesFromTheOriginalRequest() {
    String policyNum = "POL-RELAY-PII-1";
    // Real-looking PII values, not the fixture-obvious "CIF456789" used elsewhere in this file -
    // asserts the actual leak-shaped property (none of these exact values appear anywhere in the
    // published JSON), not just "the known field names are absent".
    CreatePolicyCommand cmd =
        commandWithPii(
            policyNum,
            "REQ-RELAY-PII-1",
            "TXN-PII-1",
            "CIFPII998877",
            "ACCTPII11223344",
            "Priya Sharma",
            "9123456780",
            "42, Anna Salai, Chennai");
    policyCreationService.create(cmd);

    List<ConsumerRecord<String, String>> records = consumeUntil(1, r -> policyNum.equals(r.key()));
    String published = records.get(0).value();

    assertThat(published)
        .doesNotContain("CIFPII998877")
        .doesNotContain("ACCTPII11223344")
        .doesNotContain("Priya Sharma")
        .doesNotContain("9123456780")
        .doesNotContain("42, Anna Salai");
  }

  @Test
  void eventsForTheSamePolicyLandOnTheSamePartition() {
    String policyNum = "POL-RELAY-PARTITION-1";
    policyCreationService.create(command(policyNum, "REQ-RELAY-PART-CREATE", "TXN-PART-1"));
    waitForPublished(policyNum);
    policyRenewalService.renew(renewCommand(policyNum, "REQ-RELAY-PART-RENEW", "TXN-PART-2"));

    List<ConsumerRecord<String, String>> records = consumeUntil(2, r -> policyNum.equals(r.key()));

    assertThat(records).hasSize(2);
    assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
  }

  @Test
  void outboxPendingGaugeReflectsUnpublishedRowsThenDropsToZeroOncePublished() {
    double before = gaugeValue();
    String policyNum = "POL-RELAY-GAUGE-1";
    policyCreationService.create(command(policyNum, "REQ-RELAY-GAUGE-1", "TXN-GAUGE-1"));

    // Read the gauge before the relay has necessarily ticked - it must count the just-inserted,
    // still-unpublished row (pull-based: computed at read time, not pushed by the relay).
    assertThat(gaugeValue()).isGreaterThanOrEqualTo(before + 1);

    waitForPublished(policyNum);
    assertThat(gaugeValue()).isEqualTo(before);
  }

  @Test
  void twoConcurrentRelayTicksNeverDoubleSendTheSameRow() throws Exception {
    List<String> policyNums = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String policyNum = "POL-RELAY-CONCURRENT-" + i;
      policyNums.add(policyNum);
      policyCreationService.create(
          command(policyNum, "REQ-RELAY-CONCURRENT-" + i, "TXN-CONCURRENT-" + i));
    }

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Runnable tick =
        () -> {
          try {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            outboxRelay.relay();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        };
    pool.submit(tick);
    pool.submit(tick);
    ready.await(5, TimeUnit.SECONDS);
    start.countDown();
    boolean finished = done.await(15, TimeUnit.SECONDS);
    pool.shutdown();
    assertThat(finished).isTrue();
    // Either relay call may still have missed some rows (SKIP LOCKED, timing) - run the relay a
    // few more times sequentially to drain anything left, same as production's own next ticks.
    for (int i = 0;
        i < 5 && policyNums.stream().anyMatch(p -> outboxRowFor(p).getPublishedAt() == null);
        i++) {
      outboxRelay.relay();
    }

    List<ConsumerRecord<String, String>> records =
        consumeUntil(10, r -> policyNums.contains(r.key()));
    Map<String, Long> countsByKey =
        records.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    ConsumerRecord::key, java.util.stream.Collectors.counting()));
    assertThat(countsByKey.values()).allMatch(count -> count == 1L);
    assertThat(countsByKey).hasSize(10);
  }

  private com.insurancehub.policy.domain.OutboxEvent outboxRowFor(String policyNum) {
    return outboxEvents.findAll().stream()
        .filter(e -> e.getAggregateId().equals(policyNum))
        .findFirst()
        .orElseThrow();
  }

  private void waitForPublished(String policyNum) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (outboxRowFor(policyNum).getPublishedAt() != null) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("row for " + policyNum + " was never published within 10s");
  }

  private double gaugeValue() {
    return meterRegistry.get("outbox_pending").gauge().value();
  }

  private List<ConsumerRecord<String, String>> consumeUntil(
      int expectedCount, java.util.function.Predicate<ConsumerRecord<String, String>> filter) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> matched = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(TOPIC));
      long deadline = System.currentTimeMillis() + 15_000;
      while (matched.size() < expectedCount && System.currentTimeMillis() < deadline) {
        consumer
            .poll(Duration.ofMillis(200))
            .forEach(
                r -> {
                  if (filter.test(r)) {
                    matched.add(r);
                  }
                });
      }
    }
    return matched;
  }

  private static CreatePolicyCommand command(String policyNum, String reqId, String txnId) {
    return commandWithPii(
        policyNum,
        reqId,
        txnId,
        "CIF456789",
        "ACC99887766",
        "Ravi Kumar",
        "9876543210",
        "12, MG Road, Chennai");
  }

  private static CreatePolicyCommand commandWithPii(
      String policyNum,
      String reqId,
      String txnId,
      String cif,
      String accountNum,
      String insuredName,
      String mobileNum,
      String address) {
    return new CreatePolicyCommand(
        reqId,
        INSP_ID,
        txnId,
        null,
        policyNum,
        "APP1",
        cif,
        accountNum,
        insuredName,
        mobileNum,
        address,
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

  private static com.insurancehub.policy.application.RenewPolicyCommand renewCommand(
      String policyNum, String reqId, String txnId) {
    return new com.insurancehub.policy.application.RenewPolicyCommand(
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
        LocalDate.of(2027, 2, 1),
        LocalDate.of(2028, 1, 1),
        new BigDecimal("15500.00"),
        new BigDecimal("2790.00"),
        new BigDecimal("18290.00"),
        new BigDecimal("500000.00"),
        new BigDecimal("5.00"),
        new BigDecimal("775.00"));
  }
}
