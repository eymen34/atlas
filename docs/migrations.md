# Database migrations

Atlas uses [Flyway](https://flywaydb.org/) 10.20.x with SQL-first, forward-only
migrations stored under `api/src/main/resources/db/migration/`. Flyway runs on
application startup; there is no separate migrate step.

## Local development on Windows

The Testcontainers-backed integration tests (`FlywayConcurrentBootIT`,
`StartupFailFastIT$HappyPathContextLoads`) require a working Docker
environment that the `docker-java` HTTP client can talk to. **On Windows
with Docker Desktop, those probes currently fail**: Docker Desktop's auth
proxy returns a stripped HTTP 400 response to `docker-java`'s `/info` and
`/version` calls over the named pipe (`\\.\pipe\docker_engine`) and over
the optional TCP socket (`tcp://localhost:2375`), even though direct
`Invoke-WebRequest` calls to the same endpoints succeed. The symptom is:

```
java.lang.IllegalStateException: Could not find a valid Docker environment.
```

This is independent of Atlas — it is a `docker-java` vs Docker Desktop
4.61.0+ incompatibility on Windows. The tests are annotated
`@Testcontainers(disabledWithoutDocker = true)`, so they **self-skip on
Windows** rather than failing the build. They run cleanly on Linux,
including CI.

If you need to run the integration tests on a Windows workstation:

- **Recommended:** install WSL2 with an Ubuntu distro
  (`wsl --install -d Ubuntu`), enable Docker Desktop's WSL integration for
  that distro (Docker Desktop → Settings → Resources → WSL integration), and
  run `./mvnw -pl api -am verify` from inside the WSL shell. The Linux-side
  Unix socket (`/var/run/docker.sock` via the WSL integration bridge) is
  fully compatible with `docker-java`.
- **Alternative:** develop on a Linux machine or in a Linux container/VM.

The CI pipeline runs on `ubuntu-latest`, so the integration tests execute
end-to-end on every PR and on `main`.

## Forward-only policy

Migrations are **forward-only** — there are no down migrations and no
`undo_*.sql` files. The reasons are operational, not ideological:

- Atlas runs in rolling-deploy environments where old and new pods overlap
  during the rollout. A "down" migration that the previous pod doesn't
  understand cannot be safely applied while traffic is still flowing.
- Recovering from a bad migration is done by deploying a corrective forward
  migration (`V42__revert_bad_change.sql`), not by running an undo.
- Backups, not migrations, are the recovery mechanism for catastrophic data
  loss.

Rules:

1. **Never** rewrite a migration after it has been merged to `main`. Once a
   migration has run anywhere, its checksum is frozen — modifying it later
   causes Flyway to fail validation on the next boot.
2. **Never** rename or reorder migration files. Use the next available `Vn`
   number.
3. **Never** set `flyway.outOfOrder=true`. The default in-order requirement is
   what makes concurrent boots safe (see *Concurrent boots* below).
4. **Never** delete a migration. If a migration was a mistake, write a
   corrective follow-up migration.

## Destructive changes require a two-phase deploy

A single migration must not break the application that is currently in
production. Specifically:

- **Dropping a column** is a two-phase change:
  1. *Deprecate* — first deploy stops reading/writing the column. The
     migration in this deploy is allowed to be a no-op or to add a deprecation
     comment.
  2. *Remove* — a second deploy, after the first is fully rolled out, contains
     the `ALTER TABLE ... DROP COLUMN ...` migration.
- **Renaming a column** is the same dance, structured as add-new → backfill →
  cut over readers → cut over writers → drop-old.
- **Tightening a NOT NULL constraint** requires a backfill migration first, so
  the column is fully populated before the constraint is added.

## Concurrent boots are safe

During a rolling deploy, multiple pods may boot against the same database at
the same time and each pod will attempt to run pending migrations. This is
safe because Flyway acquires a row-level lock on `flyway_schema_history`
before applying any migration; the loser of the race waits, then re-checks
and finds nothing to do. The `FlywayConcurrentBootIT` integration test
asserts this concretely by booting two Spring contexts in parallel against
the same fresh Postgres container and verifying `flyway_schema_history` ends
with exactly one V1 row, `success = true`.

Do not bypass this with `flyway.outOfOrder=true` or by skipping validation —
those flags break the concurrent-safety guarantee.

## Stub-column stability contract

`V1__baseline.sql` introduces a `users` table with the columns `id`,
`email`, `created_at`, `updated_at`. The full user model is fleshed out in
T-011.

**Stub columns named in V1 will not be renamed in T-011 or any future
migration.** Forward-only migrations make column renames expensive (the
deprecate-then-remove dance above), so the column names are committed up
front and treated as load-bearing.

T-011 and later may freely *add* columns to `users` (for example
`password_hash`, `display_name`, `last_login_at`). They may not rename the
four stub columns and they may not drop them.

## Case-insensitive email uniqueness without extensions

`users.email` is plain `text`. Per
`architecture_decisions:postgres_version`, Atlas assumes only what stock
PostgreSQL 17 ships with — no extensions of any kind, including the
case-insensitive text type some teams reach for here. Case-insensitive
uniqueness is enforced by a functional unique index on `lower(email)`:

```sql
CREATE UNIQUE INDEX users_email_lower_key ON users (lower(email));
```

Application code is responsible for normalizing email addresses to lowercase
before insert/update. The functional index is the safety net for any code
path that misses the normalization.

## Adding a new migration

1. Pick the next available number: look at the highest `Vn__*.sql` and use
   `V(n+1)__short_description.sql`.
2. Write SQL only — do not use Flyway's Java callbacks.
3. Keep one logical change per file.
4. Run `./mvnw -pl api -am verify` locally; the dual-boot Testcontainers
   integration test will exercise your migration concurrently (on Linux/CI;
   skipped on Windows — see *Local development on Windows* above).
5. Commit the migration in the same PR as the application code that depends
   on it.
