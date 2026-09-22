# Cross-cutting concerns

## Contents
1. Error handling
2. Logging, tracing, metrics
3. Configuration and secrets
4. Security
5. Crypto (JWS/JWE)
6. Resilience
7. Idempotency implementation

## 1. Error handling

Domain code throws typed exceptions that carry a `HubErrorCode`
(`HubBusinessException(HubErrorCode code, String safeDetail)`). Services never throw
`RuntimeException("something")` for an expected business failure.

Each domain service has a `@RestControllerAdvice` that returns a `ProblemDetail`:
```java
@ExceptionHandler(HubBusinessException.class)
ProblemDetail handle(HubBusinessException ex) {
    var pd = ProblemDetail.forStatusAndDetail(ex.code().httpStatus(), ex.safeDetail());
    pd.setProperty("code", ex.code().name());
    return pd;
}
```
Map `MethodArgumentNotValidException` → `VALIDATION_FAILED`, with field names only and never
the rejected values (they can be PII). Map `ObjectOptimisticLockingFailureException` → 409 with
a retryable hint. Map anything else → `INTERNAL_ERROR`, logged at ERROR with its stack trace.
The client gets a generic message only.

In the gateway, `DownstreamErrorDecoder` reads the ProblemDetail's `code` and rebuilds a
`HubResponse`. A timeout or open circuit becomes `DOWNSTREAM_UNAVAILABLE`. Business 4xx errors
are logged at WARN, 5xx at ERROR.

## 2. Logging, tracing, metrics

Log with SLF4J only, to stdout only. In `dev`/`prod`, use Spring Boot structured JSON console
logging in logstash format. In `local`, use human-readable logs. How these logs are shipped,
searched and alarmed on in AWS is in `logging-and-monitoring.md`.

The MDC keys `traceId`, `spanId`, `txnId`, `reqId`, `inspId`, `serviceType` are set by
`CorrelationFilter` in hub-common (a servlet filter plus a `RestClient` interceptor that forwards
the `X-*` headers). Clear the MDC in `finally`.

