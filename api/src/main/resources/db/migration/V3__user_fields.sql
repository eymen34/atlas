-- V3 — add display_name to users (T-011). V1 created users as a stub with
-- id / email / created_at / updated_at + the users_email_lower_key functional
-- unique index on lower(email). display_name is the ONLY column T-011 adds;
-- existing columns and that index are left untouched (see N1).
--
-- NOT NULL with no DEFAULT is safe here: the table is empty on first deploy
-- (T-011 ships the first registration path) and on every fresh Testcontainers
-- run. A future migration that needs to add NOT NULL columns to a populated
-- users table must supply a DEFAULT or backfill first.
ALTER TABLE users ADD COLUMN display_name varchar(80) NOT NULL;

COMMENT ON COLUMN users.display_name IS
    'User-facing display name (1-80 chars). Added in V3 (T-011); validated app-side.';
