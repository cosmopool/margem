# Feature pipeline — shared rules (Margem)

Single source of truth for every stage. Every agent prompt in this pipeline begins by reading
this file. Change a rule here, not in a stage script. Ported from `~/Development/pipeline`
(uList Flutter) on 2026-11-04; architecture D75 records the decisions behind this port.

Also binding on every agent: `AGENTS.md` (Codin' Dirty: few files, few concepts, big important
things), `.agents/rules/testing.md`, and the decided rows of `docs/architecture.md`.

## Stages

| Command | Stage | Runs as | Human gate |
|---|---|---|---|
| `/req-gather` | 0 — elicit the requirement | main loop (interactive) | the whole stage is a conversation |
| `/dev-build` | 1 — contract, tests, minimal implementation, contract round | manifest in main loop, rest in a workflow | manifest approval; spec gaps from triage |
| `/dev-verify` | 2 — bug hunt + AC gap tests | workflow | none |
| `/dev-bench` | 3 — measure, propose, implement | workflow | opt-in; you choose to run it at all |
| `/dev-minimize` | 4 — remove unjustified surface | workflow + main loop | approval queue for defensive deletions |
| `/dev-run` | 0→4 driver, then `finish.sh` | main loop | the gates above, then landing on main |

Stage 0 and every gate are interactive, so they run in the main loop — subagents and workflow
scripts have no channel to ask the user anything.

**Questions go to the user one at a time** (AGENTS.md, "Decisions are mine"): one
`AskUserQuestion` with one question, the recommended answer as the first option, then wait.
Facts that the repo, the docs or git can answer are looked up, never asked.

## Branches, commits, landing

- Work happens on a **work branch**, any name except `main`. `scripts/pipeline/preflight.sh
  <slug>` checks it before every stage: not main, clean tree, not behind `origin/<branch>`,
  `local.properties` present; it writes `.work/<slug>/base.txt` the first time.
- Every stage **commits its own checkpoint** on the work branch and **pushes the work branch**
  (`git push -u origin HEAD`), so a run can resume on another machine. The pipeline never
  commits to, and never pushes, `main`.
- Commit messages: terse Conventional Commits as in `git log` (`feat: …`, `test: …`, `docs: …`,
  lowercase, no scope needed, subject ≤50 chars), **no Co-Authored-By trailer**, no AI
  attribution.
- Landing: after the last stage, the driver shows `scripts/pipeline/finish.sh <slug>` (dry run)
  and asks the user once. On yes, `finish.sh <slug> --yes` cherry-picks the branch onto main and
  runs `make check`. The user pushes main.

## Decisions go to the docs

Every item `requirement.md` lists under **Decided**, and every answer the user gives at a gate
or in triage, is also written to `docs/spec.md` (product rule) or as a D-row in
`docs/architecture.md` (technical decision, next free number), in the same stage commit, so it
reaches main with the code. `.work/<slug>/` keeps the working copy; the docs are the record.

## Feature folder

Everything a stage needs is on disk, so any stage can run in a fresh session weeks later.

```
.work/<slug>/
  requirement-raw.md    the user's request, verbatim — never edited after creation
  requirement.md        specified / decided / assumed, non-goals, acceptance criteria
  code-map.md           existing code the feature touches or must reuse
  manifest.md           approved files + public symbols, budgets, API maturity, contract round
  base.txt              base commit (preflight.sh)
  candidates/           best-of-N patches and their stats (stage 1 scratch)
  triage.md             contract-round failures and how each was settled (stage 1)
  bench.md              measurements and proposals (stage 3, if run)
  reports/              one markdown report per stage run
  metrics.json          diff size history — the ratchet input for future budgets
```

Commit `.work/<slug>/` with the feature.

## Commands

| What | Command |
|---|---|
| tests while working | `make test` (`T=Name` filters by class name) — shared on the JVM, androidApp on Robolectric |
| a feature is done | `make check` — tests, iOS compile, debug build |
| coverage (stage 4) | `make coverage` → `build/reports/kover/reportPipeline.xml`, both modules merged |
| dead code (stage 4) | `scripts/pipeline/dead.sh --base <base>` — kotlinc warnings + detekt dead-code rules |
| size | `scripts/pipeline/budget.sh --base <base> …` |
| mutation | `make mutation` — apart, never inside a stage loop (D48) |

There is no separate analyzer step: a green `make test` means it compiles on the JVM and
Android; `make check` adds the iOS compile, which catches `java.*` in `commonMain`.

