---
name: dev-run
description: Drives the whole Margem feature pipeline for one slug — manifest gate, build with contract round and triage, verify, optional bench, minimize, deletion approval, then landing on main with finish.sh after one approval. Use after /req-gather, when the user wants the rest run end to end rather than stage by stage.
---

# Run the pipeline end to end

Read `.claude/pipeline/RULES.md` first. Every stage takes `apiMaturity` from the `API` line in
`manifest.md` — read it once after the gate and pass it to each workflow.

Usage: `/dev-run <slug>` — add `--bench` to include stage 3, otherwise skipped.

This skill is a driver with no rules of its own: each stage's behaviour lives in that stage's
skill and workflow. Change it there.

## Preconditions

- `.work/<slug>/requirement.md` exists and its Assumed list is confirmed.
- `scripts/pipeline/preflight.sh <slug>` passes (it runs again before every stage).

If `.work/<slug>/reports/` already holds stage reports, say which stages ran and ask (one
question) whether to resume from the next one or re-run from the top. Never silently redo
committed work.

## 1. Manifest — gate

`.claude/skills/dev-build/SKILL.md`, "Draft the manifest" and "Gate". Rejected outright → stop.

## 2. Build

The dev-build workflow, args from the manifest (see dev-build). If it returns `specGaps`, settle
them exactly as dev-build §5 says, one question at a time, before going on.

Then print the summary before anything else: candidate spread, winner's budget numbers, review
verdict, triage counts, anything flagged but not fixed. Say stage 2 starts next and the user can
interrupt. A visible checkpoint, not a question.

`ok: false` → stop and report.

## 3. Verify

`Workflow({ name: 'dev-verify', args: { slug, base, root } })` — findings raised vs. confirmed per
lens, AC gaps closed. `ok: false` → stop.

## 4. Bench — only with `--bench`

`Workflow({ name: 'dev-bench', args: { slug, base, root } })`. Without `--bench`, if the feature
has an obvious cost profile (a wider SQL read, a loop over every lançamento), say so in one line.

## 5. Minimize — gate

`Workflow({ name: 'dev-minimize', args: { slug, base, root, apiMaturity } })`, then the approval
queue exactly as dev-minimize describes. An empty queue: say so and move on.

## 6. Land — gate

Run `scripts/pipeline/finish.sh <slug>` (dry run: the commits that would land). Show it with the
summary below and ask once, with `AskUserQuestion`: land on main now, or leave it on the branch.
On yes, `scripts/pipeline/finish.sh <slug> --yes` (cherry-pick + `make check`); report its output
verbatim. It never pushes main — tell the user to push. If `make check` fails on main, stop and
report; the work branch is untouched.

## 7. Report

- acceptance criteria and the tests covering them
- candidate spread and the one kept
- contract round: tests written per area, triage outcome, decisions recorded (D-rows, spec §)
- findings confirmed vs. refuted
- lines, files and symbols added, before and after stage 4, from `metrics.json`
- anything a stage flagged and could not resolve — the part worth reading

Remind the user that `make mutation` runs apart (D48) and is worth running now that `shared`
changed.

## When a stage fails

Stop the chain. Report which stage, what it returned, and the branch's state. The driver never
improvises a fix and never advances past a failure.
