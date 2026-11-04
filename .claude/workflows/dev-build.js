export const meta = {
  name: 'dev-build',
  description: 'Stage 1 (Margem): contract commit, AC tests, N implementations racing beside the blind contract round, smallest green one wins, minimality review, then contract-round triage',
  whenToUse: 'Invoked by the /dev-build or /dev-run skill after the manifest has been approved. Not meant to be run directly.',
  phases: [
    { title: 'Contract', detail: 'public symbols, KDoc, TODO() bodies, make test green' },
    { title: 'Tests', detail: 'AC tests from requirement.md, compiling and red' },
    { title: 'Race', detail: 'N candidates implement while the contract agents write blind tests' },
    { title: 'Apply', detail: 'apply the smallest green candidate' },
    { title: 'Review', detail: 'minimality review, bounded revise loop' },
    { title: 'Record', detail: 'report, metrics, implementation commit' },
    { title: 'Triage', detail: 'run the contract tests, settle each failure by the spec' },
  ],
}

const {
  slug, base, root, candidates = 2, maxAdded, maxNewFiles, maxNewSymbols,
  apiMaturity = 'settled',          // 'settled' | 'exploratory' — manifest.md
  prefix,                           // contract-round file prefix, e.g. 'Reserva'
  areas = ['edge', 'fuzz', 'concurrency', 'invariants'],
  docs = '',                        // spec sections and architecture rows the round reads
} = args

// An exploratory API gets a deliberately thin suite: too weak an oracle to referee a size race,
// and too unsettled for a blind contract round. One candidate, no round (RULES.md).
const exploratory = apiMaturity === 'exploratory'
const raceSize = exploratory ? 1 : candidates
const W = `.work/${slug}`
const PRELUDE = `Read .claude/pipeline/RULES.md first and obey it, then AGENTS.md and
.agents/rules/testing.md. Feature slug: ${slug}. Its folder is ${W}/ — requirement.md,
code-map.md and manifest.md are the contract. Base commit: ${base}. API: ${apiMaturity}.
Commit messages: terse Conventional Commits, lowercase, no Co-Authored-By trailer, no AI attribution.`

// Every agent that gets its own worktree starts from the session's first commit, not from HEAD.
const WORKTREE = (sha) => `You are in your own git worktree. Before anything else:
1. \`cp ${root}/local.properties .\` — the only file you may ever read from ${root}.
2. \`git reset --hard ${sha}\` and check \`git log -1\` shows ${sha}.`

const BUDGET_FLAGS = [
  maxAdded ? `--max-added ${maxAdded}` : '',
  maxNewFiles ? `--max-new-files ${maxNewFiles}` : '',
  maxNewSymbols ? `--max-new-symbols ${maxNewSymbols}` : '',
].filter(Boolean).join(' ')

const AREAS = {
  edge: { file: 'EdgeTest', module: 'shared/src/commonTest', what: 'Borda: empty, zero, limits, month start 29–31, short months, the virada, the last day of the month, values at the cent' },
  fuzz: { file: 'FuzzTest', module: 'shared/src/commonTest', what: 'Fuzz: random valid inputs through the public API — never throws, the spec invariants hold; fixed seed, bounded iterations, explicit timeouts, seed/step/input in every failure message' },
  concurrency: { file: 'ConcurrencyTest', module: 'shared/src/commonTest', what: 'Concorrência e ciclo de vida: reads racing writes on the Db (D29), close and reopen, backup and import mid-use; withTimeout on Dispatchers.Default where a deadlock is possible' },
  invariants: { file: 'InvariantsTest', module: 'shared/src/commonTest', what: 'Invariantes: random action sequences through the Db, checking spec §5 and the feature rules after every step' },
  ui: { file: 'UiTest', module: 'androidApp/src/test', what: 'UI: the screens through their testTag objects (D35) on Robolectric with AndroidSQLiteDriver (D28) — what is shown, TalkBack text, navigation, typing through ComposingKeyboard' },
}

// ---------------------------------------------------------------- schemas

const SHA = {
  type: 'object',
  required: ['ok', 'commit'],
  properties: { ok: { type: 'boolean' }, commit: { type: 'string' }, detail: { type: 'string' } },
}

