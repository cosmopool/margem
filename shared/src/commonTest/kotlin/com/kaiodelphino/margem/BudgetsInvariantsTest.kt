package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Invariants of the Verbas block (spec §3, §4, §7.1; architecture D8–D11, §5, §6), checked through the
// public Db API only, after every step of random (fixed-seed) action sequences over budgets, limits,
// weekly steps, manual order, settings, installment purchases and estornos, with `today` moving forward.
// What block 1's InvariantsTest already covers (notices, tags, transactions, history) isn't repeated.

private val BINV_START = LocalDate(2026, 10, 14)
private const val BINV_PKG = "com.nu.production"
/** Epoch ms in early October 2026; notices are posted 10 minutes apart from here. */
private const val BINV_T0 = 1_791_000_000_000L
private val BINV_NAMES = listOf("Mercado", "Casa", "Lazer", "Transporte", "Saude", "Pets", "Viagem", "mercado", "CASA", "  ", "")
private val BINV_TAGS = listOf("feira", "padaria", "cafe", "farmacia")
private val BINV_UNKNOWN = BudgetId(987_654)

private fun YearMonth.binvShift(n: Int): YearMonth = if (n >= 0) plus(n, DateTimeUnit.MONTH) else minus(-n, DateTimeUnit.MONTH)

