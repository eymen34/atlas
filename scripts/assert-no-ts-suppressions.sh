#!/usr/bin/env bash
# Fails if any TypeScript suppression comment appears under web/src.
# Suppressions are banned per T-003 must_pass_quality_gates.
set -uo pipefail

MATCHES=$(grep -rn -E "@ts-(ignore|nocheck|expect-error)" web/src/ 2>/dev/null || true)

if [ -n "$MATCHES" ]; then
  echo "ERROR: TypeScript suppression comment(s) detected under web/src/:" >&2
  echo "$MATCHES" >&2
  echo "" >&2
  echo "@ts-ignore / @ts-nocheck / @ts-expect-error are banned. Fix the underlying type error." >&2
  exit 1
fi

echo "OK: no @ts-ignore/@ts-nocheck/@ts-expect-error in web/src/."
exit 0
