-- V2 — auth scaffold tables. T-009 lays down the schema; T-011/T-012 fill
-- in the application logic (issuance, rotation, revocation). No JPA
-- entities yet; queries are written against this schema directly by the
-- T-011 services.
--
-- All FKs to users(id) cascade on delete so removing a user takes their
-- credentials and tokens with them — there is no soft-delete tier yet.
-- gen_random_uuid() is PG17 core; no extension required.

CREATE TABLE refresh_tokens (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- token_hash stores the SHA-256 hex digest of the raw refresh token.
    -- The raw token is returned to the client ONCE on issuance and NEVER
    -- persisted. Lookup by hash on /api/auth/refresh; rotation replaces
    -- the row (replaced_by_id) so the previous hash is permanently dead.
    token_hash       CHAR(64)     NOT NULL UNIQUE,
    issued_at        timestamptz  NOT NULL DEFAULT now(),
    last_used_at     timestamptz  NULL,
    expires_at       timestamptz  NOT NULL,
    revoked_at       timestamptz  NULL,
    -- Self-FK: when a token is rotated, replaced_by_id points at the
    -- successor row. ON DELETE SET NULL so deleting the successor does
    -- not cascade-delete its predecessor.
    replaced_by_id   uuid         NULL REFERENCES refresh_tokens(id) ON DELETE SET NULL
);

COMMENT ON COLUMN refresh_tokens.token_hash IS
    'SHA-256 hex digest of the raw refresh token. Raw token is never stored.';

CREATE INDEX refresh_tokens_user_id_idx      ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_expires_at_idx   ON refresh_tokens (expires_at);
-- Partial index speeds up the hot path: "all live tokens for user X".
CREATE INDEX refresh_tokens_user_live_idx    ON refresh_tokens (user_id)
    WHERE revoked_at IS NULL;

CREATE TABLE password_credentials (
    user_id      uuid         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    -- BCrypt $2a/$2b/$2y output is fixed-width at 60 chars (4-byte version
    -- prefix + 22-byte cost + 22-byte salt + 31-byte hash, base64-encoded).
    bcrypt_hash  VARCHAR(60)  NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now()
);
