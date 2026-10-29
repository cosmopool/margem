# Leitura — review against the briefing

Status: **open** 2026-10-29. Five read-only review agents (one per use of the briefing, plus plan × code) over the working tree at 74a2649 plus the uncommitted margem honesta changes. Nothing run (no tests, no emulator); key findings spot-checked in the code. Questions in §5 wait for the user, one at a time.

## 1. Briefing (user's words)

> o app nasceu para ajudar o usuario a manter os gastos dentro do orcamento que ele mesmo definiu. o app deve mostrar como esta o ritmo atual dos gastos de forma simples e clara e facilitar o lancamento dos gastos para estar sempre atualizado (e evitar procrastinacao do usuario). o usuario cadastra as contas fixas, lanca as contas e transacoes dinamicas e acompanha os gastos. a leitura era para encontrar os padroes de gastos, acompanha-lo durante o mes e conseguir fazer pesquisa para entender certas tags e verbas e tambem voltar no tempo para entender e comparar com outros meses.

## 2. Verdict

The code follows spec §7.5 almost item by item. The gap is the plan: it covers "acompanhar o mês" and "padrões dentro de um mês", not "pesquisar" nor "comparar". The margem honesta (D68) also left Ritmo and Verbas por mês measuring different things on the same page.

| Use | Verdict |
|---|---|
| Acompanhar o ritmo no mês | Partial: one budget at a time; chart mixes two bases |
| Padrões de gasto | Partial: one month only; fixed lançamentos distort |
| Pesquisar marcadores e verbas | **Not met** |
| Voltar no tempo e comparar | Partial: navigation yes; comparison only in Verbas por mês |

## 3. Plan × code audit (spec §7.5; `ui/Insights.kt` unless noted)

| Item | Done? | Where | Test |
|---|---|---|---|
| One month for the page, picker, "até sex, 9" | yes | 265-307 | LeituraUiTest:99, LeituraLifecycleTest:339 |
| Ritmo current: sentences, projection | yes | 352-362 | LeituraUiTest:99, MargemUiTest:159 (only "abaixo" with previsto) |
| Ritmo past | yes | 363-367 | LeituraUiTest:168 |
| One budget at a time, default by risk | yes | 274, 456-476 | LeituraLifecycleTest:266, :395 |
| Verbas por mês | yes, without reserve | 483-523 | LeituraUiTest:99, Invariants |
| Ranking: switch, 6 rows, "Ver todos" | yes | 528-556 | LeituraUiTest:126, :154 |
| Tags-sum warning | yes | 557-560 | LeituraUiTest:126 |
| Aliases, case and accents | yes | Db.kt:1014-1022 | LeituraFuzzTest:342 (accent case not confirmed) |
| Calendar: steps, legend, sentence | yes | 582-640 | LeituraUiTest:126, :168 |
| Installments by their date | yes | Db.kt:1001-1031 | LeituraEdgeTest:472, :486 |
| Refunds out | yes | NOT_REFUNDED, 378 | LeituraEdgeTest:518, :542, LeituraFuzzTest:633 |
| TalkBack | partial | 389, 494, 619-624 | descriptions tested, sentence not |
| Empty states | yes | 289-295, 533, 586 | LeituraUiTest:92, :168 |
| Budget color | yes | 281, 320-325 | none found |

## 4. Findings

