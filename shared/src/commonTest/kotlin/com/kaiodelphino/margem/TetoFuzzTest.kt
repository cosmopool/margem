package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.onDay
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Teto fuzz: hostile and random inputs on ceiling, setCeiling and previewCeiling (spec §3, §7.1;
// architecture D42, D46, §5). Fixed seeds, bounded iterations, a real SQLite file per Db, real-time
// timeout. The expected ceiling rows and default come from a small model written from the docs; Σ of
// the month's limits comes from budgetsOverview (the public read of "budgets with a limit row in the
// month"), never from the code under test. Every failure message carries the seed, the step and the input.

/** Real threads under a wall-clock timeout (withTimeout inside Dispatchers.Default), so a deadlock fails. */
private fun realTime(block: suspend () -> Unit) = runTest(timeout = 90.seconds) {
    withContext(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
}

/** A fresh Db on a temporary file, closed and deleted (with -wal/-shm) afterwards. */
private suspend fun withDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-tfuzz-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path)
        try {
            block(db)
        } finally {
            db.close()
        }
    } finally {
        for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
    }
}

/** Runs one call of the API; any exception (but cancellation/timeout) fails with [where]. */
private suspend fun <T> noThrow(where: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fail("$where threw $e", e)
}

// ---------------------------------------------------------------- Month model (spec §3)

/** First day of the budget month [ym]: the start day, or the month's last day when it is missing. */
private fun tStart(ym: YearMonth, startDay: Int): LocalDate = ym.onDay(minOf(startDay, ym.numberOfDays))

/** The budget month [date] falls in. */
private fun tMonthOf(date: LocalDate, startDay: Int): YearMonth =
    if (date >= tStart(date.yearMonth, startDay)) date.yearMonth else date.yearMonth.minusMonth()

/**
 * The largest limit createBudget accepts, found through previewBudget ("Zero BudgetMonth when
 * createBudget would refuse the limit (≤ 0 or above the maximum)"), so the test never names the
 * internal constant. setCeiling's "above the maximum value" is read as the same maximum.
 */
private suspend fun findMax(db: Db): Long {
    val day = LocalDate(2026, 10, 16)
    suspend fun ok(v: Long) = db.previewBudget(Cents(v), day) != BudgetMonth()
    if (ok(Long.MAX_VALUE)) return Long.MAX_VALUE
    assertTrue(ok(1), "previewBudget accepts 1 cent")
    var lo = 1L
    var hi = Long.MAX_VALUE
    while (hi - lo > 1) {
        val mid = lo + (hi - lo) / 2
        if (ok(mid)) lo = mid else hi = mid
    }
    assertTrue(lo >= 1_000_000_000L, "the maximum allows at least R$ 10 milhões (got $lo)")
    return lo
}

// ---------------------------------------------------------------- Ceiling model (D42, D46, §5)

/**
 * Ceiling rows per month and the default. Rows stay contiguous: the first one is created alone, the
 * fill only goes forward from the latest row, and "em diante" deletes a suffix. The test never asks
 * for a month before the first row once there is one (the docs don't say what that does).
 */
private class TetoModel {
    var standard = 0L
    val rows = HashMap<YearMonth, Long>()

    fun first(): YearMonth? = rows.keys.minOrNull()
    fun last(): YearMonth? = rows.keys.maxOrNull()

    /** ensureMonths' ceiling part: [m] and the months skipped since the latest row before it get the default. Returns rows created. */
    fun fill(m: YearMonth): Int {
        if (m in rows) return 0
        val before = rows.keys.filter { it < m }.maxOrNull()
        if (before == null) {
            rows[m] = standard
            return 1
        }
        var n = 0
        var x = before.plusMonth()
        while (x <= m) {
            if (x !in rows) {
                rows[x] = standard
                n++
            }
            x = x.plusMonth()
        }
        return n
    }

