export const meta = {
  name: 'dev-verify',
  description: 'Stage 2 (Margem): hunt bugs through independent lenses, adversarially verify each finding, close acceptance-criteria gaps, fix what survives',
  whenToUse: 'Invoked by the /dev-verify skill after stage 1 has committed an implementation.',
  phases: [
    { title: 'Hunt', detail: 'independent lenses over the feature diff' },
    { title: 'Verify', detail: 'each finding faces a refuter' },
    { title: 'Fix', detail: 'apply confirmed fixes, keep the suite green' },
    { title: 'Record', detail: 'report and commit' },
  ],
}

const { slug, base, root } = args
const W = `.work/${slug}`
const PRELUDE = `Read .claude/pipeline/RULES.md first and obey it, then AGENTS.md. Feature slug: ${slug}.
Requirement and acceptance criteria: ${W}/requirement.md. Base commit: ${base}. Main checkout: ${root}.
Scope: the diff between ${base} and HEAD. Pre-existing code is out of scope unless the diff changes
its behaviour, depends on it wrongly, or exposes a bug in it.
Commit messages: terse Conventional Commits, lowercase, no Co-Authored-By trailer.`

const EVIDENCE = `Evidence rule (.claude/skills/margem-review/SKILL.md, "Regra de evidência — não especular"): report only a defect
you can walk as a concrete flow — initial state, action, the changed line, what it executes, the
wrong state, the impact — through code that exists. Read callers, the spec section, the D-rows and
the existing tests before concluding. "Could", "might", "if someone later…" are not defects.`

// Deliberately blind to each other — diversity finds more than redundancy.
const LENSES = [
  {
    key: 'db',
    prompt: `Db and SQL. A query that misses a row or counts one twice (joins on installments,
refunds, tags), a write outside the single writer or outside its transaction, a missing
\`version\`/\`onWrite\` bump (or an extra one), a read that sees half a write (D29), a schema change
without a migration from the v1 schema (D67), deletes that leave orphan rows (budget_months,
week_steps, installments, transaction_tags, refunds), text compared raw instead of by its key (D61).`,
  },
  {
    key: 'month',
    prompt: `Month arithmetic and money. Budget month boundaries with start day 29–31 and short
months (§3), week split and passos rounding with the rest in the last week (§4, D11), ritmo and
projection (§5), parcelas dates (D7), estornos, cents overflow or rounding, a LocalDate compared in
the wrong time zone, "today" read twice in one computation.`,
  },
  {
    key: 'lifecycle',
    prompt: `Coroutines and Compose. A Db call on the main thread, a LaunchedEffect keyed wrong (stale
data after the date or the month changes, or reloading on every recomposition), state that a
screen should keep across the back stack (rememberSaveable, D18) or should drop, a collect that
never ends, a write racing a read the screen shows, the virada (day change) not refreshing,
widgets not updated after a write.`,
  },
  {
    key: 'boundary',
    prompt: `I/O boundaries and external data. What the code does when SQLite fails, the backup file is
missing, foreign or truncated, the cobudget .db has an unexpected schema, a notification has no
text, odd Unicode (D28 normalisation) or an amount parseBrl does not expect, the SAF returns no
Uri. Compare the handling against every AC-F* in requirement.md.`,
  },
  {
    key: 'edges',
    prompt: `Requirement edges. Empty, single and very large inputs; the action applied twice or to
something already in the target state; a deleted verba, marcador or previsto still referenced; a
past or future month; anything an AC states that the code satisfies only by accident.`,
  },
]

const FINDINGS = {
  type: 'object',
  required: ['findings'],
  properties: {
    findings: {
      type: 'array', maxItems: 8,
      items: {
        type: 'object', required: ['title', 'file', 'line', 'failureScenario', 'fix'],
        properties: {
          title: { type: 'string' }, file: { type: 'string' }, line: { type: 'integer' },
          failureScenario: { type: 'string', description: 'concrete state and actions, then the wrong outcome' },
          fix: { type: 'string' },
          ac: { type: 'string', description: 'the AC id or spec section this breaks, if any' },
        },
      },
    },
  },
}

const REFUTE = {
  type: 'object',
  required: ['refuted', 'reason'],
  properties: { refuted: { type: 'boolean' }, reason: { type: 'string' }, fixCostLines: { type: 'integer' } },
}

const DONE = {
  type: 'object',
  required: ['ok'],
  properties: { ok: { type: 'boolean' }, detail: { type: 'string' }, commit: { type: 'string' } },
}

