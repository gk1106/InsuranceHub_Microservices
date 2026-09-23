CREATE TABLE claim (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  claim_num           VARCHAR(50)   NOT NULL,
  policy_num          VARCHAR(50)   NOT NULL,
  insp_id             VARCHAR(20)   NOT NULL,
  claim_type          VARCHAR(50)   NOT NULL,
  loss_desc           VARCHAR(1000),
  nature_of_loss      VARCHAR(100),
  loss_city           VARCHAR(100),
  date_of_loss        DATE          NOT NULL,
  intimation_date     DATE          NOT NULL,
  claimed_amt         DECIMAL(15,2) NOT NULL,
  settled_amt         DECIMAL(15,2),
  claim_status        VARCHAR(30)   NOT NULL,
  claim_code          VARCHAR(50),
  finalization_date   DATE,
  os_ageing           INT,
  require_details     TEXT,
  repu_cancel_date    DATE,
  reason_of_closure   VARCHAR(500),
  created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version             BIGINT        NOT NULL DEFAULT 0,
  CONSTRAINT uk_claim_claim_num UNIQUE (claim_num)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;

CREATE TABLE claim_status_history (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  claim_id      BIGINT        NOT NULL,
  from_status   VARCHAR(30),
  to_status     VARCHAR(30)   NOT NULL,
  claim_code    VARCHAR(50),
  settled_amt   DECIMAL(15,2),
  changed_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  req_id        VARCHAR(64)   NOT NULL,
  txn_id        CHAR(26)      NOT NULL,
  CONSTRAINT fk_claim_status_history_claim FOREIGN KEY (claim_id) REFERENCES claim(id)
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
