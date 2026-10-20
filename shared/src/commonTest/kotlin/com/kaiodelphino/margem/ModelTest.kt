package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Model-based test (D47). Random action sequences (fixed seeds, bounded) run against the real Db and against a
// naive in-memory model written from the spec (§2–§7) and the architecture rules it points to (§5, D7–D11, D41,
// D43). After every step the public reads of both must be equal. The model is plain lists and maps: no SQL and
// nothing `internal` of the shared module; it computes months, weeks, steps, pace and installments on its own.
// Where the spec is silent, the model follows the public KDoc of the Db (orderings, the merchant key) and says so.

private const val MT_NU = "com.nu.production"
/** A second app, a source only while the run says so. Its texts carry no R$ value: nothing is read from them. */
private const val MT_IPE = "br.com.ipe"
private val MT_TZ = TimeZone.of("America/Sao_Paulo")
private val MT_DAY0 = LocalDate(2026, 10, 14)
private val MT_UNKNOWN_TX = TransactionId(987_654)
private val MT_UNKNOWN_BUDGET = BudgetId(987_654)
private val MT_MERCHANTS = listOf("LOJA X", "PADARIA DO ZE", "MERCADO BOM PRECO", "FARMACIA SAO JOAO")
/** Form merchants: the notice ones, the same keys spelled with other spacing and case, a new one and a blank one. */
private val MT_FORM_MERCHANTS = MT_MERCHANTS + listOf("loja x", " Padaria  do ze ", "POSTO CENTRAL", "")
private val MT_OTHER_TEXTS = listOf("Seu cartão virtual foi criado.", "Confira as novidades do app.")
private val MT_TAGS = listOf("padaria", " Mercado ", "CAFE", "feira", "lazer", "Transporte")
private val MT_NAMES = listOf("Mercado", "Casa", "Lazer", "Transporte", "Saude", "mercado", "CASA", "  ", " Pets ")

private val DAY = DateTimeUnit.DAY

// ---- The model: what the spec says the app holds ----

/** A captured notice. [parsed] is known because the test wrote the text (Nubank's purchase/estorno pushes). */
private data class MtNotice(val id: NoticeId, val raw: RawNotice, val parsed: Parsed, val dismissed: Boolean)

private data class MtBudget(
    val id: BudgetId,
    val name: String,
    /** Spec §3: each month keeps the limit valid in it. Set points: a month's limit is the latest point at or before it; none before the first. */
    val limits: Map<YearMonth, Long>,
    /** Edited steps by the week's start date (D9). */
    val edits: Map<LocalDate, Long>,
) {
    fun limitAt(m: YearMonth): Long? = limits.filterKeys { it <= m }.maxByOrNull { it.key }?.value
}

private data class MtTx(
    val id: TransactionId,
    val notice: NoticeId,
    val budget: BudgetId,
    val merchant: String,
    val total: Long,
    val date: LocalDate,
    val time: LocalTime,
    val tags: Set<String>,
    val corrected: Boolean,
    val inst: List<Installment>,
)

private data class MtModel(
    val settings: Settings = Settings(),
    val sources: Map<String, Source> = emptyMap(),
    val notices: List<MtNotice> = emptyList(),
    /** In the manual order (§7.1): creation appends, reorder replaces. */
    val budgets: List<MtBudget> = emptyList(),
    val txs: List<MtTx> = emptyList(),
    val tags: Set<String> = emptySet(),
    /** Estorno notice → the transaction it cancels, or the pending notice it cancels (§6). */
    val refunds: Map<NoticeId, Pair<TransactionId, NoticeId>> = emptyMap(),
    /** Merchant key → alias (§2.10). */
    val aliases: Map<String, String> = emptyMap(),
    /** Ids handed out, never handed out again (D38). */
    val usedBudgetIds: Set<BudgetId> = emptySet(),
    val usedTxIds: Set<TransactionId> = emptySet(),
) {
    val day get() = settings.monthStartDay

    fun budget(id: BudgetId) = budgets.firstOrNull { it.id == id }

    fun notice(id: NoticeId) = notices.firstOrNull { it.id == id }

    fun refunded(id: TransactionId) = refunds.values.any { it.first == id }

    /** Architecture §5: processed when a transaction or an estorno points at it; derived, never stored. */
    fun status(n: MtNotice): NoticeStatus = when {
        txs.any { it.notice == n.id } || n.id in refunds || refunds.values.any { it.second == n.id } -> NoticeStatus.PROCESSED
        n.dismissed -> NoticeStatus.DISMISSED
        else -> NoticeStatus.PENDING
    }

    fun noticeRow(n: MtNotice) = NoticeRow(
        id = n.id, raw = n.raw, parsed = n.parsed, alias = aliases[mtKey(n.parsed.merchant)] ?: "", status = status(n),
        isNew = n.raw.postedAt > settings.pautaSeenAt, looksLikeAd = n.parsed.cents.v == 0L && n.parsed.merchant.isEmpty(),
    )

    /**
     * One row of Lançamentos; [today] picks the installment of today's month ("Parcela 3 de 10", §7.4). Spec §6 puts one
     * installment in each month; [MtRun.check] fails when two share one (see ModelTest.installmentsKeepOneMonthEachAfterStartDayChange).
     */
    fun row(t: MtTx, today: LocalDate?) = TransactionRow(
        id = t.id, notice = t.notice, budget = t.budget, budgetName = budget(t.budget)?.name ?: "", merchant = t.merchant,
        alias = aliases[mtKey(t.merchant)] ?: "", total = Cents(t.total), installments = t.inst.size, date = t.date, time = t.time,
        tags = t.tags.sorted(), estorno = refunds.entries.firstOrNull { it.value.first == t.id }?.key ?: NoticeId(0), corrected = t.corrected,
        now = if (today == null) Installment() else t.inst.firstOrNull { mtMonthOf(it.date, day) == mtMonthOf(today, day) } ?: Installment(),
    )

    /** Spec §3: Gasto do mês = installments of the budget dated in the month, without the refunded purchases. */
    fun spentOn(budget: BudgetId, from: LocalDate, to: LocalDate): Map<LocalDate, Long> {
        val out = LinkedHashMap<LocalDate, Long>()
        for (t in txs) if (t.budget == budget && !refunded(t.id)) for (i in t.inst) if (i.date in from..to) out[i.date] = (out[i.date] ?: 0L) + i.cents.v
        return out
    }

    fun month(b: MtBudget?, month: YearMonth, today: LocalDate): BudgetMonth {
        val limit = b?.limitAt(month)
        val (from, to) = mtWindow(month, day)
        return mtBudgetMonth(
            month, day, settings.weekStart, today, limit ?: 0L, limit != null, b?.edits ?: emptyMap(),
            if (b == null) emptyMap() else spentOn(b.id, from, to),
        )
    }

    fun detail(id: BudgetId, month: YearMonth, today: LocalDate): BudgetDetail {
        val b = budget(id) ?: return BudgetDetail()
        val bm = month(b, month, today)
        // §7.1: the month's lines with the running sum; refunded ones are listed (struck) and don't add.
        val lines = txs.filter { it.budget == id }
            .flatMap { t -> t.inst.filter { it.date in bm.start..bm.end }.map { MonthLine(row(t, null), it.k, it.date, it.cents) } }
            .sortedWith(compareBy<MonthLine> { it.date }.thenBy { it.row.time }.thenBy { it.row.id.v })
        return BudgetDetail(id, b.name, bm, lines, b.limits.keys.min())
    }

    /** Lançar / manual / editar (§2.4, §6, §7.3): a refused form gives null. */
    fun txOf(id: TransactionId, notice: NoticeId, f: TransactionForm): MtTx? {
        val tags = mtClean(f.tags)
        if (budget(f.budget) == null || tags.isEmpty() || f.total.v <= 0 || f.installments !in 1..400) return null
        val merchant = f.merchant.trim()
        val p = notice(notice)?.parsed
        // Glossary, Emenda: value, merchant, date or time changed from what the notice said.
        val corrected = p != null && (p.cents != f.total || p.merchant != merchant || p.date != f.date || p.time != f.time)
        return MtTx(id, notice, f.budget, merchant, f.total.v, f.date, f.time, tags.toSet(), corrected, mtInstallments(f.date, f.total.v, f.installments, day))
    }

    fun saveTx(t: MtTx, f: TransactionForm): MtModel {
        val key = mtKey(t.merchant)
        return copy(
            txs = txs.filter { it.id != t.id } + t,
            tags = tags + t.tags,
            // §2.10: a blank alias leaves the alias as it is.
            aliases = if (f.alias.isNotBlank() && key.isNotEmpty()) aliases + (key to f.alias.trim()) else aliases,
            usedTxIds = usedTxIds + t.id,
        )
    }

    /** §2.8: deleting transactions deletes the estornos on them; their notices go back to the Pauta (derived). */
    fun withoutTxs(gone: Set<TransactionId>) = copy(txs = txs.filter { it.id !in gone }, refunds = refunds.filterValues { it.first !in gone })

    /** §3, §4: the new limit from today's month on; months where the edits now sum more lose their edits. Null = refused. */
    fun withLimit(id: BudgetId, limit: Long, today: LocalDate): MtModel? {
        if (limit <= 0 || budget(id) == null) return null
        val cur = mtMonthOf(today, day)
        val from = mtWindow(cur, day).first
        return copy(budgets = budgets.map { b ->
            if (b.id != id) return@map b
            val over = b.edits.filterKeys { it >= from }.entries.groupBy { mtMonthOf(it.key, day) }.filterValues { e -> e.sumOf { it.value } > limit }.keys
            b.copy(limits = b.limits.filterKeys { it < cur } + (cur to limit), edits = b.edits.filterKeys { it < from || mtMonthOf(it, day) !in over })
        })
    }

    /**
     * §3, §4, D43: edits from the current month on are deleted. "Current month" by the old or the new day, whichever starts
     * earlier (the spec doesn't say which; Db.setMonthStartDay's KDoc). Each budget keeps a limit in today's new month: the
     * one of the old current month, unless it had one there.
     */
    fun withMonthStartDay(newDay: Int, today: LocalDate): MtModel {
        if (newDay == day) return this
        val was = mtMonthOf(today, day)
        val now = mtMonthOf(today, newDay)
        val cut = minOf(mtWindow(was, day).first, mtWindow(now, newDay).first)
        return copy(settings = settings.copy(monthStartDay = newDay), budgets = budgets.map { b ->
            val keep = b.limitAt(was)
            b.copy(edits = b.edits.filterKeys { it < cut }, limits = if (keep != null && b.limitAt(now) == null) b.limits + (now to keep) else b.limits)
        })
    }

    fun withWeekStart(w: DayOfWeek, today: LocalDate): MtModel {
        if (w == settings.weekStart) return this
        val cut = mtWindow(mtMonthOf(today, day), day).first
        return copy(settings = settings.copy(weekStart = w), budgets = budgets.map { b -> b.copy(edits = b.edits.filterKeys { it < cut }) })
    }

    /** Anotação automática (§2.1, Db `capture` KDoc): noise, other apps and re-posts are not saved. */
    fun captures(raw: RawNotice): Boolean {
        val content = mtContent(raw)
        if (content.all(String::isBlank) || raw.packageName !in sources) return false
        val sameKey = notices.filter { it.raw.key == raw.key }
        if (raw.whenMs != 0L && sameKey.any { mtContent(it.raw) == content && it.raw.whenMs == raw.whenMs }) return false
        val latest = sameKey.maxWithOrNull(compareBy<MtNotice> { it.raw.postedAt }.thenBy { it.id.v }) ?: return true
        return !(mtContent(latest.raw) == content && abs(raw.postedAt - latest.raw.postedAt) < DUPLICATE_WINDOW_MS)
    }

    fun edits() = budgets.associate { it.id to it.edits }
}

