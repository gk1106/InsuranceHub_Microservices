output "dns_name" {
  value = aws_lb.this.dns_name
}

output "target_group_arn" {
  value = aws_lb_target_group.hub_gateway.arn
}

output "arn" {
  value = aws_lb.this.arn
}

output "arn_suffix" {
  description = "For the AWS/ApplicationELB CloudWatch metric LoadBalancer dimension - not the same string as arn"
  value       = aws_lb.this.arn_suffix
}

output "access_log_bucket_name" {
  value = aws_s3_bucket.access_logs.bucket
}
