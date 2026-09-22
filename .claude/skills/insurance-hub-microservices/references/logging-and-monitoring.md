# Centralized logging and monitoring (AWS CloudWatch)

Read this for phase 8 (app side) and phase 9 (AWS side). It works together with the logging
rules in `cross-cutting.md` section 2.

## Contents
1. Compute decision (why ECS Fargate)
2. Log pipeline
3. Application log format
4. Log groups, retention, encryption
5. PII protection in the cloud
6. Logs Insights saved queries
7. Metric filters, alarms, notifications
8. Traces and metrics
9. Other AWS log sources
10. Local parity
11. Terraform checklist

## 1. Compute decision (why ECS Fargate)

| Option | Verdict | Reason |
|---|---|---|
| Lambda | Rejected | Outbox relay needs an always-on scheduler; Spring context + key loading on cold start adds latency to bank calls; RDS pool behaviour under Lambda concurrency |
| EC2 + Compose | Rejected for dev/prod | Manual OS patching, scaling, self-healing, rolling deploys, CloudWatch agent install |
| EKS | Deferred | ~$73/month control plane + Kubernetes operational load; migration path kept open |
| ECS Fargate | Chosen | No servers to manage, rolling deploys, autoscaling, Service Connect, a task role per service, native CloudWatch log driver |

Images go to ECR. Record the choice as `docs/adr/0004-compute-ecs-fargate.md`.

## 2. Log pipeline

```
Spring Boot (JSON to stdout)
   → ECS awslogs driver (non-blocking)
       → CloudWatch Logs: /insurancehub/<env>/<service>
            ├─ Logs Insights (saved queries, cross-service search by txnId)
            ├─ Metric filters → CloudWatch alarms → SNS → email / Slack (via Chatbot)
            ├─ Data protection policy (PII masking)
            └─ Subscription filter → S3 / OpenSearch (optional, long-term or analytics)
```

The app writes to stdout only. It never writes log files inside the container and never calls
the CloudWatch API directly. Container logging is the platform's job.

ECS task definition log configuration (per container):
```json
"logConfiguration": {
  "logDriver": "awslogs",
  "options": {
    "awslogs-group": "/insurancehub/prod/policy-service",
    "awslogs-region": "ap-south-1",
    "awslogs-stream-prefix": "app",
    "mode": "non-blocking",
    "max-buffer-size": "25m"
  }
}
```
Why non-blocking: in the default blocking mode, if delivery to CloudWatch stalls, stdout writes
block and request threads hang. Non-blocking drops logs only when the buffer overflows, which is
the right trade-off for a payment-adjacent API. Use FireLens (Fluent Bit) only if we later need
to route logs to more than one destination. Record that in the ADR, don't build it.

Region: use `ap-south-1` (Mumbai), or whatever region the bank mandates for data residency.
Make it a Terraform variable.

## 3. Application log format

Use Spring Boot structured logging in `dev`/`prod`: `logging.structured.format.console=logstash`.
Logstash format is chosen over ECS because MDC entries become top-level JSON keys (`txnId`,
`reqId`, `inspId`, `traceId`). CloudWatch Logs Insights discovers top-level JSON fields
automatically, so queries like `filter txnId = "..."` work without parsing. Verify the property
names for the Boot version in use.

Every line must contain: `@timestamp`, `level`, `logger_name`, `message`, `traceId`,
`spanId`, `txnId`, `reqId`, `inspId`, `serviceType`. Business-event lines also add `respCode`
and `latencyMs` (gateway), or `event` (e.g., `POLICY_CREATED`, `CLAIM_STATUS_CHANGED`) and
`fromStatus`/`toStatus` (claims). Add these as structured key-values
(`log.atInfo().addKeyValue("respCode", code).log("request completed")`), not string-concatenated,
so they become queryable fields.

