# cross-cutting.md §3: "Secrets... from Secrets Manager (bank private key, DB passwords,
# Keycloak/Cognito client secrets) and Parameter Store (non-secret config)... ECS task roles
# grant read access to that service's own secrets only." Path prefix per service enforces that
# split at the IAM-policy level (modules/ecs grants read on /insurancehub/<env>/<service>/* only).
#
# DB passwords and the internal-auth secret are Terraform-generated (random_password) since
# there's nothing sensitive to preserve across `apply`s beyond "some strong value ECS and RDS
# both know." The bank private key is NOT Terraform-generated - a real cryptographic keypair
# must come from a real key-generation process (scripts/gen-dev-keys.sh's own real-deployment
# equivalent), never derived inside Terraform state. Its secret container is created empty and
# `ignore_changes` on secret_string, so `apply` never overwrites a value someone populated
# out-of-band.

resource "random_password" "db" {
  for_each = toset(var.service_names)
  length   = 32
  special  = false # RDS password chars: avoid needing to worry about MySQL connection-string escaping
}

resource "aws_secretsmanager_secret" "db_password" {
  for_each                = toset(var.service_names)
  name                    = "/insurancehub/${var.environment}/${each.value}/db-password"
  recovery_window_in_days = var.secret_recovery_window_days
  kms_key_id              = var.kms_key_id
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "db_password" {
  for_each      = toset(var.service_names)
  secret_id     = aws_secretsmanager_secret.db_password[each.value].id
  secret_string = random_password.db[each.value].result
}

resource "random_password" "internal_auth_secret" {
  length  = 48
  special = false
}

# Shared across all three services (docs/adr/0007-internal-service-auth.md - one value, same
# everywhere) - lives under a "shared" pseudo-service prefix, and modules/ecs must grant every
# service's task role read access to this one path in addition to its own.
resource "aws_secretsmanager_secret" "internal_auth_secret" {
  name                    = "/insurancehub/${var.environment}/shared/internal-auth-secret"
  recovery_window_in_days = var.secret_recovery_window_days
  kms_key_id              = var.kms_key_id
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "internal_auth_secret" {
  secret_id     = aws_secretsmanager_secret.internal_auth_secret.id
  secret_string = random_password.internal_auth_secret.result
}

# Empty placeholder - a real PEM must be written here out-of-band (aws secretsmanager
# put-secret-value) before hub-gateway can start with hub.crypto.enabled=true. lifecycle.ignore_changes
# keeps a later `apply` from clobbering that manually-populated value back to the placeholder.
resource "aws_secretsmanager_secret" "bank_private_key" {
  name                    = "/insurancehub/${var.environment}/hub-gateway/bank-private-key"
  recovery_window_in_days = var.secret_recovery_window_days
  kms_key_id              = var.kms_key_id
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "bank_private_key" {
  secret_id     = aws_secretsmanager_secret.bank_private_key.id
  secret_string = "REPLACE-ME-populate-with-a-real-PEM-before-first-deploy"
  lifecycle {
    ignore_changes = [secret_string]
  }
}

# cross-cutting.md §4: Cognito (client_credentials + a resource-server scope) chosen over
# self-hosting Keycloak on its own ECS service+RDS - cheaper for a personal/dev account (no
# always-on compute or extra database), and this project's other cost calls (self-managed
# Kafka, single NAT gateway) follow the same "cheapest workable option, documented, reversible"
# reasoning. See docs/adr/0009-aws-topology.md.
resource "aws_cognito_user_pool" "this" {
  name = "${var.name_prefix}-insurers"
  tags = var.tags
}

resource "aws_cognito_resource_server" "insurance" {
  identifier   = "insurance"
  name         = "Insurance Hub API"
  user_pool_id = aws_cognito_user_pool.this.id
  scope {
    scope_name        = "Insurance"
    scope_description = "Access to the Insurance Hub external API"
  }
}

# One app client per insurer (mirrors the local realm's insp001-client/insp002-client/
# insp003-client) - client_credentials grant only, scoped to the Insurance resource server.
resource "aws_cognito_user_pool_client" "insurer" {
  for_each                             = toset(var.insurer_ids)
  name                                 = "${each.value}-client"
  user_pool_id                         = aws_cognito_user_pool.this.id
  generate_secret                      = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_scopes                 = ["insurance/Insurance"]
  supported_identity_providers         = ["COGNITO"]
  depends_on                           = [aws_cognito_resource_server.insurance]
}

resource "aws_cognito_user_pool_domain" "this" {
  domain       = "${var.name_prefix}-auth"
  user_pool_id = aws_cognito_user_pool.this.id
}

# --- Parameter Store: non-secret config, per cross-cutting.md §3 ---

resource "aws_ssm_parameter" "aws_region" {
  name  = "/insurancehub/${var.environment}/shared/aws-region"
  type  = "String"
  value = var.aws_region
  tags  = var.tags
}
