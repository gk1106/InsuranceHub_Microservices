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

  # A remote backend is not optional for prod the way it is for dev (docs/adr/0009-aws-topology.md)
  # - state must survive a laptop's disk and be locked against concurrent applies (this repo's own
  # CI eventually runs `deploy-prod`, per .gitlab-ci.yml, alongside any manual apply). Create the
  # bucket + DynamoDB table once, by hand or via a small bootstrap config outside this tree
  # (chicken-and-egg: this backend config can't provision its own backend), then fill these in.
  backend "s3" {
    # bucket         = "REPLACE-ME-terraform-state-bucket"
    # key            = "insurancehub/prod/terraform.tfstate"
    # region         = "ap-south-1"
    # dynamodb_table = "REPLACE-ME-terraform-locks"
    # encrypt        = true
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project     = "insurance-hub"
      Environment = "prod"
      ManagedBy   = "terraform"
    }
  }
}
