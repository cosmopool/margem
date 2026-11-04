export const meta = {
  name: 'dev-bench',
  description: 'Stage 3 (Margem): measure the feature where measurement is possible, propose only optimisations backed by numbers, implement the ones worth their added lines',
  whenToUse: 'Invoked by the /dev-bench skill. Opt-in — most features have nothing worth measuring.',
  phases: [
    { title: 'Measure', detail: 'benchmark real hotspots, or report that there are none' },
    { title: 'Propose', detail: 'optimisations, each citing a measured number' },
    { title: 'Judge', detail: 'gain per added line; reject the rest' },
    { title: 'Implement', detail: 'apply what survives' },
  ],
}

const { slug, base, root } = args
const W = `.work/${slug}`
const PRELUDE = `Read .claude/pipeline/RULES.md first and obey it, then AGENTS.md. Feature slug: ${slug}.
Requirement: ${W}/requirement.md. Base commit: ${base}. Main checkout: ${root}. Scope: code this
feature added. Commit messages: terse Conventional Commits, no Co-Authored-By trailer.`

const MEASUREMENT = {
  type: 'object',
  required: ['measurable', 'results'],
  properties: {
    measurable: { type: 'boolean' },
    whyNot: { type: 'string', description: 'required when measurable is false' },
    results: {
      type: 'array',
      items: {
        type: 'object',
        required: ['what', 'metric', 'value', 'how'],
        properties: {
          what: { type: 'string' },
          metric: { type: 'string', description: 'e.g. ms per budgetsOverview on the VolumeTest data, rows scanned, recompositions' },
          value: { type: 'string' },
          how: { type: 'string', description: 'the command or harness that produced this number' },
        },
      },
    },
  },
}

const PROPOSALS = {
  type: 'object',
  required: ['proposals'],
  properties: {
    proposals: {
      type: 'array',
      maxItems: 5,
      items: {
        type: 'object',
        required: ['change', 'measuredBefore', 'expectedAfter', 'addedLines', 'evidence'],
        properties: {
          change: { type: 'string' },
          measuredBefore: { type: 'string' },
          expectedAfter: { type: 'string' },
          addedLines: { type: 'integer' },
          evidence: { type: 'string', description: 'which measurement supports this; no number, no proposal' },
          userVisible: { type: 'boolean', description: 'would a user notice this on a real device' },
        },
      },
    },
  },
}

const RULING = {
  type: 'object',
  required: ['rulings'],
  properties: {
    rulings: {
      type: 'array',
      items: {
        type: 'object',
        required: ['change', 'accept', 'reason'],
        properties: {
          change: { type: 'string' },
          accept: { type: 'boolean' },
          reason: { type: 'string' },
        },
      },
    },
  },
}

const DONE = {
  type: 'object',
  required: ['ok'],
  properties: { ok: { type: 'boolean' }, detail: { type: 'string' }, commit: { type: 'string' } },
}

phase('Measure')
const measured = await agent(`${PRELUDE}

Measure this feature's cost. Only measure what can actually be measured here:
- the Db reads the feature added or widened, timed on the ~5 years of data VolumeTest builds (D49):
  write a temporary timing test beside it, median of 5 runs, \`make test T=<it>\`, then delete it;
- the query plan of new SQL (\`EXPLAIN QUERY PLAN\` through a temporary test) — a scan where an index
  should serve is a finding with a number (rows touched);
- budgetMonth or other pure loops over realistic sizes;
- recompositions of a screen the feature touched, counted in a Robolectric test.

"No measurable hotspot" is the expected outcome for most features and is a complete,
correct answer — set measurable:false and explain what you checked. Do not invent a
micro-benchmark of something no user waits on, and do not benchmark Compose or SQLite itself.
Nothing temporary stays in the tree.

Write raw numbers and the exact command that produced each into ${W}/bench.md.`,
  { phase: 'Measure', schema: MEASUREMENT })

if (!measured || measured.measurable === false || !measured.results?.length) {
  log(`nothing measurable: ${measured?.whyNot ?? 'no results'}`)
  return { ok: true, slug, measurable: false, whyNot: measured?.whyNot, implemented: [] }
}

phase('Propose')
const proposed = await agent(`${PRELUDE}

Here are the measurements:

${JSON.stringify(measured.results, null, 2)}

Propose optimisations. Hard rules:
- every proposal cites the measurement that justifies it; a proposal with no number
  attached is not a proposal
- state the added lines it costs, honestly
- no speculative caching, no memoisation of something called once, no indirection
  added "so it can be swapped later"
- returning zero proposals is a valid and common outcome

Write them to ${W}/bench.md under "Proposals".`, { phase: 'Propose', schema: PROPOSALS })

const proposals = proposed?.proposals ?? []
if (!proposals.length) {
  log('measured, nothing worth changing')
  return { ok: true, slug, measurable: true, implemented: [], measurements: measured.results }
}

phase('Judge')
const ruled = await agent(`${PRELUDE}

Judge these proposals as an engineer who has to maintain the result:

${JSON.stringify(proposals, null, 2)}

Accept one only if the measured gain is real on a mid-range phone in a path a user
actually waits on (opening Verbas, the Pauta, the Leitura, a widget update), AND the gain justifies the added lines. A 3ms saving that costs 40
lines and an abstraction is a rejection. Prefer changes that remove code over changes
that add it. Rejecting all of them is a good outcome when that is the truth.`,
  { phase: 'Judge', schema: RULING })

const accepted = (ruled?.rulings ?? []).filter(r => r.accept)
log(`${accepted.length} of ${proposals.length} proposal(s) accepted`)

if (!accepted.length) {
  return { ok: true, slug, measurable: true, implemented: [], rulings: ruled?.rulings, measurements: measured.results }
}

phase('Implement')
const done = await agent(`${PRELUDE}

Implement exactly these accepted optimisations and nothing else:

${JSON.stringify(accepted, null, 2)}

Re-run the measurement afterwards and record the real after-numbers in ${W}/bench.md —
if a change does not deliver the predicted gain, revert it and say so. \`make check\` green.
Commit as "perf: ${slug} <what and the measured gain>" and \`git push\`.`,
  { phase: 'Implement', schema: DONE })

return {
  ok: done?.ok !== false,
  slug,
  measurable: true,
  measurements: measured.results,
  rulings: ruled?.rulings,
  implemented: accepted.map(r => r.change),
  commit: done?.commit,
}
