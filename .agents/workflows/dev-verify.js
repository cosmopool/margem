export const meta = {
  name: 'dev-verify',
  description: 'Stage 2, one round: hunt bugs with mr-review lenses, refute and rate each, reproduce with a failing test, split feature-caused from pre-existing, fix what the table says, checkpoint',
  whenToUse: 'Invoked by /feature or /dev-verify once per round after stage 1. The caller owns the loop and the design stop.',
  phases: [
    { title: 'Hunt', detail: 'three mr-review lenses, plus AC gaps in round 1' },
    { title: 'Verify', detail: 'refute and rate each finding' },
    { title: 'Reproduce', detail: 'a failing test through a user path, then base-check.sh' },
    { title: 'Fix', detail: 'turn each reproduction green' },
    { title: 'Record', detail: 'report and checkpoint' },
  ],
}

// The caller reads the single project configuration before invoking a workflow.
const config = args.config
if (!config || config.version !== 1) throw new Error('Pass .claude/pipeline/config.json as args.config')

const {
  slug, base, root,
  round = 1,
  scope = { from: base, to: null },   // round 1: whole feature; later: the previous fix commit
  seen = [],                           // "file: title" of findings raised in earlier rounds
  budget = {},
  fixOnly = null,                      // findings to fix directly, after "fix anyway" at a design stop
} = args

const W = `${root}/.work/${slug}`
const S = `${root}/scripts/pipeline`
const DIFF = scope.to ? `git diff ${scope.from} ${scope.to}` : `git diff ${scope.from}`
const PRELUDE = `Read ${root}/.claude/pipeline/config.json and ${root}/.claude/pipeline/RULES.md first and obey it. Feature slug: ${slug}.
Requirement and acceptance criteria: ${W}/requirement.md. Base ref: ${base}.
Pipeline scripts live in ${S}/. This is stage 2, round ${round}.`

// Hunters are mr-review's bug dimensions, read from the skill rather than copied, so the
// two stay one source.
const LENSES = config.workflow.lenses
const MATRIX = config.workflow.fix_matrix

// ---------------------------------------------------------------- schemas

const FINDINGS = {
  type: 'object',
  required: ['findings'],
  properties: {
    findings: {
      type: 'array',
      maxItems: 8,
      items: {
        type: 'object',
        required: ['title', 'file', 'line', 'failureScenario', 'evidence', 'fix'],
        properties: {
          title: { type: 'string' },
          file: { type: 'string' },
          line: { type: 'integer' },
          failureScenario: { type: 'string', description: 'mr-review "fluxo": concrete inputs or sequence, then the wrong outcome' },
          evidence: { type: 'string', description: 'the code, or the AGENTS.md rule as AGENTS.md:line' },
          fix: { type: 'string' },
          ac: { type: 'string', description: 'the AC id this breaks, if any' },
        },
      },
    },
  },
}

const RATED = {
  type: 'object',
  required: ['refuted', 'reason'],
  properties: {
    refuted: { type: 'boolean' },
    reason: { type: 'string' },
    chance: { type: 'string', enum: ['common', 'occasional', 'rare', 'test-only'] },
    criticality: { type: 'string', enum: ['critical', 'major', 'minor'] },
    silent: { type: 'boolean', description: 'the user cannot notice the failure when it happens' },
    fixCostLines: { type: 'integer', description: 'rough added lines the smallest fix needs' },
  },
}

const GAPS = {
  type: 'object',
  required: ['testsAdded', 'failing'],
  properties: {
    testsAdded: { type: 'array', items: { type: 'string' } },
    failing: {
      type: 'array',
      description: 'new or extended tests that fail: each is an AC the code does not meet',
      items: {
        type: 'object',
        required: ['file', 'test', 'ac'],
        properties: { file: { type: 'string' }, test: { type: 'string' }, ac: { type: 'string' } },
      },
    },
  },
}

const REPRO = {
  type: 'object',
  required: ['outcome', 'reason'],
  properties: {
    outcome: { type: 'string', enum: ['reproduced', 'internal-only', 'untestable'] },
    testFile: { type: 'string', description: 'when reproduced: the new test file, which fails' },
    reason: { type: 'string' },
  },
}