/** "1.234,56" */
private fun binvBrl(c: Long): String {
    val reais = (c / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return "$reais,${(c % 100).toString().padStart(2, '0')}"
}

/** A real SQLite file per test, deleted afterwards with its -wal and -shm. */
private suspend fun withBudgetsDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-binv-${Random.Default.nextLong().toULong()}.db").toString()
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

/** One random run, with a small model of what the test did: budgets (name, first month) and the manual order. */
private class BudgetsInvariantRun(val db: Db, val seed: Int) {
    val rnd = Random(seed)
    val log = ArrayList<String>()
    var step = 0
    var today = BINV_START
    val names = LinkedHashMap<BudgetId, String>()
    /**
     * BudgetDetail.firstMonth as read right after createBudget. Only setMonthStartDay moves it: back to
     * today's new month when that is earlier (D43).
     */
    val firstMonths = HashMap<BudgetId, YearMonth>()
    /** Expected manual order: creation appends (createBudget KDoc), an accepted reorderBudgets replaces it. */
    var manual = ArrayList<BudgetId>()
    val gone = ArrayList<BudgetId>()
    /** Captured estorno notices. */
    val estornos = ArrayList<NoticeId>()
    var captures = 0

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
        assertTrue(db.addSource(BINV_PKG, "Nubank", BINV_T0), "addSource")
        createBudget("Mercado", 80_000)
        createBudget("Casa", 150_000)
        createBudget("Lazer", 30_000)
        repeat(8) { addManualAction() }
        repeat(3) { captureEstornoAction() }
    }

    suspend fun monthNow(): YearMonth = db.monthOf(today)

    suspend fun weeksOf(id: BudgetId, m: YearMonth): List<Week> = db.budgetDetail(id, m, today).month.weeks

    // ---- actions ----

    suspend fun act() {
        val r = rnd.nextInt(100)
        when {
            r < 7 -> createBudgetAction()
            r < 10 -> renameBudgetAction()
            r < 22 -> setLimitAction()
            r < 40 -> setStepAction()
            r < 50 -> clearStepAction()
            r < 56 -> reorderAction()
            r < 59 -> deleteBudgetAction()
            r < 62 -> setMonthStartDayAction()
            r < 65 -> setWeekStartAction()
            r < 79 -> addManualAction()
            r < 83 -> captureEstornoAction()
            r < 89 -> registerRefundAction()
            r < 93 -> undoRefundAction()
            else -> advanceTodayAction()
        }
    }

    /**
     * previewBudget(limit, today) must equal budgetDetail(new, today's month, today).month right after
     * createBudget(…, limit, today) (previewBudget KDoc), and write nothing.
     */
    suspend fun createBudget(name: String, limit: Long) {
        val m0 = monthNow()
        val v0 = db.version.value
        val preview = db.previewBudget(Cents(limit), today)
        assertEquals(v0, db.version.value, "previewBudget writes nothing (no version bump)")
        if (limit <= 0) assertEquals(BudgetMonth(), preview, "previewBudget($limit) is the zero BudgetMonth (createBudget refuses limit ≤ 0)")
        val expect = name.isNotBlank() && names.values.none { it.equals(name.trim(), ignoreCase = true) } && limit > 0
        val id = db.createBudget(name, Cents(limit), today)
        log("createBudget \"$name\" $limit -> ${id.v}")
        assertEquals(expect, id.v != 0L, "createBudget \"$name\" $limit accepted/refused (KDoc)")
        if (id.v == 0L) return
        assertTrue(id !in names && id !in gone, "createBudget returns a new id")
        db.ensureMonths(m0)
        val d = db.budgetDetail(id, m0, today)
        assertEquals(preview, d.month, "previewBudget($limit, $today) == budgetDetail month right after createBudget")
        // BudgetDetail.firstMonth KDoc: the month it was created in, by the month start day of that time.
        assertEquals(m0, d.firstMonth, "firstMonth of a budget created on $today")
        names[id] = name.trim()
        firstMonths[id] = d.firstMonth
        manual.add(id)
    }

    suspend fun createBudgetAction() {
        val name = BINV_NAMES.random(rnd) + if (rnd.nextInt(3) == 0) " ${rnd.nextInt(50)}" else ""
        val limit = if (rnd.nextInt(8) == 0) -rnd.nextLong(2) * 100 else 5_000L + rnd.nextLong(400_000)
        createBudget(name, limit)
    }

    suspend fun renameBudgetAction() {
        val id = if (manual.isEmpty() || rnd.nextInt(10) == 0) BINV_UNKNOWN else manual.random(rnd)
        val name = BINV_NAMES.random(rnd) + if (rnd.nextBoolean()) " ${rnd.nextInt(50)}" else ""
        val clean = name.trim()
        val own = names[id]
        // Renaming to its own name (any case) isn't specified: no prediction there.
        val expect: Boolean? = when {
            own == null -> false
            clean.isEmpty() -> false
            clean.equals(own, ignoreCase = true) -> null
            names.any { (k, v) -> k != id && v.equals(clean, ignoreCase = true) } -> false
            else -> true
        }
        val ok = db.renameBudget(id, name)
        log("renameBudget ${id.v} \"$name\" -> $ok")
        if (expect != null) assertEquals(expect, ok, "renameBudget accepted/refused (KDoc)")
        if (ok) names[id] = clean
    }

    /**
     * limitDropsEdits(x) ⇔ setLimit(x) really deleted step edits (compare Week.edited before/after), and
     * in each month from today's on the edits go all at once exactly when Σ edited > new limit (spec §4).
     */
    suspend fun setLimitAction() {
        val m0 = monthNow()
        // A budget whose first month is after today's month is left out: whether setLimit creates a row
        // before its first month is not specified (setMonthStartDay no longer leaves one, D43).
        val eligible = manual.filter { firstMonths.getValue(it) <= m0 }
        val unknown = eligible.isEmpty() || rnd.nextInt(12) == 0
        val id = if (unknown) gone.randomOrNull(rnd) ?: BINV_UNKNOWN else eligible.random(rnd)
        val range = (0..5).map { m0.binvShift(it) }
        db.ensureMonths(range.last())
        val before: Map<YearMonth, List<Week>> = if (unknown) emptyMap() else range.associateWith { weeksOf(id, it) }
        val editedSums = before.values.map { ws -> ws.filter { it.edited }.sumOf { it.step.v } }.filter { it > 0 }
        val limit = when {
            rnd.nextInt(10) == 0 -> -rnd.nextLong(2) * 100
            editedSums.isNotEmpty() && rnd.nextInt(3) == 0 -> (editedSums.random(rnd) - 1 - rnd.nextLong(3_000)).coerceAtLeast(1)
            editedSums.isNotEmpty() && rnd.nextInt(4) == 0 -> editedSums.random(rnd)
            else -> 5_000L + rnd.nextLong(400_000)
        }
        val prev = m0.binvShift(-1)
        val prevBefore = if (!unknown && prev >= firstMonths.getValue(id)) db.budgetDetail(id, prev, today).month else null

        val v0 = db.version.value
        val drops = db.limitDropsEdits(id, Cents(limit), today)
        assertEquals(v0, db.version.value, "limitDropsEdits writes nothing (no version bump)")
        for ((m, ws) in before) assertEquals(ws, weeksOf(id, m), "limitDropsEdits changed the weeks of $m")

        val ok = db.setLimit(id, Cents(limit), today)
        log("setLimit ${id.v} $limit (edited sums $editedSums) limitDropsEdits=$drops -> $ok")
        assertEquals(!unknown && limit > 0, ok, "setLimit accepted iff the budget exists and limit > 0 (KDoc)")
        if (!ok) {
            assertFalse(drops, "limitDropsEdits is false when setLimit refuses (KDoc)")
            assertEquals(v0, db.version.value, "refused setLimit doesn't bump version")
            return
        }
        var dropped = false
        for ((m, ws) in before) {
            val after = db.budgetDetail(id, m, today).month
            assertEquals(Cents(limit), after.limit, "spec §3: the new limit holds in $m (from today's month on)")
            val editsBefore = ws.filter { it.edited }.map { it.start to it.step }
            val editsAfter = after.weeks.filter { it.edited }.map { it.start to it.step }
            val sum = editsBefore.sumOf { it.second.v }
            if (sum > limit) {
                assertTrue(editsAfter.isEmpty(), "spec §4: Σ edited $sum > new limit $limit deletes every edit of $m, left $editsAfter")
            } else {
                assertEquals(editsBefore, editsAfter, "spec §4: Σ edited $sum ≤ new limit $limit keeps the edits of $m")
            }
            if (editsBefore != editsAfter) dropped = true
        }
        assertEquals(dropped, drops, "limitDropsEdits($limit) == setLimit($limit) actually deleted edits")
        if (prevBefore != null) {
            val pa = db.budgetDetail(id, prev, today).month
            assertEquals(prevBefore.limit, pa.limit, "spec §3: past month $prev keeps its limit")
            assertEquals(prevBefore.weeks, pa.weeks, "past month $prev keeps its steps and edits")
        }
        assertEquals(firstMonths.getValue(id), db.budgetDetail(id, m0, today).firstMonth, "setLimit doesn't move firstMonth")
    }

    /** A budget and one of the months around today where it has a limit, with its detail. */
    suspend fun pickMonth(): Triple<BudgetId, YearMonth, BudgetDetail>? {
        if (manual.isEmpty()) return null
        val id = manual.random(rnd)
        val m = monthNow().binvShift(rnd.nextInt(-1, 4))
        if (m < firstMonths.getValue(id)) return null
        db.ensureMonths(m)
        val d = db.budgetDetail(id, m, today)
        if (m < monthNow() && !editsFit(d.month)) return null
        return Triple(id, m, d)
    }

    suspend fun setStepAction() {
        val (id, m, d) = pickMonth() ?: return
        val weeks = d.month.weeks
        val w = weeks.random(rnd)
        val v = when (rnd.nextInt(8)) {
            0 -> -1L
            1 -> w.max.v + 1
            2 -> w.max.v
            else -> if (w.max.v > 0) rnd.nextLong(w.max.v + 1) else 0L
        }
        // Week.max / Week.locked KDoc: any value in 0..max is accepted, max + 1 refused; locked refuses any value.
        val expect = !w.locked && v in 0..w.max.v
        val v0 = db.version.value
        val ok = db.setStep(id, m, w.start, Cents(v))
        log("setStep ${id.v} $m ${w.start} $v (max ${w.max.v} locked ${w.locked}) -> $ok")
        assertEquals(expect, ok, "setStep accepted iff not locked and in 0..Week.max")
        val after = weeksOf(id, m)
        if (!ok) {
            assertEquals(weeks, after, "refused setStep changed the weeks")
            assertEquals(v0, db.version.value, "refused setStep doesn't bump version")
            return
        }
        val nw = after.single { it.start == w.start }
        assertEquals(Cents(v), nw.step, "edited step")
        assertTrue(nw.edited, "edited week flagged")
    }

    /** clearStep is true only for an edited week; afterwards that week is automatic and the other edits stay. */
    suspend fun clearStepAction() {
        val (id, m, d) = pickMonth() ?: return
        val weeks = d.month.weeks
        val edited = weeks.filter { it.edited }
        val r = rnd.nextInt(10)
        var budget = id
        val ws: LocalDate
        val expect: Boolean
        when {
            r == 0 -> {
                budget = gone.randomOrNull(rnd) ?: BINV_UNKNOWN
                ws = (edited.randomOrNull(rnd) ?: weeks.random(rnd)).start
                expect = false
            }
            r == 1 && weeks.any { it.start < it.end } -> {
                // A day inside a week, not its start: no week starts there, so there is no edit to remove.
                ws = weeks.filter { it.start < it.end }.random(rnd).start.plus(1, DateTimeUnit.DAY)
                expect = false
            }
            r < 8 && edited.isNotEmpty() -> {
                ws = edited.random(rnd).start
                expect = true
            }
            else -> {
                val w = weeks.random(rnd)
                ws = w.start
                expect = w.edited
            }
        }
        val v0 = db.version.value
        val ok = db.clearStep(budget, m, ws)
        log("clearStep ${budget.v} $m $ws -> $ok")
        assertEquals(expect, ok, "clearStep true iff that week of that month is edited (KDoc)")
        val after = weeksOf(id, m)
        if (!ok) {
            assertEquals(weeks, after, "clearStep false changed the weeks")
            assertEquals(v0, db.version.value, "clearStep false doesn't bump version")
            return
        }
        assertTrue(db.version.value > v0, "clearStep that removed an edit bumps version")
        assertEquals(weeks.map { it.start to it.end }, after.map { it.start to it.end }, "clearStep keeps the weeks")
        assertFalse(after.single { it.start == ws }.edited, "spec §4: after Voltar ao automático the week is automatic")
        for (w in edited) {
            if (w.start == ws) continue
            val a = after.single { it.start == w.start }
            assertTrue(a.edited, "clearStep of $ws keeps the edit of ${w.start}")
            assertEquals(w.step, a.step, "clearStep of $ws keeps the step of ${w.start}")
        }
    }

    suspend fun reorderAction() {
        val valid = manual.size < 2 || rnd.nextInt(10) < 7
        val ids: List<BudgetId> = if (valid) {
            manual.shuffled(rnd)
        } else {
            when (rnd.nextInt(4)) {
                0 -> manual.shuffled(rnd).drop(1)
                1 -> manual.shuffled(rnd) + manual.random(rnd)
                2 -> manual.shuffled(rnd) + (gone.randomOrNull(rnd) ?: BINV_UNKNOWN)
                else -> manual.shuffled(rnd).let { it.dropLast(1) + it.first() }
            }
        }
        val expect = ids.size == manual.size && ids.toSet() == manual.toSet()
        val v0 = db.version.value
        val ok = db.reorderBudgets(ids)
        log("reorderBudgets ${ids.map { it.v }} -> $ok")
        assertEquals(expect, ok, "reorderBudgets accepted iff exactly the existing budgets (KDoc)")
        if (ok) manual = ArrayList(ids) else assertEquals(v0, db.version.value, "refused reorderBudgets doesn't bump version")
    }

    suspend fun deleteBudgetAction() {
        if (manual.size < 2) return createBudgetAction()
        val id = manual.random(rnd)
        db.deleteBudget(id)
        log("deleteBudget ${id.v}")
        manual.remove(id)
        names.remove(id)
        firstMonths.remove(id)
        gone.add(id)
        assertEquals(BudgetDetail(), db.budgetDetail(id, monthNow(), today), "budgetDetail of a deleted budget is zero (KDoc)")
    }

    suspend fun setMonthStartDayAction() {
        val day = 1 + rnd.nextInt(31)
        val was = monthNow()
        db.ensureMonths(was)
        val limits = manual.associateWith { db.budgetDetail(it, was, today).month.limit }
        val ok = db.setMonthStartDay(day, today)
        log("setMonthStartDay $day -> $ok")
        assertTrue(ok, "setMonthStartDay($day)")
        // D43: a budget without a limit in today's new month gets the one it had in the old current month.
        val now = monthNow()
        for (id in manual) {
            if (firstMonths.getValue(id) <= now) continue
            firstMonths[id] = now
            assertEquals(limits.getValue(id), db.budgetDetail(id, now, today).month.limit, "D43: budget ${id.v} keeps its limit in $now")
        }
    }

    suspend fun setWeekStartAction() {
        val day = DayOfWeek.entries.random(rnd)
        db.setWeekStart(day, today)
        log("setWeekStart $day")
    }

    /** A purchase, often in installments, dated around today. */
    suspend fun addManualAction() {
        if (manual.isEmpty()) return
        val b = manual.random(rnd)
        val n = listOf(1, 1, 1, 2, 3, 4, 6, 12).random(rnd)
        val total = 100L + rnd.nextLong(300_000)
        val date = today.plus(rnd.nextInt(-45, 6), DateTimeUnit.DAY)
        val time = LocalTime(rnd.nextInt(24), rnd.nextInt(60))
        val form = TransactionForm(
            budget = b, tags = listOf(BINV_TAGS.random(rnd)), total = Cents(total), installments = n,
            merchant = "LOJA ${rnd.nextInt(5)}", date = date, time = time,
        )
        val id = db.addManual(form).transaction
        log("addManual budget=${b.v} $total in $n on $date $time -> ${id.v}")
        assertNotEquals(0L, id.v, "addManual of a valid form")
    }

    suspend fun captureEstornoAction() {
        captures++
        val at = BINV_T0 + captures * 600_000L
        val cents = 100L + rnd.nextLong(50_000)
        val raw = RawNotice(
            packageName = BINV_PKG, appLabel = "Nubank", key = "binv-$captures", postedAt = at, whenMs = at, title = "Estorno",
            text = "A compra em LOJA $captures no valor de R$ ${binvBrl(cents)} foi estornada no seu cartão.",
        )
        val saved = capture(db, raw)
        log("capture estorno $captures -> $saved")
        assertTrue(saved, "capture of a new notice from a source")
        val row = assertNotNull(db.pending().firstOrNull { it.raw.key == raw.key }, "captured notice is pending")
        estornos.add(row.id)
    }

    suspend fun registerRefundAction() {
        val pendingIds = db.pending().map { it.id }.toSet()
        val e = estornos.filter { it in pendingIds }.randomOrNull(rnd) ?: return captureEstornoAction()
        val t = db.transactions().filter { !it.refunded }.randomOrNull(rnd) ?: return
        val ok = db.registerRefund(e, t.id)
        log("registerRefund estorno=${e.v} tx=${t.id.v} -> $ok")
    }

    suspend fun undoRefundAction() {
        val t = db.transactions().filter { it.refunded }.randomOrNull(rnd) ?: return
        val ok = db.undoRefund(t.estorno)
        log("undoRefund ${t.estorno.v} (tx ${t.id.v}) -> $ok")
        assertTrue(ok, "undoRefund of a registered estorno")
    }

    suspend fun advanceTodayAction() {
        today = today.plus(1 + rnd.nextInt(20), DateTimeUnit.DAY)
        log("today -> $today")
        db.ensureMonths(monthNow())
    }

    // ---- invariants ----

    suspend fun checkAll() {
        val m0 = monthNow()
        // Every budget has a limit row from its first month up to here (ensureMonths KDoc).
        db.ensureMonths(m0.binvShift(6))
        val far = db.budgetsOverview(m0.binvShift(6), today, BudgetOrder.MANUAL).map { it.id }
        assertEquals(manual.toList(), far, "manual order == last accepted reorderBudgets, new budgets at the end, deleted ones gone")

        val txs = db.transactions()
        val details = txs.associate { it.id to db.transaction(it.id) }
        val seen = ArrayList<Pair<BudgetId, YearMonth>>()
        for (k in -1..3) {
            val m = m0.binvShift(k)
            val overview = db.budgetsOverview(m, today, BudgetOrder.MANUAL)
            // Budgets with a limit row in m: those whose first month is ≤ m, in the manual order.
            assertEquals(manual.filter { firstMonths.getValue(it) <= m }, overview.map { it.id }, "budgetsOverview($m, MANUAL)")
            for (s in overview) {
                if (checkDetail(s, m, m < m0, txs, details)) seen.add(s.id to m)
            }
        }
        val probe = seen.randomOrNull(rnd)
        if (probe != null) probeMax(probe.first, probe.second)
    }

    /**
     * Σ edited steps ≤ limit and one week not edited: what setStep (Week.max, Week.locked) and setLimit
     * (spec §4) keep true from today's month on.
     */
    fun editsFit(bm: BudgetMonth) = bm.weeks.filter { it.edited }.sumOf { it.step.v } <= bm.limit.v && bm.weeks.any { !it.edited }

    /**
     * Checks one budget in one month. Returns whether the step invariants were checked: they are skipped
     * in a [past] month whose surviving edits sum more than its limit (or cover every week), which the spec doesn't rule out
     * (D10: past months are recalculated with a new month or week start day, keeping the edits that still
     * match a week, and those may come from different old months).
     */
    suspend fun checkDetail(
        s: BudgetSummary,
        m: YearMonth,
        past: Boolean,
        txs: List<TransactionRow>,
        details: Map<TransactionId, TransactionDetail>,
    ): Boolean {
        val id = s.id
        val what = "budget ${id.v} in $m"
        val d = db.budgetDetail(id, m, today)
        assertEquals(id, d.id, "$what: detail id")
        assertEquals(names[id], d.name, "$what: detail name")
        assertEquals(s.name, d.name, "$what: overview name == detail name")
        assertEquals(firstMonths[id], d.firstMonth, "$what: firstMonth fixed at creation, or moved back by setMonthStartDay (D43)")
        assertTrue(d.firstMonth <= m, "$what: firstMonth ${d.firstMonth} ≤ a month with a limit")
        val bm = d.month
        assertEquals(s.month, bm, "$what: overview month == detail month")
        assertEquals(m, bm.month, "$what: month")

        val weeks = bm.weeks
        assertTrue(weeks.isNotEmpty(), "$what: weeks")
        checkLines(what, d, txs, details)
        if (!editsFit(bm)) {
            assertTrue(past, "$what: Σ edited steps ≤ limit and a week not edited, from today's month on (Week.max, Week.locked, spec §4): $weeks")
            log("  $what: past month whose edits don't fit; step invariants skipped")
            return false
        }

        // D11: Σ steps == limit; spec §4: automatic steps never below 0.
        assertEquals(bm.limit.v, weeks.sumOf { it.step.v }, "$what: Σ steps == limit")
        for (w in weeks) assertTrue(w.step.v >= 0, "$what: step of ${w.start} ≥ 0")

        // Week.locked: exactly the only week not edited; none with two or more not edited.
        val auto = weeks.filter { !it.edited }
        val locked = weeks.filter { it.locked }
        assertTrue(auto.isNotEmpty(), "$what: one week always stays automatic")
        if (auto.size == 1) {
            assertEquals(auto, locked, "$what: the only week not edited is locked")
        } else {
            assertTrue(locked.isEmpty(), "$what: no locked week with ${auto.size} weeks not edited")
        }
        // Week.max: limit − Σ steps of the other edited weeks; 0 when locked.
        for (w in weeks) {
            if (w.locked) {
                assertEquals(0L, w.max.v, "$what: max of the locked week ${w.start}")
            } else {
                val others = weeks.filter { it.edited && it.start != w.start }.sumOf { it.step.v }
                assertEquals(bm.limit.v - others, w.max.v, "$what: max of ${w.start} == limit − Σ other edited")
            }
        }
        return true
    }

    fun checkLines(what: String, d: BudgetDetail, txs: List<TransactionRow>, details: Map<TransactionId, TransactionDetail>) {
        val id = d.id
        val bm = d.month

        // BudgetDetail.lines: one per transaction of the budget with an installment in the month, refunded included.
        val lines = d.lines
        val inMonth = txs.filter { t -> t.budget == id && details.getValue(t.id).installments.any { it.date in bm.start..bm.end } }
        val lineIds = lines.map { it.row.id }
        assertEquals(lineIds.size, lineIds.toSet().size, "$what: one line per transaction")
        assertEquals(inMonth.map { it.id }.toSet(), lineIds.toSet(), "$what: lines == transactions with an installment in the month")
        var several = false
        for (line in lines) {
            val det = details.getValue(line.row.id)
            assertEquals(det.row, line.row, "$what: line row of ${line.row.id.v} == transaction row")
            assertTrue(line.k in 1..line.row.installments, "$what: line k ${line.k} of ${line.row.id.v} in 1..${line.row.installments}")
            val inst = det.installments.single { it.k == line.k }
            assertEquals(inst.date, line.date, "$what: line date of ${line.row.id.v} == installment ${line.k} date")
            assertEquals(inst.cents, line.cents, "$what: line cents of ${line.row.id.v} == installment ${line.k} value")
            assertTrue(line.date in bm.start..bm.end, "$what: line date ${line.date} in the month")
            if (det.installments.count { it.date in bm.start..bm.end } > 1) several = true
        }
        for (i in 1 until lines.size) {
            val a = lines[i - 1]
            val b = lines[i]
            assertTrue(
                compareValuesBy(a, b, { it.date }, { it.row.time }, { it.row.id.v }) < 0,
                "$what: lines oldest first (date, time, id): ${a.row.id.v} before ${b.row.id.v}",
            )
        }
        // Σ cents of lines not refunded == spent. Only asserted when no transaction has two installments
        // in this window (possible after a month start day change): "one line per transaction" then can't hold both.
        if (!several) {
            assertEquals(bm.spent.v, lines.filter { !it.row.refunded }.sumOf { it.cents.v }, "$what: Σ lines not refunded == spent")
        }
    }

    /**
     * Week.max ⇔ what setStep accepts, for every week of one month: not locked → max + 1 refused, max
     * accepted (then undone by re-setting the old step or clearStep); locked → any value refused.
     */
    suspend fun probeMax(id: BudgetId, m: YearMonth) {
        log("probe max ${id.v} $m")
        val before = weeksOf(id, m)
        for (w in before) {
            val v0 = db.version.value
            if (w.locked) {
                assertFalse(db.setStep(id, m, w.start, Cents(0)), "probe $m ${w.start}: locked week refuses 0")
                assertFalse(db.setStep(id, m, w.start, w.step), "probe $m ${w.start}: locked week refuses its own step")
                assertEquals(v0, db.version.value, "probe $m ${w.start}: refused setStep doesn't bump version")
                assertEquals(before, weeksOf(id, m), "probe $m ${w.start}: refused setStep changed the weeks")
                continue
            }
            assertFalse(db.setStep(id, m, w.start, Cents(w.max.v + 1)), "probe $m ${w.start}: max + 1 (${w.max.v + 1}) refused")
            assertEquals(v0, db.version.value, "probe $m ${w.start}: refused setStep doesn't bump version")
            assertEquals(before, weeksOf(id, m), "probe $m ${w.start}: refused setStep changed the weeks")
            assertTrue(db.setStep(id, m, w.start, w.max), "probe $m ${w.start}: max (${w.max.v}) accepted")
            val at = weeksOf(id, m).single { it.start == w.start }
            assertEquals(w.max, at.step, "probe $m ${w.start}: step set to max")
            assertTrue(at.edited, "probe $m ${w.start}: edited after setStep")
            if (w.edited) {
                assertTrue(db.setStep(id, m, w.start, w.step), "probe $m ${w.start}: restoring the old step ${w.step.v}")
            } else {
                assertTrue(db.clearStep(id, m, w.start), "probe $m ${w.start}: clearStep after the probe")
            }
            assertEquals(before, weeksOf(id, m), "probe $m ${w.start}: weeks restored")
        }
    }
}

