# Configuration reference (T-030)

Every environment variable atlas reads, mirroring [`/.env.example`](../.env.example) exactly.
The **same names** apply in all three deployment modes (see [`docs/deployment.md`](./deployment.md));
only their *source* differs (a `.env` file locally, a ConfigMap+Secret under Helm, `--set-env-vars`
/ `--set-secrets` on Cloud Run).

Legend — **Modes**: `local` = Docker Compose, `self` = self-hosted Docker/K8s, `saas` = Cloud Run.
`all` = the app contract used everywhere. **Secret**: yes ⇒ store in a Secret/secret-manager, never
a ConfigMap or values file.

## Application — database

| Name | Type | Default | Required | Secret | Modes |
| --- | --- | --- | --- | --- | --- |
| `APP_DATABASE_URL` | JDBC URL | `jdbc:postgresql://db:5432/atlas` | yes | no | all |
| `APP_DATABASE_USERNAME` | string | `atlas` | yes | yes | all |
| `APP_DATABASE_PASSWORD` | string | `atlas_secret` | yes | yes | all |
| `DB_POOL_MAX` | int | `10` | no | no | all |
| `DB_POOL_MIN` | int | `2` | no | no | all |

> `APP_DATABASE_STARTUP_CHECK_ENABLED` — **image-build-only**, deliberately NOT in `.env.example`
> and NOT in any chart template. Defaults to `true` (the only supported value in any deployment);
> it is set `false` *exclusively* inside stage 3 of `/Dockerfile` so the AppCDS warm-up boots
> without a database. **NEVER set it `false` in a runtime deployment** — doing so silently
> disables the fail-fast DB ping.

## Application — object storage (S3-compatible)

| Name | Type | Default | Required | Secret | Modes |
| --- | --- | --- | --- | --- | --- |
| `OBJECT_STORAGE_ENDPOINT` | URL | `http://objectstore:9000` | yes | no | all |
| `OBJECT_STORAGE_PUBLIC_ENDPOINT` | URL | `http://localhost:9000` | yes | no | all |
| `OBJECT_STORAGE_REGION` | string | `us-east-1` | no | no | all |
| `OBJECT_STORAGE_BUCKET` | string | `atlas-uploads` | yes | no | all |
| `OBJECT_STORAGE_ACCESS_KEY` | string | `minioadmin` | yes | yes | all |
| `OBJECT_STORAGE_SECRET_KEY` | string | `minioadmin123` | yes | yes | all |
| `ATTACHMENT_MAX_SIZE_BYTES` | int (bytes) | `26214400` (25 MiB) | no | no | all |

`ENDPOINT` is the server-side address; `PUBLIC_ENDPOINT` is the host embedded in presigned URLs
handed to the browser (must be client-resolvable). In prod they often point to the same S3 host.

## Application — auth

| Name | Type | Default | Required | Secret | Modes |
| --- | --- | --- | --- | --- | --- |
| `JWT_SECRET` | string (>= 32 chars) | `change-me-in-production-min-32-chars` | yes | yes | all |
| `JWT_ACCESS_TTL_SECONDS` | int | `900` | no | no | all |
| `REFRESH_TOKEN_TTL_DAYS` | int | `30` | no | no | all |
| `BCRYPT_COST` | int | `12` | no | no | all |

## Application — SMTP (email via the outbox)

| Name | Type | Default | Required | Secret | Modes |
| --- | --- | --- | --- | --- | --- |
| `SMTP_HOST` | host | `localhost` | yes | no | all |
| `SMTP_PORT` | int | `1025` | yes | no | all |
| `SMTP_USERNAME` | string | _(empty)_ | no¹ | yes | all |
| `SMTP_PASSWORD` | string | _(empty)_ | no¹ | yes | all |
| `SMTP_FROM` | email | `noreply@example.com` | yes | no | all |
| `SMTP_STARTTLS` | bool | `false` | no | no | all |

¹ Username/password are blank for an open local relay (MailHog); required for any real relay.

## Application — outbox, base URL, feature flags

| Name | Type | Default | Required | Secret | Modes |
| --- | --- | --- | --- | --- | --- |
| `OUTBOX_DRAIN_SHARED_SECRET` | string | `change-me-outbox-secret` | yes | yes | all |
| `APP_BASE_URL` | URL | `http://localhost:8080` | yes | no | all |
| `FEATURE_WATCHERS_ENABLED` | bool | `true` | no | no | all |
| `FEATURE_INLINE_THUMBNAILS_ENABLED` | bool | `true` | no | no | all |

The external cron sends `OUTBOX_DRAIN_SHARED_SECRET` as the **`X-Internal-Secret`** request header
to `POST /internal/tasks/drain-outbox` (matching `InternalSecretFilter` and the `deploy/cron/`
manifests).

## Application — API docs

| Name | Type | Default | Required | Secret | Modes |
| --- | --- | --- | --- | --- | --- |
| `API_DOCS_ENABLED` | bool | `true` | no | no | all (dev/CI `true`; prod chart `false`) |

When `false`, springdoc deregisters the routes so `/swagger-ui.html` and `/v3/api-docs` return
**404** (existence-hiding, NOT an auth-gated 401). `.env.example` defaults `true`; the Helm chart
(`apiDocsEnabled`) defaults `false` to hide docs in production. The build-time spec dump relies on
the `true` default, so `api/src/main/resources/openapi/openapi.json` is unaffected.

## Application — mentions

| Name | Type | Default | Required | Secret | Modes |
| --- | --- | --- | --- | --- | --- |
| `MENTION_MAX_CANDIDATES` | int | `50` | no | no | all |

Advisory soft cap on the number of **distinct** `@mention` candidates the server resolves per
comment/ticket body (T-043). Handles beyond the cap are silently ignored — no error, and the full
body text is stored unchanged; only member-resolution and the downstream notification fan-out are
bounded. A cheap abuse guard, **not** a feature flag (so it is absent from `/api/config/public`).
Non-positive values (including `Integer.MIN_VALUE`) resolve nobody — they are clamped to `0`.

## Local-stack-only (Docker Compose containers, not app config)

These configure the bundled `db` / `objectstore` containers in `deploy/docker-compose.yml`. In
self-hosted/SaaS modes you use a managed Postgres and object store, so these do not apply.

| Name | Type | Default | Required | Secret | Modes |
| --- | --- | --- | --- | --- | --- |
| `POSTGRES_DB` | string | `atlas` | local only | no | local |
| `POSTGRES_USER` | string | `atlas` | local only | no | local |
| `POSTGRES_PASSWORD` | string | `atlas_secret` | local only | yes | local |
| `MINIO_ROOT_USER` | string | `minioadmin` | local only | no | local |
| `MINIO_ROOT_PASSWORD` | string | `minioadmin123` | local only | yes | local |

## Frontend build-time (baked into the bundle at `npm run build`)

`VITE_`-prefixed vars are read by Vite when the frontend is compiled (the bundle is then baked
into the image), so they are NOT runtime container env. Override before building a custom image.

| Name | Type | Default | Required | Secret | Modes |
| --- | --- | --- | --- | --- | --- |
| `VITE_ACCESS_TTL_SECONDS` | int | `900` | no | no | build |
| `VITE_NOTIFICATION_POLL_INTERVAL_MS` | int (ms) | `30000` | no | no | build |
