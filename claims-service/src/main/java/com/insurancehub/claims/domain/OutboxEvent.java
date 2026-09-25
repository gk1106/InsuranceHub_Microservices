package com.insurancehub.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// id is the eventId itself (set by OutboxAppender, not @GeneratedValue) - one UUID serves as
// both the row's PK and the envelope's own eventId, generated once and never regenerated on
// retry, which is what makes at-least-once delivery + consumer dedupe-on-eventId correct
// (docs/adr/0006-outbox-relay.md). payload is a String, not a JSON-mapped object: it's already
// a complete, serialized JSON string by the time OutboxAppender builds this (one Jackson
// ObjectMapper, one serialization - see the ADR for why spring-kafka's JsonSerializer, bound to
// a different Jackson major version, is never used here). @JdbcTypeCode(SqlTypes.JSON) on a
// String field tells Hibernate to bind/read it via MySQL's JSON JDBC handling without attempting
// any (de)serialization of its own, which is what lets ddl-auto=validate accept a String
// property against a JSON column.
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class OutboxEvent {

  @Id
  @Column(name = "id", nullable = false, length = 36)
  private String id;

  @Column(name = "aggregate_type", nullable = false, length = 30)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false, length = 50)
  private String aggregateId;

  @Column(name = "event_type", nullable = false, length = 50)
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false)
  private String payload;

  @Column(name = "traceparent", length = 55)
  private String traceparent;

  @Builder.Default
  @Column(name = "attempts", nullable = false)
  private int attempts = 0;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  public void markPublished() {
    this.publishedAt = Instant.now();
  }

  public void incrementAttempts() {
    this.attempts++;
  }
}
