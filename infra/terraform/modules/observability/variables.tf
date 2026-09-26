variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "app_log_group_names" {
  description = "Map of service name -> its CloudWatch log group name (modules/ecs's own output)"
  type        = map(string)
}

variable "ecs_cluster_name" {
  type = string
}

variable "ecs_service_names" {
  type = list(string)
}

variable "rds_instance_id" {
  type = string
}

variable "alb_arn_suffix" {
  description = "The ALB's own arn_suffix attribute, for the AWS/ApplicationELB LoadBalancer dimension"
  type        = string
}

variable "alert_email" {
  description = "Email to subscribe to the alerts SNS topic. Null skips the subscription (confirm and add later via console/CLI, or set this and re-apply)."
  type        = string
  default     = null
}

variable "log_reader_trusted_principal_arn" {
  description = "IAM principal (a user, role, or account) allowed to assume the log-reader role"
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