const BASE = {
  type: 'object',
  required: ['results', 'added'],
  properties: {
    results: {
      type: 'array',
      items: {
        type: 'object',
        required: ['file', 'verdict'],
        properties: {
          file: { type: 'string' },
          onBase: { type: 'string' },
          verdict: { type: 'string', enum: ['feature', 'pre-existing'] },
        },
      },
    },
    added: { type: 'integer', description: 'added lines from budget.sh' },
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
    progressCommit: { type: 'string', description: 'the code commit made by commit-progress.sh, empty if none' },
    detail: { type: 'string' },
  },
}

// `loop` is what the caller needs to decide the next round, kept in state.json so a
// resumed run can decide it too.
const checkpoint = (step, what, pending = null, loop = null) => agent(`${PRELUDE}

Close the stage 2 step "${step}": ${what}

1. Read ${root}/.claude/skills/caveman-commit/SKILL.md and write messages by its rules,
   with the overrides in the "Commits and checkpoints" section of RULES.md.
2. If \`git status --porcelain\` shows changes outside .work/, write a message for them
   (scope ${slug}) and run \`${S}/commit-progress.sh -m "<message>"\`. Report the sha it
   prints as progressCommit when it committed something.
3. ${pending
    ? `Write this JSON to ${W}/pending.json, then run \`${S}/record.sh --slug ${slug} --stage verify
   --step ${step} --round ${round} --pending ${W}/pending.json\`:\n${JSON.stringify(pending)}`
    : `Write this JSON to ${W}/verify-loop.json, then run \`${S}/record.sh --slug ${slug} --stage verify
   --step ${step} --round ${round} --set-file verify=${W}/verify-loop.json --clear-pending --metrics\`:\n${JSON.stringify(loop)}`}
4. Write a short message naming the step that finished and run
   \`${S}/commit-checkpoint.sh -m "<message>"\` (it adds the "chore(checkpoint): " prefix).

If a script exits non-zero, stop and report ok:false with its output. Report HEAD.`,
  { label: `checkpoint:${step}`, phase: 'Record', effort: 'low', schema: CHECKPOINT })

const keyOf = f => `${f.file}: ${f.title}`

const decide = f => {
  if (f.verdict === 'pre-existing') return 'follow-up'
  const cell = MATRIX[f.chance]?.[f.criticality] ?? 'report'
  if (f.outcome === 'untestable') return f.criticality === 'critical' && cell === 'fix' ? 'fix' : 'report'
  if (cell === 'fix-small') return (f.fixCostLines ?? Infinity) <= config.workflow.small_fix_lines ? 'fix' : 'report'
  return cell
}

// ------------------------------------------------------------ hunt + rate

