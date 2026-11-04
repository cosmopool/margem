---
name: dev-bench
description: Stage 3 of the Margem feature pipeline, opt-in. Measures the feature (Db reads on the VolumeTest data, budgetMonth, recomposition), proposes only optimisations backed by numbers, and implements the ones whose gain justifies their added lines. Use only when a feature has a plausible performance concern.
---

# Stage 3 — measure, then maybe optimise

Read `.claude/pipeline/RULES.md` first.

## Decide whether to run it at all

Opt-in by design; skipping is the common case. An agent asked for optimisations will produce
them — caches, memoisation, indirection — and that is the growth the pipeline exists to stop.

Run it when the feature does something with a plausible cost: a new or wider SQL read over months
of data (`budgetsOverview`, `pending`, `history` — D49's volume test is the harness), a loop over
every lançamento or occurrence, work inside a composable that runs on every recomposition, a write
that triggers `updateAll` on the widgets. Skip it for ordinary screen and form work, and say so.

## Run

```
Workflow({ name: 'dev-bench', args: { slug, base, root } })
```

"Nothing measurable" is a success. Proposals without a real number are rejected before the judge.

## Report

The raw numbers, then what was accepted and rejected and why. Stage 4 gets the last word on any
surface added here.
