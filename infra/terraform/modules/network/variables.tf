variable "name_prefix" {
  description = "Prefix for all resource names, e.g. insurancehub-dev"
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones to spread subnets across"
  type        = number
  default     = 2
}

variable "single_nat_gateway" {
  description = "Use one NAT gateway for all private subnets instead of one per AZ - cheaper, less available. testing-and-deploy.md §5 flags NAT as one of the two expensive parts of a personal dev account; true is the sensible default outside prod."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Common tags applied to every resource in this module"
  type        = map(string)
  default     = {}
}
