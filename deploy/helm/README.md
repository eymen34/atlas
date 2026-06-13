# tracker Helm chart (T-030)

Deploys the single **atlas** application image plus an outbox-drain CronJob to Kubernetes.
You bring Postgres 17, an S3-compatible object store, and an SMTP relay — the chart wires
the app to them via a ConfigMap (non-secret env) and a Secret (credentials).

```sh
helm install tracker ./tracker --values my-values.yaml
```

## Image policy (no published image yet)

This chart **references** an image; none is published upstream (GHCR push is deferred). Either:

- build the production `/Dockerfile` (4-stage AppCDS) and push to **your own** registry —
  `docker build -t <registry>/atlas:<tag> . && docker push <registry>/atlas:<tag>` — then set
  `image.repository` / `image.tag`; or
- load a locally-built `atlas:latest` into your cluster (e.g. `kind load` / `minikube image load`).

`values.yaml` exposes `image.repository` (placeholder default `atlas`), `image.tag` (default
`latest`), `image.pullPolicy`, and optional `imagePullSecrets`. There is no GHCR coupling.

## Required values

The chart renders with defaults, but a real deployment MUST set these (the rest have sensible
defaults — see the inline comments in `values.yaml`):

| Concern | Where | Keys |
| --- | --- | --- |
| Database | `config.APP_DATABASE_URL` + secret | `APP_DATABASE_URL` (host/db), `APP_DATABASE_USERNAME`, `APP_DATABASE_PASSWORD` |
| JWT signing | secret | `JWT_SECRET` (>= 32 chars) |
| Outbox drain | secret | `OUTBOX_DRAIN_SHARED_SECRET` (shared with the CronJob automatically) |
| Object storage | `config.OBJECT_STORAGE_*` + secret | `OBJECT_STORAGE_ENDPOINT`, `OBJECT_STORAGE_PUBLIC_ENDPOINT`, `OBJECT_STORAGE_BUCKET`, `OBJECT_STORAGE_REGION`, `OBJECT_STORAGE_ACCESS_KEY`, `OBJECT_STORAGE_SECRET_KEY` |
| SMTP | `config.SMTP_*` + secret | `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, `SMTP_STARTTLS`, `SMTP_USERNAME`, `SMTP_PASSWORD` |
| Base URL | `config.APP_BASE_URL` | the public origin used in outgoing email links |

All env names mirror `/.env.example` exactly. `docs/configuration.md` is the full reference.

> `APP_DATABASE_STARTUP_CHECK_ENABLED` is **image-build-only** and is deliberately absent from
> every chart template — never set it in a runtime deployment.

## Supplying secrets

Two mutually exclusive paths (`tracker.secretName` resolves to whichever you choose; BOTH the
Deployment and the drain CronJob read from it):

1. **Production — bring your own Secret (`existingSecret`).** Manage the Secret out-of-band
   (sealed-secrets, external-secrets, Vault, or `kubectl create secret generic`) and point the
   chart at it. The chart then creates **no** Secret and only references yours:

   ```sh
   kubectl create secret generic tracker-prod \
     --from-literal=JWT_SECRET=... \
     --from-literal=OUTBOX_DRAIN_SHARED_SECRET=... \
     --from-literal=APP_DATABASE_USERNAME=atlas \
     --from-literal=APP_DATABASE_PASSWORD=... \
     --from-literal=SMTP_USERNAME=... --from-literal=SMTP_PASSWORD=... \
     --from-literal=OBJECT_STORAGE_ACCESS_KEY=... --from-literal=OBJECT_STORAGE_SECRET_KEY=...
   helm install tracker ./tracker --set existingSecret=tracker-prod --values my-values.yaml
   ```

   The Secret MUST contain every key listed in `values.yaml`'s `secrets:` block.

2. **Dev / quick-start — `--set`.** Leave `existingSecret` empty and let the chart render a
   Secret from the `secrets:` placeholders:

   ```sh
   helm install tracker ./tracker \
     --set secrets.JWT_SECRET=dev-secret-min-32-characters-long-xxxxx \
     --set secrets.OUTBOX_DRAIN_SHARED_SECRET=dev-outbox-secret \
     --set secrets.APP_DATABASE_USERNAME=atlas \
     --set secrets.APP_DATABASE_PASSWORD=atlas_secret \
     --set secrets.OBJECT_STORAGE_ACCESS_KEY=... --set secrets.OBJECT_STORAGE_SECRET_KEY=...
   ```

   Never commit real secret values into a values file.

## Outbox drain CronJob

`drainCron.enabled=true` (default) installs a CronJob (`schedule: "* * * * *"`) that POSTs
`/internal/tasks/drain-outbox` on the **in-cluster Service DNS** (`...svc.cluster.local`, never
`APP_BASE_URL`) with the `X-Internal-Secret` header sourced from the Secret — byte-identical in
endpoint + header to `deploy/cron/k8s-cronjob.yaml`. There is no in-process scheduler.

## Probes

Liveness -> `GET /health` (cheap, no DB); readiness -> `GET /ready` (DB ping). The chart never
points a probe at the Actuator aggregate (which pings DB + mail and would amplify a dependency
blip into pod kills). Tune via the `probes` block in `values.yaml`.

## Verifying locally

```sh
helm lint ./tracker
helm template tracker ./tracker > /dev/null
helm template tracker ./tracker -f ./tracker/ci-values.yaml > /dev/null   # exercises ingress
```

See `docs/deployment.md` (self-hosted mode) for the end-to-end install walkthrough.
