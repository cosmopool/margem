package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.minusMonth
import kotlinx.datetime.onDay
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toInstant
import kotlinx.datetime.yearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Margem honesta (spec §3, §4, §5; margem-plan M1–M6; architecture D68–D73) fuzz, contract round D74.
// - budgetMonth (pure, D23) on random cotas, open reserves, fixed and variable spending, step edits, start
//   days 1–31, week start days and todays inside and outside the month: never throws, and every number the
//   spec and the KDoc state outright holds (remaining = limit − spent − open, freeLimit = max(0, limit − planned)
//   ≥ 0, variable = spent − fixed, Σ passos = freeLimit, idealByDay last day = freeLimit, M3 states, M6
//   projection, D70 edits, spec §4 rounding).
// - A metamorphic relation of the spec: the passos, the ideal and Week.spent of a month with previstos are
//   those of a month without previstos whose cota is the cota livre and whose gasto is the gasto variável.
// - The Db (public API, real SQLite): random sequences of Salvar previsto (once / todo mês), "Todo mês" from
//   an old lançamento (late occurrences), closing occurrences at the same or another value (parcelas, D72
//   date rule), ordinary lançamentos with parcelas, skips, estornos and their undo, deleting lançamentos and
//   previstos, step edits and new cotas, checked after every step against a naive model written from the
//   spec (what was lançado, skipped, refunded) for today's budget month and the one before.
// Fixed seeds, bounded iterations, a wall-clock timeout, one temporary SQLite file per Db. Expected values
// come from the spec as plain sums of what the test generated, never from the implementation. Every
// failure message carries the seed, the step and the input.

private val RF_TODAY = LocalDate(2026, 10, 9) // sexta, 9 out 2026: the book's scenario
private val RF_TZ = TimeZone.of("America/Sao_Paulo")
private const val RF_PKG = "com.banco.reserva"
private const val RF_LABEL = "Banco Reserva"
private val RF_AT0 = LocalDate(2026, 10, 1).atTime(9, 0).toInstant(RF_TZ).toEpochMilliseconds()

private val RF_SEEDS = listOf(1L, 7L, 42L, 2026L, 9_10_2026L, 31_337L)
private const val RF_PURE_ITERATIONS = 250
private val RF_DB_SEEDS = listOf(9L, 10L, 2026L, 777L, 4_242L)
private const val RF_DB_STEPS = 45

/** Real threads under a wall-clock timeout (withTimeout inside Dispatchers.Default), so a hang fails. */
private fun rfRealTime(block: suspend () -> Unit) = runTest(timeout = 240.seconds) {
    withContext(Dispatchers.Default) { withTimeout(200.seconds) { block() } }
}

/** A fresh Db on a temporary file, closed and deleted (with -wal/-shm) afterwards. */
private suspend fun rfWithDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "reserva-fuzz-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path, {}, RF_TZ)
        try {
            block(db)
        } finally {
            db.close()
        }
    } finally {
        for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
    }
}

/** Runs one call of the Db API; any exception (but cancellation/timeout) fails with the context. */
private suspend fun <T> rfNoThrow(ctx: () -> String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fail("threw $e\n${ctx()}", e)
}

/** The same for the pure function. */
private fun <T> rfPure(ctx: () -> String, block: () -> T): T = try {
    block()
} catch (e: Throwable) {
    fail("budgetMonth threw $e\n${ctx()}", e)
}

/** Messages are built only on failure: the model dumps are long. */
private fun rfEq(expected: Any?, actual: Any?, msg: () -> String) {
    if (expected != actual) fail("${msg()}\nexpected: $expected\nactual:   $actual")
}

private fun rfTrue(cond: Boolean, msg: () -> String) {
    if (!cond) fail(msg())
}

private fun rfShow(bm: BudgetMonth): String =
    "BudgetMonth(${bm.start}..${bm.end} limit=${bm.limit.v} spent=${bm.spent.v} fixed=${bm.fixed.v} open=${bm.open.v} " +
        "variable=${bm.variable.v} freeLimit=${bm.freeLimit.v} remaining=${bm.remaining.v} ideal=${bm.ideal.v} " +
        "status=${bm.status} projection=${bm.projection.v} weeks=[" +
        bm.weeks.joinToString(" ") {
            "${it.start}/${it.end}:${it.step.v}${if (it.edited) "e" else ""}${if (it.locked) "L" else ""}(max=${it.max.v} spent=${it.spent.v})"
        } + "] idealByDay.last=${bm.idealByDay.lastOrNull()?.v})"

// ---- the calendar of the spec (§3, §4), written from the text ----

/** [day] in [ym], or the month's last day when it has no such day (spec §3, P8). */
private fun rfOn(ym: YearMonth, day: Int): LocalDate = ym.onDay(minOf(day, ym.numberOfDays))

private class RfWin(val start: LocalDate, val end: LocalDate) {
    operator fun contains(d: LocalDate) = d >= start && d <= end
    val days: Int get() = start.daysUntil(end) + 1
    override fun toString() = "$start..$end"
}

/** Spec §3: from day D to the eve of day D of the next month; a missing D is the month's last day. */
private fun rfWindow(ym: YearMonth, startDay: Int) = RfWin(rfOn(ym, startDay), rfOn(ym.plusMonth(), startDay).minus(1, DateTimeUnit.DAY))

/** The budget month [d] falls in (spec §3). */
private fun rfMonthOf(d: LocalDate, startDay: Int): YearMonth = if (d >= rfOn(d.yearMonth, startDay)) d.yearMonth else d.yearMonth.minusMonth()

/** Spec §4: calendar weeks starting on [weekStart], cut to the month; the first and last may be partial. */
private fun rfWeeks(w: RfWin, weekStart: DayOfWeek): List<Pair<LocalDate, LocalDate>> {
    val out = ArrayList<Pair<LocalDate, LocalDate>>()
    var s = w.start
    while (s <= w.end) {
        var e = s
        while (e < w.end && e.plus(1, DateTimeUnit.DAY).dayOfWeek != weekStart) e = e.plus(1, DateTimeUnit.DAY)
        out.add(s to e)
        s = e.plus(1, DateTimeUnit.DAY)
    }
    return out
}

private fun Week.days(): Long = (start.daysUntil(end) + 1).toLong()

// ---- what the spec says of every BudgetMonth, whatever the input ----

/**
 * The rules spec §3–§5 and the KDoc of BudgetMonth / Week / budgetMonth state outright, read off [bm] alone.
 * [exact] adds the rounding rules (spec §4 cents, §5 ideal, M6 projection), skipped for the huge inputs whose
 * products could overflow (the KDoc says nothing of overflow).
 */
