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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Edge cases of the block 3 additions (Verbas), through the Db only: Week.max / Week.locked,
 * clearStep, limitDropsEdits, previewBudget, budgetDetail (lines and firstMonth).
 * Expected values come from spec §3, §4, §6, §7.1 and the KDoc of the contract, computed by hand.
 *
 * Calendar facts used below (weeks start on Monday, the default):
 * 2026-09-01 Tue (30 days) · 2026-10-01 Thu (31) · 2026-11-01 Sun (30, Nov 30 Mon) ·
 * 2026-09-20 Sun · 2026-10-19 Mon · 2027-01-29 Fri · 2027-01-30 Sat · 2027-01-31 Sun ·
 * 2027-02-01 Mon · 2027-02-28 Sun (Feb 2027 has 28 days) · 2027-03-29 Mon · 2028-01-29 Sat ·
 * 2028-01-31 Mon · 2028-02-28 Mon · 2028-02-29 Tue · 2028-03-27 Mon.
 */
class BudgetsEdgeTest {

    private fun withDb(block: suspend (Db) -> Unit): TestResult = runTest {
        val path = Path(SystemTemporaryDirectory, "margem-budgets-edge-${Random.nextLong().toULong()}.db").toString()
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
        budget: BudgetId, cents: Long, date: LocalDate, installments: Int = 1, time: LocalTime = LocalTime(12, 0),
    ): TransactionId {
        val form = TransactionForm(
            budget = budget, tags = listOf("teste"), total = Cents(cents), installments = installments,
            merchant = "Loja", date = date, time = time,
        )
        return addManual(form).transaction.also { assertNotEquals(0L, it.v, "addManual refused $cents on $date") }
    }

    /** ensureMonths + budgetDetail, as the detail screen does; checks Σ steps = limit (spec §4, D11). */
    private suspend fun Db.detail(id: BudgetId, month: YearMonth, today: LocalDate): BudgetDetail {
        ensureMonths(month)
        val d = budgetDetail(id, month, today)
        assertEquals(d.month.limit.v, d.month.weeks.sumOf { it.step.v }, "Σ steps must equal the limit in $month")
        return d
    }

    private suspend fun Db.month(id: BudgetId, month: YearMonth, today: LocalDate): BudgetMonth = detail(id, month, today).month

    /** A pending estorno notice, to cancel a transaction with. */
    private suspend fun Db.estorno(key: String, at: Long): NoticeId {
        addSource(BANK, "Banco", 0)
        assertTrue(capture(this, RawNotice(packageName = BANK, appLabel = "Banco", key = key, postedAt = at, title = "Banco", text = "Estorno da compra $key")))
        return pending().single { it.raw.key == key }.id
    }

    private data class W(val start: LocalDate, val end: LocalDate, val step: Long, val edited: Boolean, val max: Long, val locked: Boolean)

    private fun ws(m: BudgetMonth) = m.weeks.map { W(it.start, it.end, it.step.v, it.edited, it.max.v, it.locked) }

    /** A default (preview) week: not edited, nothing spent, max = limit (no other edits), not locked. */
    private fun fresh(start: LocalDate, end: LocalDate, step: Long, limit: Long) =
        Week(start = start, end = end, step = Cents(step), edited = false, spent = Cents(0), max = Cents(limit), locked = false)

    // Sep 2026, month day 1, week Monday: 1–6 (6 days) · 7–13 · 14–20 · 21–27 · 28–30 (3 days); 30 days.
    private val s1 = day(2026, 9, 1)
    private val s2 = day(2026, 9, 7)
    private val s3 = day(2026, 9, 14)
    private val s4 = day(2026, 9, 21)
    private val s5 = day(2026, 9, 28)
    private val sep = ym(2026, 9)
    private val sep15 = day(2026, 9, 15)

    // ---------------------------------------------------------------- Week.max and Week.locked (spec §4)

    @Test
    fun `week max is the limit minus the other edited weeks and setStep takes it exactly but not one cent more`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        // Spec §4 example: R$ 600 in 30 days → full week 140,00; 6 days 120,00; 3 days 60,00. No edits: max = limit.
        assertEquals(
            listOf(
                W(s1, day(2026, 9, 6), 12000, false, 60000, false),
                W(s2, day(2026, 9, 13), 14000, false, 60000, false),
                W(s3, day(2026, 9, 20), 14000, false, 60000, false),
                W(s4, day(2026, 9, 27), 14000, false, 60000, false),
                W(s5, day(2026, 9, 30), 6000, false, 60000, false),
            ),
            ws(db.month(id, sep, sep15)),
        )

        assertFalse(db.setStep(id, sep, s1, Cents(60001)))
        assertTrue(db.setStep(id, sep, s1, Cents(25000)))
        // 35000 left over 24 days: 7 days → floor(35000·7/24) = 10208; the last not edited gets 35000 − 3·10208 = 4376.
        assertEquals(
            listOf(
                W(s1, day(2026, 9, 6), 25000, true, 60000, false),
                W(s2, day(2026, 9, 13), 10208, false, 35000, false),
                W(s3, day(2026, 9, 20), 10208, false, 35000, false),
                W(s4, day(2026, 9, 27), 10208, false, 35000, false),
                W(s5, day(2026, 9, 30), 4376, false, 35000, false),
            ),
            ws(db.month(id, sep, sep15)),
        )

        val before = db.version.value
        assertFalse(db.setStep(id, sep, s2, Cents(35001)), "one cent above max")
        assertEquals(before, db.version.value, "a refused setStep doesn't bump")
        assertEquals(10208L, db.month(id, sep, sep15).weeks[1].step.v)
        assertTrue(db.setStep(id, sep, s2, Cents(35000)), "exactly max")

