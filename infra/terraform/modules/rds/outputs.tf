output "endpoint" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "admin_password_secret_arn" {
  value = aws_secretsmanager_secret.admin_password.arn
}

output "instance_id" {
  value = aws_db_instance.this.id
}

output "instance_arn" {
  value = aws_db_instance.this.arn
}
