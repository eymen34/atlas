#!/usr/bin/env sh
# T-036 auth-flow smoke against the slimmed jlink image: register -> login -> refresh ->
# logout-all. Exercises the Postgres JDBC driver, Nimbus JOSE JWT verify, and the SASL/TLS
# crypto modules end-to-end — proof the jlink --add-modules list is complete.
#
# POSIX sh (dash-safe): `set -eu`, NO `pipefail` — there are no stdin pipelines (jq reads
# from files), so pipefail is unnecessary and non-portable (shellcheck --shell=sh SC3040).
# Invoke as: sh scripts/auth-smoke.sh
#
# Paths are /api/auth/* (verified against AuthController). register requires a displayName
# and a 10-72 char password (RegisterRequest). logout-all is bodyless + Bearer-gated -> 204.
set -eu

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="smoke+$(date +%s)@example.com"
PASSWORD="SmokeTest!1234"
DISPLAY_NAME="Smoke Test"

fail() {
  echo "$1"
  if [ -f /tmp/smoke.json ]; then cat /tmp/smoke.json; fi
  exit 1
}

REG=$(curl -sS -o /tmp/smoke.json -w '%{http_code}' -X POST "$BASE_URL/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"$DISPLAY_NAME\"}")
[ "$REG" = "201" ] || fail "register failed: HTTP $REG"

LOGIN=$(curl -sS -o /tmp/smoke.json -w '%{http_code}' -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
[ "$LOGIN" = "200" ] || fail "login failed: HTTP $LOGIN"
ACCESS=$(jq -r '.accessToken' /tmp/smoke.json)
REFRESH=$(jq -r '.refreshToken' /tmp/smoke.json)
if [ -z "$ACCESS" ] || [ "$ACCESS" = "null" ]; then fail "no accessToken in login response"; fi
if [ -z "$REFRESH" ] || [ "$REFRESH" = "null" ]; then fail "no refreshToken in login response"; fi

REF=$(curl -sS -o /tmp/smoke.json -w '%{http_code}' -X POST "$BASE_URL/api/auth/refresh" \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")
[ "$REF" = "200" ] || fail "refresh failed: HTTP $REF"
NEW_ACCESS=$(jq -r '.accessToken' /tmp/smoke.json)
if [ -z "$NEW_ACCESS" ] || [ "$NEW_ACCESS" = "null" ]; then fail "no accessToken in refresh response"; fi

OUT=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/auth/logout-all" \
  -H "Authorization: Bearer $NEW_ACCESS")
[ "$OUT" = "204" ] || fail "logout-all failed: HTTP $OUT"

echo "auth-smoke OK"
