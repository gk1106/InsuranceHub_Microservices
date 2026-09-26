output "alb_dns_name" {
  value = module.alb.dns_name
}

output "ecr_repository_urls" {
  value = module.ecr.repository_urls
}

output "cognito_issuer_uri" {
  value = module.secrets.cognito_issuer_uri
}

output "cognito_client_ids" {
  value = module.secrets.cognito_client_ids
}

output "cognito_client_secrets" {
  value     = module.secrets.cognito_client_secrets
  sensitive = true
}

output "rds_endpoint" {
  value = module.rds.endpoint
}

output "dashboard_name" {
  value = module.observability.dashboard_name
}
