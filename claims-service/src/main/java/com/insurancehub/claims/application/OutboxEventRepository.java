package com.insurancehub.claims.application;

import com.insurancehub.claims.domain.OutboxEvent;

// Port: infrastructure provides the Spring Data JPA adapter (OutboxEventJpaRepository), which
// also owns the relay's own FOR UPDATE SKIP LOCKED poll query and the outbox_pending gauge's
// count query - neither of those belongs on this port, since only OutboxAppender needs save().
public interface OutboxEventRepository {

  OutboxEvent save(OutboxEvent event);
}