private fun rfCheckMonth(bm: BudgetMonth, today: LocalDate, exact: Boolean, ctx: () -> String) {
    fun m(rule: String): () -> String = { "$rule\n${ctx()}\n${rfShow(bm)}" }
    // M1, spec §3: Margem = cota − gasto do mês − previstos em aberto do mês.
    rfEq(bm.limit.v - bm.spent.v - bm.open.v, bm.remaining.v, m("M1 spec §3: remaining = limit − spent − open"))
    // M2: previstos do mês = em aberto + lançados.
    rfEq(bm.open.v + bm.fixed.v, bm.planned.v, m("M2: planned = open + fixed"))
    // M3, M10, spec §3: cota livre = cota − previstos do mês, no mínimo R$ 0,00.
    rfEq(maxOf(0L, bm.limit.v - bm.open.v - bm.fixed.v), bm.freeLimit.v, m("M3 M10 spec §3: freeLimit = max(0, limit − open − fixed)"))
    rfTrue(bm.freeLimit.v >= 0) { m("spec §3: freeLimit ≥ 0")() }
    // M3, M4, spec §3: gasto variável = gasto do mês sem os lançamentos de previsto.
    rfEq(bm.spent.v - bm.fixed.v, bm.variable.v, m("M3 M4: variable = spent − fixed"))

    val weeks = bm.weeks
    rfTrue(weeks.isNotEmpty()) { m("spec §4: the month has weeks")() }
    rfEq(bm.start, weeks.first().start, m("spec §4: the first week starts with the month"))
    rfEq(bm.end, weeks.last().end, m("spec §4: the last week ends with the month"))
    for (i in 1 until weeks.size) rfEq(weeks[i - 1].end.plus(1, DateTimeUnit.DAY), weeks[i].start, m("spec §4: weeks are contiguous (week $i)"))
    // Spec §4, D68, Week.step KDoc: Σ steps of the month = freeLimit.
    rfEq(bm.freeLimit.v, weeks.sumOf { it.step.v }, m("spec §4 D68: Σ passos = cota livre"))
    rfTrue(weeks.all { it.step.v >= 0 }) { m("spec §4: a semana automática nunca fica abaixo de R$ 0,00")() }
    // Week.spent KDoc: the gasto variável of the week; the weeks cover the month, so Σ = variable.
    rfEq(bm.variable.v, weeks.sumOf { it.spent.v }, m("Week.spent D68: Σ Week.spent = variable"))
    val auto = weeks.count { !it.edited }
    rfTrue(auto >= 1) { m("spec §4: uma semana fica sempre automática")() }
    val editedSum = weeks.filter { it.edited }.sumOf { it.step.v }
    rfTrue(editedSum <= bm.freeLimit.v) { m("D70 budgetMonth KDoc: edits count only while they fit the cota livre")() }
    for (w in weeks) {
        val locked = !w.edited && auto == 1
        rfEq(locked, w.locked, m("Week.locked: the only week not edited (week ${w.start})"))
        // Week.max KDoc, D70: freeLimit − Σ steps of the other edited weeks; Cents(0) when locked.
        val max = if (locked) 0L else bm.freeLimit.v - (editedSum - if (w.edited) w.step.v else 0L)
        rfEq(max, w.max.v, m("Week.max D70: freeLimit − Σ other edited weeks (week ${w.start})"))
    }

    // idealByDay KDoc (D44, D68): one per day, never decreases, last day = Σ steps = freeLimit.
    val days = bm.start.daysUntil(bm.end) + 1
    rfEq(days, bm.idealByDay.size, m("D44: one idealByDay per day of the month"))
    for (i in 1 until bm.idealByDay.size) {
        rfTrue(bm.idealByDay[i].v >= bm.idealByDay[i - 1].v) { m("D44: idealByDay never decreases (index $i)")() }
    }
    rfTrue(bm.idealByDay.first().v >= 0) { m("D44: idealByDay starts at ≥ 0")() }
    rfEq(bm.freeLimit.v, bm.idealByDay.last().v, m("D68: idealByDay last day = freeLimit"))
    rfTrue(bm.ideal.v in 0..bm.freeLimit.v) { m("spec §5: 0 ≤ ritmo ideal ≤ cota livre")() }
    val inMonth = today >= bm.start && today <= bm.end
    if (inMonth) rfEq(bm.ideal, bm.idealByDay[bm.start.daysUntil(today)], m("idealByDay KDoc: idealByDay[today − start] == ideal"))

    // M3, spec §5 table: Fora da margem iff margem < 0; Acima do ritmo iff variável > ideal and margem ≥ 0; else No ritmo.
    val status = when {
        bm.remaining.v < 0 -> PaceStatus.OVERRUN
        bm.variable.v > bm.ideal.v -> PaceStatus.AHEAD
        else -> PaceStatus.ON_PACE
    }
    rfEq(status, bm.status, m("M3 spec §5: state from remaining, variable and ideal"))

    if (!exact) return
    // Spec §4: automatic weeks share freeLimit − Σ edited by days, rounded down; the last automatic one takes the rest.
    val rest = bm.freeLimit.v - editedSum
    val autoDays = weeks.filter { !it.edited }.sumOf { it.days() }
    val lastAuto = weeks.indexOfLast { !it.edited }
    var given = 0L
    for ((i, w) in weeks.withIndex()) {
        if (w.edited || i == lastAuto) continue
        val e = rest * w.days() / autoDays
        rfEq(e, w.step.v, m("spec §4: automatic step = (freeLimit − Σ edited) × ${w.days()} ÷ $autoDays, rounded down (week ${w.start})"))
        given += e
    }
    rfEq(rest - given, weeks[lastAuto].step.v, m("spec §4: the last automatic week takes the rest"))
    // Spec §5: ideal = steps of the closed weeks + step of today's week × days elapsed (with today) ÷ its days.
    if (inMonth) {
        var e = 0L
        for (w in weeks) {
            if (w.end < today) e += w.step.v
            else if (w.start <= today) e += w.step.v * (w.start.daysUntil(today) + 1) / w.days()
        }
        rfEq(e, bm.ideal.v, m("spec §5: ritmo ideal of today from the passos"))
    }
    // M6, spec §5: variable × (freeLimit ÷ ideal) + previstos do mês, rounded down; none (0) when ideal is 0.
    val projection = if (bm.ideal.v == 0L) 0L else bm.variable.v * bm.freeLimit.v / bm.ideal.v + bm.planned.v
    rfEq(projection, bm.projection.v, m("M6 spec §5: projection = variable × freeLimit ÷ ideal + planned (0 when ideal is 0)"))
}

// ---- budgetMonth inputs ----

private data class RfIn(
    val month: YearMonth,
    val startDay: Int,
    val weekStart: DayOfWeek,
    val today: LocalDate,
    val limit: Long,
    val edits: Map<LocalDate, Long>,
    /** Gasto variável by day: the part of spentByDay that is not fixed. */
    val variable: Map<LocalDate, Long>,
    /** fixedByDay; also inside spentByDay ("the whole gasto, fixed included"). */
    val fixed: Map<LocalDate, Long>,
    val open: Long,
) {
    val spent: Map<LocalDate, Long>
        get() = (variable.keys + fixed.keys).associateWith { (variable[it] ?: 0L) + (fixed[it] ?: 0L) }

    fun call(): BudgetMonth = budgetMonth(
        month, startDay, weekStart, today, Cents(limit),
        edits.mapValues { Cents(it.value) }, spent.mapValues { Cents(it.value) }, fixed.mapValues { Cents(it.value) }, Cents(open),
    )
}

/**
 * Random inputs. Normal scale keeps every product of the exact rules inside a Long (limit ≤ R$ 1 mi, ≤ 20 days
 * of ≤ R$ 100 mil); huge scale goes near MAX_CENTS for the never-throws and linear checks only.
 */
