# ADR-0004: Keycloak hostname pinning (KC_HOSTNAME)

Status: Accepted (phase 5)

## Problem

Keycloak 26's hostname-v2 provider derives the `iss` (issuer) claim it puts in every token from
whatever hostname/port the *caller* used to reach it, unless pinned. `docker-compose.yml` maps
Keycloak's container port 8080 to host port 8190 (`8190:8080`, to avoid a local port clash). A
token fetched from the host machine via `http://localhost:8190/...` would carry
`iss=http://localhost:8190/realms/insurancehub`. `hub-gateway`'s configured
`issuer-uri` is the container-network address, `http://keycloak:8080/realms/insurancehub` — same
realm, same signing key, but Spring Security's `JwtIssuerValidator` rejects on the exact-string
mismatch regardless.

## Decision

Pin `KC_HOSTNAME` on the `keycloak` service to a full URL: `http://keycloak:8080`.

A bare hostname (`KC_HOSTNAME: keycloak`) was tried first and confirmed, against the running
container, to be insufficient: it pins the *hostname* but still reflects the caller's own port
into the issuer — `curl http://localhost:8190/realms/insurancehub/.well-known/openid-configuration`
showed `"issuer":"http://keycloak:8190/..."` (the host-mapped port leaking through). Only a full
URL pins both hostname and port together. Verified against the running container afterward:
the same discovery-document request, and a real client-credentials token's own decoded `iss`
claim, both showed `http://keycloak:8080/realms/insurancehub` — correct regardless of which
host-mapped port a client used to reach it.

## Consequences

- `send-sample.sh` (phase 6) is unaffected: it still fetches tokens via the only externally
  reachable address, `http://localhost:8190/...` — only the token's *contents* change (the
  correct, gateway-acceptable issuer), not how it's fetched.
- Integration tests never hit this problem at all and needed no equivalent pinning: a
  `@SpringBootTest` IT and the embedded gateway it exercises run in one JVM, both reaching a
  Testcontainers-managed Keycloak via the exact same `localhost:{mappedPort}` URL (wired via
  `@DynamicPropertySource` for `issuer-uri`, and read the same way by the test's own
  `fetchToken()` helper). There's no cross-network-boundary split the way docker-compose has
  one, so the issuer-mismatch problem this ADR fixes simply doesn't arise for tests.
- If the host-mapped port (currently 8190) ever changes, this pin does not need to change with
  it — `KC_HOSTNAME` only affects what Keycloak itself claims as its address, not how it's
  reached from outside Docker.
