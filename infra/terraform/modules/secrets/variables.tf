variable "name_prefix" {
  type = string
}

variable "environment" {
  description = "dev or prod - used in the /insurancehub/<environment>/... secret path prefix"
  type        = string
}

variable "aws_region" {
  type = string
}

variable "service_names" {
  type    = list(string)
  default = ["hub-gateway", "policy-service", "claims-service"]
}

variable "insurer_ids" {
  description = "Insurer ids to create a Cognito app client each for (mirrors local Keycloak realm's insp001/insp002/insp003 clients)"
  type        = list(string)
  default     = ["insp001", "insp002", "insp003"]
}

variable "kms_key_id" {
  description = "KMS key ARN/id to encrypt secrets with. Null uses the AWS-managed aws/secretsmanager key."
  type        = string
  default     = null
}

variable "secret_recovery_window_days" {
  description = "Days a deleted secret is recoverable before permanent deletion. 0 allows immediate deletion (useful for a dev environment torn down often)."
  type        = number
  default     = 7
}

variable "tags" {
  type    = map(string)
  default = {}
}
