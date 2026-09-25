# ADR-0006: transactional outbox with a polling relay, not Debezium CDC

Status: Accepted (phase 7)

## Decision

`policy-service` and `claims-service` each get their own `outbox_event` table, written to in
the *same* local transaction as the business change it describes (`PolicyCreated`,
`PolicyRenewed`, `ClaimRegistered`, `ClaimStatusChanged`), and a `@Scheduled` relay (every
500 ms) that polls unpublished rows with `SELECT ... FOR UPDATE SKIP LOCKED`, publishes each to
Kafka via `KafkaTemplate`, and marks `published_at` once the broker acks. This is the standard
transactional-outbox pattern (service-design.md §5), chosen over publishing directly inside the
business transaction (not atomic — a crash between commit and send loses the event, and sending
before commit can publish a change that then rolls back).

**Debezium CDC (tailing the MySQL binlog instead of polling) was considered and explicitly
deferred, not built.** A log-tailing connector removes the polling relay's own overhead and
publish latency floor entirely, and is the natural next step if 500 ms latency or per-instance
polling load ever becomes a real constraint. It was not built now because it adds a new piece of
infrastructure (Debezium + Kafka Connect, or an MSK-Connect equivalent) this project doesn't yet
run anywhere else, for a latency requirement nothing in the spec currently demands. Revisit if
either changes.

**`FOR UPDATE SKIP LOCKED` is what makes multiple concurrent relay instances (phase 9's horizontal
scaling) safe without any extra coordination.** Two instances ticking at the same moment each run
the select inside their own transaction; `SKIP LOCKED` means the second instance's select simply
excludes whatever rows the first is already holding, rather than blocking on them (as plain
`FOR UPDATE` would) or racing to double-process them (as no locking at all would). No distributed
lock, no leader election, no partitioning of which instance owns which rows.

**Delivery is at-least-once, not exactly-once, and that's accepted deliberately.** A row's
`published_at` update can fail to commit after Kafka has already acked the send (a crash, or any
later failure in the same transaction) — that row gets picked up and resent on a later tick. This
is safe specifically because the event's `id` (also its envelope `eventId`) is generated once, at
append time, and never regenerated on retry: any consumer that keeps a `processed-events`-style
dedupe ledger (the same shape as this project's own `processed_request` table) can discard the
duplicate. The alternative — publish only after a guaranteed-committed state — cannot be made
atomic with the DB write at all, and a design that tries anyway risks the strictly worse failure
mode of silently losing an event.

**One Jackson `ObjectMapper` serializes an event exactly once - spring-kafka's own
`JsonSerializer` is never used.** Investigated and confirmed by disassembling the actually
resolved jars for this project: Boot 4.1.1 auto-configures `tools.jackson.databind.ObjectMapper`
(Jackson 3) as the one mapper every service's REST layer already uses; spring-kafka's
`JsonSerializer`/`JsonDeserializer` are hard-bound to the classic `com.fasterxml.jackson.databind.
ObjectMapper` (Jackson 2) instead — two unrelated classes, not source-compatible. Rather than
hand-build a second, Jackson-2 mapper and carry the ongoing burden of keeping its date/module
configuration in sync with the Jackson-3 one (with no compiler or test to catch drift), the event
envelope is serialized to a JSON string exactly once, at outbox-append time, by the existing
Jackson-3 mapper. That string is stored in `outbox_event.payload` verbatim and published to Kafka
verbatim (`KafkaTemplate<String, String>`, Kafka's own `StringSerializer` — not Jackson at all).
**If a future change reintroduces `JsonSerializer` "for convenience," it reintroduces the
two-mapper trap this ADR exists to avoid — don't.**

**The relay's transaction runs at `READ COMMITTED`, not the InnoDB default `REPEATABLE READ` —
found by a real IT failure, not by inspection.** A business-transaction `INSERT` into
`outbox_event` blocked for exactly MySQL's default `innodb_lock_wait_timeout` (50s), then failed
with a lock-wait-timeout error that the generic exception handler turned into a 500. Under
`REPEATABLE READ`, InnoDB's `SELECT ... FOR UPDATE` takes gap locks across the scanned index
range for phantom-read prevention - even when it matches zero rows, as the relay's poll usually
does on a lightly-loaded table - and those gap locks block concurrent inserts into the same
range from any other transaction, including `OutboxAppender`'s own append. This is a
well-documented MySQL gotcha for exactly this kind of `SKIP LOCKED` job-queue pattern; the fix is
`@Transactional(isolation = Isolation.READ_COMMITTED)` on the relay's own poll-and-publish
method, which takes plain record locks only, so it never blocks an insert into a part of the
table it never actually touched.

## Consequences

- A row is never automatically abandoned after N failures — `attempts` crossing
  `outbox.relay.alert-attempts` only changes the log level from WARN to ERROR (feeding a phase-9
  CloudWatch alarm); `outbox.relay.max-attempts` (much higher) is the only thing that excludes a
  row from the relay's own `SELECT`, as a rare safety valve for a payload that will genuinely
  never succeed, not a normal retry limit. Silently giving up on a real business event is judged
  worse than an ops team having to look at one.
- `outbox_pending` (`COUNT(*) WHERE published_at IS NULL`) is deliberately pull-based, computed
  at Prometheus scrape time against the same index the relay's own poll uses — it stays accurate
  even if the relay thread itself is wedged, which is exactly when the phase-9 alarm most needs
  to fire.
- `spring.task.scheduling.shutdown.await-termination=true` (a separate setting from
  `server.shutdown=graceful`, which only drains the HTTP request-handling pool, not `@Scheduled`
  tasks) lets an in-flight relay tick finish and commit cleanly during a phase-9 rolling deploy,
  reducing unnecessary rollback/retry churn - not required for correctness (the relay's own
  wrapping transaction already makes an abrupt cutoff data-safe), but worth it for ops hygiene.
- `traceparent` is stored on the outbox row and restored as a Kafka header when present, but
  nothing in this codebase sends one yet (no Micrometer Tracing dependency exists until phase 8) —
  the column and plumbing exist now specifically so phase 8 needs zero code changes here for
  traces to start continuing through the async hop.
