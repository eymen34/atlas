-- V12 — ticket attachments (T-025). One row per uploaded file; the bytes live in
-- S3/MinIO, never in Postgres and never in the app heap on the HTTP path.
--
-- Lifecycle: a row is created PENDING at upload-init (presigned PUT issued), then
-- READY once the server HEADs the uploaded object and verifies size+content-type,
-- or FAILED on mismatch. status is a varchar(32) + CHECK (AttachmentStatus enum in
-- io.ngss.atlas.domain — NotificationKind/V8 precedent), NOT a DB enum type.
--
-- AppCDS cold-start hard rule: id has NO DEFAULT gen_random_uuid() (app-assigned via
-- UUID.randomUUID()). All FKs are ON DELETE NO ACTION (no cascade) — consistent with
-- every other table.
--
-- Soft-delete only (deleted_at): the S3 object is NOT removed here. Object deletion +
-- a PENDING-expiry sweeper are deferred to the T-029 outbox; soft-deleted rows get no
-- presigned URLs, so the object is unreachable through the app (documented orphan
-- state — see docs/attachments.md).

CREATE TABLE attachments (
    id                   uuid        PRIMARY KEY,
    ticket_id            uuid        NOT NULL REFERENCES tickets(id) ON DELETE NO ACTION,
    uploaded_by          uuid        NOT NULL REFERENCES users(id)   ON DELETE NO ACTION,
    object_key           text        NOT NULL UNIQUE,
    filename             text        NOT NULL,
    content_type         text        NOT NULL,
    size_bytes           bigint      NOT NULL,
    status               varchar(32) NOT NULL CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    thumbnail_object_key text        NULL,
    deleted_at           timestamptz NULL,
    created_at           timestamptz NOT NULL,
    finalized_at         timestamptz NULL
);

-- The attachment list for a ticket: READY, non-deleted, newest-first.
CREATE INDEX ix_attachments_ticket_created ON attachments (ticket_id, created_at DESC);
