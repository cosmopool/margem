#!/usr/bin/env bash
# Check requirement.md and manifest.md before the manifest goes to approval.
#
#   scripts/pipeline/lint-feature.sh --slug <slug>
#
# requirement.md: every section present, at least one AC, AC ids unique, and every
# touched boundary mapped to an AC-F id that exists. manifest.md: every section present,
# a budget line `added N · new files N · new public symbols N`, and `settled` or
# `exploratory` under API.
#
# Prints JSON with the errors, the AC ids, the budget and the API setting, so the
# workflows take them from here and no agent reads them by eye. Exits 1 on any error.
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

python3 - "$(work_dir "$SLUG")" <<'PY'
import json, os, re, sys

work = sys.argv[1]
errors = []

def sections(name, required):
    path = os.path.join(work, name)
    if not os.path.exists(path):
        errors.append(f'{name} is missing')
        return {}
    found, current = {}, None
    for line in open(path):
        heading = re.match(r'^##\s+(.+?)\s*$', line)
        if heading:
            current = heading.group(1).lower()
            found[current] = []
        elif current:
            found[current].append(line.rstrip('\n'))
    for section in required:
        if section.lower() not in found:
            errors.append(f'{name}: no "## {section}" section')
    return found

req = sections('requirement.md', ['Summary', 'Specified', 'Decided', 'Assumed', 'Non-goals',
                                  'Acceptance criteria', 'Touched boundaries'])
acs = [m.group(1) for line in req.get('acceptance criteria', [])
       if (m := re.match(r'^\s*[-*]?\s*(AC-F?\d+):', line))]
if req and not acs:
    errors.append('requirement.md: no acceptance criteria (lines starting "AC-1:")')
for ac in sorted({a for a in acs if acs.count(a) > 1}):
    errors.append(f'requirement.md: {ac} is defined more than once')
for line in req.get('touched boundaries', []):
    if not re.match(r'^\s*[-*]\s+\S', line):
        continue
    cited = re.findall(r'AC-F\d+', line)
    if not cited:
        errors.append(f'requirement.md: boundary has no failure AC: {line.strip()}')
    for ac in cited:
        if ac not in acs:
            errors.append(f'requirement.md: boundary cites {ac}, which is not an AC')

man = sections('manifest.md', ['Files to touch', 'New files', 'Public symbols to add',
                               'Reused, not rebuilt', 'Budget', 'API'])
budget = None
text = ' '.join(man.get('budget', []))
m = re.search(r'added\s+(\d+)\s*[·,]\s*new files\s+(\d+)\s*[·,]\s*new public symbols\s+(\d+)', text)
if m:
    budget = {'added': int(m[1]), 'newFiles': int(m[2]), 'newSymbols': int(m[3])}
elif man:
    errors.append('manifest.md: budget must read "added N · new files N · new public symbols N"')
api = ' '.join(man.get('api', [])).strip().lower() or None
if man and api not in ('settled', 'exploratory'):
    errors.append(f'manifest.md: API must be "settled" or "exploratory", got {api!r}')

print(json.dumps({'ok': not errors, 'errors': errors, 'acs': acs,
                  'budget': budget, 'api': api}, indent=2))
sys.exit(1 if errors else 0)
PY
