# Deployment (T-030)

**The same `atlas:latest` image and the same environment contract work in all three modes
below.** atlas is a single stateless Spring Boot jar (the React frontend is baked into it);
deploying it anywhere means: run the image, point it at a Postgres 17 database, an
S3-compatible object store, and an SMTP relay via env vars, and trigger the outbox drain on a
cron. Only the *packaging* and *who runs the cron* differ between modes.

- Liveness probe: `GET /health` (cheap, no DB).
- Readiness probe: `GET /ready` (DB ping; 200 `READY` / 503 `NOT_READY`).
- Never probe the Actuator aggregate — it pings DB + mail and would amplify a dependency blip.
- Full env-var reference: [`docs/configuration.md`](./configuration.md). Internal engineering
  rationale (not needed to deploy): [`docs/architecture-decisions.md`](./architecture-decisions.md).

## Image policy

The production image is published to `ghcr.io/eymen34/atlas` (`:latest` on every `main` merge,
`:<git-sha>` for pinned deploys, `:<version>` on `v*` releases) by
`.github/workflows/publish.yml`. Pull it with `docker pull ghcr.io/eymen34/atlas:latest`.

- **Local dev (docker-compose):** `atlas:latest` refers to a **locally built** image unless you
  `docker pull ghcr.io/eymen34/atlas:latest` (and retag) first — `docker compose up --build`
  builds it from `/Dockerfile`.
- **Helm / Cloud Run:** the default `values.yaml` already points at GHCR; for Cloud Run pass the
  `ghcr.io/eymen34/atlas:<tag>` reference directly.

To build + push to your own registry instead:
`docker build -t <registry>/atlas:<tag> . && docker push <registry>/atlas:<tag>`.

---

## Mode 1 — Local development (Docker Compose)

```sh
cp .env.example .env
docker compose -f deploy/docker-compose.yml up --build --wait
```

Brings up Postgres, MinIO (+ bucket bootstrap), MailHog, and the app wired together. Ports:

| Service | URL | Purpose |
| --- | --- | --- |
| app | http://localhost:8080 | the API + bundled frontend |
| MailHog UI | http://localhost:8025 | captured outgoing email (SMTP sink on :1025) |
| MinIO console | http://localhost:9001 | object-store browser (S3 API on :9000) |

The outbox drain in local dev: hit it manually or loop it —
`curl -fsS -X POST -H "X-Internal-Secret: $OUTBOX_DRAIN_SHARED_SECRET" http://localhost:8080/internal/tasks/drain-outbox`.
Check health: `curl localhost:8080/health` and `curl localhost:8080/ready`.

---

## Mode 2 — Self-hosted (Docker or Kubernetes)

**Prerequisites:** PostgreSQL 17 (no extensions needed), an S3-compatible bucket (AWS S3,
Cloudflare R2, MinIO, …), an SMTP relay, and an **external cron** to drain the outbox (there is
no in-process scheduler).

### Docker run

```sh
docker run -d --name atlas -p 8080:8080 \
  -e APP_DATABASE_URL=jdbc:postgresql://db.internal:5432/atlas \
  -e APP_DATABASE_USERNAME=atlas -e APP_DATABASE_PASSWORD=… \
  -e JWT_SECRET=… -e OUTBOX_DRAIN_SHARED_SECRET=… -e APP_BASE_URL=https://atlas.example.com \
  -e OBJECT_STORAGE_ENDPOINT=https://s3.example.com \
  -e OBJECT_STORAGE_PUBLIC_ENDPOINT=https://objects.example.com \
  -e OBJECT_STORAGE_BUCKET=atlas-uploads -e OBJECT_STORAGE_REGION=us-east-1 \
  -e OBJECT_STORAGE_ACCESS_KEY=… -e OBJECT_STORAGE_SECRET_KEY=… \
  -e SMTP_HOST=smtp.example.com -e SMTP_PORT=587 -e SMTP_STARTTLS=true \
  -e SMTP_USERNAME=… -e SMTP_PASSWORD=… -e SMTP_FROM=noreply@example.com \
  <registry>/atlas:latest
```

Wire your platform's health checks to `/health` (liveness) and `/ready` (readiness). Drain the
outbox from a system cron (every minute):

