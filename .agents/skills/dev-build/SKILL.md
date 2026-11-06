---
name: dev-build
description: Stage 1 of the feature pipeline. Agrees a file manifest with the user, then runs the dev-build workflow — AC tests first, N competing implementations, smallest passing one wins, minimality review. Use after /req-gather has produced a requirement.md.
---

Before starting, read `.claude/pipeline/config.json` as `config`. It is the source for
project paths, commands, review instructions, budgets and workflow limits. Pass the
entire parsed object as `config` in every Workflow call. Use its commands and test
naming conventions; do not infer a stack from examples below.

# Stage 1 — build it small

Read `.claude/pipeline/RULES.md` first.

The manifest gate below is the cheapest checkpoint in the whole pipeline: eight lines of
review here prevents the invented service layer that would otherwise cost a full diff
review to find. Do not skip it, and do not let the workflow start without it.

## 1. Preconditions

- `.work/<slug>/requirement.md` exists. If not, stop and tell the user to run
  `/req-gather` — stage 1 has no oracle without acceptance criteria.
- `scripts/pipeline/preflight.sh --slug <slug>` exits 0. It refuses a branch with no
  jira issue and a remote that is ahead, and records the base in `state.json`, so every
  later stage measures against the same point.

## 2. Draft the manifest

Re-read `code-map.md`, and search the codebase again for anything the feature could
reuse — this is where duplication is prevented, not in review. Then draft
`.work/<slug>/manifest.md`:

```markdown
# Manifest — <slug>

## Files to touch
- <existing production file> — describe the smallest change

## New files
- None, unless a new file is needed and justified.

## Public symbols to add
- List names and why each is needed, or None.

## Reused, not rebuilt
- Existing symbols with file:line references.

## Budget
Use `config.budgets` as defaults and write concrete values:
`added N · new files N · new public symbols N`.
Raise them only before implementation, with a stated reason.

## API
`settled` or `exploratory`.
```

## 3. Gate

Run `scripts/pipeline/lint-feature.sh --slug <slug>` and fix what it reports until it
exits 0; keep its `budget` and `api`. Show the manifest and ask the user with
`AskUserQuestion`: approve, edit, or cancel. If they edit, rewrite the file, lint and
ask again. Nothing runs until it is approved. On approval, checkpoint `open/approved` as
the "Commits" section of `.claude/skills/feature/SKILL.md` describes, with
`--set budget='<budget>' --set api='"<api>"'`.

This is the moment to push back. If the manifest lists four new files for a feature
with three ACs, say so now rather than letting three candidates each build it.

## 4. Run the workflow

```
Workflow({
  name: 'dev-build',
  args: { config,
    slug, base, root,          // base from state.json; root = git rev-parse --show-toplevel
    candidates: config.workflow.candidates,
    budget,                    // { added, newFiles, newSymbols } from lint-feature.sh
    apiMaturity,               // from lint-feature.sh
    from: 'tests',             // or 'implement' / 'review' to resume, from state.step
  },
})
```

It authors the AC tests, races `candidates` implementations in isolated worktrees, keeps
the smallest one that passes tests, analyze, `hygiene.sh` and `candidate-check.sh`,
applies it with `apply.sh`, reviews it for minimality with a bounded revise loop, and
checkpoints after the tests, the apply and the review. An `exploratory` API collapses the
race to a single candidate — a thin suite cannot referee a size contest.

Each candidate runs the configured `commands.setup`. Tune `workflow.candidates` for setup cost.

## 5. Report

Give the user the candidate spread ("96 / 143 / 210 added lines — kept 96"), the
winner's budget numbers, and anything the reviewer flagged but could not fix. The
spread is worth showing every time: it is the evidence that selection is doing work.

Then suggest `/dev-verify`.
