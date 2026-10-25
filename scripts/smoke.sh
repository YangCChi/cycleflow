#!/usr/bin/env bash
set -euo pipefail
BASE="${BASE_URL:-http://127.0.0.1:8080}"
fail=0
check() {
  local path="$1"
  local code
  code=$(curl -s -m 90 -o /dev/null -w '%{http_code}' "$BASE$path" || true)
  printf '%s  %s\n' "$code" "$path"
  [[ "$code" == "200" ]] || fail=1
}
check /api/health
check /api/state
check /api/road-matrix
check /api/dispatch/compare/summary
check /api/regions/1/forecast
check /
if [[ "$fail" == "0" ]]; then
  echo "smoke ok"
else
  echo "smoke failed" >&2
  exit 1
fi
