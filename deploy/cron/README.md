# Outbox drain — cron manifests (T-029)

The outbox is **pull-drained**: an external scheduler calls
`POST /internal/tasks/drain-outbox` on a cadence (≈ every 60s). There is **no
in-process scheduler** (`realtime` / `background_work` decisions — no `@Scheduled`).

Every call must carry the shared secret header:

```
X-Internal-Secret: $OUTBOX_DRAIN_SHARED_SECRET
```

- No / wrong / missing secret → **403** (`InternalSecretFilter`).
- Correct secret → **200** with `{"processed":N,"succeeded":..,"failed":..,"retried":..}`.

The endpoint is **idempotent and safe to over-call**: each invocation claims a bounded
batch (≤ 50) with `FOR UPDATE SKIP LOCKED`, so concurrent or overlapping runs partition
the work instead of double-processing. An empty outbox returns `{"processed":0,...}`.

> Treat `OUTBOX_DRAIN_SHARED_SECRET` as a secret: inject it from your platform's secret
> store (k8s Secret, GCP Secret Manager, GitHub Actions secret, Fly secret), never inline.

## Examples in this directory

| File | Platform | Cadence |
| --- | --- | --- |
| [`k8s-cronjob.yaml`](./k8s-cronjob.yaml) | Kubernetes `CronJob` | every minute |
| [`cloud-scheduler.sh`](./cloud-scheduler.sh) | GCP Cloud Scheduler | every minute |
| [`github-actions-cron.yml`](./github-actions-cron.yml) | GitHub Actions | every 5 min (min granularity) |
| [`fly-scheduled-machine.md`](./fly-scheduled-machine.md) | Fly.io scheduled Machine | hourly preset |

Sub-minute drains: run a tiny sidecar loop (`while true; do curl …; sleep 60; done`) or a
k8s `CronJob` with `* * * * *`. Email/S3-delete latency tolerates a 60s cadence.
