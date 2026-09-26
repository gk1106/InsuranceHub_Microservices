variable "name_prefix" {
  type = string
}

variable "service_names" {
  description = "Service names to create one ECR repository each for"
  type        = list(string)
  default     = ["hub-gateway", "policy-service", "claims-service"]
}

variable "tags" {
  type    = map(string)
  default = {}
}
