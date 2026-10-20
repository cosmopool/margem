package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

// Invariants of the legenda do lançamento (spec §2.5, §2.8, §2.10, §2.12, §7.1, §7.3, §7.4, §7.5;
// glossary Legenda; architecture D52–D57), checked through the public API only, after every step of
// random, fixed-seed sequences of captures, accept, addManual, edit, deleteTransaction, registerRefund,
// undoRefund, deleteTag (apagar o marcador), setLimit (mudar a cota), createBudget/deleteBudget, with
// `today` moving forward. Forms carry random captions, including the invalid ones: over 80 units after
// cleaning, whitespace only, and every kind of line break (LF, CRLF, CR, U+2028, U+2029). The month
// start day is set once, before any transaction, and never changes during a run.
//
// Every successful write is mirrored into a second, captionless database — "o mesmo estado sem
// legendas": its rows are the main rows with caption "", and its Leitura readings (history,
// tagRanking, merchantRanking, spentByDay) are compared to the main ones at every step (spec §7.5:
// "a legenda não entra"). Expected captions come from the cleaning the TransactionForm.caption KDoc
// describes: every line break becomes one space, the edges are trimmed, and over 80 characters after
// cleaning (`String.length`, D55) refuses the whole form (D53) without writing anything.

private val LINV_TODAY0 = LocalDate(2026, 10, 14)
/** Purchases are never dated before this. */
private val LINV_DATE_MIN = LocalDate(2026, 5, 1)
/** Budgets are created "on" this day, so every month a purchase can touch has a limit row. */
private val LINV_BUDGET_DAY = LocalDate(2026, 3, 1)
private const val LINV_PKG = "com.nu.production"
/** Epoch ms; notices are posted 10 minutes apart from here. */
private const val LINV_T0 = 1_791_000_000_000L
/** Most installments a random form asks for; also how far ahead limit rows are filled. */
private const val LINV_HORIZON = 18
private val LINV_MERCHANTS = listOf("PADARIA SAO JOSE", "MERCADO BOM PRECO", "FARMACIA SAO JOAO", "POSTO IPIRANGA", "LOJA X")
private val LINV_TAGS = listOf("feira", " Padaria ", "CAFE", "farmacia", "lazer", "casa", "padaria")
private val LINV_ALIASES = listOf("Padaria do Zé", "Mercado Bom Preço", "Casa")
private val LINV_UNKNOWN_BUDGET = BudgetId(987_654)
private val LINV_UNKNOWN_TX = TransactionId(987_654)
private val LINV_UNKNOWN_NOTICE = NoticeId(987_654)
private val LINV_UNKNOWN_TAG = TagId(987_654)

private fun YearMonth.linvShift(n: Int): YearMonth = if (n >= 0) plus(n, DateTimeUnit.MONTH) else minus(-n, DateTimeUnit.MONTH)

private fun LocalDate.linvDays(n: Int): LocalDate = if (n >= 0) plus(n, DateTimeUnit.DAY) else minus(-n, DateTimeUnit.DAY)

/** "1.234,56" */
private fun linvBrl(c: Long): String {
    val reais = (c / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return "$reais,${(c % 100).toString().padStart(2, '0')}"
}

private fun linvPurchaseText(cents: Long, merchant: String) =
    "Compra de R$ ${linvBrl(cents)} APROVADA em $merchant para o cartão com final 1234."

private fun linvEstornoText(cents: Long, merchant: String) =
    "A compra em $merchant no valor de R$ ${linvBrl(cents)} foi estornada no seu cartão."

/** TransactionForm.tags KDoc: trimmed, lowercased, blanks and repeats dropped. */
private fun linvCleanTags(tags: List<String>) = tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()

// ---- legenda: cleaning, limit and generator (TransactionForm.caption KDoc; D53, D55) ----

private val LINV_BREAKS = Regex("\r\n|\r|\n|\u2028|\u2029")

/**
 * TransactionForm.caption KDoc: every line break (LF, CRLF, CR, U+2028, U+2029) becomes one space and
 * the edges are trimmed (whitespace only then reads "" = no legenda).
 */
private fun linvCleanCaption(raw: String): String = LINV_BREAKS.replace(raw, " ").trim()

/** D53: over 80 characters after cleaning (`String.length`; an astral emoji counts 2, D55) refuses the form. */
private fun linvCaptionFits(raw: String) = linvCleanCaption(raw).length <= 80

private val LINV_CAPTION_WORDS = listOf(
    "bolo", "do", "aniversário", "da", "Ana", "feira", "de", "sábado", "presente", "do", "João",
    "remédio", "da", "vovó", "café", "com", "pão", "gasolina", "do", "mês", "uniforme", "da", "Bia",
)

/** Hand-picked legenda texts: no legenda, whitespace only, every break kind, the 80-unit edge. */
private val LINV_CAPTION_TRICKS = listOf(
    "",
    "   ",
    "\t \r\n \u2028",
    "  bolo do aniversário da Ana  ",
    "linha um\nlinha dois",
    "linha um\r\nlinha dois",
    "linha um\rlinha dois",
    "linha um\u2028linha dois",
    "linha um\u2029linha dois",
    "um\ndois\ntrês",
    "um\r\ndois\r\ntrês",
    "um\n\ndois",
    "fim\n",
    "\tinício",
    "a".repeat(80),
    "a".repeat(81),
    " " + "a".repeat(80) + " ",
    "a".repeat(80) + " ",
    "a".repeat(78) + "😀",
    "a".repeat(79) + "😀",
)

/** The log keeps one line per action: captions print with their breaks escaped. */
private fun linvShow(s: String) =
    s.replace("\r", "\\r").replace("\n", "\\n").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

/** spec §3: from day D (or the month's last day) to the eve of day D of the next month. */
private fun linvWindow(month: YearMonth, day: Int): Pair<LocalDate, LocalDate> {
    val start = LocalDate(month.year, month.month, minOf(day, month.numberOfDays))
    val next = month.linvShift(1)
    val end = LocalDate(next.year, next.month, minOf(day, next.numberOfDays)).linvDays(-1)
    return start to end
}

private fun linvMonths(from: YearMonth, to: YearMonth): List<YearMonth> {
    val out = ArrayList<YearMonth>()
    var m = from
    while (m <= to) {
        out.add(m)
        m = m.linvShift(1)
    }
    return out
}

/** Two real SQLite files per test, deleted afterwards with their -wal and -shm. */
private suspend fun withLegendaDb(block: suspend (Db, Db) -> Unit) {
    val base = Path(SystemTemporaryDirectory, "margem-legenda-${Random.Default.nextLong().toULong()}").toString()
    val paths = listOf("$base-main.db", "$base-shadow.db")
    try {
        val main = Db(BundledSQLiteDriver(), paths[0], timeZone = TimeZone.of("America/Sao_Paulo"))
        val shadow = Db(BundledSQLiteDriver(), paths[1], timeZone = TimeZone.of("America/Sao_Paulo"))
        try {
            block(main, shadow)
        } finally {
            main.close()
            shadow.close()
        }
    } finally {
        for (p in paths) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                runCatching { SystemFileSystem.delete(Path(p + suffix), mustExist = false) }
            }
        }
    }
}