private fun rfGen(rnd: Random, huge: Boolean, withPrevisto: Boolean): RfIn {
    val month = YearMonth(2026 + rnd.nextInt(3), rnd.nextInt(1, 13))
    val startDay = if (rnd.nextInt(4) == 0) rnd.nextInt(28, 32) else rnd.nextInt(1, 32)
    val weekStart = DayOfWeek.entries.random(rnd)
    val win = rfWindow(month, startDay)
    val today = when (rnd.nextInt(10)) {
        0 -> win.start.minus(rnd.nextInt(1, 40), DateTimeUnit.DAY)
        1 -> win.end.plus(rnd.nextInt(1, 40), DateTimeUnit.DAY)
        2 -> win.start
        3 -> win.end
        else -> win.start.plus(rnd.nextInt(win.days), DateTimeUnit.DAY)
    }
    val limitMax = if (huge) 90_000_000_000_000_000L else 100_000_000L
    val dayMax = if (huge) 1_000_000_000_000_000L else 10_000_000L
    val limit = when (rnd.nextInt(10)) {
        0 -> 0L
        1, 2 -> rnd.nextLong(1, 10_001)
        3, 4, 5 -> rnd.nextLong(1, 1_000_001)
        else -> rnd.nextLong(1, limitMax + 1)
    }
    fun day(): LocalDate = when (rnd.nextInt(8)) {
        0 -> win.start.minus(rnd.nextInt(1, 20), DateTimeUnit.DAY)
        1 -> win.end.plus(rnd.nextInt(1, 20), DateTimeUnit.DAY)
        else -> win.start.plus(rnd.nextInt(win.days), DateTimeUnit.DAY)
    }
    fun upTo(max: Long): Long = rnd.nextLong(0, minOf(max, dayMax) + 1)

    val fixed = HashMap<LocalDate, Long>()
    var open = 0L
    if (withPrevisto) {
        repeat(rnd.nextInt(0, 5)) { fixed[day()] = if (rnd.nextInt(8) == 0) 0L else upTo(limit / 2 + 2) }
        open = when (rnd.nextInt(5)) {
            0 -> 0L
            1 -> rnd.nextLong(0, limit / 2 + 1)
            2, 3 -> rnd.nextLong(0, limit + 1)
            else -> rnd.nextLong(0, 2 * limit + 2)
        }
        if (fixed.isEmpty() && open == 0L) open = rnd.nextLong(1, limit + 2)
    }
    val variable = HashMap<LocalDate, Long>()
    repeat(rnd.nextInt(0, 16)) {
        variable[day()] = if (rnd.nextInt(5) == 0) upTo(limit + 1) else upTo(limit / 8 + 2)
    }
    val weeks = rfWeeks(win, weekStart)
    val edits = HashMap<LocalDate, Long>()
    val n = when (rnd.nextInt(5)) { 0, 1 -> 0; 2 -> 1; 3 -> 2; else -> 3 }
    repeat(n) {
        val key = if (rnd.nextInt(4) == 0) day() else weeks.random(rnd).first
        edits[key] = if (rnd.nextInt(3) == 0) rnd.nextLong(0, limit + 2) else rnd.nextLong(0, limit / 3 + 2)
    }
    return RfIn(month, startDay, weekStart, today, limit, edits, variable, fixed, open)
}

/** What the generated inputs say directly (budgetMonth KDoc, spec §3, §4, D70), then the rules of every month. */
private fun rfCheckPure(input: RfIn, bm: BudgetMonth, exact: Boolean, ctx: () -> String) {
    fun m(rule: String): () -> String = { "$rule\n${ctx()}\n${rfShow(bm)}" }
    val win = rfWindow(input.month, input.startDay)
    rfEq(win.start, bm.start, m("spec §3: the month starts on its start day (or its last day)"))
    rfEq(win.end, bm.end, m("spec §3: the month ends on the eve of the next start day"))
    rfEq(input.limit, bm.limit.v, m("limit is the cota given"))
    // budgetMonth KDoc: days outside the month are ignored. spent = Σ spentByDay in the month.
    val spent = input.spent.filterKeys { it in win }.values.sum()
    val fixed = input.fixed.filterKeys { it in win }.values.sum()
    rfEq(spent, bm.spent.v, m("budgetMonth KDoc: spent = Σ spentByDay inside the month"))
    rfEq(fixed, bm.fixed.v, m("D68: fixed = Σ fixedByDay inside the month"))
    rfEq(input.open, bm.open.v, m("D68: open is the reserve given"))
    val weeks = rfWeeks(win, input.weekStart)
    rfEq(weeks, bm.weeks.map { it.start to it.end }, m("spec §4: calendar weeks from the week start day, cut to the month"))
    if (weeks.size == bm.weeks.size) {
        for (w in bm.weeks) {
            // Week.spent KDoc: gasto variável of the week = Σ of the variable part of the days in it.
            val e = input.variable.filterKeys { it >= w.start && it <= w.end }.values.sum()
            rfEq(e, w.spent.v, m("Week.spent D68: gasto variável of the week ${w.start}"))
        }
    }
    // budgetMonth KDoc, D10, D70: edits matching no week are ignored; all of them when they leave no week
    // automatic or sum more than freeLimit.
    val free = maxOf(0L, input.limit - input.open - fixed)
    val matched = input.edits.filterKeys { k -> weeks.any { it.first == k } }
    val counts = matched.isNotEmpty() && matched.size < weeks.size && matched.values.sum() <= free
    for (w in bm.weeks) {
        val e = counts && w.start in matched
        rfEq(e, w.edited, m("D70: edited iff its edit matches and the month's edits fit freeLimit = $free (week ${w.start})"))
        if (e) rfEq(matched.getValue(w.start), w.step.v, m("spec §4: an edited week keeps its step (week ${w.start})"))
    }
    rfCheckMonth(bm, input.today, exact, ctx)
}

// ---- the Db model: what the test did, read with the spec ----

private class RfTx(
    val id: TransactionId,
    val budget: BudgetId,
    val total: Long,
    val n: Int,
    /** The date the lançamento must have: the form's, or the occurrence's when D72 moves it. */
    val date: LocalDate,
    val fixed: Boolean,
    /** The occurrence it closed: (previsto, occurrence date); null when none or the previsto was deleted. */
    var occ: Pair<PlannedId, LocalDate>?,
) {
    var estorno: NoticeId = NoticeId(0)
    val refunded: Boolean get() = estorno.v != 0L

    /** Spec §6: each installment total / n rounded down; the last absorbs the cents. */
    fun inst(k: Int): Long = if (k < n) total / n else total - (n - 1) * (total / n)

    /** D7: installment k = purchase date + (k − 1) months (start days ≤ 28 and kotlinx clamping keep it in the k-th month). */
    fun instDate(k: Int): LocalDate = date.plus(k - 1, DateTimeUnit.MONTH)

    override fun toString() = "tx ${id.v} budget=${budget.v} total=$total n=$n date=$date fixed=$fixed occ=$occ estorno=${estorno.v}"
}

