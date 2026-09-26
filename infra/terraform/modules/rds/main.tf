# One RDS MySQL instance, three schemas (policy_db/claims_db/gateway_db) - mirrors
# docker/mysql/init.sql exactly (one server, database-per-service, CLAUDE.md rule 1), rather
# than three separate instances, to keep a personal/dev AWS bill down. Multi-AZ only in prod
# (testing-and-deploy.md §5).
#
# Schema/user creation itself is NOT done here (see ../../scripts/init-rds.sql.tpl and this
# module's own README note) - Terraform running from a developer's machine or a GitLab CI
# runner outside the VPC can't reach a private-subnet RDS instance to run SQL against it, and
# solving that (a bastion, a Lambda-backed custom resource) is more machinery than this
# personal-project phase needs. The instance's admin password is still Terraform-managed
# (Secrets Manager) so the one-time init script has something to authenticate with.

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-db"
  subnet_ids = var.private_db_subnet_ids
  tags       = var.tags
}

resource "random_password" "admin" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "admin_password" {
  name                    = "/insurancehub/${var.environment}/rds/admin-password"
  recovery_window_in_days = var.secret_recovery_window_days
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "admin_password" {
  secret_id     = aws_secretsmanager_secret.admin_password.id
  secret_string = random_password.admin.result
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-mysql"
  engine         = "mysql"
  engine_version = "8.4"
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.rds_security_group_id]
  multi_az               = var.multi_az
  publicly_accessible    = false

  username = "admin"
  password = random_password.admin.result

  backup_retention_period = var.backup_retention_days
  deletion_protection     = var.deletion_protection
  skip_final_snapshot     = !var.deletion_protection
  apply_immediately       = var.environment != "prod"

  enabled_cloudwatch_logs_exports = ["error", "slowquery"]

  tags = var.tags
}
