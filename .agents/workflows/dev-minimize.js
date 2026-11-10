export const meta = {
  name: 'dev-minimize',
  description: 'Stage 4: measure.sh measures and pre-sorts what the feature added, an agent classifies only the judgement calls, mechanical and duplicated code is removed, defensive code is queued for approval',
  whenToUse: 'Invoked by /feature or /dev-minimize. Returns an approval queue the caller presents to the user.',
  phases: [
    { title: 'Measure', detail: 'measure.sh: coverage, analyzer, patterns, budget' },
    { title: 'Classify', detail: 'judgement calls only' },
    { title: 'Auto', detail: 'remove the mechanical and duplicated ones' },
    { title: 'Record', detail: 'checkpoint' },
  ],
}

// The caller reads the single project configuration before invoking a workflow.
const config = args.config
if (!config || config.version !== 1) throw new Error('Pass .agents/pipeline/config.json as args.config')

const { slug, base, root, apiMaturity = 'settled' } = args

// A thin suite makes coverage a weak necessity signal, so an exploratory feature only
// gets the mechanical class removed. See RULES.md.
const exploratory = apiMaturity === 'exploratory'
const W = `${root}/.work/${slug}`
const S = `${root}/scripts/pipeline`
const PRELUDE = `Read ${root}/.agents/pipeline/config.json and ${root}/.agents/pipeline/RULES.md first and obey it, in particular the
I/O boundary list and the deletion classes. Feature slug: ${slug}.
Requirement and acceptance criteria: ${W}/requirement.md. Manifest: ${W}/manifest.md.
Base ref: ${base}. Only lines this feature ADDED are in scope — never pre-existing code.
Pipeline scripts live in ${S}/.`

const MEASURED = {
  type: 'object',
  required: ['exitCode', 'json'],
  properties: {
    exitCode: { type: 'integer' },
    json: { type: 'string', description: 'everything measure.sh printed on stdout, verbatim' },
  },
}

const CANDIDATES = {
  type: 'object',
  required: ['candidates', 'examined'],
  properties: {
    candidates: {
      type: 'array',
      items: {
        type: 'object',
        required: ['file', 'line', 'construct', 'class', 'reason'],
        properties: {
          file: { type: 'string' },
          line: { type: 'integer' },
          construct: { type: 'string', description: 'the code, one line if possible' },
          class: { type: 'string', enum: ['duplication', 'defensive', 'boundary'] },
          reason: { type: 'string', description: 'why nothing requires this' },
          evidence: { type: 'string', description: 'duplication: the original as file:line' },
          riskIfWrong: { type: 'string', description: 'what breaks if this was actually needed' },
          missingAc: { type: 'string', description: 'boundary: the AC-F that should cover it' },
        },
      },
    },
    examined: { type: 'string', description: 'what was checked when fewer than five candidates were found' },
  },
}

const DONE = {
  type: 'object',
  required: ['ok'],
  properties: {
    ok: { type: 'boolean' },
    detail: { type: 'string' },
    removed: { type: 'array', items: { type: 'string' } },
    keptBack: { type: 'array', items: { type: 'string' }, description: 'candidates that could not be removed without breaking the suite' },
  },
}

const CHECKPOINT = {
  type: 'object',
  required: ['ok', 'head'],
  properties: { ok: { type: 'boolean' }, head: { type: 'string' }, detail: { type: 'string' } },
}

phase('Measure')
const run = await agent(`From ${root}, run \`${S}/measure.sh --slug ${slug}\` and report its exit
code and its stdout verbatim. Do not interpret, fix or re-run anything.`,
  { phase: 'Measure', effort: 'low', schema: MEASURED })

let measured = null
try { measured = JSON.parse(run?.json ?? '') } catch (e) { measured = null }
if (!measured || run.exitCode !== 0 || measured.suiteGreen === false) {
  return { ok: false, stage: 'minimize', detail: `measure.sh failed or the suite is red: ${run?.json ?? 'no output'}` }
}
log(`${measured.uncovered.length} uncovered added line(s), ${measured.mechanical.length} mechanical, ${measured.defensive.length} defensive by pattern`)