    /** What changed in one accepted setCeiling. */
    class Effect(val created: Int, val intended: Boolean)

    fun set(cents: Long, onward: Boolean, cur: YearMonth): Effect {
        val created = fill(cur)
        val old = rows.getValue(cur)
        var intended = old != cents
        if (onward) {
            if (standard != cents) intended = true
            standard = cents
            val later = rows.keys.filter { it > cur }
            if (later.isNotEmpty()) intended = true
            later.forEach { rows.remove(it) }
        }
        rows[cur] = cents
        return Effect(created, intended)
    }

    /** setMonthStartDay that changed the day: the new current month without a row gets the default (D46, triage). */
    fun startMoved(now: YearMonth) {
        if (now !in rows) rows[now] = standard
    }

    fun copy(): TetoModel = TetoModel().also { it.standard = standard; it.rows.putAll(rows) }
}

private fun overOf(cents: Long, limits: Long): Long = if (cents > 0 && limits > cents) limits - cents else 0L

private fun assertOver(c: Ceiling, where: String) =
    assertEquals(Cents(overOf(c.cents.v, c.limits.v)), c.over, "$where: over of $c")

/** Σ of the limits of the budgets with a limit row in [m]. */
private suspend fun limitsOf(db: Db, m: YearMonth, today: LocalDate, except: BudgetId? = null): Long =
    db.budgetsOverview(m, today, BudgetOrder.MANUAL).filter { it.id != except }.sumOf { it.month.limit.v }

/** Every month in [months] reads as the model says; reads don't bump the version. */
private suspend fun check(db: Db, model: TetoModel, months: Collection<YearMonth>, today: LocalDate, where: String) {
    val v = db.version.value
    for (m in months.toSet()) {
        val got = noThrow("$where ceiling($m)") { db.ceiling(m) }
        val want = Ceiling(m, Cents(model.rows[m] ?: 0L), Cents(model.standard), Cents(limitsOf(db, m, today)))
        assertEquals(want, got, "$where: ceiling($m)")
        assertOver(got, "$where: ceiling($m)")
    }
    assertEquals(v, db.version.value, "$where: ceiling reads bumped the version")
}

/** Months worth reading after a step: around [cur], around the rows' edges and a few random rows. */
private fun probeMonths(model: TetoModel, cur: YearMonth, rnd: Random): List<YearMonth> {
    val out = mutableListOf(cur, cur.minusMonth(), cur.plusMonth(), cur.plusMonth().plusMonth())
    model.first()?.let { out += it; out += it.minusMonth() }
    model.last()?.let { out += it; out += it.plusMonth() }
    val keys = model.rows.keys.toList()
    if (keys.isNotEmpty()) repeat(4) { out += keys.random(rnd) }
    return out
}

/** Hostile and ordinary cents: 0, negatives, Long extremes, around [max], typical values. */
private fun randomCents(rnd: Random, max: Long): Long = when (rnd.nextInt(14)) {
    0 -> 0L
    1 -> -rnd.nextLong(1, Long.MAX_VALUE)
    2 -> Long.MIN_VALUE
    3 -> Long.MAX_VALUE
    4 -> max
    5 -> if (max < Long.MAX_VALUE) max + 1 else max
    6 -> max - 1
    7 -> -1L
    8 -> 1L
    9 -> rnd.nextLong(max / 2, max)
    else -> rnd.nextLong(1, 100_000_000L)
}

private fun valid(cents: Long, max: Long) = cents == 0L || cents in 1..max

/** Any day of 2020..2034, with month-end days (28–31, 1) often. */
private fun randomDay(rnd: Random): LocalDate {
    val ym = YearMonth(rnd.nextInt(2020, 2035), rnd.nextInt(1, 13))
    val d = if (rnd.nextBoolean()) listOf(1, 2, 28, 29, 30, 31).random(rnd) else rnd.nextInt(1, 32)
    return ym.onDay(minOf(d, ym.numberOfDays))
}

