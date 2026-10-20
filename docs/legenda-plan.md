# Legenda do lançamento — plan

Status: **decided** (product 2026-10-16; implementation 2026-10-20, §5). Refined after two plan reviews (2026-10-20): test coverage gaps, `make check`, UI in the contract round, migration order.
Design: [legenda recolhida](mockups/descricao-b-recolhida.html) (B1–B8; B10 traces where the name flows). Rows in [architecture.md](architecture.md): D52–D57.

## 1. Goal

An optional **legenda** (`Caption`), up to 80 characters, for one lançamento. When it's present, it's the lançamento's name in lists, in italic, and the merchant (alias, else bank text) moves to an "em …" credit line underneath. Without one, everything stays as it is today.

The same block fixes the estorno search gap: per §7.3 it matches legenda, alias, bank text and value, ignoring case and accents. Today `ui/Inbox.kt:340` matches only the shown name and the value, ignoring case only.

## 2. Decided (product, 2026-10-16)

| Rule | Decision |
|---|---|
| Design | B · Recolhida: a "+ Legenda · só deste lançamento" link under the merchant field; italic headline plus an "em …" credit in rows. |
| Term | Legenda; `Caption` in code. |
| Suggestion (§2.5) | Doesn't carry the legenda. |
| Emenda | Not an emenda: doesn't set `corrected` or "‸ emendado". |
| Leitura | Ignores it; the merchant ranking is unchanged. |
| Text | Trimmed of every invisible at the edges (ASCII controls, VT/FF/NEL, NBSP family — kept in the middle; 2026-10-20); whitespace only means none; every line break (LF, CRLF, CR, U+2028, U+2029) becomes one space; 80 characters at most. |
| List cut | 2 lines in Lançamentos and budget detail, 1 in estorno candidates, then "…". |
| Counter | Shows near the limit ("72/80") from 70 characters (O6), and the field stops accepting input at 80. |
| Edit | Blank (or ✕) and Salvar removes it, unlike the alias. Until Salvar, a "legenda tirada: ~~…~~ Desfazer" line shows. |
| Delete / Desfazer lançar | The legenda is lost; the recado goes back to the Pauta without it; no extra warning. |
| Where shown | Form, Lançamentos, budget detail, estorno candidates. Not in Pauta, Riscados, widgets, snackbars or dialogs. |
| Estornada | Legenda and credit in tinta 3, unstruck; only the value is struck. |
| Estorno search | Legenda, alias, bank text and value; case- and accent-insensitive ("estação" finds LOJA ESTACAO). |
| Preview (2026-10-20) | Keeps the budget's name ("Mercado: margem …"): the legenda does **not** enter the preview (user decision, after the mockup and the tests showed the name is the verba's). |
| Manual form | Keeps the single "Estabelecimento" field and gets the same link underneath. |
| Manual without merchant | Legenda only, with no "em …" line. |

## 3. Current shape it must fit (line numbers at 28e3218; the teto merge and the contract move them)

- `Types.kt:122` `TransactionForm` (merchant, alias); `:139` `TransactionRow` with `name = alias.ifEmpty { merchant }` (`:167`); `:184` `RefundCandidate.name`.
- `Db.kt:908` `saveTransaction` is the one parse/write path for `accept`, `addManual` and `edit`. It returns 0 (nothing written) when the form is refused, and `storable()` cleans text. `corrected` is computed at `:916`.
- `Db.kt:880` `transactionRows`; `:483` `refundCandidates` (`:493` lançamento, `:500` recado); `:323` `suggestion`; `:755` `merchantRanking`.
- Schema at `user_version` 2 after the teto merge (`Db.kt:1016` `migrate`, one `if (v < N)` per version). Backup is a file copy, and an older backup migrates when it's reopened (`:143`, `:178`).
- UI: `ui/Transactions.kt:300` `TransactionLine` (`AliasText(t.name)` at `:317`), `TxForm` `:370–652`; `ui/Budgets.kt:652` `MonthLineRow` (`:668`); `ui/Inbox.kt:340` search, `:402` `CandidateRow`; `widgets/Components.kt:293` `Field`, `:337` `AliasText`.

## 4. Changes

### 4.1 shared

