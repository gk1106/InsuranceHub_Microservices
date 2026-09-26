# Amazon MSK - the closer-to-production alternative to modules/kafka-self-managed, selected via
# the root kafka_mode variable (testing-and-deploy.md §5). IAM auth + TLS in transit and at
# rest, matching service-design.md's own "IAM auth, TLS" line for this option. Meaningfully more
# expensive than the self-managed module for a personal account (this is one of the two
# named-expensive-parts, alongside the NAT gateway) - kept as a real, working module rather than
# a stub so switching to it later is a variable change, not a rewrite.

resource "aws_cloudwatch_log_group" "broker_logs" {
  name              = "/insurancehub/${var.environment}/msk"
  retention_in_days = var.log_retention_days
  tags              = var.tags
}

resource "aws_msk_configuration" "this" {
  name              = "${var.name_prefix}-msk-config"
  kafka_versions    = [var.kafka_version]
  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    default.replication.factor=${var.broker_count >= 3 ? 3 : var.broker_count}
    min.insync.replicas=${var.broker_count >= 3 ? 2 : 1}
  PROPERTIES
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name_prefix}-msk"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count
  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.private_app_subnet_ids
    security_groups = [var.kafka_security_group_id]
    storage_info {
      ebs_storage_info {
        volume_size = var.broker_ebs_volume_gb
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  client_authentication {
    sasl {
      iam = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.broker_logs.name
      }
    }
  }

  tags = var.tags
}
