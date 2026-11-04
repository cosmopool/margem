package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Invariants of the margem honesta (spec §2.13, §3, §4, §5, §7.1; glossary Margem, Cota livre, Gasto
// variável, Previsto; margem-plan M1–M13; architecture D68–D73, D65, D70), checked through the public Db API
// only, after every step of random, fixed-seed sequences of: previstos created ("Salvar previsto", and
// "Repete · Todo mês" on a lançamento manual), edited (value, day, verba), skipped and deleted; occurrences
// lançadas at the same or another value, by a recado (accept) or without one (addManual), dated in the same
// or another budget month than the occurrence; ordinary lançamentos, à vista and parcelados; estornos and
// their undo; edits of lançamentos (a fixed one's date across months); deleted lançamentos; setStep,
// clearStep and setLimit; and `today` moving forward across month turns. The month start day is set once.
//
// The test keeps its own plain ledger, from the spec's definitions only: every lançamento (value, parcelas,
// date, estorno, fixed or not, the occurrence it closed), every previsto (value, day, verba, repetition) with
// its occurrences (open, closed by a lançamento, skipped), the limit history of each verba and the stored
// step edits. After each step, for every verba in every month from 3 before to 2 after today's:
// - spent = Σ parcelas not refunded in the month (§3, §6); fixed = the same over the lançamentos that closed
//   an occurrence (D69); variable = spent − fixed, and variable + fixed = spent (§3, M3);
// - open = Σ previsto value of the open occurrences dated in the month (M1, M2), a skipped one never;
// - remaining (margem) = limit − spent − open (§3, M1); planned = open + fixed (M2);
//   freeLimit (cota livre) = max(0, limit − planned) (§3, M3, M10);
// - status and projection by the M3/M6 rules over the values read; Σ passos = freeLimit (§4);
//   Σ Week.spent = variable (Week KDoc); idealByDay ends at freeLimit (BudgetMonth KDoc);
// - edited passos count only while they fit in the cota livre, and count again when it grows (§4, M5, D70);
//   Week.max and Week.locked by the Week KDoc;
// - every fixed lançamento still tied to its occurrence is dated in the occurrence's budget month (D72);
// - BudgetDetail.plannedLines is exactly the month's occurrences, skipped ones left out (D73).
// Deleting a previsto is also checked to leave every verba's variable (and fixed, and spent) unchanged (D69).
//
// Months after today's may hold occurrences the Db creates only later (D71: up to the later of today + 10
// and the end of today's month); there, open is only checked as at least the occurrences already due.

private val RSV_TODAY0 = LocalDate(2026, 10, 9) // sexta-feira, the book's day
/** Budgets are created "on" this day, so every month a lançamento can touch has a limit row. */
private val RSV_BUDGET_DAY = LocalDate(2026, 7, 1)
/** No lançamento is ever dated before this. */
private val RSV_FIRST_DAY = LocalDate(2026, 8, 1)
/** `today` never passes this. */
private val RSV_LAST_TODAY = LocalDate(2027, 3, 20)
private val RSV_TZ = TimeZone.of("America/Sao_Paulo")
private const val RSV_PKG = "com.nu.production"
private const val RSV_LABEL = "Nubank"
private val RSV_MERCHANTS = listOf("ENEL", "VIVO FIBRA", "SMARTFIT", "PADARIA SAO JOSE", "MERCADO BOM PRECO")
private val RSV_TAGS = listOf("casa", "contas", "lazer", "feira")

private fun LocalDate.rsvDays(n: Int): LocalDate = if (n >= 0) plus(n, DateTimeUnit.DAY) else minus(-n, DateTimeUnit.DAY)

private fun YearMonth.rsvShift(n: Int): YearMonth {
    var m = this
    repeat(abs(n)) { m = if (n > 0) m.plusMonth() else m.minusMonth() }
    return m
}

/** Planned.date KDoc (P8): the day in that calendar month, or the month's last day when it has none. */
private fun rsvOccDate(m: YearMonth, day: Int) = LocalDate(m.year, m.month, minOf(day, m.numberOfDays))

/** spec §3: from day D (or the month's last day) to the eve of day D of the next month. */
private fun rsvWindow(m: YearMonth, start: Int): Pair<LocalDate, LocalDate> =
    rsvOccDate(m, start) to rsvOccDate(m.plusMonth(), start).rsvDays(-1)

/** spec §3: the budget month a date falls in. */
private fun rsvMonthOf(d: LocalDate, start: Int): YearMonth {
    val ym = d.yearMonth
    return if (d >= rsvWindow(ym, start).first) ym else ym.minusMonth()
}

private fun rsvClamp(d: LocalDate, lo: LocalDate, hi: LocalDate) = if (d < lo) lo else if (d > hi) hi else d

/** "1.234,56" */
private fun rsvBrl(c: Long): String {
    val reais = (c / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return "$reais,${(c % 100).toString().padStart(2, '0')}"
}

/** One real SQLite file per test, deleted afterwards with its -wal and -shm. */
private suspend fun withReservaInvariantsDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "reserva-invariants-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path, timeZone = RSV_TZ)
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

/**
 * A Nubank purchase recado (the strict pattern the extraction reads) for [cents] at [merchant], posted on
 * [date] in the morning; [n] makes the key and the minute unique. Returns it as the Pauta shows it.
 */
private suspend fun rsvCapture(db: Db, n: Int, merchant: String, cents: Long, date: LocalDate): NoticeRow {
    val at = date.atStartOfDayIn(RSV_TZ).toEpochMilliseconds() + 8 * 3_600_000L + (n % 600) * 60_000L
    val text = "Compra de R$ ${rsvBrl(cents)} APROVADA em $merchant para o cartão com final 1234."
    val raw = RawNotice(
        packageName = RSV_PKG, appLabel = RSV_LABEL, key = "rsv-$n", postedAt = at, whenMs = at,
        title = RSV_LABEL, text = text, bigText = text,
    )
    assertTrue(capture(db, raw), "capture of recado $n (a fonte, unique key)")
    val row = assertNotNull(db.pending().firstOrNull { it.raw.key == raw.key }, "captured recado $n is pending")
    assertEquals(Cents(cents), row.parsed.cents, "recado $n: value read from \"$text\"")
    assertEquals(merchant, row.parsed.merchant, "recado $n: bank text read from \"$text\"")
    assertEquals(date, row.parsed.date, "recado $n: date from its time")
    return row
}

// ---- the ledger ----

/** One previsto (the rule) and its occurrences by calendar month (D59). */
private class RsvPlan(
    val label: String,
    val id: PlannedId,
    val name: String,
    var budget: BudgetId,
    val tags: List<String>,
    var cents: Long,
    var day: Int,
    val repeats: Repeats,
    /** Calendar month of the first occurrence. */
    val first: YearMonth,
) {
    /** Only the occurrences known to exist: created by a fill (D71) that reached their date. */
    val occs = LinkedHashMap<YearMonth, RsvOcc>()

    override fun toString() = "previsto ${id.v} ($label, ${cents}c, day $day, $repeats, budget ${budget.v})"
}

private class RsvOcc(val plan: RsvPlan, val cal: YearMonth) {
    var id = OccurrenceId(0)
    var skipped = false
    /** The lançamento that closed it; null while open (architecture §5). */
    var tx: RsvTx? = null
    /** Planned.date KDoc: the previsto's day in its calendar month. Closed ones never see the day change here. */
    val date: LocalDate get() = rsvOccDate(cal, plan.day)

    override fun toString() = "${plan.label} $cal (occurrence ${id.v}, $date)"
}

private class RsvTx(
    val id: TransactionId,
    val notice: NoticeId,
    var budget: BudgetId,
    val tags: List<String>,
    var total: Long,
    var n: Int,
    var date: LocalDate,
    var time: LocalTime,
    /** Closed an occurrence (D69): never changes. */
    val fixed: Boolean,
    /** The occurrence it closed, while its previsto exists (Db.deletePlanned KDoc: occurrence 0 after). */
    var occ: RsvOcc?,
    val merchant: String,
    val caption: String,
) {
    var estorno = NoticeId(0)
    /** D65: the previsto its "Repete" created. */
    var started: RsvPlan? = null

    override fun toString() =
        "lançamento ${id.v} (budget ${budget.v}, ${total}c in $n, $date $time${if (fixed) ", fixed" else ""}" +
            "${if (estorno.v != 0L) ", estornado" else ""}${occ?.let { ", closes $it" } ?: ""})"
}

private class ReservaInvariantRun(val db: Db, val seed: Int, val startDay: Int) {
    val rnd = Random(seed)
    val log = ArrayList<String>()
    var step = 0
    var action = "setup"
    var today = RSV_TODAY0
    var captures = 0
    var labels = 0

    /** In manual order (creation order): budgetsOverview(…, MANUAL) lists them so. */
    val budgets = LinkedHashMap<BudgetId, String>()
    /** Each verba's limit history: (from month, limit). setLimit sets today's month onward (§3). */
    val limits = HashMap<BudgetId, MutableList<Pair<YearMonth, Long>>>()
    val plans = ArrayList<RsvPlan>()
    val txs = LinkedHashMap<TransactionId, RsvTx>()
    /** Stored step edits by verba and week start (spec §4): kept even while they don't count (D70). */
    val edits = HashMap<BudgetId, MutableMap<LocalDate, Long>>()

    fun log(s: String) {
        log.add("#$step [$today] $s")
    }

    fun cur(): YearMonth = rsvMonthOf(today, startDay)
    fun window(m: YearMonth) = rsvWindow(m, startDay)
    fun monthOf(d: LocalDate) = rsvMonthOf(d, startDay)

    /** D71: occurrences exist up to the later of today + 10 and the end of today's budget month. */
    fun horizon(): LocalDate = maxOf(today.rsvDays(10), window(cur()).second)

    fun checkedMonths(): List<YearMonth> {
        val first = maxOf(monthOf(RSV_FIRST_DAY), cur().rsvShift(-3))
        val out = ArrayList<YearMonth>()
        var m = first
        while (m <= cur().rsvShift(2)) {
            out.add(m)
            m = m.plusMonth()
        }
        return out
    }

    fun limitOf(b: BudgetId, m: YearMonth): Long =
        limits.getValue(b).lastOrNull { it.first <= m }?.second ?: fail("model: budget ${b.v} has no limit in $m")

    fun randomTime() = LocalTime(7 + rnd.nextInt(15), rnd.nextInt(60))
    fun randomTags(): List<String> = List(1 + rnd.nextInt(2)) { RSV_TAGS.random(rnd) }.distinct().sorted()

    /** spec §6: parcela k in the k-th budget month from the purchase's; total ÷ n rounded down, the last takes the rest. */
    fun installments(t: RsvTx): List<Pair<YearMonth, Long>> {
        val m0 = monthOf(t.date)
        return (1..t.n).map { k -> m0.rsvShift(k - 1) to (t.total / t.n + if (k == t.n) t.total % t.n else 0L) }
    }

    /** §3 Gasto do mês (and its fixed part, D69): parcelas in the month of the lançamentos not refunded. */
    fun ledger(b: BudgetId, m: YearMonth): Pair<Long, Long> {
        var spent = 0L
        var fixed = 0L
        for (t in txs.values) {
            if (t.budget != b || t.estorno.v != 0L) continue
            for ((im, c) in installments(t)) {
                if (im != m) continue
                spent += c
                if (t.fixed) fixed += c
            }
        }
        return spent to fixed
    }

    fun inWindow(d: LocalDate, m: YearMonth): Boolean {
        val (from, to) = window(m)
        return d in from..to
    }

    /** M1/M2: Σ previsto value of the open occurrences (created, not skipped, no lançamento) dated in the month. */
    fun openOf(b: BudgetId, m: YearMonth): Long =
        plans.filter { it.budget == b }.flatMap { it.occs.values }
            .filter { !it.skipped && it.tx == null && inWindow(it.date, m) }.sumOf { it.plan.cents }

    /** Every occurrence that could be dated in [m] is known to exist (or known not to be a month of its previsto). */
    fun exact(m: YearMonth): Boolean {
        val (from, to) = window(m)
        for (p in plans) {
            for (cal in listOf(from.yearMonth, to.yearMonth).distinct()) {
                if (cal < p.first) continue
                if (p.repeats == Repeats.ONCE && cal != p.first) continue
                val d = rsvOccDate(cal, p.day)
                if (d < from || d > to) continue
                if (cal !in p.occs) return false
            }
        }
        return true
    }

    /** What the fills (planned, budgetsOverview, budgetDetail with today; D71) have created by now. */
    fun markCreated() {
        val h = horizon()
        for (p in plans) {
            var cal = p.first
            while (rsvOccDate(cal, p.day) <= h) {
                p.occs.getOrPut(cal) { RsvOcc(p, cal) }
                if (p.repeats == Repeats.ONCE) break
                cal = cal.plusMonth()
            }
        }
    }

    fun storedEdits(b: BudgetId, m: YearMonth): Map<LocalDate, Long> =
        (edits[b] ?: emptyMap()).filterKeys { inWindow(it, m) }

    /** D65 (TransactionDetail.planned): the previsto its "Repete" created, else the one whose occurrence it closed. */
    fun repeatsOf(t: RsvTx): Repeats =
        t.occ?.plan?.repeats ?: t.started?.takeIf { it in plans }?.repeats ?: Repeats.ONCE

    // ---- running ----

    suspend fun run(steps: Int) {
        try {
            setup()
            action = "check after setup"
            check()
            for (i in 1..steps) {
                step = i
                act()
                action = "$action, then the check"
                check()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AssertionError(
                "seed=$seed startDay=$startDay step=$step action=$action today=$today: ${e.message}\n" +
                    "--- actions so far ---\n${log.takeLast(60).joinToString("\n")}",
                e,
            )
        }
    }

    suspend fun setup() {
        log("setup startDay=$startDay")
        assertTrue(db.addSource(RSV_PKG, RSV_LABEL, 0L), "addSource")
        assertTrue(db.setMonthStartDay(startDay, today), "setMonthStartDay($startDay)")
        for ((name, limit) in listOf("Casa" to 300_000L, "Mercado" to 120_000L, "Lazer" to 60_000L)) {
            val id = db.createBudget(name, Cents(limit), RSV_BUDGET_DAY)
            assertTrue(id.v != 0L, "createBudget $name")
            budgets[id] = name
            limits[id] = mutableListOf(monthOf(RSV_BUDGET_DAY) to limit)
            log("createBudget $name ${id.v} limit $limit")
        }
        db.ensureMonths(cur().rsvShift(2))
        repeat(5) { ordinaryAction(monthly = false) }
        // "Todo mês" on an old lançamento (spec §2.13): every month since the next one, the past ones late; day 31 (P8).
        monthlyFromLancamento(budgets.keys.first(), 8_990L, LocalDate(2026, 8, 31))
        futureAction(budgets.keys.first(), 200_000L, today.rsvDays(3), Repeats.MONTHLY)
        futureAction(budgets.keys.toList()[1], 15_000L, today.rsvDays(20), Repeats.ONCE)
        futureAction(budgets.keys.toList()[2], 9_990L, today.rsvDays(7), Repeats.MONTHLY)
    }

    suspend fun act() {
        val r = rnd.nextInt(100)
        when {
            r < 13 -> { action = "ordinary lançamento"; ordinaryAction(monthly = rnd.nextInt(7) == 0) }
            r < 20 -> { action = "Salvar previsto"; futureAction() }
            r < 36 -> { action = "lançar an occurrence"; closeAction() }
            r < 39 -> { action = "lançar a closed or skipped occurrence"; refusedCloseAction() }
            r < 45 -> { action = "Pular este mês"; skipAction() }
            r < 49 -> { action = "Apagar previsto"; deletePlannedAction() }
            r < 56 -> { action = "deleteTransaction"; deleteTxAction() }
            r < 64 -> { action = "edit"; editAction() }
            r < 70 -> { action = "Editar previsto"; editPlannedAction() }
            r < 76 -> { action = "estorno or its undo"; estornoAction() }
            r < 84 -> { action = "setStep or clearStep"; stepAction() }
            r < 89 -> { action = "setLimit"; limitAction() }
            else -> { action = "advance today"; advanceAction() }
        }
    }

    // ---- the check ----

    suspend fun check() {
        val cur = cur()
        db.ensureMonths(cur.rsvShift(2))
        val reads = LinkedHashMap<Pair<BudgetId, YearMonth>, BudgetMonth>()
        for (m in checkedMonths()) {
            val list = db.budgetsOverview(m, today, BudgetOrder.MANUAL)
            assertEquals(budgets.keys.toList(), list.map { it.id }, "budgetsOverview($m): every verba, in the manual order")
            for (s in list) reads[s.id to m] = s.month
        }
        markCreated()
        for ((key, bm) in reads) checkMonth(key.first, key.second, bm)
        checkRows()
        for (m in listOf(cur.minusMonth(), cur)) checkPlannedLines(m, reads)
    }

    fun checkMonth(b: BudgetId, m: YearMonth, bm: BudgetMonth) {
        val where = "budget ${b.v} (${budgets[b]}) month $m"
        val (from, to) = window(m)
        assertEquals(m, bm.month, "$where: month")
        assertEquals(from, bm.start, "$where: start (spec §3)")
        assertEquals(to, bm.end, "$where: end (spec §3)")
        val limit = limitOf(b, m)
        assertEquals(limit, bm.limit.v, "$where: the month's own limit (spec §3 Mudar a cota)")

        val (spent, fixed) = ledger(b, m)
        assertEquals(spent, bm.spent.v, "$where: spent = Σ parcelas not refunded in the month (spec §3, §6)")
        assertEquals(fixed, bm.fixed.v, "$where: fixed = Σ parcelas of the lançamentos of previstos, not refunded (D69, BudgetMonth.fixed)")
        assertEquals(spent - fixed, bm.variable.v, "$where: variable = spent − fixed (spec §3 Gasto variável)")
        assertEquals(bm.spent.v, bm.variable.v + bm.fixed.v, "$where: variable + fixed = spent")

        val openModel = openOf(b, m)
        val exact = exact(m)
        if (exact) {
            assertEquals(openModel, bm.open.v, "$where: open = Σ previsto value of the open occurrences dated in the month, skipped ones never (M1, M2)")
        } else {
            assertTrue(
                bm.open.v >= openModel,
                "$where: open ${bm.open.v} is at least the open occurrences already due ($openModel, M1, D71)",
            )
        }
        val open = bm.open.v
        assertEquals(limit - spent - open, bm.remaining.v, "$where: margem = cota − gasto − previstos em aberto (spec §3, M1)")
        assertEquals(open + fixed, bm.planned.v, "$where: previstos do mês = open + fixed (M2)")
        val free = maxOf(0L, limit - open - fixed)
        assertEquals(free, bm.freeLimit.v, "$where: cota livre = max(0, cota − previstos do mês) (spec §3, M3, M10)")

        // spec §5 / M3 states, over the values read.
        val status = when {
            bm.remaining.v < 0 -> PaceStatus.OVERRUN
            bm.variable > bm.ideal -> PaceStatus.AHEAD
            else -> PaceStatus.ON_PACE
        }
        assertEquals(status, bm.status, "$where: status (M3: margem < 0 Fora da margem; variable > ideal Acima do ritmo) ideal=${bm.ideal.v}")
        // M6: variable × freeLimit ÷ ideal (rounded down) + previstos do mês; none when ideal is 0.
        val projection = if (bm.ideal.v == 0L) 0L else bm.variable.v * bm.freeLimit.v / bm.ideal.v + bm.planned.v
        assertEquals(projection, bm.projection.v, "$where: projection (M6) ideal=${bm.ideal.v}")

        // Passos (spec §4): they share the cota livre.
        assertTrue(bm.weeks.isNotEmpty(), "$where: weeks")
        assertTrue(bm.weeks.all { it.step.v >= 0 }, "$where: no step below 0 (spec §4): ${bm.weeks}")
        assertEquals(free, bm.weeks.sumOf { it.step.v }, "$where: Σ passos = cota livre (spec §4)")
        assertEquals(bm.variable.v, bm.weeks.sumOf { it.spent.v }, "$where: Σ Week.spent = gasto variável (Week.spent KDoc)")

        // idealByDay (BudgetMonth KDoc): one per day, never decreasing, the last day = freeLimit.
        val days = from.daysUntil(to) + 1
        assertEquals(days, bm.idealByDay.size, "$where: idealByDay has one value per day")
        assertEquals(free, bm.idealByDay.last().v, "$where: idealByDay ends at the cota livre (D68)")
        assertTrue(bm.idealByDay.zipWithNext().all { (a, b2) -> a.v <= b2.v }, "$where: idealByDay never decreases")
        // spec §5: ritmo ideal of today = passos of the weeks closed + today's week by days elapsed.
        val ideal = when {
            today < from -> 0L // no week has started
            today > to -> free // every week closed: Σ passos
            else -> bm.idealByDay[from.daysUntil(today)].v
        }
        assertEquals(ideal, bm.ideal.v, "$where: ideal of today (spec §5, idealByDay KDoc)")

        // D70 / M5: stored edits count only while they fit in the cota livre (and leave a week automatic).
        val stored = storedEdits(b, m)
        val counting = stored.isNotEmpty() && stored.values.sum() <= free && stored.size < bm.weeks.size
        for (w in bm.weeks) {
            val edited = counting && w.start in stored
            assertEquals(
                edited, w.edited,
                "$where week ${w.start}: stored edits $stored, cota livre $free → ${if (counting) "they count" else "none counts"} (spec §4, D70)",
            )
            if (edited) assertEquals(stored.getValue(w.start), w.step.v, "$where week ${w.start}: the edited passo")
        }
        val editedSum = if (counting) stored.values.sum() else 0L
        val auto = bm.weeks.count { !it.edited }
        for (w in bm.weeks) {
            val locked = !w.edited && auto == 1
            assertEquals(locked, w.locked, "$where week ${w.start}: locked iff the only automatic week (Week.locked KDoc)")
            val max = if (locked) 0L else free - editedSum + (if (w.edited) w.step.v else 0L)
            assertEquals(max, w.max.v, "$where week ${w.start}: max = cota livre − Σ other edited passos (Week.max KDoc, D70)")
        }
    }

    suspend fun checkRows() {
        val rows = db.transactions().associateBy { it.id }
        assertEquals(txs.keys, rows.keys, "transactions(): exactly the lançamentos of the ledger")
        for (t in txs.values) {
            val r = rows.getValue(t.id)
            val where = "$t"
            assertEquals(t.fixed, r.fixed, "$where: fixed is written when it closes an occurrence and never changes (D69)")
            assertEquals(t.budget, r.budget, "$where: budget")
            assertEquals(t.total, r.total.v, "$where: total")
            assertEquals(t.n, r.installments, "$where: installments")
            assertEquals(t.date, r.date, "$where: date (D72 for a lançamento of a previsto)")
            assertEquals(t.time, r.time, "$where: time")
            assertEquals(t.estorno, r.estorno, "$where: estorno")
            val o = t.occ
            if (t.fixed && o != null) {
                assertEquals(
                    monthOf(o.date), monthOf(r.date),
                    "$where: a lançamento of a previsto is dated in its occurrence's budget month (spec §2.13, M8, D72)",
                )
            }
        }
    }

    suspend fun checkPlannedLines(m: YearMonth, reads: Map<Pair<BudgetId, YearMonth>, BudgetMonth>) {
        if (!exact(m)) return
        for (b in budgets.keys) {
            val where = "budget ${b.v} (${budgets[b]}) plannedLines of $m"
            val detail = db.budgetDetail(b, m, today)
            assertEquals(b, detail.id, "$where: the detail's budget")
            val lines = detail.plannedLines
            val expected = plans.filter { it.budget == b }.flatMap { it.occs.values }
                .filter { !it.skipped && inWindow(it.date, m) }
            val seen = HashSet<RsvOcc>()
            for (line in lines) {
                val p = plans.firstOrNull { it.id == line.planned.planned }
                    ?: fail("$where: a line of a previsto the ledger doesn't have alive: $line")
                val o = p.occs[line.planned.date.yearMonth]
                    ?: fail("$where: a line of $p for ${line.planned.date.yearMonth}, an occurrence the ledger doesn't have: $line")
                if (o.id.v == 0L) o.id = line.planned.occurrence
                assertEquals(o.id, line.planned.occurrence, "$where: $o keeps its occurrence id")
                assertTrue(seen.add(o), "$where: one line per occurrence ($o twice)")
                assertEquals(o.date, line.planned.date, "$where: $o date")
                assertEquals(b, line.planned.budget, "$where: $o budget")
                assertFalse(line.planned.noticeInPauta, "$where: $o noticeInPauta is false (PlannedLine KDoc)")
                val t = o.tx
                if (t == null) {
                    assertTrue(line.open, "$where: $o is open")
                    assertEquals(NO_DATE, line.launched, "$where: $o open → launched NO_DATE")
                    assertEquals(p.cents, line.cents.v, "$where: $o open → the previsto's value")
                    assertEquals(p.cents, line.planned.cents.v, "$where: $o open → Planned.cents is the previsto's value")
                    assertEquals(o.date < today, line.planned.late, "$where: $o late against today")
                } else {
                    assertFalse(line.open, "$where: $o is lançada by $t")
                    // D72: the lançamento is in the occurrence's month, so its parcela there is the first: the purchase date.
                    assertEquals(t.date, line.launched, "$where: $o launched = the date of its parcela in the month")
                    val first = if (t.estorno.v != 0L) 0L else t.total / t.n + (if (t.n == 1) t.total % t.n else 0L)
                    assertEquals(first, line.cents.v, "$where: $o lançada → what its lançamento adds to fixed in the month (PlannedLine.cents KDoc)")
                    assertFalse(line.planned.late, "$where: $o lançada → late false")
                }
            }
            assertEquals(expected.toSet(), seen, "$where: exactly the occurrences dated in the month, skipped ones left out (D73)")
            val sorted = lines.sortedWith(compareBy<PlannedLine>({ it.planned.date }, { it.planned.occurrence.v }))
            assertEquals(sorted, lines, "$where: by date, then occurrence id")
            assertEquals(
                detail.month.open.v, lines.filter { it.open }.sumOf { it.cents.v },
                "$where: Σ open lines = month.open (BudgetDetail.plannedLines KDoc)",
            )
            reads[b to m]?.let { assertEquals(it.open, detail.month.open, "$where: the detail's open = the overview's") }
        }
    }

    // ---- open occurrences, as planned(today) lists them ----

    suspend fun openRows(): List<Pair<Planned, RsvOcc>> {
        val rows = db.planned(today)
        markCreated()
        return rows.map { r ->
            val p = plans.firstOrNull { it.id == r.planned } ?: fail("planned(today) lists a previsto the ledger doesn't have alive: $r")
            val o = p.occs[r.date.yearMonth] ?: fail("planned(today) lists $p in ${r.date.yearMonth}, an occurrence not yet due: $r")
            if (o.id.v == 0L) o.id = r.occurrence
            assertEquals(o.id, r.occurrence, "$o keeps its occurrence id")
            assertTrue(!o.skipped && o.tx == null, "planned(today) lists only open occurrences; $o is ${if (o.skipped) "skipped" else "closed by ${o.tx}"}")
            assertEquals(Cents(p.cents), r.cents, "$o: the previsto's value")
            r to o
        }
    }

    suspend fun overviewOf(b: BudgetId, m: YearMonth): BudgetMonth =
        db.budgetsOverview(m, today, BudgetOrder.MANUAL).firstOrNull { it.id == b }?.month ?: fail("budgetsOverview($m) lacks budget ${b.v}")

    // ---- actions ----

    suspend fun ordinaryAction(monthly: Boolean) {
        val b = budgets.keys.random(rnd)
        val total = 500L + rnd.nextLong(40_000)
        val n = if (monthly || rnd.nextInt(10) < 7) 1 else 2 + rnd.nextInt(3)
        val date = rsvClamp(today.rsvDays(-rnd.nextInt(50)), RSV_FIRST_DAY, today)
        if (monthly) {
            monthlyFromLancamento(b, total, date)
            return
        }
        val f = TransactionForm(
            budget = b, tags = randomTags(), total = Cents(total), installments = n, merchant = RSV_MERCHANTS.random(rnd),
            date = date, time = randomTime(),
        )
        val saved = db.addManual(f, today)
        log("ordinary ${f.budget.v} ${f.total.v} in ${f.installments} on ${f.date} -> ${saved.transaction.v}")
        assertTrue(saved.transaction.v != 0L, "an ordinary lançamento is accepted")
        assertEquals(PlannedId(0), saved.planned, "an ordinary lançamento makes no previsto")
        txs[saved.transaction] = RsvTx(saved.transaction, NoticeId(0), b, f.tags, total, n, date, f.time, false, null, f.merchant, "")
    }

    /** "Repete · Todo mês" on a lançamento manual (D64): the lançamento (ordinary) and a previsto from the next month. */
    suspend fun monthlyFromLancamento(b: BudgetId, total: Long, date: LocalDate) {
        labels++
        val name = "Assinatura $labels"
        val f = TransactionForm(
            budget = b, tags = randomTags(), total = Cents(total), merchant = RSV_MERCHANTS.random(rnd), date = date,
            time = randomTime(), caption = name, repeats = Repeats.MONTHLY,
        )
        val saved = db.addManual(f, today)
        log("Todo mês lançamento ${b.v} $total on $date -> ${saved.transaction.v} + previsto ${saved.planned.v}")
        assertTrue(saved.transaction.v != 0L && saved.planned.v != 0L, "\"Todo mês\" from $date writes the lançamento and the previsto (Saved KDoc)")
        val t = RsvTx(saved.transaction, NoticeId(0), b, f.tags, total, 1, date, f.time, false, null, f.merchant, name)
        val p = RsvPlan(name, saved.planned, name, b, f.tags, total, date.day, Repeats.MONTHLY, date.yearMonth.plusMonth())
        t.started = p
        txs[t.id] = t
        plans.add(p)
    }

    /** "Salvar previsto" (D64): a date after today writes only the previsto. */
    suspend fun futureAction(
        b: BudgetId = budgets.keys.random(rnd),
        cents: Long = 5_000L + rnd.nextLong(150_000),
        date: LocalDate = today.rsvDays(1 + rnd.nextInt(60)),
        repeats: Repeats = if (rnd.nextBoolean()) Repeats.MONTHLY else Repeats.ONCE,
    ) {
        labels++
        val name = "Previsto $labels"
        val f = TransactionForm(budget = b, tags = randomTags(), total = Cents(cents), date = date, caption = name, repeats = repeats)
        val saved = db.addManual(f, today)
        log("Salvar previsto ${b.v} $cents on $date $repeats -> ${saved.planned.v}")
        assertEquals(TransactionId(0), saved.transaction, "Salvar previsto writes no lançamento (Db.addManual KDoc)")
        assertTrue(saved.planned.v != 0L, "Salvar previsto writes the previsto")
        plans.add(RsvPlan(name, saved.planned, name, b, f.tags, cents, date.day, repeats, date.yearMonth))
    }

    /** Lançar an open occurrence, by a recado or without one, at its value or another, in its month or another (M4, M8, D69, D72). */
    suspend fun closeAction() {
        val rows = openRows()
        if (rows.isEmpty()) {
            futureAction(date = today.rsvDays(1 + rnd.nextInt(9)))
            return
        }
        val (_, o) = rows.random(rnd)
        val p = o.plan
        val total = if (rnd.nextBoolean()) p.cents else maxOf(1L, p.cents * (60 + rnd.nextInt(80)) / 100)
        val n = if (rnd.nextInt(5) == 0) 2 + rnd.nextInt(2) else 1
        val date = rsvClamp(o.date.rsvDays(rnd.nextInt(-20, 21)), RSV_FIRST_DAY, today)
        val id: TransactionId
        val notice: NoticeId
        val time: LocalTime
        val merchant: String
        if (rnd.nextBoolean()) {
            captures++
            val r = rsvCapture(db, captures, RSV_MERCHANTS.random(rnd), total, date)
            val f = TransactionForm(
                budget = p.budget, tags = p.tags, total = r.parsed.cents, installments = n, merchant = r.parsed.merchant,
                date = r.parsed.date, time = r.parsed.time, caption = p.name, occurrence = o.id,
            )
            id = db.accept(r.id, f)
            log("Lançar $o by recado ${r.id.v} ${f.total.v} in $n on ${f.date} ${f.time} -> ${id.v}")
            assertTrue(id.v != 0L, "lançar an open occurrence by a recado is accepted (Db.accept KDoc)")
            notice = r.id
            time = r.parsed.time
            merchant = r.parsed.merchant
        } else {
            time = randomTime()
            val f = TransactionForm(
                budget = p.budget, tags = p.tags, total = Cents(total), installments = n, date = date, time = time,
                caption = p.name, occurrence = o.id,
            )
            val saved = db.addManual(f, today)
            log("Lançar $o without recado $total in $n on $date $time -> ${saved.transaction.v}")
            assertTrue(saved.transaction.v != 0L, "lançar an open occurrence without recado is accepted (Db.addManual KDoc)")
            assertEquals(PlannedId(0), saved.planned, "lançar an occurrence makes no previsto")
            id = saved.transaction
            notice = NoticeId(0)
            merchant = ""
        }
        // D72 / M8: in the occurrence's budget month the date stays; in another, it becomes the occurrence's date, time kept.
        val expected = if (monthOf(date) == monthOf(o.date)) date else o.date
        val row = db.transaction(id).row
        assertTrue(row.fixed, "a lançamento that closes an occurrence is fixed (D69)")
        assertEquals(expected, row.date, "D72: lançado on $date for the occurrence of ${o.date}")
        assertEquals(time, row.time, "D72: the time it had")
        assertFalse(row.corrected, "D72: the move is never an emenda (no ‸ emendado)")
        val t = RsvTx(id, notice, p.budget, p.tags, total, n, expected, time, true, o, merchant, p.name)
        txs[id] = t
        o.tx = t
    }

    /** TransactionForm.occurrence KDoc: refused when the occurrence is not open (skipped, or closed by another lançamento). */
    suspend fun refusedCloseAction() {
        val shut = plans.flatMap { it.occs.values }.filter { it.id.v != 0L && (it.skipped || it.tx != null) }
        if (shut.isEmpty()) {
            closeAction()
            return
        }
        val o = shut.random(rnd)
        val f = TransactionForm(
            budget = o.plan.budget, tags = o.plan.tags, total = Cents(o.plan.cents), date = today, time = randomTime(),
            caption = o.plan.name, occurrence = o.id,
        )
        val saved = db.addManual(f, today)
        log("Lançar the ${if (o.skipped) "skipped" else "closed"} $o -> $saved")
        assertEquals(Saved(), saved, "lançar a ${if (o.skipped) "skipped" else "closed"} occurrence is refused, nothing written")
    }

    /** Pular este mês: a skipped occurrence never reserves (M2, Db.skipOccurrence KDoc). */
    suspend fun skipAction() {
        val rows = openRows()
        if (rows.isEmpty()) {
            futureAction()
            return
        }
        val (_, o) = rows.random(rnd)
        val m = monthOf(o.date)
        val b = o.plan.budget
        val before = overviewOf(b, m)
        assertTrue(db.skipOccurrence(o.id), "skip the open $o")
        o.skipped = true
        log("skip $o")
        val after = overviewOf(b, m)
        markCreated()
        if (exact(m)) {
            assertEquals(
                before.open.v - o.plan.cents, after.open.v,
                "skipping $o stops its reserve in $m (M2: previstos do mês menos as puladas)",
            )
        }
        assertEquals(before.variable, after.variable, "skipping $o doesn't touch the gasto variável")
        assertFalse(db.skipOccurrence(o.id), "skipping $o again is refused (not open)")
    }

    /** Apagar previsto (Db.deletePlanned KDoc, D69): its lançamentos stay fixed; no verba's variable changes. */
    suspend fun deletePlannedAction() {
        if (plans.isEmpty()) {
            futureAction()
            return
        }
        val p = plans.random(rnd)
        val months = checkedMonths()
        suspend fun snap(): Map<Pair<BudgetId, YearMonth>, Triple<Long, Long, Long>> {
            val out = HashMap<Pair<BudgetId, YearMonth>, Triple<Long, Long, Long>>()
            for (m in months) for (s in db.budgetsOverview(m, today, BudgetOrder.MANUAL)) {
                out[s.id to m] = Triple(s.month.variable.v, s.month.fixed.v, s.month.spent.v)
            }
            return out
        }
        val before = snap()
        db.deletePlanned(p.id)
        log("deletePlanned $p")
        plans.remove(p)
        for (t in txs.values) {
            if (t.occ?.plan === p) t.occ = null
            if (t.started === p) t.started = null
        }
        val after = snap()
        for ((key, v) in before) {
            val w = after[key] ?: fail("budget ${key.first.v} month ${key.second} vanished after deleting $p")
            assertEquals(v.first, w.first, "deleting $p never changes the gasto variável of budget ${key.first.v} in ${key.second} (D69)")
            assertEquals(v.second, w.second, "deleting $p keeps its lançamentos fixed: budget ${key.first.v} in ${key.second} (D69)")
            assertEquals(v.third, w.third, "deleting $p keeps every lançamento counting: budget ${key.first.v} in ${key.second}")
        }
    }

    /** Db.deleteTransaction KDoc: a lançamento of a previsto reopens its occurrence, which reserves again (S1, M1); D65. */
    suspend fun deleteTxAction() {
        if (txs.isEmpty()) {
            ordinaryAction(monthly = false)
            return
        }
        val t = txs.values.random(rnd)
        db.deleteTransaction(t.id)
        log("deleteTransaction $t")
        txs.remove(t.id)
        t.occ?.let { o -> if (o.plan in plans) o.tx = null }
        // D65: the previsto its "Repete" made goes with it while none of its occurrences has a lançamento.
        t.started?.let { s ->
            if (s in plans && s.occs.values.none { it.tx != null }) {
                plans.remove(s)
                log("  D65: ${s} goes with it")
            }
        }
    }

    /** Edit of a lançamento (Db.edit KDoc): fixed never changes; with its occurrence, D72 keeps it in that month. */
    suspend fun editAction() {
        if (txs.isEmpty()) {
            ordinaryAction(monthly = false)
            return
        }
        val fixedOnes = txs.values.filter { it.fixed }
        val t = if (fixedOnes.isNotEmpty() && rnd.nextInt(10) < 6) fixedOnes.random(rnd) else txs.values.random(rnd)
        val o = t.occ
        val date = if (o != null) {
            rsvClamp(o.date.rsvDays(rnd.nextInt(-35, 36)), RSV_FIRST_DAY, RSV_LAST_TODAY)
        } else {
            rsvClamp(today.rsvDays(-rnd.nextInt(50)), RSV_FIRST_DAY, today)
        }
        val time = randomTime()
        val total = if (rnd.nextInt(10) < 6) t.total else 500L + rnd.nextLong(160_000)
        val n = if (rnd.nextInt(10) < 7) 1 else 2 + rnd.nextInt(2)
        // A lançamento still tied to its occurrence keeps the previsto's verba (see the ambiguity in the report).
        val budget = if (o != null) t.budget else budgets.keys.random(rnd)
        val repeats = repeatsOf(t)
        val detail = db.transaction(t.id)
        assertEquals(repeats, detail.repeats, "$t: TransactionDetail.repeats is its previsto's (D65)")
        val f = TransactionForm(
            budget = budget, tags = t.tags, total = Cents(total), installments = n, merchant = t.merchant, date = date,
            time = time, caption = t.caption, repeats = repeats,
        )
        assertTrue(db.edit(t.id, f), "edit of $t is accepted")
        val expected = if (o != null && monthOf(date) != monthOf(o.date)) o.date else date
        log("edit $t to budget ${budget.v} $total in $n on $date $time -> date $expected")
        val row = db.transaction(t.id).row
        assertEquals(t.fixed, row.fixed, "edit never changes fixed (D69)")
        assertEquals(expected, row.date, "D72: edited to $date${o?.let { " with its occurrence of ${it.date}" } ?: " without occurrence"}")
        assertEquals(time, row.time, "D72: the new time")
        t.budget = budget
        t.total = total
        t.n = n
        t.date = expected
        t.time = time
    }

    /** Editar previsto (D64): value, and day and verba while nothing of it is lançado; the open occurrences follow. */
    suspend fun editPlannedAction() {
        val rows = openRows()
        if (rows.isEmpty()) {
            futureAction()
            return
        }
        val (_, o) = rows.random(rnd)
        val p = o.plan
        val launched = p.occs.values.any { it.tx != null }
        val cents = if (rnd.nextInt(10) < 6) 5_000L + rnd.nextLong(150_000) else p.cents
        val newDay = if (!launched && rnd.nextBoolean()) 1 + rnd.nextInt(28) else 0
        val date = if (newDay != 0) LocalDate(o.cal.year, o.cal.month, newDay) else o.date
        val budget = if (!launched && rnd.nextInt(10) < 3) budgets.keys.random(rnd) else p.budget
        val f = TransactionForm(
            budget = budget, tags = p.tags, total = Cents(cents), date = date, caption = p.name, repeats = p.repeats,
            occurrence = o.id, planned = p.id,
        )
        val saved = db.addManual(f, today)
        log("Editar $p from $o: ${cents}c, date $date, budget ${budget.v} -> $saved")
        assertEquals(Saved(planned = p.id), saved, "Editar previsto updates it (Db.addManual KDoc)")
        p.cents = cents
        if (newDay != 0) p.day = newDay
        p.budget = budget
    }

    /** Registrar estorno on a lançamento, or Desfazer estorno (spec §6): a refunded lançamento doesn't count, fixed ones too. */
    suspend fun estornoAction() {
        val refunded = txs.values.filter { it.estorno.v != 0L }
        if (refunded.isNotEmpty() && rnd.nextInt(3) == 0) {
            val t = refunded.random(rnd)
            assertTrue(db.undoRefund(t.estorno), "undo the estorno of $t")
            log("undoRefund $t")
            t.estorno = NoticeId(0)
            return
        }
        val open = txs.values.filter { it.estorno.v == 0L }
        if (open.isEmpty()) {
            ordinaryAction(monthly = false)
            return
        }
        val fixedOnes = open.filter { it.fixed }
        val t = if (fixedOnes.isNotEmpty() && rnd.nextBoolean()) fixedOnes.random(rnd) else open.random(rnd)
        captures++
        val e = rsvCapture(db, captures, "LOJA ESTACAO", t.total, today)
        assertTrue(db.registerRefund(e.id, t.id), "register the estorno ${e.id.v} on $t")
        log("registerRefund ${e.id.v} on $t")
        t.estorno = e.id
    }

    /** setStep / clearStep on today's month (spec §4, Week KDoc, D70). */
    suspend fun stepAction() {
        val b = budgets.keys.random(rnd)
        val cur = cur()
        val bm = overviewOf(b, cur)
        val stored = edits.getOrPut(b) { HashMap() }
        val here = storedEdits(b, cur)
        val counting = bm.weeks.any { it.edited }
        if (here.isNotEmpty() && (!counting || rnd.nextInt(10) < 3)) {
            val ws = here.keys.random(rnd)
            assertTrue(db.clearStep(b, cur, ws), "clearStep of the stored edit ${b.v} $ws")
            stored.remove(ws)
            log("clearStep ${b.v} $ws")
            return
        }
        val w = bm.weeks.random(rnd)
        if (w.start !in here && rnd.nextInt(10) == 0) {
            assertFalse(db.clearStep(b, cur, w.start), "clearStep of a week without edit is refused")
            log("clearStep ${b.v} ${w.start} refused")
            return
        }
        if (w.locked) {
            assertFalse(db.setStep(b, cur, w.start, Cents(rnd.nextLong(1_000))), "setStep on the locked week is refused (spec §4)")
            log("setStep ${b.v} ${w.start} locked, refused")
            return
        }
        if (rnd.nextInt(4) == 0) {
            assertFalse(db.setStep(b, cur, w.start, Cents(w.max.v + 1)), "setStep at max + 1 = ${w.max.v + 1} is refused (Week.max KDoc)")
            log("setStep ${b.v} ${w.start} ${w.max.v + 1} refused")
            return
        }
        val v = if (w.max.v == 0L) 0L else rnd.nextLong(w.max.v + 1)
        assertTrue(db.setStep(b, cur, w.start, Cents(v)), "setStep ${w.start} = $v within 0..${w.max.v} is accepted (Week.max KDoc)")
        stored[w.start] = v
        log("setStep ${b.v} ${w.start} $v (max ${w.max.v})")
    }

    /** setLimit and limitDropsEdits against the cota livre of today's month (Db.setLimit KDoc, D70). */
    suspend fun limitAction() {
        val b = budgets.keys.random(rnd)
        val cur = cur()
        val limit = 30_000L + rnd.nextLong(370_000)
        val (_, fixed) = ledger(b, cur)
        val free = maxOf(0L, limit - openOf(b, cur) - fixed)
        val here = storedEdits(b, cur)
        val drops = here.isNotEmpty() && here.values.sum() > free
        assertEquals(drops, db.limitDropsEdits(b, Cents(limit), today), "limitDropsEdits(${b.v}, $limit): stored $here vs cota livre $free")
        assertTrue(db.setLimit(b, Cents(limit), today), "setLimit(${b.v}, $limit)")
        log("setLimit ${b.v} $limit (cota livre $free, stored $here, drops $drops)")
        limits.getValue(b).add(cur to limit)
        if (drops) edits[b]?.keys?.removeAll(here.keys)
    }

    suspend fun advanceAction() {
        val days = if (rnd.nextInt(6) == 0) 15 + rnd.nextInt(21) else 1 + rnd.nextInt(6)
        today = minOf(today.rsvDays(days), RSV_LAST_TODAY)
        log("today -> $today")
        db.ensureMonths(cur().rsvShift(2))
    }
}

class ReservaInvariantsTest {

    private fun randomRun(seed: Int, startDay: Int, steps: Int = 140) = runTest(timeout = 300.seconds) {
        withReservaInvariantsDb { db -> ReservaInvariantRun(db, seed, startDay).run(steps) }
    }

    @Test fun `random reserva actions keep the margem invariants with seed 7 and month start day 1`() = randomRun(7, 1)

    @Test fun `random reserva actions keep the margem invariants with seed 31 and month start day 5`() = randomRun(31, 5)

    @Test fun `random reserva actions keep the margem invariants with seed 2026 and month start day 29`() = randomRun(2026, 29)

    @Test fun `random reserva actions keep the margem invariants with seed 911 and month start day 15`() = randomRun(911, 15)

    /** The values of one BudgetMonth that the hand-made ledger fixes; the steps edited are week start → passo. */
    private fun expectMonth(
        bm: BudgetMonth, what: String, spent: Long, fixed: Long, open: Long, free: Long, remaining: Long, edited: Map<LocalDate, Long>,
    ) {
        assertEquals(spent, bm.spent.v, "$what: spent")
        assertEquals(fixed, bm.fixed.v, "$what: fixed")
        assertEquals(spent - fixed, bm.variable.v, "$what: variable")
        assertEquals(open, bm.open.v, "$what: open")
        assertEquals(open + fixed, bm.planned.v, "$what: planned")
        assertEquals(free, bm.freeLimit.v, "$what: freeLimit")
        assertEquals(remaining, bm.remaining.v, "$what: remaining")
        assertEquals(free, bm.weeks.sumOf { it.step.v }, "$what: Σ passos = cota livre (spec §4)")
        assertEquals(edited, bm.weeks.filter { it.edited }.associate { it.start to it.step.v }, "$what: the edited passos that count (D70)")
    }

    /**
     * The book's verba Casa (spec §3 example: cota R$ 3.000, aluguel R$ 2.000), October 2026, month start day 1,
     * weeks from Monday: 1–4 out (qui–dom, 4 days), 5–11, 12–18, 19–25, 26–31 (6 days). Every number by hand.
     */
    @Test fun `the book scenario keeps margem cota livre and edited passos honest step by step`() = runTest(timeout = 60.seconds) {
        withReservaInvariantsDb { db ->
            var today = LocalDate(2026, 10, 9)
            val oct = YearMonth(2026, 10)
            val oct12 = LocalDate(2026, 10, 12)
            val oct19 = LocalDate(2026, 10, 19)
            assertTrue(db.addSource(RSV_PKG, RSV_LABEL, 0L))
            val casa = db.createBudget("Casa", Cents(300_000), today)
            assertTrue(casa.v != 0L)
            db.ensureMonths(oct.plusMonth())
            suspend fun month(): BudgetMonth = db.budgetsOverview(oct, today, BudgetOrder.MANUAL).single { it.id == casa }.month

            // R$ 40,00 of variable spending on 8 out.
            val padaria = db.addManual(
                TransactionForm(budget = casa, tags = listOf("feira"), total = Cents(4_000), merchant = "PADARIA", date = LocalDate(2026, 10, 8), time = LocalTime(9, 0)),
                today,
            )
            assertTrue(padaria.transaction.v != 0L)
            // free 3.000,00; margem 3.000,00 − 40,00 = 2.960,00.
            expectMonth(month(), "after the padaria", spent = 4_000, fixed = 0, open = 0, free = 300_000, remaining = 296_000, edited = emptyMap())

            assertTrue(db.setStep(casa, oct, oct12, Cents(80_000)), "passo of 12 out = 800,00 (max 3.000,00)")

            // Salvar previsto Aluguel R$ 2.000,00 on 20 out: reserves though 20 out > today + 10 = 19 out (M1, D71).
            val aluguel = db.addManual(
                TransactionForm(budget = casa, tags = listOf("casa"), total = Cents(200_000), date = LocalDate(2026, 10, 20), caption = "Aluguel"),
                today,
            )
            assertEquals(TransactionId(0), aluguel.transaction)
            assertTrue(aluguel.planned.v != 0L)
            // open 2.000,00; cota livre 3.000 − 2.000 = 1.000,00; margem 3.000 − 40 − 2.000 = 960,00; the edit 800 ≤ 1.000 counts.
            var bm = month()
            expectMonth(bm, "aluguel open", spent = 4_000, fixed = 0, open = 200_000, free = 100_000, remaining = 96_000, edited = mapOf(oct12 to 80_000L))
            // Week.max of 19 out = 1.000 − 800 = 200,00 (D70).
            assertEquals(20_000L, bm.weeks.single { it.start == oct19 }.max.v, "max of 19 out against the cota livre")
            assertFalse(db.setStep(casa, oct, oct19, Cents(20_001)), "200,01 passes the cota livre")
            assertTrue(db.setStep(casa, oct, oct19, Cents(20_000)), "200,00 fits exactly")
            // 800 + 200 = 1.000 = cota livre: both count; the three automatic weeks share 0.
            expectMonth(month(), "two edits", spent = 4_000, fixed = 0, open = 200_000, free = 100_000, remaining = 96_000, edited = mapOf(oct12 to 80_000L, oct19 to 20_000L))

            // Salvar previsto Internet R$ 150,00 on 28 out, todo mês: open 2.150,00, cota livre 850,00 < 1.000 of edits.
            val internet = db.addManual(
                TransactionForm(budget = casa, tags = listOf("contas"), total = Cents(15_000), date = LocalDate(2026, 10, 28), caption = "Internet", repeats = Repeats.MONTHLY),
                today,
            )
            assertTrue(internet.planned.v != 0L)
            // margem 3.000 − 40 − 2.150 = 810,00; the edits stop counting, without warning (spec §4, D70).
            expectMonth(month(), "internet open too", spent = 4_000, fixed = 0, open = 215_000, free = 85_000, remaining = 81_000, edited = emptyMap())
            // setLimit to the same 3.000 would delete them (1.000 > 850); at 3.150 the cota livre is 1.000 and they stay.
            assertTrue(db.limitDropsEdits(casa, Cents(300_000), today), "1.000 of edits > cota livre 850")
            assertFalse(db.limitDropsEdits(casa, Cents(315_000), today), "1.000 of edits = cota livre 3.150 − 2.150")

            // 19 out: planned(today) lists both (up to 29 out). Pular Internet in October.
            today = oct19
            val rows = db.planned(today)
            val aluguelOcc = rows.single { it.planned == aluguel.planned && it.date == LocalDate(2026, 10, 20) }
            val internetOcc = rows.single { it.planned == internet.planned && it.date == LocalDate(2026, 10, 28) }
            assertTrue(db.skipOccurrence(internetOcc.occurrence))
            assertFalse(db.skipOccurrence(internetOcc.occurrence), "a skipped occurrence is not open")
            // A skipped one never reserves: open 2.000, cota livre 1.000, the edits count again (D70).
            bm = month()
            expectMonth(bm, "internet skipped", spent = 4_000, fixed = 0, open = 200_000, free = 100_000, remaining = 96_000, edited = mapOf(oct12 to 80_000L, oct19 to 20_000L))
            // Ideal of 19 out (spec §5): 1–4 out 0 + 5–11 out 0 + 12–18 out 800,00 + 200,00 × 1 ÷ 7 = 28,57 → 828,57.
            assertEquals(82_857L, bm.ideal.v, "ideal of 19 out")
            assertEquals(PaceStatus.ON_PACE, bm.status, "variable 40,00 ≤ ideal 828,57 and margem ≥ 0")
            // M6: 4.000 × 100.000 ÷ 82.857 = 4.827 (rounded down) + 200.000 = 204.827.
            assertEquals(204_827L, bm.projection.v, "projection (M6)")

            // Lançar sem recado the aluguel at R$ 2.100,00 on 19 out (same month: the date stays, D72).
            val paid = db.addManual(
                TransactionForm(budget = casa, tags = listOf("casa"), total = Cents(210_000), date = oct19, time = LocalTime(10, 0), caption = "Aluguel", occurrence = aluguelOcc.occurrence),
                today,
            )
            assertTrue(paid.transaction.v != 0L)
            val paidRow = db.transaction(paid.transaction).row
            assertTrue(paidRow.fixed, "closes an occurrence: fixed (D69)")
            assertEquals(oct19, paidRow.date)
            // spec §3 example: cota livre 3.000 − 2.100 = 900,00; margem 3.000 − (40 + 2.100) − 0 = 860,00; edits 1.000 > 900: ignored.
            expectMonth(month(), "aluguel lançado at 2.100", spent = 214_000, fixed = 210_000, open = 0, free = 90_000, remaining = 86_000, edited = emptyMap())
            var lines = db.budgetDetail(casa, oct, today).plannedLines
            assertEquals(1, lines.size, "Previstos de outubro: the aluguel only (the internet was skipped): $lines")
            assertEquals(aluguelOcc.occurrence, lines[0].planned.occurrence)
            assertEquals(210_000L, lines[0].cents.v, "lançada: the lançado value")
            assertEquals(oct19, lines[0].launched)

            // Estorno of the aluguel: nothing of it counts and its occurrence stays closed (a lançamento points to it).
            val estorno = rsvCapture(db, 1, "LOJA ESTACAO", 210_000, oct19)
            assertTrue(db.registerRefund(estorno.id, paid.transaction))
            // spent 40, fixed 0, open 0: cota livre 3.000, margem 2.960; the edits fit again and count.
            expectMonth(month(), "aluguel estornado", spent = 4_000, fixed = 0, open = 0, free = 300_000, remaining = 296_000, edited = mapOf(oct12 to 80_000L, oct19 to 20_000L))
            lines = db.budgetDetail(casa, oct, today).plannedLines
            assertEquals(1, lines.size, "the estornado aluguel is still a lançada line")
            assertFalse(lines[0].open)
            assertEquals(0L, lines[0].cents.v, "a refunded lançamento adds nothing to fixed (PlannedLine.cents KDoc)")

            assertTrue(db.undoRefund(estorno.id))
            expectMonth(month(), "estorno undone", spent = 214_000, fixed = 210_000, open = 0, free = 90_000, remaining = 86_000, edited = emptyMap())

            // Apagar the aluguel's lançamento: the occurrence reopens and reserves 2.000 again (S1, M1).
            db.deleteTransaction(paid.transaction)
            expectMonth(month(), "aluguel lançamento deleted", spent = 4_000, fixed = 0, open = 200_000, free = 100_000, remaining = 96_000, edited = mapOf(oct12 to 80_000L, oct19 to 20_000L))
            lines = db.budgetDetail(casa, oct, today).plannedLines
            assertEquals(1, lines.size)
            assertTrue(lines[0].open, "reopened")
            assertEquals(200_000L, lines[0].cents.v, "open: the previsto's value")
            assertFalse(lines[0].planned.late, "20 out is not before 19 out")

            // Apagar previsto Aluguel: the reserve goes; the variable 40 stays.
            db.deletePlanned(aluguel.planned)
            expectMonth(month(), "aluguel deleted", spent = 4_000, fixed = 0, open = 0, free = 300_000, remaining = 296_000, edited = mapOf(oct12 to 80_000L, oct19 to 20_000L))
            assertEquals(emptyList(), db.budgetDetail(casa, oct, today).plannedLines, "no previsto left in October")

            // setLimit 999,99: cota livre 999,99 < 1.000 of edits → deleted (Db.setLimit KDoc); 1.000,00 would keep them.
            assertFalse(db.limitDropsEdits(casa, Cents(100_000), today), "1.000 of edits = cota livre 1.000")
            assertTrue(db.limitDropsEdits(casa, Cents(99_999), today), "1.000 of edits > cota livre 999,99")
            assertTrue(db.setLimit(casa, Cents(99_999), today))
            // margem 999,99 − 40 = 959,99.
            expectMonth(month(), "limit 999,99", spent = 4_000, fixed = 0, open = 0, free = 99_999, remaining = 95_999, edited = emptyMap())
            assertFalse(db.clearStep(casa, oct, oct12), "the edit was deleted")
        }
    }

    /**
     * D72 and D69 by hand: a late October occurrence paid with a 3 nov recado lands on its October day; an edit to
     * November keeps it there; after Apagar previsto it moves freely and stays fixed. Month start day 1.
     */
    @Test fun `a late occurrence paid in the next month stays in its month and stays fixed after its previsto goes`() = runTest(timeout = 60.seconds) {
        withReservaInvariantsDb { db ->
            var today = LocalDate(2026, 10, 9)
            val sep = YearMonth(2026, 9)
            val oct = YearMonth(2026, 10)
            val nov = YearMonth(2026, 11)
            assertTrue(db.addSource(RSV_PKG, RSV_LABEL, 0L))
            val contas = db.createBudget("Contas", Cents(50_000), LocalDate(2026, 8, 1))
            assertTrue(contas.v != 0L)
            db.ensureMonths(nov)
            suspend fun month(m: YearMonth) = db.budgetsOverview(m, today, BudgetOrder.MANUAL).single { it.id == contas }.month

            // "Todo mês" on a lançamento of 5 set (R$ 99,90): a previsto from 5 out, monthly, day 5.
            val first = db.addManual(
                TransactionForm(budget = contas, tags = listOf("contas"), total = Cents(9_990), merchant = "VIVO FIBRA", date = LocalDate(2026, 9, 5), time = LocalTime(8, 0), caption = "Internet", repeats = Repeats.MONTHLY),
                today,
            )
            assertTrue(first.transaction.v != 0L && first.planned.v != 0L)
            // September: the lançamento is ordinary (variable). October: 5 out is late and open, it reserves 99,90.
            var bm = month(sep)
            assertEquals(9_990L, bm.variable.v)
            assertEquals(0L, bm.fixed.v)
            bm = month(oct)
            assertEquals(9_990L, bm.open.v, "the late 5 out keeps reserving in October (M1, M8)")
            assertEquals(50_000L - 9_990L, bm.remaining.v, "margem 500 − 0 − 99,90 = 400,10")
            assertEquals(50_000L - 9_990L, bm.freeLimit.v, "cota livre 500 − 99,90 = 400,10")

            // 3 nov: the recado of R$ 104,90 arrives; it is lançado as the October occurrence.
            today = LocalDate(2026, 11, 3)
            val octOcc = db.planned(today).single { it.planned == first.planned && it.date == LocalDate(2026, 10, 5) }
            assertTrue(octOcc.late)
            val recado = rsvCapture(db, 1, "VIVO FIBRA", 10_490, today)
            val tx = db.accept(
                recado.id,
                TransactionForm(budget = contas, tags = listOf("contas"), total = recado.parsed.cents, merchant = recado.parsed.merchant, date = recado.parsed.date, time = recado.parsed.time, caption = "Internet", occurrence = octOcc.occurrence),
            )
            assertTrue(tx.v != 0L)
            var row = db.transaction(tx).row
            assertEquals(LocalDate(2026, 10, 5), row.date, "D72: the recado of 3 nov falls in November, so the lançamento takes 5 out")
            assertEquals(recado.parsed.time, row.time, "D72: with the time it had")
            assertFalse(row.corrected, "D72: not an emenda")
            assertTrue(row.fixed, "D69")
            // October: spent = fixed = 104,90, open 0; cota livre 500 − 104,90 = 395,10 = margem. November: 5 nov open 99,90.
            bm = month(oct)
            assertEquals(10_490L, bm.spent.v)
            assertEquals(10_490L, bm.fixed.v)
            assertEquals(0L, bm.variable.v)
            assertEquals(0L, bm.open.v)
            assertEquals(39_510L, bm.freeLimit.v)
            assertEquals(39_510L, bm.remaining.v)
            bm = month(nov)
            assertEquals(0L, bm.spent.v)
            assertEquals(9_990L, bm.open.v, "5 nov reserves in November")
            assertEquals(40_010L, bm.remaining.v)

            // Edit to 10 nov 21:15: still its occurrence's October day, with the new time (D72).
            assertTrue(db.edit(tx, TransactionForm(budget = contas, tags = listOf("contas"), total = Cents(10_490), merchant = "VIVO FIBRA", date = LocalDate(2026, 11, 10), time = LocalTime(21, 15), caption = "Internet", repeats = Repeats.MONTHLY)))
            row = db.transaction(tx).row
            assertEquals(LocalDate(2026, 10, 5), row.date, "D72 on edit")
            assertEquals(LocalTime(21, 15), row.time)
            // Edit to 20 out: the same budget month, the date as given.
            assertTrue(db.edit(tx, TransactionForm(budget = contas, tags = listOf("contas"), total = Cents(10_490), merchant = "VIVO FIBRA", date = LocalDate(2026, 10, 20), time = LocalTime(7, 30), caption = "Internet", repeats = Repeats.MONTHLY)))
            assertEquals(LocalDate(2026, 10, 20), db.transaction(tx).row.date, "D72 keeps a date inside the occurrence's month")

            // Apagar previsto: November's reserve goes; October's 104,90 stays fixed, the variable stays 0.
            db.deletePlanned(first.planned)
            bm = month(oct)
            assertEquals(10_490L, bm.fixed.v, "the lançamento stays fixed (D69)")
            assertEquals(0L, bm.variable.v, "deleting the previsto never turns it into gasto variável")
            assertEquals(0L, month(nov).open.v)
            assertEquals(9_990L, month(sep).variable.v, "the September lançamento is untouched")

            // Without its occurrence, an edit moves it to November, still fixed.
            assertTrue(db.edit(tx, TransactionForm(budget = contas, tags = listOf("contas"), total = Cents(10_490), merchant = "VIVO FIBRA", date = LocalDate(2026, 11, 2), time = LocalTime(7, 30), caption = "Internet")))
            row = db.transaction(tx).row
            assertEquals(LocalDate(2026, 11, 2), row.date, "no occurrence left: the date as given")
            assertTrue(row.fixed, "still fixed (D69)")
            assertEquals(0L, month(oct).fixed.v)
            bm = month(nov)
            assertEquals(10_490L, bm.fixed.v)
            assertEquals(0L, bm.variable.v)
            assertEquals(50_000L - 10_490L, bm.remaining.v, "margem 500 − 104,90 = 395,10")
        }
    }
}
