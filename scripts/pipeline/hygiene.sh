#!/usr/bin/env bash
# Check configured added-line hygiene rules, locale completeness and dependency changes.
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
intent_to_add

mapfile -t DIFF_PATHS < <(config pathspec production)
ADDED="$(git diff -U0 "$BASE" -- "${DIFF_PATHS[@]}")"
mapfile -t DEP_PATHS < <(config pathspec dependencies)
DEPS="$(git diff --name-only "$BASE" -- "${DEP_PATHS[@]}")"

python3 - "$BASE" "$(work_dir "$SLUG")/manifest.md" "$ADDED" "$DEPS" <<'PY'
from pipeline_config import CONFIG
import json, re, subprocess, sys
base, manifest, added, deps = sys.argv[1:]
violations = []

rules = [(name, re.compile(pattern)) for name, pattern in CONFIG['patterns']['rules']]
file = None
for line in added.splitlines():
    if line.startswith('+++ '):
        file = line[6:] if line.startswith('+++ b/') else None
    elif line.startswith('@@'):
        number = int(re.search(r'\+(\d+)', line).group(1))
    elif line.startswith('+') and file:
        code = line[1:]
        if not re.search(CONFIG['patterns']['comment'], code):
            for kind, pattern in rules:
                if pattern.search(code):
                    violations.append({'kind': kind, 'at': f'{file}:{number}', 'line': code.strip()})
        number += 1

locales = list(CONFIG['localization']['files'].values())
def keys(text):
    return {k for k in json.loads(text) if not k.startswith(CONFIG['localization']['metadata_prefix'])} if text else set()
def at_base(path):
    shown = subprocess.run(['git', 'show', f'{base}:{path}'], capture_output=True, text=True)
    return shown.stdout if shown.returncode == 0 else ''
now, before = {}, set()
for name in locales:
    path = name
    try:
        now[name] = keys(open(path).read())
    except FileNotFoundError:
        now[name] = set()
    before |= keys(at_base(path))
for key in sorted(set().union(set(), *now.values()) - before):
    missing = [name for name in locales if key not in now[name]]
    if missing:
        violations.append({'kind': 'arb-key-missing-locale', 'at': key, 'line': ', '.join(missing)})

listed = open(manifest).read() if deps else ''
for path in deps.split():
    if path not in listed:
        violations.append({'kind': 'dependency-change-not-in-manifest', 'at': path, 'line': ''})

print(json.dumps({'ok': not violations, 'violations': violations}, indent=2))
sys.exit(1 if violations else 0)
PY
