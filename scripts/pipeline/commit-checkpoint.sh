#!/usr/bin/env bash
# Commit the feature folder as a checkpoint, then push the branch.
#
#   scripts/pipeline/commit-checkpoint.sh -m "<message from /caveman-commit>"
#
# Run after commit-progress.sh and record.sh. Commits only .work/, with the subject
# "chore(checkpoint): <message>", and refuses while any code change is still
# uncommitted, so a checkpoint always describes a fully committed step. This is the only
# script that pushes, and it pushes only after the checkpoint lands: another machine can
# therefore never see a step that died partway.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

MESSAGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    -m) MESSAGE="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$MESSAGE" ] || die "-m <message> is required"
MESSAGE="$CHECKPOINT_PREFIX$MESSAGE"
check_commit_message "$MESSAGE"

if [ -n "$(git status --porcelain -- . "${NON_CODE[@]}")" ]; then
  git status --short -- . "${NON_CODE[@]}" >&2
  die "code changes are still uncommitted — run commit-progress.sh first" 1
fi

# .work/ may be gitignored, so it is force-added.
git reset -q
git add -f -A -- .work
git commit -q --allow-empty -m "$MESSAGE"
git push -q -u "$(config get git.remote)" HEAD
git rev-parse HEAD
