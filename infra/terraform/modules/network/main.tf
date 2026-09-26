# VPC + 3-tier subnetting (public / private-app / private-db) across az_count AZs, per
# testing-and-deploy.md §5's topology: ALB in public subnets, ECS Fargate tasks in private-app,
# RDS + Kafka in private-db. One NAT gateway by default (single_nat_gateway=true) - the other
# named-expensive-part alongside MSK in a personal dev account; set false for a per-AZ NAT in
# prod if that resilience is worth the extra cost.

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(var.tags, { Name = "${var.name_prefix}-vpc" })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name_prefix}-igw" })
}

resource "aws_subnet" "public" {
  count                   = var.az_count
  vpc_id                  = aws_vpc.this.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index)
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true
  tags                    = merge(var.tags, { Name = "${var.name_prefix}-public-${local.azs[count.index]}" })
}

resource "aws_subnet" "private_app" {
  count             = var.az_count
  vpc_id            = aws_vpc.this.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + var.az_count)
  availability_zone = local.azs[count.index]
  tags              = merge(var.tags, { Name = "${var.name_prefix}-private-app-${local.azs[count.index]}" })
}

resource "aws_subnet" "private_db" {
  count             = var.az_count
  vpc_id            = aws_vpc.this.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + (2 * var.az_count))
  availability_zone = local.azs[count.index]
  tags              = merge(var.tags, { Name = "${var.name_prefix}-private-db-${local.azs[count.index]}" })
}

resource "aws_eip" "nat" {
  count  = var.single_nat_gateway ? 1 : var.az_count
  domain = "vpc"
  tags   = merge(var.tags, { Name = "${var.name_prefix}-nat-eip-${count.index}" })
}

resource "aws_nat_gateway" "this" {
  count         = var.single_nat_gateway ? 1 : var.az_count
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  tags          = merge(var.tags, { Name = "${var.name_prefix}-nat-${count.index}" })
  depends_on    = [aws_internet_gateway.this]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-public-rt" })
}

resource "aws_route_table_association" "public" {
  count          = var.az_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# One route table per AZ for private subnets so each can point at its own NAT gateway when
# single_nat_gateway=false; all point at nat[0] when true.
resource "aws_route_table" "private" {
  count  = var.az_count
  vpc_id = aws_vpc.this.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = var.single_nat_gateway ? aws_nat_gateway.this[0].id : aws_nat_gateway.this[count.index].id
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-private-rt-${count.index}" })
}

resource "aws_route_table_association" "private_app" {
  count          = var.az_count
  subnet_id      = aws_subnet.private_app[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

resource "aws_route_table_association" "private_db" {
  count          = var.az_count
  subnet_id      = aws_subnet.private_db[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# --- Security groups (testing-and-deploy.md §5's exact rule set) ---
# ALB to gateway :8080 only. Gateway to policy :8081 and claims :8082. Claims to policy :8081.
# Services to RDS :3306 and Kafka. Nothing else inbound.

resource "aws_security_group" "alb" {
  name_prefix = "${var.name_prefix}-alb-"
  vpc_id      = aws_vpc.this.id
  ingress {
    description = "HTTPS from the internet"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-alb-sg" })
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "gateway" {
  name_prefix = "${var.name_prefix}-gateway-"
  vpc_id      = aws_vpc.this.id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-gateway-sg" })
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group_rule" "alb_to_gateway" {
  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  security_group_id        = aws_security_group.gateway.id
  source_security_group_id = aws_security_group.alb.id
  description              = "ALB to hub-gateway"
}

resource "aws_security_group" "policy_service" {
  name_prefix = "${var.name_prefix}-policy-"
  vpc_id      = aws_vpc.this.id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-policy-sg" })
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "claims_service" {
  name_prefix = "${var.name_prefix}-claims-"
  vpc_id      = aws_vpc.this.id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-claims-sg" })
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group_rule" "gateway_to_policy" {
  type                     = "ingress"
  from_port                = 8081
  to_port                  = 8081
  protocol                 = "tcp"
  security_group_id        = aws_security_group.policy_service.id
  source_security_group_id = aws_security_group.gateway.id
  description              = "hub-gateway to policy-service"
}

resource "aws_security_group_rule" "claims_to_policy" {
  type                     = "ingress"
  from_port                = 8081
  to_port                  = 8081
  protocol                 = "tcp"
  security_group_id        = aws_security_group.policy_service.id
  source_security_group_id = aws_security_group.claims_service.id
  description              = "claims-service to policy-service (coverage lookup)"
}

resource "aws_security_group_rule" "gateway_to_claims" {
  type                     = "ingress"
  from_port                = 8082
  to_port                  = 8082
  protocol                 = "tcp"
  security_group_id        = aws_security_group.claims_service.id
  source_security_group_id = aws_security_group.gateway.id
  description              = "hub-gateway to claims-service"
}

resource "aws_security_group" "rds" {
  name_prefix = "${var.name_prefix}-rds-"
  vpc_id      = aws_vpc.this.id
  tags        = merge(var.tags, { Name = "${var.name_prefix}-rds-sg" })
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group_rule" "services_to_rds" {
  for_each                 = { gateway = aws_security_group.gateway.id, policy = aws_security_group.policy_service.id, claims = aws_security_group.claims_service.id }
  type                     = "ingress"
  from_port                = 3306
  to_port                  = 3306
  protocol                 = "tcp"
  security_group_id        = aws_security_group.rds.id
  source_security_group_id = each.value
  description              = "${each.key} to RDS MySQL"
}

resource "aws_security_group" "kafka" {
  name_prefix = "${var.name_prefix}-kafka-"
  vpc_id      = aws_vpc.this.id
  tags        = merge(var.tags, { Name = "${var.name_prefix}-kafka-sg" })
  lifecycle {
    create_before_destroy = true
  }
}

# 9092 plaintext/internal, 9094 TLS for self-managed; MSK's own IAM-auth port (9098) added the
# same way by the msk module's caller if kafka_mode="msk" (see modules/kafka-msk).
resource "aws_security_group_rule" "services_to_kafka" {
  for_each                 = { gateway = aws_security_group.gateway.id, policy = aws_security_group.policy_service.id, claims = aws_security_group.claims_service.id }
  type                     = "ingress"
  from_port                = 9092
  to_port                  = 9098
  protocol                 = "tcp"
  security_group_id        = aws_security_group.kafka.id
  source_security_group_id = each.value
  description              = "${each.key} to Kafka"
}
