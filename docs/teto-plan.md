# Teto das cotas — plan

Status: **decided** 2026-10-16 (T1–T10 answered; recorded in spec §3, §7.1 and architecture D46, §5, §9, §10).

## 1. Goal

The optional ceiling for the sum of the month's limits (spec §3, §7.1; book 8.10, 10.1): the Verbas header with the ceiling line, the ceiling dialog ("Vale para quais meses?"), snackbars with Desfazer, and the warnings in Nova verba and Editar verba. Planning limit, never a spending limit: nothing is refused.

## 2. What already exists

- Docs: spec §3 (Teto), §7.1 (Cabeçalho com o teto), glossary (Teto), architecture D42 (one row per month in `ceiling_months`, default in `settings.ceiling`, 0 = none, "só este mês" / "deste mês em diante"), §4 lists the table, §5 the rule. Book 8.10 and 10.1 draw it.
- Code: nothing. Schema is v1 (`SCHEMA_VERSION = 1`); `ceiling_months` is only in the docs. `ensureMonths` fills `budget_months` only.
- Pieces to reuse: `PageHeader` (the header), `Meter`'s drawing and `hatch` (the line), `MoneyField`, `DialogCard`/`MargemDialog`, `SnackHost` + `Snack` with Desfazer, `OptionPicker` not needed.

## 3. Screens

- **Verbas header** (book 8.10): rubric, "Dia 9 de 31.", dek; then the ceiling line + legend, or the dotted line with "As cotas somam R$ X." and "Pôr um teto". No budgets → the line is absent. One touch target from rubric to legend, opens the dialog; TalkBack sentence of §7.1.
- **Ceiling dialog**: "Teto de outubro", field "Máximo para a soma das cotas", hint while typing, "Tirar teto"; Salvar → "Vale para quais meses?" (De outubro em diante · Só em outubro, with the sub-line); Tirar → "Tirar o teto de quais meses?". Tapping an option saves, closes, shows the snackbar with Desfazer.
- **Nova verba / Editar verba**: the sentence under the cota field and the "Passar do teto?" dialog (Voltar · Criar/Salvar mesmo assim).

## 4. Changes

### 4.1 shared
- Migration v2: `CREATE TABLE ceiling_months(month TEXT PRIMARY KEY, cents INTEGER NOT NULL)`; `SCHEMA_VERSION = 2`; `isMargemDb` accepts 1..2 (a v1 backup migrates on import).
- `ensureMonths(month)` also fills `ceiling_months` up to `month` with `settings.ceiling` (D42).
- New public API per T1/T2; `Settings` unchanged unless T1 says so.

### 4.2 androidApp
- `ui/Budgets.kt`: header with the ceiling, the ceiling dialog, warnings in the budget form; tags per T6.
- `widgets/Components.kt`: the ceiling line (`CeilingMeter`) next to `Meter`; catalog entries (light/dark) for its five states.

### 4.3 Tests
- shared (`DbTest` style, JVM): migration v1 → v2 keeps data; ensureMonths copies the default, not the previous month; "só este mês" doesn't leak; "em diante" deletes later rows; remove; preview; restore (Desfazer); backup round trip.
- UI (Robolectric): header states (sem teto, abaixo, no teto, acima, só outubro), dialog flow both options and Tirar, snackbar Desfazer, warning text and dialog in Nova and Editar verba.

## 5. Decisions (asked one at a time; recommendation first)

Answers: T1 recommended · T2 **two `setCeiling` calls** (no `restoreCeiling`) · T3 recommended · T4 recommended · T5 recommended · T6 recommended · T7 **"+ 300" as the book** (whole reais; cents only when present, "+ 300,50") · T8 recommended · T9 recommended · T10 recommended. Missing copy (exact-ceiling hint, onward-removal sub-line, "Passar do teto?" body, TalkBack without ceiling): the recommended set, in spec §7.1.

| # | Question | Recommendation |
|---|---|---|
| T1 | Shared API | `ceiling(month): Ceiling` (that month's `cents`, the default `standard`, `limits` = Σ limits of the month; a month without a row reads 0 = no ceiling), `setCeiling(cents, onward, today)` (0 = remove; negative or above the maximum refused), `previewCeiling(budget, limit, today)` (the current month's `Ceiling` with that budget's limit swapped; budget 0 = a new one). |
| T2 | Desfazer | `restoreCeiling(before: Ceiling, today)`: one write that puts back the default and the current month's row as they were before. |
| T3 | Month start day changes | Nothing special: the new current month keeps its own row, or `ensureMonths` gives it the default. (Unlike D43, nothing disappears.) |
| T4 | When Nova/Editar verba warn | Only when the change **raises** the month's sum and the new sum is above the ceiling (sentence under the field and "Passar do teto?" on save). Lowering a limit while still above, or renaming, doesn't warn. |
| T5 | Both dialogs in Editar verba | Ceiling first ("Salvar mesmo assim"), then the step-edits one (spec §4) when it also applies. |
| T6 | Tags (D35) | In `BudgetTags`: everything lives in `ui/Budgets.kt`. |
| T7 | Excess in the legend | "+ 300,00" (`moneyPlain`), like the detail's "+ 12,00" in code; the book's "+ 300" drops cents. |
| T8 | Dialog field | Starts with the month's ceiling (empty when none). Salvar disabled at 0; removing is only "Tirar teto", shown only when the month has a ceiling. |
| T9 | Snackbar for "Tirar só este mês" | "Outubro sem teto." (the spec gives only the onward one). |
| T10 | Month without ceiling while the default has one ("só outubro" removed) | Plain "sem teto" header (dotted line, "Pôr um teto"); no "só outubro" mention. |

## 6. Rodada de testes por agentes (contrato fechado)

- **When:** right after the contract commit (shared types and signatures with KDoc, bodies `TODO()`; the ceiling UI as stubs `TODO()` with its tags), in parallel with the implementation.
- **Agents:** 4 background agents, `isolation: worktree`, one file each, prefix `Teto…`: Borda (`TetoEdgeTest`, shared), Fuzz (`TetoFuzzTest`, shared, fixed seed, bounded), Concorrência e ciclo de vida (`TetoLifecycleTest`, androidApp UI), Invariantes (`TetoInvariantsTest`, shared, random action sequences). Tests compile; agents don't run them.
- **Prompt:** pin the contract commit (`git merge --ff-only {COMMIT}`, check `git log -1`, check bodies are `TODO()`), never read the main checkout (only copy `local.properties` if the build needs it); read spec §3, §7.1, glossary, architecture (D8, D12, D19, D21, D30, D35, D42 + new rows, §4, §5, §10), AGENTS.md, `.agents/rules/testing.md`, the contract files; public API only, expected values by hand from the spec; commit only its file, no trailer.
- **Triage when green:** code bug → fix code; misread rule → fix test and say why; uncovered rule → user's decision, recorded in docs. Never loosen a test.

## 7. Order of work

1. Ask T1–T10; write the answers into spec / architecture.
2. Contract commit.
3. Contract test round (§6) in parallel with 4.
4. Implementation + integration tests (§4.3).
5. Triage; `make test` green.
6. Check on the emulator (`emulator-5554`, Margem-Dev), light and dark, against book 8.10 and 10.1.