/** What a refused write must leave untouched ("nothing written", and no version bump: Db.version KDoc). */
private data class LinvSnap(
    val txs: List<TransactionRow>,
    val pending: Set<NoticeId>,
    val dismissed: Set<NoticeId>,
    val tags: List<TagRow>,
    val version: Long,
)

/**
 * One random run. [db] takes the captions; [shadow] receives the very same writes with caption "" — the
 * same state without legenda, to compare the Leitura readings and the rows against (spec §7.5).
 */
private class LegendaInvariantRun(val db: Db, val shadow: Db, val seed: Int, val startDay: Int) {
    val rnd = Random(seed)
    val log = ArrayList<String>()
    var step = 0
    var today = LINV_TODAY0
    var captures = 0
    var budgetCounter = 0

    val notices = ArrayList<NoticeId>()
    val budgets = LinkedHashMap<BudgetId, String>()
    val goneBudgets = ArrayList<BudgetId>()
    val txIds = LinkedHashSet<TransactionId>()
    val goneTx = ArrayList<TransactionId>()
    /** The cleaned legenda each accepted form wrote: the model every row is checked against. */
    val captions = LinkedHashMap<TransactionId, String>()
    /** Legenda of a transaction deleted with its notice: relaunching must not bring it back (spec §2.12). */
    val deletedCaptions = LinkedHashMap<NoticeId, String>()
    /** The month start day never changes during a run, so Db.monthOf(date) can be cached. */
    val monthCache = HashMap<LocalDate, YearMonth>()

    fun log(s: String) {
        log.add("#$step [$today] $s")
    }

    suspend fun mo(d: LocalDate): YearMonth = monthCache.getOrPut(d) { db.monthOf(d) }

