---
name: dev-run
description: Drives stages 1 to 4 for a slug whose requirement.md already exists — manifest gate, build, verify loop, automatic bench decision, minimize, deletion approval, finish. Use after /req-gather when the requirement was gathered separately; /feature does the same from the PO paste.
---

Before starting, read `.agents/pipeline/config.json` as `config`. It is the source for
project paths, commands, review instructions, budgets and workflow limits. Pass the
entire parsed object as `config` in every Workflow call. Use its commands and test
naming conventions; do not infer a stack from examples below.

# Run stages 1 to 4

Usage: `/dev-run <slug>`

This is `/feature` without stage 0. It owns no rules of its own: follow
`.agents/skills/feature/SKILL.md`, which owns the order, the checkpoints and the stops.

## Preconditions

- `.work/<slug>/requirement.md` exists — if not, stop and run `/req-gather` first.
- `scripts/pipeline/preflight.sh --slug <slug>` exits 0. Read its `mode` and `pending`
  as section 1 of the feature skill says.

## Run

- A fresh run starts at section 2 of the feature skill, step 2 (draft the manifest), and
  goes on from there: lint, approval of the manifest and the Assumed list, then stages 1
  to 4 and the finish.
- A resumed run continues from the feature skill's resume table, exactly as `/feature`
  would.

Stage 3 is no longer opt-in: `perf-signals.sh` decides whether it runs.
