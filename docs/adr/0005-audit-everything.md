# ADR-0005: request_audit gets a row for every request, including pre-trust rejections

Status: Accepted (phase 5)

## Decision

Every request that reaches `hub-gateway` writes exactly one `request_audit` row — success,
business rejection, or pre-trust rejection (bad token, IP not allowed, oversized body) alike.
`req_id`, `insp_id` and `service_type` are nullable columns, since a pre-trust rejection never
resolves them; `txn_id`, `resp_code`, `latency_ms` and `client_ip` are never null.

This was reversed from an earlier draft, which wrote no audit row at all for pre-trust
rejections (reasoning: nothing meaningful was known yet). The forensic value of "who tried
what, from where, and got rejected" outweighs the minor awkwardness of three nullable columns —
a bad-token or IP-not-allowed attempt is exactly the kind of event worth a durable record, not
just a log line subject to a 14–90 day retention window. A single `RequestAuditFilter`,
registered outside Spring Security's own filter chain (right after `CorrelationFilter`, before
everything else), wraps the *entire* pipeline in try/finally and is the only thing that ever
writes a row — every other pipeline stage only populates a shared, request-scoped `AuditContext`
as it learns things (`inspId` once the JWT resolves, `reqId`/`serviceType` once the body
decrypts and dispatch resolves the code).

## Consequences

- **No payload column exists, structurally.** `RequestAuditService.record(...)` takes seven
  scalar parameters — never a request or response object — so it is not merely a convention that
  no payload reaches the table; it is impossible to pass one in. `RequestAuditTest` asserts the
  exact field list via reflection specifically so a future payload-shaped field addition fails a
  test immediately, not eventually.
- **An audit-write failure can never turn a real answer into a 500.** `filterChain.doFilter()`
  completes — the response is fully written and its status committed — strictly before the
  audit write is even attempted, inside the filter's `finally` block. The write itself is
  wrapped in its own try/catch: a failure logs ERROR with the audit fields as structured
  key-values and increments a `hub_gateway.audit.write.failures` counter, but the client-visible
  response is unaffected either way.
- **Ordering is deliberate, not incidental.** `HubDispatcher` resolves the service code and
  performs the insurer-identity check (`header.inspId` vs. the token-resolved `AuditContext`
  insurer) *before* field validation ever runs, so `AuditContext.reqId`/`serviceType` are
  populated as early as the pipeline can honestly know them - but a pre-trust rejection that
  happens even earlier (auth, IP allowlist, body size) still produces a complete, correctly
  timed row with those three fields left null.
