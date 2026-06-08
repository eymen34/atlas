-- V6 — tickets + per-project ticket numbering (T-017). The Ticket aggregate:
-- the sixth and seventh domain entities (Ticket, ProjectTicketCounter).
--
-- Soft-delete model mirrors projects: deleted_at NULL = live, NOT NULL = deleted.
-- All FKs are ON DELETE NO ACTION — this is a tombstone-only system (projects
-- soft-delete, users are not hard-deleted), so a cascade would mask a referential
-- bug rather than express intent (same rationale as V5 project_members).
--
-- Timestamps carry DEFAULT now() as a safety net, but TicketService ALWAYS sets
-- created_at/updated_at explicitly (Instant.now()) — no DB trigger, no JPA
-- @PrePersist/@PreUpdate. status/priority are text+CHECK enums mapped to the
-- TicketStatus/TicketPriority Java enums via @Enumerated(STRING) — no Postgres
-- enum type (keeps migrations forward-only, avoids ALTER TYPE churn), same as
-- project_members.role.

-- Per-project monotonic numbering. One row per project; next_number holds the
-- NEXT number to assign (seeded to 1 → the first ticket is ENG-1). Claimed via a
-- single atomic native UPDATE ... RETURNING in TicketService (race-safe: the
-- row lock serializes concurrent claimers), NOT a global Postgres sequence
-- (sequences would leak numbers across projects).
CREATE TABLE project_ticket_counters (
    project_id  uuid PRIMARY KEY REFERENCES projects(id) ON DELETE NO ACTION,
    next_number int  NOT NULL DEFAULT 1
);

CREATE TABLE tickets (
    id          uuid        PRIMARY KEY,
    project_id  uuid        NOT NULL REFERENCES projects(id) ON DELETE NO ACTION,
    number      int         NOT NULL,
    title       text        NOT NULL,  -- length enforced at the app layer (1..200)
    description text        NULL,      -- markdown; app-layer max 64KB
    status      text        NOT NULL DEFAULT 'TODO'
                    CHECK (status IN ('TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE')),
    priority    text        NOT NULL DEFAULT 'P2'
                    CHECK (priority IN ('P0', 'P1', 'P2', 'P3')),
    assignee_id uuid        NULL     REFERENCES users(id) ON DELETE NO ACTION,
    reporter_id uuid        NOT NULL REFERENCES users(id) ON DELETE NO ACTION,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz NULL,
    -- FULL uniqueness (no partial WHERE): a ticket number is permanent and never
    -- reused, even after soft-delete. Deliberately different from projects.key's
    -- partial-unique-on-live index (a deleted project key CAN be reused).
    CONSTRAINT uq_tickets_project_number UNIQUE (project_id, number)
);

-- List query hot paths (GET /api/projects/{id}/tickets). All partial WHERE
-- deleted_at IS NULL because the listing excludes soft-deleted tickets — keeps
-- the indexes lean and aligned with the only query that scans them.
CREATE INDEX idx_tickets_project_status   ON tickets (project_id, status)          WHERE deleted_at IS NULL;
CREATE INDEX idx_tickets_project_assignee ON tickets (project_id, assignee_id)     WHERE deleted_at IS NULL;
CREATE INDEX idx_tickets_project_updated  ON tickets (project_id, updated_at DESC) WHERE deleted_at IS NULL;

-- Backfill: every existing LIVE project gets a counter seeded to next_number = 1
-- (existing projects have zero tickets, so 1 is the next number to assign).
-- Soft-deleted projects (deleted_at IS NOT NULL) are skipped — they need no
-- counter. ON CONFLICT makes the block idempotent if re-run (V5 backfill pattern).
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT id AS project_id
             FROM projects
             WHERE deleted_at IS NULL
    LOOP
        INSERT INTO project_ticket_counters (project_id, next_number)
        VALUES (r.project_id, 1)
        ON CONFLICT (project_id) DO NOTHING;
    END LOOP;
END
$$;
