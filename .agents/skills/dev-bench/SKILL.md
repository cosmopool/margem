---
name: dev-bench
description: Stage 3 of the feature pipeline. Runs only when perf-signals.sh finds a cost signal; measures the feature, proposes only optimisations backed by numbers, and implements the ones whose gain justifies their added lines.
---

Before starting, read `.agents/pipeline/config.json` as `config`. It is the source for
project paths, commands, review instructions, budgets and workflow limits. Pass the
entire parsed object as `config` in every Workflow call. Use its commands and test
naming conventions; do not infer a stack from examples below.

# Stage 3 — measure, then maybe optimise

Read `.agents/pipeline/RULES.md` first.

## Decide whether to run it at all

Skipping it is the common case. An agent asked to produce optimisation proposals will
produce them — speculative caches, memoisation, indirection added so something can be
swapped later — and that is precisely the code growth the rest of the pipeline exists
to prevent. So a script decides, not an agent:

```
scripts/pipeline/perf-signals.sh --slug <slug>
```

`run: false` means skip: say so, and checkpoint `bench/skipped` as the "Commits" section
of `.agents/skills/feature/SKILL.md` describes. `run: true` means it is worth measuring.

## Run

```
Workflow({ name: 'dev-bench', args: { config, slug, base, root, signals } })
```

The workflow measures first and is explicitly allowed to return "nothing measurable" —
treat that as success, not as a failed run. Proposals without a citation to a real
number are rejected before they reach the judge, and the judge weighs measured gain
against added lines.

## Report

Give the user the raw numbers, then what was accepted and rejected and why. If
everything was rejected, that is a useful result worth stating plainly: the feature was
measured and found to be fine.

Anything this stage adds will be scrutinised by `/dev-minimize` next, and that is the
correct order — stage 4 gets the last word on surface added here.
