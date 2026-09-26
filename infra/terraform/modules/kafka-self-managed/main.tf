# Single-node Kafka (KRaft combined mode), same image/config shape as docker-compose.yml's own
# apache/kafka:3.9.0 service - the cheap alternative to MSK for a personal dev account
# (testing-and-deploy.md §5's own suggestion, `kafka_mode = "self_managed"`). One ECS Fargate
# task, EFS-backed so its log data survives a task replacement (Fargate's own ephemeral storage
# does not). Not highly available - a single node has no failover, which is an accepted
# trade-off for a personal/dev deployment; see docs/adr/0009-aws-topology.md.

resource "aws_efs_file_system" "kafka_data" {
  creation_token   = "${var.name_prefix}-kafka-data"
  encrypted        = true
  performance_mode = "generalPurpose"
  tags             = var.tags
}

resource "aws_efs_mount_target" "kafka_data" {
  for_each        = toset(var.private_app_subnet_ids)
  file_system_id  = aws_efs_file_system.kafka_data.id
  subnet_id       = each.value
  security_groups = [aws_security_group.efs.id]
}

resource "aws_efs_access_point" "kafka_data" {
  file_system_id = aws_efs_file_system.kafka_data.id
  posix_user {
    uid = 1000
    gid = 1000
  }
  root_directory {
    path = "/kafka-logs"
    creation_info {
      owner_uid   = 1000
      owner_gid   = 1000
      permissions = "0755"
    }
  }
  tags = var.tags
}

resource "aws_security_group" "efs" {
  name_prefix = "${var.name_prefix}-kafka-efs-"
  vpc_id      = var.vpc_id
  ingress {
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    security_groups = [var.kafka_security_group_id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = var.tags
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/insurancehub/${var.environment}/kafka"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
  tags              = var.tags
}

resource "aws_ecs_task_definition" "this" {
  family                   = "${var.name_prefix}-kafka"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  volume {
    name = "kafka-data"
    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.kafka_data.id
      transit_encryption = "ENABLED"
      authorization_config {
        access_point_id = aws_efs_access_point.kafka_data.id
        iam             = "ENABLED"
      }
    }
  }

  container_definitions = jsonencode([
    {
      name  = "kafka"
      image = var.kafka_image
      portMappings = [
        { containerPort = 9092, protocol = "tcp", name = "kafka-plaintext" },
        { containerPort = 9093, protocol = "tcp", name = "kafka-controller" }
      ]
      mountPoints = [
        { sourceVolume = "kafka-data", containerPath = "/var/lib/kafka/data" }
      ]
      # Same KRaft single-node shape as docker-compose.yml's own kafka service - see that
      # file's comments for why each of these is set the way it is. KAFKA_ADVERTISED_LISTENERS
      # uses the ECS Service Connect DNS name ("kafka", the alias configured on this service in
      # modules/ecs) so policy-service/claims-service's spring.kafka.bootstrap-servers=kafka:9092
      # config needs zero change between local compose and AWS.
      environment = [
        { name = "KAFKA_NODE_ID", value = "1" },
        { name = "KAFKA_PROCESS_ROLES", value = "broker,controller" },
        { name = "KAFKA_LISTENERS", value = "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093" },
        { name = "KAFKA_ADVERTISED_LISTENERS", value = "PLAINTEXT://kafka:9092" },
        { name = "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", value = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT" },
        { name = "KAFKA_CONTROLLER_LISTENER_NAMES", value = "CONTROLLER" },
        { name = "KAFKA_INTER_BROKER_LISTENER_NAME", value = "PLAINTEXT" },
        { name = "KAFKA_CONTROLLER_QUORUM_VOTERS", value = "1@localhost:9093" },
        { name = "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", value = "1" },
        { name = "CLUSTER_ID", value = var.cluster_id }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "kafka"
        }
      }
    }
  ])

  tags = var.tags
}

resource "aws_ecs_service" "this" {
  name            = "${var.name_prefix}-kafka"
  cluster         = var.ecs_cluster_id
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = 1 # single-node - see this module's own top comment
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = var.private_app_subnet_ids
    security_groups = [var.kafka_security_group_id]
  }

  service_connect_configuration {
    enabled   = true
    namespace = var.service_connect_namespace_arn
    service {
      port_name      = "kafka-plaintext"
      discovery_name = "kafka"
      client_alias {
        port     = 9092
        dns_name = "kafka"
      }
    }
  }

  # A single-node broker with a stateful EFS volume can't have two tasks running against the
  # same data directory at once during a deploy - replace, don't roll, this one task.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  tags = var.tags
}
