---
name: dev-verify
description: Stage 2 of the Margem feature pipeline. Runs the dev-verify workflow — bug hunting through independent lenses calibrated by the margem-review rules, adversarial refutation of every finding, acceptance-criteria gap tests, then fixes. Use after /dev-build.
---

# Stage 2 — verify

Read `.claude/pipeline/RULES.md` first.

## Preconditions

`.work/<slug>/reports/stage1.md` exists and `scripts/pipeline/preflight.sh <slug>` passes.

## Run

```
Workflow({ name: 'dev-verify', args: { slug, base, root } })
```

Five lenses hunt the feature diff independently — Db and SQL, month arithmetic and money,
coroutines and Compose lifecycle, I/O boundaries, requirement edges — each holding the evidence
rule of `.claude/skills/margem-review/SKILL.md`, and every finding faces a refuter whose default
is to reject it. In parallel, an agent closes AC coverage gaps.

The refuter does real work: six raised and four refuted is the pipeline behaving, not failing.

## Report

Findings raised vs. confirmed per lens, AC gaps closed, fixes applied. If several findings were
refuted for the same reason, say so — that belongs in RULES.md so hunters stop raising it.
Then suggest `/dev-minimize`, or `/dev-bench` first when the feature has a plausible cost.
