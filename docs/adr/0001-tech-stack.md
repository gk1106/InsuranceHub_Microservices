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
- **Caveat**: `io.awspring.cloud:spring-cloud-aws` (needed in phase 9 for Secrets
  Manager/Parameter Store/S3 clients) is only at a `4.0.0-M1` milestone against Boot 4/Spring
  Cloud 5 as of this writing — not GA. Not a blocker now (phase 9 is months away and the module
  isn't on the classpath yet), but **re-check spring-cloud-aws GA status before starting phase
  9**; if it's still pre-release then, fall back to the AWS SDK v2 directly instead of waiting.

## Consequences

- Observability property names, Spring Security defaults, and Jackson behavior should be
  verified against the Boot 4.1 docs when reached (phase 8 logging/tracing, phase 5 gateway
  validation) rather than assumed from Boot 3.x familiarity — Boot 4 renamed some observability
  properties, per CLAUDE.md rule 9.
- If spring-cloud-aws compatibility becomes a real blocker in phase 9, downgrading the whole
  stack to Boot 3.5.x is the fallback; revisit this ADR if that happens.
