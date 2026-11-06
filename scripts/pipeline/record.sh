#!/usr/bin/env bash
# Record where a feature run stands, in .work/<slug>/state.json.
#
#   scripts/pipeline/record.sh --slug <slug> --stage <stage> --step <step> [--round N]
#                              [--set key=<json>]... [--set-file key=<json-file>]...
#                              [--pending <json-file>] [--clear-pending] [--metrics]
#
# Run after commit-progress.sh and before commit-checkpoint.sh. `commit` is set to HEAD,
# which is the commit the checkpoint will sit on; preflight.sh later checks the branch
# against it. --metrics appends the current budget.sh result to metrics.json.
# --pending stores a question the run is waiting on, so a resumed run asks it first.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

SLUG="" STAGE="" STEP="" ROUND="null" PENDING="" CLEAR=0 METRICS=0
SETS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --slug)          SLUG="$2"; shift 2 ;;
    --stage)         STAGE="$2"; shift 2 ;;
    --step)          STEP="$2"; shift 2 ;;
    --round)         ROUND="$2"; shift 2 ;;
    --set)           SETS+=("$2"); shift 2 ;;
    --set-file)      SETS+=("${2%%=*}=$(cat "${2#*=}")"); shift 2 ;;
    --pending)       PENDING="$2"; shift 2 ;;
    --clear-pending) CLEAR=1; shift ;;
    --metrics)       METRICS=1; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "$SLUG" ] && [ -n "$STAGE" ] && [ -n "$STEP" ] || die "--slug, --stage and --step are required"
W="$(work_dir "$SLUG")"
[ -f "$W/state.json" ] || die "no $W/state.json — run preflight.sh first"

BUDGET_JSON=""
if [ "$METRICS" -eq 1 ]; then
  BUDGET_JSON="$("$PIPELINE_SCRIPTS/budget.sh" --base "$(feature_base "$SLUG")" || true)"
fi

python3 - "$W" "$STAGE" "$STEP" "$ROUND" "$(git rev-parse HEAD)" "$PENDING" "$CLEAR" \
  "$BUDGET_JSON" "${SETS[@]+"${SETS[@]}"}" <<'PY'
import json, os, sys
work, stage, step, round_, head, pending, clear, budget, *sets = sys.argv[1:]
path = os.path.join(work, 'state.json')
state = json.load(open(path))
state.update(stage=stage, step=step, round=json.loads(round_), commit=head)
for item in sets:
    key, _, value = item.partition('=')
    state[key] = json.loads(value)
if pending:
    state['pending'] = json.load(open(pending))
elif clear == '1':
    state['pending'] = None
state.setdefault('history', []).append({'stage': stage, 'step': step, 'round': state['round'], 'commit': head})
json.dump(state, open(path, 'w'), indent=2)
if budget:
    metrics_path = os.path.join(work, 'metrics.json')
    metrics = json.load(open(metrics_path)) if os.path.exists(metrics_path) else []
    metrics.append({'stage': stage, 'step': step, **json.loads(budget)})
    json.dump(metrics, open(metrics_path, 'w'), indent=2)
print(json.dumps(state, indent=2))
PY
