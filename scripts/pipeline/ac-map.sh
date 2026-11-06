#!/usr/bin/env bash
# Map every acceptance criterion to the tests that cite it.
#
#   scripts/pipeline/ac-map.sh --slug <slug> [--expect red|green]
#
# Reads the AC ids from requirement.md and the test descriptions in every test file the
# feature added or changed (descriptions start with the ids they cover). With --expect,
# also runs those test files: `red` right after the tests are written, before any
# implementation, and `green` afterwards.
#
# Prints JSON: the AC -> tests map, uncovered ACs, ids cited that no AC defines, and the
# run result. Exits 1 when an AC is uncovered or the run does not match --expect.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

SLUG="" EXPECT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --slug)   SLUG="$2"; shift 2 ;;
    --expect) EXPECT="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$SLUG" ] || die "--slug is required"
case "$EXPECT" in ''|red|green) ;; *) die "--expect must be red or green" ;; esac

mapfile -t TESTS < <(feature_tests "$(feature_base "$SLUG")")

PASSED=null
if [ -n "$EXPECT" ]; then
  if run_tests -- "${TESTS[@]+"${TESTS[@]}"}" >&2; then PASSED=true; else PASSED=false; fi
fi

python3 - "$(work_dir "$SLUG")/requirement.md" "$EXPECT" "$PASSED" \
  "${TESTS[@]+"${TESTS[@]}"}" <<'PY'
from pipeline_config import CONFIG
import json, re, sys
requirement, expect, passed, *tests = sys.argv[1:]
in_acs, acs = False, []
for line in open(requirement):
    if line.startswith('## '):
        in_acs = line.strip().lower() == '## acceptance criteria'
    elif in_acs and (m := re.match(r'^\s*[-*]?\s*(AC-F?\d+):', line)):
        acs.append(m.group(1))

described = re.compile(CONFIG['patterns']['test_description'], re.S)
cited = {}
for path in tests:
    source = open(path).read()
    for m in described.finditer(source):
        line = source.count('\n', 0, m.start()) + 1
        for ac in re.findall(r'AC-F?\d+', m.group(2)):
            cited.setdefault(ac, []).append(f'{path}:{line} {m.group(2)[:80]}')

uncovered = [ac for ac in acs if ac not in cited]
unknown = sorted(set(cited) - set(acs))
passed = json.loads(passed)
run_ok = not expect or passed == (expect == 'green')
print(json.dumps({
    'map': {ac: cited.get(ac, []) for ac in acs},
    'uncovered': uncovered,
    'unknown': unknown,
    'testFiles': tests,
    'run': {'expect': expect or None, 'passed': passed, 'asExpected': run_ok},
}, indent=2))
sys.exit(0 if not uncovered and run_ok else 1)
PY
