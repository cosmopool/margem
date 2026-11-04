---
name: dev-build
description: Stage 1 of the Margem feature pipeline. Agrees a manifest with the user, then runs the dev-build workflow — contract commit, AC tests, N competing implementations in parallel with the blind contract round, smallest passing one wins, minimality review, then contract-round triage, stopping for the user on spec gaps. Use after /req-gather has produced a requirement.md.
---

# Stage 1 — build it small

Read `.claude/pipeline/RULES.md` first.

The manifest gate is the cheapest checkpoint in the pipeline: ten lines of review here prevent the
invented layer that would otherwise cost a full diff review to find. Do not skip it.

## 1. Preconditions

- `.work/<slug>/requirement.md` exists, and the user confirmed its Assumed list. If not, stop.
- `scripts/pipeline/preflight.sh <slug>` passes; it prints the base sha.
- `root` = `git rev-parse --show-toplevel`.

## 2. Draft the manifest

Re-read `code-map.md` and search the code again for anything to reuse — this is where duplication
is prevented, not in review. Then draft `.work/<slug>/manifest.md`:

```markdown
# Manifest — <slug>

## Files to touch
- shared/src/commonMain/kotlin/com/kaiodelphino/margem/Db.kt — two sums in budgetsOverview
- androidApp/src/main/kotlin/com/kaiodelphino/margem/ui/Budgets.kt — legend in the header

## New files
(none — prefer this)

## Contract (public symbols to add or change; the contract commit writes exactly these)
- `BudgetMonth.open: Cents` — the month's open reserve; AC-1, AC-2
- `BudgetTags.HEADER_LEGEND` — the legend under the header; AC-4

## Reused, not rebuilt
- `budgetMonth` (Month.kt:41) — the reserve is one more input, not a second function

## Budget
added 600 · new files 1 · new public symbols 10

## API
settled

## Contract round
prefix Reserva · areas: edge, fuzz, concurrency, invariants, ui
docs to read: spec §2.13 §3 §4 §5 · architecture D68–D74, §5, §6
```

Deviate from the default budget only with a stated reason, and only here. Declare `exploratory`
only for a genuinely new concept (see RULES.md): no race and no contract round, then.

## 3. Gate

Show the manifest and ask with `AskUserQuestion` (one question): approve, edit, or cancel. On an
edit, rewrite and ask again. Nothing runs until it is approved. Push back here: four new symbols
for a two-AC feature is worth saying now. Approved → commit `docs: <slug> manifest` and push.

## 4. Run the workflow

```
Workflow({ name: 'dev-build', args: {
  slug, base, root,
  candidates: 2,
  maxAdded: 600, maxNewFiles: 1, maxNewSymbols: 10,   // from the manifest
  apiMaturity: 'settled',                             // from the manifest
  prefix: 'Reserva', areas: ['edge','fuzz','concurrency','invariants','ui'],
  docs: 'spec §2.13 §3 §4 §5 · architecture D68–D74, §5, §6',
} })
```

It commits the contract, writes the AC tests (red), races the candidates in isolated worktrees
while the contract agents write their blind tests, applies the smallest green candidate, reviews
it for minimality, triages the contract tests, and commits. An `exploratory` API collapses the
race to one candidate and skips the round.

## 5. Spec gaps — the second gate

If the workflow returns `specGaps`, the run stopped before its last commit with the working tree
holding the applied implementation and the triage so far. For each gap, one at a time: ask the
user with `AskUserQuestion` (recommendation first, the failing test and the spec § in the
question). Write the answer to the docs, then make the code or the test follow it — never loosen a
test — and record it in `.work/<slug>/triage.md`. When all are settled and `make test` is green,
commit `fix: <slug> contract triage` and push.

## 6. Report

The candidate spread ("212 / 348 added lines — kept 212"), the winner's budget numbers, the
reviewer's leftovers, and the triage counts (code bugs, misread tests, spec gaps). Then suggest
`/dev-verify`.