    suspend fun run(steps: Int) {
        try {
            setup()
            checkAll(full = true)
            for (i in 1..steps) {
                step = i
                act()
                checkAll(full = i % 25 == 0)
            }
            step = steps + 1
            log("final full check")
            checkAll(full = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AssertionError(
                "seed=$seed startDay=$startDay step=$step today=$today: $e\n--- actions so far ---\n${log.joinToString("\n")}", e,
            )
        }
    }

    suspend fun setup() {
        log("setup startDay=$startDay")
        assertTrue(db.addSource(LINV_PKG, "Nubank", LINV_T0), "addSource")
        assertTrue(shadow.addSource(LINV_PKG, "Nubank", LINV_T0), "addSource (shadow)")
        assertTrue(db.setMonthStartDay(startDay, today), "setMonthStartDay($startDay)")
        assertTrue(shadow.setMonthStartDay(startDay, today), "setMonthStartDay (shadow)")
        assertEquals(startDay, db.settings().monthStartDay, "month start day set")
        repeat(3) { createBudgetAction() }
        repeat(6) { capturePurchaseAction() }
        repeat(2) { captureEstornoAction() }
        captureOtherAction()
        repeat(3) { acceptAction() }
        repeat(2) { addManualAction() }
    }

    suspend fun ensureHorizon() {
        db.ensureMonths(mo(today).linvShift(LINV_HORIZON + 2))
        shadow.ensureMonths(mo(today).linvShift(LINV_HORIZON + 2))
    }

    suspend fun snap() = LinvSnap(
        db.transactions(),
        db.pending().map { it.id }.toSet(),
        db.dismissed().map { it.id }.toSet(),
        db.tags(),
        db.version.value,
    )

    suspend fun assertUnchanged(before: LinvSnap, what: String) {
        assertEquals(before, snap(), "$what: nothing written and no version bump")
    }

    suspend fun status(n: NoticeId): NoticeStatus = db.notice(n).status

    // ---- actions ----

    suspend fun act() {
        val r = rnd.nextInt(100)
        when {
            r < 10 -> capturePurchaseAction()
            r < 16 -> captureEstornoAction()
            r < 19 -> captureOtherAction()
            r < 35 -> acceptAction()
            r < 43 -> addManualAction()
            r < 55 -> editAction()
            r < 63 -> roundTripAction()
            r < 70 -> deleteTransactionAction()
            r < 78 -> registerRefundTxAction()
            r < 83 -> undoRefundAction()
            r < 88 -> deleteTagAction()
            r < 92 -> setLimitAction()
            r < 94 -> createBudgetAction()
            r < 96 -> deleteBudgetAction()
            else -> advanceTodayAction()
        }
    }

    suspend fun captureNotice(text: String): NoticeId {
        captures++
        val at = LINV_T0 + captures * 600_000L
        val raw = RawNotice(
            packageName = LINV_PKG, appLabel = "Nubank", key = "linv-$captures", postedAt = at, whenMs = at,
            title = "Nubank", text = text, bigText = text,
        )
        assertTrue(capture(db, raw), "capture of notice $captures (a source, unique key and content)")
        assertTrue(capture(shadow, raw), "capture of notice $captures (shadow)")
        val row = assertNotNull(db.pending().firstOrNull { it.raw.key == raw.key }, "captured notice $captures is pending")
        val srow = assertNotNull(shadow.pending().firstOrNull { it.raw.key == raw.key }, "captured notice $captures is pending (shadow)")
        assertEquals(row.id, srow.id, "both databases number notices alike")
        notices.add(row.id)
        log("capture ${row.id.v} \"${linvShow(text)}\" -> parsed ${row.parsed.cents.v} \"${row.parsed.merchant}\" refund=${row.parsed.isRefund}")
        return row.id
    }

    suspend fun capturePurchaseAction() {
        captureNotice(linvPurchaseText(100L + rnd.nextLong(200_000), LINV_MERCHANTS.random(rnd)))
    }

    suspend fun captureEstornoAction() {
        // Usually the estorno of a purchase already captured (same value and merchant), as banks send it.
        if (notices.isNotEmpty() && rnd.nextInt(10) < 7) {
            val p = db.notice(notices.random(rnd)).parsed
            if (p.cents.v > 0 && p.merchant.isNotEmpty() && !p.isRefund) {
                captureNotice(linvEstornoText(p.cents.v, p.merchant))
                return
            }
        }
        captureNotice(linvEstornoText(100L + rnd.nextLong(200_000), LINV_MERCHANTS.random(rnd)))
    }

    suspend fun captureOtherAction() {
        val text = if (rnd.nextBoolean()) "Recebemos seu pagamento no valor de R$ ${linvBrl(1000L + rnd.nextLong(90_000))}. Obrigado!"
        else "Seu limite aumentou para R$ 5.000,00"
        captureNotice(text)
    }

    /** A random legenda: none, hand-picked edge texts, or words with breaks, edge spaces or over-length. */
    fun randomCaption(): String {
        val base = (1..1 + rnd.nextInt(5)).joinToString(" ") { LINV_CAPTION_WORDS.random(rnd) }
        return when (rnd.nextInt(11)) {
            0 -> ""
            1 -> LINV_CAPTION_TRICKS.random(rnd)
            2 -> " $base "
            3 -> "\t$base\n"
            4 -> base.replace(" ", if (rnd.nextBoolean()) "\n" else "\r\n")
            5 -> base + "\n" + (1..1 + rnd.nextInt(2)).joinToString(" ") { LINV_CAPTION_WORDS.random(rnd) }
            6 -> base + " " + "x".repeat(81) // over 80 after cleaning: refused
            7 -> "x".repeat(1 + rnd.nextInt(100)) // sometimes over 80
            8 -> base + " ".repeat(1 + rnd.nextInt(6))
            9 -> base + "\u2028" + base
            else -> base
        }
    }

    /** A legenda that always cleans to something non-empty and within the 80 units. */
    fun validCaption(): String {
        val words = (2..5).map { LINV_CAPTION_WORDS.random(rnd) }.joinToString(" ")
        return if (rnd.nextBoolean()) " $words " else words
    }

    fun randomForm(merchant: String, exact: Parsed? = null): TransactionForm {
        val budget = if (budgets.isEmpty() || rnd.nextInt(14) == 0) (goneBudgets + LINV_UNKNOWN_BUDGET).random(rnd) else budgets.keys.random(rnd)
        val tags = if (rnd.nextInt(12) == 0) listOf("", "  ", " ").take(rnd.nextInt(3)) else List(1 + rnd.nextInt(3)) { LINV_TAGS.random(rnd) }
        // A third of the forms of a notice reproduce its Parsed exactly: corrected stays false with a legenda.
        val ex = if (exact != null && exact.cents.v > 0 && rnd.nextInt(3) == 0) exact else null
        val total = if (ex == null) (if (rnd.nextInt(14) == 0) -rnd.nextLong(2) * 100 else 100L + rnd.nextLong(300_000)) else ex.cents.v
        val n = if (ex == null) when (rnd.nextInt(20)) {
            0 -> if (rnd.nextBoolean()) 0 else 401
            1, 2, 3, 4, 5 -> 2 + rnd.nextInt(LINV_HORIZON - 1)
            else -> 1
        } else 1
        var date = if (ex == null) today.linvDays(-rnd.nextInt(160)) else ex.date
        if (date < LINV_DATE_MIN) date = LINV_DATE_MIN
        val time = if (ex == null) LocalTime(rnd.nextInt(24), rnd.nextInt(60)) else ex.time
        val alias = when (rnd.nextInt(7)) {
            0 -> "  "
            1 -> LINV_ALIASES.random(rnd)
            else -> ""
        }
        return TransactionForm(
            budget = budget, tags = tags, total = Cents(total), installments = n,
            merchant = if (ex == null) merchant else ex.merchant, date = date, time = time,
            alias = alias, caption = randomCaption(),
        )
    }

    /** Db.accept KDoc: refused unless the budget exists, ≥1 tag after cleaning, total > 0, installments 1..400, legenda ≤ 80 (D53). */
    fun valid(f: TransactionForm) =
        f.budget in budgets && linvCleanTags(f.tags).isNotEmpty() && f.total.v > 0 && f.installments in 1..400 && linvCaptionFits(f.caption)

    fun describe(f: TransactionForm) =
        "budget=${f.budget.v} tags=${f.tags} total=${f.total.v} n=${f.installments} \"${f.merchant}\" ${f.date} ${f.time} " +
            "alias=\"${linvShow(f.alias)}\" caption=\"${linvShow(f.caption)}\""

    /** The transaction as [f] described it (Db.accept KDoc, TransactionForm and TransactionRow KDoc). */
    suspend fun verifyTx(id: TransactionId, f: TransactionForm, notice: NoticeId, estorno: NoticeId, budgetName: String) {
        val d = db.transaction(id)
        val r = d.row
        assertEquals(id, r.id, "transaction ${id.v} exists")
        assertEquals(notice, r.notice, "transaction notice")
        assertEquals(f.budget, r.budget, "transaction budget")
        assertEquals(budgetName, r.budgetName, "transaction budget name")
        assertEquals(f.merchant.trim(), r.merchant, "transaction merchant (bank text, trimmed)")
        assertEquals(f.total, r.total, "transaction total")
        assertEquals(f.installments, r.installments, "transaction installments")
        assertEquals(f.date, r.date, "transaction date")
        assertEquals(f.time, r.time, "transaction time")
        assertEquals(linvCleanTags(f.tags).sorted(), r.tags, "transaction tags: cleaned and sorted")
        assertEquals(estorno, r.estorno, "transaction estorno")
        assertEquals(f.installments, d.installments.size, "one installment row per installment")
        assertEquals(f.total.v, d.installments.sumOf { it.cents.v }, "Σ installments = total")

        // The legenda read back is the cleaned text (spec §2.12, TransactionForm.caption KDoc).
        val cap = linvCleanCaption(f.caption)
        assertEquals(cap, r.caption, "transaction ${id.v}: the legenda read back is the cleaned text")
        assertTrue(r.caption.length <= 80, "transaction ${id.v}: legenda at most 80 units")
        assertFalse(r.caption.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }, "transaction ${id.v}: legenda without line breaks")
        // D54: the name never becomes the legenda; the legenda is the title, the name is the "em …" credit.
        assertEquals(r.alias.ifEmpty { r.merchant }, r.name, "transaction ${id.v}: name = alias.ifEmpty { merchant }")
        assertEquals(r.caption.ifEmpty { r.name }, r.title, "transaction ${id.v}: title = caption.ifEmpty { name }")
        // §2.12: the legenda is not an emenda: corrected only when total, merchant, date or time differ.
        val expectCorrected = notice.v != 0L && db.notice(notice).parsed.let {
            it.cents != f.total || it.merchant != f.merchant.trim() || it.date != f.date || it.time != f.time
        }
        assertEquals(expectCorrected, r.corrected, "transaction ${id.v}: a legenda never sets corrected (§2.12)")
    }