/** A day whose budget month is not before the model's first row (behaviour there isn't documented). */
private fun dayFor(model: TetoModel, startDay: Int, rnd: Random): LocalDate {
    val first = model.first() ?: return randomDay(rnd)
    repeat(20) {
        val d = randomDay(rnd)
        if (tMonthOf(d, startDay) >= first) return d
    }
    var m = first
    repeat(rnd.nextInt(0, 14)) { m = m.plusMonth() }
    return tStart(m, startDay)
}

private fun randomStartDay(rnd: Random): Int = when (rnd.nextInt(4)) {
    0 -> rnd.nextInt(1, 32)
    1 -> listOf(0, 32, -1, Int.MIN_VALUE, Int.MAX_VALUE).random(rnd)
    else -> rnd.nextInt(28, 32)
}

/** A budget limit that keeps Σ of up to [MAX_BUDGETS] budgets far from Long overflow. */
private fun budgetLimit(rnd: Random, max: Long): Long =
    if (rnd.nextInt(10) == 0) minOf(max, 100_000_000_000_000_000L) else rnd.nextLong(1, 10_000_000L)

private const val MAX_BUDGETS = 30

class TetoFuzzTest {

    @Test
    fun `random setCeiling, ensureMonths, start days and budgets follow the model`() = realTime {
        for (seed in listOf(0x7E70L, 0x7E71L, 0x7E72L)) withDb { db ->
            val rnd = Random(seed)
            val max = findMax(db)
            val model = TetoModel()
            val budgets = mutableListOf<BudgetId>()
            var startDay = db.settings().monthStartDay
            var names = 0
            var today = LocalDate(2026, 10, 16)
            check(db, model, listOf(tMonthOf(today, startDay)), today, "seed $seed start")
            for (step in 0 until 220) {
                today = dayFor(model, startDay, rnd)
                val cur = tMonthOf(today, startDay)
                val where = "seed $seed step $step today $today startDay $startDay"
                val v0 = db.version.value
                when (rnd.nextInt(10)) {
                    in 0..4 -> {
                        val cents = randomCents(rnd, max)
                        val onward = rnd.nextBoolean()
                        val w = "$where setCeiling($cents, onward=$onward)"
                        val ok = noThrow(w) { db.setCeiling(Cents(cents), onward, today) }
                        if (!valid(cents, max)) {
                            assertFalse(ok, "$w: refused")
                            assertEquals(v0, db.version.value, "$w: refused call bumped the version")
                        } else {
                            assertTrue(ok, "$w: accepted")
                            val e = model.set(cents, onward, cur)
                            if (e.intended) assertEquals(v0 + 1, db.version.value, "$w: one bump")
                            else if (e.created == 0) assertEquals(v0, db.version.value, "$w: nothing changed, no bump")
                        }
                    }
                    5 -> {
                        val m = cur
                        noThrow("$where ensureMonths($m)") { db.ensureMonths(m) }
                        model.fill(m)
                        assertEquals(v0, db.version.value, "$where ensureMonths($m) bumped the version")
                    }
                    6 -> {
                        val day = randomStartDay(rnd)
                        val old = startDay
                        noThrow("$where setMonthStartDay($day)") { db.setMonthStartDay(day, today) }
                        startDay = db.settings().monthStartDay
                        if (day in 1..31) assertEquals(day, startDay, "$where setMonthStartDay($day)")
                        // D46: existing rows and the default don't move; a new current month without a row gets the default.
                        if (startDay != old) model.startMoved(tMonthOf(today, startDay))
                    }
                    7 -> if (budgets.size < MAX_BUDGETS) {
                        val id = noThrow("$where createBudget") { db.createBudget("Verba ${names++}", Cents(budgetLimit(rnd, max)), today) }
                        assertTrue(id.v != 0L, "$where createBudget")
                        budgets += id
                    }
                    8 -> if (budgets.isNotEmpty()) {
                        val id = budgets.random(rnd)
                        assertTrue(noThrow("$where setLimit") { db.setLimit(id, Cents(budgetLimit(rnd, max)), today) }, "$where setLimit $id")
                    }
                    else -> if (budgets.isNotEmpty() && rnd.nextInt(3) == 0) {
                        val id = budgets.removeAt(rnd.nextInt(budgets.size))
                        noThrow("$where deleteBudget") { db.deleteBudget(id) }
                    }
                }
                check(db, model, probeMonths(model, tMonthOf(today, startDay), rnd), today, where)
            }
        }
    }