const GAPS = {
  type: 'object',
  required: ['uncovered', 'testsAdded'],
  properties: {
    uncovered: { type: 'array', items: { type: 'string' }, description: 'AC ids that had no test' },
    testsAdded: { type: 'array', items: { type: 'string' } },
    allGreen: { type: 'boolean' },
  },
}

phase('Hunt')
const [hunted, gaps] = await Promise.all([
  pipeline(
    LENSES,
    lens => agent(`${PRELUDE}

Hunt for real defects through this lens and no other:

${lens.prompt}

${EVIDENCE}

Style, naming and "consider extracting" are not defects. An empty list is a good result. The
smallest correct fix only — never one that adds a layer.`,
      { label: `hunt:${lens.key}`, phase: 'Hunt', schema: FINDINGS }),

    (result, lens) => parallel((result?.findings ?? []).map(f => () =>
      agent(`${PRELUDE}

Try to REFUTE this reported defect. You are not looking for reasons it might be true.

  ${f.title}
  ${f.file}:${f.line}
  claimed failure: ${f.failureScenario}

Read the code, the spec section and the tests. It is refuted if the scenario cannot occur, if
something upstream prevents it, if a test already covers it, if the spec or a D-row says the
behaviour is intended, or if the claim misreads the code. Default to refuted:true when unsure — a
fix costs code, and speculative fixes are what this pipeline exists to prevent.`,
        { label: `refute:${lens.key}:${f.file.split('/').pop()}:${f.line}`, phase: 'Verify', schema: REFUTE })
        .then(v => ({ ...f, lens: lens.key, refuted: v?.refuted !== false, why: v?.reason }))
    ))
  ),

  agent(`${PRELUDE}

Close acceptance-criteria gaps in the test suite, in the main checkout. RULES.md "Testing" and
.agents/rules/testing.md are the authority.

1. Map AC id -> tests citing it (test names start with the ids).
2. For every AC with no test, and every AC-F* whose test does not make the real boundary fail,
   write the missing coverage. Prefer extending an existing integration test (cite both ids) over a
   new one. Public API and tags only; expected values by hand from the spec.
3. \`make test\`. New tests that fail are findings: leave them failing and report them.

You may read public signatures and KDoc, not implementation bodies — these tests come from the ACs.
No new helpers or fixtures. Do not modify existing tests beyond adding AC citations. Do not touch
production code.`,
    { label: 'ac-gaps', phase: 'Hunt', schema: GAPS }),
])

const all = hunted.flat().filter(Boolean)
const confirmed = all.filter(f => !f.refuted)
log(`${all.length} raised, ${confirmed.length} survived refutation; ${gaps?.uncovered?.length ?? 0} AC gap(s) closed`)

phase('Fix')
let fixed = null
if (confirmed.length || gaps?.allGreen === false) {
  fixed = await agent(`${PRELUDE}

Fix exactly these confirmed defects, each first reproduced by an integration test through the
public API (.agents/rules/testing.md rule 5) that fails before the fix, and make any failing test
pass:

${JSON.stringify(confirmed.map(f => ({ title: f.title, file: f.file, line: f.line, scenario: f.failureScenario, fix: f.fix })), null, 2)}

The smallest correct fix for each. No refactoring, no new abstractions, no defensive code away from
an I/O boundary. \`make test\` green when done. If a fix turns out wrong, say so and skip it.`,
    { phase: 'Fix', schema: DONE })
} else {
  log('nothing to fix')
}

phase('Record')
const recorded = await agent(`${PRELUDE}

\`make check\` must pass. Write ${W}/reports/stage2.md: every finding raised, its lens, whether it
survived refutation and why, the AC gaps closed, the fixes applied. Write any decision a fix took
to the docs (RULES.md). Append {"stage":"verify", ...json} from
\`scripts/pipeline/budget.sh --base ${base}\` to ${W}/metrics.json. Commit "fix: ${slug} verification"
and \`git push\`. If nothing changed but the report, commit it as "docs: ${slug} verification report".`,
  { phase: 'Record', effort: 'low', schema: DONE })

return {
  ok: (fixed ? fixed.ok !== false : true) && recorded?.ok !== false,
  slug,
  raised: all.length,
  perLens: Object.fromEntries(LENSES.map(l => [l.key, {
    raised: all.filter(f => f.lens === l.key).length,
    confirmed: confirmed.filter(f => f.lens === l.key).length,
  }])),
  confirmed: confirmed.map(f => ({ lens: f.lens, title: f.title, file: f.file, line: f.line })),
  acGapsClosed: gaps?.uncovered ?? [],
  commit: recorded?.commit,
}
