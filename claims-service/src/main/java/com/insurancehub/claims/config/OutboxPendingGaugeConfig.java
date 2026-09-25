package com.insurancehub.claims.config;

import com.insurancehub.claims.infrastructure.persistence.OutboxEventJpaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

// cross-cutting.md §2: outbox_pending gauge, alarmed on in phase 9. Deliberately pull-based
// (Micrometer invokes the count query at scrape time, not on a push from the relay itself) - it
// stays accurate even if the relay thread is wedged or the process is mid-restart, which is
// exactly the situation the alarm most needs to catch. Hits the same (published_at, created_at)
// index the relay's own poll query uses, so it's cheap even scraped every 15s. Registered as a
// constructor side effect (the standard Micrometer pattern for a gauge with no dedicated bean of
// its own to return) rather than shoehorned into a @Bean factory method's return type.
@Configuration
public class OutboxPendingGaugeConfig {

  public OutboxPendingGaugeConfig(MeterRegistry registry, OutboxEventJpaRepository outboxEvents) {
    registry.gauge(
        "outbox_pending", outboxEvents, OutboxEventJpaRepository::countByPublishedAtIsNull);
  }
}