    @Test
    fun `cents outside 0 to the maximum are refused and write nothing`() = realTime {
        withDb { db ->
            val seed = 0x7E73L
            val rnd = Random(seed)
            val max = findMax(db)
            val model = TetoModel()
            val today = LocalDate(2026, 10, 16)
            var startDay = 1
            db.createBudget("Mercado", Cents(120_000), today)
            // A state with rows: a default, "só este mês" in the current month, later months filled.
            assertTrue(db.setCeiling(Cents(400_000), true, today)); model.set(400_000, true, tMonthOf(today, startDay))
            val later = LocalDate(2027, 3, 31)
            assertTrue(db.setCeiling(Cents(410_000), false, later)); model.set(410_000, false, tMonthOf(later, startDay))
            val fixed = mutableListOf(-1L, Long.MIN_VALUE, Long.MIN_VALUE + 1, -max, -(max + 1))
            if (max < Long.MAX_VALUE) fixed += listOf(max + 1, max + 2, Long.MAX_VALUE, Long.MAX_VALUE - 1)
            val inputs = fixed + List(200) {
                when (rnd.nextInt(3)) {
                    0 -> -rnd.nextLong(1, Long.MAX_VALUE)
                    1 -> if (max < Long.MAX_VALUE) rnd.nextLong(max + 1, Long.MAX_VALUE) else -rnd.nextLong(1, 1_000_000L)
                    else -> -rnd.nextLong(1, 1_000_000L)
                }
            }
            for ((i, cents) in inputs.withIndex()) {
                if (i % 50 == 0 && i > 0) {
                    val day = rnd.nextInt(29, 32)
                    db.setMonthStartDay(day, later)
                    startDay = db.settings().monthStartDay
                }
                val d = dayFor(model, startDay, rnd)
                val onward = rnd.nextBoolean()
                val where = "seed $seed input $i setCeiling($cents, onward=$onward, $d) startDay $startDay"
                val v0 = db.version.value
                assertFalse(noThrow(where) { db.setCeiling(Cents(cents), onward, d) }, where)
                assertEquals(v0, db.version.value, "$where: version")
                check(db, model, probeMonths(model, tMonthOf(d, startDay), rnd), d, where)
            }
            // The boundary itself is accepted, in both modes.
            assertTrue(db.setCeiling(Cents(max), false, today), "setCeiling(max=$max, só este mês)")
            model.set(max, false, tMonthOf(today, startDay))
            assertTrue(db.setCeiling(Cents(max), true, today), "setCeiling(max=$max, em diante)")
            model.set(max, true, tMonthOf(today, startDay))
            check(db, model, probeMonths(model, tMonthOf(today, startDay), rnd), today, "seed $seed at max")
        }
    }

