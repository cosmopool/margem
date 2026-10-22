package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Edge cases of block 6 (Leitura), through the Db only: BudgetMonth.idealByDay (D44, KDoc in Types.kt)
 * from budgetDetail, budgetsOverview and previewBudget, and the reads the Leitura draws with (D45):
 * history, tagRanking, merchantRanking, spentByDay, at the edges spec §7.5 names.
 * Expected values come from spec §3–§7.5, architecture §5–§6 and the KDoc of the contract, by hand.
 *
 * idealByDay rule (KDoc): day d = Σ steps of the weeks ended before d + floor(step of d's week × days
 * elapsed in it, with d, ÷ its days). Never decreases; last = Σ steps = limit; [today − start] = ideal.
 *
 * Calendar facts used below:
 * 2026-08-01 Sat · 2026-09-01 Tue (30 days) · 2026-10-01 Thu (31) · 2026-11-01 Sun (30, Nov 30 Mon) ·
 * 2027-01-31 Sun · 2027-02-01 Mon (Feb 2027 has 28 days, Feb 28 Sun) · 2027-03-01 Mon (31) ·
 * 2028-02 has 29 days.
 */
class LeituraEdgeTest {

    private fun withDb(timeout: Duration = 60.seconds, block: suspend (Db) -> Unit): TestResult = runTest(timeout = timeout) {
        val path = Path(SystemTemporaryDirectory, "margem-leitura-edge-${Random.nextLong().toULong()}.db").toString()
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

    private suspend fun Db.budget(name: String, limit: Long, today: LocalDate): BudgetId =
        createBudget(name, Cents(limit), today).also { assertNotEquals(0L, it.v, "createBudget refused $name $limit") }

    private suspend fun Db.spend(
        budget: BudgetId, cents: Long, date: LocalDate, installments: Int = 1,
        tags: List<String> = listOf("teste"), merchant: String = "Loja",
    ): TransactionId {
        val form = TransactionForm(
            budget = budget, tags = tags, total = Cents(cents), installments = installments,
            merchant = merchant, date = date, time = LocalTime(12, 0),
        )
        return addManual(form).transaction.also { assertNotEquals(0L, it.v, "addManual refused $cents on $date") }
    }

    /** ensureMonths + budgetDetail, as the Leitura does (D30, D45). */
    private suspend fun Db.month(id: BudgetId, month: YearMonth, today: LocalDate): BudgetMonth {
        ensureMonths(month)
        return budgetDetail(id, month, today).month
    }

    /** A pending estorno notice, to cancel a transaction with. */
    private suspend fun Db.estorno(key: String, at: Long): NoticeId {
        addSource(BANK, "Banco", 0)
        assertTrue(capture(this, RawNotice(packageName = BANK, appLabel = "Banco", key = key, postedAt = at, title = "Banco", text = "Estorno da compra $key")))
        return pending().single { it.raw.key == key }.id
    }

    private fun ib(m: BudgetMonth) = m.idealByDay.map { it.v }

    private fun days(m: BudgetMonth) = m.start.daysUntil(m.end) + 1

    /** The KDoc invariants of idealByDay that hold for every month with a start. */
    private fun assertShape(m: BudgetMonth, label: String) {
        val v = ib(m)
        assertEquals(days(m), v.size, "$label: one value per day of ${m.start}..${m.end}")
        for (i in 1 until v.size) assertTrue(v[i] >= v[i - 1], "$label: never decreases, day $i ${v[i - 1]} → ${v[i]}")
        assertEquals(m.limit.v, v.last(), "$label: the last day is the limit")
        assertEquals(m.limit.v, m.weeks.sumOf { it.step.v }, "$label: Σ steps = limit")
    }

    // Sep 2026, month day 1, week Monday: 1–6 (6 days) · 7–13 · 14–20 · 21–27 · 28–30 (3 days); 30 days.
    private val sep = ym(2026, 9)
    private val sep1 = day(2026, 9, 1)
    private val sep15 = day(2026, 9, 15)
    private val sep30 = day(2026, 9, 30)

    /** Spec §4 example, R$ 600 in Sep: steps 12000 · 14000 ×3 · 6000 = 2000 per day, so day n (1-based) = 2000·n. */
    private val sep600 = List(30) { 2000L * (it + 1) }

    // ---------------------------------------------------------------- idealByDay: size, index 0, today (D44)

    @Test
    fun `ideal by day has one value per day of the window starting at start`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        val m = db.month(id, sep, sep15)
        assertEquals(sep1, m.start)
        assertEquals(sep30, m.end)
        // Week 1 (6 days, step 12000): floor(12000·k/6) = 2000k; weeks 2–4 (7 days, 14000): +2000 per day;
        // week 5 (3 days, 6000): +2000 per day → 2000, 4000, …, 60000.
        assertEquals(sep600, ib(m))
        assertEquals(2000L, ib(m).first(), "index 0 is Sep 1 the start and not the creation day Sep 15 (spec §3)")
        assertEquals(60000L, ib(m).last())
        // Today Sep 15 (index 14): closed weeks 12000 + 14000, plus 14000 × 2 days (14, 15) ÷ 7 = 4000 → 30000.
        assertEquals(30000L, m.ideal.v)
        assertEquals(m.ideal, m.idealByDay[sep1.daysUntil(sep15)])
        assertShape(m, "Sep")
    }

