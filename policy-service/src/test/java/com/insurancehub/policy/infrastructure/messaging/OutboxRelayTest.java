package com.insurancehub.policy.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insurancehub.policy.config.OutboxRelayProperties;
import com.insurancehub.policy.domain.OutboxEvent;
import com.insurancehub.policy.infrastructure.persistence.OutboxEventJpaRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;

// Unit-level, mocked KafkaTemplate: proves the relay's own retry/isolation state machine
// precisely and deterministically (exactly which sends fail, in what order), independent of a
// real broker's actual failure/recovery timing. The real-broker publish path itself (a genuine
// send-and-consume round trip, and the FOR UPDATE SKIP LOCKED concurrency guarantee, which a
// mocked repository can't meaningfully exercise) is covered separately by OutboxRelayIT against
// a real Kafka Testcontainer.
class OutboxRelayTest {

  private static final OutboxRelayProperties PROPERTIES =
      new OutboxRelayProperties(
          100, Duration.ofSeconds(1), 3, 1000, "insurancehub.policy.events.v1");

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

  private final OutboxEventJpaRepository outboxEvents = mock(OutboxEventJpaRepository.class);
  private final OutboxRelay relay = new OutboxRelay(outboxEvents, kafkaTemplate, PROPERTIES);

  @Test
  void aFailedSendIncrementsAttemptsAndLeavesTheRowUnpublished() {
    OutboxEvent event = row("evt-1", "POL1");
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
    OutboxEvent event = row("evt-2", "POL2");
    when(outboxEvents.findBatchForUpdate(100, 1000)).thenReturn(List.of(event));
    @SuppressWarnings("unchecked")
    SendResult<String, String> sendResult = mock(SendResult.class);
    when(kafkaTemplate.send(any(Message.class)))
        .thenReturn(
            java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("boom")))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(sendResult));

    relay.relay(); // fails
    assertThat(event.getPublishedAt()).isNull();
    assertThat(event.getAttempts()).isEqualTo(1);

    relay.relay(); // succeeds
    assertThat(event.getPublishedAt()).isNotNull();
    assertThat(event.getAttempts()).isEqualTo(1); // unchanged by the successful attempt
  }

  @Test
  void onePoisonRowNeverStopsTheOthersInTheSameBatchFromPublishing() {
    OutboxEvent poison = row("evt-poison", "POL-POISON");
    OutboxEvent healthyA = row("evt-a", "POL-A");
    OutboxEvent healthyB = row("evt-b", "POL-B");
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
              if ("POL-POISON".equals(key)) {
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
    OutboxEvent event = row("evt-3", "POL3");
    // Pre-existing attempts already at the alert threshold (3, per PROPERTIES above) - proves
    // the row is still included in the batch (max-attempts=1000 is the only exclusion) and still
    // retried even past the point where logging switches from WARN to ERROR.
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

  private static OutboxEvent row(String id, String policyNum) {
    return OutboxEvent.builder()
        .id(id)
        .aggregateType("Policy")
        .aggregateId(policyNum)
        .eventType("PolicyCreated")
        .payload("{\"eventId\":\"" + id + "\"}")
        .build();
  }
}