private class RfPlanned(
    val id: PlannedId,
    val budget: BudgetId,
    val cents: Long,
    /** P8: the day of every occurrence; a month without it uses its last day. */
    val day: Int,
    val first: LocalDate,
    val monthly: Boolean,
    /** The lançamento whose "Todo mês" made it (D65); 0 for Salvar previsto. */
    val sourceTx: TransactionId,
) {
    val closedBy = HashMap<LocalDate, TransactionId>()
    val skipped = HashSet<LocalDate>()

    fun isOpen(d: LocalDate) = d !in skipped && d !in closedBy

    /** Occurrence dates in from..to: [first], then [day] of every later calendar month for MONTHLY. */
    fun dates(from: LocalDate, to: LocalDate): List<LocalDate> {
        if (!monthly) return if (first >= from && first <= to) listOf(first) else emptyList()
        val out = ArrayList<LocalDate>()
        var ym = first.yearMonth
        while (true) {
            val d = rfOn(ym, day)
            if (d > to) break
            if (d >= from && d >= first) out.add(d)
            ym = ym.plusMonth()
        }
        return out
    }

    override fun toString() =
        "previsto ${id.v} budget=${budget.v} cents=$cents day=$day first=$first monthly=$monthly source=${sourceTx.v} " +
            "closed=${closedBy.mapValues { it.value.v }} skipped=$skipped"
}

private class RfBudget(val prev: Long, var cur: Long)

private class RfModel {
    val budgets = LinkedHashMap<BudgetId, RfBudget>()
    val txs = LinkedHashMap<TransactionId, RfTx>()
    val planned = LinkedHashMap<PlannedId, RfPlanned>()
    /** Step edits stored for today's month (setStep accepted), by budget and week start. */
    val edits = HashMap<BudgetId, HashMap<LocalDate, Long>>()

    /** Spec §3, §6: Σ installments dated in from..to of [b]'s lançamentos not refunded; [kind] 1 = fixed only, 2 = variable only. */
    fun spent(b: BudgetId, from: LocalDate, to: LocalDate, kind: Int = 0): Long {
        var sum = 0L
        for (t in txs.values) {
            if (t.budget != b || t.refunded) continue
            if (kind == 1 && !t.fixed) continue
            if (kind == 2 && t.fixed) continue
            for (k in 1..t.n) {
                val d = t.instDate(k)
                if (d >= from && d <= to) sum += t.inst(k)
            }
        }
        return sum
    }

    /** M1, M2: Σ previsto value of [b]'s open occurrences (not skipped, not lançada) dated in [w], late ones included. */
    fun open(b: BudgetId, w: RfWin): Long {
        var sum = 0L
        for (p in planned.values) if (p.budget == b) for (d in p.dates(w.start, w.end)) if (p.isOpen(d)) sum += p.cents
        return sum
    }

    fun free(b: BudgetId, w: RfWin, limit: Long): Long = maxOf(0L, limit - open(b, w) - spent(b, w.start, w.end, 1))

    /** deleteTransaction: S1 reopens its occurrence; D65 takes the previsto its "Todo mês" made while nothing of it is lançado. */
    fun deleteTx(t: RfTx) {
        txs.remove(t.id)
        t.occ?.let { (p, d) -> planned[p]?.closedBy?.remove(d) }
        for (p in planned.values.filter { it.sourceTx == t.id }) if (p.closedBy.isEmpty()) planned.remove(p.id)
    }

    /** deletePlanned: rule and occurrences go; its lançamentos stay, fixed, without origin (D69). */
    fun deletePlanned(p: RfPlanned) {
        planned.remove(p.id)
        for (t in txs.values) if (t.occ?.first == p.id) t.occ = null
    }

    fun dump(b: BudgetId): String = buildString {
        val bb = budgets[b]
        append("model budget ${b.v}: limitPrev=${bb?.prev} limitCur=${bb?.cur} edits=${edits[b]}\n")
        for (t in txs.values) if (t.budget == b) append("  $t\n")
        for (p in planned.values) if (p.budget == b) append("  $p\n")
    }
}

private class RfCtx(val seed: Long, val startDay: Int, val weekStart: DayOfWeek) {
    val m0: YearMonth = rfMonthOf(RF_TODAY, startDay)
    val mPrev: YearMonth = m0.minusMonth()
    val w0 = rfWindow(m0, startDay)
    val wPrev = rfWindow(mPrev, startDay)
    val ops = ArrayList<String>()
    var step = 0

    fun where(): String =
        "seed=$seed step=$step startDay=$startDay weekStart=$weekStart today=$RF_TODAY month=$m0 ($w0) prev=$mPrev ($wPrev)\n" +
            "last ops:\n  " + ops.takeLast(12).joinToString("\n  ")
}

private fun rfForm(
    budget: BudgetId,
    date: LocalDate,
    total: Long,
    n: Int,
    name: String,
    repeats: Repeats = Repeats.ONCE,
    occurrence: OccurrenceId = OccurrenceId(0),
) = TransactionForm(
    budget = budget, tags = listOf("reserva"), total = Cents(total), installments = n, merchant = name.uppercase(),
    date = date, time = LocalTime(12, 0), caption = name, occurrence = occurrence, repeats = repeats,
)

