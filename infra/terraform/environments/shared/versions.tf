# Account-level, environment-independent resources: the GitLab OIDC identity provider (only one
# can exist per URL per account, so it can't live inside environments/dev or environments/prod -
# see modules/gitlab-oidc's own comment) and its two IAM roles (one per environment's deploy
# job). Applied once, rarely touched again.

terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project   = "insurance-hub"
      ManagedBy = "terraform"
      Scope     = "shared"
    }
  }
}
