# Service design

## Contents
1. Internal API conventions
2. policy-service
3. claims-service
4. hub-gateway internals
5. Events and outbox

## 1. Internal API conventions

Internal APIs are called only by hub-gateway (and claims → policy). They are not exposed on
the public ALB. Use ISO dates (`yyyy-MM-dd`), `BigDecimal` amounts, and enums where the value set
is known.

Every internal request carries these headers, set by the gateway and propagated automatically
by a `RestClient` interceptor: `X-Req-Id`, `X-Insp-Id`, `X-Txn-Id`, plus W3C `traceparent`
(Micrometer adds it).

Internal success response: `201`/`200` with
`{ "txnId": "...", "replayed": false, ...resource fields }`. Internal error: RFC 9457
`ProblemDetail` with an extra property `code` = a `HubErrorCode` name. The gateway maps `code`
to the external `respCode`/`errorDesc`. Never return a stack trace.

## 2. policy-service

### API
| Method | Path | Purpose |
|---|---|---|
| POST | `/internal/policies` | 01 create policy + first term |
| POST | `/internal/policies/{policyNum}/renewals` | 02 add renewal term |
| GET | `/internal/policies/{policyNum}/coverage?onDate=yyyy-MM-dd` | Used by claims: is a term active on that date? |
| GET | `/internal/policies/{policyNum}` | Read model (support/debug) |

Coverage response: `{ policyNum, active, termStart, termExpiry, sumInsured, insuranceType }`.
Return `404 POLICY_NOT_FOUND` if the policy doesn't exist, and `active=false` if it exists but
no term covers that date.

### Schema (Flyway `V1__init.sql`)
```
policy          id PK, policy_num UNIQUE, insp_id, application_num, cif, account_num,
                insured_name, mobile_num, address, insurance_type, insurance_name,
                region_code, region_name, branch_code, branch_name, loan_acct_num,
                spec_per_num, spec_per_name, created_at, updated_at, version
policy_term     id PK, policy_id FK, term_no, term_type (NEW|RENEWAL), application_status,
                issue_date, start_date, expiry_date, net_premium DECIMAL(15,2),
                gst_amt, gross_premium, sum_insured, commission_per DECIMAL(5,2),
                commission_amt, req_id, txn_id, created_at
                UNIQUE (policy_id, term_no)
processed_request  id PK, insp_id, req_id, service_type, txn_id, resource_key, created_at
                UNIQUE (insp_id, req_id)
outbox_event    see section 5
```
`version` supports optimistic locking (`@Version`). Encrypting `cif`, `account_num`,
`loan_acct_num` and `mobile_num` at rest via a JPA `AttributeConverter` (AES-256-GCM, key from
Secrets Manager) is a phase-8 hardening item. Design the columns wide enough now
(VARCHAR(512)).

### Rules
**01 Create:** First, check `processed_request` for `(inspId, reqId)`. If it exists, return the
stored txnId with `replayed=true`. Otherwise, if `policy_num` exists → `POLICY_ALREADY_EXISTS`.
Otherwise insert policy + term 1 (NEW) + processed_request + outbox `PolicyCreated`, all in ONE
local transaction. The unique constraint is the real guard. Catch
`DataIntegrityViolationException` on `processed_request` and re-read it to return the replay
result, because two concurrent retries can race past the check.

**02 Renew:** Same idempotency. The policy must exist (`POLICY_NOT_FOUND`). The new term's
`start_date` must be after the latest term's `start_date`, and the new term must not overlap an
existing term (`RENEWAL_NOT_ALLOWED`). Allow a gap (lapsed then renewed). Update the insured's
details on the policy row if they changed. Add a term with `term_no = max + 1` and emit
`PolicyRenewed`.

## 3. claims-service

### API
| Method | Path | Purpose |
|---|---|---|
| POST | `/internal/claims` | 03 register claim |
| PATCH | `/internal/claims/{claimNum}/status` | 04 status update |
| GET | `/internal/claims/{claimNum}` | Read model incl. history |

### Schema
```
claim           id PK, claim_num UNIQUE, policy_num, insp_id, claim_type, loss_desc,
                nature_of_loss, loss_city, date_of_loss, intimation_date, claimed_amt,
                settled_amt, claim_status, claim_code, finalization_date, os_ageing INT,
                require_details TEXT, repu_cancel_date, reason_of_closure,
                created_at, updated_at, version
claim_status_history  id PK, claim_id FK, from_status, to_status, claim_code,
                settled_amt, changed_at, req_id, txn_id
processed_request, outbox_event   same as policy-service
```

