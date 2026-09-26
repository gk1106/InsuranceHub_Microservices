# Terraform — Insurance Hub AWS infrastructure

Design reasoning and known gaps: `docs/adr/0009-aws-topology.md`. This file is the practical
"how to actually run this" reference.

## Layout

```
infra/terraform/
  modules/            reusable building blocks (network, ecr, rds, ecs, alb, kafka-*, secrets,
                       observability, kms, gitlab-oidc)
  environments/
    shared/           account-level, applied once: the GitLab OIDC provider + its two deploy roles
    dev/              the dev environment's own root module
    prod/             the prod environment's own root module
  scripts/
    init-rds.sql.tpl        one-time RDS schema/user bootstrap (see "Known gaps" #2 in the ADR)
    deploy-ecs-service.sh   used by .gitlab-ci.yml's deploy-dev/deploy-prod jobs
```

`environments/dev` and `environments/prod` are independent Terraform states on purpose - a bug
in one can't corrupt the other, and they can be applied on completely different schedules.

## Prerequisites

- Terraform >= 1.9, AWS CLI v2, `jq` (for `deploy-ecs-service.sh` if you ever run it by hand).
- Real AWS credentials (`aws configure`, or an SSO session) - none of this can be applied
  without them, obviously.
- An ACM certificate already issued and DNS-validated for a domain you own (Terraform can't
  create one against a domain it doesn't control).
- (`main` branch only / prod) An S3 bucket + DynamoDB table for `environments/prod`'s remote
  state backend, created once by hand before that environment's first `init`.

## Bootstrap order (first time only)

1. **`environments/shared`**: `terraform init && terraform plan && terraform apply` with
   `-var gitlab_project_path=<your-namespace>/<your-project>`. Copy the two role ARNs from its
   output into GitLab's CI/CD variables as `AWS_ROLE_ARN_DEV` and `AWS_ROLE_ARN_PROD`
   (Settings → CI/CD → Variables, masked, protected).
2. **Push one image per service to each environment's ECR repos before the first `apply`** -
   every repo is `IMMUTABLE`-tagged (testing-and-deploy.md §2: never `latest`), so there's no
   placeholder tag Terraform can pre-seed. Either run the `package`/`publish` CI jobs once
   manually first, or `docker build` + `docker push` by hand with a real tag, then set that tag
   in `terraform.tfvars`'s `image_tags`.
3. **`environments/dev`**: copy `terraform.tfvars.example` to `terraform.tfvars`, fill in real
   values (never commit this file - `.gitignore` already excludes it), then
   `terraform init && terraform plan` and review the plan before `terraform apply`.
4. **Run `scripts/init-rds.sql.tpl`** (with the real per-service passwords substituted, from
   Secrets Manager) against the new RDS instance once - see the ADR's "known gaps" #2 for why
   this isn't automated.
5. **`environments/prod`**: same as dev, once dev is validated end-to-end. Set up its S3+DynamoDB
   remote backend first (see `versions.tf`'s commented `backend "s3"` block).

## Switching Kafka mode

`kafka_mode = "self_managed"` (default, cheap, single-node, no HA) or `"msk"` (managed, real
replication, meaningfully more expensive) - set it in `terraform.tfvars` and re-`apply`. See
ADR-0009 for the reasoning.

## What this phase did NOT do

- Run `terraform plan`/`apply` against a real AWS account (no live credentials were available
  this session - see ADR-0009).
- Wire `hub.crypto.bank-keys[0].private-key-path` to read from an env var instead of a file path
  (an application-code change, not infra - ADR-0009's known gap #1).
- Verify the exact Spring relaxed-binding env var names against a real running ECS task, or the
  GitLab OIDC trust-policy `sub` claim against a real pipeline run.

Verify all three before trusting a real deploy, not after.