    @Test
    fun `previewCeiling never writes and swaps one budget's limit`() = realTime {
        for (seed in listOf(0x7E74L, 0x7E75L)) withDb { db ->
            val rnd = Random(seed)
            val max = findMax(db)
            val model = TetoModel()
            val budgets = mutableListOf<BudgetId>()
            val deleted = mutableListOf<BudgetId>()
            var startDay = 1
            var names = 0
            for (step in 0 until 200) {
                val today = dayFor(model, startDay, rnd)
                val cur = tMonthOf(today, startDay)
                val where = "seed $seed step $step today $today startDay $startDay"
                // Shake the state a little: budgets in different months, ceilings, start days.
                when (rnd.nextInt(6)) {
                    0 -> if (budgets.size < MAX_BUDGETS) budgets += db.createBudget("V${names++}", Cents(budgetLimit(rnd, max)), today).also { assertTrue(it.v != 0L, where) }
                    1 -> if (budgets.isNotEmpty()) db.setLimit(budgets.random(rnd), Cents(budgetLimit(rnd, max)), today)
                    2 -> if (budgets.isNotEmpty() && rnd.nextInt(3) == 0) {
                        val id = budgets.removeAt(rnd.nextInt(budgets.size))
                        db.deleteBudget(id)
                        deleted += id
                    }
                    3 -> {
                        val c = if (rnd.nextBoolean()) 0L else rnd.nextLong(1, 100_000_000L)
                        val onward = rnd.nextBoolean()
                        assertTrue(db.setCeiling(Cents(c), onward, today), where)
                        model.set(c, onward, cur)
                    }
                    4 -> if (rnd.nextInt(3) == 0) {
                        db.setMonthStartDay(rnd.nextInt(28, 32), today)
                        startDay = db.settings().monthStartDay
                    }
                    else -> {}
                }
                val t = dayFor(model, startDay, rnd)
                val m = tMonthOf(t, startDay)
                val target = when (rnd.nextInt(8)) {
                    0 -> BudgetId(0)
                    1 -> listOf(BudgetId(-1), BudgetId(Long.MAX_VALUE), BudgetId(Long.MIN_VALUE), BudgetId(1_000_000)).random(rnd)
                    2 -> if (deleted.isNotEmpty()) deleted.random(rnd) else BudgetId(0)
                    else -> if (budgets.isNotEmpty()) budgets.random(rnd) else BudgetId(0)
                }
                val limit = when (rnd.nextInt(3)) {
                    0 -> randomCents(rnd, max)
                    else -> rnd.nextLong(1, 100_000_000L)
                }
                val w = "$where previewCeiling($target, $limit, $t)"
                val v0 = db.version.value
                val before = db.ceiling(m)
                val ownCeiling = Ceiling(m, Cents(model.rows[m] ?: 0L), Cents(model.standard), Cents(limitsOf(db, m, t)))
                assertEquals(ownCeiling, before, "$w: ceiling before")
                val sumLimits = if (limit in 1..max && limit <= 100_000_000_000_000_000L) limitsOf(db, m, t, except = target) + limit
                else if (limit in 1..max) null // Σ could pass Long: only the no-write part is checked below
                else ownCeiling.limits.v
                val got = noThrow(w) { db.previewCeiling(target, Cents(limit), t) }
                assertEquals(v0, db.version.value, "$w: version")
                assertEquals(before, db.ceiling(m), "$w: ceiling after")
                if (sumLimits != null) {
                    assertEquals(ownCeiling.copy(limits = Cents(sumLimits)), got, w)
                    assertOver(got, w)
                }
            }
        }
    }

