#!/usr/bin/env bash
# Measure the size of the current feature diff and fail if it exceeds its budget.
#
#   scripts/pipeline/budget.sh --base <ref> [--max-added N] [--max-new-files N]
#                              [--max-new-symbols N] [--json-only]
#
# Compares <ref> against the working tree, counting untracked files (added with
# `git add -N`, which stages nothing and changes no content). Test files, generated
# files and the .work/ folder are excluded from every count.
#
# Prints one JSON object on stdout. Exits 1 if any declared budget is exceeded, so it
# can be used directly as a gate. Never interpret this output — report it verbatim.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

BASE=""
MAX_ADDED=""
MAX_NEW_FILES=""
MAX_NEW_SYMBOLS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --base)             BASE="$2"; shift 2 ;;
    --max-added)        MAX_ADDED="$2"; shift 2 ;;
    --max-new-files)    MAX_NEW_FILES="$2"; shift 2 ;;
    --max-new-symbols)  MAX_NEW_SYMBOLS="$2"; shift 2 ;;
    --json-only)        shift ;;
    *) echo "budget.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -n "$BASE" ] || { echo "budget.sh: --base <ref> is required" >&2; exit 2; }
git rev-parse --verify --quiet "$BASE" >/dev/null || {
  echo "budget.sh: not a valid ref: $BASE" >&2; exit 2; }

# Make untracked files visible to git diff without staging their content.
git add -A -N >/dev/null 2>&1 || true

# Production Dart only: no tests, no generated code, no pipeline bookkeeping.
mapfile -t PATHSPEC < <(config pathspec production)
PATHSPEC=(-- "${PATHSPEC[@]}")

ADDED=$(git diff --numstat "$BASE" "${PATHSPEC[@]}" \
        | awk '$1 != "-" { n += $1 } END { print n + 0 }')
REMOVED=$(git diff --numstat "$BASE" "${PATHSPEC[@]}" \
        | awk '$2 != "-" { n += $2 } END { print n + 0 }')
NEW_FILES=$(git diff --diff-filter=A --name-only "$BASE" "${PATHSPEC[@]}" | wc -l | tr -d ' ')

# Public-symbol counting uses the configured declaration heuristics.
NEW_SYMBOLS=$(git diff "$BASE" "${PATHSPEC[@]}" | config symbols)

over=0
exceeded=""
check() { # name value max
  if [ -n "$3" ] && [ "$2" -gt "$3" ]; then
    over=1
    exceeded="${exceeded:+$exceeded, }\"$1\""
  fi
}
check added        "$ADDED"       "$MAX_ADDED"
check new_files    "$NEW_FILES"   "$MAX_NEW_FILES"
check new_public_symbols "$NEW_SYMBOLS" "$MAX_NEW_SYMBOLS"

cat <<JSON
{
  "base": "$BASE",
  "added": $ADDED,
  "removed": $REMOVED,
  "new_files": $NEW_FILES,
  "new_public_symbols": $NEW_SYMBOLS,
  "budget": {
    "added": ${MAX_ADDED:-null},
    "new_files": ${MAX_NEW_FILES:-null},
    "new_public_symbols": ${MAX_NEW_SYMBOLS:-null}
  },
  "exceeded": [${exceeded}],
  "within_budget": $( [ "$over" -eq 0 ] && echo true || echo false )
}
JSON

exit "$over"
