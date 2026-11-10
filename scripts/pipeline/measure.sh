#!/usr/bin/env bash
# Measure coverage and configured analyzer, defensive and boundary patterns.
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
BASE="$(feature_base "$SLUG")"
LOG="$(work_dir "$SLUG")/reports/measure.log"
mkdir -p "$(dirname "$LOG")"

ROOT_TESTS=() PACKAGE_TESTS=()
while IFS= read -r file; do
  [ "$(package_of "$file")" = . ] && ROOT_TESTS+=("$file") || PACKAGE_TESTS+=("$file")
done < <(feature_tests "$BASE")

[ ${#ROOT_TESTS[@]} -gt 0 ] || die "the feature has no root test files to measure coverage with" 1
if ! config run coverage "${ROOT_TESTS[@]+"${ROOT_TESTS[@]}"}" > "$LOG" 2>&1; then
  echo '{"suiteGreen": false, "log": "'"$LOG"'"}'
  exit 1
fi

COVERAGE_STATUS=0
UNCOVERED="$("$PIPELINE_SCRIPTS/uncovered.sh" --base "$BASE" 2> "$LOG.uninstrumented")" || COVERAGE_STATUS=$?
[ "$COVERAGE_STATUS" -le 1 ] || die "coverage measurement failed — see $LOG.uninstrumented" 2
ANALYZER="$(config run analyzer_report 2>&1 || true)"
BUDGET="$("$PIPELINE_SCRIPTS/budget.sh" --base "$BASE" || true)"
intent_to_add
mapfile -t DIFF_PATHS < <(config pathspec production)
DIFF="$(git diff -U0 "$BASE" -- "${DIFF_PATHS[@]}")"

# Large values go through files: one argv string is capped at 128 KiB (MAX_ARG_STRLEN).
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
printf '%s' "$UNCOVERED" > "$TMP/uncovered"; cp "$LOG.uninstrumented" "$TMP/uninstrumented"
printf '%s' "$ANALYZER" > "$TMP/analyzer"; printf '%s' "$BUDGET" > "$TMP/budget"; printf '%s' "$DIFF" > "$TMP/diff"
python3 - "$TMP/uncovered" "$TMP/uninstrumented" "$TMP/analyzer" "$TMP/budget" "$TMP/diff" \
  "${PACKAGE_TESTS[@]+"${PACKAGE_TESTS[@]}"}" <<'PY'
from pipeline_config import CONFIG
import json, os, re, sys
uncovered, uninstrumented, analyzer, budget, diff = (open(p).read() for p in sys.argv[1:6])
package_tests = sys.argv[6:]

added, file = {}, None
for line in diff.splitlines():
    if line.startswith('+++ '):
        file = line[6:] if line.startswith('+++ b/') else None
    elif line.startswith('@@'):
        number = int(re.search(r'\+(\d+)', line).group(1))
    elif line.startswith('+') and file:
        added[f'{file}:{number}'] = line[1:]
        number += 1

mechanical_codes = set(CONFIG['patterns']['mechanical_codes'])
mechanical = []
for line in analyzer.splitlines():
    match = re.search(CONFIG['patterns']['analyzer_finding'], line)
    if match and match['code'] in mechanical_codes:
        at = f"{match['path']}:{match['line']}"
        if at in added:
            mechanical.append({'at': at, 'code': match['code'], 'message': match['message']})

defensive_patterns = CONFIG['patterns']['defensive_patterns']
boundary_import = re.compile(CONFIG['patterns']['boundary_import'], re.M)
near = {}
defensive = []
for at, code in added.items():
    if re.search(CONFIG['patterns']['comment'], code):
        continue
    path = at.rsplit(':', 1)[0]
    if path not in near:
        near[path] = bool(os.path.exists(path) and boundary_import.search(open(path).read()))
    for construct, pattern in defensive_patterns:
        if re.search(pattern, code):
            defensive.append({'at': at, 'construct': construct, 'line': code.strip()[:120],
                              'nearBoundary': near[path]})

print(json.dumps({
    'suiteGreen': True,
    'uncovered': uncovered.split(),
    'uninstrumented': [l.split(':')[0] for l in uninstrumented.splitlines() if l],
    'mechanical': mechanical,
    'defensive': defensive,
    'packageTests': package_tests,
    'budget': json.loads(budget) if budget else None,
}, indent=2))
PY
