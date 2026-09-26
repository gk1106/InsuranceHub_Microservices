output "bootstrap_servers" {
  description = "IAM-auth bootstrap brokers - for spring.kafka.bootstrap-servers plus the IAM SASL client config Spring Kafka needs on top (see infra/terraform/README.md)"
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_iam
}

output "cluster_arn" {
  value = aws_msk_cluster.this.arn
}
