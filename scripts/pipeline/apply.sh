#!/usr/bin/env bash
# Apply the patch, run configured generators, restore dependencies, analyze and test.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

SLUG="" PATCH=""
while [ $# -gt 0 ]; do
  case "$1" in
    --slug)  SLUG="$2"; shift 2 ;;
    --patch) PATCH="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$SLUG" ] && [ -f "$PATCH" ] || die "--slug and an existing --patch are required"
LOG="$(work_dir "$SLUG")/reports/apply.log"
mkdir -p "$(dirname "$LOG")"
: > "$LOG"

finish() { # ok failed-step
  printf '{"ok": %s, "failedAt": %s, "log": "%s"}\n' "$1" "${2:-null}" "$LOG"
  [ "$1" = true ]
}
step() { # name command...
  local name="$1"; shift
  echo "== $name" >> "$LOG"
  "$@" >> "$LOG" 2>&1 || { finish false "\"$name\""; exit 1; }
}

step apply git apply "$PATCH"
FILES="$(git apply --numstat "$PATCH" | awk '{ print $3 }')"
mapfile -t CHANGED_FILES <<< "$FILES"
step generate config generate "${CHANGED_FILES[@]}"
step dependencies config run dependencies
step analyze config run analyze
step test config run full_test
finish true