private fun mtContent(r: RawNotice) = listOf(r.title, r.text, r.bigText, r.subText, r.textLines)

/** Same merchant across spacing and case (Db.setAlias / suggestion KDoc). */
private fun mtKey(merchant: String) = merchant.filterNot(Char::isWhitespace).lowercase()

private fun mtClean(tags: List<String>) = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

private fun YearMonth.mtShift(n: Int): YearMonth = if (n >= 0) plus(n, DateTimeUnit.MONTH) else minus(-n, DateTimeUnit.MONTH)

/** Spec §3: from day D to the eve of day D of the next month; a D the month doesn't have is its last day. */
private fun mtWindow(m: YearMonth, day: Int): Pair<LocalDate, LocalDate> {
    val next = m.mtShift(1)
    return LocalDate(m.year, m.month, minOf(day, m.numberOfDays)) to LocalDate(next.year, next.month, minOf(day, next.numberOfDays)).minus(1, DAY)
}

private fun mtMonthOf(d: LocalDate, day: Int): YearMonth {
    val ym = YearMonth(d.year, d.month)
    return if (d >= mtWindow(ym, day).first) ym else ym.mtShift(-1)
}

/**
 * Spec §6, D7: installment k counts in the k-th month from the purchase's: dated purchase + (k−1) months, moved to the
 * nearest day inside that month when it falls outside (start days 29–31). Each is total ÷ n rounded down; the last takes the cents.
 */
private fun mtInstallments(date: LocalDate, total: Long, n: Int, day: Int): List<Installment> {
    val first = mtMonthOf(date, day)
    val each = total / n
    return (1..n).map { k ->
        val (from, to) = mtWindow(first.mtShift(k - 1), day)
        Installment(k, date.plus(k - 1, DateTimeUnit.MONTH).coerceIn(from, to), Cents(if (k == n) total - each * (n - 1) else each))
    }
}

/** Everything about one budget in one month, from spec §3–§5 (architecture §6). [hasRow]: the budget has a limit in the month. */
private fun mtBudgetMonth(
    month: YearMonth, day: Int, weekStart: DayOfWeek, today: LocalDate,
    limit: Long, hasRow: Boolean, edits: Map<LocalDate, Long>, spentOn: Map<LocalDate, Long>,
): BudgetMonth {
    val (start, end) = mtWindow(month, day)
    // §4: calendar weeks from the week start day, cut to the month; the first and last may be partial.
    val ws = ArrayList<LocalDate>()
    val we = ArrayList<LocalDate>()
    var s = start
    while (s <= end) {
        var e = s
        while (e < end && e.plus(1, DAY).dayOfWeek != weekStart) e = e.plus(1, DAY)
        ws.add(s)
        we.add(e)
        s = e.plus(1, DAY)
    }
    val n = ws.size
    val days = List(n) { ws[it].daysUntil(we[it]) + 1 }
    var edited = List(n) { ws[it] in edits }
    // §4: one week always stays automatic, and no automatic week goes below zero. Edits that break this only come from a
    // past month recalculated with a new start day (D10); the spec doesn't say what then: like budgetMonth's KDoc, none counts.
    if (edited.all { it } || (0 until n).filter { edited[it] }.sumOf { edits.getValue(ws[it]) } > limit) edited = List(n) { false }
    val editedSum = (0 until n).filter { edited[it] }.sumOf { edits.getValue(ws[it]) }
    // §4: automatic weeks share what the edits leave by days, rounded down; the last automatic week takes the cents left.
    val rest = limit - editedSum
    val autoDays = (0 until n).filter { !edited[it] }.sumOf { days[it] }
    val steps = LongArray(n) { if (edited[it]) edits.getValue(ws[it]) else rest * days[it] / autoDays }
    steps[(0 until n).last { !edited[it] }] += rest - (0 until n).filter { !edited[it] }.sumOf { steps[it] }
    // §5: ritmo ideal of a day = steps of the weeks closed before it + its week's step × days elapsed (with it) ÷ its days.
    fun idealOn(d: LocalDate) = (0 until n).sumOf { i ->
        when {
            we[i] < d -> steps[i]
            ws[i] <= d -> steps[i] * (ws[i].daysUntil(d) + 1) / days[i]
            else -> 0L
        }
    }
    val spent = spentOn.filterKeys { it in start..end }.values.sum()
    val ideal = idealOn(today)
    val auto = edited.count { !it }
    val weeks = List(n) { i ->
        // §4: the only week left automatic can't be edited; an edit takes at most limit − the other edited weeks.
        val locked = !hasRow || (!edited[i] && auto == 1)
        val max = if (locked) 0L else limit - editedSum + (if (edited[i]) steps[i] else 0L)
        Week(ws[i], we[i], Cents(steps[i]), edited[i], Cents(spentOn.filterKeys { it in ws[i]..we[i] }.values.sum()), Cents(max), locked)
    }
    val idealByDay = ArrayList<Cents>()
    var d = start
    while (d <= end) {
        idealByDay.add(Cents(idealOn(d)))
        d = d.plus(1, DAY)
    }
    return BudgetMonth(
        month = month, start = start, end = end, limit = Cents(limit), spent = Cents(spent), remaining = Cents(limit - spent),
        weeks = weeks, ideal = Cents(ideal),
        // §5: a cent above the ideal is already Acima do ritmo; above the limit is Fora da margem.
        status = when {
            spent > limit -> PaceStatus.OVERRUN
            spent > ideal -> PaceStatus.AHEAD
            else -> PaceStatus.ON_PACE
        },
        // §5: No passo atual = spent × (Σ steps ÷ today's ideal); none when the ideal is 0. Rounded down (spec silent).
        projection = Cents(if (ideal == 0L) 0L else spent * steps.sum() / ideal),
        idealByDay = idealByDay,
    )
}