### Rules
**03 Register:** Check idempotency first. Then call policy-service coverage with
`onDate = dateOfLoss`. On 404 → `POLICY_NOT_FOUND`. If `active=false` → `POLICY_NOT_ACTIVE`. If
`claim_num` exists → `CLAIM_ALREADY_EXISTS`. Insert the claim with `claimStatus` from the request
(default `REGISTERED` if blank), add the first history row, and emit `ClaimRegistered`. Make the
coverage call BEFORE opening the DB transaction, so a transaction is never held open across a
network call.

**04 Status:** Check idempotency. The claim must exist and its `policy_num` must equal the
request's `policyNum` (otherwise `CLAIM_NOT_FOUND`, which doesn't reveal that the claim exists
under another policy). Validate the transition with `ClaimStatusPolicy`, which reads allowed
transitions from config:

```yaml
claims:
  status:
    terminal: [CLOSED, REPUDIATED, CANCELLED]
    transitions:            # provisional: open question Q3
      REGISTERED:    [UNDER_PROCESS, CLOSED, REPUDIATED, CANCELLED]
      UNDER_PROCESS: [UNDER_PROCESS, REQUIREMENT_PENDING, CLOSED, REPUDIATED, CANCELLED]
      REQUIREMENT_PENDING: [UNDER_PROCESS, CLOSED, REPUDIATED, CANCELLED]
```

An unknown status or a transition out of a terminal state → `INVALID_STATUS_TRANSITION`. Update
the settlement fields, add a history row, and emit `ClaimStatusChanged`.

## 4. hub-gateway internals

The request pipeline runs as filters/components in this order:

1. `CorrelationFilter` (hub-common) sets up MDC and assigns `txnId`.
2. Spring Security validates the JWT and scope `Insurance`, and resolves `inspId` from the
   client id.
3. `IpAllowlistFilter` checks the source IP against the insurer's CIDRs. Read the client IP
   from `X-Forwarded-For` only when the request arrives through the trusted ALB (configure
   `server.forward-headers-strategy=native` and the ALB's CIDR).
4. `HubCryptoService.verifyAndDecrypt` decrypts the payload into `HubRequest`, then checks that
   `header.inspId` equals the token's insurer.
5. The validator applies the group for the code.
6. `HubDispatcher` switches on code → mapper → internal client.
7. The response is mapped to `HubResponse`, then encrypted and signed.
8. `request_audit` gets a row: txnId, reqId, inspId, serviceType, respCode, latencyMs, clientIp.
   Never store the payload.

Keep the controller thin. The crypto, dispatch, mapping and audit logic each live in their own
class, so each can be tested in isolation.

## 5. Events and outbox

Why outbox: publishing to Kafka inside a DB transaction can't be atomic. A crash between
commit and send loses the event, and sending before commit can publish a rolled-back change.
So write the event to `outbox_event` in the same transaction, and have a relay publish it.

```
outbox_event  id CHAR(36) PK (UUID), aggregate_type, aggregate_id, event_type,
              payload JSON, created_at, published_at NULL, attempts INT
```

The relay is a `@Scheduled` job (every 500 ms). It selects up to 100 unpublished rows
`ORDER BY created_at FOR UPDATE SKIP LOCKED`, sends each with `KafkaTemplate` and waits for the
ack, then sets `published_at`. Delivery is at-least-once, so consumers must dedupe on `eventId`.
Debezium CDC is a later upgrade. Mention it in the ADR, but don't build it.

Topics (key = aggregate id, which gives per-policy / per-claim ordering):

| Topic | Key | Events |
|---|---|---|
| `insurancehub.policy.events.v1` | policyNum | PolicyCreated, PolicyRenewed |
| `insurancehub.claim.events.v1` | claimNum | ClaimRegistered, ClaimStatusChanged |

Event envelope (JSON):
```json
{ "eventId": "uuid", "eventType": "PolicyRenewed", "eventVersion": 1,
  "occurredAt": "2026-09-22T10:15:30Z", "aggregateId": "POL445566",
  "txnId": "...", "reqId": "...", "inspId": "INSP001", "data": { ... no raw PII ... } }
```

Event data carries identifiers and business values (dates, amounts, statuses), not the
insured's name, mobile number or account numbers. Consumers that need contact details fetch
them from the owning service.

Producer config: `acks=all`, `enable.idempotence=true`. Topics are created from code
(`NewTopic` beans) locally only. In AWS, topics are provisioned by Terraform. A
notification-service consumer is an optional phase-10 extension, with retry topics and a DLT
via `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`.
