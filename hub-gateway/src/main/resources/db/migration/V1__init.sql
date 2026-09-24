CREATE TABLE request_audit (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  txn_id        CHAR(26)     NOT NULL,
  -- Nullable: a pre-trust rejection (bad token, IP not allowed) never resolves these -
  -- docs/open-questions.md / docs/adr, audit-everything decision.
  req_id        VARCHAR(64),
  insp_id       VARCHAR(20),
  service_type  VARCHAR(30),
  resp_code     VARCHAR(10)  NOT NULL,
  latency_ms    BIGINT       NOT NULL,
  client_ip     VARCHAR(45)  NOT NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;
