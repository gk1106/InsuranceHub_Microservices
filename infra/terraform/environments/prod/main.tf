locals {
  name_prefix = "insurancehub-prod"
  environment = "prod"

  # See environments/dev/main.tf's own comment - identical reasoning, prod-sized values only.
  kafka_bootstrap_servers = var.kafka_mode == "msk" ? module.kafka_msk[0].bootstrap_servers : module.kafka_self_managed[0].bootstrap_servers

  common_env = {
    SPRING_PROFILES_ACTIVE                  = "prod,json-logs"
    MANAGEMENT_OTLP_TRACING_ENDPOINT        = "http://localhost:4318/v1/traces"
    MANAGEMENT_TRACING_SAMPLING_PROBABILITY = "0.1" # cross-cutting.md §2: "10% in prod"
    SPRING_KAFKA_BOOTSTRAP_SERVERS          = local.kafka_bootstrap_servers
  }
}

module "network" {
  source      = "../../modules/network"
  name_prefix = local.name_prefix
  # testing-and-deploy.md §5 leaves per-AZ NAT as a prod option, not a requirement - still
  # single_nat_gateway=true here (docs/adr/0009-aws-topology.md's cost-consciousness applies to
  # this personal project's "prod" the same way it does to dev). Flip to false for real
  # multi-AZ NAT resilience once that cost is worth paying.
  single_nat_gateway = true
  tags               = { Environment = local.environment }
}

module "kms" {
  source      = "../../modules/kms"
  name_prefix = local.name_prefix
  environment = local.environment
  aws_region  = var.aws_region
}

module "ecr" {
  source      = "../../modules/ecr"
  name_prefix = local.name_prefix
}

module "secrets" {
  source      = "../../modules/secrets"
  name_prefix = local.name_prefix
  environment = local.environment
  aws_region  = var.aws_region
  insurer_ids = var.insurer_ids
  kms_key_id  = module.kms.key_arn
  # Default (7 days) - unlike dev, a prod secret shouldn't be trivially/immediately unrecoverable.
}

module "rds" {
  source                = "../../modules/rds"
  name_prefix           = local.name_prefix
  environment           = local.environment
  private_db_subnet_ids = module.network.private_db_subnet_ids
  rds_security_group_id = module.network.rds_security_group_id
  multi_az              = true # testing-and-deploy.md §5: Multi-AZ in prod
  deletion_protection   = true
  backup_retention_days = 14
}

module "ecs" {
  source                 = "../../modules/ecs"
  name_prefix            = local.name_prefix
  aws_region             = var.aws_region
  private_app_subnet_ids = module.network.private_app_subnet_ids
  kms_key_arn            = module.kms.key_arn
  log_retention_days     = 90 # logging-and-monitoring.md §4