/** Every read of the Db against the model, after each step. */
private suspend fun rfDbCheck(db: Db, model: RfModel, x: RfCtx) {
    val where = x::where
    // budgetsOverview first: with D71 it must create the month's occurrences itself (nothing else read them yet).
    val overview = rfNoThrow({ "budgetsOverview\n${where()}" }) { db.budgetsOverview(x.m0, RF_TODAY, BudgetOrder.MANUAL) }
    rfEq(model.budgets.keys, overview.map { it.id }.toSet()) { "budgetsOverview lists every budget\n${where()}" }
    for (sum in overview) {
        val b = sum.id
        val bb = model.budgets.getValue(b)
        val bm = sum.month
        val ctx = { "budget ${b.v} in ${x.m0} (overview)\n${where()}\n${model.dump(b)}" }
        rfCheckModelMonth(bm, model, b, x.w0, bb.cur, ctx)
        // D70: today's month edits count while they fit the cota livre and leave a week automatic.
        val stored = model.edits[b].orEmpty()
        val free = model.free(b, x.w0, bb.cur)
        val counts = stored.isNotEmpty() && stored.size < bm.weeks.size && stored.values.sum() <= free
        for (w in bm.weeks) {
            val e = counts && w.start in stored
            rfEq(e, w.edited) { "D70 spec §4: edits stored=$stored count iff Σ ≤ cota livre $free (week ${w.start})\n${ctx()}\n${rfShow(bm)}" }
            if (e) rfEq(stored.getValue(w.start), w.step.v) { "spec §4: edited step kept (week ${w.start})\n${ctx()}\n${rfShow(bm)}" }
        }
        rfCheckMonth(bm, RF_TODAY, exact = true, ctx)

        // The month before: its own cota, late occurrences and the lançamentos D72 put there.
        val prev = rfNoThrow({ "budgetDetail(${b.v}, ${x.mPrev})\n${where()}" }) { db.budgetDetail(b, x.mPrev, RF_TODAY) }
        val pctx = { "budget ${b.v} in ${x.mPrev} (detail)\n${where()}\n${model.dump(b)}" }
        rfCheckModelMonth(prev.month, model, b, x.wPrev, bb.prev, pctx)
        rfTrue(prev.month.weeks.none { it.edited }) { "no edit was ever made in the month before\n${pctx()}\n${rfShow(prev.month)}" }
        rfCheckMonth(prev.month, RF_TODAY, exact = true, pctx)
        rfCheckLines(prev, model, b, x.wPrev, pctx)

        val detail = rfNoThrow({ "budgetDetail(${b.v}, ${x.m0})\n${where()}" }) { db.budgetDetail(b, x.m0, RF_TODAY) }
        val dctx = { "budget ${b.v} in ${x.m0} (detail)\n${where()}\n${model.dump(b)}" }
        rfCheckModelMonth(detail.month, model, b, x.w0, bb.cur, dctx)
        rfCheckLines(detail, model, b, x.w0, dctx)
        rfCheckPlannedLines(detail, model, b, x.w0, dctx)
    }

    // Lançamentos: fixed (D69) and the date D72 gives.
    val rows = rfNoThrow({ "transactions\n${where()}" }) { db.transactions() }
    rfEq(model.txs.keys, rows.map { it.id }.toSet()) { "transactions() lists exactly the lançamentos made\n${where()}" }
    for (r in rows) {
        val t = model.txs.getValue(r.id)
        val ctx = { "$t\nrow=$r\n${where()}" }
        rfEq(t.fixed, r.fixed) { "D69: fixed iff the lançamento closed an occurrence (kept when its previsto is deleted)\n${ctx()}" }
        rfEq(t.date, r.date) { "D72: a previsto's lançamento is dated in its occurrence's budget month, else the form's date\n${ctx()}" }
        rfEq(t.total, r.total.v) { "total\n${ctx()}" }
        rfEq(t.n, r.installments) { "installments\n${ctx()}" }
        rfEq(t.refunded, r.refunded) { "refunded\n${ctx()}" }
        rfEq(t.budget, r.budget) { "budget\n${ctx()}" }
    }

    // "A caminho" (R1): every open occurrence dated up to today + 10, late ones included.
    val listed = rfNoThrow({ "planned(today)\n${where()}" }) { db.planned(RF_TODAY) }
    val horizon = RF_TODAY.plus(10, DateTimeUnit.DAY)
    val expected = HashSet<Pair<PlannedId, LocalDate>>()
    for (p in model.planned.values) for (d in p.dates(p.first, horizon)) if (p.isOpen(d)) expected.add(p.id to d)
    rfEq(expected, listed.map { it.planned to it.date }.toSet()) {
        "planned(today): the open occurrences up to today + 10\n${where()}\nmodel:\n" + model.planned.values.joinToString("\n")
    }
    for (o in listed) {
        val p = model.planned.getValue(o.planned)
        rfEq(p.cents, o.cents.v) { "Planned.cents is the previsto's value\n$o\n$p\n${where()}" }
        rfEq(p.budget, o.budget) { "Planned.budget\n$o\n$p\n${where()}" }
    }
}

/** The four sums the model gives for [b] in [w] (spec §3, M1, M2), then nothing else: rfCheckMonth does the rest. */
private fun rfCheckModelMonth(bm: BudgetMonth, model: RfModel, b: BudgetId, w: RfWin, limit: Long, ctx: () -> String) {
    fun m(rule: String): () -> String = { "$rule\n${ctx()}\n${rfShow(bm)}" }
    rfEq(w.start, bm.start, m("spec §3: month start"))
    rfEq(w.end, bm.end, m("spec §3: month end"))
    rfEq(limit, bm.limit.v, m("spec §3: the month's cota (setLimit from today's month on)"))
    // Spec §3: gasto do mês = Σ parcelas dated in the month, refunded ones left out, fixed included.
    rfEq(model.spent(b, w.start, w.end), bm.spent.v, m("spec §3: spent = Σ installments in the month, not refunded"))
    // M2, D68, D69: the lançados = Σ installments in the month of the fixed lançamentos, not refunded (an estorno makes it 0).
    rfEq(model.spent(b, w.start, w.end, 1), bm.fixed.v, m("M2 D69: fixed = Σ installments in the month of the fixed lançamentos, not refunded"))
    // M1, M2, D71: every open occurrence dated in the month, late ones included, whatever the R1 window; skipped never.
    rfEq(model.open(b, w), bm.open.v, m("M1 M2 D71: open = Σ previsto value of the open occurrences dated in the month"))
    for (wk in bm.weeks) {
        rfEq(model.spent(b, wk.start, wk.end, 2), wk.spent.v, m("Week.spent D68: gasto variável of the week ${wk.start}"))
    }
}

/** BudgetDetail.lines KDoc: Σ of the lines not refunded = spent; the fixed ones (D69) = fixed. */
private fun rfCheckLines(d: BudgetDetail, model: RfModel, b: BudgetId, w: RfWin, ctx: () -> String) {
    val notRefunded = d.lines.filter { !it.row.refunded }
    rfEq(d.month.spent.v, notRefunded.sumOf { it.cents.v }) { "BudgetDetail.lines: Σ lines not refunded = month.spent\n${ctx()}" }
    rfEq(model.spent(b, w.start, w.end, 1), notRefunded.filter { it.row.fixed }.sumOf { it.cents.v }) {
        "D69: Σ lines of fixed lançamentos not refunded = the lançados of the month\n${ctx()}\nlines=${d.lines}"
    }
}

/** "Previstos de outubro" (BudgetDetail.plannedLines, PlannedLine KDoc, D73, M12). */
private fun rfCheckPlannedLines(d: BudgetDetail, model: RfModel, b: BudgetId, w: RfWin, ctx: () -> String) {
    val lines = d.plannedLines
    fun m(rule: String): () -> String = { "$rule\n${ctx()}\nplannedLines=${lines.joinToString("\n  ", "\n  ") { "${it.planned.planned.v}@${it.planned.date} cents=${it.cents.v} launched=${it.launched} late=${it.planned.late}" }}" }
    // One line per occurrence of this budget's previstos dated in the month, open or lançada, skipped ones left out.
    val expected = HashMap<Pair<PlannedId, LocalDate>, RfPlanned>()
    for (p in model.planned.values) if (p.budget == b) for (dt in p.dates(w.start, w.end)) if (dt !in p.skipped) expected[p.id to dt] = p
    rfEq(expected.keys, lines.map { it.planned.planned to it.planned.date }.toSet(), m("D73: one line per occurrence in the month, skipped ones left out"))
    rfEq(expected.size, lines.size, m("D73: no occurrence twice"))
    for (i in 1 until lines.size) rfTrue(lines[i].planned.date >= lines[i - 1].planned.date) { m("D73: lines by date")() }
    for (line in lines) {
        val p = expected[line.planned.planned to line.planned.date] ?: continue
        rfEq(b, line.planned.budget, m("PlannedLine: the occurrence's budget"))
        val tx = p.closedBy[line.planned.date]
        if (tx == null) {
            rfTrue(line.open) { m("PlannedLine: open while no lançamento closed it (${p.id.v}@${line.planned.date})")() }
            rfEq(p.cents, line.cents.v, m("PlannedLine.cents: open = the previsto's value"))
            rfEq(line.planned.date < RF_TODAY, line.planned.late, m("PlannedLine: late against the read's today"))
        } else {
            val t = model.txs.getValue(tx)
            // D72 puts its first installment in the occurrence's month: that's the line's installment.
            rfEq(t.date, line.launched, m("PlannedLine.launched: the date of its lançamento's installment in the month"))
            // "What its lançamento adds to fixed": a refunded one adds nothing (D68: estorno → 0).
            rfEq(if (t.refunded) 0L else t.inst(1), line.cents.v, m("PlannedLine.cents: lançada = what its lançamento adds to fixed"))
            rfEq(false, line.planned.late, m("PlannedLine: never late when lançada"))
        }
    }
    rfEq(d.month.open.v, lines.filter { it.open }.sumOf { it.cents.v }, m("BudgetDetail.plannedLines KDoc: Σ open lines = month.open"))
}

