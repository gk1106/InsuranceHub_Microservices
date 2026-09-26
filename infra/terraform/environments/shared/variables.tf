variable "aws_region" {
  type    = string
  default = "ap-south-1"
}

variable "gitlab_project_path" {
  description = "namespace/project-name, exactly as it appears in your GitLab project's URL"
  type        = string
}