const TESTS = {
  type: 'object',
  required: ['committed', 'commit', 'testFiles', 'acCoverage', 'allFailing'],
  properties: {
    committed: { type: 'boolean' },
    commit: { type: 'string' },
    testFiles: { type: 'array', items: { type: 'string' } },
    acCoverage: {
      type: 'array',
      description: 'every AC id in requirement.md, with the tests that cover it',
      items: {
        type: 'object', required: ['ac', 'tests'],
        properties: { ac: { type: 'string' }, tests: { type: 'array', items: { type: 'string' } }, faultInjected: { type: 'boolean' } },
      },
    },
    allFailing: { type: 'boolean', description: 'true if every new test is red against the contract' },
    notes: { type: 'string' },
  },
}

const CANDIDATE = {
  type: 'object',
  required: ['index', 'testsPass', 'patchPath', 'added', 'newFiles', 'newPublicSymbols', 'withinBudget'],
  properties: {
    index: { type: 'integer' },
    testsPass: { type: 'boolean', description: 'make test green, every suite' },
    iosCompiles: { type: 'boolean' },
    patchPath: { type: 'string' },
    added: { type: 'integer' },
    newFiles: { type: 'integer' },
    newPublicSymbols: { type: 'integer' },
    withinBudget: { type: 'boolean' },
    approach: { type: 'string', description: 'one sentence on the shape chosen' },
    notes: { type: 'string' },
  },
}

const ROUND = {
  type: 'object',
  required: ['area', 'file', 'commit', 'compiled'],
  properties: {
    area: { type: 'string' },
    file: { type: 'string' },
    commit: { type: 'string' },
    contractRead: { type: 'string', description: 'the contract commit actually checked out' },
    sawImplementation: { type: 'boolean', description: 'true if any non-TODO body of the new contract was visible' },
    compiled: { type: 'boolean', description: 'compiled on every target the file belongs to' },
    coverage: { type: 'array', items: { type: 'string' } },
    ambiguities: { type: 'array', items: { type: 'string' }, description: 'each with the reading chosen' },
  },
}

const VERDICT = {
  type: 'object',
  required: ['accept', 'violations'],
  properties: {
    accept: { type: 'boolean' },
    violations: {
      type: 'array',
      items: {
        type: 'object', required: ['kind', 'file', 'line', 'evidence', 'fix'],
        properties: {
          kind: { type: 'string', enum: ['duplication', 'over-abstraction', 'unused-surface', 'out-of-manifest', 'over-budget'] },
          file: { type: 'string' }, line: { type: 'integer' },
          evidence: { type: 'string', description: 'for duplication, the pre-existing symbol as file:line' },
          fix: { type: 'string' },
        },
      },
    },
    summary: { type: 'string' },
  },
}

const DONE = {
  type: 'object',
  required: ['ok'],
  properties: { ok: { type: 'boolean' }, detail: { type: 'string' }, commit: { type: 'string' } },
}

const TRIAGE = {
  type: 'object',
  required: ['roundCommit', 'failures', 'specGaps'],
  properties: {
    roundCommit: { type: 'string', description: 'the commit adding the contract-round files' },
    fixCommit: { type: 'string', description: 'the triage fix commit; empty when specGaps is not empty' },
    failures: {
      type: 'array',
      items: {
        type: 'object', required: ['test', 'kind', 'resolution'],
        properties: {
          test: { type: 'string' },
          kind: { type: 'string', enum: ['code-bug', 'misread-test', 'spec-gap'] },
          resolution: { type: 'string', description: 'what was changed and why; for a spec-gap, nothing yet' },
        },
      },
    },
    specGaps: {
      type: 'array',
      items: {
        type: 'object', required: ['test', 'question', 'options', 'recommended'],
        properties: {
          test: { type: 'string' },
          specSection: { type: 'string' },
          question: { type: 'string' },
          options: { type: 'array', items: { type: 'string' } },
          recommended: { type: 'string' },
        },
      },
    },
    blindnessIssues: { type: 'array', items: { type: 'string' }, description: 'agents that read the wrong commit or saw implementation' },
    green: { type: 'boolean' },
  },
}

// ------------------------------------------------------------- 1. contract

phase('Contract')
const contract = await agent(`${PRELUDE}

Write the contract commit for this feature, in the main checkout (${root}), on the current branch.

Write exactly the symbols manifest.md lists under "Contract": types, fields, function signatures,
and their KDoc — the KDoc is the contract the blind tests are written against, so it states every
rule a caller can observe, with the spec section. New screen elements get their testTag const in
the screen's tags object (D35), with KDoc saying what the element shows. New bodies are \`TODO()\`.
An existing body whose rule changes keeps today's behaviour for now, so the suites that pin today's
rule stay green; new screen pieces are stubs, not wired yet.

\`make test\` must be green and \`make ios\` must compile. Commit only this, message
"feat: ${slug} contract", then \`git push -u origin HEAD\`. Report the commit sha.`,
  { phase: 'Contract', schema: SHA })

