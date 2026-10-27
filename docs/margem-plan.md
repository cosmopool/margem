# Margem honesta — plan

Status: **product decided** 2026-10-26 (M1–M10), mock decided 2026-10-27 (M11–M12); recorded in spec §2.13, §3, §4, §5, §7.1, §7.4, §7.5 and the glossary. Implementation decided 2026-10-27: architecture D68–D74, plan §5–§9. Nothing open.

## 1. Goal

The briefing: show the pace of spending simply and clearly, so the person stays within the verbas they set. Today a fixed bill (previsto) does not count until it is lançado: the verba looks loose before the bill and jumps to "Acima do ritmo" the day it lands (cota R$ 3.000, aluguel R$ 2.000 on day 5 → ideal ≈ R$ 484, projection ≈ R$ 12.400). And there is no total of the month. Found by the 5-persona review of 2026-10-26.

The rule in the owner's words: verba − previsto = quanto sobra para gastar; o ritmo é só sobre essa sobra; um lançamento maior que o previsto diminui o disponível e recalcula o ritmo.

## 2. Decided (product, 2026-10-26)

| # | Rule | Decision |
|---|---|---|
| M1 | Previsto in the margem | **Reserves.** Margem = cota − gasto do mês − previstos em aberto do mês. Every open occurrence whose day falls in the month counts, not only those inside the R1 window (today + 10). The régua shows the reserved part after the gasto. Gasto is still only what was lançado. |
| M2 | Previstos do mês | Occurrences with their day in the month, minus the skipped ones. A lançada one counts at the lançado value; an open one (late included) at the previsto's value. |
| M3 | Ritmo | **Only of the cota livre.** Cota livre = cota − previstos do mês (≥ R$ 0,00). Ritmo ideal comes from the passos of the cota livre; it is compared with the **gasto variável** (gasto do mês without the lançamentos of previstos). No ritmo: variável ≤ ideal. Acima do ritmo: variável > ideal and margem ≥ 0. Fora da margem: margem < 0. The payment day of a fixed bill no longer matters. |
| M4 | Different value | The whole lançamento stays out of the ritmo at its real value; a bigger value lowers the cota livre and the ritmo is recalculated. The difference never becomes gasto variável. |
| M5 | Passo semanal | The passos split the **cota livre**; edit limit = cota livre − other edited weeks. Previstos are shown apart in the verba detail, on their day. When a previsto change (create, edit, delete, skip, lançar at another value) leaves the cota livre below that month's edited passos, the month's edits **stop counting without warning** while they don't fit, and every week goes back to automatic; when the cota livre fits them again (a previsto deleted or refunded) they count again (derived, D70; 2026-10-27, was "erased"). |
| M6 | Projection | No passo atual = gasto variável × (cota livre ÷ ritmo ideal de hoje) + previstos do mês. Exactly on pace projects the cota. None when the ideal of today is R$ 0,00. |
| M7 | Pauta pendente | **Only the count**, as today ("40 recados na pauta"). Verbas don't change with pending recados. |
| M8 | Late or early previsto | Lançar the previsto counts **in the previsto's month**: when the recado's date falls in another verba month than the previsto's day, the lançamento takes the previsto's day, without "‸ emendado" (late and early alike). An open late occurrence keeps reserving in its own month. To count in the month paid, the person makes an ordinary lançamento and leaves the previsto open, skips or deletes it. |
| M9 | Total of the month | **Fused with the teto line** in the Verbas header, **without ritmo or state**. Track up to the teto (without teto: the sum of cotas; corrected 2026-10-27 by the mock); laid out as M11; a thin mark at the sum of cotas; the teto's double clay rule as today; cotas above the teto in lacre as today. Legend: "Margem R$ 1.570,00 de R$ 5.720,00" / "gasto 1.850 · previstos 2.300 · teto 6.000"; without teto the second line ends in "Pôr um teto". Tap opens the teto, as today. Final drawing by mock (book 8.10). |
| M10 | Names | **Margem** keeps its name with the M1 formula; cota − previstos do mês is the **Cota livre** (`FreeLimit`). |
| M11 | Régua (mock, 2026-10-27) | **Proposal A, fixed first** ([mockups/margem-honesta.html](mockups/margem-honesta.html)), in the verba régua and in the total: previstos do mês at the start of the track — open = dashed outline in fio forte, lançado = solid tinta 3 (gasto, but out of the ritmo, so no state color) — then the gasto variável in the state color (in the total, tinta). The ritmo mark sits at previstos do mês + ritmo ideal, always in the free part. Excess hatch as today. Total: thin tinta 2 mark at the sum of cotas when it falls before the teto; no double rule without teto. |
| M12 | Copy (mock, 2026-10-27) | Detail dek: "Você lançou R$ 40,00 de R$ 3.000,00 e tem R$ 2.000,00 em previstos. No ritmo: o ideal da cota livre até hoje é R$ 64,52." (the "e tem … em previstos" part only with an open previsto). TalkBack verba: "Casa, R$ 40,00 de R$ 3.000,00, R$ 2.000,00 em previstos, no ritmo, margem de R$ 960,00." TalkBack total: "Outubro: margem de R$ 1.570,00 de R$ 5.720,00. Gasto R$ 1.850,00, previstos R$ 2.300,00. Teto R$ 6.000,00, cotas a R$ 280,00 do teto. Tocar para mudar o teto." Detail: "Previstos de outubro" with the folhinha, "esperando · todo mês" (open, tinta 2 value) or "lançado em 4 out" (lançado value); passos end in "Soma = cota livre" over "cota R$ 3.000,00 − previstos R$ 2.000,00"; without previsto in the month, as today. |