/** Spec §8.1: negative when [a] is riskier. Fora da margem by excess, Acima do ritmo by distance to the ideal, No ritmo by margin share. */
private fun mtRiskCmp(a: BudgetMonth, b: BudgetMonth): Int {
    fun rank(x: BudgetMonth) = when (x.status) {
        PaceStatus.OVERRUN -> 0
        PaceStatus.AHEAD -> 1
        PaceStatus.ON_PACE -> 2
    }
    if (rank(a) != rank(b)) return rank(a).compareTo(rank(b))
    return when (a.status) {
        PaceStatus.OVERRUN -> (b.spent.v - b.limit.v).compareTo(a.spent.v - a.limit.v)
        PaceStatus.AHEAD -> (b.spent.v - b.ideal.v).compareTo(a.spent.v - a.ideal.v)
        PaceStatus.ON_PACE -> (a.remaining.v * b.limit.v).compareTo(b.remaining.v * a.limit.v)
    }
}

/** "1.234,56" */
private fun mtBrl(c: Long): String {
    val reais = (c / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return "$reais,${(c % 100).toString().padStart(2, '0')}"
}

/** A real SQLite file per test, deleted afterwards with its -wal and -shm. */
private suspend fun mtWithDb(block: suspend (Db, String) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-model-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path, timeZone = MT_TZ)
        try {
            block(db, path)
        } finally {
            db.close()
        }
    } finally {
        for (suffix in listOf("", "-wal", "-shm", "-journal", ".import", ".bak", ".bak-wal", ".bak-shm")) {
            runCatching { SystemFileSystem.delete(Path(path + suffix), mustExist = false) }
        }
    }
}

/** One random run: the model [m] next to the [db], compared after every step. */
private class MtRun(val db: Db, val path: String, val seed: Int) {
    val rnd = Random(seed)
    var m = MtModel()
    var today = MT_DAY0
    var step = 0
    var captures = 0
    val log = ArrayList<String>()
    /** What the test wrote in each text: value, merchant, estorno or not (date and time come from the notice's time). */
    val texts = HashMap<String, Parsed>()
    /** The backup file and the model at the time it was written. */
    var backup: MtModel? = null

    fun log(s: String) {
        log.add("#$step [$today] $s")
    }

    fun cur() = mtMonthOf(today, m.day)

