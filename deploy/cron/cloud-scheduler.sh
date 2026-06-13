#!/usr/bin/env bash
# GCP Cloud Scheduler job that drains the outbox every minute (T-029).
#
# Prereqs: the secret lives in Secret Manager; Cloud Scheduler reads it at create time
# and bakes it into the job's static header. Re-run this script after rotating the secret.
#
#   OUTBOX_DRAIN_SHARED_SECRET="$(gcloud secrets versions access latest --secret=atlas-outbox-secret)" \
#   APP_URL="https://atlas.example.com" \
#   ./deploy/cron/cloud-scheduler.sh
set -euo pipefail

: "${APP_URL:?set APP_URL to the public base URL of the atlas API}"
: "${OUTBOX_DRAIN_SHARED_SECRET:?set OUTBOX_DRAIN_SHARED_SECRET (e.g. from Secret Manager)}"
LOCATION="${LOCATION:-us-central1}"

gcloud scheduler jobs create http atlas-outbox-drain \
  --location="${LOCATION}" \
  --schedule="* * * * *" \
  --time-zone="Etc/UTC" \
  --uri="${APP_URL}/internal/tasks/drain-outbox" \
  --http-method=POST \
  --headers="X-Internal-Secret=${OUTBOX_DRAIN_SHARED_SECRET}" \
  --attempt-deadline=50s \
  --max-retry-attempts=0   # the outbox owns per-row backoff; the next tick retries