## 3. Consequences recorded without a separate question

- Leitura "Ritmo do mês" (§7.5) plots the gasto variável against the ideal of the cota livre; its projection is M6. Rankings and calendar still count only lançamentos.
- Widget Verbas (§8.1, not built yet) shows the M1 margem and the M3 states; the risk order is unchanged.
- "A caminho" (§7.4) still shows today + 10 only; the reserve (M1) does not depend on that window.

## 4. Mock

Decided 2026-10-27 (M11, M12): [mockups/margem-honesta.html](mockups/margem-honesta.html), proposal A; B stays in the file for the record. The book (8.5, 8.6, 8.10, 8.11) is updated from it in the implementation.

## 5. Changes

### 5.1 shared
- `Month.kt` · `budgetMonth(…, fixedByDay, open)` (D68): `fixed`, `planned`, `freeLimit`, `variable`; passos, `Week.max`, `ideal`, `idealByDay` and `Week.spent` of the free part; `remaining = limit − spent − open`; states and projection per M3/M6; edits over `freeLimit` ignored (D70). `riskOrder` unchanged in shape (its OVERRUN excess becomes `−remaining`, AHEAD distance `variable − ideal`).
- `Types.kt` · `BudgetMonth` + `open`, `fixed`, `freeLimit`, `variable` (KDoc: the M1–M6 formulas, `idealByDay` last day = `freeLimit`); `TransactionRow.fixed`; `PlannedLine` and `BudgetDetail.plannedLines` (D73).
- `Db.kt`
  - Schema v1: `transactions.fixed` (D69), no new version.
  - `budgetMonth(c, …)`: two more sums over the window — fixed installments by day (`t.fixed = 1`, `NOT_REFUNDED`) and open occurrences of the budget dated in the window (`skipped = 0`, no transaction), at `planned.cents`.
  - `saveTransaction`: `fixed = 1` when the lançamento closes an occurrence; D72 date rule (date in another budget month than the occurrence's → the occurrence's date, time kept, not `corrected`); the edit keeps both.
  - `missingOccurrences`: horizon = max(today + 10, end of today's budget month) (D71); `budgetsOverview`/`budgetDetail` call `fill(today)` first; `plannedRows` unchanged (today + 10).
  - `budgetDetail`: `plannedLines` (D73).
  - `setStep`, `limitDropsEdits`, `setLimit`, `startDropsEdits`: against the cota livre of each month (D70).
  - `transactionRows`: `fixed`.

### 5.2 androidApp
- `widgets/Components.kt` · `Meter`: fixed-first track (M11) from a `BudgetMonth` — open dashed outline in fio forte, lançado fixed in tinta 3, variable in the state color, ritmo mark at `(planned + ideal) ÷ limit`, excess hatch as today; `CeilingMeter` → the total line (M9/M11: track = teto, without teto = sum of cotas and no double rule; thin tinta 2 mark at the sum of cotas; overflow hatch as today). Catalog entries (light/dark) for every state of the mock.
- `ui/Budgets.kt`: header with the total (D73: sums of the overview list + `Ceiling`), legend and TalkBack of M12; row TalkBack with "R$ X em previstos"; detail dek of M12, "Previstos de <mês>" section (`plannedLines`, tap opens the previsto sheet), "Soma = cota livre" line under the passos. Tags for the new pieces.
- `ui/Insights.kt`: "Ritmo do mês" plots the cumulative gasto variável (lines with `row.fixed` out) against `idealByDay`; the sentence uses `variable − ideal` and the M6 projection.
- `ui/Transactions.kt`: nothing new beyond what `saveTransaction` does (D72 is silent in the form).

### 5.3 Book (`docs/design_system.html`)
From [the mock](mockups/margem-honesta.html), proposal A: 8.5 régua (fixed first, the four states, TalkBack), 8.6 passos (cota livre, "Soma = cota livre"), 8.10 header (the total; the intro's "O total não tem margem" goes), 8.11 (the intro's "não conta em verba nenhuma" goes: reserves), the Verbas and verba-detail screens of section 10. Light and dark.

### 5.4 Tests (normal integration, per `.agents/rules/testing.md`)
- **`budgetMonth`** (unit, D23): cota 3.000 + aluguel 2.000 open on day 2 → free 1.000, margem 960 with 40 spent, No ritmo; lançado at 2.100 → free 900; variable over ideal → AHEAD; `spent + open > limit` → OVERRUN with `remaining < 0`; planned > limit → free 0, passos 0, no projection; exactly on pace projects the cota; edits over the free part ignored and back when it grows (D70); no previsto → identical to today's results.
- **Db**: reserve counts every occurrence of the month (a day-28 previsto seen on day 2, D71) and never a skipped one; lançar closes it at the real value (`fixed`, out of ritmo); estorno of a fixed lançamento → 0; fixed in 3× → each installment fixed in its month; delete previsto → its lançamentos stay `fixed`; a late October occurrence lançado with a 3 nov recado lands on its October day, not `corrected` (D72), and so does an edit to November; ordinary lançamento with the previsto open → variable in its month, the occurrence keeps reserving; `plannedLines` open and lançado; "A caminho" still today + 10.
- **Existing suites that pin the old rule** ("Never counts" in `PrevistoInvariantsTest` and the like): rewritten to the new rule with the reason in the commit, never loosened.
- **ModelTest** (D47): the model gains `fixed`, the month's open reserve and the free part.
- **Volume** (D49): time `budgetsOverview` with the two extra sums and the wider fill.
- **UI** (Robolectric + Compose): header legend and TalkBack, row TalkBack, detail dek and "Previstos de outubro", Leitura sentence.

## 6. Rodada de testes por agentes (contrato fechado, D74)

- **When:** right after the contract commit (types, signatures and KDoc of §5.1, tags of §5.2, new bodies `TODO()`, compiling on all targets, `make test` green), in parallel with the implementation.
- **Agents:** 5 background agents, `isolation: worktree`, one file each: Borda (`ReservaEdgeTest`, shared: free 0, planned > limit, month start 29–31, occurrence on the last day, recado across the virada, estorno and parcelas of a fixed lançamento), Fuzz (`ReservaFuzzTest`, shared: random cotas/previstos/lançamentos/estornos through `budgetMonth` and the Db — never throws, `remaining = limit − spent − open`, `freeLimit ≥ 0`, Σ passos = `freeLimit`), Concorrência e ciclo de vida (`ReservaConcurrencyTest`, shared: `fill` racing `budgetsOverview`/`pending`/`planned`, lançar and skip on the same occurrence while the overview reads), Invariantes (`ReservaInvariantsTest`, shared: random sequences — margem = cota − gasto − abertos; variable + fixed = spent; a skipped occurrence never reserves; deleting a previsto never changes `variable`; a fixed lançamento always dated in its occurrence's budget month), UI (`ReservaUiTest`, androidApp: header, row, detail, Leitura texts). Tests compile but don't run until the implementation is green.
- **Agent prompt:** pin the contract commit (`git merge --ff-only {COMMIT}` / `git reset --hard`, check `git log -1`, check the new bodies are `TODO()`); never read from the main checkout (only `local.properties`). Read spec §2.13, §3, §4, §5, §7.1, §7.5, the glossary, architecture D68–D74, §5 and §6, AGENTS.md, `.agents/rules/testing.md` and the contract files (KDoc is contract). One file, public API only, real collaborators, `commonTest` without `java.*`, backtick names without comma/$/dot/colon/slash; expected values by hand from the spec with section + math; don't assert what spec/KDoc don't say (note it as an ambiguity). Suite conventions: one temp sqlite file per test with the block prefix, drop `-wal`/`-shm`, `TimeZone.of("America/Sao_Paulo")`, fixed dates (the book's 9 out 2026), `runTest(timeout = …)`; fuzz with fixed seeds, bounded iterations, explicit timeouts and seed/step/input in the failure message. Commit only its file, `test: {area} tests`, no Co-Authored-By. Report: contract commit read and any implementation seen; file, commit hash, compiled on all targets; coverage bullets; ambiguities and the reading chosen.
- **Triage when green:** bring the files with `git show <commit>:<file>`; a code bug → fix the code; a misread rule → fix the test and say why; a rule the spec doesn't cover → the user's decision, one question at a time, recorded in the docs. Never loosen a test.

## 7. Order of work

Each step one commit, each commit only after the user's approval.

0. **Book** (§5.3) from the mock, light and dark; `docs: margem honesta in the book`.
1. **Contract**: §5.1 types, signatures and KDoc (D68–D73), the `fixed` column, tags of §5.2; new bodies `TODO()`; `make test` green with the old behavior where bodies didn't change.
2. **Contract round** (§6), in parallel with step 3.
3. **Implementation**: `budgetMonth` first with its unit tests; then `Db` (sums, `fixed`, D72, fill horizon, `plannedLines`, the step/limit checks), rewriting the suites that pin "never counts"; then the UI per the book. `make test` after each change.
4. **Triage** of the contract tests; `make test` green.
5. **`make check`** green. `make mutation` apart (D48); a surviving mutant of the new code becomes a `MutationKillTest` case or is declared equivalent.
6. **Emulator check** in light and dark against the book and the mock (Verbas, detail, Leitura).
7. **Real data** (D69): the user's conversion script (`fixed` column, `fixed = occurrence_id != 0`) on a Backup export, restore it, and check Verbas against the phone.

## 8. Risks

- **Write before read in Verbas** (D71): `budgetsOverview`/`budgetDetail` now may write (`bump = false`) before reading, like `pending()`; a read during a long write waits (D29).
- **The phone's database** (D69): the new app can't open the release database without the conversion; installing it before step 7 breaks the app on the phone. Install only after the export.
- **Silent date change** (D72): editing a previsto's lançamento to another month keeps it in the occurrence's month without saying so in the form. Accepted with the decision; if the emulator check shows it confuses, it goes back to the user.
- **The previsto's "Parcelas" bug** (5-persona review, 2026-10-26: the stepper shows on Salvar previsto and is ignored) is out of this plan.