phase('Classify')
const classified = await agent(`${PRELUDE}

measure.sh has already done the counting and the obvious sorting. The mechanical class
(analyzer-confirmed unused or dead code on added lines) is settled and not yours to
judge. Your job is only what a pattern cannot decide.

Uncovered added lines — no acceptance test executes them, so no requirement asked for
them: ${JSON.stringify(measured.uncovered)}
Files no test reached at all: ${JSON.stringify(measured.uninstrumented)}
Defensive constructs found by pattern, with a hint when their file imports an I/O
boundary package: ${JSON.stringify(measured.defensive)}

Read the signal in one direction only: uncovered is strong evidence nothing needs a line,
covered is weak evidence anything does. Never argue a construct is justified merely
because a test happened to reach it.
${exploratory ? `
This feature is API: exploratory and its suite is deliberately thin. Classify as usual,
but only the mechanical class will be acted on — the rest are observations for when the
API settles.` : ''}

Classify each candidate into exactly one class from RULES.md:

- duplication — it reimplements something that already exists. Cite the original as
  file:line, verified by searching. No citation, no finding.
- defensive — a pattern hit that does NOT guard an I/O boundary, or an uncovered branch
  that exists only "in case".
- boundary — defensive code that really guards one of the I/O boundaries in RULES.md
  (nearBoundary is only a hint: check the actual call). Never deleted. If it is
  uncovered, name the AC-F that is missing in missingAc.

Then look beyond these lists for whole constructs the ACs do not require — a public
symbol nothing calls, a parameter always passed the same value, a branch no AC reaches,
an abstraction with one implementation — and classify those too.

Find at least five candidates in total, counting the ${measured.mechanical.length} mechanical
ones. If the diff genuinely does not contain five, say exactly what you examined in
\`examined\` — that statement is the useful result.`,
  { phase: 'Classify', schema: CANDIDATES })

const judged = classified?.candidates ?? []
const mechanical = measured.mechanical.map(m => {
  const [file, line] = m.at.split(':')
  return { file, line: Number(line), construct: m.message, class: 'mechanical', reason: m.code, evidence: m.at }
})
const auto = exploratory ? mechanical : [...mechanical, ...judged.filter(c => c.class === 'duplication')]
const queue = exploratory ? [] : judged.filter(c => c.class === 'defensive')
const boundary = judged.filter(c => c.class === 'boundary')
const deferred = exploratory ? judged.filter(c => c.class !== 'boundary') : []
log(`${auto.length} auto-removable, ${queue.length} need approval, ${boundary.length} boundary-protected${exploratory ? `, ${deferred.length} deferred until the API settles` : ''}`)

phase('Auto')
let applied = null
if (auto.length) {
  applied = await agent(`${PRELUDE}

Remove exactly these, and nothing else:

${JSON.stringify(auto, null, 2)}

After each removal run \`${S}/ac-map.sh --slug ${slug} --expect green\` and
\`${config.commands.analyze}\`. If a removal turns them red, restore it and list it under
keptBack with the reason — that is a finding, not a failure. Removing one thing often
makes another dead; note it for the next run rather than expanding the list yourself.
Do not commit; the checkpoint step does.`,
    { phase: 'Auto', schema: DONE })
} else {
  log('nothing auto-removable')
}

phase('Record')
const saved = await agent(`${PRELUDE}

Close the stage 4 automatic removals.

1. Read ${root}/.agents/skills/caveman-commit/SKILL.md and write messages by its rules,
   with the overrides in the "Commits and checkpoints" section of RULES.md.
2. If \`git status --porcelain\` shows changes outside .work/, run
   \`${S}/commit-progress.sh -m "refactor(${slug}): remove surface no requirement asks for"\`.
   These deletions stay alone in that commit, so one revert undoes the pass.
3. ${queue.length
    ? `Write this JSON to ${W}/pending.json, then run \`${S}/record.sh --slug ${slug} --stage minimize
   --step auto --metrics --pending ${W}/pending.json\`:\n${JSON.stringify({ kind: 'deletion-queue', queue })}`
    : `Run \`${S}/record.sh --slug ${slug} --stage minimize --step auto --metrics --clear-pending\`.`}
4. Write a short message naming the step and run \`${S}/commit-checkpoint.sh -m "<message>"\`.

If a script exits non-zero, stop and report ok:false with its output. Report HEAD.`,
  { label: 'checkpoint:minimize', phase: 'Record', effort: 'low', schema: CHECKPOINT })
if (!saved?.ok) return { ok: false, stage: 'minimize', detail: saved?.detail }

return {
  ok: true,
  slug,
  autoRemoved: applied?.removed ?? [],
  keptBack: applied?.keptBack ?? [],
  approvalQueue: queue,
  boundaryProtected: boundary,
  deferredUntilApiSettles: deferred,
  examined: classified?.examined,
  budget: measured.budget,
  head: saved.head,
}
