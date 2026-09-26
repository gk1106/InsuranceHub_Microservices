# Standalone module (not folded into modules/observability) specifically to avoid a circular
# module dependency: modules/ecs's log groups need this key's ARN at creation time, and
# modules/observability's alarms/queries need modules/ecs's log group names - the key has to
# exist independently of both.

resource "aws_kms_key" "logs" {
  description             = "Encrypts all InsuranceHub CloudWatch log groups (${var.environment})"
  deletion_window_in_days = 7
  enable_key_rotation     = true
  policy                  = data.aws_iam_policy_document.key_policy.json
  tags                    = var.tags
}

resource "aws_kms_alias" "logs" {
  name          = "alias/${var.name_prefix}-logs"
  target_key_id = aws_kms_key.logs.key_id
}

data "aws_caller_identity" "current" {}

# CloudWatch Logs needs an explicit grant to use a customer-managed key for a log group's
# encryption - the default key policy (root-account-only) isn't enough on its own.
data "aws_iam_policy_document" "key_policy" {
  statement {
    sid       = "AccountRootFullAccess"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
  statement {
    sid       = "AllowCloudWatchLogs"
    actions   = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["logs.${var.aws_region}.amazonaws.com"]
    }
  }
}
