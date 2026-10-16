package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/** The teto through the Db (spec §3, D42, D46): months, scopes, Desfazer, the form's preview, migration and backup. */
class TetoTest {

    private val path = Path(SystemTemporaryDirectory, "margem-teto-${Random.nextLong().toULong()}.db").toString()
    private var db = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))

    @AfterTest
    fun tearDown() {
        db.close()
        for (p in listOf(path, "$path-wal", "$path-shm", "$path.copy")) SystemFileSystem.delete(Path(p), mustExist = false)
    }

    private val oct9 = LocalDate(2026, 10, 9)
    private val nov3 = LocalDate(2026, 11, 3)
    private val oct = YearMonth(2026, 10)
    private val nov = YearMonth(2026, 11)
    private val dec = YearMonth(2026, 12)

    @Test
    fun noCeilingByDefault() = runTest {
        db.createBudget("Mercado", Cents(124_000), oct9)
        db.ensureMonths(oct)
        assertEquals(Ceiling(oct, Cents(0), Cents(0), Cents(124_000)), db.ceiling(oct))
        assertEquals(Cents(0), db.ceiling(oct).over)
    }

    @Test
    fun onlyThisMonthDoesNotLeak() = runTest {
        db.ensureMonths(oct)
        assertTrue(db.setCeiling(Cents(410_000), onward = true, oct9))
        assertTrue(db.setCeiling(Cents(440_000), onward = false, oct9))
        assertEquals(Cents(440_000), db.ceiling(oct).cents)
        assertEquals(Cents(410_000), db.ceiling(oct).standard)
        // Next month gets the default, not October's value (D42).
        db.ensureMonths(nov)
        assertEquals(Cents(410_000), db.ceiling(nov).cents)
    }

    @Test
    fun skippedMonthsGetTheOldDefaultBeforeAChange() = runTest {
        db.ensureMonths(oct)
        db.setCeiling(Cents(300_000), onward = true, oct9)
        // App closed through November; in December the default changes.
        db.setCeiling(Cents(500_000), onward = true, LocalDate(2026, 12, 2))
        assertEquals(Cents(300_000), db.ceiling(nov).cents)
        assertEquals(Cents(500_000), db.ceiling(dec).cents)
        assertEquals(Cents(300_000), db.ceiling(oct).cents)
    }

    @Test
    fun onwardDeletesLaterMonths() = runTest {
        db.ensureMonths(oct)
        db.setCeiling(Cents(300_000), onward = true, oct9)
        db.ensureMonths(dec)
        db.setCeiling(Cents(200_000), onward = true, oct9)
        assertEquals(Cents(0), db.ceiling(dec).cents)
        db.ensureMonths(dec)
        assertEquals(Cents(200_000), db.ceiling(nov).cents)
        assertEquals(Cents(200_000), db.ceiling(dec).cents)
    }

    @Test
    fun removeAndRefuse() = runTest {
        db.ensureMonths(oct)
        db.setCeiling(Cents(300_000), onward = true, oct9)
        val v = db.version.value
        assertFalse(db.setCeiling(Cents(-1), onward = true, oct9))
        assertEquals(v, db.version.value)
        assertTrue(db.setCeiling(Cents(300_000), onward = true, oct9))
        assertEquals(v, db.version.value, "same value changes nothing")
        assertTrue(db.setCeiling(Cents(0), onward = false, oct9))
        assertEquals(Cents(0), db.ceiling(oct).cents)
        assertEquals(Cents(300_000), db.ceiling(oct).standard)
    }

    @Test
    fun undoIsTwoCalls() = runTest {
        db.ensureMonths(oct)
        db.setCeiling(Cents(410_000), onward = true, oct9)
        db.setCeiling(Cents(440_000), onward = false, oct9)
        val before = db.ceiling(oct)
        db.setCeiling(Cents(0), onward = true, oct9)
        assertEquals(Ceiling(oct, Cents(0), Cents(0)), db.ceiling(oct))
        db.setCeiling(before.standard, onward = true, oct9)
        db.setCeiling(before.cents, onward = false, oct9)
        assertEquals(before, db.ceiling(oct))
    }

    @Test
    fun limitsAndPreview() = runTest {
        val mercado = db.createBudget("Mercado", Cents(124_000), oct9)
        db.createBudget("Casa", Cents(93_000), oct9)
        db.ensureMonths(oct)
        db.setCeiling(Cents(220_000), onward = true, oct9)
        assertEquals(Cents(217_000), db.ceiling(oct).limits)
        assertEquals(Cents(0), db.ceiling(oct).over)
        // A new budget of R$ 50,00: 2.220,00 passes 2.200,00 by R$ 20,00.
        assertEquals(Cents(2_000), db.previewCeiling(BudgetId(0), Cents(5_000), oct9).over)
        // Mercado down to 1.000,00: the sum goes down.
        assertEquals(Cents(193_000), db.previewCeiling(mercado, Cents(100_000), oct9).limits)
        // Invalid limit: the month as it is.
        assertEquals(db.ceiling(oct), db.previewCeiling(mercado, Cents(0), oct9))
        // Changing the limit from November on: October keeps its sum (spec §3).
        db.ensureMonths(nov)
        db.setLimit(mercado, Cents(130_000), nov3)
        assertEquals(Cents(217_000), db.ceiling(oct).limits)
        assertEquals(Cents(223_000), db.ceiling(nov).limits)
        assertEquals(Cents(3_000), db.ceiling(nov).over)
    }

    @Test
    fun ensureMonthsNeverFillsBackwardsNorBumps() = runTest {
        db.ensureMonths(nov)
        val v = db.version.value
        db.ensureMonths(oct)
        assertEquals(v, db.version.value)
        db.setCeiling(Cents(100_000), onward = true, nov3)
        db.ensureMonths(oct)
        assertEquals(Cents(0), db.ceiling(oct).cents)
    }

    @Test
    fun v1DatabaseMigratesAndBackupKeepsTheCeiling() = runTest {
        db.createBudget("Mercado", Cents(124_000), oct9)
        db.close()
        // Back to v1: what the app had before the teto.
        BundledSQLiteDriver().open(path).let {
            it.execSQL("DROP TABLE ceiling_months")
            it.execSQL("PRAGMA user_version = 1")
            it.close()
        }
        db = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))
        db.ensureMonths(oct)
        assertTrue(db.setCeiling(Cents(410_000), onward = true, oct9))
        assertEquals(Cents(124_000), db.ceiling(oct).limits)
        assertTrue(db.exportTo("$path.copy"))
        db.setCeiling(Cents(0), onward = true, oct9)
        assertTrue(db.importFrom("$path.copy"))
        assertEquals(Ceiling(oct, Cents(410_000), Cents(410_000), Cents(124_000)), db.ceiling(oct))
    }
}
