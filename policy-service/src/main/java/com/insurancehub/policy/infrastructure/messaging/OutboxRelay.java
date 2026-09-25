package com.insurancehub.policy.infrastructure.messaging;

import com.insurancehub.policy.config.OutboxRelayProperties;
import com.insurancehub.policy.domain.OutboxEvent;
import com.insurancehub.policy.infrastructure.persistence.OutboxEventJpaRepository;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// @Scheduled and @Transactional on the SAME method is safe here (unlike the self-invocation
// trap PolicyCreationService's own create()/doCreate() split exists specifically to avoid):
// Spring's scheduler invokes the method through the managed bean reference, which already IS
// the transactional proxy - there's no same-class `this` call bypassing it.
//
// The whole batch runs in ONE transaction so FOR UPDATE SKIP LOCKED's row locks are held for the
// full batch (what makes concurrent relay instances safe - phase 9), but each row's own
// send-and-mark is wrapped in its own try/catch so one failing row's exception never propagates
// out and rolls back the rows around it (docs/adr/0006-outbox-relay.md). No explicit save() per
// row: these are managed entities fetched inside this same transaction, so Hibernate's own dirty
// checking flushes markPublished()/incrementAttempts() on commit.
//
// isolation = READ_COMMITTED is required, not cosmetic: found by a real IT failure (a business
// transaction's INSERT into outbox_event blocked for exactly MySQL's default 50s
// innodb_lock_wait_timeout, then failed). Under the default REPEATABLE READ, InnoDB's
// FOR UPDATE takes gap locks on the scanned index range even when zero rows match (phantom-read
// prevention), which blocks concurrent inserts from OutboxAppender into that same range -
// exactly the standard, well-documented MySQL SKIP-LOCKED-queue gotcha. READ COMMITTED takes
// plain record locks only, so an append into a fresh part of the table is never blocked by an
// empty or in-progress relay poll.
@Component
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxEventJpaRepository outboxEvents;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final OutboxRelayProperties properties;

  public OutboxRelay(
      OutboxEventJpaRepository outboxEvents,
      KafkaTemplate<String, String> kafkaTemplate,
      OutboxRelayProperties properties) {
    this.outboxEvents = outboxEvents;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties;
  }

  @Scheduled(fixedDelay = 500)
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void relay() {
    List<OutboxEvent> batch =
        outboxEvents.findBatchForUpdate(properties.batchSize(), properties.maxAttempts());
    for (OutboxEvent event : batch) {
      publishOne(event);
    }
  }

  private void publishOne(OutboxEvent event) {
    try {
      var messageBuilder =
          MessageBuilder.withPayload(event.getPayload())
              .setHeader(KafkaHeaders.TOPIC, properties.topic())
              .setHeader(KafkaHeaders.KEY, event.getAggregateId());
      if (event.getTraceparent() != null) {
        messageBuilder.setHeader("traceparent", event.getTraceparent());
      }
      kafkaTemplate
          .send(messageBuilder.build())
          .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
      event.markPublished();
    } catch (Exception e) {
      event.incrementAttempts();
      // Never log the payload - it's business data, only the row's own identifiers.
      if (event.getAttempts() >= properties.alertAttempts()) {
        log.error(
            "outbox relay: repeated publish failure id={} aggregateType={} aggregateId={} "
                + "attempts={}",
            event.getId(),
            event.getAggregateType(),
            event.getAggregateId(),
            event.getAttempts(),
            e);
      } else {
        log.warn(
            "outbox relay: publish failed id={} aggregateId={} attempts={}: {}",
            event.getId(),
            event.getAggregateId(),
            event.getAttempts(),
            e.getMessage());
      }
    }
  }
}
