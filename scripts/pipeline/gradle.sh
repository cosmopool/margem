#!/usr/bin/env bash
# The configured project commands (config.json → commands) for Margem: Gradle, Kover and detekt
# behind the file-based interface the pipeline scripts expect.
#
#   scripts/pipeline/gradle.sh test [test files...]       run those test classes (none: every test)
#   scripts/pipeline/gradle.sh coverage [test files...]   run them under Kover, write coverage.lcov
#   scripts/pipeline/gradle.sh analyze                    compile shared (jvm) and androidApp (debug)
#   scripts/pipeline/gradle.sh analyzer-report            detekt dead-code rules: `Rule path:line message`
#
# A test file maps to its module task by source set and to a class filter by file name:
#   shared/src/commonTest/.../FooTest.kt → :shared:jvmTest --tests '*.FooTest'
#   androidApp/src/test/.../FooTest.kt   → :androidApp:testDebugUnitTest --tests '*.FooTest'
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
GRADLE=(./gradlew --console=plain)

test_args() { # files... → gradle task/filter arguments
  local shared=() android=() f c
  for f in "$@"; do
    c="$(basename "$f" .kt)"
    case "$f" in
      shared/*)     shared+=(--tests "*.$c") ;;
      androidApp/*) android+=(--tests "*.$c") ;;
      *) echo "gradle.sh: not a test file of shared or androidApp: $f" >&2; exit 2 ;;
    esac
  done
  [ ${#shared[@]} -gt 0 ] && printf '%s\n' :shared:jvmTest "${shared[@]}"
  [ ${#android[@]} -gt 0 ] && printf '%s\n' :androidApp:testDebugUnitTest "${android[@]}"
  return 0
}

action="${1:-}"; shift || true
case "$action" in
  test)
    if [ $# -eq 0 ]; then exec make test; fi
    mapfile -t args < <(test_args "$@")
    exec "${GRADLE[@]}" "${args[@]}"
    ;;
  coverage)
    if [ $# -eq 0 ]; then
      args=(:shared:jvmTest :androidApp:testDebugUnitTest)
    else
      mapfile -t args < <(test_args "$@")
    fi
    "${GRADLE[@]}" "${args[@]}" koverXmlReportPipeline
    out="$(python3 scripts/pipeline/pipeline_config.py get coverage.lcov)"
    mkdir -p "$(dirname "$out")"
    # Kover writes JaCoCo XML keyed by package path + file name; lcov wants repo paths.
    git ls-files '*.kt' | python3 -I -c '
import sys, xml.etree.ElementTree as ET
files = sys.stdin.read().split()
out = open(sys.argv[2], "w")
for pkg in ET.parse(sys.argv[1]).getroot().iter("package"):
    for sf in pkg.iter("sourcefile"):
        key = "/" + pkg.get("name") + "/" + sf.get("name")
        for path in (p for p in files if p.endswith(key)):
            out.write("SF:" + path + "\n")
            for ln in sf.iter("line"):
                out.write("DA:%s,%d\n" % (ln.get("nr"), int(ln.get("ci"))))
            out.write("end_of_record\n")
' build/reports/kover/reportPipeline.xml "$out"
    ;;
  analyze)
    exec "${GRADLE[@]}" -q :shared:compileKotlinJvm :androidApp:compileDebugKotlin
    ;;
  analyzer-report)
    "${GRADLE[@]}" -q :shared:detektMainJvm :androidApp:detektDebug > /dev/null 2>&1 || true
    for x in shared/build/reports/detekt/mainJvm.xml androidApp/build/reports/detekt/debug.xml; do
      [ -f "$x" ] && python3 -I -c '
import sys, xml.etree.ElementTree as ET
root = sys.argv[2]
for f in ET.parse(sys.argv[1]).getroot().iter("file"):
    path = f.get("name")
    if path.startswith(root + "/"): path = path[len(root) + 1:]
    for e in f.iter("error"):
        print("%s %s:%s %s" % (e.get("source", "").rsplit(".", 1)[-1], path, e.get("line"), e.get("message")))
' "$x" "$ROOT"
    done
    ;;
  *) echo "usage: gradle.sh test|coverage|analyze|analyzer-report [files...]" >&2; exit 2 ;;
esac