    @Test
    fun `Desfazer with two setCeiling calls puts back the month and the default`() = realTime {
        withDb { db ->
            val seed = 0x7E76L
            val rnd = Random(seed)
            val max = findMax(db)
            val model = TetoModel()
            var startDay = 1
            db.createBudget("Casa", Cents(300_000), LocalDate(2026, 1, 10))
            for (step in 0 until 150) {
                if (rnd.nextInt(15) == 0) {
                    val old = startDay
                    db.setMonthStartDay(randomStartDay(rnd), LocalDate(2026, 10, 16))
                    startDay = db.settings().monthStartDay
                    if (startDay != old) model.startMoved(tMonthOf(LocalDate(2026, 10, 16), startDay))
                }
                val today = dayFor(model, startDay, rnd)
                val cur = tMonthOf(today, startDay)
                db.ensureMonths(cur)
                model.fill(cur)
                val before = db.ceiling(cur)
                val cents = randomCents(rnd, max).let { if (valid(it, max)) it else rnd.nextLong(0, 1_000_000L) }
                val onward = rnd.nextBoolean()
                val where = "seed $seed step $step today $today startDay $startDay setCeiling($cents, onward=$onward) from $before"
                assertTrue(db.setCeiling(Cents(cents), onward, today), where)
                model.set(cents, onward, cur)
                check(db, model, probeMonths(model, cur, rnd), today, where)
                if (rnd.nextBoolean()) {
                    // D46: setCeiling(before.standard, onward = true) then setCeiling(before.cents, onward = false).
                    assertTrue(db.setCeiling(before.standard, true, today), "$where undo 1")
                    model.set(before.standard.v, true, cur)
                    assertTrue(db.setCeiling(before.cents, false, today), "$where undo 2")
                    model.set(before.cents.v, false, cur)
                    val after = db.ceiling(cur)
                    assertEquals(before.cents, after.cents, "$where: month after Desfazer")
                    assertEquals(before.standard, after.standard, "$where: default after Desfazer")
                    assertEquals(before, after, "$where: Desfazer")
                    check(db, model, probeMonths(model, cur, rnd), today, "$where undone")
                }
            }
        }
    }

    @Test
    fun `month start day changes keep every ceiling and give a new current month the default`() = realTime {
        withDb { db ->
            val seed = 0x7E77L
            val rnd = Random(seed)
            val max = findMax(db)
            val model = TetoModel()
            var startDay = 1
            db.createBudget("Mercado", Cents(90_000), LocalDate(2025, 12, 30))
            for (step in 0 until 120) {
                val today = dayFor(model, startDay, rnd)
                val cur = tMonthOf(today, startDay)
                val where = "seed $seed step $step today $today startDay $startDay"
                if (rnd.nextInt(3) == 0) {
                    val cents = rnd.nextLong(0, 1_000_000L)
                    val onward = rnd.nextBoolean()
                    assertTrue(db.setCeiling(Cents(cents), onward, today), "$where setCeiling($cents, $onward)")
                    model.set(cents, onward, cur)
                }
                val day = randomStartDay(rnd)
                val months = probeMonths(model, cur, rnd) + probeMonths(model, tMonthOf(today, if (day in 1..31) day else startDay), rnd)
                val old = startDay
                noThrow("$where setMonthStartDay($day)") { db.setMonthStartDay(day, today) }
                startDay = db.settings().monthStartDay
                if (startDay != old) model.startMoved(tMonthOf(today, startDay))
                check(db, model, months, today, "$where after setMonthStartDay($day)")
                // The new current month: its own row, or the default once ensureMonths runs (D46).
                val now = tMonthOf(today, startDay)
                if (model.first().let { it == null || now >= it }) {
                    db.ensureMonths(now)
                    model.fill(now)
                    check(db, model, listOf(now), today, "$where ensureMonths($now)")
                }
            }
        }
    }

    @Test
    fun `over is limits minus cents only when there is a ceiling below them`() {
        val rnd = Random(0x7E78L)
        repeat(2_000) { i ->
            val cents = when (rnd.nextInt(4)) {
                0 -> 0L
                1 -> -rnd.nextLong(1, 1_000_000L)
                else -> rnd.nextLong(1, 1_000_000_000L)
            }
            val limits = if (rnd.nextInt(5) == 0) cents else rnd.nextLong(0, 1_000_000_000L)
            val c = Ceiling(YearMonth(2026, 10), Cents(cents), Cents(rnd.nextLong(0, 1_000_000L)), Cents(limits))
            assertEquals(Cents(maxOf(0L, if (cents > 0) limits - cents else 0L)), c.over, "case $i: $c")
        }
    }
}
