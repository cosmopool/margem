package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Block 6 (Leitura) fuzz: random month start days, week starts, limits, step edits and "today"
// against BudgetMonth.idealByDay (D44), and random transactions, installments, refunds, edits,
// deletions and aliases against the Leitura reads (spec §7.5: history, tagRanking, merchantRanking,
// spentByDay, budgetDetail lines). Fixed seeds, bounded iterations, a real SQLite file per Db,
// wall-clock timeouts. Expected values come from the spec (§3–§6, §7.5) and the KDoc of Db.kt /
// Types.kt applied to public outputs (the weeks list, transaction(id).installments), never from
// the implementation. Every failure message carries the seed, the case, the step and the input.

private val L_TODAY = LocalDate(2026, 10, 16)
private const val L_PKG = "com.banco.leitura"
private const val L_NOW = 1_760_600_000_000L

/** Limits up to R$ 10 milhões must be accepted (createBudget/setLimit refuse only ≤ 0 and "above the maximum"). */
private const val L_SANE = 1_000_000_000L

private fun lRealTime(block: suspend () -> Unit) = runTest(timeout = 300.seconds) {
    withContext(Dispatchers.Default) { withTimeout(240.seconds) { block() } }
}

private suspend fun withLDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-lfuzz-${Random.Default.nextLong().toULong()}.db").toString()
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

private suspend fun <T> lNoThrow(where: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fail("$where threw $e", e)
}

private fun YearMonth.lShift(n: Int): YearMonth = if (n >= 0) plus(n, DateTimeUnit.MONTH) else minus(-n, DateTimeUnit.MONTH)

private fun LocalDate.lDays(n: Int): LocalDate = plus(n, DateTimeUnit.DAY)

/** A random date of 2024..2028, month ends and 29 Feb included. */
private fun lDate(rnd: Random): LocalDate = LocalDate(rnd.nextInt(2024, 2029), rnd.nextInt(1, 13), 1).lDays(rnd.nextInt(0, 31))

private fun lStartDay(rnd: Random): Int = if (rnd.nextInt(3) == 0) listOf(28, 29, 30, 31).random(rnd) else rnd.nextInt(1, 32)

private fun lLimit(rnd: Random): Long = when (rnd.nextInt(13)) {
    0 -> 1L
    1 -> rnd.nextLong(2, 100)
    2 -> listOf(3L, 4L, 5L, 6L, 7L, 28L, 29L, 30L, 31L, 100L, 101L).random(rnd)
    3 -> 10_000L
    4, 5, 6 -> rnd.nextLong(1, 1_000_000)
    7, 8 -> rnd.nextLong(1, L_SANE)
    9 -> L_SANE
    10 -> 999_999_999_999L
    11 -> 1_000_000_000_000_000L
    else -> 99_999_999_999_999_999L
}

private fun lTime(rnd: Random): LocalTime = LocalTime(rnd.nextInt(0, 24), rnd.nextInt(0, 60))

private fun lShowWeeks(bm: BudgetMonth): String =
    bm.weeks.joinToString(prefix = "[", postfix = "]") { "${it.start}..${it.end} step=${it.step.v}${if (it.edited) "*" else ""}" }

/**
 * The properties the KDoc of [BudgetMonth.idealByDay] states, computed by hand from [BudgetMonth.weeks]
 * with the spec §5 formula: day d of week j = Σ steps of weeks before j + step_j × (days of j up to d) ÷
 * days of j, rounded down. [today] in the window: the value at today == [BudgetMonth.ideal].
 */
private fun checkIdealByDay(where: String, bm: BudgetMonth, today: LocalDate?) {
    val by = bm.idealByDay
    if (bm.start == NO_DATE) {
        assertEquals(emptyList(), by, "$where: idealByDay empty when start is NO_DATE (KDoc)")
        return
    }
    val ctx = "$where month=${bm.month} window=${bm.start}..${bm.end} limit=${bm.limit.v} weeks=${lShowWeeks(bm)} idealByDay=${by.map { it.v }}"
    val days = bm.start.daysUntil(bm.end) + 1
    assertEquals(days, by.size, "$ctx: one value per day of the window (KDoc)")
    for (i in 1 until by.size) {
        assertTrue(by[i] >= by[i - 1], "$ctx: never decreases (KDoc), day ${bm.start.lDays(i)}: ${by[i - 1].v} → ${by[i].v}")
    }
    assertEquals(bm.limit, by.last(), "$ctx: last day = limit (KDoc)")
    assertEquals(bm.weeks.sumOf { it.step.v }, by.last().v, "$ctx: last day = Σ steps (KDoc)")

    // Weeks tile the window (spec §4: calendar weeks cut to the month), so every day has one.
    assertTrue(bm.weeks.isNotEmpty(), "$ctx: a month window has weeks")
    assertEquals(bm.start, bm.weeks.first().start, "$ctx: first week starts at the window start")
    assertEquals(bm.end, bm.weeks.last().end, "$ctx: last week ends at the window end")
    for ((a, b) in bm.weeks.zipWithNext()) assertEquals(a.end.lDays(1), b.start, "$ctx: weeks are consecutive")

    var closed = 0L
    var i = 0
    for (w in bm.weeks) {
        val wDays = w.start.daysUntil(w.end) + 1
        for (e in 1..wDays) {
            val d = w.start.lDays(e - 1)
            val expected = closed + w.step.v * e / wDays
            assertEquals(expected, by[i].v, "$ctx: day $d (week ${w.start}..${w.end}, $e of $wDays days) = closed weeks $closed + step ${w.step.v} × $e ÷ $wDays rounded down (KDoc, spec §5)")
            i++
        }
        closed += w.step.v
        assertEquals(closed, by[i - 1].v, "$ctx: value at the end of week ${w.start}..${w.end} = Σ steps of the weeks up to it")
    }

    if (today != null && today >= bm.start && today <= bm.end) {
        assertEquals(bm.ideal, by[bm.start.daysUntil(today)], "$ctx today=$today: idealByDay[today − start] == ideal (KDoc)")
    }
}

