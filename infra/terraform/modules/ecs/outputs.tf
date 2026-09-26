output "cluster_id" {
  value = aws_ecs_cluster.this.id
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "service_connect_namespace_arn" {
  value = aws_service_discovery_http_namespace.this.arn
}

output "service_names" {
  value = { for k, s in aws_ecs_service.this : k => s.name }
}

output "task_role_arn" {
  value = aws_iam_role.task.arn
}

output "log_group_names" {
  value = { for k, g in aws_cloudwatch_log_group.app : k => g.name }
}
