export const meta = {
  name: 'dev-build',
  description: 'Stage 1: author AC tests, race N implementations in worktrees, keep the smallest green one, review it for minimality, checkpoint each step',
  whenToUse: 'Invoked by /feature or /dev-build after the file manifest has been approved. Not meant to be run directly.',
  phases: [
    { title: 'Tests', detail: 'author failing tests from the acceptance criteria' },
    { title: 'Implement', detail: 'N isolated candidates race to make them pass' },
    { title: 'Apply', detail: 'apply.sh on the smallest passing candidate' },
    { title: 'Review', detail: 'minimality review, bounded revise loop' },
    { title: 'Record', detail: 'report and checkpoint' },
  ],
}

// The caller reads the single project configuration before invoking a workflow.
const config = args.config
if (!config || config.version !== 1) throw new Error('Pass .agents/pipeline/config.json as args.config')

const {
  slug, base, root, candidates = config.workflow.candidates,
  budget = {},                // { added, newFiles, newSymbols } from lint-feature.sh
  apiMaturity = 'settled',    // 'settled' | 'exploratory', from lint-feature.sh
  from = 'tests',             // resume point: 'tests' | 'implement' | 'review'
} = args

// An exploratory API gets a deliberately thin suite, which is too weak an oracle to
// select between candidates on size. Race nothing; build one and let the human judge
// the shape. See the API maturity rule in RULES.md.
const exploratory = apiMaturity === 'exploratory'
const raceSize = exploratory ? 1 : candidates

const W = `${root}/.work/${slug}`
const S = `${root}/scripts/pipeline`
const PRELUDE = `Read ${root}/.agents/pipeline/config.json and ${root}/.agents/pipeline/RULES.md first and obey it. Feature slug: ${slug}.
Its folder is ${W}/ — requirement.md, code-map.md and manifest.md are the contract.
The feature's base ref is ${base}. This feature's API is ${apiMaturity}.
Pipeline scripts live in ${S}/.`

const BUDGET_FLAGS = [
  budget.added ? `--max-added ${budget.added}` : '',
  budget.newFiles ? `--max-new-files ${budget.newFiles}` : '',
  budget.newSymbols ? `--max-new-symbols ${budget.newSymbols}` : '',
].filter(Boolean).join(' ')

// The checks every implementation must pass, in a candidate worktree or the main checkout.
const CHECKS = `\`${S}/ac-map.sh --slug ${slug} --expect green\`,
\`${config.commands.analyze}\`, \`${S}/hygiene.sh --slug ${slug}\` and
\`${S}/candidate-check.sh --slug ${slug}\``

// ---------------------------------------------------------------- schemas

const TESTS = {
  type: 'object',
  required: ['ok', 'testFiles'],
  properties: {
    ok: { type: 'boolean', description: 'ac-map.sh --expect red exited 0' },
    testFiles: { type: 'array', items: { type: 'string' } },
    acMap: { type: 'string', description: 'the JSON printed by ac-map.sh, verbatim' },
    notes: { type: 'string' },
  },
}

