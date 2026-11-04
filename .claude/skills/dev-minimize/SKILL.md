---
name: dev-minimize
description: Stage 4 of the Margem feature pipeline. Runs the dev-minimize workflow to find code no requirement asks for — Kover coverage of the feature diff, kotlinc and detekt dead-code findings — auto-removes the mechanical and duplicated parts, and asks the user to approve any defensive deletions. Use as the last stage before landing on main.
---

# Stage 4 — remove what nothing asked for

Read `.claude/pipeline/RULES.md` first.

## Preconditions

`make test` green — this stage uses the tests as its safety net. `preflight.sh <slug>` passes, so
the deletion commits stand alone.

## Run

```
Workflow({ name: 'dev-minimize', args: { slug, base, root, apiMaturity } })
```

It runs `make coverage` and `uncovered.sh` against the feature diff, `dead.sh`, and `budget.sh`;
classifies every candidate; removes the mechanical and duplicated ones in their own commit; and
returns an approval queue. Fewer than five candidates with an account of what was examined is the
result you want: the diff is tight.

On an `exploratory` feature only mechanical code goes; say so, and that stage 4 is worth re-running
once the API settles.

## The approval queue

Defensive constructs — `try`/`catch`, `runCatching`, `?:` fallbacks, `?.let` guards,
`require`/`check`, validation early-returns — reach the user instead of being deleted. Present
them with `AskUserQuestion`, **one item per question** (the user's rule), recommendation first:
the construct, why nothing requires it, what breaks if it was needed. Options: delete it (usually
recommended), keep it, or add the missing AC instead.

Say out loud when presenting: defensive code at an I/O boundary is never in the queue; if one
shows up uncovered, the gap is a missing failure AC in `requirement.md`.

Apply what the user approves in a second commit, `refactor: <slug> drop approved defensive code`,
and push.

## Report

Final numbers against the stage 1 baseline in `metrics.json` — added lines, new files, new public
symbols, before and after. Append the final entry. Then the feature is ready to land
(`/dev-run` does it, or `scripts/pipeline/finish.sh <slug>`).