class BudgetsInvariantsTest {

    private fun randomRun(seed: Int, steps: Int = 100) = runTest(timeout = 120.seconds) {
        withBudgetsDb { db -> BudgetsInvariantRun(db, seed).run(steps) }
    }

    @Test fun `random budget actions keep the invariants with seed 3`() = randomRun(3)

    @Test fun `random budget actions keep the invariants with seed 11`() = randomRun(11)

    @Test fun `random budget actions keep the invariants with seed 77`() = randomRun(77)

    @Test fun `random budget actions keep the invariants with seed 2026`() = randomRun(2026)

    /** Spec §4: edits go only when the new limit is *below* their sum; limitDropsEdits says so beforehand. */
    @Test
    fun `limitDropsEdits agrees with setLimit at the sum of the edited steps`() = runTest(timeout = 30.seconds) {
        withBudgetsDb { db ->
            val today = BINV_START
            val id = db.createBudget("Mercado", Cents(100_000), today)
            assertNotEquals(0L, id.v)
            val m = db.monthOf(today)
            db.ensureMonths(m)
            val weeks = db.budgetDetail(id, m, today).month.weeks
            assertTrue(weeks.size >= 3, "a month has at least 3 weeks")
            assertTrue(db.setStep(id, m, weeks[1].start, Cents(40_000)))
            assertTrue(db.setStep(id, m, weeks[2].start, Cents(20_000)))
            // Σ edited = 60_000.
            assertFalse(db.limitDropsEdits(id, Cents(60_000), today), "Σ edited == new limit: nothing deleted")
            assertTrue(db.limitDropsEdits(id, Cents(59_999), today), "Σ edited > new limit: edits deleted")
            assertFalse(db.limitDropsEdits(id, Cents(0), today), "setLimit refuses 0")
            assertFalse(db.limitDropsEdits(BINV_UNKNOWN, Cents(1), today), "unknown budget")

            assertTrue(db.setLimit(id, Cents(60_000), today))
            val kept = db.budgetDetail(id, m, today).month.weeks
            assertEquals(listOf(weeks[1].start, weeks[2].start), kept.filter { it.edited }.map { it.start }, "edits kept at Σ == limit")
            assertEquals(60_000L, kept.sumOf { it.step.v }, "D11: Σ steps == limit")

            assertTrue(db.setLimit(id, Cents(59_999), today))
            val dropped = db.budgetDetail(id, m, today).month.weeks
            assertTrue(dropped.none { it.edited }, "spec §4: every edit of the month deleted")
            assertEquals(59_999L, dropped.sumOf { it.step.v }, "D11: Σ steps == limit")
            assertFalse(db.clearStep(id, m, weeks[1].start), "no edit left to clear")
        }
    }
}