Log levels: INFO in prod, DEBUG only via a temporary override through Parameter Store plus the
Actuator `loggers` endpoint (management port only), never permanently. Stack traces are allowed
at ERROR. Spring's JSON output puts the stack trace inside the event, so a single error is not
split across many CloudWatch events.

## 4. Log groups, retention, encryption

| Log group | Retention | Contents |
|---|---|---|
| `/insurancehub/<env>/hub-gateway` | dev 14 d, prod 90 d | App logs |
| `/insurancehub/<env>/policy-service` | dev 14 d, prod 90 d | App logs |
| `/insurancehub/<env>/claims-service` | dev 14 d, prod 90 d | App logs |
| `/insurancehub/<env>/audit` | prod per bank policy (default 1 year, then S3 archive) | Gateway request audit events |
| `/aws/ecs/containerinsights/<cluster>/performance` | 30 d | Container Insights metrics |
| `/aws/rds/instance/<id>/error`, `/slowquery` | 30 d | RDS exports |
| `/aws/msk/<cluster>` | 30 d | MSK broker logs (if MSK) |

The audit stream: besides the `request_audit` table, the gateway writes one structured
`AUDIT` log line per request through a dedicated logger (`insurancehub.audit`). A separate ECS
log option doesn't exist per logger, so route it with a subscription filter on
`{ $.logger_name = "insurancehub.audit" }` into the audit group via a small Lambda, or accept it
in the app group with a longer retention. Pick the simpler option first and record it.

Encrypt every log group with a customer-managed KMS key (`kms_key_id`). Retention is always set
explicitly. CloudWatch's default is "never expire", which quietly grows the bill.

## 5. PII protection in the cloud

Layer 1 is the app. `PiiMasker` and the no-payload rule (SKILL rule 4) are the real control.

Layer 2 is a CloudWatch Logs data protection policy on every app log group. It audits and masks
matching data identifiers (managed identifiers such as phone numbers, plus custom regex
identifiers for our `CIF\d+`, account-number and loan-account patterns). It's a safety net for
anything the app misses, and it also produces findings you can alarm on. Findings mean a
masking bug in the app. Fix the code, don't just rely on the policy.

IAM: only an `insurancehub-log-readers` role can run Insights queries on prod log groups. The
permission to unmask (`logs:Unmask`) is not granted to anyone by default.

## 6. Logs Insights saved queries (create via `aws_cloudwatch_query_definition`)

Trace one request across all services (select all three app log groups):
```
fields @timestamp, @log, level, message, respCode
| filter txnId = "<txnId>"
| sort @timestamp asc
```

Errors in the last hour, grouped:
```
fields @timestamp, @log, message
| filter level = "ERROR"
| stats count(*) as errors by @log, logger_name
| sort errors desc
```

Business rejections by code and insurer (gateway):
```
filter logger_name like /HubRequestLogger/ and respCode != "200"
| stats count(*) by respCode, inspId, serviceType
```

Latency p50/p95/p99 by service code (gateway):
```
filter ispresent(latencyMs)
| stats pct(latencyMs, 50) as p50, pct(latencyMs, 95) as p95, pct(latencyMs, 99) as p99 by serviceType
```

Everything that happened for one insurer's request id (support ticket lookup):
```
fields @timestamp, @log, message | filter inspId = "INSP001" and reqId = "<reqId>" | sort @timestamp asc
```

Claim status changes today:
```
filter event = "CLAIM_STATUS_CHANGED" | stats count(*) by fromStatus, toStatus
```

## 7. Metric filters, alarms, notifications

Metric filters (namespace `InsuranceHub/<env>`):

