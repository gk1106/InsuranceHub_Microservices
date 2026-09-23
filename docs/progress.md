# Progress

Current phase: **2 — policy-service 01 create (done, awaiting review)**

| Phase | Goal | Status |
|---|---|---|
| 0 | Scaffold: parent POM, 4 modules, Spotless, JaCoCo, compose (MySQL/Kafka/Keycloak/LGTM), ADR-0001 | Done |
| 1 | hub-common: HubResponse, HubErrorCode, PiiMasker, correlation filter | Done |
| 2 | policy-service 01 create | Done |
| 3 | policy-service 02 renew + coverage lookup | Not started |
| 4 | claims-service 03 register + 04 status | Not started |
| 5 | hub-gateway: OAuth2, routing, mapping (crypto off in local) | Not started |
| 6 | hub-gateway crypto: JWS/JWE | Not started |
| 7 | Outbox + Kafka events | Not started |
| 8 | Hardening: resilience, structured logs, tracing, metrics, Dockerfiles | Not started |
| 9 | AWS: Terraform + centralized logging/monitoring + GitLab CI deploy | Not started |

## Phase 0 notes

- Parent POM pins Spring Boot 4.1.1 / Java 21; see `docs/adr/0001-tech-stack.md` for the
  version rationale and the spring-cloud-aws caveat to re-check before phase 9.
- Maven Wrapper (`./mvnw`) checked in so `mvn verify` doesn't require a pre-installed Maven —
  run `./mvnw verify` if the `mvn` command itself isn't on PATH.
- All 4 modules scaffolded with bootstrap `@SpringBootApplication` classes, a context-load
  smoke test (Testcontainers MySQL for policy-service/claims-service, not H2), and an ArchUnit
  layering test (`api -> application -> domain`, `infrastructure` not depended on) — currently
  vacuous since no domain code exists yet, but wired up from day one.
- `hub-common` has no source files yet on purpose — that's phase 1.
- JaCoCo's 80% line-coverage gate (`**/domain/**`, `**/application/**`) is enforced
  (`haltOnFailure=true`).
- `hub-gateway` has a placeholder `SecurityConfig` that only opens up
  `/actuator/health/**` unauthenticated (for the Docker healthcheck) and requires a JWT
  everywhere else; full OAuth2 rules land in phase 5. Local profile points its issuer-uri at
  the compose Keycloak (realm `insurancehub`, clients `insp001-client`/`insp002-client`).
- `scripts/send-sample.sh` is a stub that exits non-zero with a clear "not implemented until
  phase 6" message — there's nothing meaningful to send yet (no gateway routes, no crypto).
- `scripts/gen-dev-keys.sh` is real: generates EC (sign) + RSA (encrypt) dev keypairs per party
  into `.secrets/` (git-ignored) for `gateway` and a fictitious insurer `insp001`.
- **Validated end-to-end**: `./mvnw verify` is green across all 4 modules, and
  `docker compose up -d --build` brings up all 7 containers `(healthy)` — mysql, kafka,
  keycloak, otel-lgtm, policy-service, claims-service, hub-gateway — with each service's
  `/actuator/health/readiness` returning `{"status":"UP"}`. Ran with `docker compose down`
  afterwards to leave nothing running.
