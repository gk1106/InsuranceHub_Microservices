variable "name_prefix" {
  type = string
}

variable "gitlab_host" {
  description = "gitlab.com, or a self-managed GitLab instance's hostname"
  type        = string
  default     = "gitlab.com"
}

variable "gitlab_project_path" {
  description = "namespace/project-name, exactly as it appears in the GitLab URL"
  type        = string
}

variable "allowed_branch" {
  description = "Only a pipeline running on this branch can assume this role - \"master\"/\"main\" for deploy-prod, or \"*\" for deploy-dev if every branch should be able to deploy to dev"
  type        = string
}

variable "create_oidc_provider" {
  description = "Only one aws_iam_openid_connect_provider can exist per URL per AWS account - set true on the FIRST environment that provisions this (dev), false on every other environment (prod), passing that first one's ARN via existing_oidc_provider_arn instead."
  type        = bool
  default     = true
}

variable "existing_oidc_provider_arn" {
  type    = string
  default = null
}

variable "resource_name_prefix" {
  description = "Scopes ECR/IAM permissions to resources named \"<this>-*\" - e.g. \"insurancehub\" matches both insurancehub-dev-* and insurancehub-prod-*, since one CI pipeline deploys to both."
  type        = string
  default     = "insurancehub"
}

variable "tags" {
  type    = map(string)
  default = {}
}
