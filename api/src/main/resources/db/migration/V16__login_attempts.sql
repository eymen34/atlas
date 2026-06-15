-- T-033: per-account + per-IP brute-force login throttle counters. Native-only — NO JPA
-- @Entity maps this (entity count stays 17); read/written via NamedParameterJdbcTemplate.
-- gen_random_uuid() is a PostgreSQL 17 core function (no extension).
CREATE TABLE login_attempts (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    attempt_key      VARCHAR(255) NOT NULL,
    key_type         VARCHAR(8)   NOT NULL CHECK (key_type IN ('ACCOUNT', 'IP')),
    attempt_count    INT          NOT NULL DEFAULT 0,
    first_attempt_at TIMESTAMPTZ  NOT NULL,
    locked_until     TIMESTAMPTZ
);

-- One counter row per (key, type): the UPSERT's ON CONFLICT target.
CREATE UNIQUE INDEX uq_login_attempts_key ON login_attempts (attempt_key, key_type);
-- The maintenance sweep deletes by first_attempt_at age.
CREATE INDEX ix_login_attempts_first_attempt ON login_attempts (first_attempt_at);

COMMENT ON TABLE login_attempts IS 'Per-account and per-IP brute-force throttle counters. No FK to users.';
