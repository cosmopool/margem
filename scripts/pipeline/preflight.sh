#!/usr/bin/env bash
# Before any pipeline stage: the branch is a work branch, clean, and not behind its origin copy.
#
#   scripts/pipeline/preflight.sh <slug>
#
# - HEAD is a branch other than main (any name; Margem has no issue pattern).
# - The working tree is clean, so every stage's commit holds only that stage's work.
# - origin/<branch>, when it exists, has nothing HEAD lacks: the work may have moved on another
#   machine (checkpoints push the work branch), and resuming behind it would fork the history.
# - .work/<slug>/base.txt holds the base commit; written here the first time (merge-base with main).
# - local.properties exists: every agent worktree copies it to build (sdk.dir).
# Prints the base sha on success. Exits 1 with the reason otherwise.
set -euo pipefail
SLUG="${1:-}"
[ -n "$SLUG" ] || { echo "usage: preflight.sh <slug>" >&2; exit 2; }
ROOT="$(git rev-parse --show-toplevel)"; cd "$ROOT"

BRANCH="$(git symbolic-ref --quiet --short HEAD || true)"
[ -n "$BRANCH" ] || { echo "preflight: detached HEAD — check out the work branch" >&2; exit 1; }
[ "$BRANCH" != main ] || { echo "preflight: on main — create a work branch first (git switch -c <name>)" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "preflight: working tree not clean:" >&2; git status --short >&2; exit 1; }
[ -f local.properties ] || { echo "preflight: no local.properties (sdk.dir) — agent worktrees cannot build" >&2; exit 1; }

if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  git fetch --quiet origin "$BRANCH"
  AHEAD=$(git rev-list --count "HEAD..origin/$BRANCH")
  [ "$AHEAD" -eq 0 ] || { echo "preflight: origin/$BRANCH has $AHEAD commit(s) this checkout lacks — pull them first" >&2; exit 1; }
fi

mkdir -p ".work/$SLUG"
[ -s ".work/$SLUG/base.txt" ] || git merge-base main HEAD > ".work/$SLUG/base.txt"
cat ".work/$SLUG/base.txt"
