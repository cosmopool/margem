---
name: feature
description: Runs the whole feature pipeline for one slug, from the PO paste to MR text on disk — requirement questions and manifest approval up front, then build, a verify loop, an automatic bench decision and minimize unattended, then one stop for defensive deletions. Checkpoints every step, so a run resumes on any machine. Use when the user wants a feature built end to end, or invokes /feature.
---

Before starting, read `.claude/pipeline/config.json` as `config`. It is the source for
project paths, commands, review instructions, budgets and workflow limits. Pass the
entire parsed object as `config` in every Workflow call. Use its commands and test
naming conventions; do not infer a stack from examples below.

# /feature — one command, two stops

Usage: `/feature <slug>`, with the PO's requirement pasted alongside it or asked for.

Read `.claude/pipeline/RULES.md` first. This skill is a driver: each stage's rules live in
RULES.md, in the stage skills and in the workflows, and this file only decides what runs
next and where to stop. You talk to the user at the opening and at the end. Between them
the run is unattended and stops only when the design check fires, a critical bug
survives the configured last round, or something fails.

Every script lives in `scripts/pipeline/` and prints JSON. Report what it prints; never
restate it in your own words or overrule it. `root` below is `git rev-parse --show-toplevel`.

## Commits

Each finished step makes up to two commits, by the "Commits and checkpoints" rules in
RULES.md. The workflows do this for their own steps. When this skill closes a step
itself, it runs the same three scripts in this order, with messages written by the
`caveman-commit` rules:

1. `commit-progress.sh -m "<message>"` (commits nothing when no code changed)
2. `record.sh --slug <slug> --stage <stage> --step <step> [...]`
3. `commit-checkpoint.sh -m "<message>"` (commits `.work/` and pushes)

"Checkpoint `<stage>/<step>`" below means those three.

## 1. Preflight

```
scripts/pipeline/preflight.sh --slug <slug>
```

Exit 1 means stop: tell the user what it said (no jira issue in the branch name, origin is
ahead, state does not match the branch) and end the turn. Otherwise read `mode`:

- `fresh` or `opening`: run the opening session (section 2).
- `resume`: if `orphans` or `stash` are not empty, say so first — the commits can be
  recovered from the reflog and the uncommitted work is in that stash. If `pending` is
  set, ask that question before anything else (section 7 or 4.2 tells you how). Then
  continue from the table below.

| `state.stage` / `state.step` | Continue at |
|---|---|
| `open` / `start` | 2, the opening session |
| `open` / `approved` | 3, with `from: 'tests'` |
| `build` / `tests` | 3, with `from: 'implement'` |
| `build` / `apply` | 3, with `from: 'review'` |
| `build` / `done` | 4, round 1 |
| `verify` / `round-N` | 4.1, deciding from `state.verify` |
| `verify` / `round-N-design-stop` | 4.2, the pending question |
| `bench` / `done` or `skipped` | 6 |
| `minimize` / `auto` | 7, the pending deletion queue (or 8 if none) |
| `minimize` / `approved` | 8 |
| `finish` / `done` | nothing left: print the final summary again |

## 2. Opening session — you answer here

1. **Capture, map, ask, write.** Follow `.claude/skills/req-gather/SKILL.md` steps 1 to 4
   exactly: the paste goes verbatim to `requirement-raw.md`, the code map to
   `code-map.md`, the traceable and consequential questions (at most 4 per round, at most
   config.workflow.question_rounds rounds, every touched boundary's failure asked), then `requirement.md`.
   Do not stop after step 4 as that skill does: the hand-back happens in the approval below.
2. **Draft the manifest.** Follow "Draft the manifest" in `.claude/skills/dev-build/SKILL.md`.
3. **Lint.** `scripts/pipeline/lint-feature.sh --slug <slug>`. Fix every error it lists
   in the two files and run it again until it exits 0. Keep its `budget` and `api`.
4. **Approve.** Show the manifest, then the Assumed list and the non-goals from
   `requirement.md`, and ask once with `AskUserQuestion`: approve, edit, or cancel.
   Approving accepts the Assumed list too. On edit, change the files, lint again and ask
   again. On cancel, stop; nothing after this point runs.
5. **Checkpoint `open/approved`** with
   `--set budget='<budget JSON from lint>' --set api='"<api>"'`.
   Say that the run is now unattended, and continue without ending the turn.

## 3. Stage 1 — build

```
Workflow({ name: 'dev-build', args: { config, slug, base, root, candidates: config.workflow.candidates,
                                      budget, apiMaturity: api, from } })
```

`base`, `budget` and `api` come from `state.json`. On `ok: false`, stop (see "When something fails").
Otherwise print the candidate spread, the winner's numbers and anything the reviewer
could not fix, as a visible checkpoint rather than a question, and go on.

## 4. Stage 2 — verify loop

### 4.1 Rounds

Run one round per call, at most config.workflow.verify_rounds rounds:

```
Workflow({ name: 'dev-verify', args: { config, slug, base, root, budget, round,
                                       scope, seen } })
```

- Round 1: `scope: { from: base, to: null }`, `seen: []`.
- Round N+1: `scope: { from: '<fix>^', to: '<fix>' }`, where `<fix>` is the previous
  round's `fixCommit` (on resume: `state.commit`, which is that round's code commit), and
  `seen` from the previous round (on resume: `state.verify.seen`).

