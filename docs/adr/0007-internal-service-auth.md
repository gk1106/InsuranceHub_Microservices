# ADR-0007: internal service auth is a shared-secret header, not a service JWT

Status: Accepted (phase 8)

## Decision

`cross-cutting.md` §4 left this open: "internal services also validate a short-lived service
JWT or require `X-Internal-Auth`." Phase 8 implements the second option — a static shared
secret, sent as `X-Internal-Auth` by hub-gateway on every call to policy-service/claims-service
and to policy-service by claims-service, checked by a small servlet filter
(`infrastructure/security/InternalAuthFilter`) in front of `/internal/**` in both domain
services.

The alternative — hub-gateway authenticating to Keycloak with its own confidential client and
attaching a short-lived Bearer token, with policy-service/claims-service becoming OAuth2
resource servers themselves — was considered and rejected for this phase. It is the stronger
control (expiring, revocable, standard), but it is a materially larger change: a new Keycloak
client/realm config, a new `spring-security-oauth2-resource-server` dependency in two services
that currently have none, a `SecurityConfig` in each, and token-fetch/caching logic on the
gateway's downstream `RestClient` calls. SKILL.md rule 10 ("ask before adding new
infrastructure... small libraries are fine") and the fact that this is explicitly a
defense-in-depth layer — network isolation (private subnets, security groups) is the real
control, per `cross-cutting.md` §4's own framing — both point toward the lighter option.

## Consequences

- **Not a strong control on its own.** A leaked secret grants full access until rotated by
  hand; there is no expiry, no per-caller identity, no revocation. Acceptable specifically
  because it is a second layer behind network isolation, not the only one — the same framing
  `cross-cutting.md` §4 already used to justify deferring this from phase 5 to phase 8 at all.
- **One secret, shared by all three services.** `hub.internal-auth.secret`, same literal value
  everywhere, sourced from `INTERNAL_AUTH_SECRET` (env var locally/in compose, Secrets Manager
  from phase 9). Rotating it requires redeploying all three services together — a real
  operational cost, deliberately accepted for phase 8's scope; a per-caller secret or a real
  token would remove this coupling but wasn't judged worth the added complexity here.
- **Comparison is constant-time** (`MessageDigest.isEqual`, not `String.equals`), closing the
  same timing-side-channel class `PiiMasker`/crypto code elsewhere in this project already takes
  seriously, even though the practical exploitability of a single internal HTTP hop is low.
- **Revisit before phase 9 if the bank's security review requires it.** The ADR's own escape
  hatch: if a real token is later required, the header check in `InternalAuthFilter` is
  structurally easy to swap for a `JwtDecoder`-based one without touching the callers other than
  their `RestClient` interceptor.

## A real bug found while wiring this up

`@EnableConfigurationProperties({A.class, B.class})` (the array form, naming two
`@ConfigurationProperties` classes at once) throws `IllegalArgumentException: Could not find
class [A]` at `@SpringBootTest` context-refresh time under Boot 4.1.1/Spring Framework 7.0.9 -
an annotation-metadata resolution failure, not a compile-time problem (it compiles cleanly, and
the referenced class exists and is on the classpath). Reproduced consistently, isolated to the
array form specifically: two *separate* single-class `@EnableConfigurationProperties(A.class)`
declarations (on different `@Configuration` classes, or the same class registering only one and
relying on another class elsewhere in the same module to register the other) work correctly -
Spring dedupes `@ConfigurationProperties` bean registration by type regardless of how many
places declare it. Every config class in this phase that needs more than one
`@ConfigurationProperties` type now uses the single-class form only; `InternalAuthConfig`
(hub-gateway) and each domain service's `InternalAuthFilterConfig` exist specifically to own
`InternalAuthProperties`' registration so the client configs don't have to.
