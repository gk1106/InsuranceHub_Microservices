# logging-and-monitoring.md §11's Terraform checklist, minus the KMS key (modules/kms, to avoid
# a circular module dependency - see that module's own comment) and the log groups themselves
# (owned by whichever module creates the resource they belong to - modules/ecs, modules/rds,
# modules/kafka-*).

# --- §5: PII protection - a data protection policy per app log group, safety net behind the
#     app's own PiiMasker/no-payload rule (SKILL rule 4) ---
#
# No AWS managed data identifier covers Indian mobile numbers or bank/loan account numbers
# (confirmed against AWS's own managed-identifier ARN list, not assumed - PhoneNumber/
# BankAccountNumber only ship country variants for BR/DE/ES/FR/GB/IT/US, no IN) - so the fields
# rule 4 actually names (cif, accountNum, loanAcctNum, mobileNum) are all custom regex
# identifiers, exactly as logging-and-monitoring.md §5 itself says ("custom regex identifiers
# for our CIF\d+, account-number and loan-account patterns"). Name/Address/EmailAddress are
# genuinely country-agnostic managed identifiers, kept as a supplementary net. CreditCardNumber
# is NOT included - this domain has no card data at all, and a managed identifier scanning for
# something that structurally can't appear only adds false-positive risk for no benefit.

data "aws_cloudwatch_log_data_protection_policy_document" "pii" {
  name = "insurancehub-pii-protection"

  configuration {
    custom_data_identifier {
      name  = "CifNumber"
      regex = "CIF\\d+"
    }
    custom_data_identifier {
      name  = "AccountNumber"
      regex = "ACC\\d{6,}"
    }
    custom_data_identifier {
      name  = "LoanAccountNumber"
      regex = "LN\\d{6,}"
    }
    custom_data_identifier {
      name  = "MobileNumberIN"
      regex = "\\b[6-9]\\d{9}\\b"
    }
  }

  statement {
    sid = "Audit"
    data_identifiers = [
      "CifNumber", "AccountNumber", "LoanAccountNumber", "MobileNumberIN",
      "arn:aws:dataprotection::aws:data-identifier/Name",
      "arn:aws:dataprotection::aws:data-identifier/Address",
      "arn:aws:dataprotection::aws:data-identifier/EmailAddress",
    ]
    operation {
      audit {
        findings_destination {}
      }
    }
  }

  statement {
    sid = "Deidentify"
    data_identifiers = [
      "CifNumber", "AccountNumber", "LoanAccountNumber", "MobileNumberIN",
      "arn:aws:dataprotection::aws:data-identifier/Name",
      "arn:aws:dataprotection::aws:data-identifier/Address",
      "arn:aws:dataprotection::aws:data-identifier/EmailAddress",
    ]
    operation {
      deidentify {
        mask_config {}
      }
    }
  }
}

resource "aws_cloudwatch_log_data_protection_policy" "app" {
  for_each        = var.app_log_group_names
  log_group_name  = each.value
  policy_document = data.aws_cloudwatch_log_data_protection_policy_document.pii.json
}

# --- §6: Logs Insights saved queries, verbatim from logging-and-monitoring.md §6 ---

locals {
  all_app_log_groups = values(var.app_log_group_names)
}

resource "aws_cloudwatch_query_definition" "trace_one_request" {
  name            = "InsuranceHub/${var.environment}/trace-one-request"
  log_group_names = local.all_app_log_groups
  query_string    = <<-QUERY
    fields @timestamp, @log, level, message, respCode
    | filter txnId = "REPLACE_WITH_TXN_ID"
    | sort @timestamp asc
  QUERY
}

resource "aws_cloudwatch_query_definition" "errors_last_hour" {
  name            = "InsuranceHub/${var.environment}/errors-last-hour"
  log_group_names = local.all_app_log_groups
  query_string    = <<-QUERY
    fields @timestamp, @log, message
    | filter level = "ERROR"
    | stats count(*) as errors by @log, logger_name
    | sort errors desc
  QUERY
}

resource "aws_cloudwatch_query_definition" "business_rejections" {
  name            = "InsuranceHub/${var.environment}/business-rejections-by-code-and-insurer"
  log_group_names = [var.app_log_group_names["hub-gateway"]]
  query_string    = <<-QUERY
    filter logger_name like /HubRequestLogger/ and respCode != "200"
    | stats count(*) by respCode, inspId, serviceType
  QUERY
}