- `TransactionForm.caption: String = ""` as the last parameter, so positional calls in tests still compile.
- `TransactionRow.caption`; `name` keeps "alias, else merchant" (O3/D54): the headline is `caption.ifEmpty { name }`, the credit is `name`.
- `RefundCandidate`: add `caption`, plus the bank text and alias the search and credit need.
- `saveTransaction`: clean the caption (every line break → one space, trim, `storable`), refuse the form (return 0) if it passes 80 by `String.length` (O2/D53); write it in INSERT/UPDATE. `corrected` is unchanged. The UI never sends more than 80 (the input cuts without leaving a lone high surrogate, D55).
- `transactionRows` and `refundCandidates` read it; suggestion and `merchantRanking` are untouched.
- Schema: `transactions.caption TEXT NOT NULL DEFAULT ''` at `if (v < 3)` (D52; the teto keeps v2 and merges first).

### 4.2 androidApp

- `TxForm`: saveable `caption` and open/closed state (D57); the link and the field after "No banco" (recado) and after the name (manual); ✕; the "legenda tirada · Desfazer" line derived from `captionOriginal.isNotEmpty() && caption.isEmpty()` in edit; the counter from 70 ("72/80"); new test tags `FORM_CAPTION`, `FORM_CAPTION_OPEN`, `FORM_CAPTION_CLEAR`, `FORM_CAPTION_REMOVED` + `"$FORM_CAPTION_REMOVED.undo"`, `FORM_CAPTION_COUNT` (D35: one `const val` per node the tests touch, with KDoc saying the 70 threshold and the formats).
- `Field`: italic text style and a counter slot in the hint.
- Rows (`TransactionLine`, `MonthLineRow`, `CandidateRow`): italic headline plus an "em" + `AliasText` credit when there's a legenda. The alias underline moves to the credit; the max-lines cut follows §2 (2/1 lines).
- Estorno search: match legenda, alias, bank text and value through one `fold()` (D56: NFD, strip combining marks, lowercase, private in `ui/Inbox.kt`); the placeholder becomes "nome, legenda ou valor".
- Preview title in `TxForm` follows the lists' title (§7.3).
- KDoc of the tags that describe what tests read: `TransactionTags.ROW`, `BudgetTags.LINE`, `InboxTags.CANDIDATE_TX`, `InboxTags.CANDIDATE_NOTICE`, `InboxTags.REFUND_SEARCH` (its KDoc still describes the old search).

### 4.3 Tests (normal integration, per `.agents/rules/testing.md`)

