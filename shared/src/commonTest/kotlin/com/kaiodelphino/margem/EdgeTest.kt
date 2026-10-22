package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Edge cases of dates, months and weekly steps (spec §3–§6, architecture D7–D11, D29–D30).
 * Calendar facts used below: 2025-11-01 Sat · 2026-02-28 Sat · 2026-09-01 Tue · 2026-10-01 Thu ·
 * 2026-11-01 Sun · 2026-11-30 Mon · 2026-12-01 Tue · 2027-02-01 Mon. Default settings: month day 1, week Monday.
 */
class EdgeTest {

    private fun withDb(block: suspend (Db) -> Unit): TestResult = runTest {
        val path = Path(SystemTemporaryDirectory, "margem-edge-${Random.nextLong().toULong()}.db").toString()
        val db = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))
        try {
            block(db)
        } finally {
            db.close()
            for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
        }
    }

    private fun day(y: Int, m: Int, d: Int) = LocalDate(y, m, d)
    private fun ym(y: Int, m: Int) = YearMonth(y, m)

    private suspend fun Db.spend(budget: BudgetId, cents: Long, date: LocalDate, installments: Int = 1): TransactionId {
        val form = TransactionForm(
            budget = budget, tags = listOf("teste"), total = Cents(cents), installments = installments,
            merchant = "Loja", date = date, time = LocalTime(12, 0),
        )
        return addManual(form).transaction.also { assertNotEquals(0L, it.v, "addManual refused $cents on $date") }
    }

    /** ensureMonths + budgetDetail, as a screen does; also checks Σ steps = limit (spec §4, D11). */
    private suspend fun Db.month(id: BudgetId, month: YearMonth, today: LocalDate): BudgetMonth {
        ensureMonths(month)
        val m = budgetDetail(id, month, today).month
        assertEquals(m.limit.v, m.weeks.sumOf { it.step.v }, "Σ steps must equal the limit in $month")
        return m
    }

    private data class Wk(val start: LocalDate, val end: LocalDate, val step: Long, val edited: Boolean = false)

    private fun weeks(m: BudgetMonth) = m.weeks.map { Wk(it.start, it.end, it.step.v, it.edited) }

    /** month → (limit, spent) of one budget. */
    private suspend fun Db.historyOf(id: BudgetId, from: YearMonth, to: YearMonth): Map<YearMonth, Pair<Long, Long>> =
        history(from, to).filter { it.budget == id }.associate { it.month to (it.limit.v to it.spent.v) }

    // ---------------------------------------------------------------- Month windows (spec §3)

    @Test
    fun `month start day 31 clamps to the last day of short months in monthOf`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2026, 3, 5)))
        // D=31: Dec 31..Jan 30 · Jan 31..Feb 27 (Feb 31 → Feb 28, eve = 27) · Feb 28..Mar 30 ·
        // Mar 31..Apr 29 (Apr 31 → Apr 30) · Apr 30..May 30 · May 31..Jun 29.
        assertEquals(ym(2025, 12), db.monthOf(day(2026, 1, 30)))
        assertEquals(ym(2026, 1), db.monthOf(day(2026, 1, 31)))
        assertEquals(ym(2026, 1), db.monthOf(day(2026, 2, 27)))
        assertEquals(ym(2026, 2), db.monthOf(day(2026, 2, 28)))
        assertEquals(ym(2026, 2), db.monthOf(day(2026, 3, 30)))
        assertEquals(ym(2026, 3), db.monthOf(day(2026, 3, 31)))
        assertEquals(ym(2026, 3), db.monthOf(day(2026, 4, 29)))
        assertEquals(ym(2026, 4), db.monthOf(day(2026, 4, 30)))
        assertEquals(ym(2026, 4), db.monthOf(day(2026, 5, 30)))
        assertEquals(ym(2026, 5), db.monthOf(day(2026, 5, 31)))
        assertEquals(ym(2026, 5), db.monthOf(day(2026, 6, 29)))
        assertEquals(ym(2026, 6), db.monthOf(day(2026, 6, 30)))
    }

    @Test
    fun `month start day 31 windows run from the clamped day to the eve of the next one`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2026, 2, 10)))
        val id = db.createBudget("Mercado", Cents(31000), day(2026, 2, 10)) // Feb 10 is in the Jan month
        assertNotEquals(0L, id.v)
        val expected = mapOf(
            ym(2026, 1) to (day(2026, 1, 31) to day(2026, 2, 27)),
            ym(2026, 2) to (day(2026, 2, 28) to day(2026, 3, 30)),
            ym(2026, 3) to (day(2026, 3, 31) to day(2026, 4, 29)),
            ym(2026, 4) to (day(2026, 4, 30) to day(2026, 5, 30)),
            ym(2026, 5) to (day(2026, 5, 31) to day(2026, 6, 29)),
        )
        for ((month, window) in expected) {
            val m = db.month(id, month, day(2026, 2, 10))
            assertEquals(month, m.month)
            assertEquals(window, m.start to m.end, "window of $month")
            assertEquals(Cents(31000), m.limit)
        }
    }

    @Test
    fun `month start day 29 and 30 handle February in leap and non-leap years`() = withDb { db ->
        assertTrue(db.setMonthStartDay(29, day(2027, 1, 10)))
        // 2027 (non-leap), D=29: Jan 29..Feb 27 (Feb 29 → Feb 28) · Feb 28..Mar 28 · Mar 29..
        assertEquals(ym(2027, 1), db.monthOf(day(2027, 2, 27)))
        assertEquals(ym(2027, 2), db.monthOf(day(2027, 2, 28)))
        assertEquals(ym(2027, 2), db.monthOf(day(2027, 3, 28)))
        assertEquals(ym(2027, 3), db.monthOf(day(2027, 3, 29)))
        // 2028 (leap), D=29: Jan 29..Feb 28 · Feb 29..Mar 28.
        assertEquals(ym(2028, 1), db.monthOf(day(2028, 2, 28)))
        assertEquals(ym(2028, 2), db.monthOf(day(2028, 2, 29)))
        assertEquals(ym(2028, 2), db.monthOf(day(2028, 3, 28)))
        assertEquals(ym(2028, 3), db.monthOf(day(2028, 3, 29)))

        assertTrue(db.setMonthStartDay(30, day(2027, 1, 10)))
        // 2027, D=30: Jan 30..Feb 27 · Feb 28..Mar 29 · Mar 30..
        assertEquals(ym(2027, 1), db.monthOf(day(2027, 2, 27)))
        assertEquals(ym(2027, 2), db.monthOf(day(2027, 2, 28)))
        assertEquals(ym(2027, 2), db.monthOf(day(2027, 3, 29)))
        assertEquals(ym(2027, 3), db.monthOf(day(2027, 3, 30)))
        // 2028, D=30: Jan 30..Feb 28 · Feb 29..Mar 29.
        assertEquals(ym(2028, 1), db.monthOf(day(2028, 2, 28)))
        assertEquals(ym(2028, 2), db.monthOf(day(2028, 2, 29)))
        assertEquals(ym(2028, 2), db.monthOf(day(2028, 3, 29)))
        assertEquals(ym(2028, 3), db.monthOf(day(2028, 3, 30)))

        val id = db.createBudget("Casa", Cents(10000), day(2028, 2, 1)) // Jan 2028 month (D=30)
        assertEquals(day(2028, 1, 30) to day(2028, 2, 28), db.month(id, ym(2028, 1), day(2028, 2, 1)).let { it.start to it.end })
        assertEquals(day(2028, 2, 29) to day(2028, 3, 29), db.month(id, ym(2028, 2), day(2028, 2, 1)).let { it.start to it.end })
    }

    @Test
    fun `month start day outside 1 to 31 is refused`() = withDb { db ->
        assertFalse(db.setMonthStartDay(0, day(2026, 10, 14)))
        assertFalse(db.setMonthStartDay(32, day(2026, 10, 14)))
        assertEquals(1, db.settings().monthStartDay)
        assertTrue(db.setMonthStartDay(31, day(2026, 10, 14)))
        assertEquals(31, db.settings().monthStartDay)
    }

    @Test
    fun `transactions on the boundary days fall in the month their date belongs to`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2026, 2, 10)))
        val id = db.createBudget("Mercado", Cents(31000), day(2026, 2, 10))
        db.ensureMonths(ym(2026, 3))
        val lastOfJan = db.spend(id, 1000, day(2026, 2, 27)) // last day of the Jan month
        val firstOfFeb = db.spend(id, 2000, day(2026, 2, 28)) // first day of the Feb month
        db.spend(id, 4000, day(2026, 3, 30)) // last day of the Feb month
        db.spend(id, 8000, day(2026, 3, 31)) // first day of the Mar month

        val jan = db.budgetDetail(id, ym(2026, 1), day(2026, 3, 31))
        assertEquals(Cents(1000), jan.month.spent)
        assertEquals(Cents(30000), jan.month.remaining)
        assertEquals(listOf(lastOfJan), jan.lines.map { it.row.id })
        val feb = db.budgetDetail(id, ym(2026, 2), day(2026, 3, 31))
        assertEquals(Cents(6000), feb.month.spent) // 20 + 40
        assertTrue(firstOfFeb in feb.lines.map { it.row.id })
        assertEquals(Cents(8000), db.budgetDetail(id, ym(2026, 3), day(2026, 3, 31)).month.spent)
    }

    // ---------------------------------------------------------------- Turnover and skipped months (spec §3, D8, D30)

    @Test
    fun `turnover restarts the full limit and neither surplus nor excess carries over`() = withDb { db ->
        val id = db.createBudget("Lazer", Cents(50000), day(2026, 10, 1))
        db.ensureMonths(ym(2026, 11))
        db.spend(id, 53000, day(2026, 10, 31)) // last day of Oct: R$ 30 above the limit
        db.spend(id, 5000, day(2026, 11, 1)) // first day of Nov

        val oct = db.month(id, ym(2026, 10), day(2026, 11, 2))
        assertEquals(Cents(53000), oct.spent)
        assertEquals(Cents(-3000), oct.remaining) // 500 − 530
        assertEquals(PaceStatus.OVERRUN, oct.status)
        val nov = db.month(id, ym(2026, 11), day(2026, 11, 2))
        assertEquals(Cents(50000), nov.limit)
        assertEquals(Cents(5000), nov.spent)
        assertEquals(Cents(45000), nov.remaining) // 500 − 50, Oct's excess is not carried
    }

    @Test
    fun `spending on the last day of a week and the first of the next lands in each own week`() = withDb { db ->
        val id = db.createBudget("Lazer", Cents(60000), day(2026, 11, 1))
        db.spend(id, 500, day(2026, 11, 1)) // Sun: the 1-day first week
        db.spend(id, 700, day(2026, 11, 2)) // Mon: next week
        db.spend(id, 300, day(2026, 11, 29)) // Sun: last day of Nov 23–29
        db.spend(id, 400, day(2026, 11, 30)) // Mon: the 1-day last week
        val spent = db.month(id, ym(2026, 11), day(2026, 11, 30)).weeks.map { it.spent.v }
        assertEquals(listOf(500L, 700L, 0L, 0L, 300L, 400L), spent)
    }

    @Test
    fun `months skipped while the app was closed are filled by ensureMonths with the same limit`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 1, 15))
        // Without ensureMonths the later months have no limit row and are absent from reads (contract).
        assertEquals(emptyList(), db.budgetsOverview(ym(2026, 5), day(2026, 5, 10), BudgetOrder.RISK))
        assertEquals(setOf(ym(2026, 1)), db.historyOf(id, ym(2026, 1), ym(2026, 5)).keys)

        val version = db.version.value
        db.ensureMonths(ym(2026, 5))
        assertEquals(version, db.version.value, "ensureMonths does not bump version (D30)")

        val history = db.historyOf(id, ym(2025, 11), ym(2026, 5))
        // Months before the first row stay without one; Jan..May all R$ 600.
        assertEquals((1..5).associate { ym(2026, it) to (60000L to 0L) }, history)
        val overview = db.budgetsOverview(ym(2026, 5), day(2026, 5, 10), BudgetOrder.RISK)
        assertEquals(listOf(id), overview.map { it.id })
        assertEquals(Cents(60000), overview.single().month.limit)
    }

    // ---------------------------------------------------------------- Limit changes (spec §3, §4)

    @Test
    fun `a new limit applies from today's month onward and past months keep theirs`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 1, 15))
        db.ensureMonths(ym(2026, 5)) // Apr and May already materialized before the change
        assertTrue(db.setLimit(id, Cents(90000), day(2026, 3, 10)))
        db.ensureMonths(ym(2026, 7)) // app closed, reopened in July
        val expected = mapOf(
            ym(2026, 1) to (60000L to 0L), ym(2026, 2) to (60000L to 0L),
            ym(2026, 3) to (90000L to 0L), ym(2026, 4) to (90000L to 0L), ym(2026, 5) to (90000L to 0L),
            ym(2026, 6) to (90000L to 0L), ym(2026, 7) to (90000L to 0L),
        )
        assertEquals(expected, db.historyOf(id, ym(2026, 1), ym(2026, 7)))
    }

    @Test
    fun `a new limit set after skipped months keeps the old limit in the skipped ones`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 1, 15))
        // No ensureMonths for Feb or Mar: the app was closed; the limit is changed on Mar 10.
        assertTrue(db.setLimit(id, Cents(90000), day(2026, 3, 10)))
        db.ensureMonths(ym(2026, 3))
        // "Cada mês guarda a cota que valia nele": Feb was under R$ 600.
        val expected = mapOf(ym(2026, 1) to (60000L to 0L), ym(2026, 2) to (60000L to 0L), ym(2026, 3) to (90000L to 0L))
        assertEquals(expected, db.historyOf(id, ym(2026, 1), ym(2026, 3)))
    }

    @Test
    fun `a limit of zero or less is refused and keeps the current one`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 5))
        assertFalse(db.setLimit(id, Cents(0), day(2026, 11, 5)))
        assertFalse(db.setLimit(id, Cents(-1), day(2026, 11, 5)))
        assertEquals(BudgetId(0), db.createBudget("Zero", Cents(0), day(2026, 11, 5)))
        assertEquals(Cents(60000), db.month(id, ym(2026, 11), day(2026, 11, 5)).limit)
    }

    @Test
    fun `a lower limit deletes step edits only in current and future months whose edits exceed it`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 10, 5))
        db.ensureMonths(ym(2026, 12))
        // Oct (past at the change): edits sum 500. Nov (current): 400. Dec (future): 500.
        assertTrue(db.setStep(id, ym(2026, 10), day(2026, 10, 5), Cents(30000)))
        assertTrue(db.setStep(id, ym(2026, 10), day(2026, 10, 12), Cents(20000)))
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 2), Cents(20000)))
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 9), Cents(20000)))
        assertTrue(db.setStep(id, ym(2026, 12), day(2026, 12, 7), Cents(25000)))
        assertTrue(db.setStep(id, ym(2026, 12), day(2026, 12, 14), Cents(25000)))

        assertTrue(db.setLimit(id, Cents(45000), day(2026, 11, 10)))

        val oct = db.month(id, ym(2026, 10), day(2026, 11, 10))
        assertEquals(Cents(60000), oct.limit)
        assertEquals(
            listOf(day(2026, 10, 5) to 30000L, day(2026, 10, 12) to 20000L),
            oct.weeks.filter { it.edited }.map { it.start to it.step.v },
        )
        // Nov: 400 ≤ 450, edits kept; the other weeks (1+7+7+1 = 16 days) share 50:
        // 1 day ⌊5000·1/16⌋ = 312, 7 days ⌊5000·7/16⌋ = 2187, last non-edited = 5000 − 312 − 2187 − 2187 = 314.
        assertEquals(
            listOf(
                Wk(day(2026, 11, 1), day(2026, 11, 1), 312),
                Wk(day(2026, 11, 2), day(2026, 11, 8), 20000, true),
                Wk(day(2026, 11, 9), day(2026, 11, 15), 20000, true),
                Wk(day(2026, 11, 16), day(2026, 11, 22), 2187),
                Wk(day(2026, 11, 23), day(2026, 11, 29), 2187),
                Wk(day(2026, 11, 30), day(2026, 11, 30), 314),
            ),
            weeks(db.month(id, ym(2026, 11), day(2026, 11, 10))),
        )
        // Dec: 500 > 450, edits deleted; 31 days, weeks 6·7·7·7·4:
        // ⌊45000·6/31⌋ = 8709, ⌊45000·7/31⌋ = 10161, last = 45000 − 8709 − 3·10161 = 5808.
        assertEquals(
            listOf(
                Wk(day(2026, 12, 1), day(2026, 12, 6), 8709),
                Wk(day(2026, 12, 7), day(2026, 12, 13), 10161),
                Wk(day(2026, 12, 14), day(2026, 12, 20), 10161),
                Wk(day(2026, 12, 21), day(2026, 12, 27), 10161),
                Wk(day(2026, 12, 28), day(2026, 12, 31), 5808),
            ),
            weeks(db.month(id, ym(2026, 12), day(2026, 11, 10))),
        )
    }

    @Test
    fun `a limit equal to the edited steps keeps them and a cent less resets the month`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 5))
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 2), Cents(20000)))
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 9), Cents(20000)))

        assertTrue(db.setLimit(id, Cents(40000), day(2026, 11, 10))) // not below the sum (spec: "abaixo")
        assertEquals(
            listOf(0L, 20000L, 20000L, 0L, 0L, 0L),
            db.month(id, ym(2026, 11), day(2026, 11, 10)).weeks.map { it.step.v },
        )

        assertTrue(db.setLimit(id, Cents(39999), day(2026, 11, 10)))
        // All automatic, 30 days: ⌊39999·1/30⌋ = 1333, ⌊39999·7/30⌋ = 9333, last = 39999 − 1333 − 4·9333 = 1334.
        val m = db.month(id, ym(2026, 11), day(2026, 11, 10))
        assertEquals(listOf(1333L, 9333L, 9333L, 9333L, 9333L, 1334L), m.weeks.map { it.step.v })
        assertTrue(m.weeks.none { it.edited })
    }

    @Test
    fun `a higher limit redistributes only the weeks not edited`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 5))
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 2), Cents(20000)))
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 9), Cents(20000)))
        assertTrue(db.setLimit(id, Cents(70000), day(2026, 11, 10)))
        // 700 − 400 = 300 over 1+7+7+1 = 16 days: 1 day 1875, 7 days 13125, last 30000 − 1875 − 2·13125 = 1875.
        assertEquals(
            listOf(1875L, 20000L, 20000L, 13125L, 13125L, 1875L),
            db.month(id, ym(2026, 11), day(2026, 11, 10)).weeks.map { it.step.v },
        )
    }

    // ---------------------------------------------------------------- Weekly steps (spec §4, D9, D11)

    @Test
    fun `default steps follow the spec example of a 30-day month with a 2-day first week`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2025, 11, 5))
        // Nov 2025 starts on Saturday: 600·7/30 = 140 per full week, 600·2/30 = 40 for the 2-day week.
        assertEquals(
            listOf(
                Wk(day(2025, 11, 1), day(2025, 11, 2), 4000),
                Wk(day(2025, 11, 3), day(2025, 11, 9), 14000),
                Wk(day(2025, 11, 10), day(2025, 11, 16), 14000),
                Wk(day(2025, 11, 17), day(2025, 11, 23), 14000),
                Wk(day(2025, 11, 24), day(2025, 11, 30), 14000),
            ),
            weeks(db.month(id, ym(2025, 11), day(2025, 11, 5))),
        )
    }

    @Test
    fun `1-day first and last weeks get a 1-day share`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 5))
        // Nov 2026: Sun 1 alone, four Monday weeks, Mon 30 alone. 600·1/30 = 20, 600·7/30 = 140.
        assertEquals(
            listOf(
                Wk(day(2026, 11, 1), day(2026, 11, 1), 2000),
                Wk(day(2026, 11, 2), day(2026, 11, 8), 14000),
                Wk(day(2026, 11, 9), day(2026, 11, 15), 14000),
                Wk(day(2026, 11, 16), day(2026, 11, 22), 14000),
                Wk(day(2026, 11, 23), day(2026, 11, 29), 14000),
                Wk(day(2026, 11, 30), day(2026, 11, 30), 2000),
            ),
            weeks(db.month(id, ym(2026, 11), day(2026, 11, 5))),
        )
    }

    @Test
    fun `weeks of a month starting on day 31 are cut across calendar months`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2026, 3, 5)))
        val id = db.createBudget("Mercado", Cents(31000), day(2026, 3, 5)) // Feb month: Feb 28..Mar 30, 31 days
        // R$ 10 a day: Sat–Sun 20, four Monday weeks 70, Mon 30 alone 10.
        assertEquals(
            listOf(
                Wk(day(2026, 2, 28), day(2026, 3, 1), 2000),
                Wk(day(2026, 3, 2), day(2026, 3, 8), 7000),
                Wk(day(2026, 3, 9), day(2026, 3, 15), 7000),
                Wk(day(2026, 3, 16), day(2026, 3, 22), 7000),
                Wk(day(2026, 3, 23), day(2026, 3, 29), 7000),
                Wk(day(2026, 3, 30), day(2026, 3, 30), 1000),
            ),
            weeks(db.month(id, ym(2026, 2), day(2026, 3, 5))),
        )
    }

    @Test
    fun `automatic steps round down and the last week takes the leftover cents`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(10000), day(2025, 11, 5))
        // Nov 2025, weeks 2·7·7·7·7 of 30 days: ⌊10000·2/30⌋ = 666, ⌊10000·7/30⌋ = 2333, last = 10000 − 666 − 3·2333 = 2335.
        assertEquals(listOf(666L, 2333L, 2333L, 2333L, 2335L), db.month(id, ym(2025, 11), day(2025, 11, 5)).weeks.map { it.step.v })
    }

    @Test
    fun `100 reais in 3 equal weeks split as 3333 3333 and 3334 cents`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(10001), day(2027, 2, 3))
        // Feb 2027 starts on Monday: four 7-day weeks. 100,01 → 25,00 ×3 + 25,01.
        assertEquals(listOf(2500L, 2500L, 2500L, 2501L), db.month(id, ym(2027, 2), day(2027, 2, 3)).weeks.map { it.step.v })

        assertTrue(db.setLimit(id, Cents(10000), day(2027, 2, 3)))
        assertTrue(db.setStep(id, ym(2027, 2), day(2027, 2, 1), Cents(0)))
        // Spec §4 example: R$ 100,00 left for 3 equal weeks → 33,33 · 33,33 · 33,34.
        assertEquals(
            listOf(
                Wk(day(2027, 2, 1), day(2027, 2, 7), 0, true),
                Wk(day(2027, 2, 8), day(2027, 2, 14), 3333),
                Wk(day(2027, 2, 15), day(2027, 2, 21), 3333),
                Wk(day(2027, 2, 22), day(2027, 2, 28), 3334),
            ),
            weeks(db.month(id, ym(2027, 2), day(2027, 2, 3))),
        )
    }

    @Test
    fun `editing a step redistributes the other weeks proportionally to their days`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2025, 11, 5))
        assertTrue(db.setStep(id, ym(2025, 11), day(2025, 11, 3), Cents(7000)))
        // 600 − 70 = 530 over 2+7+7+7 = 23 days: ⌊53000·2/23⌋ = 4608, ⌊53000·7/23⌋ = 16130,
        // last = 53000 − 4608 − 2·16130 = 16132.
        assertEquals(
            listOf(
                Wk(day(2025, 11, 1), day(2025, 11, 2), 4608),
                Wk(day(2025, 11, 3), day(2025, 11, 9), 7000, true),
                Wk(day(2025, 11, 10), day(2025, 11, 16), 16130),
                Wk(day(2025, 11, 17), day(2025, 11, 23), 16130),
                Wk(day(2025, 11, 24), day(2025, 11, 30), 16132),
            ),
            weeks(db.month(id, ym(2025, 11), day(2025, 11, 5))),
        )
    }

    @Test
    fun `when the last week is edited the last week not edited takes the leftover cents`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(10000), day(2025, 11, 5))
        assertTrue(db.setStep(id, ym(2025, 11), day(2025, 11, 24), Cents(3000)))
        // 100 − 30 = 70 over 2+7+7+7 = 23 days: ⌊7000·2/23⌋ = 608, ⌊7000·7/23⌋ = 2130,
        // Nov 17 (last not edited) = 7000 − 608 − 2·2130 = 2132.
        assertEquals(
            listOf(608L, 2130L, 2130L, 2132L, 3000L),
            db.month(id, ym(2025, 11), day(2025, 11, 5)).weeks.map { it.step.v },
        )
    }

    @Test
    fun `a step accepts at most the limit minus the other edited weeks`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2025, 11, 5))
        val nov = ym(2025, 11)
        assertFalse(db.setStep(id, nov, day(2025, 11, 1), Cents(60001)))
        assertTrue(db.setStep(id, nov, day(2025, 11, 1), Cents(10000)))
        assertFalse(db.setStep(id, nov, day(2025, 11, 3), Cents(50001))) // max 600 − 100
        assertTrue(db.setStep(id, nov, day(2025, 11, 3), Cents(50000)))
        // Automatic weeks share 0 and never go below R$ 0,00.
        assertEquals(listOf(10000L, 50000L, 0L, 0L, 0L), db.month(id, nov, day(2025, 11, 5)).weeks.map { it.step.v })
        assertFalse(db.setStep(id, nov, day(2025, 11, 10), Cents(1))) // max 600 − 100 − 500 = 0
        assertTrue(db.setStep(id, nov, day(2025, 11, 10), Cents(0)))
        // Re-editing a week: its own old value doesn't count against it. Max for Nov 1 = 600 − 500 − 0.
        assertFalse(db.setStep(id, nov, day(2025, 11, 1), Cents(10001)))
        assertTrue(db.setStep(id, nov, day(2025, 11, 1), Cents(0)))
        // Nov 17 and Nov 24 (7 days each) share 600 − 0 − 500 − 0 = 100.
        assertEquals(
            listOf(
                Wk(day(2025, 11, 1), day(2025, 11, 2), 0, true),
                Wk(day(2025, 11, 3), day(2025, 11, 9), 50000, true),
                Wk(day(2025, 11, 10), day(2025, 11, 16), 0, true),
                Wk(day(2025, 11, 17), day(2025, 11, 23), 5000),
                Wk(day(2025, 11, 24), day(2025, 11, 30), 5000),
            ),
            weeks(db.month(id, nov, day(2025, 11, 5))),
        )
    }

    @Test
    fun `the only week left not edited can't be edited`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2025, 11, 5))
        val nov = ym(2025, 11)
        assertTrue(db.setStep(id, nov, day(2025, 11, 1), Cents(4000)))
        assertTrue(db.setStep(id, nov, day(2025, 11, 3), Cents(14000)))
        assertTrue(db.setStep(id, nov, day(2025, 11, 10), Cents(14000)))
        assertTrue(db.setStep(id, nov, day(2025, 11, 24), Cents(14000)))
        // Nov 17 is the only automatic week left: refused even with its current value.
        assertFalse(db.setStep(id, nov, day(2025, 11, 17), Cents(14000)))
        assertFalse(db.setStep(id, nov, day(2025, 11, 17), Cents(0)))
        // An already edited week can still change; Nov 17 absorbs: 600 − 40 − 140 − 140 − 100 = 180.
        assertTrue(db.setStep(id, nov, day(2025, 11, 24), Cents(10000)))
        assertEquals(
            listOf(
                Wk(day(2025, 11, 1), day(2025, 11, 2), 4000, true),
                Wk(day(2025, 11, 3), day(2025, 11, 9), 14000, true),
                Wk(day(2025, 11, 10), day(2025, 11, 16), 14000, true),
                Wk(day(2025, 11, 17), day(2025, 11, 23), 18000),
                Wk(day(2025, 11, 24), day(2025, 11, 30), 10000, true),
            ),
            weeks(db.month(id, nov, day(2025, 11, 5))),
        )
    }

    @Test
    fun `a negative step is refused`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2025, 11, 5))
        assertFalse(db.setStep(id, ym(2025, 11), day(2025, 11, 3), Cents(-1)))
        val m = db.month(id, ym(2025, 11), day(2025, 11, 5))
        assertTrue(m.weeks.none { it.edited })
        assertEquals(listOf(4000L, 14000L, 14000L, 14000L, 14000L), m.weeks.map { it.step.v })
    }

    @Test
    fun `step edits belong to their month only`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 5))
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 2), Cents(0)))
        // Dec 2026 is untouched: weeks 6·7·7·7·4 of 31 days; ⌊60000·6/31⌋ = 11612, ⌊60000·7/31⌋ = 13548,
        // last = 60000 − 11612 − 3·13548 = 7744.
        val dec = db.month(id, ym(2026, 12), day(2026, 11, 5))
        assertTrue(dec.weeks.none { it.edited })
        assertEquals(listOf(11612L, 13548L, 13548L, 13548L, 7744L), dec.weeks.map { it.step.v })
    }

    @Test
    fun `steps always sum to the limit and weeks tile the month for every start day and week start`() = withDb { db ->
        val today = day(2027, 1, 15)
        val id = db.createBudget("Mercado", Cents(123457), today) // Jan 2027 under D=1
        db.ensureMonths(ym(2028, 12))
        for (startDay in listOf(1, 28, 29, 30, 31)) {
            assertTrue(db.setMonthStartDay(startDay, today))
            for (weekStart in DayOfWeek.entries) {
                db.setWeekStart(weekStart, today)
                var previousEnd: LocalDate? = null
                for (i in 0 until 23) { // Feb 2027 .. Dec 2028
                    val month = YearMonth(2027 + (1 + i) / 12, (1 + i) % 12 + 1)
                    val m = db.month(id, month, today) // also asserts Σ steps = limit
                    val where = "D=$startDay, week=$weekStart, $month"
                    assertEquals(Cents(123457), m.limit, where)
                    if (previousEnd != null) assertEquals(previousEnd.plus(DatePeriod(days = 1)), m.start, "months are contiguous: $where")
                    previousEnd = m.end
                    assertEquals(month, db.monthOf(m.start), where)
                    assertEquals(month, db.monthOf(m.end), where)
                    assertEquals(m.start, m.weeks.first().start, where)
                    assertEquals(m.end, m.weeks.last().end, where)
                    m.weeks.zipWithNext { a, b -> assertEquals(a.end.plus(DatePeriod(days = 1)), b.start, where) }
                    m.weeks.drop(1).forEach { assertEquals(weekStart, it.start.dayOfWeek, where) }
                    m.weeks.forEach {
                        assertTrue(it.step.v >= 0, where)
                        assertTrue(it.start <= it.end && it.end < it.start.plus(DatePeriod(days = 7)), where)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Changing month/week start (spec §4, D10)

    @Test
    fun `changing the week start deletes edits from the current month onward and keeps earlier matching ones`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 9, 2))
        db.ensureMonths(ym(2026, 11))
        // Monday weeks. Sep 1 (Tue) starts the first week under any week start; Sep 7 is a Monday.
        assertTrue(db.setStep(id, ym(2026, 9), day(2026, 9, 1), Cents(5000)))
        assertTrue(db.setStep(id, ym(2026, 9), day(2026, 9, 7), Cents(10000)))
        assertTrue(db.setStep(id, ym(2026, 10), day(2026, 10, 1), Cents(5000))) // month start: matches any week start
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 1), Cents(1000))) // a Sunday: matches the new weeks

        db.setWeekStart(DayOfWeek.SUNDAY, day(2026, 10, 14))
        assertEquals(DayOfWeek.SUNDAY, db.settings().weekStart)

        // Sep (past) is recomputed with Sunday weeks 5·7·7·7·4: the Sep 1 edit stays, the Sep 7 edit
        // matches no week and is ignored. 600 − 50 = 550 over 25 days: 7 days 15400, last 55000 − 3·15400 = 8800.
        assertEquals(
            listOf(
                Wk(day(2026, 9, 1), day(2026, 9, 5), 5000, true),
                Wk(day(2026, 9, 6), day(2026, 9, 12), 15400),
                Wk(day(2026, 9, 13), day(2026, 9, 19), 15400),
                Wk(day(2026, 9, 20), day(2026, 9, 26), 15400),
                Wk(day(2026, 9, 27), day(2026, 9, 30), 8800),
            ),
            weeks(db.month(id, ym(2026, 9), day(2026, 10, 14))),
        )
        // Oct (current), Sunday weeks 3·7·7·7·7, all automatic: ⌊60000·3/31⌋ = 5806, ⌊60000·7/31⌋ = 13548,
        // last = 60000 − 5806 − 3·13548 = 13550.
        assertEquals(
            listOf(
                Wk(day(2026, 10, 1), day(2026, 10, 3), 5806),
                Wk(day(2026, 10, 4), day(2026, 10, 10), 13548),
                Wk(day(2026, 10, 11), day(2026, 10, 17), 13548),
                Wk(day(2026, 10, 18), day(2026, 10, 24), 13548),
                Wk(day(2026, 10, 25), day(2026, 10, 31), 13550),
            ),
            weeks(db.month(id, ym(2026, 10), day(2026, 10, 14))),
        )
        // Nov (future), Sunday weeks 7·7·7·7·2, all automatic: 140 ×4, 40.
        assertEquals(
            listOf(
                Wk(day(2026, 11, 1), day(2026, 11, 7), 14000),
                Wk(day(2026, 11, 8), day(2026, 11, 14), 14000),
                Wk(day(2026, 11, 15), day(2026, 11, 21), 14000),
                Wk(day(2026, 11, 22), day(2026, 11, 28), 14000),
                Wk(day(2026, 11, 29), day(2026, 11, 30), 4000),
            ),
            weeks(db.month(id, ym(2026, 11), day(2026, 10, 14))),
        )
    }

    @Test
    fun `changing the month start day deletes edits from the old start of today's month when it is earlier`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 9, 2))
        db.ensureMonths(ym(2026, 10))
        assertTrue(db.setStep(id, ym(2026, 9), day(2026, 9, 14), Cents(10000)))
        assertTrue(db.setStep(id, ym(2026, 10), day(2026, 10, 5), Cents(10000)))

        // Today Oct 14: old month starts Oct 1, new one (D=10) Oct 10 → delete from Oct 1.
        assertTrue(db.setMonthStartDay(10, day(2026, 10, 14)))
        assertEquals(ym(2026, 9), db.monthOf(day(2026, 10, 9)))
        assertEquals(ym(2026, 10), db.monthOf(day(2026, 10, 10)))

        // Sep month is now Sep 10..Oct 9, weeks 4·7·7·7·5. Sep 14 stays; Oct 5 was deleted.
        // 600 − 100 = 500 over 4+7+7+5 = 23 days: ⌊50000·4/23⌋ = 8695, ⌊50000·7/23⌋ = 15217,
        // last = 50000 − 8695 − 2·15217 = 10871.
        assertEquals(
            listOf(
                Wk(day(2026, 9, 10), day(2026, 9, 13), 8695),
                Wk(day(2026, 9, 14), day(2026, 9, 20), 10000, true),
                Wk(day(2026, 9, 21), day(2026, 9, 27), 15217),
                Wk(day(2026, 9, 28), day(2026, 10, 4), 15217),
                Wk(day(2026, 10, 5), day(2026, 10, 9), 10871),
            ),
            weeks(db.month(id, ym(2026, 9), day(2026, 10, 14))),
        )
    }

    @Test
    fun `changing the month start day deletes edits from the new start of today's month when it is earlier`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 8, 15))
        db.ensureMonths(ym(2026, 10))
        assertTrue(db.setStep(id, ym(2026, 9), day(2026, 9, 7), Cents(10000)))
        assertTrue(db.setStep(id, ym(2026, 9), day(2026, 9, 14), Cents(10000)))

        // Today Oct 5: old month starts Oct 1, new one (D=10) is the Sep month from Sep 10 → delete from Sep 10.
        assertTrue(db.setMonthStartDay(10, day(2026, 10, 5)))
        assertEquals(ym(2026, 9), db.monthOf(day(2026, 10, 5)))

        // Aug month is now Aug 10..Sep 9, weeks 7·7·7·7·3. Sep 7 stays: 600 − 100 = 500 over 28 days → 125 each.
        assertEquals(
            listOf(
                Wk(day(2026, 8, 10), day(2026, 8, 16), 12500),
                Wk(day(2026, 8, 17), day(2026, 8, 23), 12500),
                Wk(day(2026, 8, 24), day(2026, 8, 30), 12500),
                Wk(day(2026, 8, 31), day(2026, 9, 6), 12500),
                Wk(day(2026, 9, 7), day(2026, 9, 9), 10000, true),
            ),
            weeks(db.month(id, ym(2026, 8), day(2026, 10, 5))),
        )
        // Sep month Sep 10..Oct 9 (30 days), Sep 14 deleted: 4 days 80, 7 days 140, 5 days 100.
        assertEquals(
            listOf(
                Wk(day(2026, 9, 10), day(2026, 9, 13), 8000),
                Wk(day(2026, 9, 14), day(2026, 9, 20), 14000),
                Wk(day(2026, 9, 21), day(2026, 9, 27), 14000),
                Wk(day(2026, 9, 28), day(2026, 10, 4), 14000),
                Wk(day(2026, 10, 5), day(2026, 10, 9), 10000),
            ),
            weeks(db.month(id, ym(2026, 9), day(2026, 10, 5))),
        )
    }

    // ---------------------------------------------------------------- Pace, status, projection (spec §5)

    @Test
    fun `on the first day the ideal is the first week's share of today`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 1))
        val empty = db.month(id, ym(2026, 11), day(2026, 11, 1))
        assertEquals(Cents(2000), empty.ideal) // 1-day week Nov 1: 20 × 1/1
        assertEquals(PaceStatus.ON_PACE, empty.status)
        assertEquals(Cents(0), empty.projection) // spent 0 → 0
        assertEquals(Cents(60000), empty.remaining)

        db.spend(id, 1000, day(2026, 11, 1))
        val m = db.month(id, ym(2026, 11), day(2026, 11, 1))
        assertEquals(PaceStatus.ON_PACE, m.status)
        assertEquals(Cents(30000), m.projection) // 10 × 600/20
    }

    @Test
    fun `exactly on pace is No ritmo and projects the limit while a cent above is Acima do ritmo`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 1))
        db.spend(id, 8000, day(2026, 11, 3))
        // Today Wed Nov 4: closed week Nov 1 = 20, current Nov 2–8 = 140 × 3/7 = 60 → ideal 80.
        val on = db.month(id, ym(2026, 11), day(2026, 11, 4))
        assertEquals(Cents(8000), on.ideal)
        assertEquals(PaceStatus.ON_PACE, on.status)
        assertEquals(Cents(60000), on.projection)

        db.spend(id, 1, day(2026, 11, 4))
        val ahead = db.month(id, ym(2026, 11), day(2026, 11, 4))
        assertEquals(Cents(8001), ahead.spent)
        assertEquals(PaceStatus.AHEAD, ahead.status)
        assertEquals(Cents(51999), ahead.remaining)
    }

    @Test
    fun `half the ideal projects half the limit`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 1))
        db.spend(id, 4000, day(2026, 11, 2))
        val m = db.month(id, ym(2026, 11), day(2026, 11, 4))
        assertEquals(Cents(30000), m.projection) // 40 × 600/80
        assertEquals(PaceStatus.ON_PACE, m.status)
    }

    @Test
    fun `on the last day the ideal is the whole limit and a cent over it is Fora da margem`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 1))
        db.spend(id, 60000, day(2026, 11, 30))
        val full = db.month(id, ym(2026, 11), day(2026, 11, 30))
        assertEquals(Cents(60000), full.ideal) // 580 closed + 20 × 1/1
        assertEquals(PaceStatus.ON_PACE, full.status)
        assertEquals(Cents(0), full.remaining)
        assertEquals(Cents(60000), full.projection)

        db.spend(id, 1, day(2026, 11, 30))
        val over = db.month(id, ym(2026, 11), day(2026, 11, 30))
        assertEquals(PaceStatus.OVERRUN, over.status)
        assertEquals(Cents(-1), over.remaining)
    }

    @Test
    fun `spending over the limit mid-month is Fora da margem with a negative margin`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 1))
        db.spend(id, 70000, day(2026, 11, 2))
        val m = db.month(id, ym(2026, 11), day(2026, 11, 4))
        assertEquals(PaceStatus.OVERRUN, m.status)
        assertEquals(Cents(-10000), m.remaining)
    }

    @Test
    fun `an ideal of zero means no projection`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 1))
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 1), Cents(0)))
        db.spend(id, 1000, day(2026, 11, 1))
        val m = db.month(id, ym(2026, 11), day(2026, 11, 1))
        assertEquals(Cents(0), m.ideal)
        assertEquals(Cents(0), m.projection)
        assertEquals(PaceStatus.AHEAD, m.status) // 10 > 0 and ≤ 600
    }

    @Test
    fun `the ideal follows the edited plan`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 1))
        assertTrue(db.setStep(id, ym(2026, 11), day(2026, 11, 2), Cents(7000)))
        // 530 over 1+7+7+7+1 = 23 days: Nov 1 = ⌊53000/23⌋ = 2304. Today Nov 4: 2304 + 7000 × 3/7 = 5304.
        db.spend(id, 5304, day(2026, 11, 4))
        val m = db.month(id, ym(2026, 11), day(2026, 11, 4))
        assertEquals(Cents(2304), m.weeks.first().step)
        assertEquals(Cents(5304), m.ideal)
        assertEquals(PaceStatus.ON_PACE, m.status)
        assertEquals(Cents(60000), m.projection)
    }

    @Test
    fun `a past month's ideal is its whole plan and a future month's is zero`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 10, 5))
        db.spend(id, 1000, day(2026, 12, 1))
        val oct = db.month(id, ym(2026, 10), day(2026, 11, 18))
        assertEquals(Cents(60000), oct.ideal) // every week is closed
        val dec = db.month(id, ym(2026, 12), day(2026, 11, 18))
        assertEquals(Cents(0), dec.ideal) // no week closed nor current
        assertEquals(Cents(0), dec.projection)
    }

    @Test
    fun `a budget created mid-month has the full limit for the whole month`() = withDb { db ->
        val id = db.createBudget("Mercado", Cents(60000), day(2026, 11, 18))
        val m = db.month(id, ym(2026, 11), day(2026, 11, 18))
        assertEquals(day(2026, 11, 1) to day(2026, 11, 30), m.start to m.end)
        assertEquals(Cents(60000), m.limit)
        assertEquals(listOf(2000L, 14000L, 14000L, 14000L, 14000L, 2000L), m.weeks.map { it.step.v })
        // Wed Nov 18: closed 20 + 140 + 140, current Nov 16–22 = 140 × 3/7 = 60 → 360.
        assertEquals(Cents(36000), m.ideal)
        assertEquals(Cents(60000), m.remaining)
    }

    // ---------------------------------------------------------------- Installments (spec §6, D7)

    @Test
    fun `12 installments cross the year each in its own month`() = withDb { db ->
        val id = db.createBudget("Casa", Cents(50000), day(2026, 10, 14))
        val tx = db.spend(id, 120000, day(2026, 10, 14), installments = 12)
        val detail = db.transaction(tx)
        assertEquals(12, detail.row.installments)
        assertEquals(Cents(120000), detail.row.total)
        assertEquals(
            (1..12).map { k -> Installment(k, day(2026, 10, 14).plus(DatePeriod(months = k - 1)), Cents(10000)) },
            detail.installments.sortedBy { it.k },
        )
        db.ensureMonths(ym(2027, 10))
        val history = db.historyOf(id, ym(2026, 10), ym(2027, 10))
        val expected = (0..12).associate { i ->
            YearMonth(2026 + (9 + i) / 12, (9 + i) % 12 + 1) to (50000L to if (i < 12) 10000L else 0L)
        }
        assertEquals(expected, history)
        assertTrue(tx in db.budgetDetail(id, ym(2027, 9), day(2026, 10, 14)).lines.map { it.row.id })
        assertFalse(tx in db.budgetDetail(id, ym(2027, 10), day(2026, 10, 14)).lines.map { it.row.id })
    }

    @Test
    fun `the last installment absorbs the cents`() = withDb { db ->
        val id = db.createBudget("Casa", Cents(50000), day(2026, 10, 14))
        val tx = db.spend(id, 10000, day(2026, 10, 30), installments = 3)
        assertEquals(listOf(3333L, 3333L, 3334L), db.transaction(tx).installments.sortedBy { it.k }.map { it.cents.v })
        db.ensureMonths(ym(2026, 12))
        assertEquals(
            mapOf(ym(2026, 10) to (50000L to 3333L), ym(2026, 11) to (50000L to 3333L), ym(2026, 12) to (50000L to 3334L)),
            db.historyOf(id, ym(2026, 10), ym(2026, 12)),
        )
    }

    @Test
    fun `400 installments are accepted and the last one lands 399 months later`() = withDb { db ->
        val id = db.createBudget("Casa", Cents(50000), day(2026, 10, 14))
        val tx = db.spend(id, 400001, day(2026, 10, 14), installments = 400)
        val parts = db.transaction(tx).installments.sortedBy { it.k }
        assertEquals(400, parts.size)
        assertEquals(Installment(1, day(2026, 10, 14), Cents(1000)), parts.first())
        // 2026-10 + 399 months = 2060-01; 400001 − 399 × 1000 = 1001.
        assertEquals(Installment(400, day(2060, 1, 14), Cents(1001)), parts.last())
        assertEquals(400001L, parts.sumOf { it.cents.v })
        db.ensureMonths(ym(2060, 2))
        assertEquals(
            mapOf(ym(2059, 12) to (50000L to 1000L), ym(2060, 1) to (50000L to 1001L), ym(2060, 2) to (50000L to 0L)),
            db.historyOf(id, ym(2059, 12), ym(2060, 2)),
        )
    }

    @Test
    fun `401 and 0 installments are refused and write nothing`() = withDb { db ->
        val id = db.createBudget("Casa", Cents(50000), day(2026, 10, 14))
        for (n in listOf(0, -1, 401)) {
            val form = TransactionForm(budget = id, tags = listOf("teste"), total = Cents(40100), installments = n, date = day(2026, 10, 14))
            assertEquals(TransactionId(0), db.addManual(form).transaction, "installments = $n")
        }
        assertEquals(emptyList(), db.transactions())
        assertEquals(Preview(), db.previewAccept(id, day(2026, 10, 14), Cents(40100), 401, day(2026, 10, 14)))
        assertEquals(Preview(), db.previewAccept(id, day(2026, 10, 14), Cents(40100), 0, day(2026, 10, 14)))
    }

    @Test
    fun `accepting a notice in 401 installments is refused and the notice stays pending`() = withDb { db ->
        assertTrue(db.addSource("com.nu.production", "Nubank", 1_000))
        val raw = RawNotice(
            packageName = "com.nu.production", appLabel = "Nubank", key = "k1", postedAt = 1_760_000_000_000,
            title = "Compra no crédito aprovada", text = "Compra de R$ 4.010,00 APROVADA em LOJA X",
        )
        assertTrue(capture(db, raw))
        val notice = db.pending().single().id
        val id = db.createBudget("Casa", Cents(50000), day(2026, 10, 14))
        val form = TransactionForm(budget = id, tags = listOf("casa"), total = Cents(401000), merchant = "LOJA X", date = day(2026, 10, 14), time = LocalTime(10, 0))
        assertEquals(TransactionId(0), db.accept(notice, form.copy(installments = 401)))
        assertEquals(listOf(notice), db.pending().map { it.id })

        val tx = db.accept(notice, form.copy(installments = 400))
        assertNotEquals(0L, tx.v)
        assertEquals(400, db.transaction(tx).installments.size)
        assertEquals(401000L, db.transaction(tx).installments.sumOf { it.cents.v })
    }

    @Test
    fun `with month start day 31 installments landing past their month move back to its last day`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2026, 3, 5)))
        val id = db.createBudget("Casa", Cents(100000), day(2026, 3, 5)) // Feb month: Feb 28..Mar 30
        val tx = db.spend(id, 40000, day(2026, 3, 30), installments = 4)
        // Months: Feb 28–Mar 30 · Mar 31–Apr 29 · Apr 30–May 30 · May 31–Jun 29.
        // k=2: Apr 30 is in the 3rd month → Apr 29. k=3: May 30 fits. k=4: Jun 30 is in the 5th → Jun 29.
        assertEquals(
            listOf(day(2026, 3, 30), day(2026, 4, 29), day(2026, 5, 30), day(2026, 6, 29)),
            db.transaction(tx).installments.sortedBy { it.k }.map { it.date },
        )
        db.ensureMonths(ym(2026, 6))
        assertEquals(
            (2..5).associate { ym(2026, it) to (100000L to 10000L) } + (ym(2026, 6) to (100000L to 0L)),
            db.historyOf(id, ym(2026, 2), ym(2026, 6)),
        )
    }

    @Test
    fun `with month start day 31 installments landing before their month move forward to its first day`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2026, 3, 5)))
        val id = db.createBudget("Casa", Cents(100000), day(2026, 3, 5))
        val tx = db.spend(id, 40000, day(2026, 2, 28), installments = 4)
        // k=2: Mar 28 is still in the Feb month → Mar 31. k=3: Apr 28 → Apr 30. k=4: May 28 → May 31.
        assertEquals(
            listOf(day(2026, 2, 28), day(2026, 3, 31), day(2026, 4, 30), day(2026, 5, 31)),
            db.transaction(tx).installments.sortedBy { it.k }.map { it.date },
        )
        db.ensureMonths(ym(2026, 5))
        assertEquals((2..5).associate { ym(2026, it) to (100000L to 10000L) }, db.historyOf(id, ym(2026, 2), ym(2026, 5)))
    }

    @Test
    fun `with month start day 29 in a non-leap year installments stay in the k-th month`() = withDb { db ->
        assertTrue(db.setMonthStartDay(29, day(2027, 3, 1)))
        val id = db.createBudget("Casa", Cents(100000), day(2027, 3, 1)) // Feb 2027 month: Feb 28..Mar 28
        val a = db.spend(id, 30000, day(2027, 2, 28), installments = 3)
        // k=2: Mar 28 is still the Feb month → Mar 29. k=3: Apr 28 is the Mar month (Mar 29..Apr 28) → Apr 29.
        assertEquals(
            listOf(day(2027, 2, 28), day(2027, 3, 29), day(2027, 4, 29)),
            db.transaction(a).installments.sortedBy { it.k }.map { it.date },
        )
        val b = db.spend(id, 20000, day(2027, 1, 29), installments = 2) // Jan month: Jan 29..Feb 27
        // k=2: Jan 29 + 1 month = Feb 28 (clamped), the first day of the Feb month: no move.
        assertEquals(listOf(day(2027, 1, 29), day(2027, 2, 28)), db.transaction(b).installments.sortedBy { it.k }.map { it.date })
    }

    @Test
    fun `with month start day 30 a February purchase spreads one per month`() = withDb { db ->
        assertTrue(db.setMonthStartDay(30, day(2026, 3, 1)))
        val id = db.createBudget("Casa", Cents(100000), day(2026, 3, 1)) // Feb 2026 month: Feb 28..Mar 29
        val tx = db.spend(id, 30000, day(2026, 2, 28), installments = 3)
        // k=2: Mar 28 is still the Feb month → Mar 30. k=3: Apr 28 is the Mar month (Mar 30..Apr 29) → Apr 30.
        assertEquals(
            listOf(day(2026, 2, 28), day(2026, 3, 30), day(2026, 4, 30)),
            db.transaction(tx).installments.sortedBy { it.k }.map { it.date },
        )
    }

    @Test
    fun `preview uses the month of the first installment on the boundary days`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2026, 2, 10)))
        val id = db.createBudget("Mercado", Cents(31000), day(2026, 2, 10)) // Jan month: Jan 31..Feb 27
        db.ensureMonths(ym(2026, 2))
        db.spend(id, 1000, day(2026, 2, 27))
        // Feb 27 is the Jan month: 310 − 10 = 300 → 300 − 90 = 210.
        assertEquals(Preview(ym(2026, 1), Cents(30000), Cents(21000)), db.previewAccept(id, day(2026, 2, 27), Cents(9000), 1, day(2026, 2, 27)))
        // Feb 28 is the Feb month, untouched: 310 → 310 − 90/3 = 280.
        assertEquals(Preview(ym(2026, 2), Cents(31000), Cents(28000)), db.previewAccept(id, day(2026, 2, 28), Cents(9000), 3, day(2026, 2, 28)))
    }
}