resource "aws_cloudwatch_query_definition" "latency_percentiles" {
  name            = "InsuranceHub/${var.environment}/latency-p50-p95-p99-by-service-code"
  log_group_names = [var.app_log_group_names["hub-gateway"]]
  query_string    = <<-QUERY
    filter ispresent(latencyMs)
    | stats pct(latencyMs, 50) as p50, pct(latencyMs, 95) as p95, pct(latencyMs, 99) as p99 by serviceType
  QUERY
}

resource "aws_cloudwatch_query_definition" "support_ticket_lookup" {
  name            = "InsuranceHub/${var.environment}/everything-for-one-insurer-request"
  log_group_names = local.all_app_log_groups
  query_string    = <<-QUERY
    fields @timestamp, @log, message | filter inspId = "INSP001" and reqId = "REPLACE_WITH_REQ_ID" | sort @timestamp asc
  QUERY
}

resource "aws_cloudwatch_query_definition" "claim_status_changes_today" {
  name            = "InsuranceHub/${var.environment}/claim-status-changes-today"
  log_group_names = [var.app_log_group_names["claims-service"]]
  query_string    = <<-QUERY
    filter event = "CLAIM_STATUS_CHANGED" | stats count(*) by fromStatus, toStatus
  QUERY
}

# --- §7: metric filters + alarms ---

