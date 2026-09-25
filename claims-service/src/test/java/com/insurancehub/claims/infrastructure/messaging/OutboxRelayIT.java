package com.insurancehub.claims.infrastructure.messaging;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.insurancehub.claims.application.ClaimRegistrationService;
import com.insurancehub.claims.application.ClaimStatusUpdateService;
import com.insurancehub.claims.application.RegisterClaimCommand;
import com.insurancehub.claims.application.UpdateClaimStatusCommand;
import com.insurancehub.claims.infrastructure.persistence.OutboxEventJpaRepository;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

// Real MySQL + real Kafka; policy-service is stubbed with WireMock. Exercises what
// OutboxRelayTest's mocked KafkaTemplate deliberately can't: a genuine broker round trip, and
// FOR UPDATE SKIP LOCKED under real concurrent transactions.
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
  private static final String TOPIC = "insurancehub.claim.events.v1";

  @Container @ServiceConnection static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

  @Container @ServiceConnection
  static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

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
  @Autowired private ClaimStatusUpdateService claimStatusUpdateService;
  @Autowired private OutboxEventJpaRepository outboxEvents;
  @Autowired private OutboxRelay outboxRelay;
  @Autowired private MeterRegistry meterRegistry;

  @BeforeEach
  void resetWireMock() {
    WIRE_MOCK.resetAll();
  }

  @Test
  void theRelayPublishesAndMarksPublishedAt() {
    String claimNum = "CLM-RELAY-1";
    stubCoverage("POL-RELAY-1", true);
    claimRegistrationService.register(command("POL-RELAY-1", claimNum, "REQ-RELAY-1", "TXN-1"));

    List<ConsumerRecord<String, String>> records = consumeUntil(1, r -> claimNum.equals(r.key()));

    assertThat(records).hasSize(1);
    assertThat(records.get(0).value()).contains(claimNum).contains("ClaimRegistered");
    assertThat(outboxRowFor(claimNum).getPublishedAt()).isNotNull();
  }

  @Test
  void theStoredPayloadIsExactlyWhatGetsPublished() {
    String claimNum = "CLM-RELAY-ROUNDTRIP-1";
    stubCoverage("POL-RELAY-RT-1", true);
    claimRegistrationService.register(
        command("POL-RELAY-RT-1", claimNum, "REQ-RELAY-ROUNDTRIP-1", "TXN-RT-1"));
    waitForPublished(claimNum);

    String storedPayload = outboxRowFor(claimNum).getPayload();
    List<ConsumerRecord<String, String>> records = consumeUntil(1, r -> claimNum.equals(r.key()));

    assertThat(records.get(0).value()).isEqualTo(storedPayload);
    assertThat(storedPayload).contains("dateOfLoss").contains("2026-06-01");
  }

  @Test
  void theDataBlockContainsNoneOfTheRealPiiValuesFromTheOriginalRequest() {
    String claimNum = "CLM-RELAY-PII-1";
    stubCoverage("POL-RELAY-PII-1", true);
    // ClaimRegisteredData never carries lossDesc/lossCity/claimType-adjacent free text at all,
    // but the request itself does (lossDesc) - proves it never leaks into the published event.
    claimRegistrationService.register(
        commandWithLossDesc(
            "POL-RELAY-PII-1",
            claimNum,
            "REQ-RELAY-PII-1",
            "TXN-PII-1",
            "Vehicle registered to Arjun Mehta, contact 9988776655"));

    List<ConsumerRecord<String, String>> records = consumeUntil(1, r -> claimNum.equals(r.key()));
    String published = records.get(0).value();

    assertThat(published)
        .doesNotContain("Arjun Mehta")
        .doesNotContain("9988776655")
        .doesNotContain("Vehicle registered to");
  }

  @Test
  void eventsForTheSameClaimLandOnTheSamePartition() {
    String claimNum = "CLM-RELAY-PARTITION-1";
    stubCoverage("POL-RELAY-PART-1", true);
    claimRegistrationService.register(
        command("POL-RELAY-PART-1", claimNum, "REQ-RELAY-PART-CREATE", "TXN-PART-1"));
    waitForPublished(claimNum);
    claimStatusUpdateService.updateStatus(
        statusCommand(claimNum, "POL-RELAY-PART-1", "REQ-RELAY-PART-UPDATE", "TXN-PART-2"));

    List<ConsumerRecord<String, String>> records = consumeUntil(2, r -> claimNum.equals(r.key()));

    assertThat(records).hasSize(2);
    assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
  }

  @Test
  void outboxPendingGaugeReflectsUnpublishedRowsThenDropsToZeroOncePublished() {
    double before = gaugeValue();
    String claimNum = "CLM-RELAY-GAUGE-1";
    stubCoverage("POL-RELAY-GAUGE-1", true);
    claimRegistrationService.register(
        command("POL-RELAY-GAUGE-1", claimNum, "REQ-RELAY-GAUGE-1", "TXN-GAUGE-1"));

    assertThat(gaugeValue()).isGreaterThanOrEqualTo(before + 1);

    waitForPublished(claimNum);
    assertThat(gaugeValue()).isEqualTo(before);
  }

  @Test
  void twoConcurrentRelayTicksNeverDoubleSendTheSameRow() throws Exception {
    List<String> claimNums = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String claimNum = "CLM-RELAY-CONCURRENT-" + i;
      claimNums.add(claimNum);
      String policyNum = "POL-RELAY-CONCURRENT-" + i;
      stubCoverage(policyNum, true);
      claimRegistrationService.register(
          command(policyNum, claimNum, "REQ-RELAY-CONCURRENT-" + i, "TXN-CONCURRENT-" + i));
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
    for (int i = 0;
        i < 5 && claimNums.stream().anyMatch(c -> outboxRowFor(c).getPublishedAt() == null);
        i++) {
      outboxRelay.relay();
    }

    List<ConsumerRecord<String, String>> records =
        consumeUntil(10, r -> claimNums.contains(r.key()));
    Map<String, Long> countsByKey =
        records.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    ConsumerRecord::key, java.util.stream.Collectors.counting()));
    assertThat(countsByKey.values()).allMatch(count -> count == 1L);
    assertThat(countsByKey).hasSize(10);
  }

  private com.insurancehub.claims.domain.OutboxEvent outboxRowFor(String claimNum) {
    return outboxEvents.findAll().stream()
        .filter(e -> e.getAggregateId().equals(claimNum))
        .findFirst()
        .orElseThrow();
  }

  private void waitForPublished(String claimNum) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (outboxRowFor(claimNum).getPublishedAt() != null) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("row for " + claimNum + " was never published within 10s");
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

  private void stubCoverage(String policyNum, boolean active) {
    WIRE_MOCK.stubFor(
        get(urlPathEqualTo("/internal/policies/" + policyNum + "/coverage"))
            .withQueryParam("onDate", equalTo("2026-06-01"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"active\":" + active + "}")));
  }

  private static RegisterClaimCommand command(
      String policyNum, String claimNum, String reqId, String txnId) {
    return commandWithLossDesc(policyNum, claimNum, reqId, txnId, "Minor collision");
  }

  private static RegisterClaimCommand commandWithLossDesc(
      String policyNum, String claimNum, String reqId, String txnId, String lossDesc) {
    return new RegisterClaimCommand(
        reqId,
        INSP_ID,
        txnId,
        null,
        policyNum,
        claimNum,
        "ACCIDENT",
        lossDesc,
        "Collision",
        "Chennai",
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 6, 2),
        new BigDecimal("85000.00"),
        "",
        "");
  }

  private static UpdateClaimStatusCommand statusCommand(
      String claimNum, String policyNum, String reqId, String txnId) {
    return new UpdateClaimStatusCommand(
        reqId,
        INSP_ID,
        txnId,
        null,
        claimNum,
        policyNum,
        "UNDER_PROCESS",
        null,
        "",
        null,
        null,
        "",
        null,
        "");
  }
}
