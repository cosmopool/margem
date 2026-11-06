#!/usr/bin/env bash
# Cut the branch the merge request is opened from: the run's code, without checkpoints.
#
#   scripts/pipeline/finish.sh --slug <slug> --into <new-branch>
#
# Creates <new-branch> at the base and cherry-picks every commit of the run that is not
# a "chore(checkpoint)", oldest first, in a temporary worktree, so this checkout and
# its .work/ folder stay as they are. Code and checkpoint commits touch different files,
# so the picks cannot conflict. Cherry-pick does not re-run the commit-msg hook: the
# copies keep the jira and branch lines the hook added on the work branch.
#
# The work branch stays on the remote as the audit trail. Nothing is pushed or rebased.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

SLUG="" INTO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --slug) SLUG="$2"; shift 2 ;;
    --into) INTO="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$SLUG" ] && [ -n "$INTO" ] || die "--slug and --into are required"
! git rev-parse -q --verify "refs/heads/$INTO" >/dev/null || die "branch $INTO already exists" 1
[ -z "$(git status --porcelain -- . "${NON_CODE[@]}")" ] || die "uncommitted code changes" 1
BASE="$(feature_base "$SLUG")"

mapfile -t PICKS < <(git log --reverse --no-merges --format='%H %s' "$BASE..HEAD" \
  | grep -vF " $CHECKPOINT_PREFIX" | cut -d' ' -f1)
[ ${#PICKS[@]} -gt 0 ] || die "the run has no code commits" 1

WT="$MAIN_ROOT/.claude/worktrees/feature-$SLUG-finish"
git worktree add -q -b "$INTO" "$WT" "$BASE"
if ! git -C "$WT" cherry-pick "${PICKS[@]}" >&2; then
  git -C "$WT" cherry-pick --abort || true
  git worktree remove --force "$WT"
  git branch -q -D "$INTO"
  die "cherry-pick failed; $INTO was not created" 1
fi
git worktree remove "$WT"

printf '{"branch": "%s", "base": "%s", "picked": %s, "head": "%s"}\n' \
  "$INTO" "$BASE" "${#PICKS[@]}" "$(git rev-parse "$INTO")"
