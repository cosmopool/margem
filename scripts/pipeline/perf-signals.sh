#!/usr/bin/env bash
# Match configured performance signals on added production lines.
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
intent_to_add

mapfile -t DIFF_PATHS < <(config pathspec production)
git diff -U0 "$(feature_base "$SLUG")" -- "${DIFF_PATHS[@]}" \
  | python3 -c '
from pipeline_config import CONFIG
import json, os, re, sys
kinds = CONFIG["patterns"]["kinds"]
loop = re.compile(CONFIG["patterns"]["loop"])
signals, file, builds = [], None, False
for line in sys.stdin:
    if line.startswith("+++ "):
        file = line[6:].strip() if line.startswith("+++ b/") else None
        builds = bool(file and os.path.exists(file) and CONFIG["patterns"]["performance_context"] in open(file).read())
    elif line.startswith("@@"):
        number = int(re.search(r"\+(\d+)", line).group(1))
    elif line.startswith("+") and file:
        code = line[1:].strip()
        if not re.search(CONFIG["patterns"]["comment"], code):
            found = [k for k, p in kinds if re.search(p, code)]
            if builds and loop.search(code):
                found.append(CONFIG["patterns"]["performance_loop_kind"])
            for kind in found:
                signals.append({"kind": kind, "at": f"{file}:{number}", "line": code[:120]})
        number += 1
print(json.dumps({"run": bool(signals), "signals": signals}, indent=2))
'
