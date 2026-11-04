#!/usr/bin/env bash
# Measure the size of the current feature diff and fail if it exceeds its budget.
#
#   scripts/pipeline/budget.sh --base <ref> [--max-added N] [--max-new-files N] [--max-new-symbols N]
#
# Compares <ref> with the working tree, untracked files included. Production Kotlin only
# (common.sh): no tests, no tools/, no .work/. Prints one JSON object; exits 1 when a declared
# budget is exceeded. Never interpret this output — report it verbatim.
set -euo pipefail
. "$(dirname "$0")/common.sh"

BASE=""; MAX_ADDED=""; MAX_NEW_FILES=""; MAX_NEW_SYMBOLS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base)            BASE="$2"; shift 2 ;;
    --max-added)       MAX_ADDED="$2"; shift 2 ;;
    --max-new-files)   MAX_NEW_FILES="$2"; shift 2 ;;
    --max-new-symbols) MAX_NEW_SYMBOLS="$2"; shift 2 ;;
    *) echo "budget.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done
need_base budget.sh "$BASE"
intent_to_add

ADDED=$(git diff --numstat "$BASE" "${PROD_PATHSPEC[@]}" | awk '$1 != "-" { n += $1 } END { print n + 0 }')
REMOVED=$(git diff --numstat "$BASE" "${PROD_PATHSPEC[@]}" | awk '$2 != "-" { n += $2 } END { print n + 0 }')
NEW_FILES=$(git diff --diff-filter=A --name-only "$BASE" "${PROD_PATHSPEC[@]}" | wc -l | tr -d ' ')

# New public symbols: a deliberate heuristic over added lines, not a parser. Kotlin is public by
# default, so a declaration counts unless it is private, internal, protected or an override:
#   class / object / interface / typealias (any kind) at any depth;
#   fun, val, var at the top level, or one indent deep when the enclosing top-level declaration is a
#   class, object or interface (a member) — not a fun (a local).
# `const val` is never counted: the testTag objects (architecture D35) add one per element, and a
# tag is screen contract, not surface the budget should fight.
NEW_SYMBOLS=$(added_lines "$BASE" | python3 -I -c '
import re, sys
MOD = r"((public|open|abstract|final|data|sealed|enum|value|inner|annotation|inline|suspend|operator|infix|tailrec|lateinit|external|actual|expect|fun)\s+)*"
TYPE = re.compile(r"^" + MOD + r"(class|object|interface|typealias)\s+[A-Z]")
DECL = re.compile(r"^" + MOD + r"(fun|val|var)\s")
SKIP = re.compile(r"(^|\s)(private|internal|protected|override|const)\s")
ANNO = re.compile(r"^(@[\w.:]+(\([^)]*\))?\s+)+")
files = {}; n = 0
for item in sys.stdin.read().split():
    path, ln = item.rsplit(":", 1); ln = int(ln)
    lines = files.get(path)
    if lines is None:
        lines = files[path] = open(path, encoding="utf-8").read().split("\n")
    raw = lines[ln - 1]
    depth = len(raw) - len(raw.lstrip(" "))
    code = ANNO.sub("", raw.strip())
    if not code or code.startswith(("//", "*", "/*")) or SKIP.search(code): continue
    if TYPE.match(code): n += 1; continue
    if not DECL.match(code): continue
    if depth == 0: n += 1; continue
    if depth != 4: continue
    for prev in reversed(lines[:ln - 1]):
        if prev[:1] in ("", " ", "\t", "}", ")", "@", "/", "*"): continue
        top = ANNO.sub("", prev.strip())
        if TYPE.match(top) or re.match(r"^" + MOD + r"(class|object|interface)\b", top) or re.match(r"^\w+(\s+\w+)*\s+(class|object|interface)\b", top):
            if not SKIP.search(top): n += 1
        break
print(n)
')

over=0; exceeded=""
check() { if [ -n "$3" ] && [ "$2" -gt "$3" ]; then over=1; exceeded="${exceeded:+$exceeded, }\"$1\""; fi; }
check added              "$ADDED"       "$MAX_ADDED"
check new_files          "$NEW_FILES"   "$MAX_NEW_FILES"
check new_public_symbols "$NEW_SYMBOLS" "$MAX_NEW_SYMBOLS"

cat <<JSON
{
  "base": "$BASE",
  "added": $ADDED,
  "removed": $REMOVED,
  "new_files": $NEW_FILES,
  "new_public_symbols": $NEW_SYMBOLS,
  "budget": { "added": ${MAX_ADDED:-null}, "new_files": ${MAX_NEW_FILES:-null}, "new_public_symbols": ${MAX_NEW_SYMBOLS:-null} },
  "exceeded": [${exceeded}],
  "within_budget": $( [ "$over" -eq 0 ] && echo true || echo false )
}
JSON
exit "$over"
