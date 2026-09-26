module "gitlab_oidc_dev" {
  source               = "../../modules/gitlab-oidc"
  name_prefix          = "insurancehub-dev"
  gitlab_project_path  = var.gitlab_project_path
  allowed_branch       = "*" # any branch's pipeline can deploy to dev
  create_oidc_provider = true
}

module "gitlab_oidc_prod" {
  source                     = "../../modules/gitlab-oidc"
  name_prefix                = "insurancehub-prod"
  gitlab_project_path        = var.gitlab_project_path
  allowed_branch             = "main" # only main's pipeline can deploy to prod, per .gitlab-ci.yml's deploy-prod rule
  create_oidc_provider       = false
  existing_oidc_provider_arn = module.gitlab_oidc_dev.oidc_provider_arn
}
