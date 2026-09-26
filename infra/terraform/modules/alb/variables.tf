variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "alb_security_group_id" {
  type = string
}

variable "acm_certificate_arn" {
  description = "ACM certificate ARN for the ALB's HTTPS listener - must be issued/validated before apply (see infra/terraform/README.md)"
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