/** Todays around [bm]'s window: inside, at its edges, the days next to it and far from it. */
private fun lTodays(bm: BudgetMonth, rnd: Random): List<LocalDate> {
    if (bm.start == NO_DATE) return listOf(L_TODAY)
    val days = bm.start.daysUntil(bm.end) + 1
    return listOf(
        bm.start, bm.end, bm.start.lDays(rnd.nextInt(0, days)), bm.start.lDays(-1), bm.end.lDays(1),
        bm.start.lDays(-rnd.nextInt(30, 800)), bm.end.lDays(rnd.nextInt(30, 800)),
    )
}

// ---- Leitura model ----

private val L_TAGS = listOf("feira", "mercado", "casa", "cafe", "lazer", "saude", "transporte")

/** Merchant groups: bases distinct however spacing and case are ignored. */
private val L_BASES = listOf("PADARIA DO ZE", "MERCADO BOM PRECO", "POSTO SHELL 12", "FARMACIA VIDA", "LOJA 7", "CAFE")

/** Same merchant for the Db (spacing and case ignored, setAlias KDoc): case changes and doubled inner spaces. */
private fun lVariant(rnd: Random, base: String): String = when (rnd.nextInt(4)) {
    0 -> base
    1 -> base.lowercase()
    2 -> base.replace(" ", "  ")
    else -> base.map { if (rnd.nextBoolean()) it.lowercaseChar() else it }.joinToString("")
}

private fun lTagInput(rnd: Random, tag: String): String = when (rnd.nextInt(4)) {
    0 -> tag.uppercase()
    1 -> " $tag "
    2 -> tag.replaceFirstChar { it.uppercaseChar() }
    else -> tag
}

private class LTx(
    val id: TransactionId,
    var budget: BudgetId,
    var tags: Set<String>,
    var group: Int,
    var merchant: String,
    var installments: List<Installment>,
    /** The notice it was accepted from; 0 = manual. */
    val notice: NoticeId,
    /** The estorno registered on it; 0 = none. */
    var estorno: NoticeId = NoticeId(0),
)

private data class LRank(val total: Long, val count: Int)

class LeituraFuzzTest {

    // ---- idealByDay (D44) ----

