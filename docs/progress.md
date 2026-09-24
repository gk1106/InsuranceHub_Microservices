# Progress

Current phase: **6 — hub-gateway crypto done, awaiting review before phase 7**

| Phase | Goal | Status |
|---|---|---|
| 0 | Scaffold: parent POM, 4 modules, Spotless, JaCoCo, compose (MySQL/Kafka/Keycloak/LGTM), ADR-0001 | Done |
| 1 | hub-common: HubResponse, HubErrorCode, PiiMasker, correlation filter | Done |
| 2 | policy-service 01 create | Done |
| 3 | policy-service 02 renew + coverage lookup | Done |
| 4 | claims-service 03 register + 04 status | Done |
| 5 | hub-gateway: OAuth2, routing, mapping (crypto off in local) | Done (2 commits) |
| 6 | hub-gateway crypto: JWS/JWE | Done |
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

## Phase 3 notes — policy-service 02 renew + coverage lookup

- No schema change - `V1__init.sql`'s three tables already support both endpoints (a renewal
  is just another `policy_term` row; coverage is a date-range read over those rows).
- **Ports promoted from test-only to real production methods**: `PolicyRepository` gained
  `findByPolicyNum` (was only on the JPA adapter, used by tests) and `PolicyTermRepository`
  gained `findByPolicyId` (new) - both renewal (term history/overlap checks) and coverage
  (date lookup) need them. `PolicyJpaRepository` no longer declares anything of its own; Spring
  Data implements the whole port via query derivation.
- **`Policy` gained one mutator**, `updateInsuredDetails(...)` - not a blanket setter, an
  intention-revealing method for the one legitimate post-construction mutation (`docs/open-
  questions.md` Q8: renewals may change insured details). JPA dirty-checking plus `@Version`
  handles the `UPDATE` on commit.
- **`PolicyRenewalService`/`PolicyRenewalCommand`/`PolicyRenewalResult` mirror
  `PolicyCreationService`'s shape** (two `TransactionTemplate`s, same
  `DataIntegrityViolationException` recovery pattern) rather than reusing the create-side
  types, even though the field lists coincide today - naming the actual use case, not coupling
  renewal's payload shape to create's. `RenewPolicyResult.termNo` is correct even on replay:
  the renewal's `processed_request.resource_key` stores the new term number (a string) instead
  of the policyNum, since policyNum is already known from the path for this endpoint.
- **Renewal rules** (`service-design.md` §2): `term_no = max(existing) + 1`; new term's
  `startDate` must be after the latest term's `startDate`; new term must not overlap *any*
  existing term (inclusive boundaries) - both violations map to `RENEWAL_NOT_ALLOWED` (422).
  Gaps between terms are explicitly allowed (`docs/open-questions.md` Q9 - lapse is derived
  from the absence of a covering term at query time, not a stored status).
- **`PolicyCoverageService`** is a plain `@Transactional(readOnly = true)` method (no
  `TransactionTemplate` needed - it's called externally by the controller, not via internal
  self-invocation, so the proxy applies normally). Returns `active=false` with null term fields
  when no term covers `onDate`, but `insuranceType` always comes from the `policy` row so it's
  populated either way.
- **New `CONCURRENT_UPDATE` (409) `HubErrorCode`**, plus a
  `PolicyExceptionHandler.handleConcurrentUpdate` mapping
  `ObjectOptimisticLockingFailureException`: renewal is the first code path to mutate an
  existing `@Version`-tracked row, so this became reachable where in phase 2 it genuinely
  wasn't. `cross-cutting.md` §1 already specifies this mapping; closing the gap now also
  benefits claims-service's phase-4 status updates.
