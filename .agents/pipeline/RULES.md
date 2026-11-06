# Feature pipeline — shared rules

Read `config.json` alongside these stack-independent process rules. All project commands,
file patterns, review rules, boundaries and tunable limits live in that single file.
Configuration takes precedence over examples in this guide.

## Stages

| Command | Stage | Runs as | Human gate |
|---|---|---|---|
| `/feature` | all of them, in order, with checkpoints | main loop driving the workflows | opening questions + manifest; deletion queue; design stop only if it fires |
| `/req-gather` | 0 — elicit the requirement | main loop (interactive) | the whole stage is a conversation |
| `/dev-build` | 1 — tests, then minimal implementation | manifest in main loop, rest in a workflow | manifest approval |
| `/dev-verify` | 2 — bug hunt loop + AC gap tests | workflow, one call per round | design stop, only if it fires |
| `/dev-bench` | 3 — measure, propose, implement | workflow | none; `perf-signals.sh` decides whether it runs |
| `/dev-minimize` | 4 — remove unjustified surface | workflow + main loop | approval queue for defensive deletions |

Stage 0 and the gates are interactive, so they run in the main conversation loop —
subagents and workflow scripts have no channel to ask you anything. `/feature` runs every
stage; the single-stage commands stay for running one stage on its own.

Agents in a candidate worktree read this file, the scripts and the feature folder from
the main checkout by absolute path: the pipeline bundle is not committed, and a
worktree only has the `.work/` of its last checkpoint.

## Feature folder

Everything a stage needs is on disk, so any stage can run in a fresh session weeks later.

```
.work/<slug>/
  requirement-raw.md    verbatim paste from the PO — never edited after creation
  requirement.md        specified / decided / assumed, non-goals, acceptance criteria
  code-map.md           existing code the feature touches or must reuse
  manifest.md           approved files to touch + symbols to add, with budgets
  candidates/           best-of-N patches and their stats (stage 1 scratch)
  state.json            where the run stands: stage, step, round, commit, base, budget,
                        API, pending question — written only by record.sh
  pending.json          the last question the run stopped on (state.json holds the copy)
  verify-loop.json      the last stage 2 round's loop result (state.json holds the copy)
  repros/r<N>/          reproduction tests set aside in stage 2 round N
  bench.md              measurements and proposals (stage 3, if run)
  reports/              one markdown report per stage, plus script logs
  metrics.json          diff size history — the ratchet input for future budgets
  localizations.json         new and changed configured locale keys, from arb-keys.sh
  mr.md                 MR title and description
```

`.work/` is committed only by checkpoint commits on the work branch, which stays on
origin as the audit trail. It never reaches the MR: `finish.sh` builds the MR branch
from the code commits alone. See "Commits and checkpoints".

## Acceptance criteria

Stable IDs, one behaviour each, checkable without reading the implementation:

```
AC-1: Given a list with 2 pending tasks, when the user archives it,
      then the list leaves the home view and appears under Archived.
```

Tests cite the ID in their description. Stage 4 uses that citation to map every added
line to a requirement, so an AC without an ID is an AC that cannot protect its own code.

**Failure ACs are mandatory.** For every I/O boundary the feature touches (see below),
`requirement.md` must state what happens when it fails, or record an explicit assumption.
This is what makes error handling survive stage 4: a failure AC becomes a fault-injection
test, which covers the handler, which makes the handler justified by measurement rather
than by argument.

## Testing

`.claude/pipeline/TESTING.md` governs every test this pipeline writes. Read it before
authoring or judging a test. It is the authority; the notes below only resolve where it
meets the mechanics of this pipeline.

**Tests are integration tests by default.** Set up real state, go in through the public
entry point, assert on observable outcome. One test may cover several ACs — cite all of
their ids in its description. Never one test per AC as a matter of policy, and never one
test file per source file.

**An AC id is a citation, not a granularity.** The ids exist so stage 4 can map added
lines back to a requirement. They do not imply unit-level tests.

**Mock only at the boundaries listed below.** A failure AC is exercised by making the
real boundary fail — a Firestore instance that errors, a channel that returns an error —
not by stubbing an internal collaborator. If a test needs heavy mocking to work, it is
testing implementation: raise its level or drop it.

**API maturity decides how much gets tested up front.** `manifest.md` declares one of:

