#!/usr/bin/env bash
# Necessity oracle: production lines this feature added that no test executes.
#
#   make coverage            # every test of both modules, one merged Kover XML
#   scripts/pipeline/uncovered.sh --base <ref> [--xml build/reports/kover/reportPipeline.xml]
#
# The feature's tests come from its acceptance criteria and the blind contract round, never from
# the implementation, so an added line no test reaches is a line no requirement asked for: a
# deletion *candidate*, classified per .claude/pipeline/RULES.md before anything is removed.
# Defensive code at an I/O boundary is exempt even when uncovered.
#
# Kover writes JaCoCo XML: <package name="com/x"><sourcefile name="Db.kt"><line nr= mi= ci=>.
# A line with a record and no covered instruction is uncovered; lines with no record (comments,
# braces, declarations, inline lambdas the compiler moved) are not executable and are ignored.
# A file with no record at all is reported apart — usually no test loads it, which is the finding.
# Composables only run under Robolectric (make test-android); the merged report includes them.
#
# Exits 0 when nothing is uncovered, 1 when there are candidates, 2 on usage error.
set -euo pipefail
. "$(dirname "$0")/common.sh"

BASE=""; XML="build/reports/kover/reportPipeline.xml"
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --xml)  XML="$2"; shift 2 ;;
    *) echo "uncovered.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done
need_base uncovered.sh "$BASE"
[ -f "$XML" ] || { echo "uncovered.sh: no coverage file at $XML — run 'make coverage' first" >&2; exit 2; }
intent_to_add

added_lines "$BASE" | python3 -I -c '
import sys, xml.etree.ElementTree as ET
cov = {}   # "pkg/File.kt" -> {line: covered}
for pkg in ET.parse(sys.argv[1]).getroot().iter("package"):
    for sf in pkg.iter("sourcefile"):
        lines = cov.setdefault(pkg.get("name") + "/" + sf.get("name"), {})
        for ln in sf.iter("line"):
            n = int(ln.get("nr"))
            lines[n] = lines.get(n, False) or int(ln.get("ci")) > 0
def record(path):
    # src/<set>/kotlin/<package path>/<File>.kt -> the key Kover uses
    for key in cov:
        if path.endswith("/" + key): return cov[key]
    return None
found = 0; missing = {}
for item in sys.stdin.read().split():
    path, n = item.rsplit(":", 1)
    lines = record(path)
    if lines is None: missing[path] = 1; continue
    if lines.get(int(n)) is False:
        print(item); found += 1
for path in missing:
    print(path + ": no coverage record — not reached by any test", file=sys.stderr); found += 1
sys.exit(1 if found else 0)
' "$XML"
