#!/usr/bin/env bash
# Check that an implementation candidate stayed inside its contract.
#
#   scripts/pipeline/candidate-check.sh --slug <slug> [--since <ref>]
#
# Compares the working tree against <ref>: by default the commit that holds the
# acceptance tests, as state.json recorded it at the end of the stage 1 "tests" step.
# That holds in a candidate worktree and in the main checkout. Fails when any test file
# changed, or when a changed file is not listed under "Files to touch" or "New files" in
# manifest.md. Generated code is ignored. Prints JSON; exits 1 on any violation.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

SLUG="" SINCE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --slug)  SLUG="$2"; shift 2 ;;
    --since) SINCE="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$SLUG" ] || die "--slug is required"
if [ -z "$SINCE" ]; then
  SINCE="$(python3 - "$(work_dir "$SLUG")/state.json" <<'PY'
from pipeline_config import CONFIG
import json, sys
steps = json.load(open(sys.argv[1])).get('history', [])
print(next((s['commit'] for s in steps if s['stage'] == 'build' and s['step'] == 'tests'), ''))
PY
)"
  [ -n "$SINCE" ] || die "state.json has no stage 1 tests step — pass --since"
fi
intent_to_add

mapfile -t DIFF_PATHS < <(config pathspec candidate)
CHANGED="$(git diff --name-only "$SINCE" -- "${DIFF_PATHS[@]}")"

python3 - "$(work_dir "$SLUG")/manifest.md" "$CHANGED" <<'PY'
from pipeline_config import CONFIG
import fnmatch, json, re, sys
manifest, changed = sys.argv[1:]
approved, section = [], None
for line in open(manifest):
    if line.startswith('## '):
        section = line[3:].strip().lower()
    elif section in ('files to touch', 'new files') and (m := re.match(r'^\s*[-*]\s+`?([^`\s]+)', line)):
        approved.append(m.group(1))

def is_test(path):
    return any(fnmatch.fnmatch(path, pattern) for pattern in CONFIG['paths']['tests'])

violations = []
for path in changed.splitlines():
    if is_test(path):
        violations.append({'kind': 'test-changed', 'path': path})
    elif not any(fnmatch.fnmatch(path, pattern) for pattern in approved):
        violations.append({'kind': 'not-in-manifest', 'path': path})
print(json.dumps({'ok': not violations, 'approved': approved, 'violations': violations}, indent=2))
sys.exit(1 if violations else 0)
PY
