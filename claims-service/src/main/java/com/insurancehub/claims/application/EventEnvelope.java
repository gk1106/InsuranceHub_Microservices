package com.insurancehub.claims.application;

import java.time.Instant;

// service-design.md §5's envelope shape, exactly. Duplicated in policy-service rather than
// shared via hub-common (SKILL.md rule 2: hub-common stays tiny, no domain-shaped types even
// when the shape happens to match).
public record EventEnvelope<T>(
    String eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String aggregateId,
    String txnId,
    String reqId,
    String inspId,
    T data) {}
