# Open questions for the bank

Spec gaps hit during implementation, with the assumption made in the meantime. Confirm with
the bank and update/remove entries as they're answered. Seeded from
`references/api-contract.md` §8 before phase 1; assumptions get revisited as each phase
actually implements the affected behavior.

## Q1 — Official respCodes for crypto/IP/HMAC failures
Not finalized by the bank. **Assumption**: use the provisional codes in the `HubErrorCode`
table (`api-contract.md` §5) — 400 for signature/decryption failures, 401 for token issues,
403 for IP/insurer mismatch — until the bank confirms its own catalogue. Affects phase 6.

## Q2 — HTTP status vs. always-200 with a body error code
**Assumption**: HTTP status mirrors `respCode` (200/4xx/5xx), per `api-contract.md` §4 — the
body always carries `respCode` too, so a client reading only the body still works either way.
Affects phase 5 (gateway response mapping).

## Q3 — Allowed `claimStatus` values and transitions
Not finalized by the bank. **Assumption**: use `service-design.md` §3's sample transitions map
verbatim (terminal: `CLOSED`/`REPUDIATED`/`CANCELLED`; `REGISTERED` →
`UNDER_PROCESS`/`CLOSED`/`REPUDIATED`/`CANCELLED`; `UNDER_PROCESS` →
`UNDER_PROCESS`/`REQUIREMENT_PENDING`/`CLOSED`/`REPUDIATED`/`CANCELLED`;
`REQUIREMENT_PENDING` → `UNDER_PROCESS`/`CLOSED`/`REPUDIATED`/`CANCELLED`), config-driven via
`claims.status` (`ClaimStatusPolicy`), not hardcoded — easy to correct once the bank confirms
its real state machine.

**Sub-decision — same-status transitions** (e.g. `UNDER_PROCESS` → `UNDER_PROCESS`): not
special-cased as a no-op or auto-rejected. Governed by the exact same config-driven check as
every other transition. The sample config above already answers this: `UNDER_PROCESS` lists
itself as an allowed target (re-affirming a claim still under process — e.g. while updating
`claimCode` or settlement fields — is a legitimate, distinct event worth a history row), while
`REGISTERED` and `REQUIREMENT_PENDING` do not list themselves, so those same-status attempts
are rejected as `INVALID_STATUS_TRANSITION` like any other disallowed target. Affects phase 4b
(`ClaimStatusPolicy`/`ClaimStatusUpdateService`).

## Q4 — Is `reqId` unique per insurer or globally?
**Assumption**: per insurer. The idempotency key is `(inspId, reqId)`, not `reqId` alone
(CLAUDE.md rule 3). Affects phases 2–4 (`processed_request` unique constraint).

## Q5 — Must `grossPremium == netPremium + gstAmt` be enforced?
**Assumption**: no — log a warning only, don't reject the request. Affects phase 2 (create)
and phase 3 (renew).

## Q6 — Must `claimedAmt <= sumInsured`?
**Assumption**: not enforced. Affects phase 4 (claim registration).

## Q7 — Is the "HMAC" in the error list separate from the JWS signature?
**Assumption**: treat it as the same check as JWS signature verification
(`SIGNATURE_INVALID`) — no separate HMAC error code added — until the bank clarifies whether
a distinct HMAC step exists. Affects phase 6.

## Q8 — Can a renewal change `sumInsured` or the insured's details?
**Assumption**: yes — allowed, and term history is kept (each renewal is a new term row, not
an overwrite). Affects phase 3 (renewal schema and business rules).

## Q9 — How is a lapsed policy represented?
**Decision**: lapse is not a stored status. There's no `LAPSED` column, no scheduled job that
flips one, and no cap on how long a gap between terms can be. The coverage endpoint already
answers "is this policy active on date X" by checking whether any term's
`[startDate, expiryDate]` covers that date, so a gap between two terms is simply a stretch of
dates no term covers — `active=false` falls out of the date-range query, not a stored flag.
`service-design.md` §2 already permits "allow a gap (lapsed then renewed)"; the spec gives no
maximum-lapse-duration rule, so none is invented. Affects phase 3 (renewal/coverage) and any
future phase that reports policy status — a "lapsed" read model, if ever needed, would compute
it from term ranges at query time, not from stored state.

