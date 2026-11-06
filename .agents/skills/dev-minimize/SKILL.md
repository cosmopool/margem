---
name: dev-minimize
description: Stage 4 of the feature pipeline. Runs the dev-minimize workflow to find code no requirement asks for, auto-removes the mechanically dead parts, and asks the user to approve any defensive deletions. Use as the last stage before opening the MR.
---

Before starting, read `.claude/pipeline/config.json` as `config`. It is the source for
project paths, commands, review instructions, budgets and workflow limits. Pass the
entire parsed object as `config` in every Workflow call. Use its commands and test
naming conventions; do not infer a stack from examples below.

# Stage 4 — remove what nothing asked for

Read `.claude/pipeline/RULES.md` first.

## Preconditions

`scripts/pipeline/preflight.sh --slug <slug>` exits 0. The suite must be green — this
stage uses the tests as its safety net, and `measure.sh` refuses a red suite.

## Run

```
Workflow({ name: 'dev-minimize', args: { config, slug, base, root, apiMaturity } })
```

Pass the `API` line from `manifest.md`. On an `exploratory` feature only mechanically
dead code is removed and everything else comes back as observations — coverage is too
weak a necessity signal when the suite is deliberately thin, and deleting uncovered code
there would punish exactly the exploration that flag exists to permit. Say so when you
report, and note that stage 4 is worth re-running once the API settles.

`measure.sh` runs coverage against the feature diff (an added line no test reaches is a
line no acceptance criterion asked for) and pre-sorts by analyzer and pattern; an agent
classifies only the judgement calls. The mechanically dead and the duplicated are
removed in their own commit, and the approval queue comes back, stored as the pending
question so a resumed run asks it again.

The workflow is asked for at least five candidates. If it comes back with fewer and an
account of what it examined instead, that is the result you want — it means the diff is
tight, and it is stronger evidence than an approving review.

## The approval queue

Anything defensive — `try`/`catch`, `??`, null checks, validation early-returns,
`assert` — reaches you rather than being deleted. Present it with `AskUserQuestion`,
multi-select, one batch. For each item show the construct, why nothing requires it, and
what breaks if it was in fact needed.

Two things to say out loud when you present the queue:

- Defensive code at an I/O boundary is never in this queue; it is reported separately
  and left alone. If one shows up as uncovered, the actual gap is a missing failure AC
  in `requirement.md` — fix the requirement, not the code.
- An uncovered defensive construct away from a boundary is usually either genuinely
  speculative, or evidence that stage 0 missed an edge case. Deleting it is the default,
  but "add the missing AC instead" is always an available answer.

Remove whatever the user approves, check that `scripts/pipeline/ac-map.sh --slug <slug>
--expect green` and the configured `commands.analyze` still pass, and checkpoint
`minimize/approved` with `--clear-pending` and the code message
`refactor(<slug>): remove approved defensive surface`. Deletions stay in their own
commits so a single `git revert` undoes the pass.

## Report

Run `scripts/pipeline/metrics.sh --slug <slug>` and report its numbers against the
stage 1 entry — added lines, new files, new public symbols, before and after. Once five
features are in its ledger it suggests new default budgets for `RULES.md`.

Then the feature is ready for the MR.
