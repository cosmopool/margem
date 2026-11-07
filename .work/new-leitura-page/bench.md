# Stage 3 bench: new-leitura-page

## What was measured, and why

The perf signals point at `ui/Insights.kt`, which has three kinds of cost:

- **Canvas / text-measure** (Insights.kt:41, :311, :596, :666-:704, :840, :942; Components.kt:1013). These are draw-phase
  calls over at most 7x6 cells, 12 columns or one day per point of a month. Measuring them here would mean benchmarking
  Compose itself. **Not measured.**
- **Loops in composables** (Insights.kt:226-1371, MainActivity.kt:165). These are groupings and sums over the
  `leituraLines` rows of the interval: 88 rows for a month and 733 for 6 months at five-year volume (below). Kotlin 2.3.20
  strong skipping means the scroll-driven recomposition of `Insights` (it reads `scroll.value`) skips the `Widget` calls,
  because their arguments keep the same instances. The grid-assembly loop (Insights.kt:289-302) goes over at most
  18 ids. **Not measured separately.** At these sizes it is noise next to the reads below.
- **Db reads per grid open (D-9: one `query(db)` per widget).** This is the cost a user actually waits on, and it runs
  again on every `db.version` bump while the tab is open. **Measured.**

## Harness

`/home/arrow/Development/margem/.work/new-leitura-page/bench/LeituraBenchTest.kt` is scratch code and was not
committed. It copies VolumeTest's five-year generation (seed 49, through the public API), then runs every `query(db)`
block that the grid starts for a query with default widget options. Those blocks are Insights.kt:237 `load`,
:278 `extra`, and the Widget bodies, using copies of the private `earlier`/`plan`/`firstTimers`/`leftOut`. Each read is
timed as the median of 5 runs after 2 warm-ups (VolumeTest's method). The base row replays the old Leitura's single block
(a5980d6 Insights.kt:272-291).

Command, run from the repo root:

```
cp .work/new-leitura-page/bench/LeituraBenchTest.kt shared/src/commonTest/kotlin/com/kaiodelphino/margem/ \
  && ./gradlew --console=plain :shared:jvmTest --tests '*.LeituraBenchTest' -i 2>&1 | grep -E "LBENCH|VOLUME" ; \
  rm shared/src/commonTest/kotlin/com/kaiodelphino/margem/LeituraBenchTest.kt
```

Machine: JVM on the 16-core dev sandbox with BundledSQLiteDriver. A phone will be slower, so treat these as relative
numbers. Raw log: `.work/new-leitura-page/bench/run2.log`. Run 1 matches it within about 10%.

Data: 5608 notices (4853 accepted, 635 dismissed, 22 pending), 110 refunds, 8 budgets, 22 tags, 7866 installment rows.

## Raw numbers (run 2, ms, median of 5)

| Read | Rows | ms |
|---|---|---|
| `leituraLines` month | 88 | 2.2 |
| `leituraLines` 6 months | 733 | 9.9 |
| `leituraLines` all (5y) | 7497 | 60.9 |
| `firstTimers` history read: month, 86 chips OR'd, `from = 0001-01-01` | 7434 | 76.5 |
| `leftOut`: full `transactions()` + `dismissed()` + `pending()`, filtered in Kotlin | n/a | 55.6 |
| one `budgetDetail` (+ overview) | n/a | 21.9 |
| **Base Leitura open (a5980d6, one block)** | n/a | **296.5** |

Grid blocks, default options:

| Block (Insights.kt) | month | 6 months |
|---|---|---|
| load (:237) | 13.2 | 20.2 |
| extra = firstTimers + leftOut (:278) | 127.0 | 120.0 |
| margem (:747) | 14.2 | 8.2 |
| total, 6x earlier (:782) | 9.3 | 69.1 |
| ritmo, plan (:807) | 8.2 | 0.2 |
| previstos, overview + 8x budgetDetail (:973) | 90.6 | 87.5 |
| fora, leftOut (:1027) | 49.8 | 48.8 |
| cresceu, 3x earlier (:1122) | 5.0 | 27.5 |
| voltam, firstTimers (:1147) | 74.5 | 65.7 |
| primeira-vez, firstTimers (:1162) | 73.2 | 64.4 |
| dia-da-semana (:1237) | 0.1 | 0.1 |
| semanas (:1254) | 9.3 | 0.3 |
| mes-a-mes, 6 (:1274) | 6.9 | 6.9 |
| meses-a-frente, 3: overview + 8x budgetDetail (:1307) | 111.5 | 88.6 |
| volta-todo-mes, 6 (:1330) | 15.9 | 7.8 |
| **sequential sum, 15 blocks** | **608.7** | **615.5** |
| **concurrent wall, all blocks at once, 2 readers** | **409.5** | **393.2** |

## Reading

- One grid open costs about 400 ms of wall time (610 ms of reader time). The base Leitura costs 296 ms, so the new grid
  takes about 1.35x the wall time and 2x the reader time. The base was already dominated by `history(all months)`, so
  the grid is not an order-of-magnitude regression.
- The same work is repeated within one open:
  - `firstTimers(q, lines)` runs 3 times with identical input (:278, :1147, :1162), about 75 ms each. That is about
    150 ms of duplicate reads. Each run reads the whole history before the interval (7434 rows), because the chips
    OR'd are every merchant and tag of the interval.
  - `leftOut(q)` runs twice (:278, :1027), about 50 ms each, about 50 ms duplicate. It loads every transaction, dismissed
    notice and pending notice to keep only those dated in the interval.
  - `budgetsOverview` + `budgetDetail` for all 8 budgets runs twice (previstos :973 and meses-a-frente :1307), about
    90-110 ms each.

  Together the repeated blocks are about 200 ms of the 610 ms of reader time. Sharing them inside the existing `extra` block, or between the two
  previstos widgets, is the only gain backed by these numbers.
- `leituraLines` itself scales linearly: 2 ms per month, 61 ms for 5 years. D-9 asks for a VolumeTest ceiling on
  `leituraLines`, and this feature did not add one (VolumeTest.kt is not in the diff).

## Proposals

### P1: compute `firstTimers` and `leftOut` once per grid open and pass them to the three widgets (Insights.kt)

- **Measured before:** grid open, current month: 608.7 ms reader time and 409.5 ms wall. `firstTimers` runs 3x with
  identical input (:278, :1147 voltam 74.5 ms, :1162 primeira-vez 73.2 ms). `leftOut` runs 2x (:278, :1027 fora
  49.8 ms). The two widget-side copies add up to 197.5 ms per open (6 months: 65.7 + 64.4 + 48.8 = 178.9 ms). They run
  again on every `db.version` bump while the tab is open.
- **Change:** the `extra` block at :278 already computes both values and throws them away after `isNotEmpty()`. Keep
  the lists: `firstTimers(d, q, lines) to leftOut(d, q)`. Derive `gone` from `isNullOrEmpty()`, which keeps today's rule
  that a null `extra` hides primeira-vez and fora. Give `Widget` one trailing parameter
  `extra: Pair<List<Chip>, List<String>>? = null`. The grid call (:303) passes it. The page call (:344) passes nothing,
  so a widget's own page still reads for itself. `LeftOutWidget`, `ReturnWidget` and `FirstWidget` use
  `extra?.first/second ?: query(...)` in place of their own unconditional `query`. No new type, no cache, no new file.
- **Expected after:** reader time per open of about 411 ms for the current month (608.7 - 197.5) and about 437 ms for
  6 months (615.5 - 178.9). Wall time should drop by roughly half the removed reader time, because there are 2 readers,
  so about 310 ms against 409.5 ms. That puts the grid about level with the base Leitura (296.5 ms). Re-run the same
  LeituraBenchTest after the change to confirm. Side effect: voltam and primeira-vez now wait for `extra` (127 ms)
  instead of their own read (about 75 ms). They already appear in the same frame as the `gone` decision that lays out
  the grid, so nothing visible moves later than it does now.
- **Added lines:** about 14 changed/added lines and no new symbols: 3 in `Insights` (extra, the two `gone` lines),
  1 grid call, 1 `Widget` signature, 3 dispatch lines, and 2 each in the three widgets (signature + read line).
- **Evidence:** run2.log rows `extra` (:278), `voltam` (:1147), `primeira-vez` (:1162), `fora` (:1027), and the
  sequential-sum and concurrent-wall rows.

### Considered and not proposed

- **Share overview + 8x `budgetDetail` between previstos (:973, 90.6 ms) and meses-a-frente (:1307, 111.5 ms).** They read
  different months: `monthOf(q.to)` against `monthOf(today)`. They coincide only when the interval ends in today's
  month. Sharing would mean hoisting a second read into `Insights` and branching on that coincidence in both widgets,
  which is more surface than P1 for one case. Not proposed.
- **Replace the `firstTimers` history read (76.5 ms, 7434 rows) with a `MIN(date) GROUP BY` query in `Db`.** After P1
  it runs once per open. The win from a new SQL aggregate was not measured, and it costs a new `Db` symbol plus SQL
  across merchants and tags. No number backs the expected-after, so it is not proposed.
- **`leftOut` loading all `transactions()` to filter by date (55.6 ms).** After P1 it runs once. Narrowing it needs new
  `Db` queries, and the gain is unmeasured. Not proposed.
- **`leituraLines` (2.2 / 9.9 / 60.9 ms).** It scales linearly, and for the grid's interval it is 2-10 ms. There is
  nothing to gain there.

## After P1 (implemented)

**Change, Insights.kt:** `extra` (:278) now keeps the lists, `firstTimers(d, q, lines) to leftOut(d, q)`. `gone` uses
`isNullOrEmpty()`, so a null `extra` still hides primeira-vez and fora. `Widget` takes a trailing
`extra: Pair<List<Chip>, List<String>>?`: the grid passes its `extra` and the page passes `null`. `LeftOutWidget`,
`ReturnWidget` and `FirstWidget` choose by call site, not by whether extra is null:
`if (big) query(...) else extra?.… ?: return`. As a result voltam starts no read of its own on the frame where `data`
arrives before `extra`. It waits for `extra`, as primeira-vez and fora already did. The page (`big = true`) still reads
for itself.

**Harness:** the same `LeituraBenchTest.kt`, with the `fora(leftOut)`, `voltam(firstTimers)` and
`primeira-vez(firstTimers)` blocks removed, because the grid no longer starts them. Same machine, same command. Raw log:
`.work/new-leitura-page/bench/run3.log`.

| Grid open | before (run 2) | predicted | after (run 3) |
|---|---|---|---|
| month, sequential reader time (blocks) | 608.7 ms (15) | ~411 ms | **407.8 ms (12)** |
| month, concurrent wall, 2 readers | 409.5 ms | ~310 ms | **238.6 ms** |
| 6 months, sequential reader time (blocks) | 615.5 ms (15) | ~437 ms | **496.2 ms (12)** |
| 6 months, concurrent wall, 2 readers | 393.2 ms | ~310 ms | **282.2 ms** |
| base Leitura open (a5980d6), same run | 296.5 ms | n/a | 286.8 ms |

- The gain is delivered. The voltam, primeira-vez and fora rows are gone from the log, and `extra` is unchanged
  (124.0 / 119.6 ms against 127.0 / 120.0 ms). Wall time per open falls by 171 ms for the month and 111 ms for
  6 months. That is below the predicted ~310 ms, and below the base Leitura's 287 ms on the same run.
- The 6-month reader sum (496 ms) came in above the prediction (437 ms) because of run-to-run noise in blocks P1 does not
  touch: previstos 87.5 → 112.3 ms, meses-a-frente 88.6 → 113.7 ms. The 3 removed blocks account for 178.9 ms, as
  predicted.
- Kept. Checks: `scripts/pipeline/gradle.sh analyze` exited 0. `ac-map.sh --slug new-leitura-page --expect green`
  exited 0 on 3 consecutive runs. Two earlier runs exited 1 with `IllegalArgumentException: performMeasureAndLayout
  called during measure layout` from Robolectric, in a different test each time (ReservaUiTest teardown, LeituraUiTest
  :428). One of them, ReservaUiTest `a month past its cotas reads alem in the total`, never opens the Leitura, so that
  failure is not from this change. Each test passed on its own.
