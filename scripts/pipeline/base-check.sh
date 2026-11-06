#!/usr/bin/env bash
# Tell feature-caused bugs from bugs that already existed on the base.
#
#   scripts/pipeline/base-check.sh --slug <slug> <reproduction-test>...
#
# Runs each reproduction test, alone, against the base ref in a throwaway worktree
# kept at .claude/worktrees/feature-<slug>-base and reused across rounds. Each
# reproduction lives in its own file, so a result maps to exactly one finding:
#
#   passes on base, or does not compile there  -> "feature": the feature caused it
#   fails on base too                          -> "pre-existing": report, never fix
#
# Prints a JSON array, one entry per file. Logs go to .work/<slug>/reports/base-check/.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

SLUG=""
FILES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --slug) SLUG="$2"; shift 2 ;;
    -*) die "unknown argument: $1" ;;
    *) FILES+=("$1"); shift ;;
  esac
done
[ -n "$SLUG" ] && [ ${#FILES[@]} -gt 0 ] || die "--slug and at least one test file are required"
BASE="$(feature_base "$SLUG")"
LOGS="$(work_dir "$SLUG")/reports/base-check"
WT="$MAIN_ROOT/.claude/worktrees/feature-$SLUG-base"
mkdir -p "$LOGS"

if [ ! -d "$WT" ]; then
  git worktree add -q --detach "$WT" "$BASE"
  setup_worktree "$WT" > "$LOGS/setup.log" 2>&1 \
    || die "worktree setup failed — see $LOGS/setup.log" 1
fi
git -C "$WT" checkout -q --detach "$BASE"

RESULTS=()
for file in "${FILES[@]}"; do
  [ -f "$file" ] || die "no such test file: $file"
  log="$LOGS/$(basename "$file").log"
  mkdir -p "$WT/$(dirname "$file")"
  cp "$file" "$WT/$file"
  if (cd "$WT" && run_tests -- "$file") > "$log" 2>&1; then
    on_base=passes
  elif grep -qE "$(config get patterns.compile_error)" "$log"; then
    on_base=does-not-compile
  else
    on_base=fails
  fi
  rm -f "$WT/$file"
  [ "$on_base" = fails ] && verdict=pre-existing || verdict=feature
  RESULTS+=("{\"file\": \"$file\", \"onBase\": \"$on_base\", \"verdict\": \"$verdict\", \"log\": \"$log\"}")
done

( IFS=,; echo "[${RESULTS[*]}]" ) | python3 -m json.tool
