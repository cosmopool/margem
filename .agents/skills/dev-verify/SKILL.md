---
name: dev-verify
description: Stage 2 of the feature pipeline. Runs the dev-verify workflow round by round — mr-review bug lenses, refutation and rating of every finding, reproduction tests, base check, fix-or-report table, design check — until no new critical bug. Use after /dev-build.
---

Before starting, read `.claude/pipeline/config.json` as `config`. It is the source for
project paths, commands, review instructions, budgets and workflow limits. Pass the
entire parsed object as `config` in every Workflow call. Use its commands and test
naming conventions; do not infer a stack from examples below.

# Stage 2 — verify

Read `.claude/pipeline/RULES.md` first.

## Preconditions

`scripts/pipeline/preflight.sh --slug <slug>` exits 0 and `state.json` shows stage 1
done. If stage 1 has not run, there is nothing to verify.

## Run

Follow section 4 of `.claude/skills/feature/SKILL.md`: one `dev-verify` workflow call
per round, at most config.workflow.verify_rounds, with the loop rule and the design stop described there. The rules
for each round — lenses, ratings, reproduction, base check, the fix-or-report table and
the design check — are in the "Stage 2" section of `RULES.md`.

The refutation step is doing real work: a hunter that reports six findings and has four
refuted is behaving correctly, not badly. Speculative fixes add code, which is the thing
this pipeline is built to resist.

## Report

Show the user, per round: findings raised vs. confirmed per lens, their ratings, what
was fixed, reported, or set aside as a pre-existing follow-up, and AC gaps closed.
If several findings were refuted for the same reason, mention it — that usually points
at something worth adding to `RULES.md` so the hunters stop re-raising it.

Then suggest `/dev-bench` if `scripts/pipeline/perf-signals.sh --slug <slug>` finds a
signal, `/dev-minimize` otherwise.
