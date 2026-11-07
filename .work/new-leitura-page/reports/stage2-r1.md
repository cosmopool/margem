# Stage 2, round 1 — new-leitura-page

Base ref: `a5980d613849e903e248650a2c9d0896011b999e`. Five findings came in. None was refuted, and all five were reproduced with a failing test. Three are fixed this round, one is only reported, and one becomes a follow-up.

**Base-check caveat.** Each log in `reports/base-check/` contains only `scripts/pipeline/gradle.sh: No such file or directory`, so the repro tests never ran against the base worktree. `gradle.sh` is still untracked in the main checkout. The verdicts below therefore rest on the code itself: findings 1–4 use code that is new in this diff (`leituraLines`, `earlier()`, `leituraTo`, the NonCancellable reader), so they cannot exist on base. Finding 5 was classed pre-existing. Re-run base-check once `gradle.sh` is reachable from the base worktree.

| # | Finding | Lens | Survived refutation | Chance | Criticality | Silent | Repro | Base-check | Decision |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Total e costume / O que cresceu count future parcelas against stretches cut at today (Insights.kt:782, 1121) | correctness | yes | common | major | yes | reproduced (r1_1, TotalWidget only) | feature | fix |
| 2 | `earlier()` runs past the end of a shorter earlier month (Insights.kt:389) | correctness | yes | occasional | major | yes | reproduced (r1_2) | feature | fix |
| 3 | Cancelled reads still run their SQL after the NonCancellable reader receive (Db.kt:128) | db | yes | occasional | minor | no | reproduced (r1_3) | feature | fix |
| 4 | Desfazer of "Só X" restores the old query after the tab was left and reopened (MainActivity.kt:280, AC-F4) | state | yes | rare | minor | no | reproduced (r1_4) | feature | report |
| 5 | Semanas / Mês a mês columns have no TalkBack labels (Insights.kt:685) | state | yes | occasional | major | no | reproduced (r1_5) | pre-existing | follow-up |

## 1. Future parcelas inflate Total e costume and O que cresceu

`lines` holds every installment in the whole interval (Db.kt:1050 does not cap at today), but `earlier()` cuts each earlier interval at today's day. In TotalWidget, `now` therefore includes parcelas that are not due yet, while `before` does not. GrewWidget has the same mismatch. Versus and WeekdayWidget already cap at today, so this is an inconsistency, not a design choice. It breaks the glossary's definition of Costume and AC-28/AC-34.

**Survived refutation:** nothing upstream caps the lines at today, and no test checks the costume numbers when a parcela is dated in the future.

**Repro:** `Repro_new-leitura-page_r1_1Test.kt` runs through the UI. Both 1–6 Oct and 1–6 Nov have R$ 50, and a parcela of R$ 100 is dated 20 Nov. The sentence reads "R$ 100,00 acima do costume" when it should read "igual ao costume". Only TotalWidget was tested, not GrewWidget.

**Decision:** fix, because it is common and major. When the interval holds today, filter the current side to dates up to today in both widgets (about 4 lines).

## 2. `earlier()` overflows into the current month

When the interval holds today, the cut is `from + daysUntil(today)`, and it is never capped at the end of the earlier window. On 31 Dec this gives 1 Nov + 30 = 1 Dec, so 1 Dec is counted both as now and as "anterior". The same happens at the end of Mar, May, Jul, Oct and Dec.

**Survived refutation:** nothing clamps the date. The only AC-23 test checks day 6, where there is no overflow.

**Repro:** `Repro_new-leitura-page_r1_2Test.kt` sets today to 31/12/2026, with R$ 50 on 5/11 and R$ 50 on 1/12. It shows "R$ 50,00 abaixo do costume" when it should show "igual ao costume".

**Decision:** fix, because it is silent and major, and the change is one `minOf(..., end)` (about 2 lines).

## 3. Cancelled reads still run their SQL

`read` takes a reader under `withContext(NonCancellable)` and then runs `BEGIN`/`block`/`COMMIT` without checking for cancellation. A Leitura read that was waiting when a `version` bump cancelled it still runs its stale `leituraLines` query on one of the two readers, which delays the new generation of reads.

**Survived refutation:** the effect is limited to one stale query per waiting widget, it fixes itself, and it only costs speed. That is minor and not silent.

**Repro:** `Repro_new-leitura-page_r1_3Test.kt` uses the real Db and the bundled driver. It fails with "the cancelled Leitura read still ran its SQL on a reader expected:<0>".

**Decision:** fix. The decision came from `workflow.fix_matrix`, and the fix is one line: `currentCoroutineContext().ensureActive()` inside the outer `try`, before `BEGIN`.

## 4. Desfazer after leaving and reopening the tab

The Desfazer lambda only checks `stack.first() == Screen.Insights`. Doing Só X, then Verbas, then Leitura, then Desfazer within the snack's 6 s restores the query from before the tab change, which breaks D-5 and AC-F4.

**Survived refutation:** the path is real. The existing AC-F4 test taps Desfazer while Verbas is still open, so it does not cover this.

**Repro:** `Repro_new-leitura-page_r1_4Test.kt` ends with `chips=[Delivery, ✕]` when it should end with no chips.

**Decision:** report. It is rare and minor, and the result is visible. A possible fix is to compare `leitura == q` in the lambda instead of checking the top screen.

## 5. Chart columns have no accessibility labels

Columns draws its labels only inside a Canvas with no semantics, and puts one clickable Box per bar on top with no contentDescription. TalkBack hears 6 unnamed targets on Mês a mês. The old HISTORY_CHART had a description.

**Survived refutation:** no semantics test exists, and the claim matches the code.

**Repro:** `Repro_new-leitura-page_r1_5Test.kt` fails with "6 de 16 alvos tocáveis da página Mês a mês não têm texto nem contentDescription".

**Base-check:** classed pre-existing. This verdict is doubtful: the code is new in this diff, and base-check never actually ran (see the caveat at the top).

**Decision:** follow-up, as pipeline policy requires for pre-existing bugs. The suggested fix is a per-column `contentDescription` of label plus value, and a summary description on the Canvas.
