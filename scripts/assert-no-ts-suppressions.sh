#!/usr/bin/env bash
# Fails if any TypeScript suppression comment appears under web/src.
# Suppressions are banned per T-003 must_pass_quality_gates.
set -uo pipefail

# The generated API client (web/src/api/generated/) is excluded: it is
# gitignored, produced by `npm run codegen`, not hand-written, and ships its
# own /* eslint-disable */ plus an upstream // @ts-ignore in core/request.ts.
# This gate targets authored source only — and excluding it makes the check
# order-independent (it must not depend on whether codegen has run yet).
MATCHES=$(grep -rn -E "@ts-(ignore|nocheck|expect-error)" web/src/ --exclude-dir=generated 2>/dev/null || true)

if [ -n "$MATCHES" ]; then
  echo "ERROR: TypeScript suppression comment(s) detected under web/src/:" >&2
  echo "$MATCHES" >&2
  echo "" >&2
  echo "@ts-ignore / @ts-nocheck / @ts-expect-error are banned. Fix the underlying type error." >&2
  exit 1
fi

echo "OK: no @ts-ignore/@ts-nocheck/@ts-expect-error in web/src/."
exit 0
