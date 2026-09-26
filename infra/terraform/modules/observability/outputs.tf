output "sns_topic_arn" {
  value = aws_sns_topic.alerts.arn
}

output "dashboard_name" {
  value = aws_cloudwatch_dashboard.this.dashboard_name
}

output "log_reader_role_arn" {
  value = aws_iam_role.log_readers.arn
}
