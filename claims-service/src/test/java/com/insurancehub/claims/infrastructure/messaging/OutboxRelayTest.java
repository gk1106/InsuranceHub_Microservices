package com.insurancehub.claims.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insurancehub.claims.config.OutboxRelayProperties;
import com.insurancehub.claims.domain.OutboxEvent;
import com.insurancehub.claims.infrastructure.persistence.OutboxEventJpaRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;

// Unit-level, mocked KafkaTemplate: proves the relay's own retry/isolation state machine
// precisely and deterministically. The real-broker publish path itself (a genuine send-and-
// consume round trip, and the FOR UPDATE SKIP LOCKED concurrency guarantee) is covered
// separately by OutboxRelayIT against a real Kafka Testcontainer.
class OutboxRelayTest {

  private static final OutboxRelayProperties PROPERTIES =
      new OutboxRelayProperties(
          100, Duration.ofSeconds(1), 3, 1000, "insurancehub.claim.events.v1");

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

  private final OutboxEventJpaRepository outboxEvents = mock(OutboxEventJpaRepository.class);
  private final OutboxRelay relay = new OutboxRelay(outboxEvents, kafkaTemplate, PROPERTIES);

  @Test
  void aFailedSendIncrementsAttemptsAndLeavesTheRowUnpublished() {
    OutboxEvent event = row("evt-1", "CLM1");
    when(outboxEvents.findBatchForUpdate(100, 1000)).thenReturn(List.of(event));
    when(kafkaTemplate.send(any(Message.class)))
        .thenReturn(
            java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("boom")));

    relay.relay();

    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);
  }

  @Test
  void aFailureThenASuccessOnALaterRunEventuallyPublishes() {
    OutboxEvent event = row("evt-2", "CLM2");
    when(outboxEvents.findBatchForUpdate(100, 1000)).thenReturn(List.of(event));
    @SuppressWarnings("unchecked")
    SendResult<String, String> sendResult = mock(SendResult.class);
    when(kafkaTemplate.send(any(Message.class)))
        .thenReturn(
            java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("boom")))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(sendResult));

    relay.relay();
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);

    relay.relay();
    assertThat(event.getPublishedAt()).isNotNull();
    assertThat(event.getAttempts()).isEqualTo(1);
  }

  @Test
  void onePoisonRowNeverStopsTheOthersInTheSameBatchFromPublishing() {
    OutboxEvent poison = row("evt-poison", "CLM-POISON");
    OutboxEvent healthyA = row("evt-a", "CLM-A");
    OutboxEvent healthyB = row("evt-b", "CLM-B");
    when(outboxEvents.findBatchForUpdate(100, 1000))
        .thenReturn(List.of(poison, healthyA, healthyB));
    @SuppressWarnings("unchecked")
    SendResult<String, String> sendResult = mock(SendResult.class);
    when(kafkaTemplate.send(any(Message.class)))
        .thenAnswer(
            invocation -> {
              Message<?> message = invocation.getArgument(0);
              String key =
                  (String)
                      message.getHeaders().get(org.springframework.kafka.support.KafkaHeaders.KEY);
              if ("CLM-POISON".equals(key)) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new RuntimeException("always fails"));
              }
              return java.util.concurrent.CompletableFuture.completedFuture(sendResult);
            });

    relay.relay();

    assertThat(poison.getPublishedAt()).isNull();
    assertThat(poison.getAttempts()).isEqualTo(1);
    assertThat(healthyA.getPublishedAt()).isNotNull();
    assertThat(healthyB.getPublishedAt()).isNotNull();
  }

  @Test
  void attemptsCrossingTheAlertThresholdStillRetriesRatherThanGivingUp() {
    OutboxEvent event = row("evt-3", "CLM3");
    for (int i = 0; i < 3; i++) {
      event.incrementAttempts();
    }
    when(outboxEvents.findBatchForUpdate(100, 1000)).thenReturn(List.of(event));
    when(kafkaTemplate.send(any(Message.class)))
        .thenReturn(
            java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("boom")));

    relay.relay();

    assertThat(event.getAttempts()).isEqualTo(4);
    assertThat(event.getPublishedAt()).isNull();
  }

  private static OutboxEvent row(String id, String claimNum) {
    return OutboxEvent.builder()
        .id(id)
        .aggregateType("Claim")
        .aggregateId(claimNum)
        .eventType("ClaimRegistered")
        .payload("{\"eventId\":\"" + id + "\"}")
        .build();
  }
}
