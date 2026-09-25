package com.insurancehub.claims.application;

import com.insurancehub.claims.domain.OutboxEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

// Not a port/adapter - nothing needs to swap this implementation, unlike ClaimRepository.
// Called once per business write, inside the same @Transactional method as the write itself
// (ClaimRegistrationService.doRegister/ClaimStatusUpdateService.doUpdateStatus), after
// processedRequests.save and before the method returns - so the outbox row lands in the exact
// same local transaction as the business row it describes (docs/adr/0006-outbox-relay.md).
// Every call site's own idempotency check already runs before this is ever reached, so a
// replayed request can never cause a duplicate append.
//
// objectMapper here is the one Boot-autoconfigured Jackson 3 ObjectMapper (tools.jackson.
// databind) every REST endpoint in this service already uses - deliberately the only
// serialization step an event's JSON ever goes through. See the ADR for why spring-kafka's own
// JsonSerializer, bound to a different Jackson major version, is never used for the outbox/Kafka
// path at all.
@Service
public class OutboxAppender {

  private final OutboxEventRepository outboxEvents;
  private final ObjectMapper objectMapper;

  public OutboxAppender(OutboxEventRepository outboxEvents, ObjectMapper objectMapper) {
    this.outboxEvents = outboxEvents;
    this.objectMapper = objectMapper;
  }

  public <T> void append(
      String aggregateType,
      String aggregateId,
      String eventType,
      T data,
      String txnId,
      String reqId,
      String inspId,
      String traceparent) {
    String eventId = UUID.randomUUID().toString();
    var envelope =
        new EventEnvelope<>(
            eventId, eventType, 1, Instant.now(), aggregateId, txnId, reqId, inspId, data);
    String payload = objectMapper.writeValueAsString(envelope);

    outboxEvents.save(
        OutboxEvent.builder()
            .id(eventId)
            .aggregateType(aggregateType)
            .aggregateId(aggregateId)
            .eventType(eventType)
            .payload(payload)
            .traceparent(traceparent)
            .build());
  }
}