### 4.1 Confirmed in the code
1. **Ritmo × Verbas por mês contradict.** Ritmo judges by the margem with open previstos reserved (357, 364); Verbas por mês counts `spent > limit` (492) from `history` (Db.kt:987-998). Current month, cota 3.000, aluguel 2.000 open, 1.100 spent: Ritmo "R$ 100,00 além da cota", Verbas por mês "Nenhum mês fora da margem". Recorded as pending in margem-plan.md:96, but described only for a past month with a late previsto. Past month variant: a budget is the default (OVERRUN) in Ritmo and has no hatch below.
2. **Ritmo chart mixes two bases.** The line is the gasto variável (378), the dashed ideal ends at the cota livre, the double rule sits at the full cota (402), and the projection (408) starts at the variable cumulative and ends at `projection`, which adds the previstos (Month.kt:144). With a big previsto the ritmo is squeezed into the bottom third and the projection jumps as if it were future spending. Previstos are never drawn, so the gap cota livre → cota is unexplained. MargemUiTest scenario (cota 3.000, aluguel 2.000, variable 40): sentence "R$ 250,31 abaixo do ritmo ideal", projection line rising to 2.138.
3. **Past month: sentence and line disagree.** Sentence and TalkBack say total spent (364, 373); the line ends at the variable. Aluguel 2.000 + 900 variable: "Fechou em R$ 2.900,00", line stops at 900. Current-month TalkBack says "para uma cota de" the full cota (372).
4. **Fixed lançamentos in ranking and calendar.** `tagRanking`, `merchantRanking`, `spentByDay` don't filter `fixed` (Db.kt:1001-1031), as margem-plan.md:31 says. The aluguel day becomes step 4 and flattens the rest (step = max day ÷ 4, 604); "As segundas pesam." points at the rent day; the rent "puxa a fila". Same page: Ritmo excludes fixed, calendar right below includes it.
5. **Calendar sentence.** Sums per weekday, no mean, no threshold (593-596): one day with spending is enough for "pesam" ("100% do mês em quatro segundas"); ties go to the first weekday (594). Current month counts the whole window: on the 9th, "em cinco sábados" (590, 599), and future-dated installments color days that haven't come.
6. **TalkBack.** §7.5 asks for the conclusion sentence; descriptions (389, 501) don't have it; ranking has no chart description; calendar is 31 loose items with no summary.

### 4.2 Not covered by the plan
7. **No research.** Ranking rows aren't clickable (540-551), `RankRow` has no id (Types.kt:485), `Insights(db)` gets no `go` (MainActivity.kt:258), so the budget name in Ritmo only opens the budget choice, never the detail. Lançamentos is a flat list without search or filter (Transactions.kt:533-559, §7.4). No tag history over months, no tag × verba cross (rankings ignore the chosen budget), no merchants of a tag. Ajustes › Marcadores only counts uses. The glossary promises "análise por verba, marcador, estabelecimento e tempo" (glossary.md:52).
8. **No comparison.** No this month × previous, no average, no ranking up/down, no tag or merchant over time, no month total across budgets (§3 keeps the teto out of the Leitura, though D42 kept it per month "para a Leitura mostrá-lo depois"). Verbas por mês: one budget, fixed 6 months.
9. **No whole-month view.** One budget at a time through the dialog (456-476); Ritmo and Verbas por mês are the chosen budget, ranking and calendar are all budgets, unlabelled. Verbas por mês doesn't show the budget name (491).
10. **Nothing against procrastination.** No Pauta count (M7 shows it only in Verbas, Budgets.kt:486); with recados piling up, "abaixo do ritmo" can be false. The calendar can't tell "no spending" from "not launched".
11. **Changing the month start day rewrites the past.** `history` and `budgetMonth` use the current `monthStartDay` (Db.kt:988, 1095); "Fechou em" of an old month changes.
12. **No action in the sentence.** "Acima do ritmo: R$ 51,10 para 3 dias." (glossary.md:49) isn't implemented anywhere. "sobre o ritmo ideal" (358) vs "acima do ritmo ideal de hoje" in Verbas (Budgets.kt:982); Leitura doesn't say "da cota livre" (Verbas does, Budgets.kt:984).

