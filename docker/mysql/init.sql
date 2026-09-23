-- Database-per-service, own user per schema, no cross-schema grants (CLAUDE.md rule 1).

CREATE DATABASE IF NOT EXISTS policy_db CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS claims_db CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS gateway_db CHARACTER SET utf8mb4;

CREATE USER IF NOT EXISTS 'policy_app'@'%' IDENTIFIED BY 'policy_app';
GRANT ALL PRIVILEGES ON policy_db.* TO 'policy_app'@'%';

CREATE USER IF NOT EXISTS 'claims_app'@'%' IDENTIFIED BY 'claims_app';
GRANT ALL PRIVILEGES ON claims_db.* TO 'claims_app'@'%';

CREATE USER IF NOT EXISTS 'gateway_app'@'%' IDENTIFIED BY 'gateway_app';
GRANT ALL PRIVILEGES ON gateway_db.* TO 'gateway_app'@'%';

FLUSH PRIVILEGES;
