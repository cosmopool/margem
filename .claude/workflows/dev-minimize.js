export const meta = {
  name: 'dev-minimize',
  description: 'Stage 4 (Margem): measure what the feature added (Kover coverage, kotlinc/detekt dead code, budget), classify every unjustified construct, auto-remove the mechanical ones and queue defensive ones for the user',
  whenToUse: 'Invoked by the /dev-minimize skill. Returns an approval queue the skill presents to the user.',
  phases: [
    { title: 'Measure', detail: 'coverage of the diff, dead code, budget' },
    { title: 'Classify', detail: 'sort deletion candidates by class' },
    { title: 'Auto', detail: 'remove the mechanical and duplicated ones' },
  ],
}

const { slug, base, root, apiMaturity = 'settled' } = args
const exploratory = apiMaturity === 'exploratory'
const W = `.work/${slug}`
const PRELUDE = `Read .claude/pipeline/RULES.md first and obey it, in particular the I/O boundary list
and the deletion classes. Feature slug: ${slug}. Requirement: ${W}/requirement.md. Manifest:
${W}/manifest.md. Base commit: ${base}. Main checkout: ${root}. Only lines this feature ADDED are in
scope — never pre-existing code. Commit messages: terse Conventional Commits, no Co-Authored-By.`

const MEASURED = {
  type: 'object',
  required: ['uncoveredLines', 'deadFindings', 'budget', 'suiteGreen'],
  properties: {
    suiteGreen: { type: 'boolean' },
    uncoveredLines: { type: 'array', items: { type: 'string' }, description: 'file:line, verbatim from uncovered.sh' },
    uninstrumentedFiles: { type: 'array', items: { type: 'string' } },
    deadFindings: { type: 'array', items: { type: 'string' }, description: 'verbatim lines from dead.sh' },
    budget: { type: 'string', description: 'the JSON printed by budget.sh, verbatim' },
  },
}

const CANDIDATES = {
  type: 'object',
  required: ['candidates', 'examined'],
  properties: {
    candidates: {
      type: 'array',
      items: {
        type: 'object', required: ['file', 'line', 'construct', 'class', 'reason'],
        properties: {
          file: { type: 'string' }, line: { type: 'integer' },
          construct: { type: 'string', description: 'the code, one line if possible' },
          class: { type: 'string', enum: ['mechanical', 'duplication', 'defensive', 'boundary'] },
          reason: { type: 'string', description: 'why nothing requires this' },
          evidence: { type: 'string', description: 'duplication: the original as file:line. mechanical: the dead.sh line or "unreferenced file".' },
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
    ok: { type: 'boolean' }, detail: { type: 'string' }, commit: { type: 'string' },
    removed: { type: 'array', items: { type: 'string' } },
    keptBack: { type: 'array', items: { type: 'string' }, description: 'candidates that could not go without turning the suite red' },
  },
}

phase('Measure')
const measured = await agent(`${PRELUDE}

Measure, do not judge. Run each, in the main checkout, and report the output verbatim:

1. \`make coverage\` — every test of both modules under Kover. If any test fails, stop and report
   suiteGreen:false: stage 4 never runs against a red suite.
2. \`scripts/pipeline/uncovered.sh --base ${base}\` (stdout: uncovered lines; stderr: files with
   no coverage record).
3. \`scripts/pipeline/dead.sh --base ${base}\`
4. \`scripts/pipeline/budget.sh --base ${base}\`

Do not interpret, soften or explain away any of it.`,
  { phase: 'Measure', effort: 'low', schema: MEASURED })

if (measured?.suiteGreen === false) return { ok: false, stage: 'Measure', detail: 'suite is red — fix it before minimising' }
log(`${measured?.uncoveredLines?.length ?? 0} uncovered added line(s), ${measured?.deadFindings?.length ?? 0} dead-code finding(s)`)

phase('Classify')
const classified = await agent(`${PRELUDE}

Every line below was added by this feature and executed by no test. The suite comes from the ACs
and the blind contract round, never from the implementation, so an uncovered line is one no
requirement asked for — a deletion candidate, not yet a deletion.

Read the signal one way only: uncovered is strong evidence nothing needs a line; covered is weak
evidence something does (integration and UI tests run much code without asserting on it). Never
argue a construct is justified because a test happened to reach it.
${exploratory ? `
This feature is API: exploratory and its suite is deliberately thin. Classify as usual; only the
mechanical class will be acted on — the rest are observations for when the API settles.` : ''}

Uncovered: ${JSON.stringify(measured?.uncoveredLines ?? [])}
No coverage record at all: ${JSON.stringify(measured?.uninstrumentedFiles ?? [])}
Dead code (dead.sh): ${JSON.stringify(measured?.deadFindings ?? [])}

Classify each into exactly one class from RULES.md:
- mechanical — dead.sh reports it, or a whole added file nothing references. Cite the evidence.
- duplication — it reimplements something that exists. Cite the original as file:line, verified
  by searching. No citation, no finding.
- defensive — try/catch, runCatching, ?: fallback, ?.let guard, require/check, validation
  early-return — NOT at an I/O boundary.
- boundary — defensive code guarding an I/O boundary from RULES.md. Reported, never deleted. If
  one is uncovered, name the missing AC-F.

Then look beyond the uncovered lines for whole constructs the ACs do not need: a public symbol
nothing calls outside tests, a parameter always passed the same value, a branch no AC reaches, a
type with one use that could be the value itself.

Find at least five candidates. If the diff does not contain five, say exactly what you examined
in \`examined\` — that is the useful result.`,
  { phase: 'Classify', schema: CANDIDATES })

const all = classified?.candidates ?? []
const auto = all.filter(c => exploratory ? c.class === 'mechanical' : (c.class === 'mechanical' || c.class === 'duplication'))
const queue = exploratory ? [] : all.filter(c => c.class === 'defensive')
const boundary = all.filter(c => c.class === 'boundary')
const deferred = exploratory ? all.filter(c => c.class !== 'mechanical') : []
log(`${auto.length} auto-removable, ${queue.length} need approval, ${boundary.length} boundary-protected${exploratory ? `, ${deferred.length} deferred` : ''}`)

phase('Auto')
let applied = null
if (auto.length) {
  applied = await agent(`${PRELUDE}

Remove exactly these, and nothing else:

${JSON.stringify(auto, null, 2)}

After each removal run \`make test\` (T= filters while focused; the whole suite before committing).
If a removal turns the suite red or stops compilation, restore it and list it under keptBack with
the reason — a finding, not a failure. If a removal makes something else dead, note it for the next
run instead of widening the list.

Then \`make check\`. Commit ONLY these deletions, alone: "refactor: ${slug} drop surface no requirement asks for",
and \`git push\`. One revert must undo the whole pass.`,
    { phase: 'Auto', schema: DONE })
} else {
  log('nothing auto-removable')
}

return {
  ok: applied ? applied.ok !== false : true,
  slug,
  autoRemoved: applied?.removed ?? [],
  keptBack: applied?.keptBack ?? [],
  autoCommit: applied?.commit,
  approvalQueue: queue,
  boundaryProtected: boundary,
  deferredUntilApiSettles: deferred,
  examined: classified?.examined,
  budget: measured?.budget,
}