```cron
* * * * * curl -fsS -X POST -H "X-Internal-Secret: $OUTBOX_DRAIN_SHARED_SECRET" https://atlas.example.com/internal/tasks/drain-outbox
```

### Kubernetes (Helm)

```sh
helm install tracker ./deploy/helm/tracker --set existingSecret=tracker-prod --values my-values.yaml
```

See [`deploy/helm/README.md`](../deploy/helm/README.md) for required values and the
BYO-secret (`existingSecret`) vs `--set` paths. The chart installs the Deployment (probes on
`/health` + `/ready`), a ClusterIP Service, optional Ingress, and the outbox-drain **CronJob**
(POSTs the in-cluster Service DNS with `X-Internal-Secret`). If you prefer a standalone
manifest over the chart's CronJob, [`deploy/cron/k8s-cronjob.yaml`](../deploy/cron/k8s-cronjob.yaml)
is the same drain as a plain `kubectl apply`.

---

## Mode 3 — SaaS (Google Cloud Run + Cloud Scheduler + Neon + R2/GCS)

Serverless, **not Helm** — but the SAME `atlas:latest` image and the SAME env contract. Cloud
Run runs the container; Neon is the managed Postgres; Cloud Storage or Cloudflare R2 is the
object store; Cloud Scheduler is the outbox cron.

1. **Database — Neon.** Create a Neon Postgres 17 project; use its pooled connection string as
   `APP_DATABASE_URL` (`jdbc:postgresql://<host>/<db>?sslmode=require`), with
   `APP_DATABASE_USERNAME` / `APP_DATABASE_PASSWORD`.

2. **Object store — R2 or GCS (S3-compatible).** Create a bucket and an access key/secret. Set
   `OBJECT_STORAGE_ENDPOINT` + `OBJECT_STORAGE_PUBLIC_ENDPOINT` to the S3 API endpoint
   (R2: `https://<account>.r2.cloudflarestorage.com`), plus bucket / region / keys.

3. **Deploy the image to Cloud Run** (push to Artifact Registry first):

   ```sh
   gcloud run deploy atlas \
     --image=<region>-docker.pkg.dev/<project>/atlas/atlas:latest \
     --port=8080 --cpu=1 --memory=1Gi --min-instances=1 \
     --set-env-vars=APP_DATABASE_URL=…,APP_BASE_URL=https://atlas.example.com,OBJECT_STORAGE_ENDPOINT=…,OBJECT_STORAGE_PUBLIC_ENDPOINT=…,OBJECT_STORAGE_BUCKET=…,OBJECT_STORAGE_REGION=…,SMTP_HOST=…,SMTP_PORT=587,SMTP_STARTTLS=true,SMTP_FROM=noreply@example.com \
     --set-secrets=JWT_SECRET=jwt-secret:latest,OUTBOX_DRAIN_SHARED_SECRET=outbox-secret:latest,APP_DATABASE_PASSWORD=db-pass:latest,APP_DATABASE_USERNAME=db-user:latest,OBJECT_STORAGE_ACCESS_KEY=os-ak:latest,OBJECT_STORAGE_SECRET_KEY=os-sk:latest,SMTP_USERNAME=smtp-user:latest,SMTP_PASSWORD=smtp-pass:latest
   ```

   Configure the Cloud Run **startup + liveness probes** to `GET /health` and (where the
   platform supports a separate readiness/HTTP startup check) `GET /ready`. Secrets come from
   Secret Manager via `--set-secrets`; non-secret config via `--set-env-vars`.

4. **Outbox drain — Cloud Scheduler** (every minute, same endpoint + header as everywhere):

   ```sh
   gcloud scheduler jobs create http atlas-outbox-drain \
     --schedule="* * * * *" --uri="https://atlas.example.com/internal/tasks/drain-outbox" \
     --http-method=POST --headers="X-Internal-Secret=$(gcloud secrets versions access latest --secret=outbox-secret)" \
     --attempt-deadline=50s --max-retry-attempts=0
   ```

   This mirrors [`deploy/cron/cloud-scheduler.sh`](../deploy/cron/cloud-scheduler.sh).

`APP_DATABASE_STARTUP_CHECK_ENABLED` is image-build-only — never set it in any of these modes.
For day-2 work (backup/restore/upgrade/outbox replay) see [`docs/operations.md`](./operations.md).
