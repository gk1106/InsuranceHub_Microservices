CREATE TABLE policy (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_num        VARCHAR(50)   NOT NULL,
  insp_id           VARCHAR(20)   NOT NULL,
  application_num   VARCHAR(50),
  cif               VARCHAR(512)  NOT NULL,
  account_num       VARCHAR(512),
  insured_name      VARCHAR(512)  NOT NULL,
  mobile_num        VARCHAR(512),
  address           VARCHAR(512),
  insurance_type    VARCHAR(50)   NOT NULL,
  insurance_name    VARCHAR(100),
  region_code       VARCHAR(20),
  region_name       VARCHAR(100),
  branch_code       VARCHAR(20),
  branch_name       VARCHAR(100),
  loan_acct_num     VARCHAR(512),
  spec_per_num      VARCHAR(50),
  spec_per_name     VARCHAR(100),
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version           BIGINT        NOT NULL DEFAULT 0,
  CONSTRAINT uk_policy_policy_num UNIQUE (policy_num)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;

CREATE TABLE policy_term (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_id           BIGINT        NOT NULL,
  term_no             INT           NOT NULL,
  term_type           VARCHAR(10)   NOT NULL,
  application_status  VARCHAR(30),
  issue_date          DATE,
  start_date          DATE          NOT NULL,
  expiry_date         DATE          NOT NULL,
  net_premium         DECIMAL(15,2) NOT NULL,
  gst_amt             DECIMAL(15,2),
  gross_premium       DECIMAL(15,2) NOT NULL,
  sum_insured         DECIMAL(15,2) NOT NULL,
  commission_per      DECIMAL(5,2),
  commission_amt      DECIMAL(15,2),
  req_id              VARCHAR(64)   NOT NULL,
  txn_id              CHAR(26)      NOT NULL,
  created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_policy_term_no UNIQUE (policy_id, term_no),
  CONSTRAINT fk_policy_term_policy FOREIGN KEY (policy_id) REFERENCES policy(id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;

CREATE TABLE processed_request (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  insp_id       VARCHAR(20)  NOT NULL,
  req_id        VARCHAR(64)  NOT NULL,
  service_type  VARCHAR(30)  NOT NULL,
  txn_id        CHAR(26)     NOT NULL,
  resource_key  VARCHAR(100),
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_processed_request UNIQUE (insp_id, req_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;