if (!contract?.ok) return { ok: false, stage: 'Contract', detail: contract?.detail ?? 'contract commit failed' }
log(`contract ${contract.commit}`)

// ---------------------------------------------------------------- 2. tests

phase('Tests')
const tests = await agent(`${PRELUDE}

Author this feature's acceptance tests, in the main checkout (${root}), against the contract just
committed (${contract.commit}). No implementation exists; the new bodies are TODO().

Read only: ${W}/requirement.md, ${W}/manifest.md, ${W}/code-map.md, the contract diff
(\`git show ${contract.commit}\`), the spec and glossary, and existing tests for conventions. Do
not touch production code. The tests come from the acceptance criteria alone — that is what makes
them a valid oracle for the race.

- Integration through the public API (RULES.md, Testing): the Db, capture, parseBrl, and screens
  through their tags. Unit tests only for budgetMonth, extraction, SHA-256 (D23).
- shared tests go in shared/src/commonTest/kotlin/com/kaiodelphino/margem/${prefix ?? '<Prefix>'}Test.kt,
  screen tests in androidApp/src/test/kotlin/com/kaiodelphino/margem/${prefix ?? '<Prefix>'}ScreenTest.kt
  (the contract round owns the *EdgeTest, *FuzzTest, *ConcurrencyTest, *InvariantsTest, *UiTest names).
- Every AC id is cited at the start of a test name: \`fun \\\`AC-1 AC-3 the reserve leaves the margem\\\`()\`.
  One test covering several ACs beats several covering one each.
- Every AC-F* makes the real boundary fail (a closed Db, an unreadable file, a malformed notice) —
  nothing internal is stubbed.
- Expected values by hand from the spec, with the section and the arithmetic in a comment.
- No new helpers or fixture layers beyond what the existing suites already use.
${exploratory ? `
This feature is API: exploratory. Write SMOKE TESTS ONLY: the two or three that would catch the
feature being fundamentally broken, at the coarsest level that still means something.` : ''}

Then \`make test T=${prefix ?? '<Prefix>'}\`: it must compile, and the new tests must be red.
Commit only the test files, "test: ${slug} acceptance criteria", and \`git push\`. Report the sha
and the AC-to-test map.`, { phase: 'Tests', schema: TESTS })

if (!tests?.committed) return { ok: false, stage: 'Tests', detail: tests?.notes ?? 'test authoring failed', tests }
log(`AC tests ${tests.commit} for ${tests.acCoverage.length} acceptance criteria`)

// ------------------------------------------------- 3. race + contract round

phase('Race')
const raceJobs = Array.from({ length: raceSize }, (_, i) => () => agent(`${PRELUDE}

${raceSize > 1
  ? `You are implementation candidate ${i + 1} of ${raceSize}. Another candidate solves the same task
independently. Only the SMALLEST green candidate is kept — every line you did not have to add loses
you the race. A candidate whose suite is not green is discarded outright.`
  : `You are the sole implementation. The suite is deliberately thin because this API is exploratory;
the shape you choose is the deliverable and a human will judge it. Keep it as small as you can defend.`}

${WORKTREE(tests.commit)}

The contract and the AC tests are committed at ${tests.commit}.

1. Implement only what manifest.md approves, reusing everything code-map.md lists. Obey the
   implementer prohibitions in RULES.md literally. Rewrite an existing test only when it pins the
   rule this feature changes, and say which and why in your notes — never loosen one.
2. Iterate with \`make test\` (\`T=Name\` while focused, the whole suite at the end) until green.
   Then \`make ios\` must compile. Do not edit an AC test to make it pass.
3. \`scripts/pipeline/budget.sh --base ${base} ${BUDGET_FLAGS}\`
4. \`mkdir -p ${root}/${W}/candidates && git add -A -N && git diff ${tests.commit} > ${root}/${W}/candidates/${i + 1}.patch\` — note the
   absolute path: the patch lands in the main checkout, not your worktree.

Report the numbers from budget.sh exactly as printed.`,
  { label: `candidate ${i + 1}`, phase: 'Race', isolation: 'worktree', schema: CANDIDATE }))