    suspend fun acceptAction() {
        val pend = db.pending()
        val row = if (pend.isEmpty() || rnd.nextInt(10) == 0) null else pend.random(rnd)
        val pick = row?.id ?: (notices.filter { n -> pend.none { it.id == n } } + LINV_UNKNOWN_NOTICE).random(rnd)
        val merchant = row?.parsed?.merchant?.ifEmpty { null } ?: LINV_MERCHANTS.random(rnd)
        val f0 = randomForm(merchant, row?.parsed)
        // A relaunch usually goes without legenda: nothing of the deleted one may come back (spec §2.12).
        val f = if (pick in deletedCaptions && rnd.nextBoolean()) f0.copy(caption = "") else f0
        val expect = row != null && valid(f)
        val before = snap()
        val id = db.accept(pick, f)
        log("accept notice=${pick.v} pending=${row != null} ${describe(f)} -> ${id.v}")
        assertEquals(expect, id.v != 0L, "accept accepted/refused (KDoc)")
        if (id.v == 0L) {
            assertUnchanged(before, "refused accept")
            return
        }
        assertFalse(id in txIds, "accept returns an id not in use")
        txIds.add(id)
        captions[id] = linvCleanCaption(f.caption)
        verifyTx(id, f, pick, NoticeId(0), budgets.getValue(f.budget))
        val sid = shadow.accept(pick, f.copy(caption = ""))
        assertEquals(id, sid, "the same write without legenda yields the same transaction id")
        assertEquals(NoticeStatus.PROCESSED, status(pick), "accepted notice is processed")
        val back = deletedCaptions.remove(pick)
        if (back != null) {
            assertEquals(
                linvCleanCaption(f.caption), db.transaction(id).row.caption,
                "relaunching brings no legenda back (the deleted one was \"${linvShow(back)}\")",
            )
        }
    }

    suspend fun addManualAction() {
        val merchant = if (rnd.nextInt(8) == 0) "" else LINV_MERCHANTS.random(rnd)
        val f = randomForm(merchant)
        val expect = valid(f)
        val before = snap()
        val id = db.addManual(f)
        log("addManual ${describe(f)} -> ${id.v}")
        assertEquals(expect, id.v != 0L, "addManual accepted/refused (same rules as accept)")
        if (id.v == 0L) {
            assertUnchanged(before, "refused addManual")
            return
        }
        assertFalse(id in txIds, "addManual returns an id not in use")
        txIds.add(id)
        captions[id] = linvCleanCaption(f.caption)
        verifyTx(id, f, NoticeId(0), NoticeId(0), budgets.getValue(f.budget))
        assertFalse(db.transaction(id).row.corrected, "a manual transaction is never corrected")
        val sid = shadow.addManual(f.copy(caption = ""))
        assertEquals(id, sid, "the same write without legenda yields the same transaction id")
    }

