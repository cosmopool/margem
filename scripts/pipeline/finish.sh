#!/usr/bin/env bash
# Land a finished feature on main: the one step that needs the user's approval.
#
#   scripts/pipeline/finish.sh <slug>          # shows what would land, changes nothing
#   scripts/pipeline/finish.sh <slug> --yes    # cherry-picks it onto main
#
# Run only after /dev-run (or the last stage) reported done and the user approved in chat — the
# skill asks; this script never decides. It cherry-picks every commit of the work branch since
# .work/<slug>/base.txt onto main, in order, then runs `make check` on main. It never pushes main:
# the user pushes. The work branch stays, so a failed check can be redone from it.
set -euo pipefail
SLUG="${1:-}"; YES="${2:-}"
[ -n "$SLUG" ] || { echo "usage: finish.sh <slug> [--yes]" >&2; exit 2; }
ROOT="$(git rev-parse --show-toplevel)"; cd "$ROOT"

BRANCH="$(git symbolic-ref --quiet --short HEAD || true)"
[ -n "$BRANCH" ] && [ "$BRANCH" != main ] || { echo "finish: run it from the work branch" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "finish: working tree not clean" >&2; exit 1; }
BASE="$(cat ".work/$SLUG/base.txt" 2>/dev/null || true)"
[ -n "$BASE" ] || { echo "finish: no .work/$SLUG/base.txt" >&2; exit 1; }

echo "branch $BRANCH, base $(git rev-parse --short "$BASE"), main at $(git rev-parse --short main):"
git log --reverse --format='  %h %s' "$BASE..$BRANCH"
[ "$YES" = "--yes" ] || { echo "dry run — rerun with --yes once the user approved"; exit 0; }

git switch --quiet main
git cherry-pick "$BASE..$BRANCH" || {
  echo "finish: cherry-pick stopped on a conflict. Resolve it and 'git cherry-pick --continue'," >&2
  echo "        or 'git cherry-pick --abort && git switch $BRANCH' to back out." >&2
  exit 1; }
make check
echo "landed on main ($(git rev-parse --short HEAD)); not pushed — push when you are ready"
