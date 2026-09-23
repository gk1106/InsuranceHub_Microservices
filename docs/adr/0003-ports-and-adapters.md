# ADR-0003: Ports-and-adapters for repositories, and JPA on domain entities

Status: Accepted (phase 2)

## Decision

Repository interfaces are split across two layers:

- **Ports** — plain interfaces in `application/` (e.g. `PolicyRepository`,
  `PolicyTermRepository`, `ProcessedRequestRepository`), declaring only the methods the
  application layer actually calls (`existsByPolicyNum`, `save`, `findByInspIdAndReqId` — not
  a full CRUD surface). No Spring Data types anywhere in their signatures.
- **Adapters** — interfaces in `infrastructure/persistence/` (e.g. `PolicyJpaRepository`) that
  extend *both* `org.springframework.data.jpa.repository.JpaRepository` and the matching port.
  Spring Data implements the adapter at runtime (including query-derivation methods like
  `existsByPolicyNum`, inherited from the port but implemented via the adapter's Spring Data
  registration); Spring injects that proxy wherever the *port* type is requested. Application
  code never references the adapter type or `infrastructure` package at all.

Entity construction goes through Lombok `@Builder` (`@Getter` + `@NoArgsConstructor(PROTECTED)`
+ `@AllArgsConstructor(PRIVATE)`), called from `application/` with unpacked primitive/domain
values — not a `newFrom(command)`-style factory taking the command object directly.

## Why

CLAUDE.md's package layout says "infrastructure implements ports defined in
application/domain" and "dependencies point inward: `api -> application -> domain`." Two
concrete violations surfaced while implementing phase 2 that made this literal:

1. An earlier draft had `PolicyCreationService` (application) depend on
   `infrastructure.persistence.PolicyRepository` directly (a `JpaRepository` subtype living in
   `infrastructure`). `LayeredArchitectureTest`'s ArchUnit rule — "infrastructure may not be
   accessed by any layer" — correctly failed on this: `application` accessing `infrastructure`
   is exactly backwards from "infrastructure implements ports defined in application."
2. An earlier draft also had entity factories shaped like `Policy.newFrom(cmd)`, where `cmd` is
   `application.CreatePolicyCommand`. That makes `domain` import an `application` type — the
   same violation, one layer down, and also what phase-2's new
   `domainDoesNotDependOnApplication` ArchUnit rule (added this round, per your review) exists
   to catch mechanically. `@Builder` avoids it: `PolicyCreationService` builds a `Policy` by
   calling builder methods with plain values it already unpacked from the command, so `domain`
   never needs to know `CreatePolicyCommand` exists.

The ports-and-adapters split fixes (1): the port is a plain interface with zero framework
imports, satisfying `application` importing only `domain` and the port stays framework-free;
`infrastructure` depends on `application` (to implement its port) and `domain` (for the entity
type), which is the correct direction and exactly what the layered rule already permits
("Domain may only be accessed by Api, Application, Infrastructure").

## Accepted trade-off: domain entities carry JPA annotations

`Policy`, `PolicyTerm`, and `ProcessedRequest` are annotated `@Entity`, `@Table`, `@Column`,
`@Id`, `@Version`, etc. directly — they are not persistence-ignorant POJOs mapped by a separate
infrastructure-layer class. This is a deliberate, narrower interpretation of "ports and
adapters" than the purist form, and it's explicitly sanctioned by the project's own testing
strategy (`testing-and-deploy.md`'s ArchUnit rule): "`domain` has no Spring web/JPA-
infrastructure imports **beyond annotations**." The rule anticipates and permits exactly this —
annotations are fine, importing JPA *infrastructure* (repositories, `EntityManager`, session
types) is not, which is what `domainAndApplicationDoNotDependOnSpringData` (also added this
round) enforces.

**Why accept it instead of fully isolating domain from JPA**: a fully persistence-ignorant
domain model would need a separate row-mapping layer (either hand-written mapper classes or a
mapping framework) translating between plain domain objects and JPA entities, for every entity,
in every service, forever. That's real, ongoing complexity with no payoff unless this project
expects to swap persistence technology away from JPA/MySQL — which it doesn't (CLAUDE.md's tech
stack section pins MySQL + Spring Data JPA project-wide, not per-service). Rule 2's "tiny/
decoupled" concern is about not sharing domain code *across services* (`hub-common` staying
free of entities), not about any single service's domain layer being framework-agnostic
internally.

## Consequences

- If a future phase ever needs to change persistence technology for one service, that service's
  domain classes will need touching too — accepted, see above.
- Every new repository needs three things, not one: the port interface (`application/`), the
  JPA adapter interface (`infrastructure/persistence/`), and the entity itself (`domain/`).
  Slightly more ceremony per repository than a single `JpaRepository` subtype, in exchange for
  the layering guarantee being real and ArchUnit-enforced rather than just a convention.
- `LayeredArchitectureTest` now has three rules, not one: the original layered-architecture
  check, `domainAndApplicationDoNotDependOnSpringData`, and `domainDoesNotDependOnApplication`.
  All three were verified to actually fail (not just pass trivially) by temporarily introducing
  the violation each one targets and confirming the build breaks, then reverting.

## Idempotency is duplicated across services, not promoted into `hub-common` (phase 4a)

`claims-service`'s `Claim`, `ClaimStatusHistory` and `ProcessedRequest` entities, its ports/
adapters split, and `ClaimRegistrationService`'s `TransactionTemplate` +
`PROPAGATION_REQUIRES_NEW`-recovery pattern are near-verbatim copies of `policy-service`'s
phase-2/3 code — not a shared type or shared base class in `hub-common`.

**Why**: SKILL.md rule 2 is explicit — "`hub-common` stays tiny. No entities, no domain logic,
no service-specific DTOs. If you're tempted to share a domain class, duplicate the DTO
instead. Shared domain code couples deployments." A shared `ProcessedRequest`/idempotency
module would mean a schema or behavior change to one service's idempotency handling forces a
`hub-common` version bump and a rebuild/redeploy of every service depending on it — exactly the
deployment coupling rule 2 exists to prevent. Each service's `processed_request` table is
already a separate table in a separate database (rule 1: database per service); duplicating the
~40 lines of entity/port/recovery code that reads and writes it costs far less than the coupling
a shared abstraction would introduce.

**Consequence**: a fix to the idempotency recovery pattern (like phase 2's constraint-name-
checking bug) has to be applied — and tested — in each service independently. This is accepted,
not overlooked: it is the direct, known cost of rule 2, paid once per service rather than paid
once system-wide as a deployment-coupling risk.
