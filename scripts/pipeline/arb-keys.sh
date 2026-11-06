#!/usr/bin/env bash
# Export changes from configured JSON locale files (empty map disables localization).
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

python3 - "$(feature_base "$SLUG")" <<'PY'
from pipeline_config import CONFIG
import json, subprocess, sys
base = sys.argv[1]
locales = CONFIG['localization']['files']
def strings(text):
    return {k: v for k, v in json.loads(text).items() if not k.startswith(CONFIG['localization']['metadata_prefix'])} if text else {}
now, before = {}, {}
for locale, name in locales.items():
    path = name
    now[locale] = strings(open(path).read())
    shown = subprocess.run(['git', 'show', f'{base}:{path}'], capture_output=True, text=True)
    before[locale] = strings(shown.stdout if shown.returncode == 0 else '')
changed = sorted({k for l in locales for k, v in now[l].items() if before[l].get(k) != v})
print(json.dumps({k: {l: now[l].get(k) for l in locales} for k in changed},
                 indent=2, ensure_ascii=False))
PY