| Filter pattern | Metric | Alarm (prod) |
|---|---|---|
| `{ $.level = "ERROR" }` per service | `ErrorCount` | ≥ 5 in 5 min |
| `{ $.respCode = "5*" }` gateway | `Gateway5xx` | ≥ 3 in 5 min |
| `{ $.respCode = "401" \|\| $.respCode = "403" }` | `AuthFailures` | ≥ 20 in 5 min (possible attack or misconfigured insurer) |
| `{ $.errorCode = "SIGNATURE_INVALID" \|\| $.errorCode = "DECRYPTION_FAILED" }` | `CryptoFailures` | ≥ 5 in 5 min |
| `{ $.errorCode = "DOWNSTREAM_UNAVAILABLE" }` | `CircuitOpen` | ≥ 1 |

Make sure the gateway logs `errorCode` (the `HubErrorCode` name) as a field so these patterns
match.

Also alarm on these directly: ALB `HTTPCode_Target_5XX_Count` and `TargetResponseTime` p95; ECS
service `CPUUtilization` > 80% and `RunningTaskCount` < desired; RDS `CPUUtilization`,
`FreeStorageSpace` and `DatabaseConnections`; the custom `outbox_pending` metric > 1000 for 10 min
(events not flowing); MSK consumer lag if consumers exist.

All alarms go to the SNS topic `insurancehub-<env>-alerts` → email, plus AWS Chatbot for Slack or
Teams if available. Every alarm description links to the matching saved query name.

Dashboard `InsuranceHub-<env>` (via `aws_cloudwatch_dashboard`): request rate and 4xx/5xx by
serviceType, p95 latency, error count per service, ECS CPU/memory per service, RDS connections,
outbox_pending, plus a Logs Insights widget showing the latest ERROR lines.

## 8. Traces and metrics

Traces: run an ADOT (AWS Distro for OpenTelemetry) collector as a sidecar container in each ECS
task. The app exports OTLP to `localhost:4317`, and the collector forwards to AWS X-Ray. Because
`traceId` is also in every log line, you can jump from an X-Ray trace to its logs by querying
`filter traceId = "..."`. Sampling is 10% in prod, but always sample errors (configure a
tail-based rule in the collector, or at least log the traceId on every error line).

App metrics: publish Micrometer metrics through the same ADOT collector to CloudWatch (EMF) or
Amazon Managed Prometheus. Enable ECS Container Insights on the cluster for CPU, memory and
network per task with no code changes.

## 9. Other AWS log sources

ALB access logs → S3 bucket (ALB can't write to CloudWatch), with a 90-day lifecycle; query with
Athena when needed. CloudTrail → an org or account trail for API audit (who changed what in AWS).
VPC Flow Logs → CloudWatch, rejected traffic only in dev, to debug security groups. WAF logs (if
WAF is used) → CloudWatch. RDS error/slow-query logs and MSK broker logs → CloudWatch (see the
table in section 4).

## 10. Local parity

Locally, logs are human-readable on the console and traces/metrics go to `grafana/otel-lgtm`
(Loki, Tempo, Prometheus). Set `SPRING_PROFILES_ACTIVE=local,json-logs` to test the exact prod
JSON format locally and pipe it through `jq`. Add an integration test that captures log output
(Spring's `OutputCaptureExtension`) and asserts that a request produces JSON lines containing
`txnId`, `reqId` and `traceId`, and never the raw `mobileNum` or `accountNum` from the fixture.

## 11. Terraform checklist (`infra/terraform/modules/observability/`)

`aws_kms_key` (logs), `aws_cloudwatch_log_group` × N (retention + KMS),
`aws_cloudwatch_log_data_protection_policy` per app group, `aws_cloudwatch_query_definition` ×
the queries above, `aws_cloudwatch_log_metric_filter` × the filters above,
`aws_cloudwatch_metric_alarm` × the alarms, `aws_sns_topic` + subscriptions,
`aws_cloudwatch_dashboard`, `aws_ecs_cluster` with the `containerInsights` setting enabled, an S3
bucket + policy for ALB access logs, and an IAM role for log readers. Each ECS task role gets
only `logs:CreateLogStream` / `logs:PutLogEvents` on its own group. With the awslogs driver these
permissions sit on the task execution role.