let findings = fixOnly ?? []
let toFix = fixOnly ?? []
let raisedKeys = []
if (!fixOnly) {
  phase('Hunt')
  const [hunted, gaps] = await Promise.all([
    pipeline(
      LENSES,
      lens => agent(`${PRELUDE}

You are one bug hunter. Read ${root}/.claude/skills/mr-review/SKILL.md: the section
"Regra de evidência — não especular" in full, the "Regras comuns a todos os agentes",
and the dimension "${lens.agent}". Hunt through that dimension and no other, including
the AGENTS.md checklist for every AGENTS.md that applies to the changed files.

Scope: \`${DIFF}\`${scope.to ? ' — only the lines the previous round\'s fix changed' : ''}.
Pre-existing code is in scope only under mr-review's five conditions.
${seen.length ? `Already raised in an earlier round, skip these: ${JSON.stringify(seen)}` : ''}

Report only defects with a concrete failure scenario. Questions to the author are not
findings here; drop them. An empty list is a good result; do not pad it. Never propose a
fix that adds a layer of abstraction — the smallest correct fix only.`,
        { label: `hunt:${lens.key}`, phase: 'Hunt', schema: FINDINGS }),

      (result, lens) => parallel((result?.findings ?? [])
        .filter(f => !seen.includes(keyOf(f)))
        .map(f => () => agent(`${PRELUDE}

Try to REFUTE this reported defect. You are not looking for reasons it might be true.

  ${f.title}
  ${f.file}:${f.line}
  claimed failure: ${f.failureScenario}
  evidence: ${f.evidence}

Read the actual code and the tests. It is refuted if the scenario cannot occur, if
something upstream already prevents it, if a test already covers it, or if the claim
misreads the code. Default to refuted:true when you are not sure — a fix costs code.

If it survives, rate it by the "Rating findings" section of RULES.md: chance to happen in
real use of the app, criticality, whether the failure is silent, and the added lines the
smallest fix needs.`,
          { label: `refute:${lens.key}:${f.file.split('/').pop()}:${f.line}`, phase: 'Verify', schema: RATED })
          .then(v => ({ ...f, lens: lens.key, ...v, refuted: v?.refuted !== false }))))
    ),

    round === 1 ? agent(`${PRELUDE}

Close acceptance-criteria gaps in the test suite. ${root}/.claude/pipeline/TESTING.md is
the authority on how they are written — read it first.

1. Run \`${S}/ac-map.sh --slug ${slug}\` for the AC -> tests map.
2. For every AC it reports uncovered, and every AC-F* whose test does not make the real
   boundary fail, write the missing coverage. Prefer extending an existing integration
   test over adding a new one — cite both ids in its description.
3. Run the test files you touched. New tests that fail are findings, not mistakes —
   leave them failing and list them; the fix step turns them green.

Integration level, public entry points, observable outcomes, real collaborators inside
the boundary. Read public API signatures, never implementation bodies: these tests come
from the acceptance criteria, not from what the code happens to do. Add no new helpers,
fixtures or mock layers, and do not touch production code.`,
      { label: 'ac-gaps', phase: 'Hunt', schema: GAPS }) : null,
  ])

  const raised = hunted.flat().filter(Boolean)
  raisedKeys = raised.map(keyOf)
  const survivors = raised.filter(f => !f.refuted)
    .map(f => ({ ...f, criticality: f.criticality === 'minor' && f.silent ? 'major' : f.criticality }))
  log(`round ${round}: ${raised.length} raised, ${survivors.length} survived refutation`)

  // ------------------------------------------------------------ reproduce

  phase('Reproduce')
  // One at a time: two test runs in the same checkout break each other.
  const reproduced = []
  for (const [n, f] of survivors.entries()) {
    const repro = await agent(`${PRELUDE}

Confirm this finding with a failing test, or show that it cannot be confirmed one:

${JSON.stringify({ title: f.title, file: f.file, line: f.line, failureScenario: f.failureScenario }, null, 2)}

Write ONE new integration test file, alone in its own file, named
\`<the closest test directory>/${config.patterns.repro_file.replace('{slug}', slug).replace('{round}', round).replace('{n}', n + 1)}\`, that triggers the
failure through a path a real user can reach: public entry points, real collaborators,
mocks only at the I/O boundaries in RULES.md. ${root}/.claude/pipeline/TESTING.md applies.
Run it and confirm it fails for the reason claimed.

- reproduced: the test fails for that reason. Report its path.
- internal-only: the failure needs internals or states the app cannot produce. Delete
  the file. This is evidence the bug cannot happen in real use.
- untestable: timing, platform channels or sync make it impossible to trigger in a test.
  Delete the file.

Do not touch production code.`, { label: `repro:${f.file.split('/').pop()}:${f.line}`, phase: 'Reproduce', schema: REPRO })
    reproduced.push({
      ...f,
      outcome: repro?.outcome ?? 'untestable',
      testFile: repro?.outcome === 'reproduced' ? repro.testFile : null,
      chance: repro?.outcome === 'internal-only' ? 'test-only' : f.chance,
      reproReason: repro?.reason,
    })
  }

  const repros = reproduced.filter(f => f.testFile).map(f => f.testFile)
  const based = await agent(`${PRELUDE}

${repros.length ? `1. From ${root}, run \`${S}/base-check.sh --slug ${slug} ${repros.join(' ')}\` and
   report each entry's file, onBase and verdict as printed.
2. Move every one of those files out of the tree, keeping its path, into
   ${W}/repros/r${round}/ (for example ${W}/repros/r${round}/<original-test-path>).
   The fix step restores the ones it fixes; the rest stay there as evidence.
3. ` : '1. '}Run \`${S}/budget.sh --base ${base}\` and report its "added" value.`,
    { label: 'base-check', phase: 'Reproduce', effort: 'low', schema: BASE })
  const verdicts = Object.fromEntries((based?.results ?? []).map(r => [r.file, r.verdict]))

  findings = [
    ...reproduced.map(f => ({ ...f, verdict: f.testFile ? (verdicts[f.testFile] ?? 'feature') : 'feature' })),
    ...(gaps?.failing ?? []).map(g => ({
      title: `${g.ac} not met: ${g.test}`, file: g.file, line: 0, lens: 'ac-gaps', ac: g.ac,
      chance: 'common', criticality: 'major', outcome: 'reproduced', verdict: 'feature',
      testFile: g.file, inTree: true, fixCostLines: 0,
    })),
  ].map(f => ({ ...f, decision: decide(f) }))
  toFix = findings.filter(f => f.decision === 'fix')
  log(`${toFix.length} to fix, ${findings.filter(f => f.decision === 'report').length} to report, ${findings.filter(f => f.decision === 'follow-up').length} pre-existing`)

  // ------------------------------------------------------------ design check

  const perFile = toFix.reduce((counts, f) => ({ ...counts, [f.file]: (counts[f.file] ?? 0) + 1 }), {})
  const clustered = Object.entries(perFile).filter(([, count]) => count >= config.workflow.cluster_size).map(([file]) => file)
  const planned = toFix.reduce((sum, f) => sum + (f.fixCostLines ?? 0), 0)
  const overBudget = budget.added && based?.added != null && based.added + planned > budget.added
  if (clustered.length || overBudget) {
    const reason = [
      clustered.length ? `${config.workflow.cluster_size} or more fixes land in ${clustered.join(', ')}` : '',
      overBudget ? `the fixes (~${planned} lines) would take the diff from ${based.added} past its budget of ${budget.added}` : '',
    ].filter(Boolean).join('; ')
    log(`design check fired: ${reason}`)
    const pending = { kind: 'design-stop', round, reason, toFix, findings }
    const saved = await checkpoint(`round-${round}-design-stop`, `design check fired: ${reason}.`, pending)
    return { ok: saved?.ok === true, designStop: pending, round, head: saved?.head, findings }
  }
}

