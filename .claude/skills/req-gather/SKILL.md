---
name: req-gather
description: Stage 0 of the Margem feature pipeline. Turns the user's feature request into a reviewed .work/<slug>/requirement.md — acceptance criteria, failure ACs, non-goals and an explicit assumptions list — by checking the request against the spec, the architecture decisions and the code, asking one question at a time. Use when starting any new feature, or when asked to gather, clarify or grill a requirement.
---

# Stage 0 — gather the requirement

Read `.claude/pipeline/RULES.md` first.

This stage is a conversation. It runs in the main loop and never delegates the questioning to a
subagent, because a subagent cannot ask the user anything. In Margem the user is the product
owner: what they say is the requirement, and every open point is theirs to decide.

## 1. Capture the input, verbatim

Ask for the feature slug (kebab-case, in the project's words: `margem-honesta`, `previsto`) if it
was not given. Write the request to `.work/<slug>/requirement-raw.md` exactly as received. It is
never edited again: later stages must tell what the user said from what was inferred.

## 2. Read before asking anything

Read-only, no edits. Delegate to the Explore agent when the surface is wide. Read:

- `docs/spec.md` — the product rules the feature extends or contradicts (cite §);
- `docs/glossary.md` — the words; a new concept gets a glossary term, not a synonym;
- `docs/architecture.md` — decided rows (D-numbers) that constrain the shape, §9 files, §10 tests;
- the latest `docs/*-plan.md` — how a block is laid out here;
- the code: `shared/src/commonMain` (`Db.kt`, `Types.kt`, `Month.kt`, `Extract.kt`, `Capture.kt`)
  and `androidApp/src/main` (screens in `ui/`, `widgets/Components.kt`), and their tests.

You are looking for: existing reads, types and composables to reuse; existing rules the request
contradicts or duplicates; the I/O boundaries it crosses (RULES.md); the testTag objects (D35) of
the screens it touches.

Write `.work/<slug>/code-map.md`: what exists, where (`file:line`), what the feature should reuse
rather than rebuild, and which spec sections and D-rows apply. Stage 1 starts from this file.

## 3. Interrogate — one question at a time

Every question must pass both tests, or it is not asked:

1. **Traceable** — it comes from a specific ambiguity in `requirement-raw.md`, or a specific
   conflict with the spec, a D-row or the code. Cite which.
2. **Consequential** — different answers produce different diffs. If both lead to the same code,
   it is not a question: if one answer is obvious, take it and record it under Decided; if not,
   it is still the user's call — ask.

Ask with `AskUserQuestion`, **one question per call**, highest consequence first. The options are
the real alternatives found in the spec and the code; the first is your recommendation, marked
"(Recommended)". Wait for the answer before the next question — a later question often changes
once an earlier one is answered. No round limit: stop when the stopping rule holds.

> The request says "show the aluguel as already spent". `budgetMonth` (Month.kt) splits `spent`
> into `variable` and `fixed` since D69, and the ritmo reads only `variable`. Does an open
> aluguel count in `fixed` before it is lançado, or only reserve the margem (D71)?

**Mandatory before stopping:** for every I/O boundary the feature crosses, an answer for what the
user sees when it fails. Unanswered is fine — it becomes an assumption — but unasked is not.

**Stopping rule:** stop when every remaining unknown is low-consequence or genuinely undecided.
An undecided point is recorded under Assumed, never guessed.

## 4. Write `requirement.md`

```markdown
# <feature> (<slug>)

## Summary
One paragraph, in the glossary's words.

## Specified
What the user asked for. Each item quotes the source line from requirement-raw.md.

## Decided
Resolved in this session. Each item: the decision, the alternative rejected, why, and where it
goes in the docs (`spec §x` or `architecture D<n>`).

## Assumed
Nobody decided this; a default was chosen. Each item: the assumption, its blast radius if wrong.
**The user confirms these before /dev-build.**

## Non-goals
What the feature explicitly does not do. Specific and slightly aggressive.

## Acceptance criteria
AC-1: Given … when … then …
AC-F1: Given the SQLite write fails, when the user lança, then …

## Touched boundaries
Each I/O boundary the feature crosses, mapped to the AC covering its failure.

## Contract
The public surface the ACs imply (Db functions, types, fields, testTag elements) — a first draft
of the manifest's symbols, so the contract round knows its scope.
```

Then write each Decided item to `docs/spec.md` or as a D-row in `docs/architecture.md` (next free
number), and commit on the work branch: `docs: <slug> requirement` with `.work/<slug>/` and the
doc changes. Run `scripts/pipeline/preflight.sh <slug>` first; if HEAD is main, ask the user for
the branch name (recommend the slug) and create it.

## 5. Hand back

Print the Assumed list and the non-goals, and say plainly that the assumptions need the user's
confirmation before `/dev-build`. Do not start stage 1 in the same turn.