    suspend fun editAction() {
        val known = txIds.isNotEmpty() && rnd.nextInt(12) != 0
        val id = if (known) txIds.random(rnd) else (goneTx + LINV_UNKNOWN_TX).random(rnd)
        val old = if (known) db.transaction(id).row else null
        val exact = if (old == null || old.notice.v == 0L) null else db.notice(old.notice).parsed
        val f = randomForm(if (old != null && rnd.nextBoolean()) old.merchant else LINV_MERCHANTS.random(rnd), exact)
        val expect = old != null && valid(f)
        val before = snap()
        val ok = db.edit(id, f)
        log("edit ${id.v} known=$known refunded=${old?.refunded} oldCaption=\"${linvShow(old?.caption ?: "")}\" ${describe(f)} -> $ok")
        assertEquals(expect, ok, "edit accepted/refused (same rules as accept; false if unknown)")
        if (!ok) {
            assertUnchanged(before, "refused edit")
            return
        }
        old!!
        // A registered estorno stays (Db.edit KDoc); the notice is the same. Blank caption removes the
        // legenda (unlike the alias, §2.10).
        verifyTx(id, f, old.notice, old.estorno, budgets.getValue(f.budget))
        captions[id] = linvCleanCaption(f.caption)
        if (f.caption.isBlank()) {
            assertEquals("", db.transaction(id).row.caption, "edit with a blank legenda removes it (§2.10)")
        }
        assertTrue(shadow.edit(id, f.copy(caption = "")), "the same edit without legenda (shadow)")
    }

    /**
     * spec §2.12, D54: a row with legenda can be edited without one and goes back to the behavior it
     * had — legenda → "" removes, the same legenda again restores the whole row; only the caption moves.
     */
    suspend fun roundTripAction() {
        val rows = db.transactions()
        if (rows.isEmpty()) return
        val r = rows.random(rnd)
        fun form(caption: String) = TransactionForm(
            budget = r.budget, tags = r.tags, total = r.total, installments = r.installments,
            merchant = r.merchant, date = r.date, time = r.time, alias = "", caption = caption,
        )
        val suggestionBefore = db.suggestion(r.merchant)
        if (r.caption.isEmpty()) {
            val cap = validCaption()
            assertTrue(db.edit(r.id, form(cap)), "round trip: edit with a legenda")
            val mid = db.transaction(r.id).row
            assertEquals(linvCleanCaption(cap), mid.caption, "round trip: the legenda goes in")
            assertEquals(mid.caption, mid.title, "round trip: with legenda, the title is the legenda")
            assertEquals(r.name, mid.name, "round trip: the name never becomes the legenda")
            assertTrue(shadow.edit(r.id, form("")), "round trip (shadow)")
            assertTrue(db.edit(r.id, form("")), "round trip: legenda → \"\" removes it")
            assertEquals(r, db.transaction(r.id).row, "round trip: back to the behavior it had")
            assertTrue(shadow.edit(r.id, form("")), "round trip (shadow)")
            captions[r.id] = ""
            log("roundTrip tx=${r.id.v} sem legenda -> \"${linvShow(cap)}\" -> sem legenda")
        } else {
            assertTrue(db.edit(r.id, form("")), "round trip: legenda → \"\" removes it")
            val cleared = db.transaction(r.id).row
            assertEquals("", cleared.caption, "round trip: legenda → \"\" removes it")
            assertEquals(cleared.name, cleared.title, "round trip: without legenda the title is the name")
            assertEquals(r.name, cleared.name, "round trip: the name is untouched")
            assertTrue(shadow.edit(r.id, form("")), "round trip (shadow)")
            assertTrue(db.edit(r.id, form(r.caption)), "round trip: the same legenda again")
            assertEquals(r, db.transaction(r.id).row, "round trip: the whole row is the one of before")
            assertTrue(shadow.edit(r.id, form("")), "round trip (shadow)")
            captions[r.id] = r.caption
            log("roundTrip tx=${r.id.v} \"${linvShow(r.caption)}\" -> sem legenda -> de volta")
        }
        // §2.5, §2.12: edits that only touch the legenda leave the suggestion alone (and it has none).
        assertEquals(suggestionBefore, db.suggestion(r.merchant), "the suggestion is untouched by a legenda-only edit")
        assertEquals(db.suggestion(r.merchant), shadow.suggestion(r.merchant), "the suggestion is the same without legenda")
    }

    suspend fun deleteTransactionAction() {
        val known = txIds.isNotEmpty() && rnd.nextInt(10) != 0
        val id = if (known) txIds.random(rnd) else (goneTx + LINV_UNKNOWN_TX).random(rnd)
        val r = if (known) db.transaction(id).row else null
        val before = snap()
        db.deleteTransaction(id)
        log("deleteTransaction ${id.v} known=$known notice=${r?.notice?.v} caption=\"${linvShow(r?.caption ?: "")}\"")
        if (r == null) {
            assertUnchanged(before, "deleteTransaction of an unknown id")
            return
        }
        shadow.deleteTransaction(id)
        txIds.remove(id)
        captions.remove(id)
        goneTx.add(id)
        assertEquals(TransactionDetail(), db.transaction(id), "zero detail after delete")
        assertEquals(before.txs.filter { it.id != id }, db.transactions(), "deleteTransaction removes exactly that row")
        if (r.notice.v != 0L) {
            assertEquals(NoticeStatus.PENDING, status(r.notice), "spec §2.8: its notice is back in the Pauta")
            if (r.caption.isNotEmpty()) deletedCaptions[r.notice] = r.caption
        }
        if (r.refunded) assertEquals(NoticeStatus.PENDING, status(r.estorno), "spec §2.8, §6: its estorno is back in the Pauta")
    }

    /** An estorno to register: usually a pending notice parsed as an estorno; sometimes any notice. */
    fun pickEstorno(pend: List<NoticeRow>): NoticeRow? {
        val estornos = pend.filter { it.parsed.isRefund }
        return when {
            rnd.nextInt(10) == 0 -> null
            estornos.isNotEmpty() && rnd.nextInt(6) != 0 -> estornos.random(rnd)
            else -> pend.randomOrNull(rnd)
        }
    }

