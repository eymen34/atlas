-- V8 — activity_events (T-019). Append-only per-ticket activity log, written
-- SYNCHRONOUSLY and ATOMICALLY inside the same transaction as the originating
-- ticket change (by ActivityEventWriter, NOT via an event listener).
--
-- id has NO DEFAULT gen_random_uuid(): per the AppCDS cold-start hard rule the
-- application assigns the @Id via UUID.randomUUID() (no @GeneratedValue), so the
-- EntityManagerFactory never probes the DB for a generator during the stage-3
-- no-DB boot.
--
-- event_type is varchar(32) (NOT text) to line up with @Column(length=32) on the
-- entity field and keep Hibernate ddl-auto=validate unambiguous. payload is text
-- (arbitrary-length JSON serialized by Jackson in the service layer — NOT a jsonb
-- column, which entity_appcds_hard_rule forbids). FKs are ON DELETE NO ACTION
-- (tombstone-only system; no cascade).
--
-- IMPORTANT: the event_type CHECK enumerates all ActivityEventType values. A
-- future ticket adding a new event type MUST extend this CHECK in a new Flyway
-- migration AND add the value to the ActivityEventType enum — otherwise inserts of
-- the new type fail at runtime.

CREATE TABLE activity_events (
    id          uuid        PRIMARY KEY,
    ticket_id   uuid        NOT NULL REFERENCES tickets(id) ON DELETE NO ACTION,
    actor_id    uuid        NOT NULL REFERENCES users(id)   ON DELETE NO ACTION,
    event_type  varchar(32) NOT NULL CHECK (event_type IN (
                    'CREATED', 'STATUS_CHANGED', 'ASSIGNEE_CHANGED', 'PRIORITY_CHANGED',
                    'LABELS_CHANGED', 'COMMENT_ADDED', 'COMMENT_EDITED', 'COMMENT_DELETED',
                    'ATTACHMENT_ADDED', 'ATTACHMENT_REMOVED', 'LINK_ADDED', 'LINK_REMOVED')),
    payload     text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Hot path: the newest-first activity feed for one ticket (GET .../activity).
CREATE INDEX idx_activity_events_ticket_created ON activity_events (ticket_id, created_at DESC);
