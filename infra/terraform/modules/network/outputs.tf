output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_app_subnet_ids" {
  value = aws_subnet.private_app[*].id
}

output "private_db_subnet_ids" {
  value = aws_subnet.private_db[*].id
}

output "alb_security_group_id" {
  value = aws_security_group.alb.id
}

output "gateway_security_group_id" {
  value = aws_security_group.gateway.id
}

output "policy_security_group_id" {
  value = aws_security_group.policy_service.id
}

output "claims_security_group_id" {
  value = aws_security_group.claims_service.id
}

output "rds_security_group_id" {
  value = aws_security_group.rds.id
}

output "kafka_security_group_id" {
  value = aws_security_group.kafka.id
}

output "availability_zones" {
  value = local.azs
}
