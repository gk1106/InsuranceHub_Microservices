package com.insurancehub.claims.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// cross-cutting.md §3: typed @ConfigurationProperties, no @Value scattered across the code.
// alertAttempts is where a stuck row starts logging at ERROR (still retried, never abandoned);
// maxAttempts is the much-higher safety valve that finally excludes a row from the relay's own
// SELECT (docs/adr/0006-outbox-relay.md explains why these are two different numbers).
@ConfigurationProperties(prefix = "outbox.relay")
public record OutboxRelayProperties(
    int batchSize, Duration sendTimeout, int alertAttempts, int maxAttempts, String topic) {}