**Every agent in its own worktree** first copies `local.properties` from the main checkout
(`cp <root>/local.properties .`) — it holds `sdk.dir`, and it is the only file an agent may read
from the main checkout. Then it pins its base: `git reset --hard <sha given in the prompt>` and
checks `git log -1` — agent worktrees start at the session's first commit, not at HEAD.

## Acceptance criteria

Stable IDs, one behaviour each, checkable without reading the implementation:

```
AC-1: Given a verba with cota 3.000 and an open aluguel of 2.000, when the month opens,
      then its margem is 1.000 and the passos split 1.000.
```

Tests cite the ID at the start of their name: `` fun `AC-1 AC-3 the reserve leaves the margem`() ``
(no comma, `$`, dot, colon or slash in backtick names — they break the iOS compile). Stage 4
uses that citation to map added lines to a requirement.

**Failure ACs are mandatory.** For every I/O boundary the feature touches (below),
`requirement.md` states what happens when it fails, or records an explicit assumption. A failure
AC becomes a test that makes the real boundary fail, which covers the handler, which keeps it
alive through stage 4.

## Testing

`.agents/rules/testing.md` governs every test this pipeline writes, with architecture §10 and
D23 on top:

- **Integration through the public API**: the `Db`, `capture`, `parseBrl`, `importCobudget`, and
  the screens through their testTag objects (D35). Unit tests (seeing `internal`) only for the
  three pure contracts: `budgetMonth`, extraction, SHA-256.
- `shared` tests live in `commonTest` and run as `jvmTest`: real SQLite in a temp file per test,
  no `java.*`, no mocks. `androidApp` tests run on Robolectric with `AndroidSQLiteDriver` (D28).
