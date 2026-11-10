export const meta = {
  name: 'dev-bench',
  description: 'Stage 3: measure the feature where measurement is possible, propose only optimisations backed by numbers, implement the ones worth their added lines',
  whenToUse: 'Invoked by /feature when perf-signals.sh finds a cost signal, or by /dev-bench. Most features never get here.',
  phases: [
    { title: 'Measure', detail: 'benchmark real hotspots, or report that there are none' },
    { title: 'Propose', detail: 'optimisations, each citing a measured number' },
    { title: 'Judge', detail: 'gain per added line; reject the rest' },
    { title: 'Implement', detail: 'apply what survives' },
    { title: 'Record', detail: 'checkpoint' },
  ],
}

// The caller reads the single project configuration before invoking a workflow.
const config = args.config
if (!config || config.version !== 1) throw new Error('Pass .agents/pipeline/config.json as args.config')

const { slug, base, root, signals = [] } = args
const W = `${root}/.work/${slug}`
const S = `${root}/scripts/pipeline`
const PRELUDE = `Read ${root}/.agents/pipeline/config.json and ${root}/.agents/pipeline/RULES.md first and obey it. Feature slug: ${slug}.
Requirement: ${W}/requirement.md. Base ref: ${base}. Scope: code this feature added.
Pipeline scripts live in ${S}/.`

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
          metric: { type: 'string', description: 'e.g. ms per call, frame build ms, document reads' },
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
      maxItems: config.workflow.max_proposals,
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
  properties: { ok: { type: 'boolean' }, detail: { type: 'string' } },
}

const CHECKPOINT = {
  type: 'object',
  required: ['ok', 'head'],
  properties: { ok: { type: 'boolean' }, head: { type: 'string' }, detail: { type: 'string' } },
}

// Every exit of this stage lands as a code commit (if code changed) plus a checkpoint.
const finish = async result => {
  phase('Record')
  const saved = await agent(`${PRELUDE}

Close stage 3: ${result.implemented?.length ? `implemented ${result.implemented.join('; ')}` : 'nothing implemented'}.

1. Read ${root}/.agents/skills/caveman-commit/SKILL.md and write messages by its rules,
   with the overrides in the "Commits and checkpoints" section of RULES.md.
2. If \`git status --porcelain\` shows changes outside .work/, run
   \`${S}/commit-progress.sh -m "perf(${slug}): <what and the measured gain>"\`.
3. Run \`${S}/record.sh --slug ${slug} --stage bench --step done --metrics\`.
4. Write a short message naming the step and run \`${S}/commit-checkpoint.sh -m "<message>"\`.

If a script exits non-zero, stop and report ok:false with its output. Report HEAD.`,
    { label: 'checkpoint:bench', phase: 'Record', effort: 'low', schema: CHECKPOINT })
  return saved?.ok ? { ...result, head: saved.head } : { ok: false, stage: 'bench', detail: saved?.detail }
}

phase('Measure')
const measured = await agent(`${PRELUDE}

perf-signals.sh flagged these added lines as worth measuring; start from them:
${JSON.stringify(signals, null, 2)}

Measure this feature's cost. Only measure things that can actually be measured here:
benchmark harnesses over pure logic, widget build counts, Firestore document reads and
writes per operation, allocation-heavy loops over realistic data sizes.

"No measurable hotspot" is the expected outcome for most features and is a complete,
correct answer — set measurable:false and explain what you checked. Do not invent a
micro-benchmark of something no user waits on, and do not benchmark the framework.

Write raw numbers and the exact command that produced each into ${W}/bench.md.`,
  { phase: 'Measure', schema: MEASUREMENT })

if (!measured || measured.measurable === false || !measured.results?.length) {
  log(`nothing measurable: ${measured?.whyNot ?? 'no results'}`)
  return finish({ ok: true, slug, measurable: false, whyNot: measured?.whyNot, implemented: [] })
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
  return finish({ ok: true, slug, measurable: true, implemented: [], measurements: measured.results })
}

phase('Judge')
const ruled = await agent(`${PRELUDE}

Judge these proposals as an engineer who has to maintain the result:

${JSON.stringify(proposals, null, 2)}

Accept one only if the measured gain is real on a mid-range device in a path a user
actually waits on, AND the gain justifies the added lines. A 3ms saving that costs 40
lines and an abstraction is a rejection. Prefer changes that remove code over changes
that add it. Rejecting all of them is a good outcome when that is the truth.`,
  { phase: 'Judge', schema: RULING })

const accepted = (ruled?.rulings ?? []).filter(r => r.accept)
log(`${accepted.length} of ${proposals.length} proposal(s) accepted`)

if (!accepted.length) {
  return finish({ ok: true, slug, measurable: true, implemented: [], rulings: ruled?.rulings, measurements: measured.results })
}

phase('Implement')
const done = await agent(`${PRELUDE}

Implement exactly these accepted optimisations and nothing else:

${JSON.stringify(accepted, null, 2)}

Re-run the benchmark afterwards and record the real after-numbers in ${W}/bench.md —
if a change does not deliver the predicted gain, revert it and say so. the configured test command
green for the feature (\`${S}/ac-map.sh --slug ${slug} --expect green\`) and
\`${config.commands.analyze}\` clean. Do not commit; the checkpoint step does.`,
  { phase: 'Implement', schema: DONE })

if (done?.ok === false) return { ok: false, stage: 'bench', detail: done.detail }
return finish({
  ok: true,
  slug,
  measurable: true,
  measurements: measured.results,
  rulings: ruled?.rulings,
  implemented: accepted.map(r => r.change),
})
