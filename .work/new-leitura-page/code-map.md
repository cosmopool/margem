# Leitura v2 code map

Paths are relative to `/home/arrow/Development/margem`. Abbreviations: **Ins** = `androidApp/src/main/kotlin/com/kaiodelphino/margem/ui/Insights.kt`, **Main** = `.../ui/MainActivity.kt`, **Tx** = `.../ui/Transactions.kt`, **Comp** = `.../widgets/Components.kt`, **Theme** = `.../widgets/Theme.kt`, **Db** = `shared/src/commonMain/kotlin/com/kaiodelphino/margem/Db.kt`, **Types** = `.../Types.kt`, **Month** = `.../Month.kt`.

## 1. Today's Leitura (Ins, 681 lines)

**Entry point.** `fun Insights(db: Db)` at Ins:263-326. It takes no `go`, `back` or `snack`; Main:258 calls `Insights(db)`.

**State.** Five `rememberSaveable` values (Ins:267-271):
- `picked`: the month as a string; "" means the current month.
- `chosen`: the budget id; 0 means the riskiest.
- `rank`: TAGS or MERCHANTS.
- `all`: whether "Ver todos" was opened.
- `choosing`: whether the budget dialog is open.

**Loading.** One `query(db, picked, chosen, today) {…}` call (Ins:272-293). Inside it, in order:
- `d.monthOf(today)`, then `ensureMonths(current)`.
- `d.history(YearMonth(1,1), current)` to find the first month. No rows gives `Page(null)`, the empty state.
- Clamp the month, then `ensureMonths(month)`.
- `budgetsOverview(month, today, RISK)`, then `budgetDetail(pick.id, month, today)`.
- `tagRanking(start, end)`, `merchantRanking(start, end)`, `spentByDay(start, end)` and `spentByDay(start, end, fixedOnly = true)`.
- The month window is taken from `budgets.first().month.start/end` (Ins:284-285), not computed directly. `monthWindow` is `internal` (Month:18), so the app cannot call it.

**Data types.** `InsightsData` at Ins:233-253; `Page` at Ins:256.

**When it reloads.** `query()` (Main:175-179) re-runs on every `db.version` bump (D6/D19) or when a key changes. It returns null while loading and keeps the previous value during a reload. There is no error handling.

**Composables:**
- `Pace` (Ins:340-449): sentence, cumulative line drawn from `detail.lines` (excludes refunded and `fixed`), previstos band at the base, cota double rule, dashed ideal from `idealByDay`, dotted projection, shaded rest of the month.
- `axisStep` (Ins:454) and `grid` (Ins:463): chart helpers.
- `BudgetChoice` (Ins:474-496): a `Dialog`, not a `BottomSheet`.
- `History` (Ins:502-543): 6 months, hatched excess.
- `Ranking` (Ins:547-586): `OptionPicker` with tag prefix "insights.rank.", 6 rows plus "Ver todos", previstos part drawn faint.
- `Calendar` (Ins:606-681): heat grid aligned to `weekStart`, sentence for the heaviest weekday, legend, previsto dot.
- `seriesColor` (Ins:328): colour by budget-id order.
- `reais()` (Ins:336): whole reais.

**Filtering.** There is no grupo/busca today. The only filters are the budget choice (Ritmo and History only), the Marcadores/Estabelecimentos switch, and the single month (`MonthPicker` at Ins:311-315, arrows clamped between `first` and `current`).

**Test tags.** `object InsightsTags` (Ins:93-230): EMPTY, MONTH_LABEL/PREV/NEXT, PACE_BUDGET, BUDGET_CHOICE, BUDGET_OPTION, PACE_SENTENCE, PACE_CHART, HISTORY_SENTENCE, HISTORY_CHART, RANK_TAGS, RANK_MERCHANTS, RANK_SENTENCE, RANK_ROW, RANK_MORE, RANK_NOTE, RANK_EMPTY, CAL_SENTENCE, CAL_SHARE, CAL_DAY, CAL_LEGEND, CAL_EMPTY.

**Tests that cover today's Leitura** (all will break or need rewriting):

