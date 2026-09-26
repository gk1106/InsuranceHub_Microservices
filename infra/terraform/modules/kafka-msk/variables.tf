variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "private_app_subnet_ids" {
  type = list(string)
}

variable "kafka_security_group_id" {
  type = string
}

variable "kafka_version" {
  type    = string
  default = "3.9.x"
}

variable "broker_count" {
  description = "Must be a multiple of the number of AZs client_subnets spans. 2 (one per AZ) is the minimum viable HA setup; 3+ enables real replication-factor 3."
  type        = number
  default     = 2
}

variable "broker_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "broker_ebs_volume_gb" {
  type    = number
  default = 50
}

variable "log_retention_days" {
  type    = number
  default = 14
}

variable "tags" {
  type    = map(string)
  default = {}
}
