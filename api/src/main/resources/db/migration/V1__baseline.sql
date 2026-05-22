-- V1 baseline. The users table here is a STUB; T-011 will ADD columns but
-- will NOT rename id / email / created_at / updated_at. See /docs/migrations.md.

CREATE TABLE users (
    id          uuid        PRIMARY KEY,
    email       text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Case-insensitive email uniqueness via a functional index. We deliberately
-- do NOT rely on any non-stock Postgres extension (per
-- architecture_decisions:postgres_version, only what ships with stock
-- Postgres 17). Application code normalizes email to lowercase before
-- insert; the index defends against a missed normalization.
CREATE UNIQUE INDEX users_email_lower_key ON users (lower(email));