`androidApp/src/test/kotlin/com/kaiodelphino/margem/`:
- `LeituraUiTest.kt`:
  - empty page (l.92);
  - Ritmo opens on the riskiest budget, sentence/projection text and History description, then switching budget (l.99);
  - ranking tags/merchants rows, note, calendar levels/legend/sentence (l.126);
  - "Ver todos" (l.155);
  - a past month says how it closed, an empty month says "Nada lançado em outubro." (l.169).
- `LeituraInvariantsTest.kt`: empty state (l.109) and three seeded random runs (l.122-128). These check header, pace, history, ranking, rows and calendar against public Db reads (checks at l.319-470).
- `LeituraLifecycleTest.kt` (l.121-612), 14 tests:
  - empty page, and a budget created then deleted outside the screen;
  - Ritmo follows outside writes, refund and delete;
  - Ritmo follows a cota changed outside;
  - concurrent writes;
  - the budget choice survives writes and resets when deleted;
  - the default follows risk order;
  - month navigation bounds;
  - the choice is kept across months;
  - survives recreation;
  - leaving the tab resets everything;
  - today rolling into a new month;
  - ranking keeps Estabelecimentos and "Ver todos";
  - Estabelecimentos is kept when the month changes;
  - calendar from empty to full.
- `ReservaUiTest.kt`: Leitura pace sentences with previstos (l.530-610).
- `MargemUiTest.kt`: uses PACE_SENTENCE (l.161-164).
- `TetoLifecycleTest.kt`: switches to the Insights tab (l.459).

`shared/src/commonTest/kotlin/com/kaiodelphino/margem/`:
- `LeituraEdgeTest.kt`: `idealByDay` shape (l.107-434), and ranking/calendar/history installments, refunds and multi-tag rules (l.472-584).
- `LeituraFuzzTest.kt`: l.194, 342, 633.
- `ModelTest.kt`: covers `spentByDay` fixedOnly (l.1297).
- `VolumeTest.kt`: see section 7.

`CatalogActivity` has no Insights entries.

## 2. Db / Types / Month reads relevant to the requirement

**Infrastructure.**
- `read {}` (Db:123) runs one snapshot transaction on one of 2 readers (D29). `write(bump)` is at Db:99.
- Nothing returns null; an unknown id gives a zero value (Db:34-46).
- Schema is at Db:1533-1576. Indexes: `installments(date)`, `transactions(budget_id | merchant_key | occurrence_id | notice_id)`, `transaction_tags(tag_id)`.

