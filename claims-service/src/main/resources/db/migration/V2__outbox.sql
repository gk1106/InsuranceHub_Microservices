CREATE TABLE outbox_event (
  id              CHAR(36)      NOT NULL PRIMARY KEY,
  aggregate_type  VARCHAR(30)   NOT NULL,
  aggregate_id    VARCHAR(50)   NOT NULL,
  event_type      VARCHAR(50)   NOT NULL,
  payload         JSON          NOT NULL,
  traceparent     VARCHAR(55),
  attempts        INT           NOT NULL DEFAULT 0,
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at    TIMESTAMP     NULL,
  INDEX idx_outbox_relay (published_at, created_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;
