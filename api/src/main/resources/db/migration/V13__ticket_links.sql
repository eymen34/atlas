-- V13 — ticket links (T-026). Reciprocal relations between two tickets in the SAME
-- project. Each user action writes TWO rows (the direct relation + its inverse), paired
-- by (from,to) ↔ (to,from); there is no explicit pair id (deletion re-derives the
-- partner via inverse()). RELATES_TO is its own inverse.
--
-- The CHECK lists all five STORED relation types; the API accepts only the three
-- user-facing ones on create (BLOCKS, DUPLICATES, RELATES_TO) and rejects the inverses
-- (IS_BLOCKED_BY, IS_DUPLICATED_BY) with 400 — they are server-derived only.
--
-- AppCDS cold-start hard rule: id has NO DEFAULT gen_random_uuid() (app-assigned). All
-- FKs are ON DELETE NO ACTION. chk_no_self blocks self-links (DB backstop to the service
-- guard); uq_link enforces one row per (from, to, relation).

CREATE TABLE ticket_links (
    id             uuid        PRIMARY KEY,
    from_ticket_id uuid        NOT NULL REFERENCES tickets(id) ON DELETE NO ACTION,
    to_ticket_id   uuid        NOT NULL REFERENCES tickets(id) ON DELETE NO ACTION,
    relation       varchar(32) NOT NULL CHECK (relation IN (
                       'BLOCKS', 'IS_BLOCKED_BY', 'DUPLICATES', 'IS_DUPLICATED_BY',
                       'RELATES_TO')),
    created_by     uuid        NOT NULL REFERENCES users(id) ON DELETE NO ACTION,
    created_at     timestamptz NOT NULL,
    CONSTRAINT chk_no_self CHECK (from_ticket_id <> to_ticket_id),
    CONSTRAINT uq_link UNIQUE (from_ticket_id, to_ticket_id, relation)
);

CREATE INDEX ix_ticket_links_from ON ticket_links (from_ticket_id);
