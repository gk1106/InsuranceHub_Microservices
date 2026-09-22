# Testing and deployment

## Contents
1. Test strategy
2. Docker images
3. Local Docker Compose
4. GitLab CI/CD
5. AWS topology (phase 9)

## 1. Test strategy

| Layer | Tooling | What it proves |
|---|---|---|
| Unit | JUnit 5, AssertJ, Mockito | Domain rules: renewal overlap, status transitions, masking, mapping |
| Slice | `@WebMvcTest`, `@DataJpaTest` (with Testcontainers MySQL, not H2) | Controller validation and error mapping; repository queries and constraints |
| Integration | `@SpringBootTest` + Testcontainers (MySQL, Kafka) + WireMock | Full use case per code, idempotency race, outbox → topic |
| Architecture | ArchUnit | Layer dependencies; `domain` has no Spring web/JPA-infrastructure imports beyond annotations; no class depends on another service's package |
| E2E (phase 5+) | Separate `e2e` Maven profile against `docker compose` | Token → gateway → services → DB → Kafka for all 4 codes |

Use Testcontainers `@ServiceConnection` and reuse the containers across a test class. Don't use
H2, because MySQL behaviour (SKIP LOCKED, JSON, constraints) is part of what we're testing.

Each code gets at least: a happy path; a replay with the same reqId (same txnId, no new rows, no
new event); the main business rejection; and validation failure.

JaCoCo gate: 80% line coverage on `domain` + `application` packages. Don't chase coverage on
config/DTOs.

## 2. Docker images

Use one multi-stage Dockerfile per service. Build with `eclipse-temurin:21-jdk` and run with
`eclipse-temurin:21-jre` (or distroless java21). Use Spring Boot layered jars (`java -Djarmode=tools
-jar app.jar extract --layers --launcher`) so dependency layers cache. Run as a non-root user.
Set `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`. Add a
HEALTHCHECK only for compose; ECS uses its own. Tag images with the git SHA, never `latest`, in
CI.

## 3. Local Docker Compose

Services: `mysql` (one server; `docker/mysql/init.sql` creates `policy_db`, `claims_db`,
`gateway_db` and one user per schema with grants on its own schema only), `kafka`
(`apache/kafka` image, KRaft single node, listeners `PLAINTEXT://kafka:9092` internal and
`PLAINTEXT_HOST://localhost:29092` for the host), `keycloak` (`start-dev --import-realm` with
`docker/keycloak/insurancehub-realm.json`: clients `insp001-client`, `insp002-client`, scope
`Insurance`), `otel-lgtm` (Grafana :3000, OTLP :4317/:4318), then `policy-service`,
`claims-service` and `hub-gateway` built from the local Dockerfiles, with
`depends_on: condition: service_healthy`.

Add `make up`, `make down`, `make logs`, `make sample CODE=01` targets (or equivalent scripts)
so the whole flow runs in one command.

## 4. GitLab CI/CD (`.gitlab-ci.yml`)

Stages: `build` → `test` → `quality` → `package` → `publish` → `deploy-dev` → `deploy-prod`.

`test` runs `mvn verify` (Testcontainers needs the Docker-in-Docker service or a runner with
Docker socket). Cache `~/.m2`. `quality` runs the Spotless check, JaCoCo gate, and a dependency +
image scan (Trivy), failing on HIGH/CRITICAL with a documented allowlist. `package` builds images
per changed module. Use `rules: changes:` so a claims-only change doesn't rebuild everything,
but always rebuild everything when `pom.xml` or `hub-common/**` changes. `publish` pushes to ECR
using GitLab OIDC → an AWS IAM role (no long-lived AWS keys in CI variables). `deploy-dev`
updates the ECS service task definition image and waits for stability. `deploy-prod` is manual
and protected-branch only.

## 5. AWS topology (phase 9, Terraform in `infra/terraform/`)

```
Internet → WAF (optional) → ALB (public subnets, HTTPS, ACM cert)
             → hub-gateway (ECS Fargate, private subnets)
                 → policy-service / claims-service (ECS Fargate, private subnets,
                   discovered via ECS Service Connect: http://policy-service:8081)
             RDS MySQL (private DB subnets, Multi-AZ in prod) — separate schema + user per service
             Amazon MSK (or MSK Serverless) — IAM auth, TLS
             Secrets Manager, SSM Parameter Store, ECR, CloudWatch Logs, X-Ray/ADOT
NAT gateway (or VPC endpoints for ECR, Secrets Manager, CloudWatch, SSM to save NAT cost)
```

Why ECS Fargate (not Lambda, EC2 or EKS): see `logging-and-monitoring.md` section 1. Record it
as ADR-0004. All logging, alarms and dashboards are specified in `logging-and-monitoring.md`.

Security groups: ALB → gateway :8080 only. Gateway → policy :8081 and claims :8082. Claims →
policy :8081. Services → RDS :3306 and MSK. Nothing else inbound.

Per service: its own task role (read its own secrets, write its own logs), its own CloudWatch log group
(retention and KMS per `logging-and-monitoring.md`), autoscaling on CPU 60% (min 1 dev / 2 prod), and ALB/Service
Connect health checks on `/actuator/health/readiness`.

Cost note for dev: MSK and NAT are the expensive parts. For a personal dev account, one option is
self-managed Kafka on a single EC2 or ECS task with the same topic setup. Put it behind a
Terraform variable `kafka_mode = "msk" | "self_managed"` and ask the developer which one to use
before provisioning.

Run `terraform plan` and show it to the developer before any `apply`. Never apply to prod from a
local machine.
