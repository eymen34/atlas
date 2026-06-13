-- T-029: transactional outbox for async email fan-out + S3 object deletion.
-- Read/written EXCLUSIVELY via native SQL (no JPA @Entity), so payload may be jsonb
-- (infra_table_native_only). id is application-generated (UUID.randomUUID()), NOT a DB
-- DEFAULT — consistent with the entity_appcds_hard_rule app-UUID convention.
CREATE TABLE outbox (
  id              uuid         PRIMARY KEY,
  kind            varchar(64)  NOT NULL CHECK (kind IN ('EMAIL_NOTIFICATION','ATTACHMENT_DELETE_OBJECT','ATTACHMENT_THUMBNAIL')),
  status          varchar(16)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED')),
  payload         jsonb        NOT NULL,
  attempt_count   int          NOT NULL DEFAULT 0,
  next_attempt_at timestamptz  NOT NULL DEFAULT now(),
  last_error      text,
  created_at      timestamptz  NOT NULL DEFAULT now(),
  updated_at      timestamptz  NOT NULL DEFAULT now(),
  sent_at         timestamptz
);

-- Partial index covering the claim query's hot path: the drain selects
-- WHERE status='PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at.
-- Indexing only the live statuses keeps it small as SENT rows accumulate.
CREATE INDEX ix_outbox_status_next_attempt ON outbox (status, next_attempt_at)
  WHERE status IN ('PENDING','PROCESSING');

-- Per-recipient email opt-out (T-029). DEFAULT true so existing rows and any
-- column-listed INSERT that omits it are unaffected (not_null_column_fixture_grep).
ALTER TABLE users ADD COLUMN email_notifications_enabled boolean NOT NULL DEFAULT true;