/** One random sequence on one Db, checked after every step. */
private suspend fun rfDbRun(db: Db, seed: Long) {
    val rnd = Random(seed)
    // Start days ≤ 28 keep D7's installment k at purchase date + (k − 1) months exactly (spec §6).
    val x = RfCtx(seed, listOf(1, 5, 9, 10, 15, 28).random(rnd), DayOfWeek.entries.random(rnd))
    val model = RfModel()
    val where = x::where
    rfTrue(db.setMonthStartDay(x.startDay, RF_TODAY)) { "setup setMonthStartDay\n${where()}" }
    db.setWeekStart(x.weekStart, RF_TODAY)
    rfTrue(db.addSource(RF_PKG, RF_LABEL, RF_AT0)) { "setup addSource\n${where()}" }
    // Budgets created two months back, so the month before today's has their cota too.
    val created = RF_TODAY.minus(2, DateTimeUnit.MONTH)
    repeat(3) { i ->
        val limit = rnd.nextLong(50_000, 600_001)
        val id = db.createBudget("Verba $i", Cents(limit), created)
        rfTrue(id.v != 0L) { "setup createBudget $i\n${where()}" }
        model.budgets[id] = RfBudget(limit, limit)
    }
    db.ensureMonths(x.m0)
    rfDbCheck(db, model, x)

    val seen = HashMap<OccurrenceId, Pair<PlannedId, LocalDate>>()
    var names = 0
    var notices = 0
    fun pastDate(): LocalDate {
        val from = x.wPrev.start.minus(35, DateTimeUnit.DAY)
        return from.plus(rnd.nextInt(from.daysUntil(RF_TODAY) + 1), DateTimeUnit.DAY)
    }
    fun previstoValue(): Long = when (rnd.nextInt(6)) {
        0 -> rnd.nextLong(400_000, 900_001) // more than most cotas: cota livre 0
        else -> rnd.nextLong(1_000, 250_001)
    }
    suspend fun listOpen(): List<Planned> {
        val l = rfNoThrow({ "planned(today) for the op\n${where()}" }) { db.planned(RF_TODAY) }
        for (o in l) seen[o.occurrence] = o.planned to o.date
        return l
    }

    for (step in 1..RF_DB_STEPS) {
        x.step = step
        yield()
        val op = rnd.nextInt(100)
        when {
            op < 14 -> { // Salvar previsto: a date ahead, once or todo mês.
                val b = model.budgets.keys.random(rnd)
                val date = RF_TODAY.plus(rnd.nextInt(1, 60), DateTimeUnit.DAY)
                val monthly = rnd.nextBoolean()
                val cents = previstoValue()
                val name = "Previsto ${++names}"
                x.ops.add("Salvar previsto budget=${b.v} date=$date cents=$cents monthly=$monthly")
                val s = rfNoThrow({ "addManual Salvar previsto\n${where()}" }) {
                    db.addManual(rfForm(b, date, cents, 1, name, if (monthly) Repeats.MONTHLY else Repeats.ONCE), RF_TODAY)
                }
                rfTrue(s.planned.v != 0L && s.transaction.v == 0L) { "Db.addManual KDoc: a date ahead saves only a previsto, got $s\n${where()}" }
                model.planned[s.planned] = RfPlanned(s.planned, b, cents, date.day, date, monthly, TransactionId(0))
                x.ops[x.ops.lastIndex] += " -> previsto ${s.planned.v}"
            }
            op < 24 -> { // "Todo mês" on a lançamento of today or the past: the previsto starts next month, past ones late.
                val b = model.budgets.keys.random(rnd)
                val date = pastDate()
                val cents = rnd.nextLong(1_000, 200_001)
                val name = "Mensal ${++names}"
                x.ops.add("Todo mês lançamento budget=${b.v} date=$date cents=$cents")
                val s = rfNoThrow({ "addManual Todo mês\n${where()}" }) { db.addManual(rfForm(b, date, cents, 1, name, Repeats.MONTHLY), RF_TODAY) }
                rfTrue(s.transaction.v != 0L && s.planned.v != 0L) { "Db.addManual KDoc: Todo mês from the past writes both, got $s\n${where()}" }
                model.txs[s.transaction] = RfTx(s.transaction, b, cents, 1, date, fixed = false, occ = null)
                // TransactionForm.repeats: the day of [date], first occurrence in the calendar month after [date]'s.
                val first = rfOn(date.yearMonth.plusMonth(), date.day)
                model.planned[s.planned] = RfPlanned(s.planned, b, cents, date.day, first, true, s.transaction)
                x.ops[x.ops.lastIndex] += " -> tx ${s.transaction.v} previsto ${s.planned.v} first=$first"
            }
            op < 44 -> { // Lançar an open occurrence (manual, "É um previsto? Ligar"): same value or another, parcelas, any date up to today.
                val listed = listOpen()
                if (listed.isEmpty()) {
                    x.ops.add("close: no open occurrence")
                } else {
                    val o = listed.random(rnd)
                    val p = model.planned[o.planned] ?: fail("planned(today) lists previsto ${o.planned.v}, unknown to the model\n${where()}")
                    val total = when (rnd.nextInt(3)) {
                        0 -> o.cents.v
                        1 -> o.cents.v + rnd.nextLong(1, 50_001) // bigger: lowers the cota livre (M4)
                        else -> maxOf(1L, o.cents.v - rnd.nextLong(1, 50_001))
                    }
                    val n = if (rnd.nextInt(4) == 0) rnd.nextInt(2, 4) else 1
                    val d = when (rnd.nextInt(3)) {
                        0 -> RF_TODAY
                        1 -> if (o.date <= RF_TODAY) o.date else RF_TODAY
                        else -> x.wPrev.start.plus(rnd.nextInt(x.wPrev.start.daysUntil(RF_TODAY) + 1), DateTimeUnit.DAY)
                    }
                    // D72, M8: a date in another budget month than the occurrence's becomes the occurrence's date.
                    val eff = if (rfMonthOf(d, x.startDay) == rfMonthOf(o.date, x.startDay)) d else o.date
                    x.ops.add("close occurrence ${o.occurrence.v} (previsto ${o.planned.v}@${o.date} cents=${o.cents.v}) total=$total n=$n date=$d -> expected date $eff")
                    rfEq(p.budget, o.budget) { "Planned.budget\n${where()}" }
                    val s = rfNoThrow({ "addManual closing\n${where()}" }) {
                        db.addManual(rfForm(o.budget, d, total, n, o.name, occurrence = o.occurrence), RF_TODAY)
                    }
                    rfTrue(s.transaction.v != 0L && s.planned.v == 0L) { "Db.addManual KDoc: closing an open occurrence writes the lançamento, got $s\n${where()}" }
                    model.txs[s.transaction] = RfTx(s.transaction, o.budget, total, n, eff, fixed = true, occ = o.planned to o.date)
                    p.closedBy[o.date] = s.transaction
                    x.ops[x.ops.lastIndex] += " -> tx ${s.transaction.v}"
                }
            }
            op < 58 -> { // Ordinary lançamento, with parcelas.
                val b = model.budgets.keys.random(rnd)
                val date = pastDate()
                val n = when (rnd.nextInt(6)) { 0 -> 2; 1 -> 3; 2 -> 4; else -> 1 }
                val total = rnd.nextLong(100, 120_001)
                x.ops.add("lançamento budget=${b.v} date=$date total=$total n=$n")
                val s = rfNoThrow({ "addManual ordinary\n${where()}" }) { db.addManual(rfForm(b, date, total, n, "Loja ${++names}"), RF_TODAY) }
                rfTrue(s.transaction.v != 0L && s.planned.v == 0L) { "Db.addManual KDoc: an ordinary lançamento, got $s\n${where()}" }
                model.txs[s.transaction] = RfTx(s.transaction, b, total, n, date, fixed = false, occ = null)
                x.ops[x.ops.lastIndex] += " -> tx ${s.transaction.v}"
            }
            op < 66 -> { // Pular este mês: an open one (true), or a closed / skipped / deleted / unknown one (false).
                val listed = listOpen()
                if (listed.isNotEmpty() && rnd.nextInt(5) != 0) {
                    val o = listed.random(rnd)
                    x.ops.add("skip occurrence ${o.occurrence.v} (previsto ${o.planned.v}@${o.date})")
                    rfTrue(rfNoThrow({ "skipOccurrence\n${where()}" }) { db.skipOccurrence(o.occurrence) }) { "skipOccurrence of an open occurrence\n${where()}" }
                    model.planned.getValue(o.planned).skipped.add(o.date)
                } else {
                    val id = if (seen.isEmpty() || rnd.nextInt(3) == 0) OccurrenceId(987_654_321) else seen.keys.random(rnd)
                    val known = seen[id]
                    val p = known?.let { model.planned[it.first] }
                    val open = p != null && p.isOpen(known.second)
                    x.ops.add("skip seen/unknown occurrence ${id.v} $known open=$open")
                    val ok = rfNoThrow({ "skipOccurrence hostile\n${where()}" }) { db.skipOccurrence(id) }
                    rfEq(open, ok) { "skipOccurrence KDoc: false when not open\n${where()}" }
                    if (ok && p != null) p.skipped.add(known.second)
                }
            }
            op < 74 -> { // Estorno of a lançamento, fixed or not.
                val t = model.txs.values.filter { !it.refunded }.randomOrNull(rnd)
                if (t == null) {
                    x.ops.add("estorno: nothing to refund")
                } else {
                    notices++
                    val at = RF_AT0 + notices * 600_000L
                    val raw = RawNotice(
                        packageName = RF_PKG, appLabel = RF_LABEL, key = "reserva-estorno-$notices", postedAt = at, whenMs = at,
                        title = RF_LABEL, text = "A compra em LOJA no valor de R$ 10,00 foi estornada no seu cartão.",
                    )
                    x.ops.add("estorno of tx ${t.id.v} (fixed=${t.fixed})")
                    rfTrue(rfNoThrow({ "capture\n${where()}" }) { capture(db, raw) }) { "capture of the estorno notice\n${where()}" }
                    val e = rfNoThrow({ "pending\n${where()}" }) { db.pending() }.first { it.raw.key == raw.key }.id
                    rfTrue(rfNoThrow({ "registerRefund\n${where()}" }) { db.registerRefund(e, t.id) }) { "registerRefund of a lançamento not refunded\n${where()}" }
                    t.estorno = e
                }
            }
            op < 78 -> { // Desfazer estorno.
                val t = model.txs.values.filter { it.refunded }.randomOrNull(rnd)
                if (t == null) {
                    x.ops.add("undo estorno: none")
                } else {
                    x.ops.add("undo estorno of tx ${t.id.v}")
                    rfTrue(rfNoThrow({ "undoRefund\n${where()}" }) { db.undoRefund(t.estorno) }) { "undoRefund of a registered estorno\n${where()}" }
                    t.estorno = NoticeId(0)
                }
            }
            op < 84 -> { // Apagar lançamento: a fixed one reopens its occurrence (S1), it reserves again (M1).
                val t = model.txs.values.randomOrNull(rnd)
                if (t == null) {
                    x.ops.add("delete tx: none")
                } else {
                    x.ops.add("delete $t")
                    rfNoThrow({ "deleteTransaction\n${where()}" }) { db.deleteTransaction(t.id) }
                    model.deleteTx(t)
                }
            }
            op < 88 -> { // Apagar previsto: its open occurrences stop reserving; its lançamentos stay fixed (D69).
                val p = model.planned.values.randomOrNull(rnd)
                if (p == null) {
                    x.ops.add("delete previsto: none")
                } else {
                    x.ops.add("delete $p")
                    rfNoThrow({ "deletePlanned\n${where()}" }) { db.deletePlanned(p.id) }
                    model.deletePlanned(p)
                }
            }
            op < 95 -> { // setStep in today's month, against the cota livre (D70).
                val b = model.budgets.keys.random(rnd)
                val bm = rfNoThrow({ "budgetsOverview for setStep\n${where()}" }) { db.budgetsOverview(x.m0, RF_TODAY, BudgetOrder.MANUAL) }
                    .first { it.id == b }.month
                val stored = model.edits.getOrPut(b) { HashMap() }
                val free = model.free(b, x.w0, model.budgets.getValue(b).cur)
                val counts = stored.isNotEmpty() && stored.size < bm.weeks.size && stored.values.sum() <= free
                val w = bm.weeks.random(rnd)
                val edited = counts && w.start in stored
                val autoCount = bm.weeks.count { !(counts && it.start in stored) }
                val locked = !edited && autoCount == 1
                // Week.max (D70) = freeLimit − Σ steps of the other edited weeks.
                val max = free - (if (counts) stored.filterKeys { it != w.start }.values.sum() else 0L)
                val v = if (rnd.nextInt(4) == 0) max + 1 else rnd.nextLong(0, max + 1)
                val expected = !locked && v in 0..max
                x.ops.add("setStep budget=${b.v} week=${w.start} step=$v (free=$free max=$max locked=$locked) expect=$expected")
                val ok = rfNoThrow({ "setStep\n${where()}" }) { db.setStep(b, x.m0, w.start, Cents(v)) }
                rfEq(expected, ok) { "setStep KDoc D70: accepted iff 0 ≤ step ≤ freeLimit − Σ other edited weeks and not locked\n${where()}\n${model.dump(b)}\n${rfShow(bm)}" }
                if (ok) stored[w.start] = v
            }
            else -> { // New cota from today's month on; edits that no longer fit the new cota livre are deleted (D70).
                val b = model.budgets.keys.random(rnd)
                val newLimit = rnd.nextLong(20_000, 700_001)
                val stored = model.edits.getOrPut(b) { HashMap() }
                val newFree = maxOf(0L, newLimit - model.open(b, x.w0) - model.spent(b, x.w0.start, x.w0.end, 1))
                val drops = stored.isNotEmpty() && stored.values.sum() > newFree
                x.ops.add("setLimit budget=${b.v} limit=$newLimit (new cota livre $newFree, edits $stored) drops=$drops")
                val asked = rfNoThrow({ "limitDropsEdits\n${where()}" }) { db.limitDropsEdits(b, Cents(newLimit), RF_TODAY) }
                rfEq(drops, asked) { "limitDropsEdits KDoc D70: true iff the edits sum more than the new cota livre\n${where()}\n${model.dump(b)}" }
                rfTrue(rfNoThrow({ "setLimit\n${where()}" }) { db.setLimit(b, Cents(newLimit), RF_TODAY) }) { "setLimit\n${where()}" }
                model.budgets.getValue(b).cur = newLimit
                if (drops) stored.clear()
            }
        }
        rfDbCheck(db, model, x)
    }
}