const CANDIDATE = {
  type: 'object',
  required: ['index', 'testsPass', 'patchPath', 'added', 'newFiles', 'newPublicSymbols', 'withinBudget'],
  properties: {
    index: { type: 'integer' },
    testsPass: { type: 'boolean', description: 'every check passed on the final attempt' },
    patchPath: { type: 'string' },
    added: { type: 'integer' },
    newFiles: { type: 'integer' },
    newPublicSymbols: { type: 'integer' },
    withinBudget: { type: 'boolean' },
    approach: { type: 'string', description: 'one sentence on the shape chosen' },
    notes: { type: 'string' },
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
        type: 'object',
        required: ['kind', 'file', 'line', 'evidence', 'fix'],
        properties: {
          kind: { type: 'string', enum: ['duplication', 'over-abstraction', 'unused-surface', 'out-of-manifest', 'over-budget'] },
          file: { type: 'string' },
          line: { type: 'integer' },
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
  properties: { ok: { type: 'boolean' }, detail: { type: 'string' } },
}

const CHECKPOINT = {
  type: 'object',
  required: ['ok', 'head'],
  properties: {
    ok: { type: 'boolean' },
    head: { type: 'string' },
    progressMessage: { type: 'string', description: 'empty when no code changed' },
    checkpointMessage: { type: 'string' },
    detail: { type: 'string' },
  },
}

// Every finished step lands as a code commit (if code changed) plus a checkpoint commit.
const checkpoint = (step, what, metrics = false) => agent(`${PRELUDE}

Close the stage 1 step "${step}": ${what}

1. Read ${root}/.agents/skills/caveman-commit/SKILL.md and write messages by its rules,
   with the overrides in the "Commits and checkpoints" section of RULES.md.
2. If \`git status --porcelain\` shows changes outside .work/, write a message for them
   (scope ${slug}) and run \`${S}/commit-progress.sh -m "<message>"\`.
3. Run \`${S}/record.sh --slug ${slug} --stage build --step ${step}${metrics ? ' --metrics' : ''}\`.
4. Write a short message naming the step that finished and run
   \`${S}/commit-checkpoint.sh -m "<message>"\` (it adds the "chore(checkpoint): " prefix).

If a script exits non-zero, stop and report ok:false with its output. Report HEAD.`,
  { label: `checkpoint:${step}`, phase: 'Record', effort: 'low', schema: CHECKPOINT })

// ---------------------------------------------------------------- 1. tests

if (from === 'tests') {
  phase('Tests')
  const tests = await agent(`${PRELUDE}

Author the test suite for this feature, before any implementation exists.

Inputs you may read: ${W}/requirement.md, ${W}/manifest.md, ${W}/code-map.md, and
existing test files for conventions (helpers, fakes, fixtures, naming).

You must NOT write or modify any production file selected by config.paths.production. The tests are derived from the
acceptance criteria alone — that is what makes them a valid oracle later. The API to
test against is the one declared in manifest.md; it does not exist yet, so the suite
will not compile or will fail. That is the expected outcome.

Rules — ${root}/.agents/pipeline/TESTING.md is the authority, read it first:
- integration tests by default: set up real state, go in through the public entry point,
  assert on the observable outcome. Never a unit test per function, never a test file
  per source file.
- every AC id is cited by at least one test, and the citation goes at the start of the
  description, e.g. test('AC-1, AC-3 archiving moves the list to Archived', ...).
  One test covering several ACs is preferred over several tests covering one each —
  the ids are citations for traceability, not a granularity instruction.
- every failure AC (AC-F*) is exercised by making the real boundary fail — a Firestore
  instance that errors, a channel that returns an error. Mock at that boundary and
  nowhere else; use real collaborators everywhere inside it.
- no test reads or asserts on implementation internals, private fields, or call counts.
  Litmus test: if the implementation were rewritten with the same behaviour, would this
  test pass unchanged? If not, raise its level.
- follow the existing test style in this repo; do not introduce a new test framework,
  helper layer, or fixture abstraction. Suspect any test that needs a large mock setup —
  it is testing implementation, not behaviour.

${exploratory ? `This feature is declared API: exploratory — its abstractions have not
proven themselves yet. Write SMOKE TESTS ONLY: the two or three that would catch the
feature being fundamentally broken, at the coarsest level that still means something.
Do not write exhaustive coverage against an API that is still in flux; it will break
constantly and discourage the exploration this flag exists to permit. Exhaustive tests
come later, when the API settles.` : ''}

Then run \`${S}/ac-map.sh --slug ${slug} --expect red\`. It must exit 0: every AC cited
and the suite red. If it reports an uncovered AC, add the missing coverage and run it
again. Do not commit; the checkpoint step does. Report its JSON verbatim.`,
    { phase: 'Tests', schema: TESTS })

  if (!tests?.ok) {
    return { ok: false, stage: 'build', step: 'tests', detail: tests?.notes ?? 'test authoring failed', tests }
  }
  const saved = await checkpoint('tests', `acceptance tests written (${tests.testFiles.length} file(s)), suite red.`)
  if (!saved?.ok) return { ok: false, stage: 'build', step: 'tests', detail: saved?.detail }
  log(`tests checkpointed at ${saved.head}`)
}

// ---------------------------------------------------------- 2. candidates

let results = []
let winner = null
if (from !== 'review') {
  phase('Implement')
  results = (await parallel(
    Array.from({ length: raceSize }, (_, i) => () => agent(`${PRELUDE}

${raceSize > 1
  ? `You are implementation candidate ${i + 1} of ${raceSize}. Other candidates are solving
the same task independently. Only the SMALLEST passing candidate is kept — every line
you add that you did not have to add loses you the race. This is not a reason to skip
requirements: a candidate whose checks do not pass is discarded outright.`
  : `You are the sole implementation. The suite is deliberately thin because this API is
exploratory, so it will not catch everything — the shape you choose is the deliverable
and a human will judge it. Keep it as small as you can defend.`}

You are in your own git worktree, started from the commit that holds the acceptance
tests. Read the contract from ${W}/, not from this worktree's .work/ copy.

1. Prepare the worktree using commands.setup from the configuration (skip if empty).
2. Implement only what manifest.md approves, reusing everything code-map.md lists as
   reusable. Follow the implementation guideline and the implementer prohibitions in
   RULES.md literally.
3. Apply config.rules.implementation and the configured project review rules.
   Run the configured generation commands when their triggers match.
4. Run ${CHECKS}.
   Iterate until all four pass on the same attempt. There is no retry cap. Never edit a
   test to make it pass — if a test looks wrong, implement to satisfy it and say so in
   your notes. If you genuinely cannot make them pass, stop and report testsPass:false.
5. Measure: \`${S}/budget.sh --base ${base} ${BUDGET_FLAGS}\`
6. Write your patch against the commit you started from, so it holds only your work:
   \`mkdir -p ${W}/candidates && git add -A -N && git diff HEAD -- . ':(exclude).work'
   ':(exclude).claude' ':(exclude).agents' ':(exclude)scripts/pipeline' > ${W}/candidates/${i + 1}.patch\`

Report the numbers from budget.sh exactly as printed. Do not adjust them.`,
      { label: `candidate ${i + 1}`, phase: 'Implement', isolation: 'worktree', schema: CANDIDATE })
    )
  )).filter(Boolean)

  const passing = results.filter(r => r.testsPass && r.patchPath)
  log(`candidates: ${results.map(r => `${r.added}${r.testsPass ? '' : ' (failed)'}`).join(' / ')} added lines`)
  if (!passing.length) {
    return { ok: false, stage: 'build', step: 'implement', detail: 'no candidate passed every check', candidates: results }
  }

  winner = passing.sort((a, b) =>
    a.added - b.added ||
    a.newFiles - b.newFiles ||
    a.newPublicSymbols - b.newPublicSymbols
  )[0]
  log(`winner: candidate ${winner.index} — ${winner.added} added lines, ${winner.newFiles} new files, ${winner.newPublicSymbols} new public symbols`)
  if (!winner.withinBudget) log('note: winner is OVER the declared budget — the review phase must justify or shrink it')

  // -------------------------------------------------------------- 3. apply

  phase('Apply')
  const applied = await agent(`${PRELUDE}

From ${root}, run \`${S}/apply.sh --slug ${slug} --patch ${W}/candidates/${winner.index}.patch\`
and report its JSON verbatim as detail, ok matching its exit code. Do not fix anything,
do not commit, and do not change anything else.`,
    { phase: 'Apply', effort: 'low', schema: DONE })

  if (!applied?.ok) {
    return { ok: false, stage: 'build', step: 'apply', detail: applied?.detail ?? 'patch did not apply', winner }
  }
  const saved = await checkpoint('apply', `candidate ${winner.index} applied (${winner.approach ?? 'no summary'}).`)
  if (!saved?.ok) return { ok: false, stage: 'build', step: 'apply', detail: saved?.detail }
}

// ------------------------------------------------------------- 4. review

phase('Review')
let verdict = null
for (let round = 1; round <= config.workflow.review_rounds; round++) {
  verdict = await agent(`${PRELUDE}

You are reviewing the working-tree diff against ${base} for MINIMALITY only. You did not
write this code and have no stake in it. Correctness is covered by the test suite; do
not re-litigate it.

Read requirement.md, code-map.md and manifest.md, then the diff. For each violation:

- duplication — it reimplements something that already exists. You MUST cite the
  existing symbol as file:line. Without that citation the finding is dropped, so
  verify by searching the codebase before you claim it.
- over-abstraction — an interface with one implementation, a wrapper that only
  forwards, a generalisation no AC requires.
- unused-surface — public where private would do, a parameter nobody passes, a branch
  no AC reaches.
- out-of-manifest — a file or symbol manifest.md does not approve.
- over-budget — report the output of
  \`${S}/budget.sh --base ${base} ${BUDGET_FLAGS}\` verbatim if it exits non-zero.

A long function is not a violation: the implementation guideline in RULES.md overrides
the repository's function-size rule inside this pipeline.

Judge the diff as a whole, not construct by construct: the question is not "is this
line defensible" but "would a competent engineer who had read the ACs have written
less". Accept when the answer is no. Round ${round} of ${config.workflow.review_rounds}.`,
    { label: `minimality review r${round}`, phase: 'Review', schema: VERDICT })

  if (!verdict || verdict.accept || !verdict.violations?.length) break
  log(`round ${round}: ${verdict.violations.length} violation(s) — revising`)

  const revised = await agent(`${PRELUDE}

A minimality reviewer rejected the current diff. Apply exactly these fixes and nothing
else — do not refactor, rename, or improve anything the reviewer did not raise:

${JSON.stringify(verdict.violations, null, 2)}

Afterwards ${CHECKS} must all pass. If a fix would break an AC test, do not apply
it — report which one and why.`,
    { label: `revise r${round}`, phase: 'Review', schema: DONE })

  if (!revised?.ok) { log(`revision ${round} could not be applied cleanly: ${revised?.detail}`); break }
}

// ------------------------------------------------------------- 5. record

phase('Record')
await agent(`${PRELUDE}

Write ${W}/reports/stage1.md: the AC-to-test map from \`${S}/ac-map.sh --slug ${slug}\`,
the candidate spread (${results.length ? `${results.map(r => r.added).join(', ')} added lines` : 'not run here: resumed after apply'}),
${winner ? `candidate ${winner.index} won (${winner.approach ?? 'no summary'})` : 'the winner as recorded in the existing report, if any'},
the review verdict ${JSON.stringify(verdict?.summary ?? '')}, and anything the reviewer
flagged but could not fix. Prose only; do not commit.`,
  { phase: 'Record', effort: 'low', schema: DONE })

const saved = await checkpoint('done', 'stage 1 finished: implementation reviewed and report written.', true)
if (!saved?.ok) return { ok: false, stage: 'build', step: 'done', detail: saved?.detail }

return {
  ok: true,
  slug,
  winner,
  spread: results.map(r => ({ index: r.index, added: r.added, testsPass: r.testsPass })),
  verdict,
  head: saved.head,
}
