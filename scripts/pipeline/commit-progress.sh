#!/usr/bin/env bash
# Commit the code a finished step changed, leaving the feature folder out.
#
#   scripts/pipeline/commit-progress.sh -m "<message from /caveman-commit>"
#
# Stages every change outside .work/ (and outside the pipeline bundle), then commits.
# A step that changed no code — a round with no fixes, a skipped stage, the manifest
# approval — commits nothing, and that is not an error. Prints the new HEAD.
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
check_commit_message "$MESSAGE"

git add -A
unstage_non_code

if git diff --cached --quiet; then
  echo "commit-progress: no code change, nothing committed" >&2
  git rev-parse HEAD
  exit 0
fi

git commit -q -m "$MESSAGE"
git rev-parse HEAD
