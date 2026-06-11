-- V11 — in-app notifications (T-024). One row per (recipient, triggering change).
-- Written ASYNCHRONOUSLY by NotificationEventListener in an AFTER_COMMIT,
-- REQUIRES_NEW transaction (so a fan-out failure never rolls back the originating
-- ticket/comment write).
--
-- AppCDS cold-start hard rule: id has NO DEFAULT gen_random_uuid() (app-assigned).
-- FKs are ON DELETE NO ACTION (tombstone-only system; deleting a referenced user /
-- ticket / activity row is blocked while notifications reference it — acceptable,
-- there are no deletion endpoints). payload is text (json_payload_as_text), holding
-- a Jackson-serialized PayloadV1 (actorId denormalized so it survives even if the
-- actor is later removed).

CREATE TABLE notifications (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users(id)           ON DELETE NO ACTION,
    kind            varchar(32) NOT NULL CHECK (kind IN (
                        'ASSIGNED', 'MENTIONED_TICKET', 'MENTIONED_COMMENT',
                        'WATCHED_STATUS_CHANGED')),
    ticket_id       uuid        NOT NULL REFERENCES tickets(id)         ON DELETE NO ACTION,
    source_event_id uuid        NULL     REFERENCES activity_events(id)  ON DELETE NO ACTION,
    payload         text        NOT NULL,
    read_at         timestamptz NULL,
    created_at      timestamptz NOT NULL
);

-- Hot path: a user's unread feed, newest-first (the bell poll + badge query).
CREATE INDEX ix_notifications_user_unread ON notifications (user_id, read_at, created_at DESC);
