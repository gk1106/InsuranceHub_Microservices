variable "aws_region" {
  type    = string
  default = "ap-south-1"
}

variable "kafka_mode" {
  type    = string
  default = "self_managed" # see environments/dev/variables.tf's own comment - same reasoning applies
  validation {
    condition     = contains(["self_managed", "msk"], var.kafka_mode)
    error_message = "kafka_mode must be \"self_managed\" or \"msk\"."
  }
}

variable "acm_certificate_arn" {
  type = string
}

variable "alert_email" {
  type    = string
  default = null
}

variable "log_reader_trusted_principal_arn" {
  type = string
}

variable "image_tags" {
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