class ReservaFuzzTest {

    @Test
    fun `budgetMonth keeps the margem honesta on random cotas previstos and spending`() = rfRealTime {
        for (seed in RF_SEEDS) {
            val rnd = Random(seed)
            repeat(RF_PURE_ITERATIONS) { i ->
                yield()
                // One in five without previsto: the rules of before must satisfy the same statements.
                val input = rfGen(rnd, huge = false, withPrevisto = rnd.nextInt(5) != 0)
                val ctx = { "seed=$seed iteration=$i input=$input" }
                val bm = rfPure(ctx) { input.call() }
                rfCheckPure(input, bm, exact = true, ctx)
            }
        }
    }

    @Test
    fun `budgetMonth never throws on huge cotas previstos and spending`() = rfRealTime {
        for (seed in RF_SEEDS) {
            val rnd = Random(seed xor 0x5EEDL)
            repeat(RF_PURE_ITERATIONS / 2) { i ->
                yield()
                val input = rfGen(rnd, huge = true, withPrevisto = rnd.nextInt(5) != 0)
                val ctx = { "seed=$seed iteration=$i (huge) input=$input" }
                val bm = rfPure(ctx) { input.call() }
                // The sums stay inside a Long here; the products of the rounding rules may not, and the KDoc is silent on overflow.
                rfCheckPure(input, bm, exact = false, ctx)
            }
        }
    }

