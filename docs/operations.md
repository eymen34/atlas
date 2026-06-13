# Operations runbook (T-030)

Day-2 operations for a deployed atlas: backup, restore, upgrade, and outbox triage. Applies to
all deployment modes ([`docs/deployment.md`](./deployment.md)); commands assume `psql`/`pg_dump`
reachable against your Postgres 17 and a CLI for your object store.

## 1. Backup

Two pieces of state: the Postgres database and the object-store bucket (attachments). Back up
both at the same cadence so a restore is internally consistent.

```sh
# Database — logical dump, custom format (compressed, parallel-restorable).
pg_dump --format=custom --no-owner --no-privileges \
  --dbname="$DATABASE_URL" --file="atlas-$(date -u +%Y%m%dT%H%M%SZ).dump"

# Push the dump to a backup bucket (S3-compatible).
aws s3 cp atlas-*.dump s3://atlas-backups/db/ --endpoint-url "$OBJECT_STORAGE_ENDPOINT"

# Attachments — sync the uploads bucket to the backup bucket.
aws s3 sync s3://atlas-uploads s3://atlas-backups/objects/ --endpoint-url "$OBJECT_STORAGE_ENDPOINT"
```

The schema is owned by Flyway; `--no-owner --no-privileges` keeps the dump portable across
managed-Postgres role models (Neon, RDS, …).

## 2. Restore

Restore the database first, then the objects (so attachment rows have their backing objects).

```sh
# Database — into a fresh, empty database (Flyway history is included in the dump).
pg_restore --no-owner --no-privileges --clean --if-exists \
  --dbname="$DATABASE_URL" atlas-20260101T000000Z.dump

# Attachments — back into the uploads bucket.
aws s3 sync s3://atlas-backups/objects/ s3://atlas-uploads --endpoint-url "$OBJECT_STORAGE_ENDPOINT"
```

Restore into a database at the **same or older** Flyway version, then start the app (or run an
upgrade — §3) to apply any newer migrations. Do not restore an old dump over a newer schema.

## 3. Upgrade path

atlas is a single stateless image; upgrades are a **rolling deploy** of a new image tag
(`kubectl set image` / `helm upgrade` / new Cloud Run revision). On boot the app runs Flyway.

- **Forward-only migrations.** Flyway 10.20 SQL migrations are forward-only (V1…V15 today;
  next is V16) — there is no down-migration. Roll *back* by deploying the previous image only
  if no new migration ran; once a migration applies, roll forward with a fix migration.
- **Concurrent-boot safety.** When several replicas boot at once, Flyway takes a
  `flyway_schema_history` row-lock so migrations **serialize** — exactly one replica applies a
  given version, the rest wait then no-op. This is the `migration_tool` decision, regression-
  tested by the T-004 `FlywayConcurrentBootIT` (dual-boot) in `mvn verify`. A rolling deploy is
  therefore safe without a separate migration job.
- **Sequence.** Push the new image → rolling restart → first replica migrates under the lock →
  readiness (`/ready`) flips green once the DB is reachable and migrated → old replicas drain.

Take a backup (§1) before any upgrade that includes a new migration.

## 4. Outbox — inspect & replay FAILED rows

Async work (email, S3 object deletes) flows through the `outbox` table, drained by
`POST /internal/tasks/drain-outbox`. A row that fails its handler 10 times lands in `FAILED` and
is no longer picked up.

```sql
-- Inspect failures (newest first).
SELECT id, kind, attempt_count, last_error, updated_at
FROM outbox WHERE status = 'FAILED' ORDER BY updated_at DESC;
```

Once you've fixed the cause (SMTP relay back up, bucket perms corrected, …), **replay** by
resetting the row to PENDING and re-running the drain:

```sql
UPDATE outbox
SET status = 'PENDING', next_attempt_at = now(), attempt_count = 0, last_error = NULL
WHERE id = '<row-id>';   -- or: WHERE status = 'FAILED' AND kind = 'EMAIL_NOTIFICATION'
```

```sh
curl -fsS -X POST -H "X-Internal-Secret: $OUTBOX_DRAIN_SHARED_SECRET" \
  https://atlas.example.com/internal/tasks/drain-outbox
```

The drain claims due PENDING rows with `FOR UPDATE SKIP LOCKED`, so a manual drain is safe to
run alongside the cron — they partition the work, never double-process.

## 5. Caveat — rows stuck in PROCESSING (manual reclaim)

A row can be left in `PROCESSING` if the process crashes (or the status write fails) **after**
the handler's irreversible side-effect succeeded — `claimBatch` only picks `PENDING`, so such a
row is **not auto-recovered** (tracked backlog: `stuck_processing_reclaim`; no reclaim sweep
ships yet). Inspect and reset manually:

```sql
-- Rows stuck PROCESSING longer than is plausible for a drain (tune the interval).
SELECT id, kind, attempt_count, updated_at
FROM outbox WHERE status = 'PROCESSING' AND updated_at < now() - interval '10 minutes';

-- Reclaim: send them back to PENDING so the next drain retries them.
-- CAUTION: the handler is at-least-once — for EMAIL_NOTIFICATION this may resend an email
-- that was actually delivered just before the crash; for ATTACHMENT_DELETE_OBJECT the S3
-- delete is idempotent (a missing key is treated as success), so it is always safe to replay.
UPDATE outbox
SET status = 'PENDING', next_attempt_at = now()
WHERE status = 'PROCESSING' AND updated_at < now() - interval '10 minutes';
```

Prefer reclaiming `ATTACHMENT_DELETE_OBJECT` freely; for `EMAIL_NOTIFICATION`, weigh a possible
duplicate email against a possibly-lost one before resetting.