    @Test
    fun `idealByDay follows the spec formula under random start days week starts limits step edits and todays`() = lRealTime {
        val seed = 0x1D44L
        val rnd = Random(seed)
        for (case in 0 until 30) withLDb { db ->
            var today = lDate(rnd)
            val startDay = lStartDay(rnd)
            val weekStart = DayOfWeek.entries.random(rnd)
            val head = "seed=$seed case=$case startDay=$startDay weekStart=$weekStart today=$today"
            if (startDay != 1) assertTrue(lNoThrow("$head setMonthStartDay") { db.setMonthStartDay(startDay, today) }, "$head: setMonthStartDay($startDay) in 1..31")
            lNoThrow("$head setWeekStart") { db.setWeekStart(weekStart, today) }
            assertEquals(startDay, db.settings().monthStartDay, "$head: settings start day")
            assertEquals(weekStart, db.settings().weekStart, "$head: settings week start")

            // Unknown budget: zero BudgetDetail, empty idealByDay.
            val unknown = lNoThrow("$head budgetDetail(unknown)") { db.budgetDetail(BudgetId(987_654), db.monthOf(today), today) }
            assertEquals(emptyList(), unknown.month.idealByDay, "$head: unknown budget has an empty idealByDay")

            val budgets = mutableListOf<BudgetId>()
            val nBudgets = rnd.nextInt(1, 4)
            var tries = 0
            while (budgets.size < nBudgets && tries++ < 10) {
                val limit = lLimit(rnd)
                val where = "$head createBudget(limit=$limit)"
                val preview = lNoThrow("$where previewBudget") { db.previewBudget(Cents(limit), today) }
                checkIdealByDay("$where previewBudget", preview, today)
                val id = lNoThrow(where) { db.createBudget("Verba $case-${budgets.size}", Cents(limit), today) }
                if (id.v == 0L) {
                    assertTrue(limit > L_SANE, "$where: refused a limit up to R$ 10 milhões")
                    assertEquals(emptyList(), preview.idealByDay, "$where: refused limit → zero preview (previewBudget KDoc)")
                    continue
                }
                val detail = db.budgetDetail(id, db.monthOf(today), today)
                assertEquals(preview.idealByDay, detail.month.idealByDay, "$where: previewBudget equals budgetDetail right after createBudget (KDoc)")
                checkIdealByDay("$where budgetDetail", detail.month, today)
                budgets += id
            }
            if (budgets.isEmpty()) return@withLDb

            var m0 = db.monthOf(today)
            db.ensureMonths(m0.lShift(3))
            fun months(): List<YearMonth> = listOf(m0.lShift(-6), m0.lShift(-1), m0, m0.lShift(1), m0.lShift(2), m0.lShift(3))

            /** budgetDetail and budgetsOverview at several todays: idealByDay is the same at every today, and right at each. */
            suspend fun checkAt(where: String, b: BudgetId, m: YearMonth, todays: List<LocalDate>) {
                var first: List<Cents>? = null
                for (t in todays) {
                    val w = "$where budgetDetail(${b.v}, $m, today=$t)"
                    val bm = lNoThrow(w) { db.budgetDetail(b, m, t) }.month
                    checkIdealByDay(w, bm, t)
                    if (first == null) first = bm.idealByDay
                    else assertEquals(first, bm.idealByDay, "$w: idealByDay doesn't depend on today (day d = the rule with today = d, KDoc)")
                    val ov = lNoThrow("$w budgetsOverview") { db.budgetsOverview(m, t, BudgetOrder.RISK) }.firstOrNull { it.id == b }
                    if (ov != null) assertEquals(bm.idealByDay, ov.month.idealByDay, "$w: budgetsOverview and budgetDetail give the same idealByDay")
                }
            }

            for (step in 0 until 30) {
                val b = budgets.random(rnd)
                val m = months().drop(2).random(rnd) // months with a limit row: m0..m0+3
                val bm = db.budgetDetail(b, m, today).month
                val where = "$head step=$step budget=${b.v} month=$m weeks=${lShowWeeks(bm)}"
                // Without weeks (a zero month) only the writes that don't pick a week.
                val op = if (bm.weeks.isEmpty()) listOf(6, 8, 9).random(rnd) else rnd.nextInt(10)
                when (op) {
                    0, 1, 2, 3 -> { // setStep
                        val w = bm.weeks.random(rnd)
                        if (w.locked) {
                            val v = rnd.nextLong(0, maxOf(1L, bm.limit.v))
                            assertFalse(lNoThrow("$where setStep") { db.setStep(b, m, w.start, Cents(v)) }, "$where: setStep(${w.start}, $v) on the locked week refused (Week.locked KDoc)")
                        } else if (rnd.nextInt(6) == 0) {
                            assertFalse(lNoThrow("$where setStep") { db.setStep(b, m, w.start, Cents(w.max.v + 1)) }, "$where: setStep(${w.start}, max + 1 = ${w.max.v + 1}) refused (Week.max KDoc)")
                        } else {
                            val v = when (rnd.nextInt(5)) {
                                0 -> 0L
                                1 -> w.max.v
                                2 -> minOf(w.max.v, w.step.v + 1)
                                else -> rnd.nextLong(0, w.max.v + 1)
                            }
                            assertTrue(lNoThrow("$where setStep") { db.setStep(b, m, w.start, Cents(v)) }, "$where: setStep(${w.start}, $v) with max ${w.max.v} accepted (Week.max KDoc)")
                        }
                    }
                    4, 5 -> { // clearStep
                        val edited = bm.weeks.filter { it.edited }
                        if (edited.isNotEmpty() && rnd.nextInt(4) != 0) {
                            val w = edited.random(rnd)
                            assertTrue(lNoThrow("$where clearStep") { db.clearStep(b, m, w.start) }, "$where: clearStep(${w.start}) of an edited week (clearStep KDoc)")
                        } else {
                            val w = bm.weeks.filter { !it.edited }.random(rnd)
                            assertFalse(lNoThrow("$where clearStep") { db.clearStep(b, m, w.start) }, "$where: clearStep(${w.start}) of a week without edit (clearStep KDoc)")
                        }
                    }
                    6 -> { // setLimit from today's month on
                        val limit = lLimit(rnd)
                        val ok = lNoThrow("$where setLimit($limit)") { db.setLimit(b, Cents(limit), today) }
                        if (limit <= L_SANE) assertTrue(ok, "$where: setLimit($limit) accepted")
                    }
                    7 -> { // spending never changes the plan (spec §4)
                        val before = db.budgetDetail(b, m, today).month.idealByDay
                        val days = bm.start.daysUntil(bm.end) + 1
                        val form = TransactionForm(
                            budget = b, tags = listOf("casa"), total = Cents(rnd.nextLong(1, 10_000_000)),
                            installments = listOf(1, 1, 2, 12).random(rnd), merchant = "LOJA ${rnd.nextInt(50)}",
                            date = bm.start.lDays(rnd.nextInt(0, days)), time = lTime(rnd),
                        )
                        assertNotEquals(0L, lNoThrow("$where addManual($form)") { db.addManual(form).transaction }.v, "$where: addManual($form) accepted")
                        assertEquals(before, db.budgetDetail(b, m, today).month.idealByDay, "$where: a purchase of ${form.total.v} on ${form.date} doesn't change idealByDay (spec §4: the plan doesn't change with spending)")
                    }
                    8 -> { // week start change: edits from today's month on are deleted, months recomputed
                        val ws = DayOfWeek.entries.random(rnd)
                        lNoThrow("$where setWeekStart($ws)") { db.setWeekStart(ws, today) }
                    }
                    else -> { // month start change, and a new today
                        val day = lStartDay(rnd)
                        today = if (rnd.nextBoolean()) today else today.lDays(rnd.nextInt(-40, 41))
                        val current = db.settings().monthStartDay
                        val ok = lNoThrow("$where setMonthStartDay($day, $today)") { db.setMonthStartDay(day, today) }
                        if (day != current) assertTrue(ok, "$where: setMonthStartDay($day, $today) in 1..31")
                        m0 = db.monthOf(today)
                        db.ensureMonths(m0.lShift(3))
                    }
                }
                val after = db.budgetDetail(b, m, today).month
                checkAt("$where after", b, m, lTodays(after, rnd).shuffled(rnd).take(3))
            }

            // Final sweep: every budget, every month, every kind of today.
            for (b in budgets) for (m in months()) {
                val bm = db.budgetDetail(b, m, today).month
                checkAt("$head final", b, m, lTodays(bm, rnd))
            }
            // Every day of one month as today: ideal of that day == idealByDay at it.
            val b = budgets.random(rnd)
            val bm = db.budgetDetail(b, m0, today).month
            if (bm.start != NO_DATE) {
                val days = bm.start.daysUntil(bm.end) + 1
                for (i in 0 until days) {
                    val t = bm.start.lDays(i)
                    val at = db.budgetDetail(b, m0, t).month
                    assertEquals(bm.idealByDay, at.idealByDay, "$head sweep budget=${b.v} month=$m0 today=$t: idealByDay doesn't depend on today")
                    assertEquals(at.ideal, at.idealByDay[i], "$head sweep budget=${b.v} month=$m0 today=$t weeks=${lShowWeeks(at)}: ideal == idealByDay[today − start] (KDoc)")
                }
            }
        }
    }

