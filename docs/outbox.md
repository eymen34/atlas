# Transactional outbox (T-029)

The long-deferred background-work outbox: the async half of the T-024 email fan-out and the
T-025 attachment-object sweeper. Work is enqueued in the **same transaction** as the change
that triggers it, then drained out-of-band by an external cron hitting a shared-secret
endpoint. There is **no in-process scheduler** (`realtime` / `background_work`).

## The table (V15)

`outbox` is written/read **exclusively via native SQL** — it is NOT a JPA `@Entity` (entity
count stays **17**; a tsvector/jsonb/DB-function column type would break the stage-3 no-DB
AppCDS boot, `entity_appcds_hard_rule`). Because nothing maps it, `payload` is `jsonb`
(`infra_table_native_only`).

```
id              uuid PK            -- application-generated (UUID.randomUUID()), NOT a DB default
kind            varchar(64)        -- CHECK: EMAIL_NOTIFICATION | ATTACHMENT_DELETE_OBJECT | ATTACHMENT_THUMBNAIL
status          varchar(16)        -- CHECK: PENDING | PROCESSING | SENT | FAILED  (default PENDING)
payload         jsonb NOT NULL
attempt_count   int  default 0
next_attempt_at timestamptz default now()
last_error      text
created_at / updated_at / sent_at  timestamptz
```