- **Real Boot 4/Spring 7 bug found by the overlap-rejection IT, not by inspection**:
  `HttpStatus.UNPROCESSABLE_ENTITY` is deprecated in Spring 7 in favor of
  `HttpStatus.UNPROCESSABLE_CONTENT` (RFC 9110 renamed 422's reason phrase) - and they're kept
  as two *distinct* enum constants, both code 422. `HttpStatus.valueOf(422)` (used when a test
  client resolves the status from a raw HTTP response) resolves to `UNPROCESSABLE_CONTENT`, so
  a test asserting `isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)` fails even though the numeric
  code matches. This had been latent since phase 2 (`POLICY_NOT_ACTIVE`,
  `INVALID_STATUS_TRANSITION`, `RENEWAL_NOT_ALLOWED` all used the deprecated constant) - no IT
  had asserted an exact 422 status object until `PolicyRenewalIT.overlappingTermIsRejected`.
  Fixed at the source in `HubErrorCode` (all three 422 entries now use
  `UNPROCESSABLE_CONTENT`); the numeric wire status (422) was never wrong, only the client-side
  Java object identity.
- **Environment note, not a code issue**: `mvn clean` intermittently fails on this machine with
  "file being used by another process" on `policy-service/target/classes`, and one `clean` that
  raced past that lock produced a build with corrupted (ECJ-style "Unresolved compilation
  problems") stub `.class` files, itself a downstream symptom of the same lock contention
  (something else - most likely an IDE's background compiler - writes into the same `target/`
  directory). A plain `./mvnw verify` (no `clean`) was consistently green across three separate
  runs.
### Phase 3 review round (2026-09-23)

- **`docs/open-questions.md` Q10**: `CONCURRENT_UPDATE` (409) is a code this project invented,
  not one in the bank's `api-contract.md` §5 catalogue - flagged as an assumption exactly like
  `RENEWAL_NOT_ALLOWED` was, with an explicit note that **any future project-defined error code
  needs the same treatment** (a numbered open question before it ships, not just an enum entry).
- **Coverage boundary tests, both ends inclusive**: `onDate == termStart` and
  `onDate == termExpiry` are both active; one day either side of the term is not. Added at both
  levels - `PolicyCoverageServiceTest.termBoundariesAreInclusiveOnBothEnds` (unit) and
  `PolicyCoverageIT.termBoundariesAreInclusiveOnBothEndsOverTheWire` (real HTTP round trip) -
  since a claim filed exactly on the expiry date is an ordinary date of loss phase 4 will send,
  and an off-by-one here would silently reject it. The implementation
  (`!onDate.isBefore(start) && !onDate.isAfter(expiry)`) was already correct; these tests just
  make that explicit and pin it against regression.
- **Coverage response shape is now contract-tested**: `PolicyCoverageIT.
  coverageResponseContainsExactlyTheDocumentedFields` parses the raw JSON body and asserts its
  key set equals exactly `{policyNum, active, termStart, termExpiry, sumInsured,
  insuranceType}` - `service-design.md` §2's point that claims doesn't get the insured's name,
  mobile, CIF or account number, enforced as a test rather than left as a convention someone
  could quietly break later.
- **Lapse behavior now named explicitly by a test**: renamed
  `PolicyRenewalServiceTest.allowsARenewalAfterAGapAndAssignsTheNextTermNo` to
  `allowsARenewalArrivingOverAYearAfterThePolicyLapsed`, with a comment cross-referencing both
  Q9 and `PolicyCoverageServiceTest.returnsInactiveCoverageForADateInAGapBetweenTerms` - so the
  "a renewal is accepted no matter how long the gap" behavior and the "the gap reads back as
  `active=false`, never the nearest term's stale values" behavior are each pinned by name, not
  just implied by a general "allow a gap" test.
- **Real Boot 4/Jackson 3 finding, again surfaced by writing the contract test rather than by
  inspection**: `@Autowired ObjectMapper` failed with "No qualifying bean" using
  `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2). Boot 4.1.1's
  `spring-boot-starter-jackson` defaults to **Jackson 3** (`tools.jackson.core:jackson-databind`,
  package `tools.jackson.databind`) - `com.fasterxml.jackson-databind` 2.21.5 is still on the
  classpath transitively (YAML config parsing, some test deps) but Spring no longer registers
  it as the primary `ObjectMapper` bean. Fixed by importing `tools.jackson.databind.ObjectMapper`
  instead; API shape is source-compatible so no other code change was needed. Same modularization
  pattern as every other Boot 4 surprise this project has hit - the class still exists somewhere,
  just not the one you'd reflexively import.
- 40 policy-service tests total (16 IT across `PolicyCreationIT`/`PolicyRenewalIT`/
  `PolicyCoverageIT` + 20 unit across `PolicyCreationServiceTest`/`PolicyRenewalServiceTest`/
  `PolicyCoverageServiceTest`/`PolicyExceptionHandlerTest` + 3 ArchUnit + 1 context-load
  smoke), all green. `hub-common`'s `HubErrorCodeTest` now covers 20 entries (was 19).
  Domain+application JaCoCo line coverage: 95.1%. Full `./mvnw verify` reactor passes across
  all 4 modules.

## Phase 4a notes — claims-service 03 register (04 status update deferred to 4b)

- `Claim`/`ClaimStatusHistory`/`ProcessedRequest`, the ports/adapters split, and
  `ClaimRegistrationService`'s `TransactionTemplate`/recovery pattern are deliberate near-
  duplicates of policy-service's phase 2/3 code, not shared via `hub-common` - recorded as a
  new section in `docs/adr/0003-ports-and-adapters.md` (SKILL.md rule 2: shared domain code
  couples deployments).
- **First inter-service HTTP call in the system**: `claims-service` → `policy-service`'s
  coverage endpoint, via an `@HttpExchange` interface (`PolicyServiceHttpApi`) built on a
  manually-constructed `RestClient` (explicit connect/read timeouts from config, not Boot's
  autoconfigured default) and `HttpServiceProxyFactory`. `@CircuitBreaker`/`@Retry` live
  directly on the `@HttpExchange` interface's method - Spring AOP matches annotations declared
  on the interfaces a bean's proxy implements, so this works without any wrapping method;
  confirmed by `ClaimRegistrationResilienceIT`, not just asserted. A separate, plain
  `PolicyCoverageClient` (implements the `application.PolicyCoverageGateway` port) does the
  exception translation via a real try/catch at a real bean-to-bean call site - no
  `fallbackMethod`, since Resilience4j's fallback-on-a-dynamic-proxy resolution is a known
  fragility source and a plain catch is simpler to verify.
- **Retry/circuit-breaker exception lists are allow-lists**
  (`{HttpServerErrorException.ServiceUnavailable, ResourceAccessException}`), not the Resilience4j
  default of "every exception counts." A 404 matches neither list, so it's retried zero times
  and never counts toward the circuit breaker's failure rate - "never retry on 4xx"
  (cross-cutting.md §6) and "a burst of legitimate 404s doesn't trip the breaker" both fall out
  of the same config, not a special case in code. `ClaimRegistrationResilienceIT` proves both:
  `a404IsNeverRetried` (WireMock sees exactly one request) and the circuit-open test (built
  entirely on 503s, never 404s).
- **Order of operations matches service-design.md §3 literally**: idempotency check → coverage
  call (no transaction open) → re-check idempotency (race safety) → `claim_num` exists check →
  insert. A request that will ultimately fail with `CLAIM_ALREADY_EXISTS` still pays for one
  coverage call first - that's the spec's ordering, not an oversight.
- **Two real bugs found by the resilience IT, neither from inspection**:
  - Boot 4.1.1 renamed `ClientHttpRequestFactorySettings` to `HttpClientSettings`, and moved
    `ClientHttpRequestFactoryBuilder` into a new dedicated artifact,
    `spring-boot-http-client` - separate from both `spring-boot` core and
    `spring-boot-restclient` (which pulls it in transitively, but claims-service only had that
    at test scope). Compile failed with "package does not exist" until `spring-boot-http-client`
    was added at compile scope.
  - `PolicyServiceHttpApi.getCoverage`'s `onDate` parameter had no explicit date format, and
    the default `LocalDate`-to-query-param conversion for an `@HttpExchange` client is **not**
    ISO-8601 - it rendered as `dd/MM/yy` (URL-encoded, e.g. `01%2F06%2F26`), which WireMock's
    exact-match stub then silently failed to match, returning its own unmatched-request 404,
    which the client correctly-but-misleadingly translated to `POLICY_NOT_FOUND`. Every
    registration in `ClaimRegistrationIT` failed this way until diagnosed by logging
    `WireMockServer.getAllServeEvents()` and reading the literal request URL. Fixed with
    `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)` on the parameter, matching the pattern
    `PolicyController.coverage()` already used server-side. A reminder that "no explicit
    format" has a real, non-obvious default on the client side of Spring's declarative HTTP
    clients, not just the server (MVC) side.
- **Environment note, not a code issue**: hit the same stale/corrupted `target/classes` symptom
  from phase 3 (an "Unresolved compilation problems" stub for `PolicyServiceClientConfig`,
  after adding the new `spring-boot-http-client` dependency) - this time it persisted across
  several `mvn verify` runs rather than clearing on retry, because Maven's incremental compiler
  trusts the `.class` file's timestamp and had no reason to recompile a file whose source
  hadn't changed since the bad write. Deleting just that one stale `.class` file (not `clean`,
  which is still blocked by the file-lock issue reported after phase 3) forced a real
  recompile and resolved it. Still an IDE/Maven `target/` contention issue, not a build
  problem to route around.
- Both new `LayeredArchitectureTest` rules verified to actually fail (not just pass trivially):
  temporarily made `ClaimRepository extends CrudRepository` and confirmed
  `domainAndApplicationDoNotDependOnSpringData` failed; temporarily added a
  `RegisterClaimCommand`-typed field to `ProcessedRequest` and confirmed
  `domainDoesNotDependOnApplication` failed; reverted both immediately after confirming.
- 22 claims-service tests total (7 `ClaimRegistrationIT` + 4 `ClaimRegistrationResilienceIT` +
  7 `ClaimRegistrationServiceTest` unit + 3 ArchUnit + 1 context-load smoke), all green.
  Domain+application JaCoCo line coverage: 87.7%. Full `./mvnw verify` reactor passes across
  all 4 modules.
- Not in this phase: 04 (status update) and its `ObjectOptimisticLockingFailureException`
  handler (unreachable until something mutates an existing `@Version`-tracked `claim` row),
  the outbox/`ClaimRegistered` event (phase 7), and `GET /internal/claims/{claimNum}`.

### Phase 4b pre-work housekeeping (2026-09-23)

- **`@Retry`/`@CircuitBreaker` aspect order confirmed by decompiling resilience4j-spring6
  2.4.0** (not assumed): default `retryAspectOrder`=2147483642, `circuitBreakerAspectOrder`=
  2147483643. Lower runs outermost in Spring AOP, so **Retry wraps CircuitBreaker** - each of
  Retry's up-to-3 attempts re-enters the CircuitBreaker aspect separately. Consequence: the
  50%-over-a-20-call sliding window counts **individual HTTP attempts**, not logical
  registration calls - one logical call that exhausts all 3 retries contributes 3 window
  entries, not 1. Documented in `PolicyServiceHttpApi`.
- **Worst-case latency for one `POST /internal/claims` call, computed precisely**: 3 attempts x
  5s read-timeout (the dominant case - a hung-but-connected policy-service, worse than a
  connect-timeout-only failure) + backoff between attempts (200ms, then 400ms) = **15.6s**. Not
  enforced by an overall deadline (no `TimeLimiter` added - that would be a bigger decision than
  this housekeeping pass warrants, and cross-cutting.md's connect/read/retry numbers were
  already explicitly settled in the phase-4a prompt, not being revisited here). Recorded as a
  comment in `application.yml` next to the timeout config, including the note that a caller
  (hub-gateway, phase 5) must set its own timeout to claims-service longer than 15.6s or this
  bound is moot from its perspective.

## Phase 4b notes — claims-service 04 status update (claims-service now fully done)

- No schema change - phase 4a's `V1__init.sql` already had every column 04 needs.
- **`ClaimStatusPolicy`** (`domain/`) is a plain, Spring-free POJO taking `terminal`/
  `transitions` via its constructor - SKILL.md's package layout explicitly lists "domain rules"
  under `domain/`. The `@ConfigurationProperties` binding lives separately in
  `config/ClaimStatusProperties`, wired to a `ClaimStatusPolicy` bean by `config/
  ClaimStatusConfig`. One check (`!terminal.contains(from) && transitions.getOrDefault(from,
  List.of()).contains(to)`) covers both conditions service-design.md §3 describes ("an unknown
  status, or a transition out of a terminal state") - an unrecognized target is never in any
  source's allowed list either, so no separate code path is needed.
- **`docs/open-questions.md` Q3 resolved** (was "no assumption yet"): the transitions map is
  `service-design.md` §3's sample verbatim, config-driven not hardcoded. Same-status
  transitions (e.g. `UNDER_PROCESS`→`UNDER_PROCESS`) are not special-cased anywhere in code -
  they're allowed exactly when the config lists them, which the sample already does for
  `UNDER_PROCESS` only (re-affirming a claim still under process is a legitimate, distinct
  event) and not for `REGISTERED`/`REQUIREMENT_PENDING`. Proven by
  `ClaimStatusUpdateIT.sameStatusTransitionIsAllowedWhenConfigPermitsItAndWritesAHistoryRow`,
  not just asserted.
- **`CLAIM_NOT_FOUND` covers "doesn't exist" and "exists under a different policy" identically**:
  `claims.findByClaimNum(claimNum).filter(c -> c.getPolicyNum().equals(cmd.policyNum()))`,
  same `safeDetail` (just the `claimNum` the caller already sent) either way.
  `ClaimRepository.findByClaimNum` promoted from JPA-adapter/test-only to a real port method,
  same pattern as `PolicyRepository.findByPolicyNum` in phase 3.
- **`Claim.applyStatusUpdate(...)` is a partial update**: `claimStatus` always changes, but
  every settlement field (`settledAmt`, `claimCode`, `finalizationDate`, `osAgeing`,
  `requireDetails`, `repuCancelDate`, `reasonOfClosure`) only overwrites when the command's
  value is non-null - `PATCH` semantics, so a caller sending only `claimStatus` can't
  accidentally erase settlement data a prior call already set.
- **Real bug found by the concurrent-update IT, not by inspection**: two different `reqId`s
  racing to update the same `claim` row doesn't always surface as a clean optimistic-lock
  version mismatch. MySQL/InnoDB can instead detect a genuine deadlock between the two
  transactions and roll one back with `CannotAcquireLockException`
  (`PessimisticLockingFailureException`), not `ObjectOptimisticLockingFailureException`
  (`OptimisticLockingFailureException`) - two different exception types under the *same*
  `ConcurrencyFailureException` superclass, and both mean the same thing to the caller: retry.
  `ClaimExceptionHandler`'s handler was written to catch only the narrower
  `ObjectOptimisticLockingFailureException` first, which meant the deadlock case fell through
  to the catch-all and returned 500 instead of 409 - exactly the "409 not 500" outcome the
  required test asked for, caught by writing that exact test. Fixed by broadening the handler
  to `ConcurrencyFailureException`, which covers both.
- **Two self-inflicted test bugs, also worth recording**: a synthesized `txnId` in a test setup
  helper (`"TXN-SETUP-" + claimNum`) overflowed the `CHAR(26)` column once claim numbers got
  long enough - fixed with a short counter instead of concatenating unbounded test data into a
  fixed-width column. And a "message leaks nothing" assertion (`doesNotContainIgnoringCase
  ("policy")`) false-failed against its own test's claim number, `CLM-STATUS-CROSSPOLICY-1`,
  because "CROSSPOLICY" contains "policy" as a substring - renamed the test data, not the
  assertion.
- 45 claims-service tests total: 21 IT (7 `ClaimRegistrationIT` + 4
  `ClaimRegistrationResilienceIT` + 10 `ClaimStatusUpdateIT`) + 24 unit/ArchUnit/smoke (7
  `ClaimRegistrationServiceTest` + 7 `ClaimStatusUpdateServiceTest` + 6 `ClaimStatusPolicyTest`
  + 3 ArchUnit + 1 context-load smoke), all green, including three extra standalone runs of
  `ClaimStatusUpdateIT` to confirm the concurrent-update test isn't flaky. Domain+application
  JaCoCo line coverage: 89.0%. Full `./mvnw verify` reactor passes across all 4 modules.
  `domainDoesNotDependOnApplication` re-verified against the new `ClaimStatusPolicy` class
  specifically (not just the rule in general) by temporarily adding an `application`-package
  field to it and confirming the build broke, then reverting.
- claims-service is now feature-complete for phases 4a+4b. Not built: the outbox/
  `ClaimStatusChanged` event (phase 7) and `GET /internal/claims/{claimNum}` (read model,
  never explicitly requested).

## Phase 5 notes — hub-gateway commit 1 (security + dispatch skeleton)

OAuth2 resource server, insurer/IP resolution, request body size limiting, `HubServiceCode`
resolution + `HubDispatcher`, and the pre-trust JSON error shape. Crypto stays off
(`hub.crypto.enabled=false`, `local` profile only; `HubStartupGuard` fails startup outside
`local`/`dev`-`prod` guards). No `CodeHandler` is registered yet - dispatch is proven via a
deliberately mismatched service code reaching a clean `INVALID_SERVICE_CODE`, not a real
business path (commit 2).

Five real bugs surfaced only once `HubGatewaySecurityIT` actually ran against a live Keycloak
container - none of them visible from reading the code:
- **`HubSecurityProperties` was never registered.** `@ConfigurationProperties` alone doesn't
  create a bean; it needs `@EnableConfigurationProperties`, which every other properties record
  had via `HubStartupGuard` except this one. Added to the same list.
- **`LayeredArchitectureTest` failed on empty layers, not a real violation.** `application/` and
  `infrastructure/` legitimately have zero classes until commit 2. Restored
  `.withOptionalLayers(true)` (to be removed once commit 2 populates both packages) rather than
  leaving the whole rule off.
- **Missing-scope requests got 401, not 403.** `IpAllowlistFilter` ran right after the JWT
  authentication filter, ahead of Spring Security's own scope-based authorization - so a token
  from a client id no insurer maps to (the test-only `test-no-scope-client`) was rejected as
  `TOKEN_INVALID` before the scope check ever ran, masking the `INSUFFICIENT_SCOPE` case the
  test was actually checking. Moved `IpAllowlistFilter` to run after `AuthorizationFilter`
  instead - scope is a property of the token itself and shouldn't need insurer resolution to be
  enforced.
- **Expired-token detection never worked, for two compounding reasons.** (1) Spring Boot's
  autoconfigured `JwtTimestampValidator` defaults to a 60-second clock skew - a token that
  expired 3 seconds ago was still authenticating successfully, so the "expired" branch was
  unreachable regardless of anything else. Tightened to 5s via a custom `JwtDecoder` bean
  (`docs/open-questions.md` Q14 - not bank-specified). (2) Even with real expiry enforced,
  matching `authException`'s message for the word "expired" is not reliable on this Spring
  Security version - the exception reaching `commence()` for an expired token is a generic
  `InsufficientAuthenticationException`, indistinguishable by message from a plain missing
  token. Replaced with re-parsing the token's own `exp` claim directly (no re-verification -
  the token already failed real verification by the time the entry point runs; this only picks
  which error message to show).
- **413's `HttpStatus` enum identity changed.** Spring Framework renamed 413's canonical entry
  from `PAYLOAD_TOO_LARGE` to `CONTENT_TOO_LARGE`; `HttpStatus.valueOf(413)` now resolves to the
  new one, so a test comparing by the old enum constant failed even though the wire response was
  correct. Same class of issue `UNPROCESSABLE_ENTITY`/`UNPROCESSABLE_CONTENT` already hit for
  422 in `hub-common`'s `HubErrorCode` (phase 3/4). Fixed by comparing the raw numeric code in
  the test, matching how `HubController` already treats `respCode` as the source of truth rather
  than looking up an enum.
- Also hit along the way, not a product bug: `OAuth2ResourceServerProperties` moved package in
  Boot 4 (`org.springframework.boot.security.oauth2.server.resource.autoconfigure`, not the old
  `org.springframework.boot.autoconfigure.security.oauth2.resource`) - same modularization
  pattern as `TestRestTemplate` in earlier phases. The custom `JwtDecoder` bean also had to be
  wrapped in `SupplierJwtDecoder` (the same wrapper Boot's own autoconfiguration uses) so issuer
  metadata is fetched lazily, not at bean-creation time - otherwise a plain `@SpringBootTest`
  with no real Keycloak reachable fails application startup outright.
- `./mvnw -pl hub-common,hub-gateway -am verify` green: 17 hub-gateway unit/ArchUnit tests + 7
  `HubGatewaySecurityIT` scenarios against a real, singleton-per-JVM Keycloak container
  (missing/malformed/expired token, missing scope with status/body agreement asserted, IP not
  allowed, oversized body, valid-token-reaches-dispatcher), plus hub-common's existing suite
  (picking up the two new `HubErrorCode` entries).
- Not built yet (commit 2): the four real `CodeHandler`s, external↔internal mapping,
  `request_audit` write, downstream client wiring, and their ITs.

## Phase 5 commit-1 review round fixes

Before starting commit 2, fixed everything flagged in commit-1 review:
- **Expired-token detection now gates on `JwtValidationException`, not message-matching.**
  Confirmed against real Keycloak (three exception-chain shapes captured for missing/malformed/
  expired tokens) that `JwtValidationException` only ever appears in the chain *after* a token
  has already decoded and signature-verified successfully and then failed a validator (issuer or
  timestamp) - a bad signature or malformed token throws plain `BadJwtException` instead, never
  reaching this class. So looking for a `JwtValidationException` at all already rules out
  signature/decode failures by construction; only its own `getErrors()` text still needs
  reading (`JwtTimestampValidator` uses the same `invalid_token` code for both expiry and
  not-yet-valid, distinguished only by description).
- **`-Xlint:deprecation -Werror`** added to the parent POM for all four modules. Found and fixed
  three real deprecated-API usages this immediately caught: `HttpStatus.PAYLOAD_TOO_LARGE` (→
  `CONTENT_TOO_LARGE`, same Spring 7 rename `UNPROCESSABLE_ENTITY` already hit in phase 3/4) in
  `hub-common`'s `HubErrorCode`, and `org.testcontainers.containers.MySQLContainer` (→
  `org.testcontainers.mysql.MySQLContainer`, a Testcontainers module split) across all 8 test
  files in policy-service/claims-service/hub-gateway that used it - the new module's
  `MySQLContainer` also isn't generic anymore (`MySQLContainer`, not `MySQLContainer<?>`).
- **`LayeredArchitectureTest`'s `.withOptionalLayers(true)`** removed now that `application/`
  and `infrastructure/` are populated (commit 2).
- **A defined code + test for a valid token whose client id isn't in `hub.insurers`**: confirmed
  `TOKEN_INVALID` (401) is the deliberate, documented choice (not `INSURER_MISMATCH`, which is
  reserved for a different, phase-6 check - a decrypted body whose `header.inspId` disagrees
  with the token's *already-resolved* insurer). Added `test-unregistered-client` to the test
  realm and `HubGatewaySecurityIT.validTokenFromAnUnregisteredClientIsRejectedAsTokenInvalid`.
  The other four review-round items (scope respCode consistency, the `0.0.0.0/0` startup guard,
  the 256 KB body limit, `NoOpHubCryptoService`'s `matchIfMissing`, the singleton Keycloak
  container) were already done in commit 1.
- `docs/adr/0004-keycloak-hostname-pinning.md` and `docs/adr/0005-audit-everything.md` added -
  durable records for the two review-round design decisions, not just code comments.

## Phase 5 commit-2 notes — mapping, dispatch, audit, error handling

The four real `CodeHandler`s (`api/`), external↔internal mapping (`mapping/`), downstream client
wiring (`infrastructure/client/`), and the `request_audit` write (`RequestAuditFilter` wrapping
the whole pipeline, `RequestAuditService`, `RequestAuditJpaRepository`) - hub-gateway is now
feature-complete for phase 5 (crypto still off; phase 6 swaps in the real JWS/JWE
`HubCryptoService`).

**Package-layout correction, carried over from commit 1's own ArchUnit correction**: the plan
originally had the mapper layer converting raw validated strings directly into wire DTOs. That
would put an `application/`-layer port (`PolicyServiceGateway`, `ClaimsServiceGateway`)
depending on `infrastructure/client/`-owned wire DTOs, which the same ArchUnit rule that moved
`CodeHandler`/`HubDispatcher` into `api/` in commit 1 also forbids (`Infrastructure
mayNotBeAccessedByAnyLayer` - application may not depend on it either). Fixed by giving
`application/` its own plain
Command/Result records (`CreatePolicyCommand`, `PolicyServiceResult`, etc.) that mirror the wire
shape field-for-field; `mapping/`'s per-code mappers (unconstrained by the layered-architecture
rule, so free to import both `domain/`'s `RawPolicyDetails`/`RawClaimDetails` and
`application/`'s Command types) produce the Command, and each `infrastructure/client/` adapter
translates Command → wire request DTO → real HTTP call → wire response DTO → Result privately,
inside itself. `RawHeader`/`RawHubRequestBody`/`RawPolicyDetails`/`RawClaimDetails` themselves
moved from `api/` to `domain/` for the same reason - `application/`'s `HubRequestValidator`
needs to read them directly, and `application` may not depend on `api` either.

Real bugs found only once tests actually ran, not by inspection:
- **`DateTimeFormatter`'s default SMART resolver does not reject an impossible date.**
  `LocalDate.parse("30/02/2024", DateTimeFormatter.ofPattern("dd/MM/yyyy"))` silently **clamps**
  day-of-month to the nearest valid value (parses as 29/02/2024) instead of throwing - found by
  `ExternalDateConverterTest`, which was written expecting a `DateTimeParseException` and got a
  parsed date back instead. Without `ResolverStyle.STRICT`, an insurer sending a nonsense date
  would have had it silently corrupted rather than rejected as `VALIDATION_FAILED`. Fixing this
  uncovered a second, compounding gotcha, also confirmed with a standalone reproduction before
  relying on it: `ResolverStyle.STRICT` combined with the `yyyy` (year-of-era) pattern letter
  then fails to parse even an ordinary *valid* date, since year-of-era needs an era STRICT won't
  infer on its own - `uuuu` (proleptic year) is required instead. `ExternalDateConverter` now
  uses `dd/MM/uuuu` + `STRICT`.
- **`HttpServiceProxyFactory` does not treat an unannotated `@HttpExchange` parameter as the
  request body**, unlike Spring MVC controller methods - confirmed at runtime
  (`IllegalStateException: Could not resolve parameter... No suitable resolver`) for *every*
  `PolicyServiceHttpApi`/`ClaimsServiceHttpApi` method, including the single-parameter ones.
  Fixed by adding explicit `@RequestBody` to each wire-request parameter.
- **`HubResponse.failure(code, txnId, reqId)` always used the catalogue's generic
  `code.errorDesc()`, discarding the exception's own substituted detail** - so
  `VALIDATION_FAILED`'s `<field>` placeholder was never actually being filled in on the wire; a
  field-validation rejection's `errorDesc` was the literal string `"Validation failed: <field>"`.
  Found by `HubGatewayFieldValidationIT` asserting the real field name appeared in the response.
  Fixed with a new `HubResponse.failure(code, errorDesc, txnId, reqId)` overload (hub-common),
  used only for `VALIDATION_FAILED` (`HubController`) - every other code's `safeDetail` is an
  internal identifier (a policyNum, an inspId), never meant to replace the catalogue's fixed
  external wording, and continues to use the plain three-arg overload.
- Two WireMock test-authoring mistakes, worth recording since they'll recur: the static
  `WireMock.findAll(...)`/`WireMock.post(...)` helpers talk to a *default* admin client
  (`localhost:8080`) unrelated to a specific `WireMockServer` instance's actual (random) port -
  use the instance method (`wireMockServer.findAll(...)`) instead. And a hand-crafted
  `Map`-based JSON body for a stubbed `ProblemDetail` response doesn't reliably round-trip
  through `getResponseBodyAs(ProblemDetail.class)`'s `detail` field - moot here once the
  `HubResponse.failure` fix above meant hub-gateway discards the downstream `detail` for
  non-`VALIDATION_FAILED` codes anyway, but worth remembering if a future test needs to assert
  on a downstream `detail` value specifically.

`./mvnw -pl hub-common,hub-gateway -am verify` green: full commit-1 + commit-2 suite, including
`HubGatewayDispatchIT` (one happy path per code, WireMock-stubbed downstreams),
`HubGatewayServiceCodeValidationIT`, `HubGatewayFieldValidationIT`, `HubGatewayErrorMappingIT`
(each downstream `ProblemDetail` code + the replay-txnId proof), `HubGatewayResilienceIT`
(shortened per-class timeouts, claims-service unreachable/slow → `DOWNSTREAM_UNAVAILABLE` never
a 500), `HubGatewayAuditIT` (success/business-rejection/pre-trust-rejection/IP-rejection rows,
plus a real-PII-values-never-in-any-column proof), `HubGatewayAuditWriteFailureIT` (a mocked
`AuditRepository` throwing still returns the real response and increments the failure counter),
plus unit tests for both converters, `HubRequestValidator`, `DownstreamErrorDecoder`,
`RequestAudit`'s reflection field-list proof, all four mappers, and all four `CodeHandler`s.

**Known issue, not caused by this phase's code changes**: `./mvnw clean verify` for the full
4-module reactor (or various module subsets including policy-service or claims-service) showed
intermittent `NoClassDefFoundError`/`NoSuchBeanDefinitionException` failures on completely
unmodified classes (`CreatePolicyRequest`, a MapStruct-generated
`ClaimRegistrationCommandMapperImpl`) not reproducible when the affected module builds alone.
Ruled out disk space, stale Docker/Testcontainers state, and JaCoCo instrumentation as causes;
not further root-caused. `hub-common`+`hub-gateway` together verify green reliably (confirmed
repeatedly). Worth a dedicated follow-up investigation before relying on the full-reactor `mvn
verify` command in CI.

## Phase 6 notes — hub-gateway crypto (JWS/JWE)

`NoOpHubCryptoService` replaced by `NimbusHubCryptoService` (Nimbus JOSE+JWT): inbound
Base64-decode → JWS-verify (insurer's public key) → JWE-decrypt (bank's private key); outbound
JWE-encrypt (insurer's public key) → JWS-sign (bank's private key) → Base64. Algorithm allow-list
(RS256 / RSA-OAEP-256 / A256GCM) is checked *before* any cryptographic operation runs, so
algorithm-confusion attempts (`none`, `HS256`, `RSA1_5`, `A128CBC-HS256`) are rejected without
ever touching a key. `KeyRegistry` parses every PEM once at `@PostConstruct` (bank key(s) by kid,
each insurer's public key by `inspId`) and never throws on failure - it sets an internal
"not loaded" flag instead, which `KeyRegistryHealthIndicator` surfaces as readiness `DOWN`
(`crypto.KeyRegistry`/`crypto.KeyRegistryHealthIndicator`, both `@ConditionalOnProperty(hub.crypto
.enabled=true)`, complementary to `NoOpHubCryptoService`'s own condition so exactly one
`HubCryptoService` bean always exists). `gen-dev-keys.sh` and `send-sample.sh` are now real
(RSA-2048 PKCS#8 keypairs for `bank`/`insp001`/`insp002`/`insp003`; a small `exec-maven-plugin`-
invoked JVM helper, `testsupport/EnvelopeCli`, does the actual JOSE work since plain `openssl`
CLI calls can't produce a compliant JWE compact serialization).

Two real bugs found only once tests/the real stack actually ran, not by inspection:
- **Spring Boot's readiness health *group* does not automatically include a custom
  `HealthIndicator` bean** - it only joins the top-level aggregate `/actuator/health` endpoint by
  default; a probe needs `management.endpoint.health.group.readiness.include` naming it
  explicitly. Worse, **Boot fails application startup outright** if that include list names a
  contributor that doesn't exist (`HealthContributorMembershipValidator`, not a silent drop as
  first assumed) - so this can't be a static default in the shared `application.yml`, since most
  of the phase-5 IT suite runs with crypto off (`keyRegistry` bean absent). Fixed by setting the
  include list only where crypto is actually on: `docker-compose.yml`'s `hub-gateway` environment
  (`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE`) and `AbstractHubGatewayCryptoIT`'s own
  `@DynamicPropertySource` (plus `HubGatewayCryptoReadinessIT` directly, since it deliberately
  doesn't extend that base class). Caught by re-running the *full* reactor after the crypto ITs
  looked green in isolation - the crypto-off suite (`HubGatewayAuditIT`,
  `HubGatewaySecurityIT`, etc., all using the `local` profile's crypto-off default) failed
  entirely on context startup until this was scoped correctly.
- **git-bash's automatic path conversion mangles an absolute POSIX path passed as a Maven
  `-Dexec.args=...` argument to a Windows-native `java.exe`** - `/c/Users/.../.secrets/
  bank-public.pem` arrived at `PemKeys.readPublicKey` as `\c\Users\...` (no drive-letter colon),
  a `NoSuchFileException` `send-sample.sh` only surfaced as an empty `ENC` variable. Fixed by
  passing paths relative to `ROOT_DIR` (the subshell already `cd`s there) instead of `$SECRETS_DIR`'s
  absolute form - a relative path never triggers the conversion.

Also confirmed empirically, not just designed for: a JWE nested as a JWS's own signed payload
means tampering the JWE's ciphertext *after* it's wrapped breaks the JWS signature check, not the
JWE decrypt - `NimbusHubCryptoServiceIT`'s tampered-ciphertext test builds and tampers the JWE
*before* wrapping/signing it, to isolate `DECRYPTION_FAILED` from `SIGNATURE_INVALID` correctly.

`./mvnw -pl hub-common,hub-gateway -am verify` green: full phase-5 suite unaffected (crypto off,
`local` profile default) + the new crypto suite - `NimbusHubCryptoServiceIT` (11, round trip for
all 4 codes, tampered ciphertext, wrong signer, all 4 disallowed algorithms, malformed
Base64/empty `enc`, insurer mismatch enveloped not plain, unregistered-insurer-key rejection, no
key material/`enc`/JOSE internals ever in captured logs), `KeyRegistryTest` (5, incl. bank-key
rotation by kid), `KeyRegistryHealthIndicatorTest` (2), `CryptoBeanSelectionTest` (3, exactly one
`HubCryptoService` bean for enabled/disabled/absent), `NimbusHubCryptoServiceTest` (1),
`HubGatewayCryptoAuditIT` (2, crypto rejections still write one audit row per ADR-0005),
`HubGatewayCryptoReadinessIT`/`HubGatewayCryptoReadinessUpIT` (readiness DOWN/UP matching
`KeyRegistry`'s own loaded state).

**Manually verified against the real stack**: `scripts/gen-dev-keys.sh` → `docker compose up -d
--build` → `/actuator/health/readiness` returns `{"status":"UP"}` (real `KeyRegistry` loaded 1
bank key, 3 insurer public keys) → `scripts/send-sample.sh 01`/`02`/`03`/`04` each round-trip a
genuinely signed+encrypted request through the running gateway and decrypt+verify a genuinely
signed+encrypted response back, all four returning `{"status":"S","respCode":"200",...}`.

## Phase 6 review round (ultrareview)

A full-repo `/code-review ultra` pass surfaced one real regression from this phase's own change
plus three real, pre-existing gaps in already-committed (phase 5) code. Fixed all four, plus the
three nits it flagged, before committing:
- **`EncryptedEnvelope.enc` losing `@NotBlank` (this phase's own change) regressed the crypto-off
  missing-`enc` case from 400 to 500.** `NoOpHubCryptoService.verifyAndDecrypt` echoes a null
  `enc` straight through (by design - it's an identity no-op), and
  `ObjectMapper.readValue((String) null, ...)` throws `IllegalArgumentException`, not a
  `JacksonException` - falling through `HubController.parse`'s catch into the generic 500
  handler. Fixed with an explicit null check in `parse()`, throwing `INVALID_JSON` (400) directly.
  `HubGatewayDispatchIT.missingEncFieldIsRejectedAsInvalidJsonNeverA500` pins it.
- **`HubRequestValidator` skipped validation entirely for a `policyDetails`/`claimDetails` object
  missing from the JSON altogether** (only blank-field values were checked, not a missing object)
  - every mapper (`NewPolicyRequestMapper` etc.) dereferences it unconditionally, so this was a
    real NPE-to-500 path for any code whose required object was omitted. Fixed by making
    `HubRequestValidator.validate` report a `policyDetails`/`claimDetails` violation when the
    resolved group requires that object and it's null (the requiredness matrix mirrors each
    mapper's own field usage exactly - 03 is the one code needing both objects).
- **Circuit breaker `record-exceptions` for both downstreams omitted generic 5xx**
  (`HttpServerErrorException$InternalServerError`) - resilience4j's allow-list semantics mean a
  downstream returning a clean 500 (not 503) was recorded as a *successful* call, so a genuinely
  broken downstream would never trip the breaker. Added `$InternalServerError` to both breakers'
  `record-exceptions` only (deliberately NOT to `retry-exceptions` - cross-cutting.md §6 still says
  never retry a 500, only 503/timeouts/connect-errors).
  `HubGatewayResilienceIT.repeated500sFromClaimsServiceTripTheCircuitBreaker` proves it (with a
  test-only `minimum-number-of-calls=20` override matching the 20-call window, since resilience4j's
  own default is 100).
- **`RawHeader.reqId` had no length bound against the `request_audit.req_id VARCHAR(64)` column**,
  and `HubDispatcher` captures it into `AuditContext` *before* `HubRequestValidator` ever runs - so
  an oversized `reqId` could make the audit INSERT itself fail under MySQL strict mode, silently
  losing exactly the row ADR-0005 most wants recorded (adversarial/malformed input). Fixed two ways:
  `RawHeader.reqId` gained `@Size(max = 64)` for the clean-rejection happy path, and
  `RequestAudit.of` now truncates `reqId`/`serviceType` defensively (the two free-text fields with
  no upstream length bound) so the row is still written even for a request that fails validation
  for being oversized.
- **Nits**: `CodeHandler.handle`'s `txnId` parameter was threaded through all four implementations
  and never read (the response always reflects the downstream's own txnId - confirmed by
  `NewPolicyCodeHandlerTest`'s own test name) - removed from the interface, `HubDispatcher`, and
  `HubController`. `NewPolicyRequestMapper`/`RenewalRequestMapper`'s identical 25-field
  `RawPolicyDetails` parsing is now a shared `PolicyDetailsFields.from(...)` (their target Command
  types stay deliberately distinct per the phase 3 precedent). `PolicyServiceClient`/
  `ClaimsServiceClient`'s identical `decode(HttpStatusCodeException)` fallback is now one method on
  `DownstreamErrorDecoder`.

Two findings from the same review turned out to be false positives, not fixed: it reported
`NimbusHubCryptoService`/`KeyRegistry`/`EnvelopeCli` as not existing in the repo. They exist on
disk and the full suite (including a real `docker compose` + `send-sample.sh` run) proves them
working - the review's bundle evidently only reflected *tracked* file changes, and this entire
phase's new classes were still untracked (never committed) at review time.

`./mvnw -pl hub-common,hub-gateway -am verify` green after all of the above: full phase 5 + phase
6 crypto suite unaffected, plus every new/updated test the fixes above added.