**Transactions.**
- `transactions(today = NO_DATE): List<TransactionRow>` (Db:467) returns all transactions, newest first. Each row has the budget, alias, tags, installment count and estorno, plus `now` (the installment in today's month).
- Private helper `transactionRows(c, where, args)` (Db:1219-1241) loads the rows and then every tag link in one query.
- `transaction(id)` (Db:480) returns `TransactionDetail` with installments and the full `NoticeRow`.
- `TransactionRow` (Types:228-268) fields: `id`, `notice` (0 = manual), `budget`, `budgetName`, `merchant` (bank text), `alias`, `total`, `installments`, `date`, `time`, `tags`, `estorno`, `corrected`, `now`, `caption`, `fixed`. Derived: `name`, `title`, `refunded`.
- No public read lists every installment with its date. Only `budgetDetail.lines` (per budget and month) and `transaction(id).installments` exist.

**Tags.**
- `tags(): List<TagRow>` (Db:876) gives uses, soleUses and solePlanned.
- `tagRanking(from, to)` (Db:1001) and `merchantRanking(from, to)` (Db:1014) return `RankRow(name, total, count, fixed)` (Types:488). Merchants are grouped by `alias_key`, else by `merchant_key` (D50/D61).
- `spentByDay(from, to, fixedOnly)` (Db:1028).
- All three exclude refunded transactions via `NOT_REFUNDED` (Db:1522) and count each installment on its own date.

**Merchant key, alias and bank text.**
- `transactions.merchant_key`; `merchant_aliases(merchant_key, alias, alias_key)`.
- `setAlias` at Db:977. `MerchantKey.of` at Extract.kt:96-99.
- `fold(s)` (Extract.kt:43) and `foldedMatch(s, q)` (Extract.kt:63) are public.

**Time (hora) and how reliable it is.** `transactions.time` is TEXT HH:MM, stored per transaction, not per installment.
- From a recado: `Parsed.time` comes from the notice text, else from the notice's `whenMs`/`postedAt` (Extract.kt:130-132, 158). The user can edit it in the form, which sets `corrected`.
- Manual entry: defaults to the system clock when the form opens (Tx:1035-1036).
- Lançar sem recado (`formFrom`, Tx:809-820): uses `NO_TIME` (00:00) and the occurrence's date.
- Lançar previsto: D72 may move the date to the occurrence's date while keeping the time (Db:1305-1315).
- Parcelas: installment k > 1 has a date only, and the time is the purchase's.
- So "came from a recado" is `notice_id != 0`. Manual and sem-recado lançamentos have notice 0.

**Source (fonte).**
- The link is `transactions.notice_id` → `notices.package` / `app_label` → `sources.label` (LEFT JOIN, because a source can be removed).
- No current read returns a transaction's source. Only `transaction(id).notice.raw.packageName/appLabel` does, one transaction at a time.
- Previstos expose their source: `plannedRows` uses `COALESCE(s.label, p.link_package)` (Db:1431-1433), giving `Planned.linkSource`.
- The recado text is only available in Kotlin after `extract()` (D60). There is no SQL column for the merchant or the parsed text of a notice.

**Installments.**
- Table `installments(transaction_id, k, date, cents)`.
- `installmentDate` (Month:33).
- Future parcelas already exist as rows dated in future months, so "Meses à frente" can SUM them.

**Previstos (planned).**
- `planned(today)` (Db:944): open occurrences only, up to today + 10.
- Private `plannedRows(c, today, where, open)` (Db:1429). With `open = false` it returns every occurrence regardless of date; no public wrapper exists.
- `budgetDetail(...).plannedLines` (Db:806-819; Types:427-439): one budget, one month, open or lançada, skipped excluded.
- `planned_dates` horizon is the later of today + 10 and the end of today's budget month (`missingOccurrences` Db:1405-1422, D71). It is created by `fill(today)` (Db:957) inside `pending`, `planned`, `budgetsOverview` and `budgetDetail`.
- `skipOccurrence` (Db:962) sets `skipped = 1`. No public read returns skipped occurrences.
- `link_key` / `link_package` / `link_text` live on `planned`.
- `Planned.late` is `date < today` (Db:1446).
- The occurrence date is private `occurrenceDate` (Db:1666).
- `Planned` type: Types:89-117. `Repeats`: Types:82.

**Refunds (estornos).**
- Table `refunds(estorno_notice_id, transaction_id, notice_id)`. `notice_id` is non-zero when two pending notices cancelled each other.
- Functions: `registerRefund` (Db:530, 542), `undoRefund` (Db:569), `refundCandidates` (Db:585).
- `TransactionRow.estorno` / `refunded`.

**Dismissed notices.** `dismissed()` (Db:337); status is derived in the `NOTICES` SQL (Db:1514-1520).

**Notices still in the Pauta.**
- `pending(today)` (Db:325) returns `NoticeRow` with `parsed.cents/merchant/date/time/isRefund` and `planned`. `pendingCount()` is at Db:334.

**Budgets and monthly limits.**
- `budget_months(budget_id, month, limit_cents)`, filled by `ensureMonths` (Db:205) and `ensureMonth` (Db:1055).
- `history(from, to): List<HistoryRow>` (Db:987) gives spent against that month's own limit.
- `budgetsOverview(month, today, order)` (Db:759) and `budgetDetail(id, month, today)` (Db:782). `BudgetDetail` is at Types:397-420 and includes `lines: List<MonthLine>` and `firstMonth`.
- `riskOrder` (Month:182).

**Teto.** `ceiling(month)` (Db:829); `Ceiling` (Types:445). Spec §3 says the Leitura must not show it.

**Weekly steps (passo semanal).** `week_steps`; `setStep` / `clearStep` (Db:719, 733); `BudgetMonth.weeks: List<Week>` (Types:312-333). `Week.spent` is the variable spend only.

**Pace, ideal and projection.** Pure function `budgetMonth(month, monthStartDay, weekStart, today, limit, editedSteps, spentByDay, fixedByDay, open)` (Month:51-170). `BudgetMonth` (Types:341-382) has `limit`, `spent`, `remaining`, `weeks`, `ideal`, `status`, `projection`, `idealByDay`, `open`, `fixed`, `freeLimit`, `variable`, and `planned` (= open + fixed). Its private Db wrapper is at Db:1097-1114.

**Month and week boundaries.**
- `Settings.monthStartDay` and `weekStart` (Types:119-130); `settings()` at Db:215.
- `monthOf(date)` is public and suspend (Db:211).
- `monthWindow` and `monthOf(date, day)` are internal (Month:18, 24).

**Date bounds.** `FIRST_DATE` / `LAST_DATE`; `low()` / `high()` (Db:1671-1681, D37).

## 3. Shared UI pieces

All in Comp unless noted.
- **Theme.** Colors (Theme:37-94): `series` c1..c6 plus neutral, `heat` s0..s4, `brand` (argila), `info` (anil), `surfaceSunken`. Type scale (Theme:141-155): `headline` is Newsreader 29. `Space` (Theme:157-171): `page` 24, `touch` 48, `emphasis` 2. Text helpers: `serif()` / `sans()` (Theme:126-133). Formatting: `money` / `moneyPlain` / `moneyParts` (Theme:207-220), `weekdayShort` / `monthShort` / `monthLong` / `dateShort` / `dateLong` / `time` / `monthTitle` (Theme:227-242).
- **TopBar** (Comp:917): title, optional `onBack`/`close`, and an `actions` slot. **HomeBar** at Comp:926.
- **IconButton** (Comp:258): 48dp, `dim`/`enabled`. No badge exists; R11 asks for one.
- **SearchLine**: private in Tx:1433-1462 (lupa, rule, onDone). It must be moved or copied to Comp to reuse.
- **OptionPicker** (Comp:980): tag is `"$tag" + value.lowercase()`.
- **Check** (Comp:526). **Field** (Comp:305). **MoneyField** (Comp:410). **Stepper** (Comp:481). **DayPicker** (Comp:508).
- **TagChip** (Comp:544): NORMAL / SELECTED / SUGGESTED / DISABLED, removable ✕. It has no type indicator.
- **DayHeader** (Comp:1021). `dayTitle(day, today)` is public at `ui/Inbox.kt:501`.
- **TransactionLine** is private in Tx:566-603 and depends on `TransactionTags.ROW`. **ListRow** is at Comp:1031.
- **Amount** (Comp:695). **StatusLabel** (Comp:712).
- **Meter (régua)** (Comp:755). **TotalMeter** (Comp:803). **TotalLegend** (Comp:835).
- **hatch** (Comp:734) and **dashedBox** (Comp:787) are internal draw helpers. **SeriesMark** (Comp:169).
- **MonthPicker** (Comp:868). **StepsEditor** (Comp:892).
- **PageHeader** (Comp:1000): overline, serif 29 headline, dek. This is the closest piece to the R8 context title.
- **SectionLine** (Comp:1008). **EmptyState** (Comp:1053). **Notice** box (Comp:1067). **InfoBand** (Comp:1098). **DayStamp** (Comp:1120).
- **BottomSheet** (Comp:1133): a Dialog anchored at the bottom. **MargemDialog** / **DialogCard** (Comp:1159, 1172).
- **Snack / SnackHost / Snackbar / SnackbarHost / SnackTags** (Comp:1193-1250). `Snack(text, action, suspend onAction)` runs on the App scope.
- **DateTimeDialog** (Comp:1304; tags at Comp:1256-1300): picks one date plus a time. No range picker exists.
- **Sticky or collapsing headers: none.** No `stickyHeader` or `nestedScroll` anywhere in `androidApp/src/main`.
- **Navigation (D18).** `sealed interface Screen` (Main:69-96). The stack is saved with `encode`/`decode` and `StackSaver` (Main:98-153), so every new Screen needs an encode/decode case and a `tabOf` mapping (Main:155-161). Helpers: `go` = push, `back`, `home`, `replace` (Main:241-244). `BackHandler` is at Main:246.
  - The TabBar is hidden only for forms (Main:248-250).
  - `rememberSaveableStateHolder` keeps state per stack position and drops it when the screen leaves the stack (Main:225-230). Tapping a tab replaces the whole stack (Main:279), so Leitura state resets.
  - Detail-page pattern: Budgets `go(Screen.Budget(id, month))` (`ui/Budgets.kt:545`) → `BudgetDetailScreen(db, id, month, go, onMonth, back)` (Main:261).
- **TabBar** (Comp:945) with `enum Tab` (Comp:932-938): Verbas, Pauta, Lançamentos, Leitura, Ajustes. Five tabs, as spec §7 says.

## 4. Existing search and autocomplete to reuse

- **TagPicker** (Tx:1519-1611) is the best template for the chip field:
  - chosen chips in a `FlowRow`, plus `SearchLine`;
  - "Mais usados" shortcuts when empty;
  - typed results filtered with `fold` / `contains`, starts-with first, then by uses;
  - matched letters in bold via `foldedMatch`, plus a "33 lançamentos" count;
  - a "none" line;
  - a list that scrolls and sizes itself above the keyboard (`bringIntoViewRequester`, `room`);
  - spec rules at §7.3; tags documented in `TransactionTags` TAG_*, Tx:385-427.
- **BudgetSheet** (Tx:1464-1510): `SearchLine` inside a `BottomSheet`, filtered by `fold`.
- **Refund search** (`ui/Inbox.kt:400-404`): `fold` matching on caption, alias, merchant, name, plus `moneyPlain`.
- Lançamentos has no search.

## 5. Spec and architecture constraints

**spec.md:**
- **§7.5** (spec.md:200-218): "**Um mês para a página toda**: seletor de mês no topo (do primeiro mês com verba até o atual…)". The whole section describes Ritmo, Verbas por mês, ranking and calendar: "Seletor 'Marcadores · Estabelecimentos'", "Um lançamento com vários marcadores conta inteiro em cada um", "Previstos no ranking e no calendário … lançamentos de previsto continuam contando", "Ranking e calendário contam a compra parcelada pela data de cada parcela", "Compras estornadas não contam", "Frases … por modelos fixos", "Acessibilidade: cada gráfico tem descrição para o TalkBack", "Vazio: sem verba, 'Nada para ler ainda.'", "Cor da verba … pela ordem de criação". **It must be rewritten for v2.**
- **§3** (spec.md:62): "Aparece só no cabeçalho de Verbas (§7.1) e nos avisos de cota; widget e Leitura não mostram o teto." This matches the requirement's non-goal.
- **§2.12**: legenda "não entra na Leitura (§7.5)". Lugar and aviso matching must not search captions.
- **§2.13**: Previsto "Não é lançamento: não é gasto nem entra nos rankings e no calendário da Leitura até ser lançado." This conflicts with the "com previstos" options unless open previstos are kept visually separate.
- **§5** pace and projection rules; **§4** steps; **§6** refunds and parcelas.
- **§7**: "Cinco abas: Verbas · Pauta · Lançamentos · Leitura · Ajustes."

**architecture.md:**
- **D5/D12**: SQL hand-written; "O SQL lê, uma função Kotlin calcula". `budgetMonth` is internal; screens receive a ready `BudgetMonth`.
- **D6/D19/D30**: a version counter plus `query()`; no ViewModel. Screens call `ensureMonths` before reading a month.
- **D15**: no Material 3. **D17**: "Gráficos em Canvas próprio, um Composable por gráfico. São 4 gráficos simples".
- **D18**: navigation without a library.
- **D29**: 1 writer and 2 readers.
- **D35**: "cada arquivo de tela tem um objeto de tags … uma `const val` por elemento que os testes tocam ou leem, com KDoc… O objeto é parte do contrato do bloco."
- **D44**: `idealByDay` in shared.
- **D45**: "**Leitura sem consulta nova**: a tela usa history, tagRanking, merchantRanking, spentByDay e budgetDetail… Frases, degraus … são cálculo de apresentação em ui/Insights.kt." R22 directly contradicts this, so a new D-row is needed.
- **D47**: ModelTest. New public reads must enter the model.
- **D48**: PIT plus an SQL operator, threshold 86. New SQL needs `MutationKillTest` coverage.
- **D49**: "Volume … mede budgetsOverview, budgetDetail, history e as leituras da Leitura com um teto de tempo folgado (~10×)".
- **D50/D61**: one alias equals one place, grouped by `alias_key`. `fold` is the only folding function; SQL compares only key columns, never raw text.
- **D67/D69**: one schema v1; any change is a migration. R22 says no schema change, which is consistent.
- **D71**: occurrence horizon is the end of the current month.
- **D75**: default budget per feature is "600 linhas de produção, 1 arquivo novo, 10 símbolos públicos (`const val` de tags não contam)". **The rebuild (18 widgets plus pages) will almost certainly exceed it**; an override must be declared in the manifest.
- **§9 Arquivos** (architecture.md:302-327): one file per tab ("`ui/Insights.kt`"); `widgets/Components.kt` holds the book's components.
- **§10 Testes** (architecture.md:336-350): integration through the public API, unit tests only for `budgetMonth`/extraction/SHA; a 4-agent blind round per block with a prefix (Leitura used `Leitura…`); UI tests via Robolectric plus `AndroidSQLiteDriver`.

**glossary.md:**
- **Leitura** = `Insights`, "Área de análise: gastos por verba, marcador, estabelecimento e tempo." Evitar: "relatórios, dashboard, estatísticas" (relevant to Q10, "Análise").
- **Lugar vs estabelecimento**: the glossary uses *estabelecimento* everywhere (Texto do banco, Apelido). "Lugar" is not a term.
- **Aviso**: not a glossary term for notices (they are *recado*; the app is *fonte*). In code and spec, "aviso" already means the warning box (`Notice` composable, Comp:1061-1063, "notice box (aviso)") and "avisos de cota" (§3). Reusing it would clash.
- **Período**: listed under Evitar for **Mês** ("período, ciclo, competência"), although spec §7.5 itself says "parcela no período". "Intervalo" is not defined.
- **Recorrente**: Evitar for **Previsto** ("agendado, compromisso, conta fixa, recorrente…"). "Volta todo mês" also sits close to the *Repete · Todo mês* term.
- **Costume**: absent (Q7).

## 6. I/O boundaries crossed

- **SQLite reads**: only through `Db` suspend functions, on IO with a reader snapshot (Db:123). Several calls inside one `query{}` are not one snapshot; each `read{}` is its own transaction.
- **Hidden writes inside reads**: `budgetsOverview`, `budgetDetail`, `pending(today)` and `planned(today)` call `fill(today)` (Db:957), and the screen calls `ensureMonths`. These are writes that do not bump `version`.
- **Clock**: `LocalToday.current()` (Main:181-184), a static CompositionLocal that tests replace. It is read at composition and not re-read on day rollover (the Lifecycle test covers rollover with the screen closed). The manual form reads `Clock.System` directly (Tx:1035).
- **Settings**: `monthStartDay` and `weekStart` come from `db.settings()`.
- **Android lifecycle**:
  - `rememberSaveable` survives recreation and process death.
  - Stack screens must be encodable as strings (Main:98-153).
  - Leaving the tab drops state (Main:279).
  - `LifecycleEventEffect(ON_RESUME)` is used only for notification access (Main:222).
- **Snackbar**: the app-scoped `SnackHost` (Main:237); Desfazer runs after the screen is gone.
- **Failed or empty reads**:
  - `query()` returns null while loading; screens render nothing or only the TopBar (Ins:297-304; Tx:543).
  - The Db never throws for unknown ids. There is no try/catch or runCatching in any `ui/*.kt`, so an exception in `produceState` would crash. Only `Backup.kt:15-26` uses runCatching.
  - Empty data gets explicit empty states ("Nada para ler ainda." / "Nada lançado em outubro.").

## 7. Volume (`shared/src/commonTest/kotlin/com/kaiodelphino/margem/VolumeTest.kt`)

**What it builds** in `five years of use still read fast` (l.48-187), with seed 49, from 2021-10-01 to today 2026-10-16:
- 2–4 Nubank notices per day, about 5.5k notices. About 12% are dismissed; in the last 20 days, 18% are left pending.
- The rest are accepted, about 4.5k+ transactions. 10% have 2–12 installments; each has 1–3 of 20 tags.
- 8 budgets, 150 merchants (40 with aliases), refunds on about 2%.
- Limit changes in 2023 and 2025; step edits every third month.
- 12 linked monthly previstos, 6 ahead, and 1 open monthly previsto since 2021.
- Then week start is set to Sunday and month start day to 5.

**Reads it times** (l.161-177; ceilings l.312-323):

| Read | Ceiling |
|---|---|
| `budgetsOverview` | 30 ms |
| `budgetDetail`, every budget | 350 ms |
| `history`, all months | 1200 ms |
| `pending` | 15 ms |
| `pending(today)` | 45 ms |
| `planned(today)` | 45 ms |
| `dismissed` | 50 ms |
| `transactions(today)` | **200 ms** |
| `tagRanking` / `merchantRanking`, month | 10 ms |
| `tagRanking` / `merchantRanking`, all | 80 ms |
| `spentByDay`, year / all | 40 ms |

`transactions(today)` is the existing "load all lançamentos" read. It has no per-installment dates, so loading everything for in-memory widgets also needs a full installments read (one row per installment).

Other tests in the file:
- `planned without a linked previsto does not read the pauta` (l.195): 3000 notices; `planned` must take under ¼ of `pending`.
- `two years of launched previstos keep pending and planned under twice the time` (l.226): 200 previstos × 24 months, at most 2× a database without that history.

## Contradictions and points to resolve

1. **Spec §7.5** "Um mês para a página toda" against R1 (day interval) and R7 (widget grid). The spec must be rewritten.
2. **R2** says "Grupo e busca, que hoje são separados". Today's Insights has neither; it only has the budget dialog and the Marcadores/Estabelecimentos picker. The phrase likely refers to the v4 mockups.
3. **D45** "Leitura sem consulta nova" and **D17** "4 gráficos simples" against R22 and the 18 widgets. New D-rows are needed.
4. **D75 budget** (600 lines, 1 new file, 10 public symbols) will be exceeded.
5. **Glossary**: "aviso" clashes with the existing warning-box meaning and spec "avisos de cota"; "lugar" is not a term (estabelecimento is); "período" is Evitar; "recorrente" is Evitar for previsto; "costume" is undefined.
6. **§2.13** says open previstos are not in the Leitura rankings or calendar, against the "com previstos" options (R19). This needs a spec decision.
7. **§2.12**: the caption must not enter search, so lugar matches only alias or bank text (as R2 says) and aviso matches only fonte or recado text.
8. **R21 hora**: "veio de recado" equals `notice_id != 0`. Recado times can still be the notice's post time or user-edited (`corrected`); Lançar previsto can move the date (D72) while keeping the time. Manual entries get the clock time and sem-recado entries get 00:00.
9. **R22 reads that don't exist today**:
   - transaction → source;
   - first date per merchant and per tag;
   - all budgets' planned occurrences for an arbitrary range, including skipped ones ("O que ficou fora" needs skipped; no public read returns them);
   - tag → budgets;
   - all installments with dates.
   - The planned horizon is the end of the current month (D71), so "Meses à frente" must project monthly previstos in memory, as R22 says.
10. **`monthWindow` is internal**: the "este mês / mês passado / 3 / 6" shortcuts need the window from `Db` (`monthOf` plus the `BudgetMonth.start/end` trick at Ins:284) or a new public helper.
11. **Missing UI pieces**: no range-date picker (DateTimeDialog picks one date), no sticky or collapsing header, no badged IconButton, no typed chip; SearchLine and TransactionLine are private in Tx.
12. **Navigation**: widget pages pushed as new `Screen` entries need string encode/decode of the whole query. Today's `Insights(db)` has no `go`, `back` or `snack`.
13. **Q6** (remember the filter): tab switches currently drop state (Main:279), and the Lifecycle test "sair da aba e voltar abre no mês atual" asserts that reset.