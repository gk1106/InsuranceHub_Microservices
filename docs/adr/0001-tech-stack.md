# ADR-0001: Tech stack and versions

Status: Accepted (phase 0)

## Decision

- **Java 21** (LTS), per CLAUDE.md.
- **Spring Boot 4.1.1** (GA 2026-08-21, current stable at time of writing), pulled in via
  `spring-boot-starter-parent` as the parent of `insurance-hub-parent`. Requires Spring
  Framework 7 / Jakarta EE 11 / Servlet 6.1 baseline.
- Build: Maven multi-module, with the Maven Wrapper (`./mvnw`) checked in so the build doesn't
  depend on a pre-installed Maven.
- `springdoc-openapi-starter-webmvc-ui` **3.1.1** — first springdoc major aligned with Boot 4;
  built against Boot 4.1.0.
- `com.nimbusds:nimbus-jose-jwt` **10.0.2** for JWS/JWE (phase 6). Not Boot-version-coupled.
- `org.mapstruct` **1.6.3**, `com.tngtech.archunit:archunt-junit5` **1.4.1**,
  `org.wiremock:wiremock-standalone` **3.10.0**. Plain libraries, Boot-version-agnostic;
  bump opportunistically, not automatically.
- Everything else (Testcontainers, JUnit 5, AssertJ, Mockito, Lombok, MySQL connector, Flyway,
  Spring Kafka) takes the version managed by the Spring Boot 4.1.1 BOM — no explicit pin.

## Why Spring Boot 4.1.1 instead of the 3.5.x line

CLAUDE.md rule 9 says use the latest stable release. Boot 4 is a real major-version jump
(Jackson 3 by default, Spring Security 7 defaults, package modularization, deprecated 3.x APIs
removed) and was only GA since November 2025, so this was checked rather than assumed:

- springdoc-openapi already ships a Boot-4-targeted major (3.x) — OpenAPI docs work.
- Testcontainers, MapStruct, Nimbus JOSE+JWT, ArchUnit, WireMock are all plain JVM libraries
  with no Boot-version coupling — unaffected either way.
- **Caveat, resolved**: `io.awspring.cloud:spring-cloud-aws` was only at a `4.0.0-M1`
  milestone when this ADR was first written. Re-checked on the phase-3 dependency spike
  (branch `spike/phase3-boot4-deps`, not merged): **it's GA now, at 4.1.1**, and Spring Cloud
  2025.1.x (the release train pairing with it) is confirmed compatible with Boot 4.0.1+/4.1.x.
  No fallback to raw AWS SDK v2 needed.

## Phase 3 dependency spike (branch `spike/phase3-boot4-deps`)

Re-verified all three of Resilience4j, Spring Kafka, and spring-cloud-aws under Boot 4.1.1
before starting phase 3 feature work, per CLAUDE.md's "ask before adding new infrastructure"
spirit and because the codebase had changed structurally (ports-and-adapters, Failsafe) since
the phase-0→1 spike. One trivial bean per dependency, in `policy-service`'s `spike` package,
exercised via a `@SpringBootTest` IT (`SpikeCompatibilityIT`, Testcontainers MySQL — 9 total
tests pass including the pre-existing suite). Not merged; delete the spike code when phase 3's
real work supersedes it.

- **Resilience4j**: `resilience4j-spring-boot4` 2.4.0 — same finding as the earlier spike,
  reconfirmed. `@CircuitBreaker` annotation proxying and fallback dispatch work with no extra
  AOP dependency (`spring-boot-starter-aop` doesn't exist for Boot 4; `spring-aop` comes
  transitively via `spring-context`).
- **Spring Kafka**: unchanged from the BOM-managed version; a `NewTopic` bean via
  `KafkaAdmin`/`TopicBuilder` still wires up fine with no broker present (`fail-fast=false`
  default).
- **spring-cloud-aws**: `spring-cloud-aws-dependencies` 4.1.1 (BOM) +
  `spring-cloud-aws-starter-secrets-manager`. A `SecretsManagerClient` bean auto-configures and
  injects successfully — confirms the dependency chain cross-cutting.md §3 describes
  (`spring.config.import=optional:aws-secretsmanager:...`) will work when phase 9 actually uses
  it. The spike used static test credentials/region
  (`spring.cloud.aws.region.static`/`spring.cloud.aws.credentials.*`) to avoid the AWS SDK's
  default credential chain probing EC2 instance metadata in a non-EC2 dev environment — it
  proves the bean *constructs*, not that it can reach real AWS, which phase 9 will need to
  verify separately against real credentials.

## Consequences

- Observability property names, Spring Security defaults, and Jackson behavior should be
  verified against the Boot 4.1 docs when reached (phase 8 logging/tracing, phase 5 gateway
  validation) rather than assumed from Boot 3.x familiarity — Boot 4 renamed some observability
  properties, per CLAUDE.md rule 9.
- The spring-cloud-aws fallback (downgrade to Boot 3.5.x) is no longer a live concern — it's
  GA-compatible with 4.1.1. Left the note in case a *future* Boot 4.x point release regresses
  this; revisit this ADR if that happens.
