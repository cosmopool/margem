#!/usr/bin/env bash
# Record the feature's final size and suggest budgets from past features.
#
#   scripts/pipeline/metrics.sh --slug <slug>
#
# Appends the final budget.sh result to .work/<slug>/metrics.json and to a ledger
# shared by every feature of this clone (feature-metrics.jsonl in the git common dir:
# never committed, and it survives switching branches, which .work/ does not). Once the
# ledger holds five features, prints their medians as the suggested default budgets.
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
BUDGET="$("$PIPELINE_SCRIPTS/budget.sh" --base "$(feature_base "$SLUG")" || true)"
LEDGER="$(git rev-parse --path-format=absolute --git-common-dir)/feature-metrics.jsonl"

python3 - "$(work_dir "$SLUG")/metrics.json" "$LEDGER" "$SLUG" "$BUDGET" <<'PY'
from pipeline_config import CONFIG
import json, os, statistics, sys
metrics_path, ledger_path, slug, budget = sys.argv[1:]
budget = json.loads(budget)
metrics = json.load(open(metrics_path)) if os.path.exists(metrics_path) else []
metrics.append({'stage': 'final', 'step': 'final', **budget})
json.dump(metrics, open(metrics_path, 'w'), indent=2)

entries = [json.loads(l) for l in open(ledger_path)] if os.path.exists(ledger_path) else []
entries = [e for e in entries if e['slug'] != slug]
entries.append({'slug': slug, 'added': budget['added'], 'new_files': budget['new_files'],
                'new_public_symbols': budget['new_public_symbols']})
with open(ledger_path, 'w') as ledger:
    ledger.writelines(json.dumps(e) + '\n' for e in entries)

first = next((m for m in metrics if m.get('stage') == 'build'), None)
suggested = None
if len(entries) >= CONFIG['workflow']['metrics_min_features']:
    suggested = {key: int(statistics.median(e[key] for e in entries))
                 for key in ('added', 'new_files', 'new_public_symbols')}
print(json.dumps({'final': budget, 'afterBuild': first, 'features': len(entries),
                  'suggestedBudgets': suggested}, indent=2))
PY