    @Test
    fun `today on the first and on the last day of the month`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep1)
        // Today Sep 1: no closed week, 12000 × 1 ÷ 6 = 2000.
        val first = db.month(id, sep, sep1)
        assertEquals(2000L, first.ideal.v)
        assertEquals(first.ideal, first.idealByDay[0])
        assertEquals(sep600, ib(first))
        // Today Sep 30: every week counts in full → 60000 = limit.
        val last = db.month(id, sep, sep30)
        assertEquals(60000L, last.ideal.v)
        assertEquals(last.ideal, last.idealByDay[29])
        assertEquals(sep600, ib(last))
    }

    @Test
    fun `ideal by day does not depend on today for a past month and for a future month`() = withDb { db ->
        // KDoc: day d = the rule of ideal with today = d, so the list is the same whatever today is.
        val id = db.budget("Mercado", 60000, day(2026, 8, 20))
        // Future month: Sep seen from Aug 20 (ensureMonths copies the limit into Sep).
        assertEquals(sep600, ib(db.month(id, sep, day(2026, 8, 20))))
        // Past month: Sep seen from Oct 10; the last day is the limit.
        val past = db.month(id, sep, day(2026, 10, 10))
        assertEquals(sep600, ib(past))
        assertEquals(past.limit, past.idealByDay.last())
        // Current month, any day.
        assertEquals(sep600, ib(db.month(id, sep, day(2026, 9, 9))))
    }

    @Test
    fun `ideal by day does not change with spending even past the limit`() = withDb { db ->
        // Spec §4: the step is a visualization, it does not change with what was spent.
        val id = db.budget("Mercado", 60000, sep15)
        db.spend(id, 99999, day(2026, 9, 2))
        db.spend(id, 500, sep15)
        val m = db.month(id, sep, sep15)
        assertEquals(PaceStatus.OVERRUN, m.status)
        assertEquals(sep600, ib(m))
        assertEquals(m.ideal, m.idealByDay[14])
    }

    @Test
    fun `budgetsOverview and previewBudget carry the same ideal by day as budgetDetail`() = withDb { db ->
        // previewBudget KDoc: equal to budgetDetail's month right after createBudget with the same limit and day.
        val preview = db.previewBudget(Cents(60000), sep15)
        assertEquals(sep600, ib(preview))
        val id = db.budget("Mercado", 60000, sep15)
        val other = db.budget("Lazer", 100, sep15)
        db.ensureMonths(sep)
        val overview = db.budgetsOverview(sep, sep15, BudgetOrder.AZ)
        assertEquals(ib(db.budgetDetail(id, sep, sep15).month), ib(overview.single { it.id == id }.month))
        assertEquals(ib(db.budgetDetail(other, sep, sep15).month), ib(overview.single { it.id == other }.month))
        assertEquals(sep600, ib(overview.single { it.id == id }.month))
    }

    @Test
    fun `zero months have an empty ideal by day`() = withDb { db ->
        // KDoc: empty when start is NO_DATE. budgetDetail of an unknown budget and previewBudget of a refused limit are zero.
        assertEquals(emptyList(), db.budgetDetail(BudgetId(999), sep, sep15).month.idealByDay)
        assertEquals(emptyList(), db.previewBudget(Cents(0), sep15).idealByDay)
        assertEquals(emptyList(), db.previewBudget(Cents(-1), sep15).idealByDay)
        assertEquals(emptyList(), BudgetMonth().idealByDay)
    }

    // ---------------------------------------------------------------- Tiny limits, rounding down per week (spec §4, D11)

    @Test
    fun `a one cent limit stays at zero until the last day`() = withDb { db ->
        val id = db.budget("Mercado", 1, sep15)
        val m = db.month(id, sep, sep15)
        // Steps: floor(1·6/30) = 0, floor(1·7/30) = 0 ×3, the last week takes 1.
        assertEquals(listOf(0L, 0, 0, 0, 1), m.weeks.map { it.step.v })
        // Week 5 (Sep 28–30): floor(1·1/3) = 0, floor(1·2/3) = 0, 1 → 29 zeros then 1.
        assertEquals(List(29) { 0L } + 1L, ib(m))
        assertEquals(0L, m.ideal.v)
        assertEquals(1L, db.month(id, sep, sep30).ideal.v)
    }

    @Test
    fun `100 cents in September round down inside each week`() = withDb { db ->
        val id = db.budget("Mercado", 100, sep15)
        val m = db.month(id, sep, sep15)
        // Steps: floor(100·6/30) = 20; floor(100·7/30) = 23 ×3; last = 100 − 20 − 69 = 11.
        assertEquals(listOf(20L, 23, 23, 23, 11), m.weeks.map { it.step.v })
        // Week 1, floor(20k/6): 3, 6, 10, 13, 16, 20.
        // Week 2, 20 + floor(23k/7) (3, 6, 9, 13, 16, 19, 23): 23, 26, 29, 33, 36, 39, 43.
        // Week 3, 43 + same: 46, 49, 52, 56, 59, 62, 66. Week 4, 66 + same: 69, 72, 75, 79, 82, 85, 89.
        // Week 5, 89 + floor(11k/3) (3, 7, 11): 92, 96, 100.
        assertEquals(
            listOf(
                3L, 6, 10, 13, 16, 20,
                23, 26, 29, 33, 36, 39, 43,
                46, 49, 52, 56, 59, 62, 66,
                69, 72, 75, 79, 82, 85, 89,
                92, 96, 100,
            ),
            ib(m),
        )
        // Today Sep 15 (index 14) = 49: 20 + 23 closed, plus floor(23·2/7) = 6.
        assertEquals(49L, m.ideal.v)
    }

    // ---------------------------------------------------------------- Edited steps: the degrau (spec §4, §5)

    @Test
    fun `a first week edited to zero keeps the ideal flat at zero then climbs`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        assertTrue(db.setStep(id, sep, sep1, Cents(0)))
        val m = db.month(id, sep, day(2026, 9, 4))
        // 60000 over the other 24 days: 7 days → floor(420000/24) = 17500 ×3; last (3 days) = 60000 − 52500 = 7500.
        // That is 2500 per day from Sep 7: day n (n ≥ 7) = 2500·(n − 6).
        assertEquals(listOf(0L, 17500, 17500, 17500, 7500), m.weeks.map { it.step.v })
        assertEquals(List(6) { 0L } + List(24) { 2500L * (it + 1) }, ib(m))
        // Spec §5: if week 1 was lowered, the ideal starts lower too.
        assertEquals(0L, m.ideal.v)
    }

    @Test
    fun `a first week edited up makes a steeper first step then a gentler slope`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        assertTrue(db.setStep(id, sep, sep1, Cents(25000)))
        val today = day(2026, 9, 9)
        val m = db.month(id, sep, today)
        // 35000 over 24 days: floor(35000·7/24) = 10208 ×3; last = 35000 − 30624 = 4376 (BudgetsEdgeTest).
        assertEquals(listOf(25000L, 10208, 10208, 10208, 4376), m.weeks.map { it.step.v })
        // Week 1, floor(25000k/6): 4166, 8333, 12500, 16666, 20833, 25000.
        // Weeks 2–4, base + floor(10208k/7) (1458, 2916, 4374, 5833, 7291, 8749, 10208).
        // Week 5, 55624 + floor(4376k/3) (1458, 2917, 4376).
        assertEquals(
            listOf(
                4166L, 8333, 12500, 16666, 20833, 25000,
                26458, 27916, 29374, 30833, 32291, 33749, 35208,
                36666, 38124, 39582, 41041, 42499, 43957, 45416,
                46874, 48332, 49790, 51249, 52707, 54165, 55624,
                57082, 58541, 60000,
            ),
            ib(m),
        )
        // Today Sep 9 (index 8): 25000 + floor(10208·3/7) (Sep 7, 8, 9) = 25000 + 4374 = 29374.
        assertEquals(29374L, m.ideal.v)
        assertEquals(m.ideal, m.idealByDay[8])

        // Voltar ao automático brings back the default 2000 per day.
        assertTrue(db.clearStep(id, sep, sep1))
        assertEquals(sep600, ib(db.month(id, sep, today)))
    }

    @Test
    fun `a middle week edited to zero is a plateau`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        assertTrue(db.setStep(id, sep, day(2026, 9, 14), Cents(0)))
        val m = db.month(id, sep, sep15)
        assertShape(m, "plateau")
        val v = ib(m)
        // Week 3 (Sep 14–20, indexes 13..19) adds 0: every day of it equals Sep 13 (index 12).
        for (i in 13..19) assertEquals(v[12], v[i], "Sep ${i + 1} stays at the end of week 2")
        assertTrue(v[20] > v[19], "Sep 21 climbs again")
        // Today Sep 15 is inside the plateau.
        assertEquals(v[12], m.ideal.v)
    }

    // ---------------------------------------------------------------- Partial weeks and week start (spec §4)

    @Test
    fun `one day first and last weeks are full on their own day`() = withDb { db ->
        // Nov 2026, Monday weeks: Sun Nov 1 (1) · 2–8 · 9–15 · 16–22 · 23–29 · Mon Nov 30 (1); 30 days.
        // 30000 → 1000 per day: steps 1000, 7000 ×4, 1000 → day n = 1000·n.
        val nov = ym(2026, 11)
        val id = db.budget("Mercado", 30000, day(2026, 11, 1))
        val first = db.month(id, nov, day(2026, 11, 1))
        assertEquals(listOf(1000L, 7000, 7000, 7000, 7000, 1000), first.weeks.map { it.step.v })
        assertEquals(List(30) { 1000L * (it + 1) }, ib(first))
        // Today Nov 1: the 1-day week elapsed in full (with today) → 1000.
        assertEquals(1000L, first.ideal.v)
        // Today Nov 30: 29000 closed + 1000 × 1 ÷ 1.
        assertEquals(30000L, db.month(id, nov, day(2026, 11, 30)).ideal.v)
    }

    @Test
    fun `week start Sunday cuts October into a 3 day week and four full weeks`() = withDb { db ->
        val oct = ym(2026, 10)
        val today = day(2026, 10, 12)
        db.setWeekStart(DayOfWeek.SUNDAY, today)
        val id = db.budget("Mercado", 100, today)
        val m = db.month(id, oct, today)
        // Oct 2026, Sunday weeks: Thu 1–Sat 3 (3) · 4–10 · 11–17 · 18–24 · 25–31; 31 days.
        assertEquals(
            listOf(day(2026, 10, 1), day(2026, 10, 4), day(2026, 10, 11), day(2026, 10, 18), day(2026, 10, 25)),
            m.weeks.map { it.start },
        )
        // Steps: floor(300/31) = 9; floor(700/31) = 22 ×3; last = 100 − 9 − 66 = 25.
        assertEquals(listOf(9L, 22, 22, 22, 25), m.weeks.map { it.step.v })
        // Week 1, floor(9k/3): 3, 6, 9. Weeks 2–4, base + floor(22k/7) (3, 6, 9, 12, 15, 18, 22).
        // Week 5, 75 + floor(25k/7) (3, 7, 10, 14, 17, 21, 25).
        assertEquals(
            listOf(
                3L, 6, 9,
                12, 15, 18, 21, 24, 27, 31,
                34, 37, 40, 43, 46, 49, 53,
                56, 59, 62, 65, 68, 71, 75,
                78, 82, 85, 89, 92, 96, 100,
            ),
            ib(m),
        )
        // Today Oct 12 (index 11): 9 + 22 closed + floor(22·2/7) = 6 → 37.
        assertEquals(37L, m.ideal.v)
    }

    // ---------------------------------------------------------------- February and month start day 29–31 (spec §3)

    @Test
    fun `February 2027 with day 1 is four full weeks`() = withDb { db ->
        val feb = ym(2027, 2)
        val id = db.budget("Mercado", 28000, day(2027, 2, 1))
        // Mon Feb 1 .. Sun Feb 28: four weeks of 7000 → 1000 per day.
        val m = db.month(id, feb, day(2027, 2, 28))
        assertEquals(28, m.idealByDay.size)
        assertEquals(List(28) { 1000L * (it + 1) }, ib(m))
        assertEquals(28000L, m.ideal.v)
    }

    @Test
    fun `month start day 31 runs from January 31 to February 27 and then February 28 to March 30`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2027, 1, 5)))
        val feb10 = day(2027, 2, 10)
        val id = db.budget("Mercado", 28000, feb10)
        // 2027-01: Jan 31 .. Feb 27 (Feb 31 → 28, eve 27), 28 days. Weeks Sun Jan 31 (1, 1000) · Feb 1–7 · 8–14 ·
        // 15–21 (7000 each) · 22–27 (6, 6000) → 1000 per day; index 0 = Jan 31.
        val jan = db.month(id, ym(2027, 1), feb10)
        assertEquals(day(2027, 1, 31), jan.start)
        assertEquals(List(28) { 1000L * (it + 1) }, ib(jan))
        // Today Feb 10 is index 10: 1000 + 7000 + 7000 × 3 ÷ 7 = 11000.
        assertEquals(11000L, jan.ideal.v)
        assertEquals(jan.ideal, jan.idealByDay[10])

        // 2027-02: Feb 28 .. Mar 30, 31 days. Steps 903 · 6322 ×4 · 1809 (BudgetsEdgeTest).
        // Sun Feb 28: 903. Mar 1–7, 903 + floor(6322k/7) (903, 1806, 2709, 3612, 4515, 5418, 6322).
        // Mar 29–30, 26191 + floor(1809k/2) (904, 1809).
        val feb = db.month(id, ym(2027, 2), day(2027, 3, 10))
        assertEquals(listOf(903L, 6322, 6322, 6322, 6322, 1809), feb.weeks.map { it.step.v })
        assertEquals(
            listOf(
                903L,
                1806, 2709, 3612, 4515, 5418, 6321, 7225,
                8128, 9031, 9934, 10837, 11740, 12643, 13547,
                14450, 15353, 16256, 17159, 18062, 18965, 19869,
                20772, 21675, 22578, 23481, 24384, 25287, 26191,
                27095, 28000,
            ),
            ib(feb),
        )
        // Today Mar 10 (index 10): 903 + 6322 closed + floor(6322·3/7) = 2709 → 9934.
        assertEquals(9934L, feb.ideal.v)
    }

    @Test
    fun `every start day and week start keep the shape and today matches ideal`() = withDb(timeout = 300.seconds) { db ->
        val setup = day(2026, 11, 20)
        val id = db.budget("Mercado", 12345, setup)
        for (startDay in listOf(1, 29, 30, 31)) for (weekStart in DayOfWeek.entries) {
            db.setMonthStartDay(startDay, setup)
            db.setWeekStart(weekStart, setup)
            for (month in listOf(ym(2027, 1), ym(2027, 2), ym(2028, 2))) {
                val label = "day $startDay, $weekStart, $month"
                db.ensureMonths(month)
                val base = db.budgetDetail(id, month, setup).month
                assertShape(base, label)
                // Every day of the window as today: the same list, and ideal is its value (KDoc).
                var d = base.start
                while (d <= base.end) {
                    val m = db.budgetDetail(id, month, d).month
                    assertEquals(ib(base), ib(m), "$label today $d")
                    assertEquals(m.idealByDay[base.start.daysUntil(d)], m.ideal, "$label today $d")
                    d = d.plus(1, DateTimeUnit.DAY)
                }
                assertEquals(ib(base), ib(db.budgetsOverview(month, base.start, BudgetOrder.AZ).single().month), label)

                // An edited week of 0 adds nothing: a plateau, still never decreasing and ending at the limit.
                if (base.weeks.size >= 3) {
                    val w = base.weeks[1]
                    assertTrue(db.setStep(id, month, w.start, Cents(0)), label)
                    val e = db.budgetDetail(id, month, w.start).month
                    assertShape(e, "$label edited")
                    val v = ib(e)
                    val before = v[base.start.daysUntil(w.start) - 1]
                    for (i in base.start.daysUntil(w.start)..base.start.daysUntil(w.end)) assertEquals(before, v[i], "$label plateau $i")
                    assertTrue(db.clearStep(id, month, w.start), label)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Month without a limit row (spec §7.5, Db KDoc)

    @Test
    fun `a month before the first month of the budget is absent from the overview and its ideal by day never passes its limit`() = withDb { db ->
        val oct15 = day(2026, 10, 15)
        val id = db.budget("Mercado", 60000, oct15)
        db.ensureMonths(ym(2026, 10))
        assertTrue(db.budgetsOverview(sep, oct15, BudgetOrder.AZ).none { it.id == id })
        val m = db.budgetDetail(id, sep, oct15).month
        // Week.locked KDoc: such a month still has weeks (all locked), so it has a window: one value per day of Sep.
        assertTrue(m.weeks.isNotEmpty())
        assertTrue(m.weeks.all { it.locked })
        assertEquals(30, m.idealByDay.size)
        assertShape(m, "Sep without limit row")
        assertTrue(ib(m).all { it <= m.limit.v })
        assertTrue(db.history(sep, sep).none { it.budget == id }, "history has only months with a limit row")
    }

    @Test
    fun `a new limit redraws the current month and the past month keeps its own`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        val oct5 = day(2026, 10, 5)
        assertTrue(db.setLimit(id, Cents(31000), oct5))
        assertEquals(sep600, ib(db.month(id, sep, oct5)))
        // Oct 2026, Monday weeks: Thu 1–Sun 4 (4) · 5–11 · 12–18 · 19–25 · 26–31 (6); 31000 → 1000 per day.
        val oct = db.month(id, ym(2026, 10), oct5)
        assertEquals(listOf(4000L, 7000, 7000, 7000, 6000), oct.weeks.map { it.step.v })
        assertEquals(List(31) { 1000L * (it + 1) }, ib(oct))
        assertEquals(5000L, oct.ideal.v)
    }

    // ---------------------------------------------------------------- Ranking, calendar and history (spec §7.5, §6)

    /**
     * Shared scenario (budget "Mercado" from Aug 1, month day 1):
     * A 5000 Sep 5 [feira, casa] "Feira Livre" · B 3000 Sep 12 [feira] "feira   LIVRE" (same key) ·
     * C 12000 in 3× bought Aug 20 [casa] "Loja": 4000 on Aug 20, Sep 20, Oct 20 ·
     * D 7000 Sep 18 [lazer] "Cinema" · E 2000 Oct 1 [feira] "Loja".
     */
    private class Scenario(val id: BudgetId, val c: TransactionId, val d: TransactionId)

    private suspend fun Db.scenario(): Scenario {
        val id = budget("Mercado", 60000, day(2026, 8, 1))
        ensureMonths(ym(2026, 10))
        spend(id, 5000, day(2026, 9, 5), tags = listOf("feira", "casa"), merchant = "Feira Livre")
        spend(id, 3000, day(2026, 9, 12), tags = listOf("feira"), merchant = "feira   LIVRE")
        val c = spend(id, 12000, day(2026, 8, 20), installments = 3, tags = listOf("casa"), merchant = "Loja")
        val d = spend(id, 7000, day(2026, 9, 18), tags = listOf("lazer"), merchant = "Cinema")
        spend(id, 2000, day(2026, 10, 1), tags = listOf("feira"), merchant = "Loja")
        setAlias("Feira Livre", "Feira do bairro")
        return Scenario(id, c, d)
    }

    /** name → (total, count), dropping rows that count nothing (a row of 0 is not asserted either way). */
    private fun ranks(rows: List<RankRow>) = rows.filter { it.total.v != 0L || it.count != 0 }.map { Triple(it.name, it.total.v, it.count) }

    @Test
    fun `ranking and calendar count each installment on its own date`() = withDb { db ->
        db.scenario()
        val oct1 = day(2026, 10, 1)
        val oct31 = day(2026, 10, 31)
        // Oct: C's 3rd installment 4000 on Oct 20 and E 2000 on Oct 1. By value: casa 4000, feira 2000.
        assertEquals(listOf(Triple("casa", 4000L, 1), Triple("feira", 2000L, 1)), ranks(db.tagRanking(oct1, oct31)))
        // Merchants: C and E share "Loja": 6000 over 2 transactions.
        assertEquals(listOf(Triple("Loja", 6000L, 2)), ranks(db.merchantRanking(oct1, oct31)))
        assertEquals(mapOf(oct1 to Cents(2000), day(2026, 10, 20) to Cents(4000)), db.spentByDay(oct1, oct31))
        // Aug: only C's 1st installment.
        assertEquals(mapOf(day(2026, 8, 20) to Cents(4000)), db.spentByDay(day(2026, 8, 1), day(2026, 8, 31)))
    }

    @Test
    fun `a purchase with installments counts once in the ranking over a period with several of them`() = withDb { db ->
        db.scenario()
        // Aug 1 .. Oct 31 holds all three of C's installments: casa = A 5000 + C 12000 over 2 transactions (not 4);
        // feira = A 5000 + B 3000 + E 2000 over 3; lazer = D 7000 over 1.
        assertEquals(
            listOf(Triple("casa", 17000L, 2), Triple("feira", 10000L, 3), Triple("lazer", 7000L, 1)),
            ranks(db.tagRanking(day(2026, 8, 1), day(2026, 10, 31))),
        )
        assertEquals(
            listOf(Triple("Loja", 14000L, 2), Triple("Feira do bairro", 8000L, 2), Triple("Cinema", 7000L, 1)),
            ranks(db.merchantRanking(day(2026, 8, 1), day(2026, 10, 31))),
        )
    }

    @Test
    fun `a transaction with several tags counts in full in each and the tags may sum more than the total`() = withDb { db ->
        db.scenario()
        // Sep: casa = A 5000 + C 4000 (2), feira = A 5000 + B 3000 (2), lazer = D 7000 (1).
        val tags = ranks(db.tagRanking(sep1, sep30))
        assertEquals(listOf(Triple("casa", 9000L, 2), Triple("feira", 8000L, 2), Triple("lazer", 7000L, 1)), tags)
        // Sep total spent: 5000 + 3000 + 4000 + 7000 = 19000; the tags sum 24000 (A counted twice, spec §7.5).
        val byDay = db.spentByDay(sep1, sep30)
        assertEquals(19000L, byDay.values.sumOf { it.v })
        assertEquals(24000L, tags.sumOf { it.second })
        // Merchants by alias, grouped by key (spacing and case ignored): A and B are one row.
        assertEquals(
            listOf(Triple("Feira do bairro", 8000L, 2), Triple("Cinema", 7000L, 1), Triple("Loja", 4000L, 1)),
            ranks(db.merchantRanking(sep1, sep30)),
        )
    }

    @Test
    fun `a refunded purchase leaves ranking calendar and history and comes back on undo`() = withDb { db ->
        val s = db.scenario()
        val e = db.estorno("e1", 1_000)
        assertTrue(db.registerRefund(e, s.d))
        // Sep without D: casa 9000 (2), feira 8000 (2); lazer counts nothing.
        assertEquals(listOf(Triple("casa", 9000L, 2), Triple("feira", 8000L, 2)), ranks(db.tagRanking(sep1, sep30)))
        assertTrue(ranks(db.merchantRanking(sep1, sep30)).none { it.first == "Cinema" })
        // spentByDay KDoc: days without spending are absent; Sep 18 had only D.
        assertEquals(
            mapOf(day(2026, 9, 5) to Cents(5000), day(2026, 9, 12) to Cents(3000), day(2026, 9, 20) to Cents(4000)),
            db.spentByDay(sep1, sep30),
        )
        assertEquals(12000L, db.history(sep, sep).single { it.budget == s.id }.spent.v)

        assertTrue(db.undoRefund(e))
        assertEquals(
            listOf(Triple("casa", 9000L, 2), Triple("feira", 8000L, 2), Triple("lazer", 7000L, 1)),
            ranks(db.tagRanking(sep1, sep30)),
        )
        assertEquals(Cents(7000), db.spentByDay(sep1, sep30)[day(2026, 9, 18)])
        assertEquals(19000L, db.history(sep, sep).single { it.budget == s.id }.spent.v)
    }

    @Test
    fun `refunding a purchase with installments takes every installment out of every month`() = withDb { db ->
        val s = db.scenario()
        // Spec §6: the estorno of an installment purchase takes all of them out, past months included.
        assertTrue(db.registerRefund(db.estorno("e2", 2_000), s.c))
        val byDay = db.spentByDay(day(2026, 8, 1), day(2026, 10, 31))
        for (d in listOf(day(2026, 8, 20), day(2026, 9, 20), day(2026, 10, 20))) assertTrue(d !in byDay, "C's installment on $d")
        // Aug had only C: nothing there.
        assertEquals(emptyMap(), db.spentByDay(day(2026, 8, 1), day(2026, 8, 31)))
        // Sep: casa only A 5000 (1); Loja counts nothing in Sep.
        assertEquals(
            listOf(Triple("feira", 8000L, 2), Triple("lazer", 7000L, 1), Triple("casa", 5000L, 1)),
            ranks(db.tagRanking(sep1, sep30)),
        )
        assertTrue(ranks(db.merchantRanking(sep1, sep30)).none { it.first == "Loja" })
        // History: Aug 0, Sep 5000 + 3000 + 7000 = 15000, Oct E 2000.
        val h = db.history(ym(2026, 8), ym(2026, 10)).filter { it.budget == s.id }.associate { it.month to it.spent.v }
        assertEquals(mapOf(ym(2026, 8) to 0L, sep to 15000L, ym(2026, 10) to 2000L), h)
    }

    @Test
    fun `history matches the month spent and starts at the first month of the budget`() = withDb { db ->
        val s = db.scenario()
        // Aug: C 4000. Sep: 5000 + 3000 + 4000 + 7000 = 19000. Oct: 4000 + 2000 = 6000. Each month with its own limit 60000.
        val h = db.history(ym(2026, 3), ym(2026, 10)).filter { it.budget == s.id }
        assertEquals(listOf(ym(2026, 8), sep, ym(2026, 10)), h.map { it.month }, "months before Aug have no limit row")
        assertEquals(listOf(4000L, 19000L, 6000L), h.map { it.spent.v })
        assertEquals(listOf(60000L, 60000L, 60000L), h.map { it.limit.v })
        for (row in h) assertEquals(db.budgetDetail(s.id, row.month, sep15).month.spent, row.spent, "${row.month}")
    }

    @Test
    fun `a month with nothing spent has empty rankings and calendar`() = withDb { db ->
        db.scenario()
        val nov1 = day(2026, 11, 1)
        val nov30 = day(2026, 11, 30)
        // Spec §7.5: "Nada lançado em outubro." — C ended in Oct, nothing is dated in Nov.
        assertEquals(emptyList(), ranks(db.tagRanking(nov1, nov30)))
        assertEquals(emptyList(), ranks(db.merchantRanking(nov1, nov30)))
        assertEquals(emptyMap(), db.spentByDay(nov1, nov30))
    }

    @Test
    fun `the reads include both ends of a month window with start day 31`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2027, 1, 5)))
        val id = db.budget("Mercado", 28000, day(2027, 1, 5))
        db.ensureMonths(ym(2027, 2))
        db.spend(id, 1000, day(2027, 1, 30), tags = listOf("antes"))
        db.spend(id, 2000, day(2027, 1, 31), tags = listOf("dentro"))
        db.spend(id, 3000, day(2027, 2, 27), tags = listOf("dentro"))
        db.spend(id, 4000, day(2027, 2, 28), tags = listOf("depois"))
        // 2027-01 = Jan 31 .. Feb 27: Jan 30 is in 2026-12 and Feb 28 starts 2027-02.
        val m = db.budgetDetail(id, ym(2027, 1), day(2027, 2, 10)).month
        assertEquals(day(2027, 1, 31), m.start)
        assertEquals(day(2027, 2, 27), m.end)
        assertEquals(mapOf(day(2027, 1, 31) to Cents(2000), day(2027, 2, 27) to Cents(3000)), db.spentByDay(m.start, m.end))
        assertEquals(listOf(Triple("dentro", 5000L, 2)), ranks(db.tagRanking(m.start, m.end)))
        assertEquals(5000L, m.spent.v)
        assertEquals(5000L, db.history(ym(2027, 1), ym(2027, 1)).single().spent.v)
        // A single day period: from == to.
        assertEquals(listOf(Triple("depois", 4000L, 1)), ranks(db.tagRanking(day(2027, 2, 28), day(2027, 2, 28))))
        assertEquals(mapOf(day(2027, 2, 28) to Cents(4000)), db.spentByDay(day(2027, 2, 28), day(2027, 2, 28)))
    }

    private companion object {
        const val BANK = "com.banco.leitura"
    }
}
