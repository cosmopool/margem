---
name: req-gather
description: Stage 0 of the feature pipeline. Turns a product owner's raw requirement into a reviewed requirement.md — acceptance criteria, non-goals, and an explicit assumptions list — by interrogating the paste against the actual codebase. Use when starting any new feature, or when asked to gather, clarify or grill a requirement.
---

Before starting, read `.agents/pipeline/config.json` as `config`. It is the source for
project paths, commands, review instructions, budgets and workflow limits. Pass the
entire parsed object as `config` in every Workflow call. Use its commands and test
naming conventions; do not infer a stack from examples below.

# Stage 0 — gather the requirement

Read `.agents/pipeline/RULES.md` first.

This stage is a conversation. It runs in the main loop and never delegates the
questioning to a subagent, because a subagent cannot ask the user anything.

## 1. Capture the input, verbatim

Ask for the feature slug (kebab-case) if it was not given, then write the paste to
`.work/<slug>/requirement-raw.md` exactly as received — no cleanup, no reformatting,
no summarising. Every later stage needs to distinguish what the PO said from what was
inferred, and that distinction is destroyed the moment this file is edited. It is
never edited again.

## 2. Read the code before asking anything

Explore the areas the requirement touches — read-only, no edits. Delegate this to the
Explore agent when the surface is wide. You are looking for:

- existing models, providers, widgets, and utilities the feature would reuse;
- existing behaviour the requirement contradicts or duplicates;
- the I/O boundaries the feature will cross (see RULES.md);
- naming and layering conventions in the touched packages, plus any local `AGENTS.md`.

Write `.work/<slug>/code-map.md`: what exists, where, and what the feature should reuse
rather than rebuild. Stage 1 starts from this file instead of re-deriving it.

## 3. Interrogate

Every question must satisfy both tests, or it does not get asked:

1. **Traceable** — it comes from a specific ambiguity in `requirement-raw.md`, or a
   specific conflict with something in `code-map.md`. Cite which.
2. **Consequential** — different answers produce different diffs. If both answers lead
   to the same implementation, choose one yourself and record it as a decision.

Generic product questions ("what is the success metric?", "who is the target user?")
fail the second test. Do not ask them. The value of this stage is entirely in questions
that only someone who has read this codebase could ask:

> The PO says "let users archive a list". `TaskList` already has `isDeleted`, used by
> the trash view. Is archive the same state renamed, a second orthogonal flag, or a
> status enum that replaces both?

Ask with `AskUserQuestion`, at most 4 at a time, highest-consequence first. Options
should be the real alternatives you found in the code, not open prose — they are far
faster to answer. At most config.workflow.question_rounds rounds, then stop.

**Mandatory coverage before you stop:** for every I/O boundary the feature crosses, you
must have an answer for what the user sees when it fails. Unanswered is fine —
it becomes an assumption — but unasked is not. These answers become failure ACs, which
become fault-injection tests, which are the only thing that keeps error handling alive
through stage 4.

**Stopping rule:** stop when every remaining unknown is either low-consequence or a
decision the PO has not actually made yet. Do not resolve the second kind by guessing
in a chat window — record it as an assumption and let the user take it back to the PO.

## 4. Write `requirement.md`

```markdown
# <feature> (<slug>)

## Summary
One paragraph.

## Specified
What the PO asked for. Each item quotes the source line from requirement-raw.md.

## Decided
Resolved during this session. Each item states the decision, the alternative rejected,
and why.

## Assumed
Nobody decided this; a default was chosen. Each item states the assumption, its blast
radius if wrong, and who can confirm it. **This section goes back to the PO.**

## Non-goals
What this feature explicitly does not do. Be specific and slightly aggressive here —
a non-goal costs one line now and prevents code that stage 4 would otherwise have to
argue its way into deleting.

## Acceptance criteria
AC-1: Given … when … then …
AC-2: …
AC-F1: Given the Firestore write fails, when the user archives, then …

## Touched boundaries
The I/O boundaries this feature crosses, each mapped to the AC covering its failure.
```

## 5. Hand back

Print the **Assumed** list and the non-goals in the chat, and say plainly that the
assumptions are what needs confirming before `/dev-build` runs. Do not start stage 1
in the same turn.