What to log: one INFO line per request at the gateway (code, respCode, latency); one INFO line
per business state change in the services ("policy created", "claim status
UNDER_PROCESS→CLOSED"); WARN for business rejections; ERROR for unexpected failures. Don't log
per-row inside loops, and never log payloads (see SKILL rule 4). `PiiMasker.mask("9876543210")`
→ `98******10`.

Tracing: Micrometer Tracing with the OpenTelemetry bridge and OTLP exporter. Locally, export to
`grafana/otel-lgtm` (Grafana on :3000). In AWS, export to the ADOT collector sidecar → X-Ray,
or keep OTLP to a managed Grafana. Sampling: 100% locally, 10% in prod. Kafka producer
observation must be enabled so traces continue through the outbox relay (store `traceparent`
in the outbox row).

Metrics: Actuator + Micrometer Prometheus registry. Add custom counters:
`hub_requests_total{serviceType,respCode,inspId}`, `policy_created_total`,
`claim_status_changed_total{to}`, `outbox_pending` (gauge). Expose health groups `liveness` and
`readiness`. Readiness includes the DB and, in the gateway, the key registry being loaded.

## 3. Configuration and secrets

Profiles: `local` (compose, crypto may be off), `test` (Testcontainers), `dev`, `prod`
(AWS). Keep `application.yml` for defaults plus `application-<profile>.yml` per profile.

Bind all custom settings through typed `@ConfigurationProperties` records with `@Validated`,
for example `hub.insurers`, `hub.crypto`, `claims.status`, `clients.policy-service.base-url`,
and timeouts. No `@Value` scattered across the code.

Secrets: locally from env vars / `.env` (git-ignored). In AWS, from Secrets Manager
(bank private key, DB passwords, Keycloak/Cognito client secrets) and Parameter Store
(non-secret config), loaded via Spring Cloud AWS
`spring.config.import=optional:aws-secretsmanager:...,optional:aws-parameterstore:...`.
ECS task roles grant read access to that service's own secrets only.

A Config Server is NOT used. It would be one more thing to run, and Parameter Store covers the
need. Record this in an ADR.

## 4. Security

The gateway is an OAuth2 resource server (JWT). Locally the issuer is Keycloak with a realm
`insurancehub`, one confidential client per insurer, and a client scope `Insurance`. In AWS,
use Cognito (client_credentials with a resource-server scope) or keep Keycloak on ECS. The issuer
URI is config-only.

Internal services are not reachable from the internet (private subnets, security groups allow
only the gateway and claims→policy). For defense in depth, internal services also validate a
short-lived service JWT or require `X-Internal-Auth`. Start with network isolation plus
the security groups, add service JWTs in phase 8, and record the choice in an ADR.

Other headers and settings: `Strict-Transport-Security` at the ALB/gateway, and a request body
size limit of 256 KB. Actuator is exposed on the management port only, with
health/info/prometheus.

## 5. Crypto (JWS/JWE) — hub-gateway `crypto` package

Use Nimbus JOSE+JWT. Don't hand-roll AES/RSA for the envelope.

```java
// Inbound
String jws = new String(Base64.getDecoder().decode(enc), UTF_8);
JWSObject signed = JWSObject.parse(jws);
if (!signed.verify(new RSASSAVerifier(insurerPublicKey))) throw SIGNATURE_INVALID;
JWEObject jwe = JWEObject.parse(signed.getPayload().toString());
jwe.decrypt(new RSADecrypter(bankPrivateKey));             // RSA-OAEP-256 + A256GCM
String json = jwe.getPayload().toString();

// Outbound
JWEObject outJwe = new JWEObject(new JWEHeader(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM),
                                 new Payload(responseJson));
outJwe.encrypt(new RSAEncrypter(insurerPublicKey));
JWSObject outJws = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(bankKid).build(),
                                 new Payload(outJwe.serialize()));
outJws.sign(new RSASSASigner(bankPrivateKey));
return Base64.getEncoder().encodeToString(outJws.serialize().getBytes(UTF_8));
```

Before verifying, check that the header algorithms are exactly RS256 / RSA-OAEP-256 / A256GCM.
Reject anything else (e.g., `none` or HS256) to block algorithm-confusion attacks.

`KeyRegistry` loads the bank private key once at startup (PEM from Secrets Manager or a file
path locally) and the insurer public keys (X.509 PEM) by `inspId`. Support two bank keys by
`kid` for rotation.

`scripts/gen-dev-keys.sh` generates the dev bank keypair plus one keypair per sample insurer
into `.secrets/`. `scripts/send-sample.sh` gets a Keycloak token, encrypts a sample with the
insurer's private key, calls the gateway, and decrypts the response. Put the encryption logic
in a small test-support class shared with the ITs, not in production code.

Tests: round-trip, tampered ciphertext → `DECRYPTION_FAILED`, wrong signer →
`SIGNATURE_INVALID`, disallowed alg → rejected, valid signature but `inspId` mismatch →
`INSURER_MISMATCH`.

## 6. Resilience

Use `RestClient` with a JDK or Apache HTTP client and explicit timeouts. Start with connect 2 s
and read 5 s, and put them in config.

Resilience4j per client (`policyService`, `claimsService`):
circuit breaker (50% failures over a 20-call window, 30 s open), retry (3 attempts, exponential
backoff 200 ms × 2, retrying only on connect errors, timeouts and 503), and a bulkhead on the
gateway's clients.

Retry is safe here only because every write is idempotent on `(inspId, reqId)`. That's the
reason rule 3 exists. Never retry on 4xx.

In a Kubernetes/ECS rolling deploy, graceful shutdown (`server.shutdown=graceful`) plus the
readiness probe prevents dropped requests.

## 7. Idempotency implementation (both domain services)

```java
@Transactional
public CreatePolicyResult create(CreatePolicyCommand cmd) {
    var existing = processedRequests.find(cmd.inspId(), cmd.reqId());
    if (existing.isPresent()) return CreatePolicyResult.replay(existing.get().txnId());
    if (policies.existsByPolicyNum(cmd.policyNum())) throw new HubBusinessException(POLICY_ALREADY_EXISTS, cmd.policyNum());
    var policy = policies.save(Policy.newFrom(cmd));
    processedRequests.save(ProcessedRequest.of(cmd, policy.getPolicyNum()));
    outbox.append(PolicyCreated.from(policy, cmd));
    return CreatePolicyResult.created(cmd.txnId());
}
```
Wrap the call in an application-level retry that catches `DataIntegrityViolationException` on
the `uk_processed_request` constraint and re-runs the lookup, so the losing side of a
concurrent race returns the replay result instead of a 500. Test this with two threads and a
`CountDownLatch`.
