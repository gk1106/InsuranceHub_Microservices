variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_app_subnet_ids" {
  type = list(string)
}

variable "kafka_security_group_id" {
  type = string
}

variable "ecs_cluster_id" {
  type = string
}

variable "service_connect_namespace_arn" {
  type = string
}

variable "execution_role_arn" {
  type = string
}

variable "task_role_arn" {
  type = string
}

variable "kafka_image" {
  description = "Same image docker-compose.yml pins for the real stack, not the 4.1.0 tag Testcontainers-only tests use (docs/adr/0006-outbox-relay.md - that tag mismatch is a Testcontainers-specific quirk, not a real image problem)"
  type        = string
  default     = "apache/kafka:3.9.0"
}

variable "cluster_id" {
  description = "KRaft cluster id - same base64 placeholder docker-compose.yml uses; a single-node dev cluster has no reason to differ"
  type        = string
  default     = "MTIzNDU2Nzg5MGFiY2RlZg"
}

variable "task_cpu" {
  type    = number
  default = 512
}

variable "task_memory" {
  type    = number
  default = 1024
}

variable "log_retention_days" {
  type    = number
  default = 14
}

variable "kms_key_arn" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