resource "aws_sns_topic" "alerts" {
  name = "${var.name_prefix}-alerts"
  tags = var.tags
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alert_email == null ? 0 : 1
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

resource "aws_cloudwatch_log_metric_filter" "error_count" {
  for_each       = var.app_log_group_names
  name           = "${each.key}-error-count"
  log_group_name = each.value
  pattern        = "{ $.level = \"ERROR\" }"
  metric_transformation {
    name       = "ErrorCount"
    namespace  = "InsuranceHub/${var.environment}"
    value      = "1"
    dimensions = { service = each.key }
  }
}

resource "aws_cloudwatch_metric_alarm" "error_count" {
  for_each            = var.environment == "prod" ? var.app_log_group_names : {}
  alarm_name          = "${var.name_prefix}-${each.key}-errors"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 5
  namespace           = "InsuranceHub/${var.environment}"
  metric_name         = aws_cloudwatch_log_metric_filter.error_count[each.key].metric_transformation[0].name
  statistic           = "Sum"
  dimensions          = { service = each.key }
  alarm_description   = "See saved query InsuranceHub/${var.environment}/errors-last-hour"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_log_metric_filter" "gateway_5xx" {
  name           = "gateway-5xx"
  log_group_name = var.app_log_group_names["hub-gateway"]
  pattern        = "{ $.respCode = \"5*\" }"
  metric_transformation {
    name      = "Gateway5xx"
    namespace = "InsuranceHub/${var.environment}"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "gateway_5xx" {
  count               = var.environment == "prod" ? 1 : 0
  alarm_name          = "${var.name_prefix}-gateway-5xx"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 3
  namespace           = "InsuranceHub/${var.environment}"
  metric_name         = aws_cloudwatch_log_metric_filter.gateway_5xx.metric_transformation[0].name
  statistic           = "Sum"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_log_metric_filter" "auth_failures" {
  name           = "auth-failures"
  log_group_name = var.app_log_group_names["hub-gateway"]
  pattern        = "{ $.respCode = \"401\" || $.respCode = \"403\" }"
  metric_transformation {
    name      = "AuthFailures"
    namespace = "InsuranceHub/${var.environment}"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "auth_failures" {
  count               = var.environment == "prod" ? 1 : 0
  alarm_name          = "${var.name_prefix}-auth-failures"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 20
  namespace           = "InsuranceHub/${var.environment}"
  metric_name         = aws_cloudwatch_log_metric_filter.auth_failures.metric_transformation[0].name
  statistic           = "Sum"
  alarm_description   = "20+ 401/403s in 5 min - possible attack or a misconfigured insurer client"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_log_metric_filter" "crypto_failures" {
  name           = "crypto-failures"
  log_group_name = var.app_log_group_names["hub-gateway"]
  pattern        = "{ $.errorCode = \"SIGNATURE_INVALID\" || $.errorCode = \"DECRYPTION_FAILED\" }"
  metric_transformation {
    name      = "CryptoFailures"
    namespace = "InsuranceHub/${var.environment}"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "crypto_failures" {
  count               = var.environment == "prod" ? 1 : 0
  alarm_name          = "${var.name_prefix}-crypto-failures"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 5
  namespace           = "InsuranceHub/${var.environment}"
  metric_name         = aws_cloudwatch_log_metric_filter.crypto_failures.metric_transformation[0].name
  statistic           = "Sum"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_log_metric_filter" "circuit_open" {
  name           = "circuit-open"
  log_group_name = var.app_log_group_names["hub-gateway"]
  pattern        = "{ $.errorCode = \"DOWNSTREAM_UNAVAILABLE\" }"
  metric_transformation {
    name      = "CircuitOpen"
    namespace = "InsuranceHub/${var.environment}"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "circuit_open" {
  count               = var.environment == "prod" ? 1 : 0
  alarm_name          = "${var.name_prefix}-circuit-open"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 1
  namespace           = "InsuranceHub/${var.environment}"
  metric_name         = aws_cloudwatch_log_metric_filter.circuit_open.metric_transformation[0].name
  statistic           = "Sum"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

# outbox_pending: a real app-emitted Micrometer gauge (phase 7), not a log-derived metric filter
# - alarms directly on the CloudWatch EMF/ADOT-exported metric name Micrometer publishes it as.
resource "aws_cloudwatch_metric_alarm" "outbox_pending" {
  count               = var.environment == "prod" ? 1 : 0
  alarm_name          = "${var.name_prefix}-outbox-pending"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  period              = 600
  threshold           = 1000
  namespace           = "InsuranceHub/${var.environment}"
  metric_name         = "outbox_pending"
  statistic           = "Maximum"
  alarm_description   = "Events not flowing to Kafka for 10+ min"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  count               = var.environment == "prod" ? 1 : 0
  alarm_name          = "${var.name_prefix}-alb-5xx"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 3
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  dimensions          = { LoadBalancer = var.alb_arn_suffix }
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  count               = var.environment == "prod" ? 1 : 0
  alarm_name          = "${var.name_prefix}-rds-free-storage-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  period              = 300
  threshold           = 2147483648 # 2 GiB
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Average"
  dimensions          = { DBInstanceIdentifier = var.rds_instance_id }
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

# --- §7: dashboard ---

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = "InsuranceHub-${var.environment}"
  dashboard_body = jsonencode({
    widgets = [
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "Gateway 5xx / Auth failures / Crypto failures"
          region = var.aws_region
          metrics = [
            ["InsuranceHub/${var.environment}", "Gateway5xx"],
            ["InsuranceHub/${var.environment}", "AuthFailures"],
            ["InsuranceHub/${var.environment}", "CryptoFailures"],
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          title   = "outbox_pending"
          region  = var.aws_region
          metrics = [["InsuranceHub/${var.environment}", "outbox_pending"]]
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 12
        height = 6
        properties = {
          title   = "ECS CPU/memory per service"
          region  = var.aws_region
          metrics = [for svc in var.ecs_service_names : ["AWS/ECS", "CPUUtilization", "ServiceName", svc, "ClusterName", var.ecs_cluster_name]]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 6
        width  = 12
        height = 6
        properties = {
          title   = "RDS connections"
          region  = var.aws_region
          metrics = [["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", var.rds_instance_id]]
        }
      },
      {
        type   = "log"
        x      = 0
        y      = 12
        width  = 24
        height = 6
        properties = {
          title  = "Latest ERROR lines"
          region = var.aws_region
          query  = "SOURCE '${var.app_log_group_names["hub-gateway"]}' | fields @timestamp, message | filter level = \"ERROR\" | sort @timestamp desc | limit 20"
          view   = "table"
        }
      }
    ]
  })
}

data "aws_caller_identity" "current" {}

# --- §5: log-reader IAM role - Insights queries on prod log groups only for this role ---

data "aws_iam_policy_document" "log_reader_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = [var.log_reader_trusted_principal_arn]
    }
  }
}

resource "aws_iam_role" "log_readers" {
  name               = "${var.name_prefix}-log-readers"
  assume_role_policy = data.aws_iam_policy_document.log_reader_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "log_reader_access" {
  statement {
    actions = [
      "logs:StartQuery", "logs:GetQueryResults", "logs:StopQuery",
      "logs:GetLogEvents", "logs:FilterLogEvents", "logs:DescribeLogGroups", "logs:DescribeLogStreams",
    ]
    resources = [for g in local.all_app_log_groups : "arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:${g}:*"]
    # Deliberately no logs:Unmask - logging-and-monitoring.md §5: "not granted to anyone by
    # default."
  }
}

resource "aws_iam_role_policy" "log_readers" {
  name   = "read-app-logs"
  role   = aws_iam_role.log_readers.id
  policy = data.aws_iam_policy_document.log_reader_access.json
}
