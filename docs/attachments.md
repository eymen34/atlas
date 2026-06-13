# Attachments (T-025)

How ticket file attachments work. The bytes live in S3/MinIO and NEVER traverse the
app on the HTTP path: the browser uploads to a presigned PUT URL and downloads from a
presigned GET URL; the server only issues URLs and verifies the result.

## Why TWO S3 endpoints (dual-endpoint presigning)

A presigned URL embeds the host it was **signed against** and cannot be rewritten
afterward. The server reaches the store at one address (`OBJECT_STORAGE_ENDPOINT` —
the Docker-internal `objectstore:9000`), but the browser must use another
(`OBJECT_STORAGE_PUBLIC_ENDPOINT` — `localhost:9000` in dev, the real S3/CDN host in
prod). So there are two beans (`S3Config`):

- **`S3Client`** → `endpointOverride = OBJECT_STORAGE_ENDPOINT`. Server-side only:
  the finalize HEAD and the thumbnail GET/PUT.
- **`S3Presigner`** → `endpointOverride = OBJECT_STORAGE_PUBLIC_ENDPOINT`. Signs
  every URL handed back to the client (PUT on init, GET on download).

Both use path-style addressing (MinIO) and `StaticCredentialsProvider` from
`OBJECT_STORAGE_ACCESS_KEY`/`SECRET_KEY`. Both are `@Lazy` and injected via `@Lazy`
points so the Dockerfile stage-3 no-DB AppCDS boot never constructs them; config is
read with empty-string `@Value` defaults and validated lazily at first use.

## Data model (V12)

`attachments` — one row per uploaded file:

| column                 | notes                                                       |
| ---------------------- | ----------------------------------------------------------- |
| `id`                   | surrogate UUID PK (app-generated, no `@GeneratedValue`)     |
| `ticket_id`            | FK → tickets(id), ON DELETE NO ACTION                       |
| `uploaded_by`          | FK → users(id), ON DELETE NO ACTION — the uploader          |
| `object_key`           | `text` UNIQUE — `tickets/{ticketId}/{uuid}/{sanitized-name}`|
| `filename`             | `text` — the original (sanitized) name                      |
| `content_type`         | `text` — the declared MIME type                             |
| `size_bytes`           | `bigint`                                                    |
| `status`               | `varchar(32)` CHECK ∈ {PENDING, READY, FAILED}              |
| `thumbnail_object_key` | `text` NULL — set by the thumbnail worker                   |
| `deleted_at`           | timestamptz NULL — soft delete                              |
| `created_at`           | timestamptz NOT NULL                                        |
| `finalized_at`         | timestamptz NULL — stamped at READY                         |

Index `ix_attachments_ticket_created (ticket_id, created_at DESC)` backs the list.
`Attachment` is the 16th `@Entity`; `AttachmentStatus` lives in `io.ngss.atlas.domain`.
BaseIT teardown deletes `attachments` FIRST.

## Upload lifecycle

1. **init** `POST /api/tickets/{id}/attachments/init` (any member) — validates the
   content-type allowlist and the **claimed** `sizeBytes` against
   `ATTACHMENT_MAX_SIZE_BYTES` (default 25 MiB), creates a `PENDING` row, returns
   `{attachmentId, uploadUrl (10-min presigned PUT, Content-Type signed), headers}`.
2. **PUT** — the browser PUTs the bytes directly to `uploadUrl` with the signed
   `Content-Type` header (raw XHR for progress; no Authorization — the URL is
   self-authorizing).
3. **finalize** `POST /api/attachments/{id}/finalize` (**uploader only** — a foreign
   PENDING row → 404) — HEADs the object and verifies the **actual** `contentLength`
   and `contentType` match the declared values: success → `READY` + finalized_at +
   `ATTACHMENT_ADDED` activity (synchronous, atomic); mismatch or missing object →
   `FAILED` + 400 (retry allowed). Idempotent: already-`READY` → 204 no-op.

### Size truth is the finalize HEAD, not the PUT

