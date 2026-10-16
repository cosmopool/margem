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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Edge cases of the teto (spec §3, §7.1; architecture D42, D46, §5), through the Db only:
 * ceiling, setCeiling, previewCeiling and how ensureMonths, budgets, the month start day, backup
 * and version treat them. Expected values computed by hand from the docs.
 *
 * Rules used (D42/D46 and the KDoc):
 * - a month's ceiling is its own row; a month without row reads cents 0; `standard` is the default;
 * - ensureMonths gives the asked month and the months skipped since the latest row before it the
 *   DEFAULT (never the previous month's value) and never creates months before the asked one;
 * - setCeiling first fills the skipped months up to the current one with the OLD default; onward sets
 *   default + current row and deletes later rows; otherwise only the current row changes;
 * - limits = Σ limits of the budgets with a row in that month.
 *
 * Calendar: month start day 1 unless said; Feb 2027 has 28 days.
 */
class TetoEdgeTest {

    private fun withDb(block: suspend (Db) -> Unit): TestResult = runTest {
        val path = temp("margem-teto-edge")
        val db = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))
        try {
            block(db)
        } finally {
            db.close()
            for (suffix in listOf("", "-wal", "-shm", ".import")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
        }
    }

    private fun temp(prefix: String) = Path(SystemTemporaryDirectory, "$prefix-${Random.nextLong().toULong()}.db").toString()

    private fun day(y: Int, m: Int, d: Int) = LocalDate(y, m, d)
    private fun ym(y: Int, m: Int) = YearMonth(y, m)
    private fun c(v: Long) = Cents(v)

    private val oct = ym(2026, 10)
    private val nov = ym(2026, 11)
    private val dec = ym(2026, 12)
    private val jan = ym(2027, 1)
    private val today = day(2026, 10, 16)

    /** The largest value the app accepts (same as the money field, see BudgetsEdgeTest). */
    private val max = parseBrl("999999999999999,99")

    private suspend fun Db.budget(name: String, limit: Long, today: LocalDate): BudgetId =
        createBudget(name, Cents(limit), today).also { assertNotEquals(0L, it.v, "createBudget refused $name $limit") }

    private suspend fun Db.set(cents: Long, onward: Boolean, today: LocalDate) =
        assertTrue(setCeiling(Cents(cents), onward, today), "setCeiling($cents, onward=$onward, $today) refused")

    /** ensureMonths then ceiling, as the Verbas screen does. */
    private suspend fun Db.seen(month: YearMonth): Ceiling {
        ensureMonths(month)
        return ceiling(month)
    }

    private fun cl(month: YearMonth, cents: Long, standard: Long, limits: Long) = Ceiling(month, c(cents), c(standard), c(limits))

    // ---- Defaults ----

    @Test
    fun noCeilingByDefault() = withDb { db ->
        assertEquals(cl(oct, 0, 0, 0), db.ceiling(oct), "fresh db, no ensureMonths")
        assertEquals(cl(oct, 0, 0, 0), db.seen(oct), "fresh db after ensureMonths")
        assertEquals(c(0), db.ceiling(oct).over)
        // Budgets alone don't make a ceiling.
        db.budget("Mercado", 100_000, today)
        assertEquals(cl(oct, 0, 0, 100_000), db.seen(oct))
        assertEquals(c(0), db.ceiling(oct).over, "no ceiling: never over")
    }

    @Test
    fun monthWithoutRowReadsZeroButKeepsDefault() = withDb { db ->
        db.budget("Mercado", 100_000, today)
        db.set(410_000, onward = true, today)
        // Nov: no ensureMonths yet → no ceiling row and no limit row.
        assertEquals(cl(nov, 0, 410_000, 0), db.ceiling(nov))
        // After ensureMonths: the default, and the copied limit (D8).
        assertEquals(cl(nov, 410_000, 410_000, 100_000), db.seen(nov))
    }

    // ---- Remove (0) ----

    @Test
    fun zeroOnwardRemovesDefaultAndMonth() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.set(0, onward = true, today)
        assertEquals(cl(oct, 0, 0, 0), db.seen(oct))
        assertEquals(cl(nov, 0, 0, 0), db.seen(nov), "Outubro em diante sem teto")
    }

    @Test
    fun zeroOnlyThisMonthKeepsDefault() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.set(0, onward = false, today)
        assertEquals(cl(oct, 0, 410_000, 0), db.seen(oct), "Outubro sem teto")
        assertEquals(cl(nov, 410_000, 410_000, 0), db.seen(nov), "Nov gets the default back")
    }

    // ---- Refused ----

    @Test
    fun negativeAndAboveMaxRefusedWritingNothing() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.set(440_000, onward = false, today)
        val before = db.seen(oct)
        val v0 = db.version.value
        for (onward in listOf(true, false)) {
            for (bad in listOf(-1L, -410_000L, Long.MIN_VALUE, max.v + 1, Long.MAX_VALUE)) {
                assertFalse(db.setCeiling(c(bad), onward, today), "setCeiling($bad, onward=$onward) must be refused")
            }
        }
        assertEquals(v0, db.version.value, "refused writes don't bump version")
        assertEquals(before, db.ceiling(oct))
        assertEquals(cl(oct, 440_000, 410_000, 0), db.ceiling(oct))
    }

    @Test
    fun refusedDoesNotFillSkippedMonths() = withDb { db ->
        db.set(410_000, onward = true, today)
        val v0 = db.version.value
        // Today is in Jan 2027; Nov and Dec were never ensured. A refused call writes nothing at all.
        assertFalse(db.setCeiling(c(-1), onward = true, day(2027, 1, 10)))
        assertFalse(db.setCeiling(c(max.v + 1), onward = false, day(2027, 1, 10)))
        assertEquals(v0, db.version.value)
        assertEquals(cl(nov, 0, 410_000, 0), db.ceiling(nov), "Nov still without row")
        assertEquals(cl(dec, 0, 410_000, 0), db.ceiling(dec), "Dec still without row")
        assertEquals(cl(jan, 0, 410_000, 0), db.ceiling(jan), "Jan still without row")
    }

    @Test
    fun maximumIsAccepted() = withDb { db ->
        assertTrue(db.setCeiling(max, onward = true, today), "the maximum itself is not above the maximum")
        assertEquals(cl(oct, max.v, max.v, 0), db.ceiling(oct))
        assertTrue(db.setCeiling(max, onward = false, today))
        assertEquals(cl(oct, max.v, max.v, 0), db.ceiling(oct))
    }

    // ---- Só este mês ----

    @Test
    fun onlyThisMonthDoesNotLeakWithoutDefault() = withDb { db ->
        db.set(440_000, onward = false, today)
        assertEquals(cl(oct, 440_000, 0, 0), db.seen(oct))
        // ensureMonths copies the default (0), not the previous month (D42).
        assertEquals(cl(nov, 0, 0, 0), db.seen(nov))
        assertEquals(cl(oct, 440_000, 0, 0), db.ceiling(oct), "Oct keeps its own row")
    }

    @Test
    fun onlyThisMonthDoesNotLeakWithDefault() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.set(440_000, onward = false, today)
        assertEquals(cl(oct, 440_000, 410_000, 0), db.seen(oct))
        // Jan via ensureMonths: Nov, Dec (skipped) and Jan all get the default.
        db.ensureMonths(jan)
        assertEquals(cl(nov, 410_000, 410_000, 0), db.ceiling(nov))
        assertEquals(cl(dec, 410_000, 410_000, 0), db.ceiling(dec))
        assertEquals(cl(jan, 410_000, 410_000, 0), db.ceiling(jan))
    }

    @Test
    fun onlyThisMonthLeavesLaterRowsAlone() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.ensureMonths(dec) // Nov and Dec rows = 410_000
        db.set(440_000, onward = false, today)
        assertEquals(cl(oct, 440_000, 410_000, 0), db.ceiling(oct))
        assertEquals(cl(nov, 410_000, 410_000, 0), db.ceiling(nov))
        assertEquals(cl(dec, 410_000, 410_000, 0), db.ceiling(dec))
    }

    // ---- Deste mês em diante ----

    @Test
    fun onwardDeletesLaterRows() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.ensureMonths(jan) // Nov, Dec, Jan rows = 410_000
        db.set(500_000, onward = true, today)
        assertEquals(cl(oct, 500_000, 500_000, 0), db.ceiling(oct))
        // Later rows deleted: read 0 until ensureMonths.
        assertEquals(cl(nov, 0, 500_000, 0), db.ceiling(nov))
        assertEquals(cl(dec, 0, 500_000, 0), db.ceiling(dec))
        assertEquals(cl(jan, 0, 500_000, 0), db.ceiling(jan))
        // ensureMonths(Jan): latest row before Jan is Oct → Nov, Dec, Jan get the new default.
        db.ensureMonths(jan)
        assertEquals(cl(nov, 500_000, 500_000, 0), db.ceiling(nov))
        assertEquals(cl(dec, 500_000, 500_000, 0), db.ceiling(dec))
        assertEquals(cl(jan, 500_000, 500_000, 0), db.ceiling(jan))
    }

    @Test
    fun onwardDeletesLaterOnlyThisMonthRow() = withDb { db ->
        // A "só novembro" row set while Nov was current, then today goes back to Oct: Nov is a later row.
        db.set(410_000, onward = true, today)
        db.set(450_000, onward = false, day(2026, 11, 3))
        assertEquals(cl(nov, 450_000, 410_000, 0), db.ceiling(nov))
        db.set(300_000, onward = true, today)
        assertEquals(cl(nov, 0, 300_000, 0), db.ceiling(nov), "Nov row deleted")
        assertEquals(cl(nov, 300_000, 300_000, 0), db.seen(nov))
    }

    @Test
    fun onwardKeepsPastMonths() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.set(500_000, onward = true, day(2026, 11, 3))
        // Nov was skipped: setCeiling fills it with the old default, then onward overwrites Nov.
        assertEquals(cl(oct, 410_000, 500_000, 0), db.ceiling(oct), "past month keeps its ceiling")
        assertEquals(cl(nov, 500_000, 500_000, 0), db.ceiling(nov))
    }

    @Test
    fun onwardAfterMonthStartMovedBackDeletesTheOldCurrentRow() = withDb { db ->
        // D43: day 1 → 15 on Oct 14 makes Sep (Sep 15–Oct 14) the current month; Oct's row is now a later one.
        val oct14 = day(2026, 10, 14)
        val sep = ym(2026, 9)
        db.set(410_000, onward = true, oct14)
        assertEquals(cl(oct, 410_000, 410_000, 0), db.ceiling(oct))
        assertTrue(db.setMonthStartDay(15, oct14))
        assertEquals(sep, db.monthOf(oct14), "setup: current month is Sep")
        // T3: nothing special, the Oct row stays; Sep gets the default from ensureMonths (D46).
        assertEquals(cl(oct, 410_000, 410_000, 0), db.ceiling(oct))
        assertEquals(cl(sep, 410_000, 410_000, 0), db.seen(sep))
        db.set(300_000, onward = true, oct14)
        assertEquals(cl(sep, 300_000, 300_000, 0), db.ceiling(sep))
        assertEquals(cl(oct, 0, 300_000, 0), db.ceiling(oct), "the later Oct row is deleted")
        assertEquals(cl(oct, 300_000, 300_000, 0), db.seen(oct))
    }

    @Test
    fun onlyThisMonthAfterMonthStartMovedBackKeepsOldCurrentRow() = withDb { db ->
        val oct14 = day(2026, 10, 14)
        val sep = ym(2026, 9)
        db.set(410_000, onward = true, oct14)
        db.set(440_000, onward = false, oct14)
        assertTrue(db.setMonthStartDay(15, oct14))
        assertEquals(cl(oct, 440_000, 410_000, 0), db.ceiling(oct), "T3: the old current month keeps its row")
        db.set(300_000, onward = false, oct14)
        assertEquals(cl(sep, 300_000, 410_000, 0), db.ceiling(sep))
        assertEquals(cl(oct, 440_000, 410_000, 0), db.ceiling(oct), "só este mês touches only Sep")
        // Back to day 1: Oct is current again with its own row.
        assertTrue(db.setMonthStartDay(1, oct14))
        assertEquals(oct, db.monthOf(oct14))
        assertEquals(cl(oct, 440_000, 410_000, 0), db.seen(oct))
    }

    // ---- Skipped months ----

    @Test
    fun skippedMonthsGetTheOldDefaultOnward() = withDb { db ->
        db.set(410_000, onward = true, today)
        // App closed until Jan 10: Nov and Dec never ensured.
        db.set(500_000, onward = true, day(2027, 1, 10))
        assertEquals(cl(oct, 410_000, 500_000, 0), db.ceiling(oct))
        assertEquals(cl(nov, 410_000, 500_000, 0), db.ceiling(nov), "Nov filled with the OLD default")
        assertEquals(cl(dec, 410_000, 500_000, 0), db.ceiling(dec), "Dec filled with the OLD default")
        assertEquals(cl(jan, 500_000, 500_000, 0), db.ceiling(jan))
        assertEquals(cl(ym(2027, 2), 500_000, 500_000, 0), db.seen(ym(2027, 2)))
    }

    @Test
    fun skippedMonthsGetTheOldDefaultOnlyThisMonth() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.set(500_000, onward = false, day(2027, 1, 10))
        assertEquals(cl(nov, 410_000, 410_000, 0), db.ceiling(nov))
        assertEquals(cl(dec, 410_000, 410_000, 0), db.ceiling(dec))
        assertEquals(cl(jan, 500_000, 410_000, 0), db.ceiling(jan))
        assertEquals(cl(ym(2027, 2), 410_000, 410_000, 0), db.seen(ym(2027, 2)))
    }

    @Test
    fun skippedMonthsGetTheDefaultNotTheOnlyThisMonthValue() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.set(440_000, onward = false, today)
        // Nov, Dec skipped; the latest row (Oct) is 440_000 only for Oct: the fill uses the default.
        db.set(500_000, onward = false, day(2027, 1, 10))
        assertEquals(cl(nov, 410_000, 410_000, 0), db.ceiling(nov))
        assertEquals(cl(dec, 410_000, 410_000, 0), db.ceiling(dec))
        assertEquals(cl(oct, 440_000, 410_000, 0), db.ceiling(oct))
    }

    // ---- ensureMonths ----

    @Test
    fun ensureMonthsNeverFillsBackwards() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.ensureMonths(dec)
        assertEquals(cl(nov, 410_000, 410_000, 0), db.ceiling(nov))
        assertEquals(cl(dec, 410_000, 410_000, 0), db.ceiling(dec))
        // Months before the first row were never asked for: still no row.
        assertEquals(cl(ym(2026, 9), 0, 410_000, 0), db.ceiling(ym(2026, 9)))
        assertEquals(cl(ym(2026, 8), 0, 410_000, 0), db.ceiling(ym(2026, 8)))
        // Reading a later month doesn't create rows before the asked month either.
        db.ensureMonths(ym(2026, 9))
        assertEquals(cl(ym(2026, 8), 0, 410_000, 0), db.ceiling(ym(2026, 8)), "Aug is before the asked month")
    }

    @Test
    fun ensureMonthsWithoutAnyRowAndNoDefault() = withDb { db ->
        db.ensureMonths(jan)
        for (m in listOf(oct, nov, dec, jan)) assertEquals(cl(m, 0, 0, 0), db.ceiling(m))
    }

    @Test
    fun ensureMonthsCopiesTheDefaultChangedLater() = withDb { db ->
        // Oct 410_000 onward; then in Oct the default changes to 300_000 onward. Nov copies 300_000.
        db.set(410_000, onward = true, today)
        db.set(300_000, onward = true, today)
        assertEquals(cl(nov, 300_000, 300_000, 0), db.seen(nov))
    }

    // ---- limits ----

    @Test
    fun limitsSumTheMonthRows() = withDb { db ->
        db.set(400_000, onward = true, today)
        val a = db.budget("Mercado", 100_000, day(2026, 10, 1))
        val b = db.budget("Farmácia", 50_000, day(2026, 10, 28)) // created late in the month: counts whole
        assertEquals(cl(oct, 400_000, 400_000, 150_000), db.seen(oct))

        // Nov: both copied (D8), then a limit change from Nov on and a budget created in Nov.
        db.ensureMonths(nov)
        assertTrue(db.setLimit(a, c(120_000), day(2026, 11, 5)))
        val n = db.budget("Casa", 30_000, day(2026, 11, 5))
        assertEquals(cl(nov, 400_000, 400_000, 200_000), db.seen(nov)) // 120_000 + 50_000 + 30_000
        assertEquals(cl(oct, 400_000, 400_000, 150_000), db.ceiling(oct), "past month keeps its limits; Casa has no Oct row")

        // Deleting a budget takes its limits out of every month.
        db.deleteBudget(b)
        assertEquals(c(100_000), db.ceiling(oct).limits)
        assertEquals(c(150_000), db.ceiling(nov).limits) // 120_000 + 30_000
        db.deleteBudget(n)
        assertEquals(c(100_000), db.ceiling(oct).limits)
        assertEquals(c(120_000), db.ceiling(nov).limits)
    }

    @Test
    fun overBoundary() = withDb { db ->
        val a = db.budget("Mercado", 100_000, today)
        db.budget("Farmácia", 50_000, today)
        db.set(150_000, onward = true, today)
        assertEquals(c(0), db.seen(oct).over, "exactly at the ceiling: not over")
        assertTrue(db.setLimit(a, c(100_001), today))
        assertEquals(c(1), db.ceiling(oct).over, "one cent above")
        assertTrue(db.setLimit(a, c(400_000), today))
        assertEquals(c(300_000), db.ceiling(oct).over)
        assertTrue(db.setLimit(a, c(99_999), today))
        assertEquals(c(0), db.ceiling(oct).over, "below")
        // Removing the ceiling: never over, whatever the limits.
        assertTrue(db.setLimit(a, c(900_000), today))
        db.set(0, onward = false, today)
        assertEquals(c(0), db.ceiling(oct).over)
    }

    @Test
    fun ceilingNeverRefusesBudgetWrites() = withDb { db ->
        // Planning limit only (spec §3): budgets above the ceiling are created and raised.
        db.set(10_000, onward = true, today)
        val a = db.budget("Mercado", 50_000, today)
        assertTrue(db.setLimit(a, c(90_000), today))
        assertEquals(cl(oct, 10_000, 10_000, 90_000), db.seen(oct))
        assertEquals(c(80_000), db.ceiling(oct).over)
    }

    // ---- previewCeiling ----

    @Test
    fun previewNewBudget() = withDb { db ->
        db.budget("Mercado", 100_000, today)
        db.budget("Farmácia", 50_000, today)
        db.set(400_000, onward = true, today)
        db.ensureMonths(oct)
        assertEquals(cl(oct, 400_000, 400_000, 180_000), db.previewCeiling(BudgetId(0), c(30_000), today))
        val over = db.previewCeiling(BudgetId(0), c(300_000), today)
        assertEquals(cl(oct, 400_000, 400_000, 450_000), over)
        assertEquals(c(50_000), over.over)
    }

    @Test
    fun previewExistingBudgetSwapsItsLimit() = withDb { db ->
        val a = db.budget("Mercado", 100_000, today)
        db.budget("Farmácia", 50_000, today)
        db.set(400_000, onward = true, today)
        db.ensureMonths(oct)
        assertEquals(cl(oct, 400_000, 400_000, 70_000), db.previewCeiling(a, c(20_000), today)) // 50_000 + 20_000
        assertEquals(cl(oct, 400_000, 400_000, 150_000), db.previewCeiling(a, c(100_000), today), "same limit")
        val atCeiling = db.previewCeiling(a, c(350_000), today)
        assertEquals(c(400_000), atCeiling.limits)
        assertEquals(c(0), atCeiling.over, "exactly at the ceiling")
        assertEquals(c(1), db.previewCeiling(a, c(350_001), today).over)
    }

    @Test
    fun previewBudgetWithoutRowInTheMonthAddsIt() = withDb { db ->
        db.budget("Mercado", 100_000, today)
        db.set(400_000, onward = true, today)
        db.ensureMonths(oct)
        // Created with a November today: its first row is Nov, so it has none in Oct.
        val late = db.budget("Casa", 70_000, day(2026, 11, 2))
        assertEquals(c(100_000), db.ceiling(oct).limits, "setup: Casa has no Oct row")
        assertEquals(cl(oct, 400_000, 400_000, 110_000), db.previewCeiling(late, c(10_000), today))
        // Unknown and deleted ids have no row either.
        assertEquals(cl(oct, 400_000, 400_000, 110_000), db.previewCeiling(BudgetId(999), c(10_000), today))
        val gone = db.budget("Lazer", 20_000, today)
        db.deleteBudget(gone)
        assertEquals(cl(oct, 400_000, 400_000, 110_000), db.previewCeiling(gone, c(10_000), today))
    }

    @Test
    fun previewInvalidLimitIsTheMonthAsItIs() = withDb { db ->
        val a = db.budget("Mercado", 100_000, today)
        db.budget("Farmácia", 50_000, today)
        db.set(400_000, onward = true, today)
        db.set(120_000, onward = false, today)
        val now = db.seen(oct)
        assertEquals(cl(oct, 120_000, 400_000, 150_000), now)
        for (bad in listOf(0L, -1L, -100_000L, Long.MIN_VALUE, max.v + 1, Long.MAX_VALUE)) {
            assertEquals(now, db.previewCeiling(a, c(bad), today), "existing budget, limit $bad")
            assertEquals(now, db.previewCeiling(BudgetId(0), c(bad), today), "new budget, limit $bad")
        }
    }

    @Test
    fun previewMaxLimitIsAccepted() = withDb { db ->
        val a = db.budget("Mercado", 100_000, today)
        db.set(400_000, onward = true, today)
        db.ensureMonths(oct)
        assertEquals(cl(oct, 400_000, 400_000, max.v), db.previewCeiling(a, max, today))
    }

    @Test
    fun previewMonthWithoutCeilingRowReadsZero() = withDb { db ->
        db.set(400_000, onward = true, today)
        // Today is in Nov, never ensured: no ceiling row and no limit rows there.
        val p = db.previewCeiling(BudgetId(0), c(10_000), day(2026, 11, 2))
        assertEquals(cl(nov, 0, 400_000, 10_000), p)
        assertEquals(c(0), p.over)
    }

    @Test
    fun previewWritesNothing() = withDb { db ->
        val a = db.budget("Mercado", 100_000, today)
        db.set(400_000, onward = true, today)
        db.ensureMonths(oct)
        val v0 = db.version.value
        val before = db.ceiling(oct)
        db.previewCeiling(a, c(900_000), today)
        db.previewCeiling(BudgetId(0), c(900_000), today)
        db.previewCeiling(a, c(-1), today)
        db.previewCeiling(BudgetId(0), c(10_000), day(2027, 1, 10)) // a month never ensured
        assertEquals(v0, db.version.value)
        assertEquals(before, db.ceiling(oct))
        assertEquals(cl(jan, 0, 400_000, 0), db.ceiling(jan), "no row created in Jan")
        assertEquals(cl(nov, 0, 400_000, 0), db.ceiling(nov), "no skipped row created")
    }

    // ---- Month start days 29–31 ----

    @Test
    fun startDay31InFebruary() = withDb { db ->
        // Day 31: Feb 2027 has 28 days → its month starts Feb 28; Feb 27 is still in January's month.
        assertTrue(db.setMonthStartDay(31, day(2027, 1, 31)))
        assertEquals(jan, db.monthOf(day(2027, 2, 27)))
        assertEquals(ym(2027, 2), db.monthOf(day(2027, 2, 28)))
        db.set(410_000, onward = false, day(2027, 2, 27))
        assertEquals(cl(jan, 410_000, 0, 0), db.ceiling(jan))
        assertEquals(cl(ym(2027, 2), 0, 0, 0), db.seen(ym(2027, 2)), "só janeiro doesn't leak into Feb")
        db.set(300_000, onward = true, day(2027, 2, 28))
        assertEquals(cl(ym(2027, 2), 300_000, 300_000, 0), db.ceiling(ym(2027, 2)))
        assertEquals(cl(jan, 410_000, 300_000, 0), db.ceiling(jan))
    }

    @Test
    fun startDay29InLeapAndCommonFebruary() = withDb { db ->
        // Day 29: Feb 2028 (29 days) starts Feb 29; Feb 2027 (28 days) starts Feb 28.
        assertTrue(db.setMonthStartDay(29, day(2027, 1, 10)))
        db.set(100_000, onward = true, day(2027, 2, 28)) // Feb 2027's month
        assertEquals(cl(ym(2027, 2), 100_000, 100_000, 0), db.ceiling(ym(2027, 2)))
        db.set(200_000, onward = false, day(2028, 2, 28)) // still Jan 2028's month
        assertEquals(cl(ym(2028, 1), 200_000, 100_000, 0), db.ceiling(ym(2028, 1)))
        assertEquals(cl(ym(2027, 12), 100_000, 100_000, 0), db.ceiling(ym(2027, 12)), "skipped filled with the default")
        assertEquals(cl(ym(2028, 2), 100_000, 100_000, 0), db.seen(ym(2028, 2)))
    }

    @Test
    fun monthStartMovedForwardOnlyThisMonthDoesNotLeak() = withDb { db ->
        // Day 15 on Oct 10 → Sep is current; "só setembro" there; day back to 1 → Oct current, gets the default.
        val oct10 = day(2026, 10, 10)
        val sep = ym(2026, 9)
        assertTrue(db.setMonthStartDay(15, oct10))
        db.set(300_000, onward = true, oct10)
        db.set(410_000, onward = false, oct10)
        assertEquals(cl(sep, 410_000, 300_000, 0), db.ceiling(sep))
        assertTrue(db.setMonthStartDay(1, oct10))
        assertEquals(oct, db.monthOf(oct10))
        assertEquals(cl(oct, 300_000, 300_000, 0), db.seen(oct))
        assertEquals(cl(sep, 410_000, 300_000, 0), db.ceiling(sep), "Sep keeps its row")
    }

    @Test
    fun monthStartChangeKeepsLimitsInTheCeilingSum() = withDb { db ->
        // D43: on Oct 14, day 1 → 15 moves the current month back to Sep; the budget's Oct limit is copied there.
        val oct14 = day(2026, 10, 14)
        val sep = ym(2026, 9)
        db.budget("Mercado", 100_000, oct14)
        db.set(80_000, onward = true, oct14)
        assertTrue(db.setMonthStartDay(15, oct14))
        val s = db.seen(sep)
        assertEquals(cl(sep, 80_000, 80_000, 100_000), s)
        assertEquals(c(20_000), s.over)
        assertEquals(cl(oct, 80_000, 80_000, 100_000), db.ceiling(oct))
    }

    // ---- Desfazer (D46: two calls) ----

    @Test
    fun undoOnwardRemoval() = withDb { db ->
        db.budget("Mercado", 100_000, today)
        db.set(410_000, onward = true, today)
        db.set(440_000, onward = false, today)
        val before = db.seen(oct)
        assertEquals(cl(oct, 440_000, 410_000, 100_000), before)
        db.set(0, onward = true, today) // "Outubro em diante sem teto."
        assertEquals(cl(oct, 0, 0, 100_000), db.ceiling(oct))
        db.set(before.standard.v, onward = true, today)
        db.set(before.cents.v, onward = false, today)
        assertEquals(before, db.ceiling(oct))
        assertEquals(cl(nov, 410_000, 410_000, 100_000), db.seen(nov))
    }

    @Test
    fun undoOnlyThisMonth() = withDb { db ->
        db.set(410_000, onward = true, today)
        val before = db.seen(oct)
        db.set(440_000, onward = false, today) // "Teto de R$ 4.400,00 só em outubro."
        db.set(before.standard.v, onward = true, today)
        db.set(before.cents.v, onward = false, today)
        assertEquals(cl(oct, 410_000, 410_000, 0), db.ceiling(oct))
        assertEquals(cl(nov, 410_000, 410_000, 0), db.seen(nov))
    }

    @Test
    fun undoFirstCeiling() = withDb { db ->
        val before = db.seen(oct)
        assertEquals(cl(oct, 0, 0, 0), before)
        db.set(410_000, onward = true, today)
        db.set(before.standard.v, onward = true, today)
        db.set(before.cents.v, onward = false, today)
        assertEquals(before, db.ceiling(oct))
        assertEquals(cl(nov, 0, 0, 0), db.seen(nov))
    }

    @Test
    fun undoOnlyThisMonthRemoval() = withDb { db ->
        db.set(410_000, onward = true, today)
        db.set(440_000, onward = false, today)
        val before = db.seen(oct)
        db.set(0, onward = false, today) // "Outubro sem teto."
        assertEquals(cl(oct, 0, 410_000, 0), db.ceiling(oct))
        db.set(before.standard.v, onward = true, today)
        db.set(before.cents.v, onward = false, today)
        assertEquals(cl(oct, 440_000, 410_000, 0), db.ceiling(oct))
    }

    // ---- Backup ----

    @Test
    fun exportImportKeepsCeilingRowsAndDefault() = withDb { db ->
        db.budget("Mercado", 100_000, today)
        db.set(410_000, onward = true, today)
        db.set(440_000, onward = false, today)
        db.ensureMonths(nov)
        db.set(450_000, onward = false, day(2026, 11, 3))
        val file = temp("margem-teto-backup")
        try {
            assertTrue(db.exportTo(file))
            // Change everything after the export.
            db.set(0, onward = true, today)
            db.set(999_000, onward = false, today)
            assertEquals(cl(oct, 999_000, 0, 100_000), db.ceiling(oct))
            assertTrue(db.importFrom(file))
            assertEquals(cl(oct, 440_000, 410_000, 100_000), db.ceiling(oct))
            assertEquals(cl(nov, 450_000, 410_000, 100_000), db.ceiling(nov))
            assertEquals(cl(dec, 410_000, 410_000, 100_000), db.seen(dec), "the restored default fills Dec")
        } finally {
            SystemFileSystem.delete(Path(file), mustExist = false)
        }
    }

    // ---- version (D6, D30) ----

    @Test
    fun versionBumpsOnRealChangesOnly() = withDb { db ->
        db.ensureMonths(oct)
        var v = db.version.value
        db.set(410_000, onward = true, today)
        assertEquals(v + 1, db.version.value, "new default: one bump")
        v = db.version.value
        db.set(410_000, onward = true, today)
        assertEquals(v, db.version.value, "same default and same row: nothing changed")
        db.set(410_000, onward = false, today)
        assertEquals(v, db.version.value, "same row: nothing changed")
        db.set(440_000, onward = false, today)
        assertEquals(v + 1, db.version.value, "só este mês: one bump")
        v = db.version.value
        db.set(440_000, onward = false, today)
        assertEquals(v, db.version.value)
        db.set(440_000, onward = true, today)
        assertEquals(v + 1, db.version.value, "same row, new default: a change")
        v = db.version.value
        db.set(0, onward = false, today)
        assertEquals(v + 1, db.version.value, "removing: one bump")
        v = db.version.value
        db.set(0, onward = false, today)
        assertEquals(v, db.version.value, "removing again: nothing changed")
    }

    @Test
    fun ensureMonthsAndReadsNeverBump() = withDb { db ->
        db.budget("Mercado", 100_000, today)
        db.set(410_000, onward = true, today)
        val v = db.version.value
        db.ensureMonths(ym(2027, 6)) // creates Nov..Jun ceiling and limit rows
        assertEquals(v, db.version.value, "ensureMonths never bumps (D30)")
        assertEquals(cl(ym(2027, 6), 410_000, 410_000, 100_000), db.ceiling(ym(2027, 6)))
        db.ceiling(ym(2027, 7))
        db.previewCeiling(BudgetId(0), c(1), today)
        assertEquals(v, db.version.value, "reads never bump")
    }

    @Test
    fun onWriteFollowsCeilingWrites() = runTest {
        // D21: onWrite is called after every write that bumped version.
        val path = temp("margem-teto-onwrite")
        var calls = 0
        val db = Db(BundledSQLiteDriver(), path, onWrite = { calls++ }, timeZone = TimeZone.of("America/Sao_Paulo"))
        try {
            db.ensureMonths(oct)
            assertTrue(db.setCeiling(c(410_000), onward = true, today))
            assertEquals(1, calls)
            assertFalse(db.setCeiling(c(-1), onward = true, today))
            assertEquals(1, calls, "refused: no onWrite")
            db.previewCeiling(BudgetId(0), c(1), today)
            db.ceiling(oct)
            db.ensureMonths(jan)
            assertEquals(1, calls, "reads and ensureMonths: no onWrite")
        } finally {
            db.close()
            for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
        }
    }
}
