# Public ALB -> hub-gateway only (testing-and-deploy.md §5's topology - policy-service/
# claims-service are never reachable from the ALB, only from hub-gateway/each other inside the
# VPC). HTTPS only; an HTTP listener exists solely to redirect to HTTPS.
#
# The access-log S3 bucket lives in THIS module, not modules/observability - observability's
# own alarms need this module's arn_suffix output, and this module needs a bucket to log to;
# putting the bucket in observability would make the two modules depend on each other, which
# Terraform can't resolve (module dependencies must form a DAG, not a cycle).

data "aws_caller_identity" "current" {}
data "aws_elb_service_account" "main" {}

resource "aws_s3_bucket" "access_logs" {
  bucket = "${var.name_prefix}-alb-logs-${data.aws_caller_identity.current.account_id}"
  tags   = var.tags
}

resource "aws_s3_bucket_lifecycle_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  rule {
    id     = "expire-after-90-days"
    status = "Enabled"
    filter {} # applies to every object in the bucket - no prefix/tag scoping needed
    expiration {
      days = 90
    }
  }
}

resource "aws_s3_bucket_policy" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowALBLogDelivery"
        Effect    = "Allow"
        Principal = { AWS = data.aws_elb_service_account.main.arn }
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.access_logs.arn}/alb/AWSLogs/${data.aws_caller_identity.current.account_id}/*"
      }
    ]
  })
}

resource "aws_lb" "this" {
  name               = "${var.name_prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.alb_security_group_id]
  subnets            = var.public_subnet_ids

  access_logs {
    bucket  = aws_s3_bucket.access_logs.bucket
    prefix  = "alb"
    enabled = true
  }

  depends_on = [aws_s3_bucket_policy.access_logs]

  tags = var.tags
}

resource "aws_lb_target_group" "hub_gateway" {
  name        = "${var.name_prefix}-gateway-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # Fargate tasks have no EC2 instance id to register by

  # Phase 8 moved actuator to its own management.server.port (9080) - the health check must
  # target that port explicitly, not the traffic port (8080), or every task looks unhealthy.
  health_check {
    path                = "/actuator/health/readiness"
    port                = "9080"
    protocol            = "HTTP"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
    matcher             = "200"
  }

  deregistration_delay = 30

  tags = var.tags
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.acm_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.hub_gateway.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}