    // ---- Leitura reads (spec §7.5) ----

    @Test
    fun `Leitura reads match a model of installments refunds tags and aliases under random edits`() = lRealTime {
        val seed = 0x7E17L
        val rnd = Random(seed)
        for (case in 0 until 8) withLDb { db ->
            val today = L_TODAY.lDays(rnd.nextInt(-200, 200))
            val startDay = lStartDay(rnd)
            val weekStart = DayOfWeek.entries.random(rnd)
            val head = "seed=$seed case=$case startDay=$startDay weekStart=$weekStart today=$today"
            if (startDay != 1) assertTrue(db.setMonthStartDay(startDay, today), "$head: setup setMonthStartDay")
            db.setWeekStart(weekStart, today)
            assertTrue(db.addSource(L_PKG, "Banco Leitura", L_NOW), "$head: setup addSource")

            suspend fun captureAll(prefix: String, count: Int, from: Int, text: (Int) -> String): List<NoticeId> {
                repeat(count) { i ->
                    val at = L_NOW + (from + i) * 60_000L
                    val raw = RawNotice(packageName = L_PKG, appLabel = "Banco Leitura", key = "$prefix|$i", postedAt = at, whenMs = at, title = "Banco", text = text(i))
                    assertTrue(capture(db, raw), "$head: setup capture $prefix|$i")
                }
                val byKey = db.pending().associate { it.raw.key to it.id }
                return List(count) { i -> byKey["$prefix|$i"] ?: fail("$head: setup notice $prefix|$i not pending") }
            }
            val purchaseNotices = captureAll("compra", 30, 0) { i -> "Compra de R$ ${5 + i},90 em LOJA $i" }
            val estornos = captureAll("estorno", 20, 1000) { i -> "Estorno de R$ ${10 + i},00 efetuado" }

            val budgets = List(rnd.nextInt(2, 5)) { k ->
                db.createBudget("Verba $k", Cents(rnd.nextLong(1, L_SANE)), today).also { assertNotEquals(0L, it.v, "$head: setup createBudget $k") }
            }
            val m0 = db.monthOf(today)
            val lastMonth = m0.lShift(14)
            db.ensureMonths(lastMonth)

            val txs = LinkedHashMap<TransactionId, LTx>()
            val pendingPurchases = purchaseNotices.toMutableSet()
            /** Estorno → what it cancels: a transaction, or a pending purchase notice (both leave and nothing counts). */
            val estornoOnTx = HashMap<NoticeId, TransactionId>()
            val estornoOnNotice = HashMap<NoticeId, NoticeId>()
            val aliases = HashMap<Int, String>() // group → alias
            val merchantTexts = HashMap<Int, MutableSet<String>>() // group → every merchant text used
            var aliasVersion = 0

            fun freeEstornos() = estornos.filter { it !in estornoOnTx && it !in estornoOnNotice }

            fun randomForm(): Pair<TransactionForm, Int> {
                val group = rnd.nextInt(L_BASES.size)
                val merchant = lVariant(rnd, L_BASES[group])
                val n = when (rnd.nextInt(20)) {
                    in 0..9 -> 1
                    in 10..16 -> rnd.nextInt(2, 13)
                    17, 18 -> 24
                    else -> if (rnd.nextInt(4) == 0) 400 else 36
                }
                val total = when (rnd.nextInt(10)) {
                    0 -> rnd.nextLong(1, n.toLong() + 1) // fewer cents than installments: some are R$ 0,00
                    1 -> rnd.nextLong(1, 1_000_000_000_000L)
                    else -> rnd.nextLong(1, 500_000)
                }
                val tags = List(rnd.nextInt(1, 4)) { lTagInput(rnd, L_TAGS.random(rnd)) }
                val alias = if (rnd.nextInt(8) == 0) "Apelido $group v${aliasVersion++}" else ""
                val form = TransactionForm(
                    budget = budgets.random(rnd), tags = tags, total = Cents(total), installments = n, merchant = merchant,
                    date = today.lDays(rnd.nextInt(-420, 120)), time = lTime(rnd), alias = alias,
                )
                return form to group
            }

            /** Reads [id]'s installments back (public output) and checks spec §6: n of them, Σ = total. */
            suspend fun installmentsOf(where: String, id: TransactionId, form: TransactionForm): List<Installment> {
                val d = lNoThrow("$where transaction(${id.v})") { db.transaction(id) }
                assertEquals(form.installments, d.installments.size, "$where: one installment per parcela")
                assertEquals(form.total.v, d.installments.sumOf { it.cents.v }, "$where: Σ installments = total")
                return d.installments
            }

            fun applyForm(form: TransactionForm, group: Int) {
                merchantTexts.getOrPut(group) { mutableSetOf() } += form.merchant
                if (form.alias.isNotBlank()) aliases[group] = form.alias
            }

            fun tagSet(form: TransactionForm) = form.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

            fun counted(): List<LTx> = txs.values.filter { it.estorno.v == 0L }

            suspend fun checkReads(where: String, from: LocalDate, to: LocalDate) {
                val w = "$where range=$from..$to"
                val inRange = counted().mapNotNull { t ->
                    val ins = t.installments.filter { it.date >= from && it.date <= to }
                    if (ins.isEmpty()) null else t to ins
                }
                // spentByDay: installments on their own date, refunds out, days without spending absent.
                val wantDays = HashMap<LocalDate, Long>()
                for ((_, ins) in inRange) for (i in ins) wantDays[i.date] = (wantDays[i.date] ?: 0L) + i.cents.v
                val wantDayMap = wantDays.filterValues { it > 0 }.mapValues { Cents(it.value) }
                val days = lNoThrow("$w spentByDay") { db.spentByDay(from, to) }
                assertEquals(wantDayMap, days, "$w: spentByDay = Σ installments not refunded per date, days without spending absent")

                // tagRanking: each transaction counts whole in each of its tags.
                val wantTags = HashMap<String, LRank>()
                for ((t, ins) in inRange) for (g in t.tags) {
                    val r = wantTags[g] ?: LRank(0, 0)
                    wantTags[g] = LRank(r.total + ins.sumOf { it.cents.v }, r.count + 1)
                }
                val tags = lNoThrow("$w tagRanking") { db.tagRanking(from, to) }
                assertEquals(tags.size, tags.map { it.name }.toSet().size, "$w: tagRanking has one row per tag: $tags")
                assertEquals(wantTags, tags.associate { it.name to LRank(it.total.v, it.count) }, "$w: tagRanking totals and counts")
                for ((a, b) in tags.zipWithNext()) assertTrue(a.total >= b.total, "$w: tagRanking by value: $tags")
                val spent = days.values.sumOf { it.v }
                assertTrue(tags.sumOf { it.total.v } >= spent, "$w: Σ tags ≥ spent (a transaction counts in each tag, spec §7.5)")

                // merchantRanking: grouped by merchant (spacing and case ignored), named by alias.
                val wantMerchants = HashMap<Int, LRank>()
                for ((t, ins) in inRange) {
                    val r = wantMerchants[t.group] ?: LRank(0, 0)
                    wantMerchants[t.group] = LRank(r.total + ins.sumOf { it.cents.v }, r.count + 1)
                }
                val merchants = lNoThrow("$w merchantRanking") { db.merchantRanking(from, to) }
                val gotMerchants = HashMap<Int, LRank>()
                for (row in merchants) {
                    val group = aliases.entries.firstOrNull { it.value == row.name }?.key
                        ?: merchantTexts.entries.firstOrNull { row.name in it.value && it.key !in aliases }?.key
                        ?: fail("$w: merchantRanking row ${row.name} is neither an alias nor a merchant text of an unaliased group; aliases=$aliases rows=$merchants")
                    assertFalse(group in gotMerchants, "$w: one merchantRanking row per merchant: $merchants")
                    gotMerchants[group] = LRank(row.total.v, row.count)
                }
                assertEquals(wantMerchants, gotMerchants, "$w: merchantRanking totals and counts by merchant group; rows=$merchants")
                for ((a, b) in merchants.zipWithNext()) assertTrue(a.total >= b.total, "$w: merchantRanking by value: $merchants")
                assertEquals(spent, merchants.sumOf { it.total.v }, "$w: Σ merchants = Σ spentByDay (one merchant per transaction)")
                assertEquals(inRange.size, merchants.sumOf { it.count }, "$w: Σ merchant counts = transactions with an installment in the range")
            }

            suspend fun checkMonth(where: String, m: YearMonth) {
                val w = "$where month=$m"
                val history = lNoThrow("$w history") { db.history(m, m) }
                var sumHistory = 0L
                var window: Pair<LocalDate, LocalDate>? = null
                for (b in budgets) {
                    val bd = lNoThrow("$w budgetDetail(${b.v})") { db.budgetDetail(b, m, today) }
                    val bm = bd.month
                    checkIdealByDay("$w budgetDetail(${b.v})", bm, today)
                    val rows = history.filter { it.budget == b }
                    val hasRow = m >= bd.firstMonth && m <= lastMonth
                    if (hasRow) assertEquals(1, rows.size, "$w: history has one row for budget ${b.v} (firstMonth=${bd.firstMonth}): $history")
                    if (rows.isEmpty()) continue
                    val row = rows.single()
                    window = bm.start to bm.end
                    val want = txs.values.filter { it.budget == b && it.estorno.v == 0L }
                        .sumOf { t -> t.installments.filter { it.date >= bm.start && it.date <= bm.end }.sumOf { it.cents.v } }
                    assertEquals(want, row.spent.v, "$w budget=${b.v} window=${bm.start}..${bm.end}: history spent = Σ installments not refunded in the month")
                    assertEquals(bm.spent, row.spent, "$w budget=${b.v}: history spent = budgetDetail spent")
                    assertEquals(bm.limit, row.limit, "$w budget=${b.v}: history limit = the month's own limit")
                    assertEquals(bm.spent.v, bd.lines.filter { !it.row.refunded }.sumOf { it.cents.v }, "$w budget=${b.v}: Σ lines not refunded = spent (BudgetDetail.lines KDoc)")
                    // Lines: every transaction of the budget with an installment in the month, refunded included.
                    val wantLines = txs.values.filter { it.budget == b }.flatMap { t ->
                        t.installments.filter { it.date >= bm.start && it.date <= bm.end }.map { Triple(t.id, it.date, it.cents) to (t.estorno.v != 0L) }
                    }.toSet()
                    val gotLines = bd.lines.map { Triple(it.row.id, it.date, it.cents) to it.row.refunded }.toSet()
                    assertEquals(wantLines, gotLines, "$w budget=${b.v}: budgetDetail lines are the installments dated in the month, on their own date")
                    sumHistory += row.spent.v
                }
                val win = window
                if (win != null && m >= budgets.maxOf { db.budgetDetail(it, m, today).firstMonth } && m <= lastMonth) {
                    // Every budget has a row: Σ history = Σ spentByDay over the month window.
                    val byDay = db.spentByDay(win.first, win.second).values.sumOf { it.v }
                    assertEquals(sumHistory, byDay, "$w window=${win.first}..${win.second}: Σ history spent of all budgets = Σ spentByDay")
                }
                // A multi-month history: each row's spent is its own month's.
                val wide = lNoThrow("$w history wide") { db.history(m.lShift(-2), m.lShift(2)) }
                for (row in wide) {
                    assertTrue(row.month >= m.lShift(-2) && row.month <= m.lShift(2), "$w: history row month ${row.month} inside the range")
                    val bm = db.budgetDetail(row.budget, row.month, today).month
                    val want = txs.values.filter { it.budget == row.budget && it.estorno.v == 0L }
                        .sumOf { t -> t.installments.filter { it.date >= bm.start && it.date <= bm.end }.sumOf { it.cents.v } }
                    assertEquals(want, row.spent.v, "$w: wide history row ${row.budget.v} ${row.month} spent")
                }
            }

            suspend fun checkAll(where: String) {
                val m = m0.lShift(rnd.nextInt(-14, 15))
                val bm = db.budgetDetail(budgets.random(rnd), m, today).month
                if (bm.start != NO_DATE) checkReads("$where monthWindow($m)", bm.start, bm.end)
                val a = today.lDays(rnd.nextInt(-500, 200))
                checkReads("$where random", a, a.lDays(rnd.nextInt(0, 400)))
                val dates = txs.values.flatMap { t -> t.installments.map { it.date } }
                if (dates.isNotEmpty()) {
                    val d = dates.random(rnd)
                    checkReads("$where oneDay", d, d)
                }
                checkReads("$where everything", LocalDate(1, 1, 1), LocalDate(9999, 12, 31))
                // Reversed range: from..to is empty.
                val r = today.lDays(rnd.nextInt(1, 300))
                val rw = "$where reversed range=$r..$today"
                assertEquals(emptyMap(), lNoThrow("$rw spentByDay") { db.spentByDay(r, today) }, "$rw: spentByDay of an empty range")
                assertEquals(emptyList(), lNoThrow("$rw tagRanking") { db.tagRanking(r, today) }, "$rw: tagRanking of an empty range")
                assertEquals(emptyList(), lNoThrow("$rw merchantRanking") { db.merchantRanking(r, today) }, "$rw: merchantRanking of an empty range")
                checkMonth(where, m0.lShift(rnd.nextInt(-3, 15)))
            }

            for (step in 0 until 90) {
                val where = "$head step=$step"
                when (rnd.nextInt(16)) {
                    in 0..4 -> { // addManual
                        val (form, group) = randomForm()
                        val w = "$where addManual($form)"
                        val id = lNoThrow(w) { db.addManual(form).transaction }
                        assertNotEquals(0L, id.v, "$w: a valid form is accepted")
                        txs[id] = LTx(id, form.budget, tagSet(form), group, form.merchant, installmentsOf(w, id, form), NoticeId(0))
                        applyForm(form, group)
                    }
                    5, 6 -> if (pendingPurchases.isNotEmpty()) { // accept
                        val n = pendingPurchases.random(rnd)
                        val (form, group) = randomForm()
                        val w = "$where accept(${n.v}, $form)"
                        val id = lNoThrow(w) { db.accept(n, form) }
                        assertNotEquals(0L, id.v, "$w: a pending notice with a valid form is accepted")
                        pendingPurchases -= n
                        txs[id] = LTx(id, form.budget, tagSet(form), group, form.merchant, installmentsOf(w, id, form), n)
                        applyForm(form, group)
                    }
                    7 -> if (txs.isNotEmpty()) { // edit: replaces everything, a registered estorno stays
                        val t = txs.values.random(rnd)
                        val (form, group) = randomForm()
                        val w = "$where edit(${t.id.v}, $form)"
                        assertTrue(lNoThrow(w) { db.edit(t.id, form) }, "$w: a valid edit is accepted")
                        t.budget = form.budget
                        t.tags = tagSet(form)
                        t.group = group
                        t.merchant = form.merchant
                        t.installments = installmentsOf(w, t.id, form)
                        applyForm(form, group)
                    }
                    8 -> if (txs.isNotEmpty()) { // delete: its notice and its estorno are pending again
                        val t = txs.values.random(rnd)
                        lNoThrow("$where deleteTransaction(${t.id.v})") { db.deleteTransaction(t.id) }
                        txs.remove(t.id)
                        if (t.notice.v != 0L) pendingPurchases += t.notice
                        if (t.estorno.v != 0L) estornoOnTx.remove(t.estorno)
                    }
                    9, 10 -> { // registerRefund on a transaction
                        val free = freeEstornos()
                        val open = txs.values.filter { it.estorno.v == 0L }
                        if (free.isNotEmpty() && open.isNotEmpty()) {
                            val e = free.random(rnd)
                            val t = open.random(rnd)
                            val w = "$where registerRefund(${e.v}, TransactionId(${t.id.v}))"
                            assertTrue(lNoThrow(w) { db.registerRefund(e, t.id) }, "$w: pending estorno on a transaction not refunded")
                            estornoOnTx[e] = t.id
                            t.estorno = e
                        }
                    }
                    11 -> { // registerRefund on a pending purchase notice: nothing counts, nothing changes in Leitura
                        val free = freeEstornos()
                        if (free.isNotEmpty() && pendingPurchases.isNotEmpty()) {
                            val e = free.random(rnd)
                            val n = pendingPurchases.random(rnd)
                            val w = "$where registerRefund(${e.v}, NoticeId(${n.v}))"
                            assertTrue(lNoThrow(w) { db.registerRefund(e, n) }, "$w: both pending and different")
                            estornoOnNotice[e] = n
                            pendingPurchases -= n
                        }
                    }
                    12, 13 -> { // undoRefund
                        val bound = (estornoOnTx.keys + estornoOnNotice.keys).toList()
                        if (bound.isNotEmpty()) {
                            val e = bound.random(rnd)
                            val w = "$where undoRefund(${e.v})"
                            assertTrue(lNoThrow(w) { db.undoRefund(e) }, "$w: a registered estorno")
                            estornoOnTx.remove(e)?.let { txs[it]?.estorno = NoticeId(0) }
                            estornoOnNotice.remove(e)?.let { pendingPurchases += it }
                        }
                    }
                    14 -> { // setAlias / remove it
                        val group = rnd.nextInt(L_BASES.size)
                        val variant = lVariant(rnd, L_BASES[group])
                        if (rnd.nextInt(3) == 0) {
                            lNoThrow("$where setAlias($variant, \"\")") { db.setAlias(variant, "") }
                            aliases.remove(group)
                        } else {
                            val alias = "Apelido $group v${aliasVersion++}"
                            lNoThrow("$where setAlias($variant, $alias)") { db.setAlias(variant, alias) }
                            aliases[group] = alias
                        }
                    }
                    else -> checkAll(where)
                }
                if (step % 10 == 9) checkAll("$where periodic")
            }
            checkAll("$head final")
            for (k in -3..14) checkMonth("$head final", m0.lShift(k))
        }
    }