  services = {
    hub-gateway = {
      image                          = "${module.ecr.repository_urls["hub-gateway"]}:${var.image_tags.hub-gateway}"
      container_port                 = 8080
      management_port                = 9080
      cpu                            = 1024
      memory                         = 2048
      desired_count                  = 2 # testing-and-deploy.md §5: min 2 prod
      autoscaling_max                = 6
      security_group_id              = module.network.gateway_security_group_id
      log_group_name                 = "/insurancehub/${local.environment}/hub-gateway"
      load_balancer_target_group_arn = module.alb.target_group_arn
      environment = merge(local.common_env, {
        SPRING_DATASOURCE_URL                                = "jdbc:mysql://${module.rds.endpoint}:${module.rds.port}/gateway_db"
        SPRING_DATASOURCE_USERNAME                           = "gateway_app"
        HUB_CRYPTO_ENABLED                                   = "true"
        SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI = module.secrets.cognito_issuer_uri
      })
      secrets = {
        SPRING_DATASOURCE_PASSWORD = module.secrets.db_password_secret_arns["hub-gateway"]
        HUB_INTERNAL_AUTH_SECRET   = module.secrets.internal_auth_secret_arn
        # See environments/dev/main.tf's own comment - not yet wired to a real app property.
        HUB_CRYPTO_BANK_KEYS_0_PRIVATE_KEY_PEM_TODO = module.secrets.bank_private_key_secret_arn
      }
    }
    policy-service = {
      image             = "${module.ecr.repository_urls["policy-service"]}:${var.image_tags.policy-service}"
      container_port    = 8081
      management_port   = 9081
      cpu               = 1024
      memory            = 2048
      desired_count     = 2
      autoscaling_max   = 6
      security_group_id = module.network.policy_security_group_id
      log_group_name    = "/insurancehub/${local.environment}/policy-service"
      environment = merge(local.common_env, {
        SPRING_DATASOURCE_URL      = "jdbc:mysql://${module.rds.endpoint}:${module.rds.port}/policy_db"
        SPRING_DATASOURCE_USERNAME = "policy_app"
      })
      secrets = {
        SPRING_DATASOURCE_PASSWORD = module.secrets.db_password_secret_arns["policy-service"]
        HUB_INTERNAL_AUTH_SECRET   = module.secrets.internal_auth_secret_arn
      }
    }
    claims-service = {
      image             = "${module.ecr.repository_urls["claims-service"]}:${var.image_tags.claims-service}"
      container_port    = 8082
      management_port   = 9082
      cpu               = 1024
      memory            = 2048
      desired_count     = 2
      autoscaling_max   = 6
      security_group_id = module.network.claims_security_group_id
      log_group_name    = "/insurancehub/${local.environment}/claims-service"
      environment = merge(local.common_env, {
        SPRING_DATASOURCE_URL      = "jdbc:mysql://${module.rds.endpoint}:${module.rds.port}/claims_db"
        SPRING_DATASOURCE_USERNAME = "claims_app"
        POLICY_SERVICE_BASE_URL    = "http://policy-service:8081"
      })
      secrets = {
        SPRING_DATASOURCE_PASSWORD = module.secrets.db_password_secret_arns["claims-service"]
        HUB_INTERNAL_AUTH_SECRET   = module.secrets.internal_auth_secret_arn
      }
    }
  }

  tags = { Environment = local.environment }
}

module "kafka_self_managed" {
  count                         = var.kafka_mode == "self_managed" ? 1 : 0
  source                        = "../../modules/kafka-self-managed"
  name_prefix                   = local.name_prefix
  environment                   = local.environment
  aws_region                    = var.aws_region
  vpc_id                        = module.network.vpc_id
  private_app_subnet_ids        = module.network.private_app_subnet_ids
  kafka_security_group_id       = module.network.kafka_security_group_id
  ecs_cluster_id                = module.ecs.cluster_id
  service_connect_namespace_arn = module.ecs.service_connect_namespace_arn
  execution_role_arn            = module.ecs.task_role_arn
  task_role_arn                 = module.ecs.task_role_arn
  kms_key_arn                   = module.kms.key_arn
  task_cpu                      = 1024
  task_memory                   = 2048
  tags                          = { Environment = local.environment }
}

module "kafka_msk" {
  count                   = var.kafka_mode == "msk" ? 1 : 0
  source                  = "../../modules/kafka-msk"
  name_prefix             = local.name_prefix
  environment             = local.environment
  private_app_subnet_ids  = module.network.private_app_subnet_ids
  kafka_security_group_id = module.network.kafka_security_group_id
  broker_count            = 3
  tags                    = { Environment = local.environment }
}

module "alb" {
  source                = "../../modules/alb"
  name_prefix           = local.name_prefix
  vpc_id                = module.network.vpc_id
  public_subnet_ids     = module.network.public_subnet_ids
  alb_security_group_id = module.network.alb_security_group_id
  acm_certificate_arn   = var.acm_certificate_arn
  tags                  = { Environment = local.environment }
}

module "observability" {
  source                           = "../../modules/observability"
  name_prefix                      = local.name_prefix
  environment                      = local.environment
  aws_region                       = var.aws_region
  app_log_group_names              = module.ecs.log_group_names
  ecs_cluster_name                 = module.ecs.cluster_name
  ecs_service_names                = values(module.ecs.service_names)
  rds_instance_id                  = module.rds.instance_id
  alb_arn_suffix                   = module.alb.arn_suffix
  alert_email                      = var.alert_email
  log_reader_trusted_principal_arn = var.log_reader_trusted_principal_arn
  tags                             = { Environment = local.environment }
}