    suspend fun go(steps: Int) {
        try {
            setup()
            check(full = true)
            for (i in 1..steps) {
                step = i
                act()
                check(full = i % 10 == 0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AssertionError("seed=$seed step=$step today=$today: ${e.message}\n--- last actions ---\n${log.takeLast(30).joinToString("\n")}", e)
        }
    }

    suspend fun setup() {
        log("setup")
        assertTrue(db.addSource(MT_NU, "Nubank", 1L))
        m = m.copy(sources = mapOf(MT_NU to Source(MT_NU, "Nubank", 1L)))
        for ((name, limit) in listOf("Mercado" to 80_000L, "Casa" to 150_000L, "Lazer" to 30_000L)) createBudget(name, limit)
        repeat(8) { captureNew() }
        repeat(6) { manual() }
    }

    // ---- actions: each one predicts the outcome from the model, runs it on the Db and moves the model ----

    suspend fun act() {
        db.ensureMonths(cur().mtShift(3))
        when (rnd.nextInt(100)) {
            in 0..9 -> captureNew()
            in 10..12 -> captureOdd()
            13 -> sourceAction()
            in 14..17 -> dismiss()
            in 18..19 -> restore()
            in 20..31 -> accept()
            in 32..35 -> manual()
            in 36..40 -> edit()
            in 41..43 -> deleteTx()
            in 44..47 -> refundTx()
            in 48..49 -> refundNotice()
            in 50..52 -> undoRefund()
            in 53..55 -> createBudget(MT_NAMES.random(rnd), if (rnd.nextInt(8) == 0) 0L else 5_000L + rnd.nextLong(300_000))
            56 -> renameBudget()
            in 57..60 -> setLimit()
            61 -> deleteBudget()
            62 -> reorder()
            in 63..70 -> setStep()
            in 71..73 -> clearStep()
            in 74..75 -> setMonthStartDay()
            76 -> setWeekStart()
            in 77..80 -> tagAction()
            in 81..82 -> aliasAction()
            83 -> settingsAction()
            in 84..88 -> advance()
            in 89..90 -> backupAction()
            else -> probe()
        }
    }

    fun parsedOf(raw: RawNotice): Parsed {
        val at = Instant.fromEpochMilliseconds(if (raw.whenMs != 0L) raw.whenMs else raw.postedAt).toLocalDateTime(MT_TZ)
        return (texts[raw.text] ?: Parsed()).copy(date = at.date, time = LocalTime(at.hour, at.minute))
    }

    suspend fun capture(raw: RawNotice) {
        val expect = m.captures(raw)
        val got = capture(db, raw)
        log("capture ${raw.packageName} ${raw.key} posted=${raw.postedAt} when=${raw.whenMs} \"${raw.text}\" -> $got")
        assertEquals(expect, got, "capture of ${raw.key} saved")
        if (!got) return
        val id = db.pending().single { it.raw == raw }.id
        assertTrue(m.notices.none { it.id == id }, "new notice id ${id.v}")
        m = m.copy(notices = m.notices + MtNotice(id, raw, parsedOf(raw), false))
    }

    /** A new Nubank purchase, an estorno (usually of a purchase already noticed), a message, or a message from the other app. */
    suspend fun captureNew() {
        captures++
        val at = today.atStartOfDayIn(MT_TZ).toEpochMilliseconds() + 8 * 3_600_000L + captures * 60_000L
        val whenMs = if (rnd.nextInt(4) == 0) 0L else at - rnd.nextLong(1, 3_600_000)
        val r = rnd.nextInt(10)
        val raw = RawNotice(packageName = MT_NU, appLabel = "Nubank", key = "k-$captures", postedAt = at, whenMs = whenMs, title = "Nubank")
        when {
            r < 6 -> {
                val cents = 100L + rnd.nextLong(150_000)
                val merchant = MT_MERCHANTS.random(rnd)
                val text = "Compra de R$ ${mtBrl(cents)} APROVADA em $merchant para o cartão com final 1234."
                texts[text] = Parsed(cents = Cents(cents), merchant = merchant)
                capture(raw.copy(text = text))
            }
            r < 8 -> {
                val bought = m.notices.filter { it.parsed.cents.v > 0 && !it.parsed.isRefund }.randomOrNull(rnd)?.parsed
                val cents = bought?.cents?.v ?: (100L + rnd.nextLong(50_000))
                val merchant = bought?.merchant ?: MT_MERCHANTS.random(rnd)
                val text = "A compra em $merchant no valor de R$ ${mtBrl(cents)} foi estornada no seu cartão."
                texts[text] = Parsed(cents = Cents(cents), merchant = merchant, isRefund = true)
                capture(raw.copy(title = "Estorno", text = text))
            }
            r < 9 -> capture(raw.copy(text = MT_OTHER_TEXTS.random(rnd)))
            else -> capture(raw.copy(packageName = MT_IPE, appLabel = "Banco Ipê", title = "Banco Ipê", text = MT_OTHER_TEXTS.random(rnd)))
        }
    }

    /** Re-posts (same key, maybe same content and when, near or far) and noise: the model's dedupe decides. */
    suspend fun captureOdd() {
        val old = m.notices.randomOrNull(rnd)
        if (old == null || rnd.nextInt(5) == 0) {
            captures++
            return capture(RawNotice(packageName = MT_NU, appLabel = "Nubank", key = "k-$captures", postedAt = 5L, whenMs = 5L, title = " "))
        }
        val posted = old.raw.postedAt + listOf(30_000L, 90_000L, 150_000L, 3_600_000L).random(rnd)
        capture(
            when (rnd.nextInt(4)) {
                0 -> old.raw.copy(postedAt = posted)
                1 -> old.raw.copy(postedAt = posted, whenMs = 0L)
                2 -> old.raw.copy(postedAt = posted, whenMs = old.raw.whenMs + 60_000L)
                else -> old.raw.copy(postedAt = posted, text = MT_OTHER_TEXTS.random(rnd))
            }
        )
    }

    suspend fun sourceAction() {
        when (rnd.nextInt(3)) {
            0 -> {
                val label = if (rnd.nextBoolean()) "Banco Ipê" else "ipê"
                assertTrue(db.addSource(MT_IPE, label, 50L + step))
                log("addSource $MT_IPE $label")
                val had = m.sources[MT_IPE]
                m = m.copy(sources = m.sources + (MT_IPE to (had?.copy(label = label) ?: Source(MT_IPE, label, 50L + step))))
            }
            1 -> {
                db.removeSource(MT_IPE)
                log("removeSource $MT_IPE")
                m = m.copy(sources = m.sources - MT_IPE)
            }
            else -> assertEquals(false, db.addSource(MT_IPE, "  ", 1L), "addSource with a blank label")
        }
    }

    /** Mostly a pending notice matching [want]; sometimes any notice (which the Db may refuse). */
    fun pickNotice(want: (MtNotice) -> Boolean = { true }): MtNotice? {
        if (rnd.nextInt(6) != 0) m.notices.filter { m.status(it) == NoticeStatus.PENDING && want(it) }.randomOrNull(rnd)?.let { return it }
        return m.notices.randomOrNull(rnd)
    }

    suspend fun dismiss() {
        val n = pickNotice() ?: return
        val expect = m.status(n) == NoticeStatus.PENDING
        val got = db.dismiss(n.id)
        log("dismiss ${n.id.v} -> $got")
        assertEquals(expect, got, "dismiss ${n.id.v}")
        if (got) m = m.copy(notices = m.notices.map { if (it.id == n.id) it.copy(dismissed = true) else it })
    }

    suspend fun restore() {
        val n = (if (rnd.nextInt(4) != 0) m.notices.filter { it.dismissed }.randomOrNull(rnd) else null) ?: m.notices.randomOrNull(rnd) ?: return
        val got = db.restore(n.id)
        log("restore ${n.id.v} -> $got")
        assertEquals(n.dismissed, got, "restore ${n.id.v}")
        if (got) m = m.copy(notices = m.notices.map { if (it.id == n.id) it.copy(dismissed = false) else it })
    }

    fun aliasFor(merchant: String) = if (rnd.nextInt(8) == 0) " Ap ${mtKey(merchant)} ${rnd.nextInt(2)} " else if (rnd.nextInt(10) == 0) "   " else ""

    /** A form, valid most of the time; sometimes exactly what the notice said (not emendado). */
    fun form(parsed: Parsed?): TransactionForm {
        val budget = when (rnd.nextInt(20)) {
            0 -> BudgetId(0)
            1 -> (m.usedBudgetIds - m.budgets.map { it.id }.toSet()).randomOrNull(rnd) ?: MT_UNKNOWN_BUDGET
            else -> m.budgets.randomOrNull(rnd)?.id ?: BudgetId(0)
        }
        val tags = when (rnd.nextInt(20)) {
            0 -> emptyList()
            1 -> listOf(" ", "")
            else -> List(1 + rnd.nextInt(3)) { if (rnd.nextInt(15) == 0) "Novo${rnd.nextInt(4)}" else MT_TAGS.random(rnd) }
        }
        val n = when (rnd.nextInt(25)) {
            0 -> 0
            1 -> 401
            else -> if (rnd.nextInt(3) == 0) 2 + rnd.nextInt(13) else 1
        }
        if (parsed != null && parsed.cents.v > 0 && rnd.nextInt(3) == 0) {
            return TransactionForm(budget, tags, parsed.cents, n, parsed.merchant, parsed.date, parsed.time, aliasFor(parsed.merchant))
        }
        val merchant = MT_FORM_MERCHANTS.random(rnd)
        val total = when (rnd.nextInt(20)) {
            0 -> 0L
            1 -> -250L
            2 -> 1L + rnd.nextInt(9)
            else -> 100L + rnd.nextLong(120_000)
        }
        val date = today.minus(rnd.nextInt(-5, 120), DAY)
        return TransactionForm(budget, tags, Cents(total), n, merchant, date, LocalTime(rnd.nextInt(24), rnd.nextInt(60)), aliasFor(merchant))
    }

    fun describe(f: TransactionForm) =
        "budget=${f.budget.v} tags=${f.tags} total=${f.total.v} n=${f.installments} merchant=\"${f.merchant}\" ${f.date} ${f.time} alias=\"${f.alias}\""

    suspend fun accept() {
        val n = pickNotice { !it.parsed.isRefund || rnd.nextInt(5) == 0 } ?: return
        val f = form(n.parsed)
        val t = if (m.status(n) == NoticeStatus.PENDING) m.txOf(TransactionId(0), n.id, f) else null
        val id = db.accept(n.id, f)
        log("accept ${n.id.v} ${describe(f)} -> ${id.v}")
        assertEquals(t != null, id.v != 0L, "accept ${n.id.v} accepted")
        if (t == null) return
        assertTrue(id !in m.usedTxIds, "accept: transaction id ${id.v} never used before (D38)")
        m = m.saveTx(t.copy(id = id), f)
        assertEquals(m.txs.last().let { x -> TransactionDetail(m.row(x, null), x.inst, m.noticeRow(n)) }, db.transaction(id), "transaction ${id.v} after accept")
    }

    suspend fun manual() {
        val f = form(null)
        val t = m.txOf(TransactionId(0), NoticeId(0), f)
        val id = db.addManual(f)
        log("addManual ${describe(f)} -> ${id.v}")
        assertEquals(t != null, id.v != 0L, "addManual accepted")
        if (t == null) return
        assertTrue(id !in m.usedTxIds, "addManual: transaction id ${id.v} never used before (D38)")
        m = m.saveTx(t.copy(id = id), f)
    }

    fun pickTx(): MtTx? = if (m.txs.isEmpty() || rnd.nextInt(12) == 0) null else m.txs.random(rnd)

    suspend fun edit() {
        val old = pickTx()
        val f = form(old?.let { m.notice(it.notice)?.parsed })
        val t = old?.let { m.txOf(it.id, it.notice, f) }
        val id = old?.id ?: MT_UNKNOWN_TX
        val got = db.edit(id, f)
        log("edit ${id.v} ${describe(f)} -> $got")
        assertEquals(t != null, got, "edit ${id.v} accepted")
        if (t != null) m = m.saveTx(t, f)
    }

    suspend fun deleteTx() {
        val id = pickTx()?.id ?: MT_UNKNOWN_TX
        db.deleteTransaction(id)
        log("deleteTransaction ${id.v}")
        m = m.withoutTxs(setOf(id))
    }

    suspend fun refundTx() {
        val e = pickNotice { it.parsed.isRefund } ?: return
        val t = if (m.txs.isEmpty() || rnd.nextInt(10) == 0) null else m.txs.filter { !m.refunded(it.id) || rnd.nextInt(4) == 0 }.randomOrNull(rnd)
        val id = t?.id ?: MT_UNKNOWN_TX
        val expect = m.status(e) == NoticeStatus.PENDING && t != null && !m.refunded(t.id)
        val got = db.registerRefund(e.id, id)
        log("registerRefund estorno=${e.id.v} tx=${id.v} -> $got")
        assertEquals(expect, got, "registerRefund estorno=${e.id.v} tx=${id.v}")
        if (got) m = m.copy(refunds = m.refunds + (e.id to (id to NoticeId(0))))
    }

    suspend fun refundNotice() {
        val e = pickNotice { it.parsed.isRefund } ?: return
        val p = if (rnd.nextInt(10) == 0) e else pickNotice { !it.parsed.isRefund } ?: return
        val expect = e.id != p.id && m.status(e) == NoticeStatus.PENDING && m.status(p) == NoticeStatus.PENDING
        val got = db.registerRefund(e.id, p.id)
        log("registerRefund estorno=${e.id.v} notice=${p.id.v} -> $got")
        assertEquals(expect, got, "registerRefund estorno=${e.id.v} notice=${p.id.v}")
        if (got) m = m.copy(refunds = m.refunds + (e.id to (TransactionId(0) to p.id)))
    }

    suspend fun undoRefund() {
        val e = (if (rnd.nextInt(5) != 0) m.refunds.keys.randomOrNull(rnd) else null) ?: m.notices.randomOrNull(rnd)?.id ?: return
        val got = db.undoRefund(e)
        log("undoRefund ${e.v} -> $got")
        assertEquals(e in m.refunds, got, "undoRefund ${e.v}")
        m = m.copy(refunds = m.refunds - e)
    }

    suspend fun createBudget(name: String, limit: Long) {
        val clean = name.trim()
        val expect = clean.isNotEmpty() && limit > 0 && m.budgets.none { it.name.lowercase() == clean.lowercase() }
        val id = db.createBudget(name, Cents(limit), today)
        log("createBudget \"$name\" $limit -> ${id.v}")
        assertEquals(expect, id.v != 0L, "createBudget \"$name\" $limit accepted")
        if (id.v == 0L) return
        assertTrue(id !in m.usedBudgetIds, "createBudget: id ${id.v} never used before (D38)")
        // §3: a budget created mid-month has its full limit in the whole month.
        m = m.copy(budgets = m.budgets + MtBudget(id, clean, mapOf(cur() to limit), emptyMap()), usedBudgetIds = m.usedBudgetIds + id)
    }

    fun pickBudget(): BudgetId = if (m.budgets.isEmpty() || rnd.nextInt(10) == 0) {
        (m.usedBudgetIds - m.budgets.map { it.id }.toSet()).randomOrNull(rnd) ?: MT_UNKNOWN_BUDGET
    } else m.budgets.random(rnd).id

    suspend fun renameBudget() {
        val id = pickBudget()
        val name = MT_NAMES.random(rnd)
        val clean = name.trim()
        val expect = m.budget(id) != null && clean.isNotEmpty() && m.budgets.none { it.id != id && it.name.lowercase() == clean.lowercase() }
        val got = db.renameBudget(id, name)
        log("renameBudget ${id.v} \"$name\" -> $got")
        assertEquals(expect, got, "renameBudget ${id.v} \"$name\"")
        if (got) m = m.copy(budgets = m.budgets.map { if (it.id == id) it.copy(name = clean) else it })
    }

    suspend fun setLimit() {
        val id = pickBudget()
        // Often right at the edits of a month from today's on: §4 deletes them only when the new limit is below their sum.
        val from = mtWindow(cur(), m.day).first
        val sums = m.budget(id)?.edits?.filterKeys { it >= from }?.entries?.groupBy { mtMonthOf(it.key, m.day) }?.values?.map { e -> e.sumOf { it.value } }.orEmpty()
        val limit = when {
            rnd.nextInt(8) == 0 -> -rnd.nextLong(2) * 100
            sums.isNotEmpty() && rnd.nextBoolean() -> maxOf(1L, sums.random(rnd) - 1 + rnd.nextLong(3))
            else -> 1_000L + rnd.nextLong(250_000)
        }
        val next = m.withLimit(id, limit, today)
        // §4: the app warns before a limit that deletes edits; the warning must say exactly that.
        val drops = next != null && next.edits() != m.edits()
        assertEquals(drops, db.limitDropsEdits(id, Cents(limit), today), "limitDropsEdits ${id.v} $limit")
        val got = db.setLimit(id, Cents(limit), today)
        log("setLimit ${id.v} $limit -> $got (drops=$drops)")
        assertEquals(next != null, got, "setLimit ${id.v} $limit")
        if (next != null) m = next
    }

    suspend fun deleteBudget() {
        val id = pickBudget()
        db.deleteBudget(id)
        log("deleteBudget ${id.v}")
        // §3: its transactions go, their notices back to the Pauta.
        m = m.withoutTxs(m.txs.filter { it.budget == id }.map { it.id }.toSet()).let { it.copy(budgets = it.budgets.filter { b -> b.id != id }) }
    }

    suspend fun reorder() {
        val ids = m.budgets.map { it.id }.shuffled(rnd).toMutableList()
        when (rnd.nextInt(6)) {
            0 -> if (ids.isNotEmpty()) ids.removeAt(0)
            1 -> if (ids.isNotEmpty()) ids[0] = MT_UNKNOWN_BUDGET
            2 -> if (ids.size > 1) ids[0] = ids[1]
        }
        val expect = ids.size == m.budgets.size && ids.toSet() == m.budgets.map { it.id }.toSet()
        val got = db.reorderBudgets(ids)
        log("reorderBudgets ${ids.map { it.v }} -> $got")
        assertEquals(expect, got, "reorderBudgets")
        if (got) m = m.copy(budgets = ids.map { id -> m.budget(id)!! })
    }

    suspend fun setStep() {
        val b = m.budgets.randomOrNull(rnd) ?: return
        val month = cur().mtShift(rnd.nextInt(-2, 3))
        val bm = m.month(b, month, today)
        val week = bm.weeks.random(rnd)
        val ws = if (rnd.nextInt(12) == 0) bm.start.minus(3, DAY) else week.start
        val max = week.max.v
        val stepCents = when (rnd.nextInt(6)) {
            0 -> -100L
            1 -> max + 1 + rnd.nextLong(1_000)
            2 -> max
            else -> rnd.nextLong(max + 1)
        }
        val w = bm.weeks.firstOrNull { it.start == ws }
        val expect = b.limitAt(month) != null && w != null && !w.locked && stepCents in 0..w.max.v
        val got = db.setStep(b.id, month, ws, Cents(stepCents))
        log("setStep ${b.id.v} $month $ws $stepCents (max ${w?.max?.v}, locked ${w?.locked}) -> $got")
        assertEquals(expect, got, "setStep ${b.id.v} $month $ws $stepCents")
        if (got) m = m.copy(budgets = m.budgets.map { if (it.id == b.id) it.copy(edits = it.edits + (ws to stepCents)) else it })
    }

    suspend fun clearStep() {
        val b = m.budgets.randomOrNull(rnd) ?: return
        val month = cur().mtShift(rnd.nextInt(-2, 3))
        val bm = m.month(b, month, today)
        val ws = if (rnd.nextInt(10) == 0) bm.end.plus(1, DAY) else (bm.weeks.filter { it.edited }.randomOrNull(rnd) ?: bm.weeks.random(rnd)).start
        val expect = ws in bm.start..bm.end && ws in b.edits
        val got = db.clearStep(b.id, month, ws)
        log("clearStep ${b.id.v} $month $ws -> $got")
        assertEquals(expect, got, "clearStep ${b.id.v} $month $ws")
        if (got) m = m.copy(budgets = m.budgets.map { if (it.id == b.id) it.copy(edits = it.edits - ws) else it })
    }

    suspend fun setMonthStartDay() {
        val day = when (rnd.nextInt(10)) {
            0 -> if (rnd.nextBoolean()) 0 else 32
            1 -> m.day
            2 -> 1
            else -> 1 + rnd.nextInt(31)
        }
        val next = if (day in 1..31) m.withMonthStartDay(day, today) else null
        // D41: the warning before saving says exactly whether edits go.
        assertEquals(next != null && next.edits() != m.edits(), db.startDropsEdits(day, m.settings.weekStart, today), "startDropsEdits $day")
        val got = db.setMonthStartDay(day, today)
        log("setMonthStartDay $day (was ${m.day}) -> $got")
        assertEquals(next != null, got, "setMonthStartDay $day")
        if (next != null) m = next
    }

    suspend fun setWeekStart() {
        val w = DayOfWeek.entries.random(rnd)
        val next = m.withWeekStart(w, today)
        assertEquals(next.edits() != m.edits(), db.startDropsEdits(m.day, w, today), "startDropsEdits week $w")
        db.setWeekStart(w, today)
        log("setWeekStart $w (was ${m.settings.weekStart})")
        m = next
    }

    suspend fun tagAction() {
        val tags = db.tags()
        fun idOf(name: String?) = tags.firstOrNull { it.name == name }?.id ?: TagId(987_654)
        val name = m.tags.randomOrNull(rnd)?.takeIf { rnd.nextInt(8) != 0 }
        val id = idOf(name)
        when (rnd.nextInt(4)) {
            0 -> {
                val raw = if (rnd.nextInt(6) == 0) "   " else if (rnd.nextBoolean()) MT_TAGS.random(rnd) else "Novo${rnd.nextInt(6)}"
                val clean = raw.trim().lowercase()
                val got = db.createTag(raw)
                log("createTag \"$raw\" -> ${got.v}")
                assertEquals(clean.isNotEmpty(), got.v != 0L, "createTag \"$raw\"")
                if (clean in m.tags) assertEquals(idOf(clean), got, "createTag of a used name gives that tag")
                if (clean.isNotEmpty()) m = m.copy(tags = m.tags + clean)
            }
            1 -> {
                val raw = if (rnd.nextInt(6) == 0) "  " else if (rnd.nextBoolean()) MT_TAGS.random(rnd) else "Novo${rnd.nextInt(6)}"
                val clean = raw.trim().lowercase()
                val expect = name != null && clean.isNotEmpty() && (clean == name || clean !in m.tags)
                val got = db.renameTag(id, raw)
                log("renameTag ${id.v} ($name) \"$raw\" -> $got")
                assertEquals(expect, got, "renameTag $name \"$raw\"")
                if (!got || name == null) return
                assertEquals(id, db.tags().firstOrNull { it.name == clean }?.id, "renaming keeps the tag's id")
                m = m.copy(tags = m.tags - name + clean, txs = m.txs.map { if (name in it.tags) it.copy(tags = it.tags - name + clean) else it })
            }
            2 -> {
                val into = m.tags.randomOrNull(rnd)
                val got = db.mergeTags(id, idOf(into))
                log("mergeTags ${id.v} ($name) -> $into -> $got")
                assertEquals(name != null && into != null && name != into, got, "mergeTags $name -> $into")
                if (!got || name == null || into == null) return
                m = m.copy(tags = m.tags - name, txs = m.txs.map { if (name in it.tags) it.copy(tags = it.tags - name + into) else it })
            }
            else -> {
                db.deleteTag(id)
                log("deleteTag ${id.v} ($name)")
                if (name == null) return
                // §2.9: transactions left without a tag go; their notices back to the Pauta.
                val stripped = m.txs.map { if (name in it.tags) it.copy(tags = it.tags - name) else it }
                m = m.copy(tags = m.tags - name, txs = stripped).withoutTxs(stripped.filter { it.tags.isEmpty() }.map { it.id }.toSet())
            }
        }
    }

    suspend fun aliasAction() {
        val merchant = MT_FORM_MERCHANTS.random(rnd)
        val alias = if (rnd.nextInt(4) == 0) " " else " Ap ${mtKey(merchant)} ${rnd.nextInt(3)}"
        db.setAlias(merchant, alias)
        log("setAlias \"$merchant\" \"$alias\"")
        val key = mtKey(merchant)
        if (key.isEmpty()) return
        m = m.copy(aliases = if (alias.isBlank()) m.aliases - key else m.aliases + (key to alias.trim()))
    }

    suspend fun settingsAction() {
        when (rnd.nextInt(4)) {
            0 -> Theme.entries.random(rnd).let { db.setTheme(it); m = m.copy(settings = m.settings.copy(theme = it)) }
            1 -> BudgetOrder.entries.random(rnd).let { db.setBudgetOrder(it); m = m.copy(settings = m.settings.copy(budgetOrder = it)) }
            2 -> BudgetOrder.entries.random(rnd).let { db.setWidgetOrder(it); m = m.copy(settings = m.settings.copy(widgetOrder = it)) }
            else -> {
                // §2.11: opening the Pauta; notices posted after it are new.
                val at = m.notices.randomOrNull(rnd)?.raw?.postedAt ?: 0L
                db.markPautaSeen(at)
                m = m.copy(settings = m.settings.copy(pautaSeenAt = at))
            }
        }
        log("settings ${m.settings}")
    }

    /** Time passes; often straight to the first day of the next month (Virada, §3). */
    suspend fun advance() {
        today = if (rnd.nextBoolean()) today.plus(1 + rnd.nextInt(20), DAY) else mtWindow(cur(), m.day).second.plus(1, DAY)
        log("today -> $today")
        db.ensureMonths(cur())
    }

    /** §7.6 Backup: export, restore the last export (the whole database as it was), or a file that isn't a backup. */
    suspend fun backupAction() {
        val file = "$path.bak"
        when (rnd.nextInt(3)) {
            0 -> {
                assertTrue(db.exportTo(file), "exportTo")
                log("exportTo")
                backup = m
            }
            1 -> {
                val b = backup ?: return
                assertTrue(db.importFrom(file), "importFrom")
                log("importFrom (back to the export)")
                m = b
            }
            else -> assertEquals(false, db.importFrom("$path.missing"), "importFrom a missing file")
        }
    }

    /** Reads that take arguments: previews, warnings, suggestion, refund candidates, monthOf. They write nothing. */
    suspend fun probe() {
        val s = m.settings
        // §7.3 Prévia: the margin of the budget in the month of the first installment, before and after.
        val b = pickBudget()
        val date = today.minus(rnd.nextInt(-5, 90), DAY)
        val total = if (rnd.nextInt(10) == 0) 0L else 1L + rnd.nextLong(90_000)
        val n = if (rnd.nextInt(15) == 0) 0 else 1 + rnd.nextInt(6)
        val replacing = if (rnd.nextBoolean()) pickTx() else null
        val expect = run {
            val budget = m.budget(b)
            if (budget == null || total <= 0 || n !in 1..400) return@run Preview()
            val month = mtMonthOf(date, s.monthStartDay)
            val (from, to) = mtWindow(month, s.monthStartDay)
            val before = (budget.limitAt(month) ?: 0L) - m.spentOn(b, from, to).values.sum()
            val old = if (replacing != null && replacing.budget == b && !m.refunded(replacing.id)) replacing.inst.filter { it.date in from..to }.sumOf { it.cents.v } else 0L
            // An edit keeps the estorno (Db.edit): a refunded purchase doesn't count after it either (§6).
            val new = if (replacing != null && m.refunded(replacing.id)) 0L else total / n
            Preview(month, Cents(before), Cents(before + old - new))
        }
        log("previewAccept ${b.v} $date $total $n replacing=${replacing?.id?.v}")
        assertEquals(expect, db.previewAccept(b, date, Cents(total), n, today, replacing?.id ?: TransactionId(0)), "previewAccept ${b.v} $date $total×$n replacing ${replacing?.id?.v}")

        // §7.3 Registrar estorno's preview: the purchase's month gets back its installment there.
        val t = pickTx()
        val expectRefund = if (t == null || m.refunded(t.id)) Preview() else {
            val month = mtMonthOf(t.date, s.monthStartDay)
            val (from, to) = mtWindow(month, s.monthStartDay)
            val before = (m.budget(t.budget)!!.limitAt(month) ?: 0L) - m.spentOn(t.budget, from, to).values.sum()
            Preview(month, Cents(before), Cents(before + t.inst.filter { it.date in from..to }.sumOf { it.cents.v }))
        }
        assertEquals(expectRefund, db.previewRefund(t?.id ?: MT_UNKNOWN_TX, today), "previewRefund ${t?.id?.v}")

        // §7.1 Nova verba: the steps of today's month for a new budget.
        val limit = if (rnd.nextInt(8) == 0) 0L else 1L + rnd.nextLong(200_000)
        val expectNew = if (limit <= 0) BudgetMonth() else mtBudgetMonth(cur(), s.monthStartDay, s.weekStart, today, limit, true, emptyMap(), emptyMap())
        assertEquals(expectNew, db.previewBudget(Cents(limit), today), "previewBudget $limit")

        // D41: both start days at once.
        val day = 1 + rnd.nextInt(31)
        val week = DayOfWeek.entries.random(rnd)
        assertEquals(m.withMonthStartDay(day, today).withWeekStart(week, today).edits() != m.edits(), db.startDropsEdits(day, week, today), "startDropsEdits $day $week")

        // §2.5 Sugestão: budget and tags of the merchant's last transaction (latest by date, time, then id: Db KDoc).
        for (merchant in MT_FORM_MERCHANTS) {
            val key = mtKey(merchant)
            val last = if (key.isEmpty()) null else m.txs.filter { mtKey(it.merchant) == key }.maxWithOrNull(compareBy<MtTx> { it.date }.thenBy { it.time }.thenBy { it.id.v })
            assertEquals(last?.let { Suggestion(it.budget, it.tags.sorted()) } ?: Suggestion(), db.suggestion(merchant), "suggestion \"$merchant\"")
        }

        // §7.3: every candidate an estorno may cancel, the likely ones first, newest first within each group.
        val e = m.notices.filter { m.status(it) == NoticeStatus.PENDING }.randomOrNull(rnd)
        if (e != null) {
            val key = mtKey(e.parsed.merchant)
            fun likely(pkg: String, cents: Long, merchant: String, posted: Long) =
                e.parsed.cents.v > 0 && pkg == e.raw.packageName && cents == e.parsed.cents.v && (key.isEmpty() || mtKey(merchant) == key) && posted <= e.raw.postedAt
            val exp = m.txs.filter { !m.refunded(it.id) }.map { x ->
                val bought = m.notice(x.notice)
                RefundCandidate(transaction = x.id, name = m.aliases[mtKey(x.merchant)] ?: x.merchant, total = Cents(x.total), date = x.date,
                    likely = bought != null && likely(bought.raw.packageName, x.total, x.merchant, bought.raw.postedAt))
            } + m.notices.filter { it.id != e.id && m.status(it) == NoticeStatus.PENDING && !it.parsed.isRefund }.map { x ->
                RefundCandidate(notice = x.id, name = m.aliases[mtKey(x.parsed.merchant)] ?: x.parsed.merchant, total = x.parsed.cents, date = x.parsed.date,
                    likely = likely(x.raw.packageName, x.parsed.cents.v, x.parsed.merchant, x.raw.postedAt))
            }
            val got = db.refundCandidates(e.id)
            val canon = compareBy<RefundCandidate> { it.transaction.v }.thenBy { it.notice.v }
            assertEquals(exp.sortedWith(canon), got.sortedWith(canon), "refundCandidates ${e.id.v}")
            for (i in 1 until got.size) {
                val (a, z) = got[i - 1] to got[i]
                assertTrue(a.likely || !z.likely, "refundCandidates: likely ones first")
                if (a.likely == z.likely) assertTrue(a.date >= z.date, "refundCandidates: newest first within a group")
            }
        }

        for (d in listOf(today, mtWindow(cur(), s.monthStartDay).first, mtWindow(cur(), s.monthStartDay).second, date)) {
            assertEquals(mtMonthOf(d, s.monthStartDay), db.monthOf(d), "monthOf $d")
        }
    }

    // ---- the comparison: every public read the model can predict ----

    suspend fun check(full: Boolean) {
        val cur = cur()
        db.ensureMonths(cur.mtShift(3))
        val s = m.settings
        assertEquals(s, db.settings(), "settings")
        assertEquals(m.sources.values.sortedWith(compareBy<Source> { it.label.lowercase() }.thenBy { it.packageName }), db.sources(), "sources")

        // §7.2 Pauta and §7.6 Riscados: newest first.
        val newest = m.notices.sortedWith(compareByDescending<MtNotice> { it.raw.postedAt }.thenByDescending { it.id.v })
        val pending = newest.filter { m.status(it) == NoticeStatus.PENDING }.map { m.noticeRow(it) }
        assertEquals(pending, db.pending(), "pending (Pauta)")
        assertEquals(pending.size, db.pendingCount(), "pendingCount")
        assertEquals(newest.filter { m.status(it) == NoticeStatus.DISMISSED }.map { m.noticeRow(it) }, db.dismissed(), "dismissed (Riscados)")

        // §6: installment k counts in the k-th month, one per month. Their dates stay as written (D7), so after a start day
        // change two can share a month; then "the installment of this month" (TransactionRow.now) means nothing.
        for (t in m.txs) {
            val months = t.inst.map { mtMonthOf(it.date, s.monthStartDay) }
            assertEquals(months.distinct(), months, "§6: one installment per month for transaction ${t.id.v} ${t.inst} (installmentsKeepOneMonthEachAfterStartDayChange)")
        }

        // §7.4 Lançamentos: newest first (date, time, then id: Db KDoc).
        val rows = m.txs.sortedWith(compareByDescending<MtTx> { it.date }.thenByDescending { it.time }.thenByDescending { it.id.v }).map { m.row(it, today) }
        assertEquals(rows, db.transactions(today), "transactions")

        // §7.6 Marcadores: how many transactions use each, and how many would go with it.
        val tagRows = m.tags.sorted().map { g -> TagRow(TagId(0), g, m.txs.count { g in it.tags }, m.txs.count { it.tags == setOf(g) }) }
        assertEquals(tagRows, db.tags().map { it.copy(id = TagId(0)) }, "tags")

        // §7.1 Verbas and the budget detail, in the current month and around it.
        val months = if (full) (-3..2).map { cur.mtShift(it) } else listOf(cur, cur.mtShift(rnd.nextInt(-2, 2))).distinct()
        for (month in months) {
            val exp = m.budgets.filter { it.limitAt(month) != null }.map { BudgetSummary(it.id, it.name, m.month(it, month, today)) }
            for (order in if (full) BudgetOrder.entries else listOf(s.budgetOrder)) {
                val got = db.budgetsOverview(month, today, order)
                when (order) {
                    BudgetOrder.MANUAL -> assertEquals(exp, got, "budgetsOverview $month MANUAL")
                    BudgetOrder.AZ -> assertEquals(exp.sortedBy { it.name.lowercase() }, got, "budgetsOverview $month AZ")
                    BudgetOrder.RISK -> {
                        // §8.1 orders by risk; between equal risks the spec says nothing.
                        assertEquals(exp.sortedBy { it.id.v }, got.sortedBy { it.id.v }, "budgetsOverview $month RISK")
                        for (i in 1 until got.size) assertTrue(mtRiskCmp(got[i - 1].month, got[i].month) <= 0, "risk order $month: ${got[i - 1]} before ${got[i]}")
                    }
                }
            }
            val ids = m.budgets.map { it.id } + if (full) listOf(pickBudget(), MT_UNKNOWN_BUDGET) else emptyList()
            for (id in ids.distinct()) assertEquals(m.detail(id, month, today), db.budgetDetail(id, month, today), "budgetDetail ${id.v} $month")
        }
        if (!full) return

        // §7.5 Verbas por mês: each month's spent against that month's own limit.
        val from = cur.mtShift(-6)
        val history = m.budgets.flatMap { b ->
            (0..6).map { from.mtShift(it) }.mapNotNull { mo ->
                val (a, z) = mtWindow(mo, s.monthStartDay)
                b.limitAt(mo)?.let { HistoryRow(b.id, b.name, mo, Cents(it), Cents(m.spentOn(b.id, a, z).values.sum())) }
            }
        }
        val byBudgetMonth = compareBy<HistoryRow> { it.budget.v }.thenBy { it.month }
        assertEquals(history.sortedWith(byBudgetMonth), db.history(from, cur).sortedWith(byBudgetMonth), "history $from..$cur")

        // §7.5 ranking and calendar: installments by their own date, refunded purchases out.
        for ((a, z) in listOf(mtWindow(cur, s.monthStartDay), mtWindow(cur.mtShift(-1), s.monthStartDay), today.minus(45, DAY) to today.plus(3, DAY))) {
            val counted = m.txs.filter { !m.refunded(it.id) }.mapNotNull { t ->
                val inside = t.inst.filter { it.date in a..z }
                if (inside.isEmpty()) null else t to inside
            }
            val tagRank = m.tags.mapNotNull { g ->
                val with = counted.filter { g in it.first.tags }
                if (with.isEmpty()) null else RankRow(g, Cents(with.sumOf { (_, i) -> i.sumOf { it.cents.v } }), with.size)
            }
            val gotTags = db.tagRanking(a, z)
            assertEquals(tagRank.sortedBy { it.name }, gotTags.sortedBy { it.name }, "tagRanking $a..$z")
            for (i in 1 until gotTags.size) assertTrue(gotTags[i - 1].total >= gotTags[i].total, "tagRanking by value")

            // Merchants by key, named by the alias. Without one, the spec doesn't say which bank spelling names the row.
            class Group(val total: Long, val count: Int, val names: Set<String>)
            val groups = counted.groupBy { mtKey(it.first.merchant) }.map { (key, list) ->
                Group(list.sumOf { (_, i) -> i.sumOf { it.cents.v } }, list.size, m.aliases[key]?.let { setOf(it) } ?: list.map { it.first.merchant }.toSet())
            }.toMutableList()
            val gotMerchants = db.merchantRanking(a, z)
            for (r in gotMerchants) {
                val g = groups.firstOrNull { it.total == r.total.v && it.count == r.count && r.name in it.names }
                assertNotNull(g, "merchantRanking $a..$z: unexpected $r; expected ${groups.map { "${it.names} ${it.total} ${it.count}" }}")
                groups.remove(g)
            }
            assertTrue(groups.isEmpty(), "merchantRanking $a..$z misses ${groups.map { "${it.names} ${it.total} ${it.count}" }}")
            for (i in 1 until gotMerchants.size) assertTrue(gotMerchants[i - 1].total >= gotMerchants[i].total, "merchantRanking by value")

            val byDay = LinkedHashMap<LocalDate, Long>()
            for ((_, inside) in counted) for (i in inside) byDay[i.date] = (byDay[i.date] ?: 0L) + i.cents.v
            assertEquals(byDay.filterValues { it > 0 }.mapValues { Cents(it.value) }, db.spentByDay(a, z), "spentByDay $a..$z")
        }

        // §7.3 the edit screen and the notice behind each transaction.
        for (t in m.txs) {
            val n = m.notice(t.notice)
            assertEquals(TransactionDetail(m.row(t, null), t.inst, n?.let { m.noticeRow(it) } ?: NoticeRow()), db.transaction(t.id), "transaction ${t.id.v}")
        }
        assertEquals(TransactionDetail(), db.transaction(MT_UNKNOWN_TX), "unknown transaction")
        for (n in m.notices) assertEquals(m.noticeRow(n), db.notice(n.id), "notice ${n.id.v}")
        assertEquals(NoticeRow(), db.notice(NoticeId(987_654)), "unknown notice")
    }
}

class ModelTest {
    private fun randomRun(seed: Int, steps: Int) = runTest(timeout = 120.seconds) {
        mtWithDb { db, path -> MtRun(db, path, seed).go(steps) }
    }

    @Test fun modelSeed1() = randomRun(1, 500)

    @Test fun modelSeed7() = randomRun(7, 500)

    @Test fun modelSeed42() = randomRun(42, 500)

    @Test fun modelSeed124() = randomRun(124, 500)

    @Test fun modelSeed2026() = randomRun(2026, 500)

    // ---- what the model found, reduced ----

    /**
     * Spec §7.3: the Prévia shows the effect on the budget before confirming. Spec §6: a refunded purchase isn't summed, and
     * Db.edit keeps the estorno. So editing a refunded purchase changes nothing in its budget, and the preview must say so.
     */
    @Test
    fun previewOfAnEditOfARefundedPurchaseKeepsTheMargin() = runTest(timeout = 30.seconds) {
        mtWithDb { db, _ ->
            assertTrue(db.addSource(MT_NU, "Nubank", 1L))
            val budget = db.createBudget("Mercado", Cents(60_000), MT_DAY0)
            val form = TransactionForm(budget, listOf("feira"), Cents(10_000), 1, "LOJA X", MT_DAY0, LocalTime(12, 0))
            val purchase = db.addManual(form)
            val at = 1_791_000_000_000L
            assertTrue(capture(db, RawNotice(MT_NU, "Nubank", "e", at, at, "Estorno", "A compra em LOJA X no valor de R$ 100,00 foi estornada no seu cartão.")))
            assertTrue(db.registerRefund(db.pending().single().id, purchase))
            val month = db.monthOf(MT_DAY0)
            db.ensureMonths(month)
            assertEquals(Cents(60_000), db.budgetDetail(budget, month, MT_DAY0).month.remaining, "the refunded purchase doesn't count")

            // The edit screen: R$ 100,00 becomes R$ 250,00.
            val preview = db.previewAccept(budget, MT_DAY0, Cents(25_000), 1, MT_DAY0, replacing = purchase)
            assertTrue(db.edit(purchase, form.copy(total = Cents(25_000))))
            val after = db.budgetDetail(budget, month, MT_DAY0).month.remaining
            assertEquals(Cents(60_000), after, "still refunded after the edit: nothing counts")
            assertEquals(Preview(month, Cents(60_000), after), preview, "the preview says what saving does to the margin")
        }
    }

    /**
     * Spec §6: installment k counts in the k-th month from the purchase's. Spec §4: past months are recalculated with a new
     * start day. A purchase on 30 Jan in 3× (start day 1: dated 30 Jan, 28 Feb, 30 Mar, D7); with the month starting on
     * day 31, the purchase is in the month of 31 Dec–30 Jan, and the next two months (31 Jan–27 Feb, 28 Feb–30 Mar) must
     * get installments 2 and 3. The dates kept from day 1 put none in the second month and both in the third.
     */
    @Test
    fun installmentsKeepOneMonthEachAfterStartDayChange() = runTest(timeout = 30.seconds) {
        mtWithDb { db, _ ->
            val budget = db.createBudget("Casa", Cents(500_000), MT_DAY0)
            val bought = LocalDate(2026, 1, 30)
            assertNotEquals(0L, db.addManual(TransactionForm(budget, listOf("casa"), Cents(30_000), 3, "LOJA X", bought, LocalTime(12, 0))).v)
            assertTrue(db.setMonthStartDay(31, MT_DAY0))
            val first = db.monthOf(bought)
            for (i in 0..2) {
                val month = first.mtShift(i)
                db.ensureMonths(month)
                assertEquals(listOf(i + 1), db.budgetDetail(budget, month, MT_DAY0).lines.map { it.k }, "§6: installment ${i + 1} alone in month ${i + 1} ($month)")
            }
        }
    }
}