const roundJobs = exploratory ? [] : areas.map(key => {
  const a = AREAS[key]
  const file = `${a.module}/kotlin/com/kaiodelphino/margem/${prefix}${a.file}.kt`
  return () => agent(`Blind contract tests for the Margem feature "${slug}". You write ONE file:
${file}. Area: ${a.what}.

${WORKTREE(contract.commit)}
3. Check the bodies the contract added are TODO() (\`git show ${contract.commit}\`). If any new body
   is implemented, say so in sawImplementation — never use it.

Read: .claude/pipeline/RULES.md (Testing, Contract round), AGENTS.md, .agents/rules/testing.md,
docs/spec.md and docs/glossary.md, docs/architecture.md (${docs || 'the rows and sections the contract cites'}),
${W}/requirement.md, and the contract files — the KDoc is the contract. Read existing tests of the
same area for the suite conventions. Do not read androidApp/ sources beyond the tags objects and
the public screen signatures, and never read anything under ${root} except local.properties.

Rules: public API only; unit tests only for the pure contracts (budgetMonth, extraction, SHA-256);
real collaborators, no mocks; commonTest without java.*; backtick names without comma, $, dot,
colon or slash; one temp sqlite file per test prefixed "${prefix}", -wal/-shm dropped after;
TimeZone.of("America/Sao_Paulo"); fixed dates (9 out 2026); runTest(timeout = …). Expected values
by hand from the spec with the section and the arithmetic in a comment — never reimplement the
algorithm. Do not assert what the spec and KDoc do not say: note it as an ambiguity, with the
reading you chose.

The file must compile (\`make test T=${prefix}${a.file}\` compiles the module; for commonTest also
\`make ios\`); it is expected to fail — the implementation does not exist. Commit only your file,
"test: ${prefix.toLowerCase()} ${key} tests". Report: the contract commit read and any
implementation seen; file, commit sha, compiled on all targets; coverage bullets; ambiguities.`,
    { label: `round:${key}`, phase: 'Race', isolation: 'worktree', schema: ROUND })
})

const raced = await parallel([...raceJobs, ...roundJobs])
const results = raced.slice(0, raceSize).filter(Boolean)
const round = raced.slice(raceSize).filter(Boolean)

const passing = results.filter(r => r.testsPass && r.patchPath)
log(`candidates: ${results.map(r => `${r.added}${r.testsPass ? '' : ' (red)'}`).join(' / ')} added lines`)
log(`contract round: ${round.map(r => `${r.area}${r.compiled ? '' : ' (no compile)'}`).join(', ') || 'skipped'}`)

if (!passing.length) {
  return { ok: false, stage: 'Race', detail: 'no candidate produced a green suite', candidates: results, round }
}

const winner = passing.sort((a, b) =>
  a.added - b.added || a.newFiles - b.newFiles || a.newPublicSymbols - b.newPublicSymbols)[0]
log(`winner: candidate ${winner.index} — ${winner.added} added, ${winner.newFiles} new files, ${winner.newPublicSymbols} new public symbols`)
if (!winner.withinBudget) log('note: the winner is OVER budget — the review must justify or shrink it')

// -------------------------------------------------------------- 4. apply

phase('Apply')
const applied = await agent(`${PRELUDE}

In the main checkout (${root}), HEAD is ${tests.commit}. Apply the winner:
\`git apply ${W}/candidates/${winner.index}.patch\`. Then \`make test\` must be green. Do not commit
and do not change anything else. If the patch does not apply cleanly, report ok:false with the
conflict — do not improvise a fix.`, { phase: 'Apply', effort: 'low', schema: DONE })

if (!applied?.ok) return { ok: false, stage: 'Apply', detail: applied?.detail ?? 'patch did not apply', winner, round }

// ------------------------------------------------------------- 5. review

