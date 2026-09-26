# testing-and-deploy.md §4: "publish pushes to ECR using GitLab OIDC -> an AWS IAM role (no
# long-lived AWS keys in CI variables)." One OIDC identity provider (shared, account-wide - only
# one can exist per URL) plus one role per environment, trusted only for this specific GitLab
# project and the branch that environment deploys from.

data "tls_certificate" "gitlab" {
  url = "https://${var.gitlab_host}/oauth/discovery/keys"
}

resource "aws_iam_openid_connect_provider" "gitlab" {
  count           = var.create_oidc_provider ? 1 : 0
  url             = "https://${var.gitlab_host}"
  client_id_list  = ["https://sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.gitlab.certificates[0].sha1_fingerprint]
  tags            = var.tags
}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.gitlab[0].arn : var.existing_oidc_provider_arn
}

data "aws_iam_policy_document" "trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.gitlab_host}:aud"
      values   = ["https://sts.amazonaws.com"]
    }
    # project_path:<namespace>/<project>:ref_type:branch:ref:<branch> - restricts which GitLab
    # project AND which branch can assume this role, so a deploy-prod role can't be assumed from
    # an arbitrary feature branch's pipeline.
    condition {
      test     = "StringLike"
      variable = "${var.gitlab_host}:sub"
      values   = ["project_path:${var.gitlab_project_path}:ref_type:branch:ref:${var.allowed_branch}"]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = "${var.name_prefix}-gitlab-ci"
  assume_role_policy = data.aws_iam_policy_document.trust.json
  tags               = var.tags
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# Scoped by this project's own "insurancehub-<env>-*" naming convention (every module in this
# tree names its resources that way), not by exact ARNs handed in from a specific environment's
# module outputs - deliberately, to avoid coupling this shared/account-level module's state to
# environments/dev's or environments/prod's (a real cross-state reference would need a shared
# remote backend + terraform_remote_state, more machinery than a personal project's CI role
# needs). A real name-prefix wildcard is still meaningfully narrower than resources = ["*"].
data "aws_iam_policy_document" "permissions" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid = "PushToOwnRepos"
    actions = [
      "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage",
      "ecr:PutImage", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload",
    ]
    resources = ["arn:aws:ecr:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:repository/${var.resource_name_prefix}-*"]
  }
  statement {
    sid = "UpdateEcsServices"
    actions = [
      "ecs:UpdateService", "ecs:DescribeServices", "ecs:DescribeTaskDefinition",
      "ecs:RegisterTaskDefinition",
    ]
    resources = ["*"] # ECS scopes registration/update by cluster+family at the API level, not by a resource ARN pattern usable here
  }
  statement {
    sid     = "PassRolesToEcs"
    actions = ["iam:PassRole"]
    resources = [
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${var.resource_name_prefix}-*",
    ]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "this" {
  name   = "deploy"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.permissions.json
}
