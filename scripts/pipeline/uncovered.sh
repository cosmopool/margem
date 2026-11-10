#!/usr/bin/env bash
# Necessity oracle: list production lines this feature added that no test executes.
#
#   Run the configured commands.coverage first.
#   scripts/pipeline/uncovered.sh --base <ref> [--lcov coverage/lcov.info]
#
# Because the test suite is derived only from the acceptance criteria in
# requirement.md — never from the implementation — an added line that no test reaches
# is a line no requirement asked for. That makes it a deletion *candidate*: classify it
# per .agents/pipeline/RULES.md before removing anything. Defensive code at an I/O
# boundary is exempt even when uncovered.
#
# Lines with no coverage record (comments, braces, declarations) are not executable and
# are ignored. Files with no record at all are reported separately — usually they were
# never imported by any test, which is itself the finding.
#
# Exits 0 when nothing is uncovered, 1 when there are candidates, 2 on usage error.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

BASE=""
LCOV="$(config get coverage.lcov)"

while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --lcov) LCOV="$2"; shift 2 ;;
    *) echo "uncovered.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -n "$BASE" ] || { echo "uncovered.sh: --base <ref> is required" >&2; exit 2; }
git rev-parse --verify --quiet "$BASE" >/dev/null || {
  echo "uncovered.sh: not a valid ref: $BASE" >&2; exit 2; }
[ -f "$LCOV" ] || {
  echo "uncovered.sh: no coverage file at $LCOV — run the configured coverage command first" >&2
  exit 2; }

git add -A -N >/dev/null 2>&1 || true

ROOT="$(git rev-parse --show-toplevel)"

mapfile -t PATHSPEC < <(config pathspec production)
PATHSPEC=(-- "${PATHSPEC[@]}")

git diff -U0 "$BASE" "${PATHSPEC[@]}" \
  | awk -v lcov="$LCOV" -v root="$ROOT" '
  function norm(p) {
    sub(/^\.\//, "", p)
    if (index(p, root "/") == 1) p = substr(p, length(root) + 2)
    return p
  }
  BEGIN {
    while ((getline line < lcov) > 0) {
      if (line ~ /^SF:/)      { sf = norm(substr(line, 4)); seen[sf] = 1 }
      else if (line ~ /^DA:/) {
        split(substr(line, 4), a, ",")
        # A line can appear more than once; executed anywhere counts as covered.
        if (a[2] + 0 > 0) cov[sf ":" a[1]] = 1
        else if (!((sf ":" a[1]) in cov)) cov[sf ":" a[1]] = 0
      }
    }
    close(lcov)
  }
  /^\+\+\+ b\// { file = norm(substr($0, 7)); next }
  /^\+\+\+/     { file = ""; next }
  /^@@/ {
    # @@ -old,n +new,m @@  — added lines start at `new`
    match($0, /\+[0-9]+/)
    lineno = substr($0, RSTART + 1, RLENGTH - 1) + 0
    next
  }
  /^\+/ {
    key = file ":" lineno
    if (!(file in seen))      uninstrumented[file] = uninstrumented[file] " " lineno
    else if (key in cov && cov[key] == 0) { print file ":" lineno; n++ }
    lineno++
  }
  /^[ -]/ { next }
  END {
    for (f in uninstrumented) {
      print f ": no coverage record — not reached by any test" > "/dev/stderr"
      n++
    }
    exit (n > 0 ? 1 : 0)
  }'
