output "dev_deploy_role_arn" {
  description = "Set as $AWS_ROLE_ARN_DEV in GitLab CI/CD variables"
  value       = module.gitlab_oidc_dev.role_arn
}

output "prod_deploy_role_arn" {
  description = "Set as $AWS_ROLE_ARN_PROD in GitLab CI/CD variables"
  value       = module.gitlab_oidc_prod.role_arn
}
