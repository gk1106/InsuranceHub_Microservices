variable "name_prefix" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "private_app_subnet_ids" {
  type = list(string)
}

variable "adot_collector_image" {
  description = "AWS Distro for OpenTelemetry collector image (logging-and-monitoring.md §8)"
  type        = string
  default     = "public.ecr.aws/aws-observability/aws-otel-collector:latest"
}

variable "log_retention_days" {
  type    = number
  default = 14
}

variable "kms_key_arn" {
  description = "KMS key ARN to encrypt this module's CloudWatch log groups with (modules/kms)"
  type        = string
}

variable "services" {
  description = <<-DESC
    One entry per ECS service. `secrets` maps container env var name -> Secrets Manager ARN
    (injected via the execution role, never written into this task definition's own plaintext).
    `load_balancer_target_group_arn` is null for policy-service/claims-service (never reachable
    from the ALB) and set for hub-gateway only.
  DESC
  type = map(object({
    image                          = string
    container_port                 = number
    management_port                = number
    cpu                            = number
    memory                         = number
    desired_count                  = number
    autoscaling_max                = number
    security_group_id              = string
    log_group_name                 = string
    environment                    = map(string)
    secrets                        = map(string)
    load_balancer_target_group_arn = optional(string)
  }))
}

variable "tags" {
  type    = map(string)
  default = {}
}