    suspend fun registerRefundTxAction() {
        val pend = db.pending()
        val eRow = pickEstorno(pend)
        val e = eRow?.id ?: (notices.filter { n -> pend.none { it.id == n } } + LINV_UNKNOWN_NOTICE).random(rnd)
        val txs = db.transactions()
        val free = txs.filter { !it.refunded }
        val t = when {
            free.isNotEmpty() && rnd.nextInt(10) < 8 -> free.random(rnd).id
            else -> (txs.filter { it.refunded }.map { it.id } + goneTx + LINV_UNKNOWN_TX).random(rnd)
        }
        val tRow = txs.firstOrNull { it.id == t }
        // Ambiguity: the KDoc doesn't say whether a pending notice not parsed as an estorno may be
        // registered as one; only estorno texts get a predicted success.
        val expect: Boolean? = when {
            eRow == null -> false
            tRow == null || tRow.refunded -> false
            eRow.parsed.isRefund -> true
            else -> null
        }
        val before = snap()
        val ok = db.registerRefund(e, t)
        log("registerRefund estorno=${e.v} tx=${t.v} (known=${tRow != null} refunded=${tRow?.refunded} caption=\"${linvShow(tRow?.caption ?: "")}\") -> $ok")
        if (expect != null) assertEquals(expect, ok, "registerRefund(notice, transaction) accepted/refused (KDoc)")
        if (!ok) {
            assertUnchanged(before, "refused registerRefund")
            return
        }
        assertTrue(shadow.registerRefund(e, t), "the same estorno without legenda (shadow)")
        tRow!!
        val after = db.transaction(t).row
        assertEquals(tRow.copy(estorno = e), after, "registerRefund changes only the estorno")
        assertEquals(tRow.caption, after.caption, "estornar não mexe na legenda (§6 × §2.12)")
        assertEquals(tRow.name, after.name, "a linha estornada mantém o nome")
        assertEquals(tRow.title, after.title, "a linha estornada mantém o título")
        assertEquals(NoticeStatus.PROCESSED, status(e), "estorno notice is processed")
    }

    suspend fun undoRefundAction() {
        val txs = db.transactions()
        val onTx = txs.filter { it.refunded }.associateBy { it.estorno }
        val pick = if (onTx.isNotEmpty() && rnd.nextInt(10) < 8) onTx.keys.random(rnd) else (notices + LINV_UNKNOWN_NOTICE).random(rnd)
        val expect = pick in onTx
        val before = snap()
        val ok = db.undoRefund(pick)
        log("undoRefund ${pick.v} registered=$expect -> $ok")
        assertEquals(expect, ok, "undoRefund: true only for a registered estorno")
        if (!ok) {
            assertUnchanged(before, "refused undoRefund")
            return
        }
        shadow.undoRefund(pick)
        assertEquals(NoticeStatus.PENDING, status(pick), "spec §6: the estorno is back in the Pauta")
        val t = onTx.getValue(pick)
        val after = db.transaction(t.id).row
        assertEquals(t.copy(estorno = NoticeId(0)), after, "undoRefund changes only the estorno")
        assertEquals(t.caption, after.caption, "desestornar não mexe na legenda (§6 × §2.12)")
        assertEquals(t.name, after.name, "desestornar mantém o nome")
        assertEquals(t.title, after.title, "desestornar mantém o título")
    }

    suspend fun deleteTagAction() {
        val tags = db.tags()
        val t = if (tags.isEmpty() || rnd.nextInt(10) == 0) null else tags.random(rnd)
        val before = snap()
        db.deleteTag(t?.id ?: LINV_UNKNOWN_TAG)
        log("deleteTag ${t?.id?.v ?: LINV_UNKNOWN_TAG.v} \"${t?.name}\"")
        if (t == null) {
            assertUnchanged(before, "deleteTag of an unknown tag")
            return
        }
        shadow.deleteTag(t.id)
        val removed = before.txs.filter { it.tags == listOf(t.name) }
        for (r in removed) {
            txIds.remove(r.id)
            captions.remove(r.id)
            goneTx.add(r.id)
            if (r.notice.v != 0L && r.caption.isNotEmpty()) deletedCaptions[r.notice] = r.caption
        }
        val after = db.transactions().associateBy { it.id }
        assertEquals(before.txs.filter { it !in removed }.map { it.id }.toSet(), after.keys, "spec §2.9: only the transactions left without tags are deleted")
        for (r in before.txs) {
            val a = after[r.id] ?: continue
            assertEquals(r.tags - t.name, a.tags, "deleteTag: the tag leaves transaction ${r.id.v}")
            assertEquals(r.caption, a.caption, "deleteTag: the legenda of transaction ${r.id.v} survives")
        }
        assertTrue(db.tags().none { it.id == t.id }, "deleteTag: the tag is gone")
        for (r in removed) {
            if (r.notice.v != 0L) assertEquals(NoticeStatus.PENDING, status(r.notice), "spec §2.9: the notices go back to the Pauta")
            if (r.refunded) assertEquals(NoticeStatus.PENDING, status(r.estorno), "the estorno of a deleted transaction goes back to the Pauta")
        }
    }

