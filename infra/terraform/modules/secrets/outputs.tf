output "db_password_secret_arns" {
  value = { for name, s in aws_secretsmanager_secret.db_password : name => s.arn }
}

output "internal_auth_secret_arn" {
  value = aws_secretsmanager_secret.internal_auth_secret.arn
}

output "bank_private_key_secret_arn" {
  value = aws_secretsmanager_secret.bank_private_key.arn
}

output "db_passwords" {
  value     = { for name, p in random_password.db : name => p.result }
  sensitive = true
}

output "cognito_user_pool_id" {
  value = aws_cognito_user_pool.this.id
}

output "cognito_issuer_uri" {
  description = "OIDC issuer URI for hub-gateway's spring.security.oauth2.resourceserver.jwt.issuer-uri"
  value       = "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.this.id}"
}

output "cognito_client_ids" {
  value = { for id, c in aws_cognito_user_pool_client.insurer : id => c.id }
}

output "cognito_client_secrets" {
  value     = { for id, c in aws_cognito_user_pool_client.insurer : id => c.client_secret }
  sensitive = true
}
