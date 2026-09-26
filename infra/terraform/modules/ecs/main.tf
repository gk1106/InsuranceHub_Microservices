# One ECS Fargate cluster, three services (hub-gateway, policy-service, claims-service) via
# for_each over var.services, discovered by each other through ECS Service Connect
# (testing-and-deploy.md §5: "http://policy-service:8081"). Each task also runs an ADOT
# collector sidecar (logging-and-monitoring.md §8) so app-exported OTLP traces reach X-Ray.
#
# Secrets reach the container as plain env vars via ECS's native `secrets` container-definition
# field (execution-role-scoped Secrets Manager reads) rather than Spring Cloud AWS's own runtime
# `spring.config.import=optional:aws-secretsmanager:...` fetch that cross-cutting.md §3
# describes - a deliberate simplification (docs/adr/0009-aws-topology.md): it needs zero new
# Spring dependencies in any service, and the existing `${POLICY_DB_PASSWORD:policy_app}`-style
# env var placeholders already in application-local.yml work identically against a real secret
# value injected this way. The real-secrets-never-in-the-task-definition-or-repo property Spring
# Cloud AWS would also give us holds either way.

resource "aws_ecs_cluster" "this" {
  name = var.name_prefix
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
  tags = var.tags
}

resource "aws_service_discovery_http_namespace" "this" {
  name = var.name_prefix
  tags = var.tags
}

# --- IAM: one execution role per service (scoped to that service's own secrets, per
#     cross-cutting.md §3), one shared task role (X-Ray write only - no app-level AWS SDK
#     calls in this design) ---

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  for_each           = var.services
  name               = "${var.name_prefix}-${each.key}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  for_each   = var.services
  role       = aws_iam_role.execution[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  for_each = var.services
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = values(each.value.secrets)
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  for_each = var.services
  name     = "read-own-secrets"
  role     = aws_iam_role.execution[each.key].id
  policy   = data.aws_iam_policy_document.execution_secrets[each.key].json
}

resource "aws_iam_role" "task" {
  name               = "${var.name_prefix}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "task_xray" {
  statement {
    actions = [
      "xray:PutTraceSegments",
      "xray:PutTelemetryRecords",
      "xray:GetSamplingRules",
      "xray:GetSamplingTargets",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "task_xray" {
  name   = "adot-xray-export"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_xray.json
}

resource "aws_cloudwatch_log_group" "app" {
  for_each          = var.services
  name              = each.value.log_group_name
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
  tags              = var.tags
}

resource "aws_cloudwatch_log_group" "adot" {
  for_each          = var.services
  name              = "${each.value.log_group_name}/adot"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
  tags              = var.tags
}

resource "aws_ecs_task_definition" "this" {
  for_each                 = var.services
  family                   = "${var.name_prefix}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = each.key
      image     = each.value.image
      essential = true
      portMappings = [
        { containerPort = each.value.container_port, protocol = "tcp", name = "${each.key}-app" },
        { containerPort = each.value.management_port, protocol = "tcp", name = "${each.key}-mgmt" }
      ]
      environment = [for k, v in each.value.environment : { name = k, value = v }]
      secrets     = [for k, arn in each.value.secrets : { name = k, valueFrom = arn }]
      # non-blocking: logging-and-monitoring.md §2 - a stalled CloudWatch delivery must never
      # block a request thread; drop, don't block.
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app[each.key].name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "app"
          "mode"                  = "non-blocking"
          "max-buffer-size"       = "25m"
        }
      }
      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:${each.value.management_port}/actuator/health/readiness || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 30
      }
    },
    {
      name      = "adot-collector"
      image     = var.adot_collector_image
      essential = false
      portMappings = [
        { containerPort = 4317, protocol = "tcp" },
        { containerPort = 4318, protocol = "tcp" }
      ]
      environment = [
        { name = "AWS_REGION", value = var.aws_region }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.adot[each.key].name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "adot"
        }
      }
    }
  ])

  tags = var.tags
}

resource "aws_ecs_service" "this" {
  for_each                           = var.services
  name                               = each.key
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.this[each.key].arn
  desired_count                      = each.value.desired_count
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = each.value.load_balancer_target_group_arn == null ? null : 60

  network_configuration {
    subnets         = var.private_app_subnet_ids
    security_groups = [each.value.security_group_id]
  }

  dynamic "load_balancer" {
    for_each = each.value.load_balancer_target_group_arn == null ? [] : [each.value.load_balancer_target_group_arn]
    content {
      target_group_arn = load_balancer.value
      container_name   = each.key
      container_port   = each.value.container_port
    }
  }

  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_http_namespace.this.arn
    service {
      port_name      = "${each.key}-app"
      discovery_name = each.key
      client_alias {
        port     = each.value.container_port
        dns_name = each.key
      }
    }
  }

  tags = var.tags
}

resource "aws_appautoscaling_target" "this" {
  for_each           = var.services
  max_capacity       = each.value.autoscaling_max
  min_capacity       = each.value.desired_count
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this[each.key].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

# cross-cutting.md's own build-order table / testing-and-deploy.md §5: autoscaling on CPU 60%.
resource "aws_appautoscaling_policy" "cpu" {
  for_each           = var.services
  name               = "${each.key}-cpu-60"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.this[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.this[each.key].scalable_dimension
  service_namespace  = aws_appautoscaling_target.this[each.key].service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value = 60
  }
}