    suspend fun setLimitAction() {
        val known = budgets.isNotEmpty() && rnd.nextInt(8) != 0
        val id = if (known) budgets.keys.random(rnd) else (goneBudgets + LINV_UNKNOWN_BUDGET).random(rnd)
        val limit = when (rnd.nextInt(10)) {
            0 -> 0L
            1 -> -100L
            else -> 20_000L + rnd.nextLong(500_000)
        }
        val before = snap()
        val ok = db.setLimit(id, Cents(limit), today)
        log("setLimit ${id.v} $limit -> $ok")
        assertEquals(known && limit > 0, ok, "setLimit: false for limit ≤ 0 or an unknown budget")
        if (!ok) {
            assertUnchanged(before, "refused setLimit")
            return
        }
        shadow.setLimit(id, Cents(limit), today)
        assertEquals(before.txs, db.transactions(), "mudar a cota não mexe nos lançamentos nem nas legendas")
    }

    suspend fun createBudgetAction() {
        budgetCounter++
        val name = "Verba $budgetCounter"
        val limit = 20_000L + rnd.nextLong(500_000)
        val id = db.createBudget(name, Cents(limit), LINV_BUDGET_DAY)
        log("createBudget \"$name\" $limit -> ${id.v}")
        assertTrue(id.v != 0L, "createBudget of a new name")
        budgets[id] = name
        assertEquals(id, shadow.createBudget(name, Cents(limit), LINV_BUDGET_DAY), "the same budget without legenda (shadow)")
        ensureHorizon()
    }

    suspend fun deleteBudgetAction() {
        val known = budgets.isNotEmpty() && rnd.nextInt(6) != 0
        val id = if (known) budgets.keys.random(rnd) else (goneBudgets + LINV_UNKNOWN_BUDGET).random(rnd)
        val before = snap()
        db.deleteBudget(id)
        log("deleteBudget ${id.v} known=$known")
        if (!known) {
            assertUnchanged(before, "deleteBudget of an unknown id")
            return
        }
        shadow.deleteBudget(id)
        budgets.remove(id)
        goneBudgets.add(id)
        val removed = before.txs.filter { it.budget == id }
        for (r in removed) {
            txIds.remove(r.id)
            captions.remove(r.id)
            goneTx.add(r.id)
            if (r.notice.v != 0L && r.caption.isNotEmpty()) deletedCaptions[r.notice] = r.caption
        }
        assertEquals(before.txs.filter { it.budget != id }.map { it.id }.toSet(), db.transactions().map { it.id }.toSet(), "spec §3: the budget's transactions are deleted, no other")
        for (r in removed) {
            if (r.notice.v != 0L) assertEquals(NoticeStatus.PENDING, status(r.notice), "spec §3: the notices go back to the Pauta")
            if (r.refunded) assertEquals(NoticeStatus.PENDING, status(r.estorno), "the estorno of a deleted transaction goes back to the Pauta")
        }
    }

    suspend fun advanceTodayAction() {
        val n = 1 + rnd.nextInt(25)
        today = today.linvDays(n)
        log("today += $n")
        ensureHorizon()
    }

    // ---- invariants ----

    suspend fun checkAll(full: Boolean) {
        val txs = db.transactions()
        val ids = txs.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "transactions() lists each transaction once")
        assertEquals(txIds, ids.toSet(), "transactions() lists exactly the transactions alive")

        // The same state without legenda: same rows with caption "", same Pauta, Riscados and tags.
        val stxs = shadow.transactions()
        assertEquals(txs.map { it.copy(caption = "") }, stxs, "sem legenda: the very same transactions")
        assertEquals(db.pending().map { it.id }.toSet(), shadow.pending().map { it.id }.toSet(), "sem legenda: the same Pauta")
        assertEquals(db.dismissed().map { it.id }.toSet(), shadow.dismissed().map { it.id }.toSet(), "sem legenda: the same Riscados")
        assertEquals(db.tags(), shadow.tags(), "sem legenda: the same tags")

        for (r in txs) {
            assertEquals(captions[r.id], r.caption, "row ${r.id.v}: the legenda the form wrote is the legenda read back (cleaned)")
            assertEquals(r.alias.ifEmpty { r.merchant }, r.name, "row ${r.id.v}: name = alias.ifEmpty { merchant }; the name never becomes the legenda")
            assertEquals(r.caption.ifEmpty { r.name }, r.title, "row ${r.id.v}: title = caption.ifEmpty { name }")
            assertTrue(r.caption.length <= 80, "row ${r.id.v}: legenda at most 80 units")
            assertTrue(r.caption.none { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }, "row ${r.id.v}: legenda without line breaks")
            assertEquals(r.caption.trim(), r.caption, "row ${r.id.v}: legenda without edge spaces")
            assertEquals(linvCleanCaption(r.caption), r.caption, "row ${r.id.v}: the stored legenda is the cleaned text")
        }

        // Refunded rows keep the legenda and the name (spec §6 × §2.12), also through the edit screen's read.
        val refunded = txs.filter { it.refunded }
        for (r in if (full) refunded else refunded.shuffled(rnd).take(3)) {
            val d = db.transaction(r.id)
            assertEquals(r, d.row, "refunded row ${r.id.v}: transaction() = its transactions() row")
            assertEquals(r.caption, d.row.caption, "refunded row ${r.id.v}: the estorno keeps the legenda")
            assertEquals(r.name, d.row.name, "refunded row ${r.id.v}: the estorno keeps the name")
        }
        if (full) {
            for (r in txs) assertEquals(r, db.transaction(r.id).row, "row ${r.id.v}: transaction() = its transactions() row")
        } else {
            for (r in txs.shuffled(rnd).take(3)) assertEquals(r, db.transaction(r.id).row, "row ${r.id.v}: transaction() = its transactions() row")
        }

        // §2.5, §2.12: the suggestion never carries a legenda — identical with and without captions.
        for (m in LINV_MERCHANTS + listOf("")) {
            assertEquals(shadow.suggestion(m), db.suggestion(m), "suggestion of \"${linvShow(m)}\": the same without legenda")
        }