- Db round trip: the caption through `accept`, `addManual` and `edit`; not carried by the suggestion; `corrected` unchanged ("caption only → false" next to `RecadoEdgeTest.kt:501`); refund candidates carry it.
- Limit (O2/O4): exactly 80 accepted, 81 after cleaning refused in the three writes; a refused edit keeps the previous state; 81 raw with edges that clean to 80 accepted; one astral case (emoji counts 2) and one NFD case fixed by the count rule.
- Cleaning (§2.12): trim at the edges; whitespace only → no legenda (reads `""`); `\r\n`/`\r`/`\n`/U+2028/U+2029 → one space each; what is read back is the cleaned text.
- Edit blank clears it (unlike the alias).
- Delete and Desfazer lançar: `deleteTransaction` (and the snackbar undo) return the recado to the Pauta with no legenda; relaunching comes without one; the delete dialog says nothing about a legenda.
- Leitura ignores it: `merchantRanking`, `tagRanking` and `history` equal those of the same lançamento without legenda.
- Migration: a v1 fixture built in the test (v1 DDL + rows + `PRAGMA user_version = 1`) **opened** and **restored with `importFrom`**: data intact, captions empty, reopen idempotent. Assert behavior, not `user_version == 3`.
- ModelTest (D47): the model's rows and random forms gain the caption; the model reproduces the cleaning and the refusal, so caption × estorno × delete × edit sequences are compared.
- Existing tests that read `name` (`RecadoInvariantsTest.kt:800`, `ConcurrencyTest.kt:661`) stay valid (O3) and are **extended**: `checkCandidates` compares caption/alias/bank text with the row; `verifyTx` round-trips the caption; `RecadoEdgeTest.kt:553` gains "no merchant + legenda → no credit line".
- UI (Robolectric): link opens and ✕ folds; Desfazer after ✕; counter from 70 and the 80 cut; row headline and credit in Lançamentos (2 lines), budget detail, candidates (1 line), estornada (credit in tinta 3, unstruck, only the value struck); manual without merchant (no credit line); no legenda text in Pauta, Riscados, snackbars or the delete dialog; estorno search by legenda, by alias, by bank text behind an alias and by value, case- and accent-insensitive ("estação" × LOJA ESTACAO), with the new placeholder; preview title with legenda; one `typeComposing` case in the legenda field (composing word + counter + 80 cut).
- The "…" cut and the italic are checked on the emulator against the mockup (Robolectric doesn't expose them in semantics).

## 5. Decided (implementation, 2026-10-20)

| # | Decision |
|---|---|
| O1 | **Revised**: the column takes migration **v3** (`ALTER TABLE transactions ADD COLUMN caption TEXT NOT NULL DEFAULT ''`). The teto already had v2 written (`claude/teto-dc57c8`), so it merges first and D42 stays "Migração v2" (D52). |
| O2 | Over 80 after cleaning: `saveTransaction` refuses the form (return 0), as for out-of-range installments (D53). |
| O3 | `TransactionRow.name` keeps "alias, else merchant"; `caption` beside it; headline `caption.ifEmpty { name }` (D54). |
| O4 | Count = `String.length` (UTF-16 units; an astral emoji counts 2). The user's choice, against the code-points recommendation (D55). |
| O5 | One `fold()` helper (NFD, strip combining marks, lowercase), private in `ui/Inbox.kt`, only by the estorno search (D56). |
| O6 | Counter from 70 characters (D57). |

## 6. Rodada de testes por agentes (contrato fechado)

- **When:** right after the contract commit (types and signatures with KDoc describing the new behavior; new bodies `TODO()`; compiling and `make test` green), in parallel with the implementation.
- **Agents:** 5 background agents, one worktree and one file/area each: Borda (`LegendaEdgeTest`, shared), Fuzz (`LegendaFuzzTest`, shared), Invariantes (`LegendaInvariantsTest`, shared), UI (`LegendaUiTest`, androidApp), Ciclo de vida (`LegendaLifecycleTest`, androidApp, `StateRestorationTester` for the saveable caption and the open/closed state). The tests must compile but don't run until the implementation is green.
- **Fuzz scope:** hostile caption text through the three writes and back (loose surrogates, D28 characters, combining marks, RTL, controls, over-length): never throws, refuses what the KDoc refuses, reads back what it wrote. Fixed seeds, bounded iterations, wall-clock timeout, seed in the failure message. Search, counter and limit stay in the normal tests.
- **Agent prompt:** pin the contract commit, verify `git log -1`, never read from the main checkout or from `androidApp/src/main` beyond the contract files (tags). Read spec §2.5, §2.8, §2.10, §2.12, §7.1, §7.3, §7.4, §7.5, the glossary, the relevant architecture rows (D52–D57), AGENTS.md, `.agents/rules/testing.md` and the contract files (types, KDoc, tags — the KDoc carries O2–O6). Suite conventions: one temp sqlite file per test with the block prefix, drop `-wal`/`-shm`, `TimeZone.of("America/Sao_Paulo")`, fixed dates per file, `runTest(timeout = …)`.
- **Triage when green:** a code bug → fix the code; a misread rule → fix the test and say why; a rule the spec doesn't cover → the user's decision, recorded in the docs. Never loosen a test.

## 7. Order of work (0–6 done 2026-10-20; 7 and 8 pending)

0. **Teto merge first** (2026-10-20 decision): `claude/teto-dc57c8` into `main`, `make test` green; the legenda then takes v3.
1. Docs: this plan decided, D52–D57 in architecture.md, spec §2.12/§7.3/§7.5 amendments.
2. Contract commit: types, signatures, KDoc with O2–O6, `TODO()` bodies where the signature is new; KDoc of the tags above.
3. Contract test round (§6) in parallel with step 4.
4. Implementation: shared, then UI per the mockup, plus the integration tests in §4.3. `make test` after each change.
5. Triage of the contract tests; `make test` green.
6. **`make check`** green (includes mutation, D48: a surviving SQL/Kotlin mutant of the new code becomes a `MutationKillTest` case or is declared equivalent in the commit).
7. Check on the emulator in light and dark against [the mockup](mockups/descricao-b-recolhida.html): the "…" cut, the italic, the widget da Pauta without legenda.
8. Before release: restore a real `Backup` export (the §8 risk; the golden DB was dropped, D46).

## 8. Risks

- **Migration order (2026-10-20):** v3 requires the teto's v2 to be on the device first. Resolved by merging the teto before the legenda lands; if the order ever flips, the legenda's `if (v < 3)` would skip `ceiling_months` and must not ship.
- **UTF-16 cut (O4):** a flag/emoji family counts 4–7 units, and "até 80 caracteres" reads as perceived characters; the counter jumps by 2. The input cut never leaves a lone high surrogate (the `storable` would turn it into U+FFFD and the legenda would be saved with "�"). Accepted cost of the decision.
- **Taller rows:** a row with a legenda grows to 3–4 lines; the budget detail's running sum stays aligned to the first line.
- **Real data:** the migration runs on the release app on the phone. Test it against a Backup export of the real data before release (step 8).
