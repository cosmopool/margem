#!/usr/bin/env bash
# The mechanical deletion class: dead code on lines this feature added, as the tools see it.
#
#   scripts/pipeline/dead.sh --base <ref>
#
# Two sources, both run here so nothing is read from a stale build:
#   - kotlinc warnings (`w: file:line:col`), from a forced recompile of shared (jvm) and androidApp
#     (debug) — up-to-date tasks print nothing, hence --rerun;
#   - detekt with type resolution, keeping only its dead-code rules (DEAD below) from the XML.
# Kotlin 2 (K2) no longer warns about unused locals or imports without extra flags, so detekt carries
# most of this; the kotlinc source still catches what K2 does report (unreachable code, unused results).
# Only findings on lines added since <base> are printed: `file:line source message`.
# Exits 0 when there are none, 1 when there are, 2 on usage error.
set -euo pipefail
. "$(dirname "$0")/common.sh"

BASE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    *) echo "dead.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done
need_base dead.sh "$BASE"
intent_to_add
ROOT="$(git rev-parse --show-toplevel)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

added_lines "$BASE" > "$TMP/added"

./gradlew :shared:compileKotlinJvm --rerun :androidApp:compileDebugKotlin --rerun \
          :shared:detektMainJvm :androidApp:detektDebug --console=plain > "$TMP/build.log" 2>&1 || {
  echo "dead.sh: the build failed — fix compilation first:" >&2; tail -30 "$TMP/build.log" >&2; exit 2; }

DEAD='UnusedImport|UnusedPrivateClass|UnusedPrivateFunction|UnusedPrivateProperty|UnusedPrivateMember|UnusedParameter|UnusedVariable|UnreachableCode|UnreachableCatchBlock'

{
  # w: file:///abs/path/File.kt:12:5 message   (or w: /abs/path/File.kt:12:5 message)
  sed -nE 's#^w: (file://)?'"$ROOT"'/([^:]+):([0-9]+):[0-9]+ (.*)#\2:\3 kotlinc \4#p' "$TMP/build.log"
  for x in shared/build/reports/detekt/mainJvm.xml androidApp/build/reports/detekt/debug.xml; do
    [ -f "$x" ] && python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
dead = re.compile(r"detekt\.(" + sys.argv[2] + r")$")
for f in ET.parse(sys.argv[1]).getroot().iter("file"):
    for e in f.iter("error"):
        if dead.search(e.get("source", "")):
            print("%s:%s %s %s" % (f.get("name"), e.get("line"), e.get("source"), e.get("message")))
' "$x" "$DEAD"
  done
} | awk -v added="$TMP/added" '
  BEGIN { while ((getline l < added) > 0) want[l] = 1 }
  { key = $1; if (key in want) { print; n++ } }
  END { exit (n > 0 ? 1 : 0) }'
