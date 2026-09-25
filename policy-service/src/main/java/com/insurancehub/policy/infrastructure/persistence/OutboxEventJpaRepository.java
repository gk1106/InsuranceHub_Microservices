package com.insurancehub.policy.infrastructure.persistence;

import com.insurancehub.policy.application.OutboxEventRepository;
import com.insurancehub.policy.domain.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Adapter: OutboxEventRepository's own save() is Spring Data's usual query derivation. The two
// extra methods here (the relay's own poll and the outbox_pending gauge's count) are NOT on the
// port - only infrastructure/messaging/OutboxRelay and config/OutboxPendingGaugeConfig need
// them, and adding them to the port would leak relay/gauge concerns into application/.
public interface OutboxEventJpaRepository
    extends JpaRepository<OutboxEvent, String>, OutboxEventRepository {

  // Spring Data's derived-query/@Lock support has no vocabulary for SKIP LOCKED, so this is raw
  // SQL. attempts < :maxAttempts is the only thing that ever excludes a row from retry - see
  // docs/adr/0006-outbox-relay.md for why that's a much higher, rarely-hit safety valve, not a
  // normal retry limit.
  @Query(
      value =
          "SELECT * FROM outbox_event "
              + "WHERE published_at IS NULL AND attempts < :maxAttempts "
              + "ORDER BY created_at "
              + "LIMIT :batchSize "
              + "FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<OutboxEvent> findBatchForUpdate(
      @Param("batchSize") int batchSize, @Param("maxAttempts") int maxAttempts);

  long countByPublishedAtIsNull();
}
