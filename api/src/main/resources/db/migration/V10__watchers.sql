-- V10 — ticket watchers (T-023). A user "watches" a ticket to subscribe to its
-- notifications (T-024). One row per (ticket, user).
--
-- AppCDS cold-start hard rule: id has NO DEFAULT gen_random_uuid() — the
-- application assigns @Id via UUID.randomUUID() (no @GeneratedValue). FKs are
-- ON DELETE NO ACTION (tombstone-only system; no cascade). UNIQUE(ticket_id,
-- user_id) makes the idempotent watch (INSERT ... ON CONFLICT DO NOTHING) safe.

CREATE TABLE ticket_watchers (
    id          uuid        PRIMARY KEY,
    ticket_id   uuid        NOT NULL REFERENCES tickets(id) ON DELETE NO ACTION,
    user_id     uuid        NOT NULL REFERENCES users(id)   ON DELETE NO ACTION,
    created_at  timestamptz NOT NULL,
    CONSTRAINT uq_ticket_watchers UNIQUE (ticket_id, user_id)
);

-- Reverse lookup: "every ticket a user watches" (the T-024 notification fan-out).
CREATE INDEX ix_ticket_watchers_user_id ON ticket_watchers (user_id);