- `API: settled` — the feature extends an API whose shape has proven itself. Full AC
  coverage is written before implementation, and the suite is the selection oracle for
  the best-of-N race. This is the normal case.
- `API: exploratory` — a new module or concept whose abstractions are still in flux.
  Write smoke ACs only, run a single candidate rather than a race, and defer exhaustive
  tests until the API crystallises. Exhaustive tests against an unproven abstraction
  break constantly and discourage exploration.

An exploratory feature is a deliberate, temporary state. Record in `requirement.md` what
would have to be true for it to become settled, and run `/dev-verify` again then.

## Implementation guideline

Carson Gross's essay *Codin' Dirty* governs how implementation code is written, as
`TESTING.md` governs tests. Inside this pipeline it overrides the repository's
"functions under 20 lines" rule and its preference for many small helpers. In practice:

- **A long function is fine when it keeps one behaviour in one place.** Reading one
  function top to bottom beats chasing ten. Split only when a piece is genuinely reused
  or genuinely separate, never to hit a line count.
- **Keep the number of classes, interfaces and files low.** Every new type is a concept
  the next reader must learn. Add one only when the feature cannot be expressed in the
  types that already exist.
- **No abstraction for a future that has not arrived.** One implementation needs no
  interface; one caller needs no parameter for another. A little duplication is cheaper
  than the wrong abstraction.
- **Code lives where it is used.** Prefer putting behaviour next to the thing it acts
  on over a new layer that forwards to it.

The repository's architecture rules (`AGENTS.md`: layers, DI, constructor injection,
offline-first invariants) still apply in full; this guideline is about size and shape
inside them.

## Budgets

Declared in `manifest.md` before any code is written, enforced by
`scripts/pipeline/budget.sh`. Defaults come from `config.json` → `budgets`.

Raise a budget in the manifest when the feature genuinely needs it — but raise it
*before* implementing, as a decision, never afterwards as an excuse. After `workflow.metrics_min_features` features,
replace these defaults with the median from `metrics.json`.

A budget is checked by a script and fails hard. Never ask an agent whether a diff
"feels large".

## Stage 2 — verify loop

One workflow call per round, at most `workflow.verify_rounds` rounds, driven by the caller. Round 1 hunts the
whole feature diff and closes AC gaps alongside; later rounds hunt only the lines the
previous round's fix changed, and skip findings already raised. The loop ends when a
round confirms no new critical bug to fix. A critical bug still confirmed after the configured last round
stops the run and asks: fix anyway (another round), or back to the manifest.

**Hunt.** Use `workflow.lenses` and the configured `rules.mr-review` instructions with its evidence rule and AGENTS.md
checklist. Questions to the author are dropped.

**Refute, then rate.** A refuter tries to prove each finding wrong and defaults to
refuted when unsure — the cheap first filter. A survivor gets two ratings and a fix-size
estimate (added lines).

### Rating findings

Chance to happen, judged from real use of the app:

| Chance | Meaning |
|---|---|
| common | most users on the normal path |
| occasional | a real but less travelled path: a second device, a slow network, a long list |
| rare | needs an unusual but possible sequence |
| test-only | needs internals or a state the app cannot produce |

Criticality:

| Criticality | Meaning |
|---|---|
| critical | data loss or corruption, sync divergence, crash, security or privacy |
| major | visible wrong behaviour, a broken AC, or silent wrong behaviour the user cannot notice (a missed reminder, a wrong saved state) |
| minor | cosmetic or self-recovering |

A minor failure that is silent counts as major.

### Reproduce, then base-check

A finding counts as confirmed only when a failing integration test triggers it through
a path a real user can reach. Each reproduction goes in its own test file,
the configured `patterns.repro_file` under the closest test directory, so `base-check.sh` can run it alone.

- reachable only through internals: rated test-only on that evidence;
- impossible to trigger in a test (timing, platform channels, sync): marked untestable,
  keeps the refuter's rating, and is fixed only if critical;
- `base-check.sh` runs each reproduction on the base. Passing there, or not compiling,
  means the feature caused it. Failing there too means it already existed: it is a
  follow-up in the final summary and is never fixed in this feature, even if critical.

Reproductions move to `.work/<slug>/repros/r<round>/` after the base check, so the suite
stays green; the fix step restores the ones it fixes.

