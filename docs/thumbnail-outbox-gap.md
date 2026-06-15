# Thumbnail generation moved to the outbox (T-040)

**Gap name:** `attachment_thumbnail_no_poll`

## What changed

T-025 generated an image attachment's JPEG thumbnail **inline**, in an
`@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` listener
(`AttachmentThumbnailListener`) that ran synchronously on the finalize request
thread. T-040 moves generation to the transactional outbox:

- `AttachmentService.finalizeUpload` now **enqueues** an `ATTACHMENT_THUMBNAIL`
  outbox row inside the same finalize transaction (atomic with the `READY`
  mark), instead of publishing `AttachmentFinalizedEvent`. The listener and the
  event record are deleted.
- `AttachmentThumbnailHandler` (an `OutboxHandler`) does the work when the drain
  runs: GET the source object, apply the decompression-bomb header guard,
  generate the 256px JPEG, PUT it to `thumbnails/{attachmentId}.jpg`, and record
  the key via `Attachment.attachThumbnail(...)`. The generation code (bomb guard
  + JPEG encode) is **ported verbatim** from the old listener.

The trigger and fault model moved; the produced thumbnail is byte-for-byte the
same.

## Latency widening (accepted, documented)

- **Before:** the thumbnail was written **AFTER_COMMIT on the request thread**,
  so it existed within milliseconds of finalize returning.
- **After:** the thumbnail appears only once the **outbox drain** processes the
  row. The drain is an external cron hitting `POST /internal/tasks/drain-outbox`
  — there is **no in-process scheduler**. The default cadence is **every minute**
  (`deploy/helm/tracker/values.yaml` → `drainCron.schedule: "* * * * *"`; same in
  `deploy/cron/k8s-cronjob.yaml` and Cloud Scheduler). So the steady-state
  visibility delay is **up to ~1 minute**, plus one backoff cycle (≈1–2 min more)
  if a transient S3 fault forces a retry.
- On coarser schedulers the lag is larger by construction — e.g. the GitHub
  Actions cron has a **5-minute** minimum granularity, and the Fly.io preset is
  hourly. Pick the trigger that matches the acceptable thumbnail latency.

The frontend already renders an attachment with no thumbnail gracefully
(`hasThumbnail=false` until the key is set), so the only user-visible effect is
that the thumbnail "pops in" a short while after upload rather than immediately.

## Fault handling (new)

The outbox gives the generation retry/backoff and crash-safety the inline
listener never had:

- **Transient** (S3 5xx, timeout, `SdkClientException`): the handler **throws**,
  so the outbox retries with exponential backoff and marks the row `FAILED` only
  after 10 attempts. The attachment keeps a NULL `thumbnail_object_key`
  throughout.
- **Permanent** (source `NoSuchKey`/4xx, no ImageIO reader, decompression bomb,
  corrupt/undecodable image): the handler returns normally → the row goes `SENT`
  and the attachment keeps a NULL `thumbnail_object_key`. This is **identical**
  to the old listener, which simply did not call `attachThumbnail(...)` on those
  paths. There is deliberately **no** distinct `SKIPPED`/`FAILED` thumbnail state
  on the attachment — "has a thumbnail" is exactly `thumbnail_object_key != null`,
  and downloads check only that.
- **Idempotent re-drain:** if the row is re-processed after the key is already
  set (re-claim, or a concurrent drain won), the handler short-circuits before
  any S3 call — no duplicate object, no duplicate write.

## Out of scope (unchanged from T-025)

- **No backfill.** Only attachments finalized *after* this change take the outbox
  path. Pre-existing attachments without a thumbnail are not retro-generated.
- webp/AVIF output, multiple sizes, on-the-fly resize. (webp uploads still get no
  thumbnail — the JVM has no webp decoder; that source yields no ImageIO reader,
  a permanent skip.)

## Naming note

The env var is still `FEATURE_INLINE_THUMBNAILS_ENABLED`
(`app.feature.inline-thumbnails.enabled`). The word **"inline" is now historical
and inaccurate** — generation is outbox-driven, not inline — but the name is kept
unchanged for env-contract compatibility. The flag remains **internal**: it is
read via `FeatureFlags.inlineThumbnailsEnabled()` and is **never** exposed through
`GET /api/config/public`. Off → finalize still succeeds, just enqueues nothing.
