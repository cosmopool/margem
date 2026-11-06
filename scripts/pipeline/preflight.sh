#!/usr/bin/env bash
# Check the branch before a /feature run starts or resumes, and say where it stands.
#
#   scripts/pipeline/preflight.sh --slug <slug>
#
# Refuses (exit 1) when: HEAD is detached or its branch name does not match the configured issue pattern; the
# remote branch is ahead after a fetch; or state.json does not match the branch.
#
# A first run records the base (merge-base with configured git.base) in a new state.json.
# On a resumed run, commits after the last "chore(checkpoint)" mean a step died
# partway. Their hashes are printed so they can be recovered from the reflog, any
# uncommitted change is stashed, and the branch is hard-reset to that checkpoint so the
# step re-runs from a known state.
#
# Prints one JSON object: mode (fresh, opening or resume), the state, the pending
# question if any, and what was set aside.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

SLUG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --slug) SLUG="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$SLUG" ] || die "--slug is required"
[ "$ROOT" = "$MAIN_ROOT" ] || die "run from the main checkout, not a worktree"

BRANCH="$(git symbolic-ref -q --short HEAD)" || die "HEAD is detached" 1
ISSUE_PATTERN="$(config get git.issue_pattern)"
JIRA=""
if [ -n "$ISSUE_PATTERN" ]; then
  JIRA="$(printf '%s' "$BRANCH" | grep -oE "$ISSUE_PATTERN" | head -n1)" \
    || die "branch '$BRANCH' must match configured git.issue_pattern ($ISSUE_PATTERN)" 1
fi
git fetch -q "$(config get git.remote)"
BEHIND=0 UNPUSHED=0
if git rev-parse -q --verify '@{u}' >/dev/null; then
  BEHIND="$(git rev-list --count 'HEAD..@{u}')"
  UNPUSHED="$(git rev-list --count '@{u}..HEAD')"
fi
[ "$BEHIND" -eq 0 ] || die "origin is $BEHIND commit(s) ahead of '$BRANCH' — pull before running" 1

W="$(work_dir "$SLUG")"
STATE="$W/state.json"
MODE="" LAST="" ORPHANS="" STASH=""

if [ ! -f "$STATE" ]; then
  MODE=fresh
  if [ -n "$(git status --porcelain -- . "${NON_CODE[@]}")" ]; then
    die "the working tree has uncommitted changes — commit or stash them first" 1
  fi
  mkdir -p "$W"
  python3 - "$STATE" "$SLUG" "$BRANCH" "$JIRA" "$(git merge-base "$(config get git.base)" HEAD)" \
    "$(git rev-parse HEAD)" <<'PY'
import json, sys
path, slug, branch, jira, base, head = sys.argv[1:]
json.dump({'slug': slug, 'branch': branch, 'jira': jira, 'base': base,
           'stage': 'open', 'step': 'start', 'round': None, 'commit': head,
           'budget': None, 'api': None, 'pending': None, 'history': []},
          open(path, 'w'), indent=2)
PY
else
  BASE="$(feature_base "$SLUG")"
  LAST="$(git log -n1 --format=%H -E --grep="^chore\(checkpoint\): " "$BASE..HEAD")"
  if [ -z "$LAST" ]; then
    # The opening session never reached its first checkpoint: redo it from the files.
    MODE=opening
  else
    MODE=resume
    ORPHANS="$(git rev-list "$LAST..HEAD")"
    mapfile -t DIRTY < <({ git ls-files -m -d -o --exclude-standard -- . "${NON_CODE[@]}"
                           git diff --cached --name-only -- . "${NON_CODE[@]}"; } | sort -u)
    if [ ${#DIRTY[@]} -gt 0 ]; then
      git stash push -q -u -m "feature $SLUG: uncommitted work of a step that died" \
        -- "${DIRTY[@]}"
      STASH="$(git rev-parse stash@{0})"
    fi
    [ -z "$ORPHANS" ] || git reset -q --hard "$LAST"
    git checkout -q -- .work
    EXPECTED="$(state_get "$SLUG" commit | tr -d '"')"
    [ "$(git rev-parse "$LAST^")" = "$EXPECTED" ] \
      || die "state.json says the last step ended at $EXPECTED, but the last checkpoint sits on $(git rev-parse "$LAST^")" 1
  fi
fi

python3 - "$STATE" "$MODE" "$ORPHANS" "$STASH" "$UNPUSHED" "$LAST" <<'PY'
from pipeline_config import CONFIG
import json, sys
path, mode, orphans, stash, unpushed, last = sys.argv[1:]
state = json.load(open(path))
print(json.dumps({
    'config': CONFIG,
    'mode': mode,
    'checkpoint': last or None,
    'orphans': orphans.split(),
    'stash': stash or None,
    'unpushed': int(unpushed),
    'pending': state.get('pending'),
    'state': {k: v for k, v in state.items() if k != 'history'},
}, indent=2))
PY
