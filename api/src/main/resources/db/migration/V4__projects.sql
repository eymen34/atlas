-- V4 — projects table (T-014). First domain aggregate after User/auth.
--
-- Soft-delete model: deleted_at NULL = live, NOT NULL = deleted. The key
-- uniqueness constraint is PARTIAL (WHERE deleted_at IS NULL) so a soft-deleted
-- project's key can be reused by a new live project.
--
-- Timestamps carry a DEFAULT now() as a safety net, but ProjectService ALWAYS
-- sets created_at/updated_at explicitly (Instant.now()) — there is no DB trigger
-- and no JPA @PrePersist/@PreUpdate. The DEFAULT only fires if a future code path
-- inserts without supplying them.
--
-- created_by → users(id) is the only FK. T-015 will widen ownership to a
-- project_members join table; no project_members / tenant_id here.

CREATE TABLE projects (
    id          uuid        PRIMARY KEY,
    key         text        NOT NULL,
    name        text        NOT NULL,
    description text        NULL,
    created_by  uuid        NOT NULL REFERENCES users(id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz NULL,
    CONSTRAINT projects_key_format_chk CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$')
);

-- Uniqueness among live (non-deleted) projects only — lets a deleted key be reused.
CREATE UNIQUE INDEX projects_key_unique ON projects (key) WHERE deleted_at IS NULL;

-- Hot path: "all live projects for caller X" (GET /api/projects).
CREATE INDEX projects_created_by_idx ON projects (created_by) WHERE deleted_at IS NULL;
