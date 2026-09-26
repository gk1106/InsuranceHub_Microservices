-- One-time RDS bootstrap, mirroring docker/mysql/init.sql's schema-per-service /
-- user-per-schema pattern exactly (CLAUDE.md rule 1). Not applied by Terraform itself - see
-- infra/terraform/README.md for why. Run once per environment, from anywhere with network
-- access into the VPC (a bastion, an ECS exec session, or a temporary public-access window):
--
--   mysql -h <rds endpoint> -u admin -p < init-rds.sql
--
-- Passwords below are placeholders - substitute the real values from Secrets Manager
-- (/insurancehub/<env>/policy-service/db-password etc.) before running, then never store this
-- filled-in file anywhere.

CREATE DATABASE IF NOT EXISTS policy_db CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS claims_db CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS gateway_db CHARACTER SET utf8mb4;

CREATE USER IF NOT EXISTS 'policy_app'@'%' IDENTIFIED BY '${policy_app_password}';
GRANT ALL PRIVILEGES ON policy_db.* TO 'policy_app'@'%';

CREATE USER IF NOT EXISTS 'claims_app'@'%' IDENTIFIED BY '${claims_app_password}';
GRANT ALL PRIVILEGES ON claims_db.* TO 'claims_app'@'%';

CREATE USER IF NOT EXISTS 'gateway_app'@'%' IDENTIFIED BY '${gateway_app_password}';
GRANT ALL PRIVILEGES ON gateway_db.* TO 'gateway_app'@'%';

FLUSH PRIVILEGES;
