terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Local state for now - the developer's own account/region is small enough that a shared
  # remote backend (S3 + DynamoDB lock table) isn't needed yet. Add one before a second person
  # or a CI runner ever applies against this same environment (state file conflicts otherwise).
  # backend "s3" {}
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project     = "insurance-hub"
      Environment = "dev"
      ManagedBy   = "terraform"
    }
  }
}