`ix_outbox_status_next_attempt` is a **partial** index on `(status, next_attempt_at) WHERE
status IN ('PENDING','PROCESSING')` — it stays small as SENT rows accumulate, covering the
claim query's hot path. V15 also adds `users.email_notifications_enabled boolean NOT NULL
DEFAULT true`.

`ATTACHMENT_THUMBNAIL` is reserved in the CHECK but **never enqueued** in T-029 (D2: the
AFTER_COMMIT `AttachmentThumbnailListener` is left as-is); no handler is registered for it.

## Enqueue sites (in-transaction)

- **EMAIL_NOTIFICATION** — `NotificationEventListener` (the AFTER_COMMIT + REQUIRES_NEW fan-out
  handlers). After saving each in-app notification it enqueues an email row **gated on the
  recipient's `email_notifications_enabled`**, in the same REQUIRES_NEW transaction. The
  subject `[<projectKey>-<number>] <ticketTitle>` and body are built **at enqueue time** from
  the loaded ticket/project/actor and stored in the payload; the handler sends them verbatim.
- **ATTACHMENT_DELETE_OBJECT** — `AttachmentService.delete` enqueues the object key (+ thumbnail
  key, if any) in the same transaction that stamps `deleted_at`.

## Drain lifecycle

`POST /internal/tasks/drain-outbox` → `OutboxDrainService.drain(50)`:

1. **Claim** (`OutboxRepository.claimBatch`): one atomic
   `UPDATE … SET status='PROCESSING' WHERE id IN (SELECT id … WHERE status='PENDING' AND
   next_attempt_at <= now() ORDER BY next_attempt_at LIMIT :max FOR UPDATE SKIP LOCKED)
   RETURNING …`. `SKIP LOCKED` ⇒ concurrent drains claim **disjoint** subsets (never double-process).
2. **Handle, outside any transaction**: the matched `OutboxHandler` performs the side effect
   (SMTP send / S3 delete). Running the I/O transaction-free is deliberate — it avoids holding
   a DB transaction open across a network call and avoids the `jpa_rollback_only_trap` (a
   status-write failure can't poison a transaction that also did the side effect).
3. **Record outcome in its OWN `REQUIRES_NEW` transaction** containing a single UPDATE:
   - success → `markSent` (status SENT, `sent_at`),
   - failure with attempts remaining → `scheduleRetry` (back to PENDING, bumped `attempt_count`,
     backed-off `next_attempt_at`, `last_error`),
   - failure at the budget → `markFailed` (status FAILED).
   Every status write is wrapped exception-safe: a write failure is logged and the loop
   continues — it never propagates out of the drain (`status_write_is_not_an_exception`).

Response: `{processed, succeeded, failed, retried}` with the invariant
`processed == succeeded + failed + retried`.

### Backoff

`delay = min(60 · 2^attemptCountBefore, 3600)` seconds — keyed on the attempt count **before**
this attempt: before=0 → 60s, 1 → 120s, 2 → 240s, … capped at 1h. A row that has been attempted
**10** times (`before + 1 >= 10`) goes to **FAILED** instead of being rescheduled.

## Security

`/internal/**` is outside `/api/**` and stays `denyAll` except `POST /internal/tasks/drain-outbox`,
which requires `ROLE_INTERNAL`. `InternalSecretFilter` (mirroring `JwtAuthenticationFilter`)
grants it only on a constant-time (`MessageDigest.isEqual`) match of `X-Internal-Secret` against
`OUTBOX_DRAIN_SHARED_SECRET`. A missing/wrong/blank secret yields a non-anonymous,
empty-authority token so the request is **403** (the access-denied handler), never the 401
entry point. A blank/unset secret never matches, so the stage-3 no-DB AppCDS boot is safe — and
the endpoint stays forbidden.

## Email

`spring-boot-starter-mail` + a plaintext `SimpleMailMessage` (no Thymeleaf). The
`JavaMailSenderImpl` is built lazily and connects only on `send()`, so a blank/unreachable
SMTP host never blocks boot. Local dev points at MailHog (`deploy/docker-compose.yml`: 1025
SMTP, 8025 web UI). Config: `SMTP_HOST/PORT/USERNAME/PASSWORD/FROM/STARTTLS`, `APP_BASE_URL`
(deep-link origin) — all already in `.env.example`.

## Operating it

External cron calls the drain on a ≈60s cadence — see `deploy/cron/` for Kubernetes CronJob,
GCP Cloud Scheduler, GitHub Actions, and Fly.io examples. The endpoint is idempotent and safe
to over-call.

## Out of scope (T-029)

Non-email/non-S3 work kinds. (The PENDING-upload-expiry sweep, originally deferred here, ships in
T-053 — see below.)

## Maintenance sweep (T-053)

A **separate, less-frequent** external cron (every 10 min vs the per-minute drain) calls
`POST /internal/tasks/run-maintenance` — same `InternalSecretFilter` / `X-Internal-Secret` gate as
the drain. It returns `{reclaimedToPending, reclaimedToFailed, expiredUploads}` and does two things:

**1. Reclaim stuck PROCESSING rows.** A row claimed for processing whose worker crashed before it
wrote a terminal status sits in `PROCESSING` forever. The sweep finds `PROCESSING` rows whose
`updated_at` is older than `OUTBOX_RECLAIM_AFTER_MINUTES` (default 15) and, **uniformly across all
kinds**, returns them to `PENDING` (`next_attempt_at = now()`) if `attempt_count < 10`, or moves
them to `FAILED` if the attempt budget is exhausted. `FAILED` is terminal — `next_attempt_at` is
left untouched. Every `UPDATE` sets `updated_at = now()` explicitly and uses `FOR UPDATE SKIP
LOCKED`, so a concurrent drain never double-touches a row.

> **AT-LEAST-ONCE TRADE-OFF.** Reclaim cannot tell "worker crashed before doing the work" from
> "worker did the work but crashed before the `SENT` write". Reclaiming the latter re-runs the
> handler. For `EMAIL_NOTIFICATION` that means a **rare double-send** on a crash — an accepted
> trade-off (the alternative, never-retrying, silently drops mail). `ATTACHMENT_DELETE_OBJECT` is
> idempotent (a delete of an already-gone key is `NoSuchKey` = success), so its reclaim is harmless.

> **THRESHOLD RULE.** `OUTBOX_RECLAIM_AFTER_MINUTES` **must** be set significantly larger than the
> maximum handler runtime. The 15-min default is >> the seconds-scale email/S3 handlers. Lowering
> it toward handler runtime risks reclaiming an **in-flight** row and causing double-execution.

**2. Expire abandoned PENDING uploads.** An upload that issued a presigned PUT but never finalized
leaves an `attachments` row stuck `PENDING` (and possibly an orphan S3 object). The sweep finds
`PENDING` rows with `deleted_at IS NULL` older than `ATTACHMENT_PENDING_EXPIRY_HOURS` (default 24)
and soft-deletes each via the **existing** `AttachmentService` system path, which enqueues
**exactly one** `ATTACHMENT_DELETE_OBJECT` (no second enqueue) atomically with the `deleted_at`
write. A re-run is a no-op: the row is no longer `PENDING`/live, so it is not re-selected and not
re-enqueued.

**Bounding & timekeeping.** Each sweep is `LIMIT`-bounded by `MAX_BATCH = 500`; a backlog larger
than that drains over multiple cron ticks (partial clearance per run). All time comparisons use DB
`now()` via `make_interval(...)`, never the application clock. The endpoint runs with
`propagation = NEVER` so each reclaim statement autocommits and each per-attachment soft-delete is
its own atomic transaction (partial progress survives a mid-loop failure).

> **TODO (follow-up).** Add covering indexes on `outbox(status, updated_at)` and
> `attachments(status, created_at, deleted_at)` once a backlog routinely exceeds `MAX_BATCH`. D5
> prohibited a migration in T-053; the `LIMIT` + 10-min cadence mitigate today. [follow-up ref TBD]