After each round, using its result (on resume: `state.verify`):

- `newCritical` is 0: the loop ends. Go to 5.
- `newCritical` > 0 and round < config.workflow.verify_rounds: run the next round.
- `newCritical` > 0 after the configured last round: a critical bug survived the configured round limit. Ask as in 4.2,
  with that as the reason. "Fix anyway" runs the next round and continues the loop.

`ok: false` stops the run (see "When something fails").

### 4.2 Design stop

A round returns `designStop` when config.workflow.cluster_size or more fixes land in one file or the fixes would
take the diff over budget. The workflow has already checkpointed it as `pending`. Show
the reason and the findings it would fix, then ask with `AskUserQuestion`:

- **Fix anyway** — run the same round again with `fixOnly: designStop.toFix`, then
  continue the loop from its result.
- **Back to the manifest** — stop the run. Say the manifest needs reshaping and that
  rerunning `/feature <slug>` after editing it resumes from there.

## 5. Stage 3 — bench, only on a signal

```
scripts/pipeline/perf-signals.sh --slug <slug>
```

- `run: false`: checkpoint `bench/skipped`; say "no cost signal, stage 3 skipped".
- `run: true`: `Workflow({ name: 'dev-bench', args: { config, slug, base, root, signals } })`.
  "Nothing measurable" is a success.

## 6. Stage 4 — minimize

```
Workflow({ name: 'dev-minimize', args: { config, slug, base, root, apiMaturity: api } })
```

It removes the mechanical and duplicated code in its own commit and returns the approval
queue, already stored as `pending`. On `ok: false`, stop (see "When something fails").

## 7. Deletion queue — you answer here

Follow "The approval queue" in `.claude/skills/dev-minimize/SKILL.md`: one batch,
multi-select, each item with its construct, why nothing needs it, and what breaks if it
was needed. `AskUserQuestion` takes at most 4 options per question and 4 questions per
call, so split a longer queue into several questions. Boundary-protected code is listed,
never offered. "Add the missing AC instead" is always a valid answer: take it from Other.

Remove what was approved. `scripts/pipeline/ac-map.sh --slug <slug> --expect green` and
the configured `commands.analyze` must pass; restore anything that breaks them and say so. Then
checkpoint `minimize/approved` with `--clear-pending` and the code message
`refactor(<slug>): remove approved defensive surface`. An empty queue is still
checkpointed: say it was empty.

## 8. Finish

1. `scripts/pipeline/metrics.sh --slug <slug>`
2. `scripts/pipeline/arb-keys.sh --slug <slug> > .work/<slug>/localizations.json`
3. Ask with `AskUserQuestion` for the name of the clean branch the MR will be opened
   from. Then `scripts/pipeline/finish.sh --slug <slug> --into <name>`: it cherry-picks
   every code commit, without checkpoints, onto a new branch from the base.
4. Follow `.claude/skills/mr-description/SKILL.md` for that branch against the configured `git.base`,
   and write the title and description to `.work/<slug>/mr.md`.
5. Checkpoint `finish/done`.

## 9. Final summary

- the ACs and the tests covering them (`ac-map.sh --slug <slug>`)
- the candidate spread and which one was kept
- findings per round: fixed, reported, and pre-existing follow-ups, with their ratings
- size after stage 1 and at the end, from `metrics.sh`, plus suggested budgets if any
- anything a stage flagged and could not resolve — the part worth reading
- the clean branch, `.work/<slug>/mr.md` and `.work/<slug>/localizations.json`

Print the localization JSON in the chat as well. Then say plainly: the work branch with its
checkpoints is on origin as the audit trail; the clean branch is local only, and the user
pushes it and opens the MR. Rebase only the clean branch, never the work branch.

## When something fails

Stop the run. Report which stage and step failed, what it returned, and that the last
checkpoint is the resume point. Never improvise a fix and never advance past a failure.
After the cause is dealt with, `/feature <slug>` resumes from the last checkpoint.