A presigned PUT **cannot enforce a maximum size** (only browser POST policies can).
init only checks the *claim*; the finalize HEAD's actual `contentLength` is the real
gate. State this wherever a size limit is implied.

### Content-type allowlist

`image/png`, `image/jpeg`, `image/gif`, `image/webp`; `application/pdf`,
`text/plain`, `text/markdown`, `text/csv`; and the OpenXML Office types (docx, xlsx,
pptx). The PUT signs `Content-Type`, so the stored object's type matches the claim;
finalize re-verifies via HEAD (defense for any direct-PUT path).

## Read & delete

- `GET /api/tickets/{id}/attachments` (any member) — bare array of `READY`,
  non-deleted rows, newest-first, each with a `hasThumbnail` flag.
- `GET /api/attachments/{id}/download-url?thumbnail=` (any member) — `{url}`, a 5-min
  presigned GET signed against the PUBLIC endpoint; `thumbnail=true` → the thumbnail
  (404 if none). Never 302-redirects (the JSON API stays JSON).
- `DELETE /api/attachments/{id}` (**uploader OR project ADMIN**) — soft-delete
  (`deleted_at`) + `ATTACHMENT_REMOVED` activity; a second delete → 404.

All endpoints are caller-scoped: the user id comes only from the SecurityContext.
Non-member → 404 everywhere; downloading another project's attachment → 404 (IDOR-safe).

## Thumbnails

Generated by an AFTER_COMMIT worker on `AttachmentFinalizedEvent`
(`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` +
try/catch + ERROR log — thumbnail loss is accepted, finalize is sacred). Only
`image/*` kinds. **JPEG, not webp** — the JVM has no webp encoder (and no webp
decoder without TwelveMonkeys, so webp uploads simply get no thumbnail). Pipeline:
GET the object (≤ max-size — the ONE bounded exception to "the app never holds file
bytes"), read the header dimensions and **reject a decompression bomb**
(`width*height*4 > 64 MiB`) BEFORE decoding pixels, resize longest-edge to 256,
encode JPEG q≈0.85, PUT to `thumbnails/{attachmentId}.jpg`, store
`thumbnail_object_key`.

Feature-flagged by `FEATURE_INLINE_THUMBNAILS_ENABLED` (`app.feature.inline-thumbnails.enabled`,
default true) — an INTERNAL flag (NOT in `/api/config/public`; it swaps server
behavior with no UI dependency). Off → uploads still finalize, just without a
thumbnail. Swapped for an outbox-driven worker in T-029.

## Soft-delete & orphaned objects

Delete is soft-delete only; the S3 object is **not** removed (no outbox yet —
deferred to T-029's sweeper). Soft-deleted rows get no presigned URLs, so the object
is unreachable through the app. Orphaned objects (deleted rows + never-finalized
PENDING uploads) are a documented known state a T-029 sweeper will reclaim.

## CORS & bucket privacy

- The compose `mc-init` runs `mc anonymous set download` — **LOCAL DEV ONLY**.
  Production buckets stay private and serve objects exclusively via short-lived
  presigned URLs.
- Browser→bucket CORS: modern MinIO is permissive by default; if a local e2e upload
  hits CORS, set `MINIO_API_CORS_ALLOW_ORIGIN=*` on the dev `objectstore` service.
  In production, configure the S3/R2 bucket CORS to allow `PUT`/`GET` from the app
  origin and expose `ETag` — that is the operator's responsibility.

## Packages

`io.ngss.atlas.attachment` (service, controller, repository, `S3Config`,
`ObjectStorageProperties`, the finalize event + thumbnail listener, exceptions) and
`io.ngss.atlas.attachment.dto` (request/response records). `Attachment` +
`AttachmentStatus` live in `io.ngss.atlas.domain`.

## Out of scope / future work

- T-029: outbox-driven S3 object deletion + a PENDING-expiry sweeper + the
  outbox-driven thumbnail worker.
- Attachment notifications, EXIF stripping, virus scanning, multi-file zip download,
  attachments on comments, pagination of the attachment list.
