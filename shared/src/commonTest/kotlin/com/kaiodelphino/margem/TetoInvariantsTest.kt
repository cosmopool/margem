package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Invariants of the teto (spec §3, §7.1; glossary Teto; architecture D42, D46, §5), checked through the
// public Db API only, after every step of random (fixed-seed) action sequences: budgets created, deleted
// and re-limited, purchases, setCeiling with both scopes (0 included), the two-call Desfazer, previewCeiling
// probes, `today` moving forward (with ensureMonths, as the screens do) and the month start day changing.
//
// The test keeps a small model of the ceiling rows and the default, built only from the KDoc of
// ensureMonths and setCeiling (D42): ensureMonths fills [month] and the months skipped since the latest
// ceiling row before it with the default (only [month] when there is no earlier row); setCeiling first does
// the same up to the current month, then writes the current month's row and, onward, the default, deleting
// the later rows. A month without a row reads 0.

private val TINV_START = LocalDate(2026, 10, 14)
private val TINV_TAGS = listOf("feira", "aluguel", "cinema", "uber")
private val TINV_UNKNOWN = BudgetId(987_655)

private fun YearMonth.tinvShift(n: Int): YearMonth = if (n >= 0) plus(n, DateTimeUnit.MONTH) else minus(-n, DateTimeUnit.MONTH)

/** A real SQLite file per test, deleted afterwards with its -wal and -shm. */
private suspend fun withTetoDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-tinv-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))
        try {
            block(db)
        } finally {
            db.close()
        }
    } finally {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            runCatching { SystemFileSystem.delete(Path(path + suffix), mustExist = false) }
        }
    }
}

private class TetoInvariantRun(val db: Db, val seed: Int) {
    val rnd = Random(seed)
    val log = ArrayList<String>()
    var step = 0
    var today = TINV_START

    /** Model (D42): ceiling row per month and the default. A month missing here reads 0. */
    val rows = HashMap<YearMonth, Long>()
    var standard = 0L

    /** Whether ensureMonths ran for today's month since it last changed (screens call it before reading). */
    var ensured = false
    val live = ArrayList<BudgetId>()
    val gone = ArrayList<BudgetId>()
    var created = 0

    fun log(s: String) {
        log.add("#$step [$today] $s")
    }