### 4.3 Docs and tests out of date
13. Book Fig. 9.2 (design_system.html:1933) and 10.6 (:2244) still describe the projection as "gasto × soma dos passos ÷ ideal"; margem-plan §5.3 didn't list them.
14. Book Fig. 9.4 (design_system.html:1983) "o resto, rolando" vs spec/code "Ver todos". Book §9 "Toque" (tap opens a card with the exact value) isn't implemented and §7.5 doesn't mention it. Fig. 9.3 (all budgets side by side) is in the book, not in §7.5 nor 10.6.
15. Section title "Gasto por dia" (584); spec and book say "Calendário de gastos".
16. LeituraInvariantsTest:333-359 oracle still uses total spent × ideal (pre-D68) and checks "last idealByDay = cota"; passes only because the generator makes no previstos. Only one Leitura test with a previsto (MargemUiTest:159); missing "além" by reserve, past month with open/launched previsto, chart without fixed lançamentos. `ReservaUiTest` (margem-plan §6) doesn't exist yet.
17. Edge, not verified: a month where no budget has a cota makes the window day 1 only (277-278).

## 5. Questions (one at a time; recommendation first)

| # | Question | Recommendation |
|---|---|---|
| R1 | Verbas por mês: what is "fora da margem"? (4.1.1) | The margem with open previstos reserved, like Ritmo: `history` returns the open previstos, the bar draws them, out = `remaining < 0`. Alternatives: an open occurrence of a closed month stops reserving (changes M8); keep spent × cota and reword the sentence. |
| R2 | Ritmo chart base (4.1.2, 4.1.3) | Previstos as a band at the bottom (like the M11 rule), line = previstos + variable, ideal = previstos + ideal of the cota livre, rule at the cota: one base, the sentence and the drawing agree. Alternative: everything variable (rule at the cota livre, projection without previstos, total only in the sentence). |
| R3 | Fixed lançamentos in ranking and calendar (4.1.4) | Out, as in Ritmo: patterns are of the gasto variável. Alternatives: a "variável · tudo" switch; keep and mark previsto days/rows; keep as is. |
| R4 | Research tags and budgets (4.2.7) | Filter in Lançamentos (tag, budget, merchant, month) and a ranking row opens Lançamentos already filtered; the budget name in Ritmo opens the budget detail. Reuses the list; revisit D45 (new read). Alternatives: drill-down sheet in the Leitura; tag history 6 months reusing the Verbas por mês chart. |
| R5 | Comparing months (4.2.8) | Delta against the previous month on each ranking row ("+ R$ 40 vs set"). Alternatives: previous month ghost line in Ritmo; "Todas as verbas" in Verbas por mês (total × Σ cotas or × teto); 6-month average line. |
| R6 | Whole-month view (4.2.9) | Leave it in Verbas; label the Leitura sections ("todas as verbas" / budget name). Alternatives: "Todas" in the budget choice; a strip with each budget's state above Ritmo. |
| R7 | Procrastination in the Leitura (4.2.10) | Repeat the "N recados na Pauta" strip (M7) above Ritmo. Alternatives: "último lançamento em …"; nothing. |
| R8 | Calendar sentence (4.1.5) | Mean per occurrence of the weekday, only days up to today in the current month, threshold (≥ 2 days with spending, else "Nenhum dia pesa."). Alternatives: only fix the wording for a single day; look at several months. |
| R9 | Sentence wording (4.2.12) | "acima/abaixo do ideal da cota livre", "sobre" → "acima" as in Verbas, plus the action "cabem R$ X por dia até o fim do mês". |
| R10 | Tap on charts (4.3.14) | Drop it from the book for v1 (§7.5 doesn't have it); fix Fig. 9.4 to "Ver todos". |
| R11 | Month start day rewriting the past (4.2.11) | Document it in spec §3 (the past is re-read with the current start day). Alternative: store each month's window. |

Tasks without a decision (do after R1–R3, which change what they test): TalkBack sentence and calendar summary (4.1.6), title "Calendário de gastos" and budget name in Verbas por mês (4.3.15, 4.2.9), book 9.2/10.6 (4.3.13), LeituraInvariantsTest oracle and the missing previsto tests (4.3.16), check the day-1 window edge (4.3.17).
