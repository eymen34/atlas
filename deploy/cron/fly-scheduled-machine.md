# Fly.io scheduled drain (T-029)

Fly Machines support a built-in `--schedule` preset (`hourly`, `daily`, `weekly`,
`monthly`). For a tighter cadence, run a tiny always-on Machine that loops with `sleep`.

## Option A — scheduled Machine (preset cadence)

Store the secret, then create a short-lived Machine that fires on the schedule:

```sh
fly secrets set OUTBOX_DRAIN_SHARED_SECRET="…" --app atlas

fly machine run curlimages/curl:8.10.1 \
  --app atlas \
  --schedule hourly \
  --env APP_URL="http://atlas.internal:8080" \
  --command 'sh -c "curl -fsS -X POST -H \"X-Internal-Secret: $OUTBOX_DRAIN_SHARED_SECRET\" \"$APP_URL/internal/tasks/drain-outbox\""'
```

`atlas.internal` is Fly's private 6PN DNS for the app, so the internal endpoint is never
exposed publicly.

## Option B — every-60s loop Machine (sub-hour cadence)

When hourly is too slow, run one persistent Machine that drains on a 60s loop:

```sh
fly machine run curlimages/curl:8.10.1 \
  --app atlas \
  --restart always \
  --env APP_URL="http://atlas.internal:8080" \
  --command 'sh -c "while true; do curl -fsS -X POST -H \"X-Internal-Secret: $OUTBOX_DRAIN_SHARED_SECRET\" \"$APP_URL/internal/tasks/drain-outbox\" || true; sleep 60; done"'
```

`SKIP LOCKED` makes overlapping/extra calls safe, and the outbox owns per-row exponential
backoff, so a missed tick simply retries on the next one.