        // §7.5: the Leitura ignores the legenda — same readings with and without captions.
        val to = mo(today)
        val from = to.linvShift(-6)
        assertEquals(shadow.history(from, to), db.history(from, to), "$from..$to: history is the same without legenda")
        val all = linvMonths(mo(LINV_DATE_MIN), to)
        for (m in listOf(all.random(rnd), to)) {
            val (ws, we) = linvWindow(m, startDay)
            assertEquals(shadow.tagRanking(ws, we), db.tagRanking(ws, we), "$m: tagRanking is the same without legenda")
            assertEquals(shadow.merchantRanking(ws, we), db.merchantRanking(ws, we), "$m: merchantRanking is the same without legenda (a legenda doesn't enter, §7.5)")
            assertEquals(shadow.spentByDay(ws, we), db.spentByDay(ws, we), "$m: spentByDay is the same without legenda")
        }

        // §7.1: the budget detail shows the row of the list, legenda included.
        if (budgets.isNotEmpty()) checkDetail(budgets.keys.random(rnd), all.random(rnd), txs)

        // §7.3: the estorno candidates carry the legenda; a pending recado always has "" (recados have none).
        val pend = db.pending()
        val estornos = pend.filter { it.parsed.isRefund }
        if (estornos.isNotEmpty()) checkCandidates(estornos.random(rnd), txs, pend)
    }

    suspend fun checkDetail(b: BudgetId, month: YearMonth, txs: List<TransactionRow>) {
        val d = db.budgetDetail(b, month, today)
        val sd = shadow.budgetDetail(b, month, today)
        assertEquals(d.month, sd.month, "budgetDetail ${b.v} $month: the month is the same without legenda")
        assertEquals(d.lines.map { it.copy(row = it.row.copy(caption = "")) }, sd.lines, "budgetDetail ${b.v} $month: the same lines without legenda")
        val byId = txs.associateBy { it.id }
        for (line in d.lines) {
            assertEquals(byId[line.row.id], line.row, "budgetDetail ${b.v} $month line ${line.row.id.v}: the row of the list, legenda included (spec §7.1)")
        }
    }

    /** refundCandidates KDoc (spec §7.3, D54): caption, alias and bank text for the search and the "em …" credit. */
    suspend fun checkCandidates(e: NoticeRow, txs: List<TransactionRow>, pend: List<NoticeRow>) {
        val cands = db.refundCandidates(e.id)
        val scands = shadow.refundCandidates(e.id)
        assertEquals(cands.map { it.copy(caption = "") }, scands, "refundCandidates: the same list without legenda")
        for (c in cands) assertTrue((c.transaction.v != 0L) != (c.notice.v != 0L), "candidate $c: exactly one id is non-zero")
        val byId = txs.associateBy { it.id }
        for (c in cands.filter { it.transaction.v != 0L }) {
            val r = assertNotNull(byId[c.transaction], "candidate transaction ${c.transaction.v} exists")
            assertFalse(r.refunded, "refundCandidates never includes a refunded purchase (${r.id.v})")
            assertEquals(r.caption, c.caption, "candidate ${r.id.v}: the legenda of the row (spec §7.3)")
            assertEquals(r.name, c.name, "candidate ${r.id.v} name: alias, else merchant")
            assertEquals(r.alias, c.alias, "candidate ${r.id.v}: alias for the search")
            assertEquals(r.merchant, c.merchant, "candidate ${r.id.v}: bank text for the search")
            assertEquals(c.caption.ifEmpty { c.name }, c.title, "candidate ${r.id.v}: title = caption.ifEmpty { name }")
            assertEquals(r.total, c.total, "candidate ${r.id.v} total")
            assertEquals(r.date, c.date, "candidate ${r.id.v} date")
        }
        val pendById = pend.associateBy { it.id }
        for (c in cands.filter { it.notice.v != 0L }) {
            assertTrue(c.notice != e.id, "refundCandidates never includes the estorno itself")
            val n = assertNotNull(pendById[c.notice], "refundCandidates never includes a notice not pending (${c.notice.v})")
            assertFalse(n.parsed.isRefund, "refundCandidates never includes a notice parsed as an estorno (${n.id.v})")
            assertEquals("", c.caption, "candidate notice ${c.notice.v}: a pending recado always has caption \"\" (recados have no legenda)")
            assertEquals(n.alias.ifEmpty { n.parsed.merchant }, c.name, "candidate notice ${c.notice.v}: alias, else merchant")
            assertEquals(n.alias, c.alias, "candidate notice ${c.notice.v}: alias for the search")
            assertEquals(n.parsed.merchant, c.merchant, "candidate notice ${c.notice.v}: bank text for the search")
            assertEquals(c.name, c.title, "candidate notice ${c.notice.v}: without legenda the title is the name")
        }
        val firstUnlikely = cands.indexOfFirst { !it.likely }
        if (firstUnlikely >= 0) assertTrue(cands.drop(firstUnlikely).none { it.likely }, "refundCandidates: likely ones first")
    }
}

class LegendaInvariantsTest {

    private fun randomRun(seed: Int, startDay: Int, steps: Int = 100) = runTest(timeout = 180.seconds) {
        withLegendaDb { main, shadow -> LegendaInvariantRun(main, shadow, seed, startDay).run(steps) }
    }

    @Test fun `random actions with random legenda keep the invariants with seed 5 and month start day 1`() = randomRun(5, 1)

    @Test fun `random actions with random legenda keep the invariants with seed 17 and month start day 31`() = randomRun(17, 31)

    @Test fun `random actions with random legenda keep the invariants with seed 42 and month start day 29`() = randomRun(42, 29)

    @Test fun `random actions with random legenda keep the invariants with seed 909 and month start day 30`() = randomRun(909, 30)

    @Test fun `random actions with random legenda keep the invariants with seed 2026 and month start day 10`() = randomRun(2026, 10)
}
