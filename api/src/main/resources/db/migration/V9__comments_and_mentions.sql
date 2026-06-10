-- V9 — comments + @mentions (T-022). Three new tables plus a global, immutable
-- mention_handle on users.
--
-- AppCDS cold-start hard rule: every id has NO DEFAULT gen_random_uuid() — the
-- application assigns @Id via UUID.randomUUID() (no @GeneratedValue), so the
-- EntityManagerFactory never probes the DB for a generator during stage-3 boot.
-- FKs are ON DELETE NO ACTION (tombstone-only system; no cascade).
--
-- NB: activity_events.event_type CHECK already enumerates COMMENT_ADDED /
-- COMMENT_EDITED / COMMENT_DELETED (declared in the V8 CHECK + ActivityEventType
-- enum back in T-019), so this migration does NOT alter that constraint.

CREATE TABLE comments (
    id          uuid        PRIMARY KEY,
    ticket_id   uuid        NOT NULL REFERENCES tickets(id) ON DELETE NO ACTION,
    author_id   uuid        NOT NULL REFERENCES users(id)   ON DELETE NO ACTION,
    body        text        NOT NULL CHECK (length(body) <= 16384),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    deleted_at  timestamptz NULL
);

-- Hot path: newest-first comment list for one ticket (GET .../comments).
CREATE INDEX comments_ticket_created_idx ON comments (ticket_id, created_at DESC, id DESC);

-- Join tables: surrogate UUID @Id + UNIQUE(pair) (join_entity_surrogate pattern).
CREATE TABLE comment_mentions (
    id          uuid PRIMARY KEY,
    comment_id  uuid NOT NULL REFERENCES comments(id) ON DELETE NO ACTION,
    user_id     uuid NOT NULL REFERENCES users(id)    ON DELETE NO ACTION,
    CONSTRAINT comment_mentions_comment_user_key UNIQUE (comment_id, user_id)
);

CREATE TABLE ticket_mentions (
    id          uuid PRIMARY KEY,
    ticket_id   uuid NOT NULL REFERENCES tickets(id) ON DELETE NO ACTION,
    user_id     uuid NOT NULL REFERENCES users(id)   ON DELETE NO ACTION,
    CONSTRAINT ticket_mentions_ticket_user_key UNIQUE (ticket_id, user_id)
);

-- mention_handle (D3): global-unique, lowercase, derived ONCE from the email
-- local-part at registration and NEVER re-derived on email change.
ALTER TABLE users ADD COLUMN mention_handle text;

-- Backfill existing rows. The base slug is truncated to 60 chars BEFORE the
-- collision suffix is appended (NOT after) so two long local-parts can never
-- truncation-collide on the column limit; the row-number suffix is deterministic
-- within each base (ordered by created_at, id).
WITH numbered AS (
    SELECT
        id,
        base,
        ROW_NUMBER() OVER (PARTITION BY base ORDER BY created_at, id) AS rn
    FROM (
        SELECT
            id,
            created_at,
            CASE
                WHEN slug = '' THEN 'user'
                ELSE substring(slug FROM 1 FOR 60)
            END AS base
        FROM (
            SELECT
                id,
                created_at,
                lower(regexp_replace(split_part(email, '@', 1), '[^a-zA-Z0-9._-]', '', 'g')) AS slug
            FROM users
        ) s
    ) b
)
UPDATE users u
SET mention_handle = n.base || CASE WHEN n.rn = 1 THEN '' ELSE '-' || n.rn::text END
FROM numbered n
WHERE u.id = n.id;

ALTER TABLE users ALTER COLUMN mention_handle SET NOT NULL;
CREATE UNIQUE INDEX users_mention_handle_key ON users (mention_handle);