- Suite conventions: one temp sqlite file per test with the feature's prefix, drop `-wal`/`-shm`
  after; `TimeZone.of("America/Sao_Paulo")`; fixed dates (the book's 9 out 2026); `runTest(timeout
  = …)`; random tests with a fixed seed, bounded iterations, explicit timeouts, and seed/step/input
  in the failure message.
- Expected values are worked out by hand from the spec, with the section and the arithmetic in a
  comment. Never reimplement the algorithm in the test. Never assert what the spec and KDoc do not
  say — note it as an ambiguity instead.
- One test may cover several ACs; never one test per AC as a policy, never one file per source
  file.

### Two oracles in stage 1

Kotlin compiles a module's tests together, so red tests must compile before any of them run. The
order is therefore:

1. **Contract commit** — the manifest's public symbols: types, signatures, KDoc (the KDoc is the
   contract), testTag objects for new screen elements; new bodies `TODO()`; existing bodies whose
   rule changes keep today's behaviour; `make test` green. `feat: <slug> contract`.
2. **AC tests** — from `requirement.md` only, against the contract; compile, run red.
   `test: <slug> acceptance criteria`.
3. In parallel: the **implementation race** (N candidates, each in its own worktree, the AC suite
   as oracle) and the **contract round** (below).
4. Apply the smallest passing candidate.
5. **Triage** the contract round against the applied implementation.

### Contract round (blind contract tests)

Four agents — five when the feature has UI — `isolation: worktree`, one file and one area each,
named with the feature's prefix (`<Prefix>EdgeTest`, `…FuzzTest`, `…ConcurrencyTest`,
`…InvariantsTest`, and `…UiTest` in androidApp):

| Area | Covers |
|---|---|
| Borda (edge) | empty, zero, limits, month start 29–31, short months, the virada, last day |
| Fuzz | random valid inputs through the public API: never throws, invariants hold |
| Concorrência e ciclo de vida | reads racing writes, reopen, backup/import mid-use, screen leaving |
| Invariantes | random action sequences checking spec §5 after every step |
| UI | the screens through their tags: what is shown, TalkBack text, navigation |

Each agent pins the contract commit (`git merge --ff-only <sha>` or `git reset --hard <sha>`,
checks `git log -1`, checks the new bodies are `TODO()`); never reads from the main checkout
except `local.properties`; reads spec, glossary, the relevant architecture rows and sections,
AGENTS.md, `.agents/rules/testing.md` and the contract files; never reads `androidApp/` source
beyond the tag objects and public screen signatures. Its test must compile on every target
(`make test T=<File>` compiles both modules; `make ios` for `commonTest`) but is not expected to
pass. It commits only its file (`test: <prefix> <area> tests`) and reports: the contract commit
read and any implementation seen; file, commit, compiled on all targets; coverage bullets;
ambiguities and the reading chosen.

**Triage** brings each file in with `git show <commit>:<file>`, runs it, and settles every
failure by the spec:

- code bug → fix the code;
- the test misread the rule → fix the test and say why in a comment and in `triage.md`;
- a rule the spec does not cover → **stop for the user**: one question at a time, recommendation
  first; the answer goes to the docs, then the code or test follows it.

Never loosen a test to make it pass. Before triage, check each agent's report: which commit it
read, and whether it saw implementation (a test that saw implementation is not blind; say so).

## API maturity

`manifest.md` declares one of:

- `API: settled` — the feature extends an API whose shape has proven itself. Full AC coverage is
  written before implementation, the race runs, the contract round runs. The normal case.
- `API: exploratory` — a new module or concept still in flux. Smoke ACs only, one candidate (no
  race), and the contract round is deferred until the API settles (record what would settle it in
  `requirement.md`, then run `/dev-verify` and the round).

## Budgets

Declared in `manifest.md` before any code is written, enforced by `scripts/pipeline/budget.sh`.
Defaults (2026-11-04, the user; recent blocks added 66–1077 production lines and no file):

| Metric | Default |
|---|---|
| added production lines (Kotlin, no tests) | 600 |
| new files | 1 |
| new public symbols (`const val` tags not counted) | 10 |

Raise a budget in the manifest when the feature genuinely needs it — before implementing, as a
decision, never afterwards as an excuse. After ~5 features, replace the defaults with the median
from `metrics.json`. A budget is checked by a script and fails hard; never ask an agent whether a
diff "feels large".

## Implementer prohibitions

Unless `manifest.md` explicitly approves it, an implementer may not:

- create a new file, class, object, interface, sealed hierarchy, typealias or extension function;
- add a ViewModel, repository, use case, mapper or any layer between a screen and the `Db`;
- add an interface or abstract class with a single implementation;
- add a parameter or default argument no caller passes differently, a config flag, a toggle;
- add a wrapper that only forwards to something else;
- add `try`/`catch`, `runCatching`, `?:` fallbacks or `require` around code that does not cross
  an I/O boundary — inside the `Db`, values are trusted (parse, don't validate: the boundary
  parses once);
- add a public function to the `Db` when an existing read gives the data (D12, D45);
- generalise beyond the ACs ("so it also supports…");
- reimplement anything `code-map.md` lists as reusable;
- edit a test to make it pass.

Write it as if you had to delete it tomorrow.

## I/O boundaries

Defensive code guarding these is **exempt from deletion** in stage 4 — it may be flagged, never
removed. Defensive code around pure logic is fair game.

- SQLite: `androidx.sqlite` (`SQLiteDriver`, `SQLiteConnection`, `SQLiteStatement`) — opening,
  migrating, every statement the `Db` runs
- files: `kotlinx.io` (`SystemFileSystem`, `Path`), `java.io.File`; backup `exportTo` /
  `importFrom` (`VACUUM INTO`, file replace, `-wal`/`-shm`)
- the cobudget `.db` read by `importCobudget` (foreign schema)
- notifications: `NotificationListenerService`, `StatusBarNotification` → `RawNotice`
- notification text: `extract`, `parseBrl`, `capture` — external data from bank apps
- Android: `Intent`, `Uri`, `ContentResolver`, the SAF pickers, `ActivityResult`
- app widgets / Glance and their `updateAll`

Extend this list as the app grows; it is the exemption rule.

## Deletion classes (stage 4)

| Class | Examples | Handling |
|---|---|---|
| mechanical | `dead.sh`: kotlinc warning or detekt unused import / private member / parameter / variable, unreachable code; an unreferenced file | auto-apply |
| duplication | reimplements a symbol cited by `file:line` | auto-apply |
| defensive | `try`/`catch`, `runCatching`, `?:` fallback, `?.let` guard, `require`/`check`, validation early-return | propose only — the user approves |
| boundary | defensive code at an I/O boundary above | never deleted, only reported |

Stage 4 only considers lines this feature added. Deletions land in their own commit so one
`git revert` undoes the pass. On an `API: exploratory` feature only the mechanical class runs.

**Coverage says "reached", not "asserted".** An uncovered line is strong evidence nothing needs
it; a covered line is weak evidence something does. Composables count as covered only through
the Robolectric UI tests — a screen line no UI test reaches is uncovered, not exempt.

## Reporting rules for agents

- Run the scripts; report their exit code and output verbatim. Never restate a script's result in
  your own words, and never overrule it.
- A duplication finding must cite the pre-existing symbol by `file:line`, or it is dropped.
- A question or finding that does not change the diff is noise. Do not report it.
- "Nothing to report" is a valid and expected result. Do not manufacture findings.