    @Test
    fun `the free part of a month with previstos is a month whose cota is the cota livre`() = rfRealTime {
        // Spec §4, §5, M3, Week KDoc: the passos split the cota livre, the ritmo ideal comes from them and Week.spent
        // is the gasto variável. So steps, edits, max, locked, Week.spent, ideal and idealByDay of (limit, open, fixed,
        // spent) are those of (cota = freeLimit, no previsto, spent = the variable part).
        for (seed in RF_SEEDS) {
            val rnd = Random(seed + 1)
            repeat(RF_PURE_ITERATIONS) { i ->
                yield()
                val input = rfGen(rnd, huge = false, withPrevisto = true)
                val win = rfWindow(input.month, input.startDay)
                val free = maxOf(0L, input.limit - input.open - input.fixed.filterKeys { it in win }.values.sum())
                val plainIn = input.copy(limit = free, fixed = emptyMap(), open = 0L)
                val ctx = { "seed=$seed iteration=$i input=$input\nplain (cota livre $free, no previsto)=$plainIn" }
                val bm = rfPure(ctx) { input.call() }
                val plain = rfPure(ctx) { plainIn.call() }
                rfEq(free, bm.freeLimit.v) { "M3: freeLimit = max(0, limit − open − Σ fixed in the month)\n${ctx()}\n${rfShow(bm)}" }
                rfEq(plain.weeks, bm.weeks) { "spec §4 D70: weeks of the free part = weeks of a cota equal to the cota livre\n${ctx()}\n${rfShow(bm)}\n${rfShow(plain)}" }
                rfEq(plain.ideal, bm.ideal) { "spec §5 M3: ritmo ideal of the cota livre\n${ctx()}\n${rfShow(bm)}\n${rfShow(plain)}" }
                rfEq(plain.idealByDay, bm.idealByDay) { "D44 M3: idealByDay of the cota livre\n${ctx()}\n${rfShow(bm)}\n${rfShow(plain)}" }
            }
        }
    }

    @Test
    fun `budgetMonth exactly on pace projects the cota and one cent more is Acima do ritmo`() = rfRealTime {
        for (seed in RF_SEEDS) {
            val rnd = Random(seed + 2)
            repeat(RF_PURE_ITERATIONS / 2) { i ->
                yield()
                val g = rfGen(rnd, huge = false, withPrevisto = true)
                val win = rfWindow(g.month, g.startDay)
                val base = g.copy(today = win.start.plus(rnd.nextInt(win.days), DateTimeUnit.DAY), variable = emptyMap())
                val fixedIn = base.fixed.filterKeys { it in win }.values.sum()
                val ctx = { "seed=$seed iteration=$i base=$base" }
                val bm0 = rfPure(ctx) { base.call() }
                val ideal = bm0.ideal.v
                if (ideal == 0L) {
                    // M6, spec §5: sem projeção quando o ritmo ideal de hoje é R$ 0,00.
                    rfEq(0L, bm0.projection.v) { "M6: no projection when today's ideal is 0\n${ctx()}\n${rfShow(bm0)}" }
                    return@repeat
                }
                // Gasto variável exactly the ideal of today, on today.
                val onPace = base.copy(variable = mapOf(base.today to ideal))
                val bm1 = rfPure(ctx) { onPace.call() }
                rfEq(ideal, bm1.ideal.v) { "spec §4: the passos (and so the ideal) don't change with what was spent\n${ctx()}\n${rfShow(bm1)}" }
                // M6: ideal × (freeLimit ÷ ideal) + planned = freeLimit + planned = limit (ideal > 0 ⇒ freeLimit > 0 ⇒ limit > planned).
                rfEq(base.limit, bm1.projection.v) { "M6 spec §5: exactly on pace projects the cota\n${ctx()}\n${rfShow(bm1)}" }
                // remaining = limit − (ideal + fixed) − open = freeLimit − ideal ≥ 0, variable = ideal: No ritmo.
                rfEq(PaceStatus.ON_PACE, bm1.status) { "M3: variável = ideal is No ritmo\n${ctx()}\n${rfShow(bm1)}" }
                // Spec §5, sem tolerância: one cent over the ideal is Acima do ritmo, unless the margem went negative.
                val ahead = base.copy(variable = mapOf(base.today to ideal + 1))
                val bm2 = rfPure(ctx) { ahead.call() }
                val remaining = base.limit - (ideal + 1 + fixedIn) - base.open
                rfEq(remaining, bm2.remaining.v) { "M1: remaining = limit − spent − open\n${ctx()}\n${rfShow(bm2)}" }
                rfEq(if (remaining < 0) PaceStatus.OVERRUN else PaceStatus.AHEAD, bm2.status) {
                    "spec §5: one cent over the ideal is Acima do ritmo (Fora da margem when remaining < 0)\n${ctx()}\n${rfShow(bm2)}"
                }
            }
        }
    }

    @Test
    fun `random previstos lancamentos and estornos keep the margem honesta in the Db`() = rfRealTime {
        for (seed in RF_DB_SEEDS) rfWithDb { db -> rfDbRun(db, seed) }
    }
}