- Host port mappings were shifted from the "obvious" defaults because several were already
  taken by unrelated local processes on this machine (a native MySQL on 3306, WSL relaying
  Keycloak/Grafana/Kafka default ports from another project, Oracle's listener on 8080):
  mysql host port **3307**, keycloak **8190**, kafka **29093**, hub-gateway **8090**,
  otel-lgtm **3001/4327/4328**. Internal container-to-container ports are unchanged (services
  still talk to each other as `mysql:3306`, `kafka:9092`, `keycloak:8080`, etc.) — only the
  host-side mapping moved, so this is a non-issue on a clean machine but worth knowing if `make
  up`/`docker compose up` collides with something else in your own environment; adjust the
  `ports:` mappings in `docker-compose.yml` as needed.
- Kafka's healthcheck must target `kafka:9092`, not `localhost:9092` — its PLAINTEXT listener
  binds to the `kafka` hostname's interface specifically (per `KAFKA_LISTENERS`), not
  `0.0.0.0`, so a loopback-address healthcheck fails even though inter-container traffic on
  `kafka:9092` works fine.
- Testcontainers 2.x (the version Spring Boot 4.1.1's BOM manages) renamed its per-module
  artifacts with a `testcontainers-` prefix (`testcontainers-mysql`, `testcontainers-kafka`,
  `testcontainers-junit-jupiter`) — the old 1.x names (`mysql`, `kafka`, `junit-jupiter`) no
  longer resolve. Also needed an explicit `spring-boot-testcontainers` test dependency for
  `@ServiceConnection`, which isn't pulled in by `spring-boot-starter-test`.
- ArchUnit's `layeredArchitecture()` rule fails by default on an empty layer, so all three
  services' layering tests need `.withOptionalLayers(true)` for now — remove it once each
  service actually has classes in every layer, so an accidentally-empty layer starts failing
  the build again as a real signal.

## Phase 1 notes

- `hub-common` now has real code: `HubErrorCode` (the ~18-entry catalogue from
  `api-contract.md` §5, `respCode` an explicit stored field — see review note below),
  `HubBusinessException`, `HubResponse` (the external envelope, `@JsonInclude(NON_NULL)` so a
  pre-trust failure omits `txnId`/`reqId` entirely — matches `api-contract.md` §4's samples
  exactly), `HubHeaders`, `CorrelationFilter`, `CorrelationPropagationInterceptor`, `PiiMasker`,
  `Ulid`, and `HubCommonAutoConfiguration` (auto-registers `CorrelationFilter` via
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`). Full
  reasoning for every class is in the phase-1 plan (see git history / conversation) — not
  duplicated here.
- `hub-common` went from zero Spring dependencies to three: `spring-web`,
  `spring-boot-autoconfigure`, `spring-boot` — deliberate, not scope creep (needed for
  `HttpStatus`, `@AutoConfiguration`, and `FilterRegistrationBean` respectively).
- `HubBusinessException` isn't itemized by name in SKILL.md's phase-1 list — added because
  `cross-cutting.md` §1's error-handling contract requires it verbatim
  (`HubBusinessException(HubErrorCode code, String safeDetail)`), thrown identically by both
  domain services against the shared enum.
- **`CorrelationFilter`'s MDC keys deliberately stay narrower** than `cross-cutting.md`'s full
  list (`traceId, spanId, txnId, reqId, inspId, serviceType`): `traceId`/`spanId` are left to
  Micrometer's own MDC integration (phase 8, no tracing dependency here yet); `serviceType`
  isn't known until after the gateway decrypts the body (`service-design.md` §4 step 4), which
  runs *after* `CorrelationFilter` (step 1), so it has to be set later by gateway dispatch code.

### Phase 1 review round (2026-09-23)

- **`HubErrorCode.respCode()` is now a stored field**, not derived from `httpStatus()` — they
  match today (`HubErrorCodeTest.respCodeCurrentlyMatchesHttpStatus`, parameterized over all 19
  entries) but `docs/open-questions.md` Q1/Q2 may force them apart once the bank answers, and
  deriving one from the other would have made that impossible to express.
- **`CorrelationFilter` gained a `trustInboundHeaders` flag** (constructor arg, bound from
  `hub.correlation.trust-inbound-headers` via the new `CorrelationProperties`, defaulting to
  `false` - fail-safe): `false` for `hub-gateway` (the insurer's raw request is untrusted;
  reqId/inspId come from the decrypted body and the validated JWT, never from a header at this
  point) and `true` for policy-service/claims-service (the gateway is the only caller and always
  sets all three headers). `policy-service`/`claims-service`/`hub-gateway`'s `application.yml`
  now set this explicitly rather than relying on the default silently.
- **Every value entering MDC is sanitized first**: whitelist `[A-Za-z0-9._-]`, cap 64 chars,
  applied regardless of trust mode (cheap, and closes the gap even for values hub-common
  generates itself). `CorrelationFilterTest.sanitizesCrlfAndOtherDisallowedCharactersOutOfHeaderValues`
  proves a CRLF-log-injection attempt in a header value can't reach the log output.
  `mdcDoesNotLeakBetweenSequentialRequestsOnTheSameThread` proves the `finally` block actually
  prevents cross-request leakage on a reused worker thread, not just that MDC is empty
  immediately after one call returns.
- **`txnId` is now a real ULID**, not a `UUID.randomUUID()` placeholder — hand-rolled Crockford
  Base32 encoder (`Ulid`, no new dependency), still behind the same `Supplier<String>` seam.
  Decision, alternatives considered, and the **`CHAR(26)` column type phase 2's Flyway
  migrations should use** are recorded in `docs/adr/0002-txn-id-format.md`.
- **Real bug found and fixed**: adding the `CorrelationProperties` parameter to
  `correlationFilterRegistration()` broke `@ConditionalOnMissingBean`'s return-type deduction
  under Boot 4.1.1 (`BeanTypeDeductionException` → `ClassNotFoundException` for
  `HubCommonAutoConfiguration` itself, surfaced only when policy-service/claims-service loaded
  the full Spring context in their `@SpringBootTest`s - hub-common's own `ApplicationContextRunner`
  test didn't catch it). Fixed by specifying `@ConditionalOnMissingBean(name =
  "correlationFilterRegistration")` explicitly instead of relying on deduction.
- `HubCommonAutoConfiguration` confirmed to already use `@ConditionalOnWebApplication(SERVLET)`
  and `@ConditionalOnMissingBean` (present since the initial phase-1 implementation).
- 84 hub-common tests (was 56), all green. Full `./mvnw verify` reactor passes, including
  policy-service's and claims-service's full `@SpringBootTest` context loads against real
  Testcontainers MySQL - which is what caught the `@ConditionalOnMissingBean` bug above.

## Phase 2 notes — policy-service 01 create

- Flyway `V1__init.sql` (`policy`, `policy_term`, `processed_request` - no `outbox_event`,
  that's phase 7), entities (`Policy`/`PolicyTerm`/`ProcessedRequest`, Lombok `@Builder` +
  `@NoArgsConstructor(PROTECTED)`, no setters), `PolicyCreationService` (the 01 create use
  case), `PolicyController` + `CreatePolicyRequest`/`Response` + `PolicyExceptionHandler`.
  `txn_id`/`req_id` columns match ADR-0002/the plan's constraints exactly (`CHAR(26)`,
  `VARCHAR(64)`); PII columns (`cif`, `account_num`, `mobile_num`, `address`, `loan_acct_num`,
  `insured_name` - all six rule-4 fields, not just the four phase-8-encryption ones
  `service-design.md` names) are `VARCHAR(512)`.
- **Repository ports live in `application/`, not `infrastructure/`** — a deviation from the
  original plan text (which showed `Policy.newFrom(cmd)` and referenced
  `infrastructure.persistence.PolicyRepository` directly). Discovered while implementing:
  `PolicyCreationService` (application) depending on a `PolicyRepository` interface *defined in*
  `infrastructure` violates the ArchUnit rule "infrastructure may not be accessed by any layer."
  Fixed with a proper ports-and-adapters split: `PolicyRepository`/`PolicyTermRepository`/
  `ProcessedRequestRepository` are plain interfaces in `application/` (only the methods actually
  called - not full CRUD); `infrastructure/persistence/PolicyJpaRepository` etc. extend both
  `JpaRepository` and the matching port, so Spring Data implements the port at runtime with zero
  compile-time dependency from application back to infrastructure. Also switched entity
  construction from a `newFrom(cmd)`-style factory (which would have required domain to import
  the application-layer command type - the same violation, one layer down) to Lombok
  `@Builder`, called from `application/` with unpacked primitive values.
- `CreatePolicyCommandMapper` (MapStruct, in `api/`) maps `CreatePolicyRequest` + the three
  header strings to `CreatePolicyCommand` - lives in `api/`, not `application/`, for the same
  layering reason (`CreatePolicyCommand` must not import `CreatePolicyRequest`).
- Idempotency retry (`PolicyCreationService.create`) uses `TransactionTemplate` (built from an
  injected `PlatformTransactionManager`), not `@Transactional` on a private method, since
  self-invocation on `this` bypasses Spring's proxy.
- **Real concurrency bug found by the required race test, not by inspection**: the first
  implementation only recognized "lost the idempotency race" by checking that the caught
  `DataIntegrityViolationException` wrapped a `uk_processed_request` constraint violation by
  name. The race test failed because two threads on the *same* `(inspId, reqId)` and
  `policyNum` can just as easily collide on `uk_policy_policy_num` first (the `policy` insert
  happens before `processed_request` in the transaction) - depends purely on timing, not
  something worth hardcoding a specific constraint name for. Fixed by dropping the constraint-
  name check entirely: on *any* `DataIntegrityViolationException`, re-look-up
  `processed_request` by `(inspId, reqId)` - if a row now exists, that's the real proof someone
  else won this exact request, regardless of which constraint fired; if not, rethrow. Simpler
  and more correct than the original design. Ran the race test 3 extra times standalone
  afterward - not flaky.
- Three more Boot 4.1.1 modularization gaps hit (same pattern as phase 0/1's testcontainers/
  `@ConditionalOnMissingBean` findings - each is "the feature still exists, but the class moved
  to a new artifact with zero error until you need it"):
  - `FlywayAutoConfiguration` moved out of `spring-boot-autoconfigure` into
    `org.springframework.boot:spring-boot-flyway`. Without it, Flyway doesn't run - no error,
    no log line, nothing; `ddl-auto=validate` just fails with "missing table" once you actually
    have entities. Added to **both** policy-service and claims-service now (claims-service
    doesn't need it yet, but will the moment phase 4 adds a migration, and this exact silent
    failure mode is worth not re-discovering twice).
  - `TestRestTemplate` moved out of `spring-boot-test` into
    `org.springframework.boot:spring-boot-resttestclient` (new package
    `org.springframework.boot.resttestclient`), and needs `@AutoConfigureTestRestTemplate`
    explicitly - `@SpringBootTest` no longer wires it automatically. (Boot's own newer
    recommendation is `RestTestClient`; stuck with `TestRestTemplate` here since it's still
    fully supported in 4.1.1 and the `ResponseEntity`-based test code was already written -
    revisit if a future phase's IT would benefit from `RestTestClient`'s fluent API.)
  - That auto-config in turn needs `RestTemplateBuilder`, which lives in yet another new
    artifact, `org.springframework.boot:spring-boot-restclient` - also added.
- Integration test class was temporarily named `PolicyCreationIntegrationTest` mid-phase
  (Failsafe wasn't configured yet - see the review round below, which fixes this and renames
  it back to `PolicyCreationIT`).
- ArchUnit's `LayeredArchitectureTest` now uses
  `withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)` - without it, the rule
  scans test classes too, and an IT that autowires a JPA repository for row-count assertions
  (legitimate - tests aren't bound by the same layering purity as production code) trips
  "infrastructure may not be accessed by any layer." `.withOptionalLayers(true)` (the phase-0
  placeholder for empty layers) is now removed, as planned - every layer has real code.

### Phase 2 review round 2 (2026-09-23)

- **Two more ArchUnit rules**: `domainAndApplicationDoNotDependOnSpringData` (no
  `org.springframework.data..` imports in either layer - Spring Data types belong only to
  `infrastructure` adapters) and `domainDoesNotDependOnApplication` (the original layered-
  architecture rule only constrains who may access `domain`, not what `domain` itself depends
  on - this closes that gap explicitly). **Verified both actually have teeth**: temporarily
  made `PolicyRepository` extend `CrudRepository` and confirmed the first rule failed;
  temporarily made `Policy` reference `CreatePolicyCommand` and confirmed the second rule
  failed; reverted both immediately after confirming.
- `docs/adr/0003-ports-and-adapters.md` records the ports-and-adapters split (repository
  interfaces in `application/`, Spring Data adapters in `infrastructure/`) and the accepted
  trade-off that domain entities carry JPA annotations directly - sanctioned by
  `testing-and-deploy.md`'s own ArchUnit rule text ("no JPA-infrastructure imports **beyond
  annotations**").
- **The idempotency recovery lookup now explicitly runs in its own
  `PROPAGATION_REQUIRES_NEW` transaction** (`PolicyCreationService.recoverFromRace`, via a
  second `TransactionTemplate` field), rather than relying on Spring Data's implicit
  per-query-method transaction. By the time `create()`'s catch block runs, the original
  transaction has already rolled back and completed, so REQUIRES_NEW behaves identically to
  the implicit default here - the point is making that fact unambiguous in code, not changing
  behavior.
- **Sixth test scenario added, at both levels**: two threads, same `policyNum`, *different*
  `reqId`s - the loser must get `HubBusinessException(POLICY_ALREADY_EXISTS)` (409), never an
  uncaught exception. This is exactly the scenario `recoverFromRace` exists to handle (case 2
  of its three-way branch) - `PolicyCreationServiceTest.losingToADifferentReqIdOnTheSamePolicyNumReturnsPolicyAlreadyExists`
  (unit, mocked) and `PolicyCreationIT.concurrentDifferentReqIdsOnTheSamePolicyNumTheLoserGetsPolicyAlreadyExists`
  (integration, real MySQL + real concurrent threads).
- **Failsafe now configured** (root `pom.xml`, active for every module): `*IT.java` runs under
  `maven-failsafe-plugin`, bound to `integration-test`+`verify` by Failsafe's own default
  convention (no explicit `<executions>` needed - matches how Surefire's `test`-phase binding
  already worked without one). Surefire now explicitly excludes `**/*IT.java` too (belt and
  suspenders - already true by Surefire's own default include patterns, but relying on that
  silently is exactly how an `*IT` class went unrun earlier this phase). `PolicyCreationIT`
  renamed back from `PolicyCreationIntegrationTest` now that it actually runs. **Verified**:
  `mvn test` (full reactor) mentions `PolicyCreationIT` zero times in its output; `mvn verify`
  runs and passes all 6 of its scenarios. Removed `hub-common/pom.xml`'s now-redundant explicit
  `maven-surefire-plugin` declaration (phase 0 leftover) since Surefire/Failsafe are active for
  every module via the root pom now.
- 17 policy-service tests total (6 IT + 7 unit + 3 ArchUnit + 1 context-load smoke), all
  green. Full `./mvnw verify` reactor passes.
