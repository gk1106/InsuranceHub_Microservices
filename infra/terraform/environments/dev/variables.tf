variable "aws_region" {
  description = "logging-and-monitoring.md §2: ap-south-1 (Mumbai) or whatever region the bank mandates for data residency"
  type        = string
  default     = "ap-south-1"
}

# testing-and-deploy.md §5: "ask the developer which one to use before provisioning." Answered
# for this repo as self_managed (docs/adr/0009-aws-topology.md) - the cheaper option for a
# personal dev account; change to "msk" here to switch, no other file needs to change.
variable "kafka_mode" {
  type    = string
  default = "self_managed"
  validation {
    condition     = contains(["self_managed", "msk"], var.kafka_mode)
    error_message = "kafka_mode must be \"self_managed\" or \"msk\"."
  }
}

variable "acm_certificate_arn" {
  description = "ACM cert for the ALB's HTTPS listener - must already be issued and validated (a Terraform-managed ACM cert needs a real, owned domain's DNS validation, which is outside this repo's control). No default - apply fails loudly if this isn't supplied rather than silently using a placeholder."
  type        = string
}

variable "alert_email" {
  description = "Email for CloudWatch alarm notifications. Null skips the SNS subscription."
  type        = string
  default     = null
}

variable "log_reader_trusted_principal_arn" {
  description = "IAM principal allowed to assume the insurancehub-log-readers role - typically the developer's own IAM user ARN for a personal account"
  type        = string
}

variable "image_tags" {
  description = "Git-SHA image tags per service, supplied by CI (.gitlab-ci.yml's deploy-dev job) - or set by hand for a manual first apply, once each ECR repo has at least one image pushed (ECR repos are IMMUTABLE-tagged, so a placeholder tag can't be pre-seeded here)."
  type = object({
    hub-gateway    = string
    policy-service = string
    claims-service = string
  })
}

variable "insurer_ids" {
  type    = list(string)
  default = ["insp001", "insp002", "insp003"]
}
