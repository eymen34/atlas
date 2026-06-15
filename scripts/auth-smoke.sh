#!/usr/bin/env sh
# T-048 auth-flow smoke against the slimmed jlink image: register -> login -> refresh ->
# logout-all. Exercises the Postgres JDBC driver, Nimbus JOSE JWT verify, and the SASL/TLS
# crypto modules end-to-end - proof the jlink --add-modules list is complete.
#
# T-048 cutover: the refresh token is now the HttpOnly `atlas_refresh` cookie (Path=/api/auth),
# NEVER in the response body. /api/auth/refresh and /logout-all are body-less. This script uses
# a cookie jar to carry the refresh cookie.
#
# IMPORTANT: when BASE_URL is plain http:// (the local/CI dev stack), the cookie must be
# non-Secure or curl will refuse to send it back (curl has no localhost exception, unlike
# browsers). Set APP_AUTH_COOKIE_SECURE=false in the stack's env for this smoke. Prod runs
# over TLS with APP_AUTH_COOKIE_SECURE=true.
#
# POSIX sh (dash-safe): `set -eu`, NO `pipefail` (no stdin pipelines; jq reads from files).
# Invoke as: sh scripts/auth-smoke.sh
set -eu

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="smoke+$(date +%s)@example.com"
PASSWORD="SmokeTest!1234"
DISPLAY_NAME="Smoke Test"
JAR=/tmp/smoke-cookies.txt
rm -f "$JAR"

fail() {
  echo "$1"
  if [ -f /tmp/smoke.json ]; then cat /tmp/smoke.json; fi
  exit 1
}

REG=$(curl -sS -o /tmp/smoke.json -w '%{http_code}' -X POST "$BASE_URL/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"$DISPLAY_NAME\"}")
[ "$REG" = "201" ] || fail "register failed: HTTP $REG"

# login: capture the atlas_refresh cookie into the jar
LOGIN=$(curl -sS -c "$JAR" -o /tmp/smoke.json -w '%{http_code}' -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
[ "$LOGIN" = "200" ] || fail "login failed: HTTP $LOGIN"

ACCESS=$(jq -r '.accessToken' /tmp/smoke.json)
if [ -z "$ACCESS" ] || [ "$ACCESS" = "null" ]; then fail "no accessToken in login response"; fi

# T-048: the refresh token must NOT be in the body any more
[ "$(jq -r 'has("refreshToken")' /tmp/smoke.json)" = "false" ] \
  || fail "refreshToken leaked into login body (T-048 regression)"

# T-048: the atlas_refresh cookie must have been set on login
grep -q 'atlas_refresh' "$JAR" \
  || fail "atlas_refresh cookie not set on login (over http? set APP_AUTH_COOKIE_SECURE=false)"

# refresh: body-less; the cookie carries the refresh token. The jar is updated with the
# rotated cookie (-c) so a second refresh would also work.
REF=$(curl -sS -b "$JAR" -c "$JAR" -o /tmp/smoke.json -w '%{http_code}' \
  -X POST "$BASE_URL/api/auth/refresh")
[ "$REF" = "200" ] || fail "refresh failed: HTTP $REF (cookie not sent? check APP_AUTH_COOKIE_SECURE vs http)"

NEW_ACCESS=$(jq -r '.accessToken' /tmp/smoke.json)
if [ -z "$NEW_ACCESS" ] || [ "$NEW_ACCESS" = "null" ]; then fail "no accessToken in refresh response"; fi

# logout-all: bodyless + Bearer-gated -> 204
OUT=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/auth/logout-all" \
  -H "Authorization: Bearer $NEW_ACCESS")
[ "$OUT" = "204" ] || fail "logout-all failed: HTTP $OUT"

echo "auth-smoke OK"
