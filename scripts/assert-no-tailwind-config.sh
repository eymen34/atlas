#!/usr/bin/env bash
# Fails if a Tailwind v3-style JS/TS config file appears under /web. Tailwind v4
# uses CSS-native @import "tailwindcss"; see architecture_decisions.ui_library.
set -euo pipefail

OFFENDERS=()
for f in web/tailwind.config.js web/tailwind.config.ts web/tailwind.config.cjs web/tailwind.config.mjs; do
  if [ -e "$f" ]; then
    OFFENDERS+=("$f")
  fi
done

if [ ${#OFFENDERS[@]} -gt 0 ]; then
  echo "ERROR: Tailwind v3-style config file(s) detected under /web:" >&2
  for f in "${OFFENDERS[@]}"; do
    echo "  - $f" >&2
  done
  echo "" >&2
  echo "Atlas uses Tailwind 4 with CSS-native @import 'tailwindcss' (no JS config)." >&2
  echo "Move any required tokens into web/src/styles/globals.css under @theme {}." >&2
  exit 1
fi

echo "OK: no Tailwind v3-style config files under /web."
exit 0