    suspend fun run(steps: Int) {
        try {
            setup()
            checkAll()
            for (i in 1..steps) {
                step = i
                act()
                checkAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AssertionError("seed=$seed step=$step today=$today: $e\n--- actions so far ---\n${log.joinToString("\n")}", e)
        }
    }

    suspend fun setup() {
        log("setup")
        ensureCurrent()
        createBudget(80_000)
        createBudget(150_000)
        createBudget(30_000)
        repeat(6) { addManualAction() }
    }

    suspend fun monthNow(): YearMonth = db.monthOf(today)

    // ---- model of ensureMonths (KDoc, D42, architecture §5) ----

    fun modelEnsure(month: YearMonth) {
        val before = rows.keys.filter { it < month }.maxOrNull()
        if (before == null) {
            if (month !in rows) rows[month] = standard
            return
        }
        var m = before.tinvShift(1)
        while (m <= month) {
            if (m !in rows) rows[m] = standard
            m = m.tinvShift(1)
        }
    }

    /** ensureMonths never bumps version (D6, D30) and fills ceiling rows as the model says. */
    suspend fun ensure(month: YearMonth) {
        val v0 = db.version.value
        db.ensureMonths(month)
        assertEquals(v0, db.version.value, "ensureMonths($month) doesn't bump version")
        modelEnsure(month)
        log("ensureMonths $month")
    }

    suspend fun ensureCurrent() {
        ensure(monthNow())
        ensured = true
    }

    // ---- actions ----

    suspend fun act() {
        val r = rnd.nextInt(100)
        when {
            r < 8 -> createBudgetAction()
            r < 20 -> setLimitAction()
            r < 24 -> deleteBudgetAction()
            r < 32 -> addManualAction()
            r < 60 -> setCeilingAction()
            r < 64 -> refusedCeilingAction()
            r < 74 -> previewAction()
            r < 77 -> ensureFutureAction()
            r < 81 -> setMonthStartDayAction()
            else -> advanceTodayAction()
        }
    }

    suspend fun createBudget(limit: Long) {
        val m = monthNow()
        val before = db.ceiling(m)
        val preview = db.previewCeiling(BudgetId(0), Cents(limit), today)
        created++
        val id = db.createBudget("Verba $created", Cents(limit), today)
        log("createBudget \"Verba $created\" $limit -> ${id.v}")
        // The teto never refuses: a fresh name with limit > 0 is created whatever the sum becomes (spec §3).
        assertNotEquals(0L, id.v, "createBudget with a new name and limit $limit is accepted, ceiling or not")
        live.add(id)
        val after = db.ceiling(m)
        assertEquals(before.cents, after.cents, "createBudget doesn't change the ceiling of $m")
        assertEquals(before.standard, after.standard, "createBudget doesn't change the default")
        assertEquals(preview.limits, after.limits, "previewCeiling(0, $limit) before == ceiling($m).limits after createBudget")
    }

    suspend fun createBudgetAction() {
        createBudget(5_000L + rnd.nextLong(400_000))
    }

    suspend fun setLimitAction() {
        if (live.isEmpty()) return
        val id = live.random(rnd)
        val limit = 5_000L + rnd.nextLong(400_000)
        val m = monthNow()
        val hasRow = db.budgetsOverview(m, today, BudgetOrder.MANUAL).any { it.id == id }
        val before = db.ceiling(m)
        val preview = db.previewCeiling(id, Cents(limit), today)
        val ok = db.setLimit(id, Cents(limit), today)
        log("setLimit ${id.v} $limit -> $ok")
        // Nothing about the teto is ever refused (spec §3): setLimit only refuses limit ≤ 0 or unknown budgets.
        assertTrue(ok, "setLimit(${id.v}, $limit) accepted, ceiling or not")
        val after = db.ceiling(m)
        assertEquals(before.cents, after.cents, "setLimit doesn't change the ceiling of $m")
        assertEquals(before.standard, after.standard, "setLimit doesn't change the default")
        if (hasRow) assertEquals(preview.limits, after.limits, "previewCeiling(${id.v}, $limit) before == ceiling($m).limits after setLimit")
    }

    suspend fun deleteBudgetAction() {
        if (live.isEmpty()) return
        val id = live.random(rnd)
        db.deleteBudget(id)
        log("deleteBudget ${id.v}")
        live.remove(id)
        gone.add(id)
    }

    suspend fun addManualAction() {
        if (live.isEmpty()) return
        val b = live.random(rnd)
        val form = TransactionForm(
            budget = b, tags = listOf(TINV_TAGS.random(rnd)), total = Cents(100L + rnd.nextLong(200_000)),
            installments = listOf(1, 1, 1, 2, 3, 6).random(rnd), merchant = "LOJA ${rnd.nextInt(5)}",
            date = today.plus(rnd.nextInt(-40, 6), DateTimeUnit.DAY), time = LocalTime(rnd.nextInt(24), rnd.nextInt(60)),
        )
        val id = db.addManual(form)
        log("addManual budget=${b.v} ${form.total.v} in ${form.installments} on ${form.date} -> ${id.v}")
        assertNotEquals(0L, id.v, "addManual of a valid form")
    }

    /** The budgets as every screen reads them, around today's month: the teto must leave all of it alone. */
    suspend fun budgetsSnapshot(m0: YearMonth): List<Any> {
        val out = ArrayList<Any>()
        for (k in -2..2) out.add(db.budgetsOverview(m0.tinvShift(k), today, BudgetOrder.MANUAL))
        out.add(db.history(m0.tinvShift(-3), m0.tinvShift(3)))
        for (id in live) out.add(db.budgetDetail(id, m0, today).month)
        return out
    }

    suspend fun pickCents(m: YearMonth): Long {
        val sum = db.ceiling(m).limits.v
        return when (rnd.nextInt(6)) {
            0 -> 0L
            1 -> sum
            2 -> maxOf(1L, sum - rnd.nextLong(1, 100_000))
            3 -> sum + rnd.nextLong(1, 100_000)
            4 -> rnd.nextLong(1, 100)
            else -> rnd.nextLong(1, 3_000_000)
        }
    }

    suspend fun setCeilingAction() {
        val m = monthNow()
        // Screens call ensureMonths before reading the month; sometimes the test doesn't, so setCeiling's own
        // fill of the skipped months (with the old default) is exercised too.
        if (!ensured && rnd.nextInt(3) != 0) ensureCurrent()
        val wasEnsured = ensured
        val cents = pickCents(m)
        val onward = rnd.nextBoolean()
        val before = db.ceiling(m)
        val snap = if (wasEnsured) budgetsSnapshot(m) else null
        val v0 = db.version.value
        val ok = db.setCeiling(Cents(cents), onward, today)
        log("setCeiling $cents onward=$onward -> $ok")
        assertTrue(ok, "setCeiling($cents, onward=$onward) is accepted (only invalid cents are refused)")
        modelSet(m, cents, onward)
        val after = db.ceiling(m)
        assertEquals(cents, after.cents.v, "ceiling($m).cents == $cents after setCeiling")
        if (onward) assertEquals(cents, after.standard.v, "onward: standard == $cents")
        else assertEquals(before.standard, after.standard, "só este mês: the default stays")
        assertEquals(before.limits, after.limits, "setCeiling doesn't change the sum of the limits")
        if (before.cents != after.cents || before.standard != after.standard) {
            assertTrue(db.version.value > v0, "a setCeiling that changed the ceiling or the default bumps version")
        }
        // Planning only (spec §3): limits, spent, status and weeks of every budget stay as they were.
        if (snap != null) assertEquals(snap, budgetsSnapshot(m), "setCeiling leaves every budget as it was")
        checkCeilings()

        if (!onward) {
            // "No mês seguinte volta o padrão" (spec §3): the next month, when ensureMonths creates its row.
            val next = m.tinvShift(1)
            if (next !in rows) {
                ensure(next)
                assertEquals(standard, db.ceiling(next).cents.v, "só este mês: $next gets the default after ensureMonths")
            }
        } else {
            // "Deste mês em diante" (D42): no later month keeps a row; each one reads the new default once ensured.
            for (k in 1..3) assertEquals(0L, db.ceiling(m.tinvShift(k)).cents.v, "onward deleted the row of ${m.tinvShift(k)}")
            if (rnd.nextBoolean()) {
                val next = m.tinvShift(1)
                ensure(next)
                assertEquals(cents, db.ceiling(next).cents.v, "onward: $next gets $cents after ensureMonths")
            }
        }

        if (wasEnsured && rnd.nextInt(3) == 0) undo(m, before)
    }

    /** Desfazer (spec §7.1, D46): two calls, default first, then the month; both values come back exactly. */
    suspend fun undo(m: YearMonth, before: Ceiling) {
        assertTrue(db.setCeiling(before.standard, onward = true, today), "Desfazer 1/2: setCeiling(${before.standard.v}, onward)")
        modelSet(m, before.standard.v, true)
        assertTrue(db.setCeiling(before.cents, onward = false, today), "Desfazer 2/2: setCeiling(${before.cents.v}, só este mês)")
        modelSet(m, before.cents.v, false)
        log("Desfazer -> ${before.cents.v} / ${before.standard.v}")
        val after = db.ceiling(m)
        assertEquals(before.cents, after.cents, "Desfazer restores ceiling($m).cents")
        assertEquals(before.standard, after.standard, "Desfazer restores the default")
        assertEquals(before.limits, after.limits, "Desfazer leaves the sum of the limits")
    }

    fun modelSet(m: YearMonth, cents: Long, onward: Boolean) {
        modelEnsure(m)
        rows[m] = cents
        if (onward) {
            standard = cents
            rows.keys.filter { it > m }.forEach { rows.remove(it) }
        }
    }

    suspend fun refusedCeilingAction() {
        val m = monthNow()
        val cents = -rnd.nextLong(1, 500_000)
        val onward = rnd.nextBoolean()
        val v0 = db.version.value
        val ok = db.setCeiling(Cents(cents), onward, today)
        log("setCeiling $cents onward=$onward -> $ok")
        assertFalse(ok, "setCeiling($cents) is refused")
        assertEquals(v0, db.version.value, "a refused setCeiling writes nothing (no version bump)")
        // Nothing written: not even the fill of skipped months (model untouched); checkAll compares every month.
        assertEquals(rows[m] ?: 0L, db.ceiling(m).cents.v, "refused setCeiling leaves ceiling($m)")
    }

    suspend fun previewAction() {
        val m = monthNow()
        val id = when (rnd.nextInt(5)) {
            0 -> BudgetId(0)
            1 -> (gone + TINV_UNKNOWN).random(rnd)
            else -> live.randomOrNull(rnd) ?: BudgetId(0)
        }
        val limit = when (rnd.nextInt(6)) {
            0 -> -rnd.nextLong(0, 1_000)
            else -> 1L + rnd.nextLong(500_000)
        }
        val c = db.ceiling(m)
        val row = db.budgetsOverview(m, today, BudgetOrder.MANUAL).firstOrNull { it.id == id }?.month?.limit?.v
        val v0 = db.version.value
        val p = db.previewCeiling(id, Cents(limit), today)
        log("previewCeiling ${id.v} $limit -> ${p.cents.v}/${p.standard.v}/${p.limits.v}")
        assertEquals(v0, db.version.value, "previewCeiling writes nothing (no version bump)")
        assertEquals(c, db.ceiling(m), "previewCeiling leaves ceiling($m)")
        val expected = if (limit <= 0) c else c.copy(limits = Cents(c.limits.v - (row ?: 0L) + limit))
        assertEquals(expected, p, "previewCeiling(${id.v}, $limit) (KDoc)")
        assertEquals(m, p.month, "previewCeiling is of today's month")
    }

    /** ensureMonths of a month after today's: allowed by the API; fills with the default. */
    suspend fun ensureFutureAction() {
        ensure(monthNow().tinvShift(1 + rnd.nextInt(3)))
    }

    suspend fun setMonthStartDayAction() {
        val day = 1 + rnd.nextInt(31)
        val v0 = db.version.value
        val old = db.settings().monthStartDay
        val ok = db.setMonthStartDay(day, today)
        log("setMonthStartDay $day -> $ok (v $v0 -> ${db.version.value})")
        assertTrue(ok, "setMonthStartDay($day)")
        // D46: changing the start day doesn't change any ceiling row; the new current month without one
        // gets the default (decided in the triage), and checkAll compares every month around it with the model.
        if (day != old) rows.getOrPut(db.monthOf(today)) { standard }
        ensured = false
        if (rnd.nextInt(4) != 0) {
            ensureCurrent()
        }
    }

    suspend fun advanceTodayAction() {
        val was = monthNow()
        today = today.plus(1 + rnd.nextInt(25), DateTimeUnit.DAY)
        log("today -> $today")
        if (monthNow() != was) ensured = false
        if (rnd.nextInt(4) != 0) ensureCurrent()
    }

    // ---- invariants ----

    /** Every month around today reads what the model says, and the default is the same in all of them. */
    suspend fun checkCeilings() {
        val m0 = monthNow()
        for (k in -4..4) {
            val m = m0.tinvShift(k)
            val c = db.ceiling(m)
            assertEquals(m, c.month, "ceiling($m).month")
            assertEquals(rows[m] ?: 0L, c.cents.v, "ceiling($m).cents (model rows: ${rows.toSortedMap()})")
            assertEquals(standard, c.standard.v, "ceiling($m).standard")
        }
    }

    suspend fun checkAll() {
        checkCeilings()
        val m0 = monthNow()
        for (k in -3..3) {
            val m = m0.tinvShift(k)
            val v0 = db.version.value
            val c = db.ceiling(m)
            assertEquals(v0, db.version.value, "ceiling($m) writes nothing")
            // limits = Σ of the limits of the budgets with a row in m, as budgetsOverview and history read them.
            val overview = db.budgetsOverview(m, today, BudgetOrder.MANUAL).sumOf { it.month.limit.v }
            val history = db.history(m, m).sumOf { it.limit.v }
            assertEquals(overview, c.limits.v, "ceiling($m).limits == Σ budgetsOverview($m) limits")
            assertEquals(history, c.limits.v, "ceiling($m).limits == Σ history($m, $m) limits")
            // over = limits − cents when there is a ceiling and the limits pass it, else 0 (Ceiling.over KDoc).
            val over = if (c.cents.v > 0) maxOf(0L, c.limits.v - c.cents.v) else 0L
            assertEquals(over, c.over.v, "ceiling($m).over")
            assertTrue(c.cents.v >= 0 && c.standard.v >= 0, "ceiling and default never negative")
        }
    }
}

class TetoInvariantsTest {

    private fun randomRun(seed: Int, steps: Int = 120) = runTest(timeout = 120.seconds) {
        withTetoDb { db -> TetoInvariantRun(db, seed).run(steps) }
    }

    @Test fun `random teto actions keep the invariants with seed 5`() = randomRun(5)

    @Test fun `random teto actions keep the invariants with seed 42`() = randomRun(42)

    @Test fun `random teto actions keep the invariants with seed 314`() = randomRun(314)

    @Test fun `random teto actions keep the invariants with seed 2026`() = randomRun(2026)

    @Test fun `random teto actions keep the invariants with seed 9001`() = randomRun(9001)
}
