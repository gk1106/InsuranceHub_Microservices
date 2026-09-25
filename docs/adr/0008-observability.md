# ADR-0008: Micrometer Tracing + OTel bridge, OTLP to `otel-lgtm` locally, logstash JSON logs

Status: Accepted (phase 8)

## Decision

Tracing: Micrometer Tracing with the OpenTelemetry bridge (`micrometer-tracing-bridge-otel`) and
the OTLP exporter (`opentelemetry-exporter-otlp`), exactly as `cross-cutting.md` §2 specifies.
No explicit version is pinned in the parent POM — `spring-boot-dependencies` (inherited via
`spring-boot-starter-parent`) already imports `micrometer-tracing-bom`/`opentelemetry-bom`, and
`dependency:tree` confirms both resolve cleanly through it (`micrometer-tracing-bridge-otel
1.7.1`, `opentelemetry-exporter-otlp 1.62.0`, at time of writing) — pinning a version here would
fight the BOM, not use it. Sampling is `management.tracing.sampling.probability: 1.0` in the
base `application.yml` of all three services (`cross-cutting.md`'s "100% locally"); a 10% prod
value is deferred to phase 9, which is when a real `prod` profile file first exists at all —
recorded in `docs/open-questions.md` so it isn't silently forgotten. Locally, all three services
export via OTLP HTTP to the compose `otel-lgtm` container (`http://otel-lgtm:4318/v1/traces`,
`application-local.yml` only) — Grafana's own Tempo. In AWS (phase 9), ADOT → X-Ray takes over,
per `logging-and-monitoring.md` §8.

Logging: a composable `json-logs` Spring profile (`application-json-logs.yml`, one per service)
sets `logging.structured.format.console=logstash`, activated alongside `local`
(`SPRING_PROFILES_ACTIVE=local,json-logs`) to verify the exact prod JSON shape without losing
`local`'s own human-readable default for everyday dev — exactly `logging-and-monitoring.md` §10's
own suggested pattern, not invented here. `HubHeaders` gained an `MDC_SERVICE_TYPE` key,
populated by `HubDispatcher` once the service code resolves (post-decryption, matching phase 1's
own reasoning for why it can't live in `CorrelationFilter`) and cleared by `RequestAuditFilter`'s
`finally` block, which already wraps the whole pipeline. `traceId`/`spanId` need no equivalent
hand-written MDC code — Boot's own structured-logging integration adds them automatically once a
`Tracer` bean exists, confirmed by capturing a real JSON log line rather than assumed.

## Two real, independent bugs found before tracing actually worked

**1. Boot 4.1.1 split tracing autoconfiguration into its own artifact — the dependency list
above (`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`) is necessary but not
sufficient.** A first pass with just those two showed zero test failures on the surface *and* a
context that started cleanly - the gap was only caught because
`HubGatewayDispatchIT.policyServiceCallCarriesAnInternalAuthHeaderAndATraceparentHeader` (added
specifically to verify this end-to-end) asserted a real WireMock-captured `traceparent` header
and got `null`. Root-caused by enabling `logging.level.org.springframework.boot.autoconfigure
=DEBUG` for one test run and reading the real condition-evaluation report: `ObservationRegistry`,
`RestClientObservationAutoConfiguration` and `WebMvcObservationAutoConfiguration` all matched
(so Observations were being created and Micrometer counters/timers worked fine), but **not a
single autoconfiguration class mentioning "Tracing" appeared anywhere in the report** - positive
or negative. `spring-boot-dependencies-4.1.1.pom` confirms why: Boot 4.1.1 moved the actual
`TracingAutoConfiguration`/`OtlpAutoConfiguration`/`Tracer`-bean wiring out of
`spring-boot-actuator-autoconfigure` into three new artifacts it separately manages -
`spring-boot-micrometer-tracing` (base), `spring-boot-micrometer-tracing-brave`, and
`spring-boot-micrometer-tracing-opentelemetry` - mirroring the same split already done for
metrics (`spring-boot-micrometer-metrics`) and observation (`spring-boot-micrometer-observation`)
generally, and the exact same pattern this project has hit repeatedly for Kafka/Flyway/HTTP-client
(a feature's classes exist, but its autoconfiguration moved to a new artifact with zero error
until you actually need it). Fixed by adding
`org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry` (no explicit version -
BOM-managed, pulls the base `spring-boot-micrometer-tracing` transitively) to all three services.
Re-running the same debug condition report afterward showed real tracing autoconfiguration
classes matching, and the WireMock-captured `traceparent` header assertion passed.

**2. Manually-built `RestClient`s were invisible to tracing, independent of bug 1.**
hub-gateway's `PolicyServiceClient`/`ClaimsServiceClient` and claims-service's own coverage
client are all built from a hand-constructed `RestClient` (deliberate, since phase 4a/5: each
needs its own explicit connect/read timeout, cross-cutting.md §6). All three originally called
the static `RestClient.builder()` factory directly, not the Boot-managed `RestClient.Builder`
*bean* — and Micrometer's client-side observation instrumentation (the thing that actually
injects a `traceparent` header into an outbound call) is applied by `RestClientAutoConfiguration`
to that bean specifically, via `RestClientObservationConvention`. A client built from the static
factory silently gets none of it: no compile error, no runtime error, just an outbound call with
no `traceparent` header ever - this would have been a real bug even after fixing (1) above.
Fixed by injecting `RestClient.Builder` into each `@Bean` factory method and building from it
(`builder.baseUrl(...).requestFactory(...).requestInterceptor(...).build()`) instead of
`RestClient.builder()...`.

Both fixes were necessary together - fixing only one still leaves `traceparent` null. Neither was
caught by a compile error or a context-load failure; only a test that actually inspects the
outbound request's headers caught either one. This is exactly the kind of "confirm by inspection,
not assumption" discipline this project has needed for every prior Boot 4 surprise.

## Consequence: phase 7's `traceparent` plumbing now does something

`docs/open-questions.md` Q15 recorded phase 7's `outbox_event.traceparent` column and the
relay's restore-as-Kafka-header logic as inert plumbing, built ahead of tracing existing, with an
explicit promise: "needs zero code changes once phase 8 adds real tracing." That promise held —
`OutboxAppender`/`OutboxRelay` are unchanged in this phase. The gateway's outbound call now
carries a real `traceparent` (per the fix above); the domain services' existing
`@RequestHeader(value = "traceparent", required = false)` controller parameters, already
threaded through to `OutboxAppender.append(...)` since phase 7, simply start receiving a real
value instead of always `null`. Q15 is closed.

## A real bug found while wiring `@EnableConfigurationProperties` for two classes

Recorded in `docs/adr/0007-internal-service-auth.md` (found while wiring `InternalAuthProperties`
alongside each client's own connection-properties class) rather than duplicated here — it isn't
tracing-specific, but it was found in the same implementation pass.
