variable "name_prefix" {
  type = string
}

variable "environment" {
  type = string
}

variable "private_db_subnet_ids" {
  type = list(string)
}

variable "rds_security_group_id" {
  type = string
}

variable "instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "allocated_storage_gb" {
  type    = number
  default = 20
}

variable "max_allocated_storage_gb" {
  description = "Storage autoscaling ceiling"
  type        = number
  default     = 100
}

variable "multi_az" {
  description = "testing-and-deploy.md §5: Multi-AZ in prod only"
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  type    = number
  default = 7
}

variable "deletion_protection" {
  type    = bool
  default = false
}

variable "secret_recovery_window_days" {
  type    = number
  default = 7
}

variable "tags" {
  type    = map(string)
  default = {}
}