## Q10 — Is `CONCURRENT_UPDATE` (409) an acceptable respCode for the bank, or should it fold
into an existing code?
**Assumption**: `CONCURRENT_UPDATE` is a code this project invented (`cross-cutting.md` §1
specifies the *behavior* - map `ObjectOptimisticLockingFailureException` to "409 with a
retryable hint" - but not a wire name for it), not one in `api-contract.md` §5's bank-defined
catalogue. Every non-catalogue code is an assumption about what the bank's error handling
expects, exactly like `RENEWAL_NOT_ALLOWED` before it: the bank may want 409s folded into a
smaller set of generic codes, may already have their own name for this case, or may not expect
a 409 here at all (e.g. if they'd rather the gateway retry once internally, per
`cross-cutting.md` §6's retry-on-idempotent-writes note, and only surface a failure after that
retry too fails). Until confirmed, `CONCURRENT_UPDATE`/409 stands for any service that mutates
a `@Version`-tracked row concurrently. Affects phase 3 (renewal) and phase 4 (claim status
update, which has the same optimistic-locking exposure) - **any new project-defined error code
future phases add needs the same treatment**: a numbered entry here before it ships, not just
an enum addition.

## Q11 — Is `INSUFFICIENT_SCOPE` (403) an acceptable respCode for a token missing the
`Insurance` scope?
**Assumption**: project-defined, same treatment as Q10's `CONCURRENT_UPDATE`. A token that's
otherwise valid but lacks the required scope is "authenticated, not authorized" - distinct from
`TOKEN_INVALID`/`TOKEN_EXPIRED` (401, "not authenticated at all"). Folding it into
`TOKEN_INVALID` was considered and rejected: that would mean returning HTTP 403 (the
conventional status for `insufficient_scope` per RFC 6750) with a body claiming respCode 401,
which `api-contract.md` §4's "HTTP status mirrors respCode" rule explicitly forbids - body and
status must never disagree. Affects phase 5 (gateway OAuth2 scope check).

## Q12 — Is `PAYLOAD_TOO_LARGE` (413) an acceptable respCode for the 256 KB body limit?
**Assumption**: project-defined, same treatment as Q10/Q11. `cross-cutting.md` §4 specifies the
limit but not a wire error code for exceeding it. Affects phase 5 (`RequestBodySizeFilter`).

## Q13 — Real per-insurer IP allowlists (CIDRs)
Not supplied by the spec at all - `api-contract.md` §1 says each `hub.insurers[]` entry "holds
the insurer's... IP allowlist" but the spec gives no actual ranges for INSP001/002/003.
**Assumption**: `0.0.0.0/0` (allow-all) as an explicit placeholder in `local`/`test` config
only - `HubStartupGuard` fails startup if this placeholder is still present in `dev`/`prod`, so
it can't silently ship. Affects phase 5 (`IpAllowlistFilter`); real CIDRs must replace the
placeholder before any non-local deployment.

## Q14 — JWT clock skew tolerance for token expiry
Not specified anywhere in the bank's docs. **Assumption**: 5 seconds (down from Spring Security's
own 60-second default), enough for ordinary clock drift between the gateway and Keycloak without
meaningfully extending how long an expired token keeps working. Found via
`HubGatewaySecurityIT.expiredTokenIsRejectedAsTokenExpired` against real Keycloak: with the
60-second default, a token that had expired 3 seconds earlier was still authenticating
successfully. Affects phase 5 (`SecurityConfig`'s `JwtDecoder` bean).

## Q15 — `traceparent` propagation is wired but not yet real (phase 7 scope boundary) — CLOSED
Closed in phase 8: Micrometer Tracing now sends a real `traceparent` header on hub-gateway's
downstream calls (verified via `HubGatewayDispatchIT
.policyServiceCallCarriesAnInternalAuthHeaderAndATraceparentHeader`, a real WireMock-captured
header, not just config), and it reaches `OutboxAppender`/`OutboxRelay` with zero code changes,
exactly as this entry originally promised. See `docs/adr/0008-observability.md` for the one real
fix that was needed (manually-built `RestClient`s bypassing Micrometer's instrumentation).

## Q16 — `INTERNAL_AUTH_FAILED` (401) is a project-defined respCode

**Assumption**: project-defined, same treatment as Q10/Q11/Q12. Not in the bank's
`api-contract.md` §5 catalogue — it can never reach an insurer anyway (it's only ever returned
by policy-service/claims-service's internal `/internal/**` endpoints to hub-gateway itself, per
`docs/adr/0007-internal-service-auth.md`; a real occurrence means a misconfigured
`INTERNAL_AUTH_SECRET`, not something an insurer request can trigger), but recorded here per the
standing rule anyway. Affects phase 8 (`InternalAuthFilter`, both domain services).

## Q17 — Tracing sample rate in `prod` is a deferred property, not yet wired to a real profile

Not a spec gap - `cross-cutting.md` §2 says "100% locally, 10% in prod," and phase 8 sets
`management.tracing.sampling.probability: 1.0` in all three services' base `application.yml`
(the only profiles that exist today are `local`/`test`). No `application-prod.yml` exists yet -
phase 9 creates it, and that is when the 10% override should be added
(`management.tracing.sampling.probability: 0.1`). Flagged here so it isn't silently forgotten
when phase 9 scaffolds the prod profile. Affects phase 9.