### Fix-or-report table

For feature-caused findings, decided in code, never by an agent:

Use `config.json` → `workflow.fix_matrix`. A `fix-small` decision uses
`workflow.small_fix_lines` as its maximum added-line estimate.

Untestable findings are fixed only in the critical column. A failing AC gap test is
major and common. Reported findings land in the stage 2 report and the final summary
without code changes.

### Design check

Runs on the findings the table marked as fix, before any fix. It fires when `workflow.cluster_size` or more
of them land in the same file, or when their estimated lines would take the diff over
its budget. Then the run stops and asks: fix anyway, or back to the manifest. Bugs that
cluster or blow the budget usually mean the shape is wrong, and more fixes would bury
that.

## Implementer prohibitions

Named prohibitions are enforceable; "keep it simple" is not. Unless `manifest.md`
explicitly approves it, an implementer may not:

- create a new file, class, mixin, extension, or typedef;
- add an abstract class or interface with a single implementation;
- add optional parameters, config flags, or feature toggles;
- add a wrapper that only forwards to something else;
- add `try`/`catch` around code that does not cross an I/O boundary;
- generalise beyond the ACs ("so it also supports…");
- reimplement anything listed in `code-map.md` as reusable.

Write it as if you have to delete it tomorrow.

## I/O boundaries

Defensive code guarding these is **exempt from deletion** in stage 4 — it may be flagged,
never removed. Defensive code around pure logic is fair game.

Use `config.json` → `rules.io_boundaries` and `patterns.boundary_import`.
Keep these in sync as the project changes.

## Deletion classes (stage 4)

| Class | Examples | Handling |
|---|---|---|
| mechanical | analyzer-confirmed unused element/field/import, unreferenced file, dead code | auto-apply |
| duplication | reimplements a symbol cited by file and line | auto-apply |
| defensive | `try`/`catch`, `??`, `if (x == null)`, validation early-return, `assert` | propose only — you approve |
| boundary | defensive code at an I/O boundary above | never deleted, only reported |

Stage 4 only ever considers lines this feature added. Pre-existing code is out of scope.
Deletions land in their own commit so one `git revert` undoes the whole pass.

**On an `API: exploratory` feature, only the mechanical class runs.** Coverage is a weak
necessity signal when the suite is deliberately thin, and deleting uncovered code there
would punish the exploration the maturity flag exists to permit. Report the rest as
observations and re-run stage 4 once the API settles.

**Coverage says "reached", not "asserted".** Integration tests execute a lot of code
incidentally, so an uncovered line is strong evidence that nothing needs it, while a
covered line is only weak evidence that something does. The asymmetry is the right way
round — it shortens the candidate list rather than inflating it — but do not read
"covered" as "justified" when judging whether a construct earns its place.

## Commits and checkpoints

Commit messages follow the configured `git.commit_types` and `git.allow_coauthors`.
Additional project commit conventions belong in `config.json` → `rules.commits`.

Every finished step makes up to two commits on the work branch:

1. `commit-progress.sh` commits the code the step changed, never `.work/`. A step that
   changed no code commits nothing.
2. `record.sh` writes `state.json`, then `commit-checkpoint.sh` commits only `.work/`
   as `chore(checkpoint): <message>` and pushes. It refuses while code is uncommitted.
   It is the only script that pushes, so another machine never sees a half-done step.

**Resume.** `preflight.sh` refuses a branch with no jira issue, fetches, and stops if
origin is ahead. Commits after the last checkpoint mean a step died partway: their
hashes are printed for the reflog, uncommitted work is stashed, and the branch is
hard-reset to the checkpoint so the step re-runs. A pending question is asked first.

**Never rebase during a run** — it would invalidate the commits in `state.json`.
`finish.sh` cuts the MR branch from the base and cherry-picks every non-checkpoint
commit, oldest first; rebase that branch if needed, never the work branch.

## Reporting rules for agents

- Run the scripts; report their exit code and output verbatim. Never restate a script's
  result in your own words, and never overrule it.
- A duplication finding must cite the pre-existing symbol by `file:line`, or it is dropped.
- A question or finding that does not change the diff is noise. Do not report it.
- "Nothing to report" is a valid and expected result. Do not manufacture findings to
  look useful.