    @Test
    fun `refunding and undoing a refund moves exactly that purchase out of and back into every Leitura read`() = lRealTime {
        val seed = 0xE570L
        val rnd = Random(seed)
        for (case in 0 until 6) withLDb { db ->
            val today = L_TODAY.lDays(rnd.nextInt(-100, 100))
            val startDay = lStartDay(rnd)
            val head = "seed=$seed case=$case startDay=$startDay today=$today"
            if (startDay != 1) assertTrue(db.setMonthStartDay(startDay, today), "$head: setup setMonthStartDay")
            assertTrue(db.addSource(L_PKG, "Banco Leitura", L_NOW), "$head: setup addSource")
            repeat(12) { i ->
                val at = L_NOW + i * 60_000L
                assertTrue(capture(db, RawNotice(packageName = L_PKG, appLabel = "Banco Leitura", key = "estorno|$i", postedAt = at, whenMs = at, title = "Banco", text = "Estorno de R$ ${10 + i},00 efetuado")), "$head: setup capture")
            }
            val estornos = db.pending().map { it.id }
            val budget = db.createBudget("Casa", Cents(1_000_000), today)
            assertNotEquals(0L, budget.v, "$head: setup createBudget")
            val ids = List(15) { k ->
                val form = TransactionForm(
                    budget = budget, tags = listOf(L_TAGS.random(rnd), L_TAGS.random(rnd)), total = Cents(rnd.nextLong(1, 300_000)),
                    installments = listOf(1, 1, 3, 10, 24).random(rnd), merchant = L_BASES.random(rnd),
                    date = today.lDays(rnd.nextInt(-300, 60)), time = lTime(rnd),
                )
                db.addManual(form).transaction.also { assertNotEquals(0L, it.v, "$head: setup addManual $k $form") }
            }
            val from = LocalDate(1, 1, 1)
            val to = LocalDate(9999, 12, 31)
            suspend fun snapshot() = Triple(db.spentByDay(from, to), db.tagRanking(from, to).toSet(), db.merchantRanking(from, to).toSet())

            for (step in 0 until 12) {
                val t = ids.random(rnd)
                val e = estornos[step]
                val w = "$head step=$step registerRefund(${e.v}, TransactionId(${t.v}))"
                val before = snapshot()
                val inst = db.transaction(t).installments
                assertTrue(lNoThrow(w) { db.registerRefund(e, t) }, "$w: accepted")
                val (days, tags, merchants) = snapshot()
                // spentByDay drops exactly the purchase's installments, on their own dates.
                val want = HashMap(before.first.mapValues { it.value.v })
                for (i in inst) want[i.date] = (want[i.date] ?: 0L) - i.cents.v
                assertEquals(want.filterValues { it != 0L }.mapValues { Cents(it.value) }, days, "$w: spentByDay without the refunded installments $inst")
                assertEquals(before.second.sumOf { it.count } - db.transaction(t).row.tags.size, tags.sumOf { it.count }, "$w: tag counts drop by one per tag of the purchase")
                assertEquals(before.third.sumOf { it.total.v } - inst.sumOf { it.cents.v }, merchants.sumOf { it.total.v }, "$w: merchant totals drop by the purchase")
                assertTrue(lNoThrow("$w undoRefund") { db.undoRefund(e) }, "$w: undoRefund accepted")
                assertEquals(before, snapshot(), "$w: undoRefund brings every read back")
            }
        }
    }
}
