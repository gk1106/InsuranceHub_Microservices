# ADR-0009: phase 9 AWS topology — cost-conscious defaults, code-only this session

Status: Accepted (phase 9)

## Decision

ECS Fargate + ALB + RDS MySQL + Cognito + self-managed Kafka + CloudWatch/X-Ray, exactly per
`testing-and-deploy.md` §5 and `logging-and-monitoring.md`, implemented as Terraform under
`infra/terraform/`. This phase produced the Terraform code and validated it locally
(`terraform validate`, real bugs found and fixed - see below); it did **not** run `plan`/`apply`
against a real AWS account. This machine's AWS CLI session was expired and no Terraform CLI was
installed at the start of this phase (Terraform 1.16.2 was installed via winget specifically to
run `validate`); a real `apply` needs the developer's own live credentials, and
`testing-and-deploy.md` itself says to show `terraform plan` to the developer before any `apply`
regardless.

## Cost-driven choices, each reversible via one variable

- **Kafka: self-managed on a single ECS Fargate task (EFS-backed), not MSK.**
  `testing-and-deploy.md` §5 names MSK and the NAT gateway as the two expensive parts of a
  personal dev AWS bill and says to ask before provisioning Kafka. `kafka_mode = "self_managed"`
  is the default in both `environments/dev` and `environments/prod`; both a working
  `modules/kafka-self-managed` and a working `modules/kafka-msk` exist, so switching later is a
  one-line variable change, not a rewrite.
- **Single NAT gateway, not one per AZ**, in both dev and prod. Real AWS's own multi-AZ NAT
  resilience is a cost this personal project doesn't need yet; `single_nat_gateway` is a module
  variable, not a hardcoded assumption.
- **Cognito (client_credentials + a resource-server scope), not self-hosted Keycloak on its own
  ECS service + RDS**, for the AWS-side OAuth2 issuer (`cross-cutting.md` §4 names both as
  options). Cognito needs no always-on compute or a second database; the trade-off is a real
  Keycloak-config difference from local dev (the local realm's JSON import has no Cognito
  equivalent), noted as a known gap below, not silently glossed over.
- **Secrets reach ECS tasks via the native `secrets` container-definition field (execution-role
  Secrets Manager reads), not Spring Cloud AWS's own runtime `spring.config.import=
  optional:aws-secretsmanager:...` fetch** that `cross-cutting.md` §3 describes. Zero new Spring
  dependencies in any service; the existing `${POLICY_DB_PASSWORD:policy_app}`-style env var
  placeholders already in `application-local.yml` work identically against a real injected
  secret value. The real-secrets-never-in-a-committed-file property either approach gives holds
  regardless of which one is used.

## Known gaps - real, not hidden

1. **`hub.crypto.bank-keys[0].private-key-path` expects a file path; ECS `secrets` injects a raw
   value as an env var.** The Secrets Manager container for the bank's private key exists
   (`modules/secrets`'s `bank_private_key`), and its ARN is wired into hub-gateway's task
   definition as `HUB_CRYPTO_BANK_KEYS_0_PRIVATE_KEY_PEM_TODO` - but nothing in `crypto.PemKeys`
   reads PEM content directly from an env var today. hub-gateway cannot actually start with
   `hub.crypto.enabled=true` in AWS until that app-code change is made. This is an application
   change, not an infra one, and is out of scope for a Terraform-only phase - tracked here so it
   isn't discovered by surprise at first real deploy.
2. **RDS schema/user bootstrap is a manual one-time script**
   (`infra/terraform/scripts/init-rds.sql.tpl`), not Terraform-managed. Terraform running from a
   developer's machine or a GitLab CI runner outside the VPC can't reach a private-subnet RDS
   instance to run SQL against it; solving that (a bastion, a Lambda-backed custom resource) is
   more machinery than this personal project's phase 9 needs. Run once per environment before
   the first real request.
3. **Spring relaxed-binding env var names in the ECS task definitions are not verified against a
   real deployment.** `HUB_INTERNAL_AUTH_SECRET` → `hub.internal-auth.secret`,
   `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` → the matching nested property, etc.
   follow Spring Boot's documented relaxed-binding rules, but this project's own history (Boot 4
   modularization surprises found in every prior phase) argues for treating this as "should work"
   rather than "confirmed working" until a real ECS task actually starts successfully with them.
4. **A remote Terraform backend for `prod`** (`environments/prod/versions.tf`'s commented-out
   `backend "s3"` block) needs to be created and filled in before a second person or a CI runner
   ever applies against that state - local state (dev's own choice) is unsafe for prod the
   moment more than one entity can run `apply`.
5. **The GitLab OIDC role trust policy's `sub` claim match, and the exact `aws-cli`/Alpine
   package names, are unverified against a live GitLab pipeline** - written from GitLab's and
   AWS's own current documentation (fetched directly during this phase, not from training-data
   memory alone, given how often cloud SDKs/CLIs rename things), but never actually run.

## A real bug found while writing this, not by inspection

`terraform validate` (run locally after installing Terraform via winget specifically for this)
caught two real errors before they could reach a live account: AWS security group rule
`description` fields reject the `->` character entirely (regex
`^[0-9A-Za-z_ .:/()#,@\[\]+=&;{}!$*-]*$`, no `>`) - every "X -> Y" description in
`modules/network` was invalid; and `aws_s3_bucket_lifecycle_configuration` now requires an
explicit `filter {}` block per rule (a provider warning that is becoming a hard error). Both
fixed and re-validated. Separately, `data.aws_region.current` was written as `.region` (a
different, newer AWS provider's attribute name) before checking against the actually-resolved
provider version (5.100.0) and finding the real attribute is `.name` - fixed the same way,
confirmed by `validate` succeeding rather than assumed correct.

Also worth recording: no AWS managed CloudWatch Logs data-protection identifier covers Indian
phone numbers or Indian bank/loan account numbers (confirmed against AWS's own published
identifier ARN list, fetched directly rather than assumed) - `modules/observability`'s PII
protection policy uses custom regex identifiers for `cif`/`accountNum`/`loanAcctNum`/`mobileNum`
instead, exactly as `logging-and-monitoring.md` §5 itself already said to do.