phase('Review')
let verdict = null
for (let r = 1; r <= 3; r++) {
  verdict = await agent(`${PRELUDE}

Review the working-tree diff against ${base} for MINIMALITY only. You did not write it and have no
stake in it. Correctness is the suite's job; do not re-litigate it.

Read requirement.md, code-map.md and manifest.md, then the diff. For each violation:
- duplication — reimplements something that exists. Cite the existing symbol as file:line, found by
  searching; without the citation the finding is dropped.
- over-abstraction — an interface with one implementation, a wrapper that only forwards, a layer
  between a screen and the Db, a generalisation no AC needs.
- unused-surface — public where private would do, a parameter every caller passes the same, a branch
  no AC reaches.
- out-of-manifest — a file or public symbol manifest.md does not list.
- over-budget — the output of \`scripts/pipeline/budget.sh --base ${base} ${BUDGET_FLAGS}\`,
  verbatim, if it exits non-zero.

Judge the diff as a whole: would a competent engineer who had read the ACs have written less?
Accept when the answer is no. Round ${r} of 3.`,
    { label: `minimality r${r}`, phase: 'Review', schema: VERDICT })

  if (!verdict || verdict.accept || !verdict.violations?.length) break
  log(`round ${r}: ${verdict.violations.length} violation(s) — revising`)

  const revised = await agent(`${PRELUDE}

A minimality reviewer rejected the working-tree diff. Apply exactly these fixes and nothing else:

${JSON.stringify(verdict.violations, null, 2)}

\`make test\` stays green. If a fix would break an AC test, do not apply it — say which and why.`,
    { label: `revise r${r}`, phase: 'Review', schema: DONE })
  if (!revised?.ok) { log(`revision ${r} not applied cleanly: ${revised?.detail}`); break }
}

// ------------------------------------------------------------- 6. record

phase('Record')
const recorded = await agent(`${PRELUDE}

Finish the implementation, in the main checkout:
1. \`make check\` must pass (tests, iOS compile, debug build). If it does not, report ok:false with
   the failure and stop — do not commit.
2. Run \`scripts/pipeline/budget.sh --base ${base} ${BUDGET_FLAGS}\` and append
   {"stage":"build", ...its json} to ${W}/metrics.json (a top-level array; create it if absent).
3. Write ${W}/reports/stage1.md: contract commit ${contract.commit}; the AC-to-test map; the candidate
   spread (${results.map(r => `${r.added}${r.testsPass ? '' : ' red'}`).join(', ')} added lines) and
   the winner; the review verdict and anything flagged but not fixed; the contract round as reported
   (${JSON.stringify(round.map(r => ({ area: r.area, file: r.file, commit: r.commit, compiled: r.compiled })))}).
4. Write every decision this stage took that requirement.md did not already settle to the docs
   (spec or an architecture D-row), as RULES.md "Decisions go to the docs" says.
5. Commit everything: "feat: ${slug}", then \`git push\`. Report the sha.`,
  { phase: 'Record', schema: DONE })

if (!recorded?.ok) return { ok: false, stage: 'Record', detail: recorded?.detail, winner, round }

// ------------------------------------------------------------- 7. triage

let triage = null
if (round.length) {
  phase('Triage')
  triage = await agent(`${PRELUDE}

Triage the blind contract round against the implementation now on HEAD (${recorded.commit}).

The agents' reports:
${JSON.stringify(round, null, 2)}

1. Check each report first: an agent that read another commit than ${contract.commit}, or saw
   implementation, wrote a test that is not blind — list it under blindnessIssues and still triage it.
2. Bring each file in with \`git show <commit>:<file> > <file>\`. Commit them as they are, alone:
   "test: ${prefix.toLowerCase()} contract round", and push. Report that sha as roundCommit.
3. \`make test T=${prefix}\` and settle every failure by the spec:
   - code-bug → fix the code, smallest correct fix;
   - misread-test → the test read the rule wrong: fix the test and say why in a comment on it;
   - spec-gap → a rule the spec and the KDoc do not cover. Do NOT decide it. Describe it as a
     question for the user, the real options, and your recommendation. Leave that test failing.
   Never loosen a test to make it pass. Write every failure and its resolution to ${W}/triage.md.
4. If there are no spec-gaps: \`make check\` green, commit the fixes and triage.md as
   "fix: ${slug} contract triage", push, report fixCommit. If there are spec-gaps: commit nothing
   more and leave the working tree as it is — the user settles them next.`,
    { phase: 'Triage', schema: TRIAGE })
  log(`triage: ${triage?.failures?.length ?? 0} failure(s), ${triage?.specGaps?.length ?? 0} spec gap(s)`)
}

return {
  ok: true,
  slug,
  contractCommit: contract.commit,
  testsCommit: tests.commit,
  implementationCommit: recorded.commit,
  winner,
  spread: results.map(r => ({ index: r.index, added: r.added, testsPass: r.testsPass })),
  acCount: tests.acCoverage.length,
  verdict,
  round: round.map(r => ({ area: r.area, file: r.file, commit: r.commit, sawImplementation: r.sawImplementation, ambiguities: r.ambiguities })),
  triage,
  specGaps: triage?.specGaps ?? [],
}