        // Σ edited = limit: the three automatic weeks share 0, none is locked (three are not edited).
        val m = db.month(id, sep, sep15)
        assertEquals(listOf(25000L, 35000L, 0L, 0L, 0L), m.weeks.map { it.step.v })
        assertEquals(listOf(25000L, 35000L, 0L, 0L, 0L), m.weeks.map { it.max.v }) // 60000 − 35000 · 60000 − 25000 · 0 · 0 · 0
        assertTrue(m.weeks.none { it.locked })

        assertFalse(db.setStep(id, sep, s3, Cents(1)), "max is 0")
        assertFalse(db.setStep(id, sep, s3, Cents(-1)), "negative step")
        assertTrue(db.setStep(id, sep, s3, Cents(0)), "0 is in 0..max")
        assertTrue(db.month(id, sep, sep15).weeks.none { it.locked }, "two weeks are still not edited")
        assertTrue(db.setStep(id, sep, s4, Cents(0)))

        // Now s5 is the only week not edited: locked, max 0.
        assertFalse(db.setStep(id, sep, s5, Cents(0)), "the locked week refuses any value")

        // Re-editing an edited week is allowed up to its max (60000 − 35000 − 0 − 0); the locked week absorbs the rest.
        assertTrue(db.setStep(id, sep, s1, Cents(24000)))
        assertEquals(
            listOf(
                W(s1, day(2026, 9, 6), 24000, true, 25000, false),
                W(s2, day(2026, 9, 13), 35000, true, 36000, false),
                W(s3, day(2026, 9, 20), 0, true, 1000, false),
                W(s4, day(2026, 9, 27), 0, true, 1000, false),
                W(s5, day(2026, 9, 30), 1000, false, 0, true),
            ),
            ws(db.month(id, sep, sep15)),
        )
    }

    @Test
    fun `the only week not edited is locked with max zero and refuses even its own step`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        for (start in listOf(s1, s2, s3, s4)) assertTrue(db.setStep(id, sep, start, Cents(10000)))
        val m = db.month(id, sep, sep15)
        // s5 takes 60000 − 4·10000 = 20000; each edited week's max = 60000 − 3·10000.
        assertEquals(
            listOf(
                W(s1, day(2026, 9, 6), 10000, true, 30000, false),
                W(s2, day(2026, 9, 13), 10000, true, 30000, false),
                W(s3, day(2026, 9, 20), 10000, true, 30000, false),
                W(s4, day(2026, 9, 27), 10000, true, 30000, false),
                W(s5, day(2026, 9, 30), 20000, false, 0, true),
            ),
            ws(m),
        )
        assertEquals(1, m.weeks.count { it.locked })
        val before = db.version.value
        for (v in listOf(0L, 1L, 20000L)) assertFalse(db.setStep(id, sep, s5, Cents(v)), "locked week, step $v")
        assertEquals(before, db.version.value)
        assertEquals(ws(m), ws(db.month(id, sep, sep15)))
    }

    // ---------------------------------------------------------------- One day weeks (spec §4: partial weeks)

    @Test
    fun `one day weeks at both ends of the month take their share and can be edited up to the limit`() = withDb { db ->
        val nov1 = day(2026, 11, 1)
        val nov = ym(2026, 11)
        val id = db.budget("Lazer", 30000, nov1)
        // Nov 2026: Sun 1 (1 day) · 2–8 · 9–15 · 16–22 · 23–29 · Mon 30 (1 day); 30000 / 30 days = 1000 per day.
        val defaults = listOf(
            W(nov1, nov1, 1000, false, 30000, false),
            W(day(2026, 11, 2), day(2026, 11, 8), 7000, false, 30000, false),
            W(day(2026, 11, 9), day(2026, 11, 15), 7000, false, 30000, false),
            W(day(2026, 11, 16), day(2026, 11, 22), 7000, false, 30000, false),
            W(day(2026, 11, 23), day(2026, 11, 29), 7000, false, 30000, false),
            W(day(2026, 11, 30), day(2026, 11, 30), 1000, false, 30000, false),
        )
        assertEquals(defaults, ws(db.month(id, nov, nov1)))

        assertFalse(db.setStep(id, nov, nov1, Cents(30001)))
        assertTrue(db.setStep(id, nov, nov1, Cents(30000)))
        val m = db.month(id, nov, nov1)
        assertEquals(listOf(30000L, 0L, 0L, 0L, 0L, 0L), m.weeks.map { it.step.v })
        assertTrue(m.weeks.none { it.locked })

        assertTrue(db.clearStep(id, nov, nov1))
        assertEquals(defaults, ws(db.month(id, nov, nov1)))

        // The one day last week edited: the last week not edited (23–29) absorbs the cents.
        // 28000 over 29 days: 1 day → floor(28000/29) = 965; 7 days → floor(196000/29) = 6758;
        // 23–29 gets 28000 − 965 − 3·6758 = 6761.
        assertTrue(db.setStep(id, nov, day(2026, 11, 30), Cents(2000)))
        assertEquals(
            listOf(
                W(nov1, nov1, 965, false, 28000, false),
                W(day(2026, 11, 2), day(2026, 11, 8), 6758, false, 28000, false),
                W(day(2026, 11, 9), day(2026, 11, 15), 6758, false, 28000, false),
                W(day(2026, 11, 16), day(2026, 11, 22), 6758, false, 28000, false),
                W(day(2026, 11, 23), day(2026, 11, 29), 6761, false, 28000, false),
                W(day(2026, 11, 30), day(2026, 11, 30), 2000, true, 30000, false),
            ),
            ws(db.month(id, nov, nov1)),
        )
    }

    // ---------------------------------------------------------------- clearStep (spec §4: Voltar ao automático)

    @Test
    fun `clearStep on the only edited week brings back the default steps`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        val defaults = ws(db.month(id, sep, sep15))
        assertTrue(db.setStep(id, sep, s2, Cents(0)))
        // 60000 over the other 23 days: 6 days → floor(360000/23) = 15652; 7 days → floor(420000/23) = 18260;
        // the last (3 days) gets 60000 − 15652 − 2·18260 = 7828.
        assertEquals(listOf(15652L, 0L, 18260L, 18260L, 7828L), db.month(id, sep, sep15).weeks.map { it.step.v })

        val before = db.version.value
        assertTrue(db.clearStep(id, sep, s2))
        assertTrue(db.version.value > before, "clearStep that removed an edit bumps version")
        assertEquals(defaults, ws(db.month(id, sep, sep15)))
        assertEquals(listOf(12000L, 14000L, 14000L, 14000L, 6000L), defaults.map { it.step })

        val after = db.version.value
        assertFalse(db.clearStep(id, sep, s2), "no edit left")
        assertEquals(after, db.version.value)
    }

    @Test
    fun `clearStep refuses a week without edit a day that is not a week start another month and an unknown budget`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        val other = db.budget("Lazer", 60000, sep15)
        assertTrue(db.setStep(id, sep, s2, Cents(5000)))
        db.ensureMonths(ym(2026, 10))
        val m = db.month(id, sep, sep15)
        val before = db.version.value

        assertFalse(db.clearStep(id, sep, s3), "week not edited")
        assertFalse(db.clearStep(id, sep, day(2026, 9, 8)), "inside the edited week but not its start")
        assertFalse(db.clearStep(id, sep, day(2026, 8, 31)), "the calendar Monday before Sep 1 is not a week of September")
        assertFalse(db.clearStep(id, ym(2026, 10), s2), "Sep 7 is not a week of October")
        assertFalse(db.clearStep(other, sep, s2), "the other budget has no edit there")
        assertFalse(db.clearStep(BudgetId(999), sep, s2), "unknown budget")
        assertFalse(db.clearStep(BudgetId(0), sep, s2), "zero budget")

        assertEquals(before, db.version.value, "nothing changed")
        assertEquals(m, db.month(id, sep, sep15))
        assertTrue(db.month(id, sep, sep15).weeks[1].edited)
    }

    @Test
    fun `after clearStep the week that was locked is no longer locked`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        for (start in listOf(s1, s2, s3, s4)) assertTrue(db.setStep(id, sep, start, Cents(10000)))
        assertTrue(db.month(id, sep, sep15).weeks[4].locked)

        assertTrue(db.clearStep(id, sep, s2))
        // s2 (7 days) and s5 (3 days) share 60000 − 30000: 30000·7/10 = 21000 and 9000.
        // Max: edited weeks 60000 − 20000; automatic ones 60000 − 30000.
        assertEquals(
            listOf(
                W(s1, day(2026, 9, 6), 10000, true, 40000, false),
                W(s2, day(2026, 9, 13), 21000, false, 30000, false),
                W(s3, day(2026, 9, 20), 10000, true, 40000, false),
                W(s4, day(2026, 9, 27), 10000, true, 40000, false),
                W(s5, day(2026, 9, 30), 9000, false, 30000, false),
            ),
            ws(db.month(id, sep, sep15)),
        )
        assertTrue(db.setStep(id, sep, s5, Cents(0)), "the old locked week can be edited now")
        val m = db.month(id, sep, sep15)
        assertTrue(m.weeks[1].locked, "s2 is now the only week not edited")
        assertEquals(30000L, m.weeks[1].step.v)
    }

    // ---------------------------------------------------------------- limitDropsEdits (spec §4: aviso de cota)

    /** Sep 2026 budget of 600,00 with edits 200,00 (s1) + 150,00 (s2) = 350,00. */
    private suspend fun Db.edited(name: String): BudgetId {
        val id = budget(name, 60000, sep15)
        assertTrue(setStep(id, sep, s1, Cents(20000)))
        assertTrue(setStep(id, sep, s2, Cents(15000)))
        return id
    }

    @Test
    fun `limitDropsEdits is false when the edits sum exactly the new limit and true one cent below like setLimit`() = withDb { db ->
        val a = db.edited("A")
        val b = db.edited("B")

        assertFalse(db.limitDropsEdits(a, Cents(35000), sep15), "Σ edited = new limit is not above it")
        assertTrue(db.setLimit(a, Cents(35000), sep15))
        val ma = db.month(a, sep, sep15)
        assertEquals(35000L, ma.limit.v)
        assertEquals(listOf(20000L, 15000L, 0L, 0L, 0L), ma.weeks.map { it.step.v })
        assertEquals(listOf(true, true, false, false, false), ma.weeks.map { it.edited })

        val before = db.version.value
        assertTrue(db.limitDropsEdits(b, Cents(34999), sep15))
        assertEquals(before, db.version.value, "limitDropsEdits writes nothing")
        assertEquals(listOf(true, true, false, false, false), db.month(b, sep, sep15).weeks.map { it.edited })
        assertTrue(db.setLimit(b, Cents(34999), sep15))
        // Every edit gone; defaults of 34999 in 30 days: 6 days → 6999; 7 days → 8166; last gets 34999 − 6999 − 3·8166 = 3502.
        val mb = db.month(b, sep, sep15)
        assertTrue(mb.weeks.none { it.edited })
        assertEquals(listOf(6999L, 8166L, 8166L, 8166L, 3502L), mb.weeks.map { it.step.v })

        // A keeps its edits, and the same rule still holds on it.
        assertFalse(db.limitDropsEdits(a, Cents(35000), sep15))
        assertTrue(db.limitDropsEdits(a, Cents(34999), sep15))
        assertFalse(db.limitDropsEdits(b, Cents(1), sep15), "B has no edits left")
    }

    @Test
    fun `limitDropsEdits ignores edits of past months and setLimit keeps them`() = withDb { db ->
        val id = db.edited("Mercado")
        val oct5 = day(2026, 10, 5)
        db.ensureMonths(ym(2026, 10))

        assertTrue(db.limitDropsEdits(id, Cents(34999), day(2026, 9, 30)), "still September on Sep 30")
        assertFalse(db.limitDropsEdits(id, Cents(1), oct5), "September is past on Oct 5")
        assertTrue(db.setLimit(id, Cents(1), oct5))

        val past = db.month(id, sep, oct5)
        assertEquals(60000L, past.limit.v, "earlier months keep their limit (spec §3)")
        assertEquals(listOf(20000L, 15000L), past.weeks.filter { it.edited }.map { it.step.v })
        assertEquals(1L, db.month(id, ym(2026, 10), oct5).limit.v)
    }

    @Test
    fun `limitDropsEdits is false for a limit setLimit refuses or an unknown budget`() = withDb { db ->
        val id = db.edited("Mercado")
        val before = db.version.value
        for (v in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) assertFalse(db.limitDropsEdits(id, Cents(v), sep15), "limit $v")
        assertFalse(db.limitDropsEdits(BudgetId(999), Cents(1), sep15))
        assertFalse(db.limitDropsEdits(BudgetId(0), Cents(1), sep15))
        assertEquals(before, db.version.value)
        assertEquals(listOf(true, true, false, false, false), db.month(id, sep, sep15).weeks.map { it.edited })
    }

    // ---------------------------------------------------------------- previewBudget (book 10.7)

    @Test
    fun `previewBudget is the default month of a new budget and equals its detail`() = withDb { db ->
        val before = db.version.value
        val preview = db.previewBudget(Cents(60000), sep15)
        assertEquals(before, db.version.value, "previewBudget writes nothing")
        assertTrue(db.budgetsOverview(sep, sep15, BudgetOrder.AZ).isEmpty())

        // Ideal of Sep 15 (§5): closed weeks 12000 + 14000, plus 14000 × 2 days (14, 15) ÷ 7 = 4000 → 30000.
        val expected = BudgetMonth(
            month = sep, start = s1, end = day(2026, 9, 30), limit = Cents(60000), spent = Cents(0), remaining = Cents(60000), freeLimit = Cents(60000), variable = Cents(0),
            weeks = listOf(
                fresh(s1, day(2026, 9, 6), 12000, 60000),
                fresh(s2, day(2026, 9, 13), 14000, 60000),
                fresh(s3, day(2026, 9, 20), 14000, 60000),
                fresh(s4, day(2026, 9, 27), 14000, 60000),
                fresh(s5, day(2026, 9, 30), 6000, 60000),
            ),
            ideal = Cents(30000), status = PaceStatus.ON_PACE, projection = Cents(0),
        )
        assertEquals(expected, preview.withoutDaily())

        val id = db.budget("Mercado", 60000, sep15)
        assertEquals(preview, db.month(id, sep, sep15))
    }

    @Test
    fun `previewBudget of one cent puts the cent in the last week`() = withDb { db ->
        val preview = db.previewBudget(Cents(1), sep15)
        // Every share rounds down to 0; the last week not edited gets the difference (spec §4). Ideal 0 → no projection.
        val expected = BudgetMonth(
            month = sep, start = s1, end = day(2026, 9, 30), limit = Cents(1), spent = Cents(0), remaining = Cents(1), freeLimit = Cents(1), variable = Cents(0),
            weeks = listOf(
                fresh(s1, day(2026, 9, 6), 0, 1),
                fresh(s2, day(2026, 9, 13), 0, 1),
                fresh(s3, day(2026, 9, 20), 0, 1),
                fresh(s4, day(2026, 9, 27), 0, 1),
                fresh(s5, day(2026, 9, 30), 1, 1),
            ),
            ideal = Cents(0), status = PaceStatus.ON_PACE, projection = Cents(0),
        )
        assertEquals(expected, preview.withoutDaily())
        val id = db.budget("Centavo", 1, sep15)
        assertEquals(preview, db.month(id, sep, sep15))
    }

    @Test
    fun `previewBudget is zero for a limit of zero or less and writes nothing`() = withDb { db ->
        val before = db.version.value
        for (v in listOf(0L, -1L, -60000L, Long.MIN_VALUE)) {
            assertEquals(BudgetMonth(), db.previewBudget(Cents(v), sep15), "limit $v")
            assertEquals(BudgetId(0), db.createBudget("X$v", Cents(v), sep15))
        }
        assertEquals(before, db.version.value)
        assertTrue(db.budgetsOverview(sep, sep15, BudgetOrder.AZ).isEmpty())
    }

    @Test
    fun `previewBudget at the largest limits is zero exactly when createBudget refuses them`() = withDb { db ->
        // The contract only says "above the maximum" without naming it: the preview must be zero exactly
        // when createBudget refuses, and equal to the created budget's month otherwise.
        val big = parseBrl("999999999999999,99")
        assertTrue(big.v > 0)
        for ((i, limit) in listOf(big, Cents(big.v + 1), Cents(Long.MAX_VALUE)).withIndex()) {
            val preview = db.previewBudget(limit, sep15)
            val id = db.createBudget("Grande $i", limit, sep15)
            if (id.v == 0L) {
                assertEquals(BudgetMonth(), preview, "refused limit ${limit.v}")
            } else {
                assertEquals(limit, preview.limit)
                assertEquals(limit.v, preview.weeks.sumOf { it.step.v }, "Σ steps = limit ${limit.v}")
                assertEquals(limit, preview.remaining)
                assertEquals(preview, db.month(id, sep, sep15))
            }
        }
    }

    // ---------------------------------------------------------------- Month start day 29–31 (spec §3)

    @Test
    fun `month start day 31 in february in previewBudget and budgetDetail`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2027, 1, 5)))
        val feb10 = day(2027, 2, 10)
        // Feb 10 2027 falls in 2027-01: Jan 31 .. Feb 27 (Feb 31 → Feb 28, eve 27), 28 days → 1000 per day.
        // Weeks: Sun Jan 31 (1) · Feb 1–7 · 8–14 · 15–21 · 22–27 (6).
        // Ideal of Feb 10: 1000 + 7000 closed, plus 7000 × 3 days (8, 9, 10) ÷ 7 = 3000 → 11000.
        val expected = BudgetMonth(
            month = ym(2027, 1), start = day(2027, 1, 31), end = day(2027, 2, 27),
            limit = Cents(28000), spent = Cents(0), remaining = Cents(28000), freeLimit = Cents(28000), variable = Cents(0),
            weeks = listOf(
                fresh(day(2027, 1, 31), day(2027, 1, 31), 1000, 28000),
                fresh(day(2027, 2, 1), day(2027, 2, 7), 7000, 28000),
                fresh(day(2027, 2, 8), day(2027, 2, 14), 7000, 28000),
                fresh(day(2027, 2, 15), day(2027, 2, 21), 7000, 28000),
                fresh(day(2027, 2, 22), day(2027, 2, 27), 6000, 28000),
            ),
            ideal = Cents(11000), status = PaceStatus.ON_PACE, projection = Cents(0),
        )
        val preview = db.previewBudget(Cents(28000), feb10)
        assertEquals(expected, preview.withoutDaily())

        val id = db.budget("Mercado", 28000, feb10)
        val d = db.detail(id, ym(2027, 1), feb10)
        assertEquals(preview, d.month)
        assertEquals(ym(2027, 1), d.firstMonth)

        // 2027-02: Feb 28 .. Mar 30 (eve of Mar 31), 31 days: Sun Feb 28 (1) · Mar 1–7 · 8–14 · 15–21 · 22–28 · 29–30 (2).
        // 28000 copied: 1 day → floor(28000/31) = 903; 7 days → floor(196000/31) = 6322; last gets 28000 − 903 − 4·6322 = 1809.
        val feb = db.month(id, ym(2027, 2), day(2027, 3, 10))
        assertEquals(day(2027, 2, 28), feb.start)
        assertEquals(day(2027, 3, 30), feb.end)
        assertEquals(
            listOf(
                day(2027, 2, 28) to day(2027, 2, 28), day(2027, 3, 1) to day(2027, 3, 7), day(2027, 3, 8) to day(2027, 3, 14),
                day(2027, 3, 15) to day(2027, 3, 21), day(2027, 3, 22) to day(2027, 3, 28), day(2027, 3, 29) to day(2027, 3, 30),
            ),
            feb.weeks.map { it.start to it.end },
        )
        assertEquals(listOf(903L, 6322L, 6322L, 6322L, 6322L, 1809L), feb.weeks.map { it.step.v })
    }

    @Test
    fun `month start day 30 in january and february in previewBudget`() = withDb { db ->
        assertTrue(db.setMonthStartDay(30, day(2027, 1, 5)))
        // Jan 30 2027 starts 2027-01: Jan 30 .. Feb 27 (Feb 30 → Feb 28, eve 27), 29 days.
        // Weeks: Sat–Sun Jan 30–31 (2) · Feb 1–7 · 8–14 · 15–21 · 22–27 (6). Ideal of Jan 30: 2000 × 1 ÷ 2 = 1000.
        val jan30 = day(2027, 1, 30)
        assertEquals(
            BudgetMonth(
                month = ym(2027, 1), start = jan30, end = day(2027, 2, 27),
                limit = Cents(29000), spent = Cents(0), remaining = Cents(29000), freeLimit = Cents(29000), variable = Cents(0),
                weeks = listOf(
                    fresh(jan30, day(2027, 1, 31), 2000, 29000),
                    fresh(day(2027, 2, 1), day(2027, 2, 7), 7000, 29000),
                    fresh(day(2027, 2, 8), day(2027, 2, 14), 7000, 29000),
                    fresh(day(2027, 2, 15), day(2027, 2, 21), 7000, 29000),
                    fresh(day(2027, 2, 22), day(2027, 2, 27), 6000, 29000),
                ),
                ideal = Cents(1000), status = PaceStatus.ON_PACE, projection = Cents(0),
            ),
            db.previewBudget(Cents(29000), jan30).withoutDaily(),
        )
        // Feb 28 2027 starts 2027-02: Feb 28 .. Mar 29 (eve of Mar 30), 30 days.
        // Weeks: Sun Feb 28 (1) · Mar 1–7 · 8–14 · 15–21 · 22–28 · Mon Mar 29 (1). Ideal of Feb 28: 1000.
        val feb28 = day(2027, 2, 28)
        assertEquals(
            BudgetMonth(
                month = ym(2027, 2), start = feb28, end = day(2027, 3, 29),
                limit = Cents(30000), spent = Cents(0), remaining = Cents(30000), freeLimit = Cents(30000), variable = Cents(0),
                weeks = listOf(
                    fresh(feb28, feb28, 1000, 30000),
                    fresh(day(2027, 3, 1), day(2027, 3, 7), 7000, 30000),
                    fresh(day(2027, 3, 8), day(2027, 3, 14), 7000, 30000),
                    fresh(day(2027, 3, 15), day(2027, 3, 21), 7000, 30000),
                    fresh(day(2027, 3, 22), day(2027, 3, 28), 7000, 30000),
                    fresh(day(2027, 3, 29), day(2027, 3, 29), 1000, 30000),
                ),
                ideal = Cents(1000), status = PaceStatus.ON_PACE, projection = Cents(0),
            ),
            db.previewBudget(Cents(30000), feb28).withoutDaily(),
        )
    }

    @Test
    fun `month start day 29 in a leap february`() = withDb { db ->
        assertTrue(db.setMonthStartDay(29, day(2028, 1, 5)))
        // Feb 28 2028 is the last day of 2028-01: Jan 29 .. Feb 28 (eve of Feb 29), 31 days.
        // Weeks: Sat–Sun Jan 29–30 (2) · Jan 31–Feb 6 · Feb 7–13 · 14–20 · 21–27 · Mon Feb 28 (1).
        // On the last day the ideal is every step: 31000.
        val feb28 = day(2028, 2, 28)
        val jan = BudgetMonth(
            month = ym(2028, 1), start = day(2028, 1, 29), end = feb28,
            limit = Cents(31000), spent = Cents(0), remaining = Cents(31000), freeLimit = Cents(31000), variable = Cents(0),
            weeks = listOf(
                fresh(day(2028, 1, 29), day(2028, 1, 30), 2000, 31000),
                fresh(day(2028, 1, 31), day(2028, 2, 6), 7000, 31000),
                fresh(day(2028, 2, 7), day(2028, 2, 13), 7000, 31000),
                fresh(day(2028, 2, 14), day(2028, 2, 20), 7000, 31000),
                fresh(day(2028, 2, 21), day(2028, 2, 27), 7000, 31000),
                fresh(feb28, feb28, 1000, 31000),
            ),
            ideal = Cents(31000), status = PaceStatus.ON_PACE, projection = Cents(0),
        )
        assertEquals(jan, db.previewBudget(Cents(31000), feb28).withoutDaily())
        val id = db.budget("Mercado", 31000, feb28)
        val d = db.detail(id, ym(2028, 1), feb28)
        assertEquals(jan, d.month.withoutDaily())
        assertEquals(ym(2028, 1), d.firstMonth)

        // Feb 29 2028 starts 2028-02: Feb 29 .. Mar 28, 29 days.
        // Weeks: Tue Feb 29–Sun Mar 5 (6) · Mar 6–12 · 13–19 · 20–26 · Mon–Tue Mar 27–28 (2). Ideal: 6000 × 1 ÷ 6 = 1000.
        val feb29 = day(2028, 2, 29)
        assertEquals(
            BudgetMonth(
                month = ym(2028, 2), start = feb29, end = day(2028, 3, 28),
                limit = Cents(29000), spent = Cents(0), remaining = Cents(29000), freeLimit = Cents(29000), variable = Cents(0),
                weeks = listOf(
                    fresh(feb29, day(2028, 3, 5), 6000, 29000),
                    fresh(day(2028, 3, 6), day(2028, 3, 12), 7000, 29000),
                    fresh(day(2028, 3, 13), day(2028, 3, 19), 7000, 29000),
                    fresh(day(2028, 3, 20), day(2028, 3, 26), 7000, 29000),
                    fresh(day(2028, 3, 27), day(2028, 3, 28), 2000, 29000),
                ),
                ideal = Cents(1000), status = PaceStatus.ON_PACE, projection = Cents(0),
            ),
            db.previewBudget(Cents(29000), feb29).withoutDaily(),
        )
    }

    @Test
    fun `month start day 29 in a february without day 29`() = withDb { db ->
        assertTrue(db.setMonthStartDay(29, day(2027, 1, 5)))
        // Feb 27 2027 is the last day of 2027-01: Jan 29 .. Feb 27 (Feb 29 → Feb 28, eve 27), 30 days.
        // Weeks: Fri–Sun Jan 29–31 (3) · Feb 1–7 · 8–14 · 15–21 · 22–27 (6). Last day: ideal = 30000.
        val feb27 = day(2027, 2, 27)
        val jan = BudgetMonth(
            month = ym(2027, 1), start = day(2027, 1, 29), end = feb27,
            limit = Cents(30000), spent = Cents(0), remaining = Cents(30000), freeLimit = Cents(30000), variable = Cents(0),
            weeks = listOf(
                fresh(day(2027, 1, 29), day(2027, 1, 31), 3000, 30000),
                fresh(day(2027, 2, 1), day(2027, 2, 7), 7000, 30000),
                fresh(day(2027, 2, 8), day(2027, 2, 14), 7000, 30000),
                fresh(day(2027, 2, 15), day(2027, 2, 21), 7000, 30000),
                fresh(day(2027, 2, 22), feb27, 6000, 30000),
            ),
            ideal = Cents(30000), status = PaceStatus.ON_PACE, projection = Cents(0),
        )
        assertEquals(jan, db.previewBudget(Cents(30000), feb27).withoutDaily())
        val id = db.budget("Mercado", 30000, feb27)
        assertEquals(jan, db.month(id, ym(2027, 1), feb27).withoutDaily())

        // 2027-02: Feb 28 .. Mar 28 (eve of Mar 29), 29 days: Sun Feb 28 (1) · Mar 1–7 · 8–14 · 15–21 · 22–28.
        // 30000 copied: 1 day → floor(30000/29) = 1034; 7 days → floor(210000/29) = 7241; last gets 30000 − 1034 − 3·7241 = 7243.
        val feb = db.month(id, ym(2027, 2), day(2027, 3, 10))
        assertEquals(day(2027, 2, 28), feb.start)
        assertEquals(day(2027, 3, 28), feb.end)
        assertEquals(
            listOf(day(2027, 2, 28), day(2027, 3, 1), day(2027, 3, 8), day(2027, 3, 15), day(2027, 3, 22)),
            feb.weeks.map { it.start },
        )
        assertEquals(listOf(1034L, 7241L, 7241L, 7241L, 7243L), feb.weeks.map { it.step.v })

        // A preview on Feb 28 is already 2027-02, with its own 29 days: 1000 + 4·7000.
        val p = db.previewBudget(Cents(29000), day(2027, 2, 28))
        assertEquals(ym(2027, 2), p.month)
        assertEquals(listOf(1000L, 7000L, 7000L, 7000L, 7000L), p.weeks.map { it.step.v })
        assertEquals(Cents(1000), p.ideal)
    }

    // ---------------------------------------------------------------- firstMonth (spec §7.1: month picker)

    @Test
    fun `firstMonth of a budget created mid month stays across skipped months and limit changes`() = withDb { db ->
        val oct15 = day(2026, 10, 15)
        val id = db.budget("Mercado", 60000, oct15)
        val oct = db.detail(id, ym(2026, 10), oct15)
        assertEquals(ym(2026, 10), oct.firstMonth)
        // Created mid month: the whole month with the full limit (spec §3).
        assertEquals(day(2026, 10, 1), oct.month.start)
        assertEquals(day(2026, 10, 31), oct.month.end)
        assertEquals(60000L, oct.month.limit.v)

        db.ensureMonths(ym(2027, 6)) // skips Nov..May
        val jun = db.detail(id, ym(2027, 6), day(2027, 6, 10))
        assertEquals(ym(2026, 10), jun.firstMonth)
        assertEquals(ym(2027, 6), jun.month.month)
        assertEquals(60000L, jun.month.limit.v)
        val feb = db.detail(id, ym(2027, 2), day(2027, 6, 10))
        assertEquals(60000L, feb.month.limit.v, "skipped months are filled with the limit")
        assertEquals(ym(2026, 10), feb.firstMonth)

        assertTrue(db.setLimit(id, Cents(50000), day(2027, 6, 10)))
        assertEquals(ym(2026, 10), db.detail(id, ym(2027, 6), day(2027, 6, 10)).firstMonth)
    }

    @Test
    fun `firstMonth with month start day 20 is the budget month the creation day falls in`() = withDb { db ->
        val oct15 = day(2026, 10, 15)
        assertTrue(db.setMonthStartDay(20, oct15))
        // Oct 15 falls in 2026-09: Sep 20 .. Oct 19, 30 days. Weeks: Sun Sep 20 (1) · Sep 21–27 · Sep 28–Oct 4 ·
        // Oct 5–11 · Oct 12–18 · Mon Oct 19 (1). Full limit for the whole month even created on Oct 15 (spec §3);
        // ideal of Oct 15 counts the weeks already past: 1000 + 3·7000 + 7000 × 4 days (12..15) ÷ 7 = 26000.
        val expected = BudgetMonth(
            month = ym(2026, 9), start = day(2026, 9, 20), end = day(2026, 10, 19),
            limit = Cents(30000), spent = Cents(0), remaining = Cents(30000), freeLimit = Cents(30000), variable = Cents(0),
            weeks = listOf(
                fresh(day(2026, 9, 20), day(2026, 9, 20), 1000, 30000),
                fresh(day(2026, 9, 21), day(2026, 9, 27), 7000, 30000),
                fresh(day(2026, 9, 28), day(2026, 10, 4), 7000, 30000),
                fresh(day(2026, 10, 5), day(2026, 10, 11), 7000, 30000),
                fresh(day(2026, 10, 12), day(2026, 10, 18), 7000, 30000),
                fresh(day(2026, 10, 19), day(2026, 10, 19), 1000, 30000),
            ),
            ideal = Cents(26000), status = PaceStatus.ON_PACE, projection = Cents(0),
        )
        assertEquals(expected, db.previewBudget(Cents(30000), oct15).withoutDaily())
        val a = db.budget("A", 30000, oct15)
        val d = db.detail(a, ym(2026, 9), oct15)
        assertEquals(ym(2026, 9), d.firstMonth)
        assertEquals(expected, d.month.withoutDaily())

        val oct20 = day(2026, 10, 20)
        val b = db.budget("B", 30000, oct20)
        assertEquals(ym(2026, 10), db.detail(b, ym(2026, 10), oct20).firstMonth)

        // "By the month start day of that time": changing the day later doesn't move it.
        assertTrue(db.setMonthStartDay(1, oct20))
        assertEquals(ym(2026, 9), db.detail(a, ym(2026, 10), oct20).firstMonth)
    }

    @Test
    fun `budgetDetail of an unknown budget is zero`() = withDb { db ->
        db.budget("Mercado", 60000, sep15)
        db.ensureMonths(sep)
        assertEquals(BudgetDetail(), db.budgetDetail(BudgetId(999), sep, sep15))
        assertEquals(BudgetDetail(), db.budgetDetail(BudgetId(0), sep, sep15))
        assertEquals(NO_MONTH, db.budgetDetail(BudgetId(999), sep, sep15).firstMonth)
    }

    // ---------------------------------------------------------------- MonthLine (spec §6, §7.1)

    @Test
    fun `a cash purchase is one line with k 1 its date and its total`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        val t = db.spend(id, 4590, day(2026, 9, 10))
        val d = db.detail(id, sep, sep15)
        assertEquals(id, d.id)
        assertEquals("Mercado", d.name)
        assertEquals(listOf(MonthLine(row = db.transaction(t).row, k = 1, date = day(2026, 9, 10), cents = Cents(4590))), d.lines)
        assertEquals(1, d.lines.single().row.installments)
        assertEquals(Cents(4590), d.month.spent)
    }

    @Test
    fun `installment lines carry k date and value per month and the last takes the cents`() = withDb { db ->
        val id = db.budget("Casa", 60000, sep15)
        // R$ 100,00 in 3×: 33,33 · 33,33 · 33,34 (spec §6: the last absorbs the cents), dated Sep 20, Oct 20, Nov 20.
        val t = db.spend(id, 10000, day(2026, 9, 20), installments = 3)
        val expected = listOf(
            Triple(sep, day(2026, 9, 20), 3333L),
            Triple(ym(2026, 10), day(2026, 10, 20), 3333L),
            Triple(ym(2026, 11), day(2026, 11, 20), 3334L),
        )
        for ((i, e) in expected.withIndex()) {
            val (month, date, cents) = e
            val d = db.detail(id, month, sep15)
            val line = d.lines.single()
            assertEquals(t, line.row.id)
            assertEquals(i + 1, line.k, "k in $month")
            assertEquals(date, line.date, "date in $month")
            assertEquals(Cents(cents), line.cents, "cents in $month")
            assertEquals(3, line.row.installments)
            assertEquals(Cents(10000), line.row.total)
            assertEquals(Cents(cents), d.month.spent)
        }
        assertTrue(db.detail(id, ym(2026, 12), sep15).lines.isEmpty())
    }

    @Test
    fun `installment lines with month start day 31 land inside their own month`() = withDb { db ->
        val jan5 = day(2027, 1, 5)
        assertTrue(db.setMonthStartDay(31, jan5))
        val id = db.budget("Casa", 60000, jan5) // 2026-12: Dec 31 .. Jan 30
        // Bought Jan 30 2027 (2026-12) in 3× 30,00. 2nd month 2027-01 = Jan 31 .. Feb 27: Jan 30 + 1 month falls
        // outside it, so the nearest day inside, Feb 27 (D7, KDoc of accept). 3rd month 2027-02 = Feb 28 .. Mar 30: Mar 30.
        val t = db.spend(id, 9000, day(2027, 1, 30), installments = 3)
        val expected = listOf(
            Triple(ym(2026, 12), 1, day(2027, 1, 30)),
            Triple(ym(2027, 1), 2, day(2027, 2, 27)),
            Triple(ym(2027, 2), 3, day(2027, 3, 30)),
        )
        for ((month, k, date) in expected) {
            val d = db.detail(id, month, jan5)
            val line = d.lines.single()
            assertEquals(t, line.row.id)
            assertEquals(k, line.k, "k in $month")
            assertEquals(date, line.date, "date in $month")
            assertTrue(line.date >= d.month.start && line.date <= d.month.end, "the installment date is inside $month")
            assertEquals(Cents(3000), line.cents)
            assertEquals(Cents(3000), d.month.spent)
        }
    }

    @Test
    fun `refunded lines show up and do not add to spent`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        val kept = db.spend(id, 1000, day(2026, 9, 3))
        val cash = db.spend(id, 2500, day(2026, 9, 8))
        val split = db.spend(id, 9000, day(2026, 9, 15), installments = 3)
        assertTrue(db.registerRefund(db.estorno("e1", 1_000), cash))
        assertTrue(db.registerRefund(db.estorno("e2", 2_000), split))

        val d = db.detail(id, sep, sep15)
        assertEquals(listOf(kept, cash, split), d.lines.map { it.row.id })
        assertEquals(listOf(false, true, true), d.lines.map { it.row.refunded })
        assertEquals(listOf(1000L, 2500L, 3000L), d.lines.map { it.cents.v }, "a refunded line keeps its value")
        assertEquals(Cents(1000), d.month.spent)
        assertEquals(d.month.spent.v, d.lines.filter { !it.row.refunded }.sumOf { it.cents.v })

        // §6: the refund of an installment purchase takes every installment out.
        val oct = db.detail(id, ym(2026, 10), sep15)
        val line = oct.lines.single()
        assertEquals(split, line.row.id)
        assertEquals(2, line.k)
        assertTrue(line.row.refunded)
        assertEquals(Cents(3000), line.cents)
        assertEquals(Cents(0), oct.month.spent)
    }

    @Test
    fun `month lines are oldest first by installment date then time then id`() = withDb { db ->
        val id = db.budget("Mercado", 60000, sep15)
        val other = db.budget("Lazer", 60000, sep15)
        val late = db.spend(id, 5000, day(2026, 9, 25), time = LocalTime(7, 0))
        val noonA = db.spend(id, 1000, day(2026, 9, 3), time = LocalTime(12, 0))
        val noonB = db.spend(id, 2000, day(2026, 9, 3), time = LocalTime(12, 0))
        val morning = db.spend(id, 3000, day(2026, 9, 3), time = LocalTime(8, 0))
        // Bought Aug 5 in 2×: its 2nd installment is dated Sep 5 and sorts by that date.
        val fromAug = db.spend(id, 10000, day(2026, 8, 5), installments = 2, time = LocalTime(23, 0))
        db.spend(other, 7000, day(2026, 9, 1)) // another budget
        db.spend(id, 7000, day(2026, 10, 1)) // another month

        val d = db.detail(id, sep, sep15)
        assertEquals(listOf(morning, noonA, noonB, fromAug, late), d.lines.map { it.row.id })
        assertEquals(listOf(1, 1, 1, 2, 1), d.lines.map { it.k })
        assertEquals(
            listOf(day(2026, 9, 3), day(2026, 9, 3), day(2026, 9, 3), day(2026, 9, 5), day(2026, 9, 25)),
            d.lines.map { it.date },
        )
        assertEquals(listOf(3000L, 1000L, 2000L, 5000L, 5000L), d.lines.map { it.cents.v })
        assertEquals(Cents(16000), d.month.spent)
        assertEquals(d.month.spent.v, d.lines.sumOf { it.cents.v })
    }

    private companion object {
        const val BANK = "com.banco.teste"
    }
}

/** These expectations predate BudgetMonth.idealByDay (D44, block 6), which LeituraEdgeTest and MonthTest cover. */
private fun BudgetMonth.withoutDaily() = copy(idealByDay = emptyList())