// ------------------------------------------------------------------ fix

phase('Fix')
if (toFix.length) {
  const fixed = await agent(`${PRELUDE}

Fix exactly these findings, each starting from its failing reproduction test:

${JSON.stringify(toFix.map(f => ({ title: f.title, file: f.file, line: f.line, fix: f.fix, testFile: f.testFile, outcome: f.outcome })), null, 2)}

1. For each reproduced finding whose test is not in the tree, restore it from
   ${W}/repros/r${round}/<same path>. Untestable findings have no test: fix them by
   reading the code, with extra care.
2. Make the smallest change that turns each reproduction green. No refactoring, no new
   abstractions, no defensive code away from an I/O boundary, nothing this list does not
   name. If a fix is wrong, say so and skip it rather than forcing it.
3. \`${S}/ac-map.sh --slug ${slug} --expect green\`, \`${config.commands.analyze}\` and
   \`${S}/hygiene.sh --slug ${slug}\` must all pass when you are done.`,
    { phase: 'Fix', schema: DONE })
  if (!fixed?.ok) return { ok: false, stage: 'verify', round, detail: fixed?.detail ?? 'fix failed', findings }
} else {
  log('nothing to fix')
}

// ---------------------------------------------------------------- record

phase('Record')
await agent(`${PRELUDE}

Write ${W}/reports/stage2-r${round}.md: every finding below with its lens, whether it
survived refutation and why, its chance and criticality, how reproduction went, the
base-check verdict, and the decision (fix, report, or follow-up for pre-existing bugs).
Prose and a table; do not commit.

${JSON.stringify(findings, null, 2)}`,
  { phase: 'Record', effort: 'low', schema: DONE })

const loop = {
  round,
  newCritical: toFix.filter(f => f.criticality === 'critical').length,
  seen: [...seen, ...raisedKeys],
}
const saved = await checkpoint(`round-${round}`, `stage 2 round ${round}: ${toFix.length} fix(es) applied.`, null, loop)
if (!saved?.ok) return { ok: false, stage: 'verify', round, detail: saved?.detail, findings }

return {
  ok: true,
  ...loop,
  fixCommit: saved.progressCommit || null,
  fixed: toFix.map(f => ({ title: f.title, file: f.file, chance: f.chance, criticality: f.criticality })),
  reported: findings.filter(f => f.decision === 'report').map(f => ({ title: f.title, file: f.file, chance: f.chance, criticality: f.criticality })),
  followUps: findings.filter(f => f.decision === 'follow-up').map(f => ({ title: f.title, file: f.file, criticality: f.criticality })),
  head: saved.head,
}
