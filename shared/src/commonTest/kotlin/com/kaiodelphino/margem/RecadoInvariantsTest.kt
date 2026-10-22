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

// Invariants of the recado cycle (block 4: Lançar, editar, lançamento manual, estorno, Lançamentos;
// spec §2, §3, §6, §7.2–§7.4; architecture D6, D7, §5), checked through the public API only (Db,
// capture) after every step of random, fixed-seed sequences of captures (purchases, estornos, other
// texts), accept, addManual, edit, deleteTransaction, dismiss/restore, registerRefund (against a
// transaction and against a notice), undoRefund, createBudget/deleteBudget, deleteTag, mergeTags,
// with `today` moving forward. The month start day is set once, before any transaction, and never
// changes during a run (so "at most one installment per month", D7, holds by construction).
//
// Expected values come from the spec and the KDoc: the test never recomputes installment dates or
// values, it only sums and compares what the API returns (Db.monthOf tells which budget month a
// returned date falls in).

private val RINV_TODAY0 = LocalDate(2026, 10, 14)
/** Purchases are never dated before this. */
private val RINV_DATE_MIN = LocalDate(2026, 5, 1)
/** Budgets are created "on" this day, so every month a purchase can touch has a limit row. */
private val RINV_BUDGET_DAY = LocalDate(2026, 3, 1)
private const val RINV_PKG = "com.nu.production"
/** Epoch ms; notices are posted 10 minutes apart from here. */
private const val RINV_T0 = 1_791_000_000_000L
/** Most installments a random form asks for; also how far ahead limit rows and spent checks go. */
private const val RINV_HORIZON = 18
private val RINV_MERCHANTS = listOf("PADARIA SAO JOSE", "MERCADO BOM PRECO", "FARMACIA SAO JOAO", "POSTO IPIRANGA", "LOJA X")
private val RINV_TAGS = listOf("feira", " Padaria ", "CAFE", "farmacia", "lazer", "casa", "padaria")
private val RINV_UNKNOWN_BUDGET = BudgetId(987_654)
private val RINV_UNKNOWN_TX = TransactionId(987_654)
private val RINV_UNKNOWN_NOTICE = NoticeId(987_654)
private val RINV_UNKNOWN_TAG = TagId(987_654)

private fun YearMonth.rinvShift(n: Int): YearMonth = if (n >= 0) plus(n, DateTimeUnit.MONTH) else minus(-n, DateTimeUnit.MONTH)

private fun LocalDate.rinvDays(n: Int): LocalDate = if (n >= 0) plus(n, DateTimeUnit.DAY) else minus(-n, DateTimeUnit.DAY)

/** "1.234,56" */
private fun rinvBrl(c: Long): String {
    val reais = (c / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return "$reais,${(c % 100).toString().padStart(2, '0')}"
}

private fun rinvPurchaseText(cents: Long, merchant: String) =
    "Compra de R$ ${rinvBrl(cents)} APROVADA em $merchant para o cartão com final 1234."

private fun rinvEstornoText(cents: Long, merchant: String) =
    "A compra em $merchant no valor de R$ ${rinvBrl(cents)} foi estornada no seu cartão."

/** TransactionForm.tags KDoc: trimmed, lowercased, blanks and repeats dropped. */
private fun rinvCleanTags(tags: List<String>) = tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()

/** A real SQLite file per test, deleted afterwards with its -wal and -shm. */
private suspend fun withRecadoDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-rinv-${Random.Default.nextLong().toULong()}.db").toString()
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

/** What a refused write must leave untouched ("nothing written", and no version bump: Db.version KDoc). */
private data class RinvSnap(
    val txs: List<TransactionRow>,
    val pending: Set<NoticeId>,
    val dismissed: Set<NoticeId>,
    val tags: List<TagRow>,
    val version: Long,
)

/**
 * One random run, with a small model of what the test did: notices captured, budgets alive, transactions
 * alive, which notices it dismissed, and the estornos registered against a notice (only visible through
 * the notices' status, so the model keeps them).
 */
private class RecadoInvariantRun(val db: Db, val seed: Int, val startDay: Int) {
    val rnd = Random(seed)
    val log = ArrayList<String>()
    var step = 0
    var today = RINV_TODAY0
    var captures = 0
    var budgetCounter = 0

    val notices = ArrayList<NoticeId>()
    val budgets = LinkedHashMap<BudgetId, String>()
    val goneBudgets = ArrayList<BudgetId>()
    val txIds = LinkedHashSet<TransactionId>()
    val goneTx = ArrayList<TransactionId>()
    val dismissedModel = HashSet<NoticeId>()
    /** estorno notice -> purchase notice, for registerRefund(NoticeId, NoticeId). */
    val noticeRefunds = LinkedHashMap<NoticeId, NoticeId>()
    /** The month start day never changes during a run, so Db.monthOf(date) can be cached. */
    val monthCache = HashMap<LocalDate, YearMonth>()

    fun log(s: String) {
        log.add("#$step [$today] $s")
    }

    suspend fun mo(d: LocalDate): YearMonth = monthCache.getOrPut(d) { db.monthOf(d) }

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
            throw AssertionError(
                "seed=$seed startDay=$startDay step=$step today=$today: $e\n--- actions so far ---\n${log.joinToString("\n")}", e,
            )
        }
    }

    suspend fun setup() {
        log("setup startDay=$startDay")
        assertTrue(db.addSource(RINV_PKG, "Nubank", RINV_T0), "addSource")
        assertTrue(db.setMonthStartDay(startDay, today), "setMonthStartDay($startDay)")
        assertEquals(startDay, db.settings().monthStartDay, "month start day set")
        repeat(3) { createBudgetAction() }
        repeat(6) { capturePurchaseAction() }
        repeat(2) { captureEstornoAction() }
        captureOtherAction()
        repeat(3) { acceptAction() }
        repeat(2) { addManualAction() }
    }

    suspend fun ensureHorizon() {
        db.ensureMonths(mo(today).rinvShift(RINV_HORIZON + 2))
    }

    suspend fun snap() = RinvSnap(
        db.transactions(),
        db.pending().map { it.id }.toSet(),
        db.dismissed().map { it.id }.toSet(),
        db.tags(),
        db.version.value,
    )

    suspend fun assertUnchanged(before: RinvSnap, what: String) {
        assertEquals(before, snap(), "$what: nothing written and no version bump")
    }

    suspend fun remaining(b: BudgetId, m: YearMonth): Cents = db.budgetDetail(b, m, today).month.remaining

    suspend fun status(n: NoticeId): NoticeStatus = db.notice(n).status

    // ---- actions ----

    suspend fun act() {
        val r = rnd.nextInt(104)
        when {
            r < 14 -> capturePurchaseAction()
            r < 22 -> captureEstornoAction()
            r < 25 -> captureOtherAction()
            r < 43 -> acceptAction()
            r < 51 -> addManualAction()
            r < 59 -> editAction()
            r < 63 -> deleteTransactionAction()
            r < 69 -> dismissAction()
            r < 73 -> restoreAction()
            r < 81 -> registerRefundTxAction()
            r < 85 -> registerRefundNoticeAction()
            r < 90 -> undoRefundAction()
            r < 92 -> deleteBudgetAction()
            r < 94 -> createBudgetAction()
            r < 96 -> deleteTagAction()
            r < 99 -> mergeTagsAction()
            else -> advanceTodayAction()
        }
    }

    suspend fun captureNotice(text: String): NoticeId {
        captures++
        val at = RINV_T0 + captures * 600_000L
        val raw = RawNotice(
            packageName = RINV_PKG, appLabel = "Nubank", key = "rinv-$captures", postedAt = at, whenMs = at,
            title = "Nubank", text = text, bigText = text,
        )
        assertTrue(capture(db, raw), "capture of notice $captures (a source, unique key and content)")
        val row = assertNotNull(db.pending().firstOrNull { it.raw.key == raw.key }, "captured notice $captures is pending")
        notices.add(row.id)
        log("capture ${row.id.v} \"$text\" -> parsed ${row.parsed.cents.v} \"${row.parsed.merchant}\" refund=${row.parsed.isRefund}")
        return row.id
    }

    suspend fun capturePurchaseAction() {
        captureNotice(rinvPurchaseText(100L + rnd.nextLong(200_000), RINV_MERCHANTS.random(rnd)))
    }

    suspend fun captureEstornoAction() {
        // Usually the estorno of a purchase already captured (same value and merchant), as banks send it.
        if (notices.isNotEmpty() && rnd.nextInt(10) < 7) {
            val p = db.notice(notices.random(rnd)).parsed
            if (p.cents.v > 0 && p.merchant.isNotEmpty() && !p.isRefund) {
                captureNotice(rinvEstornoText(p.cents.v, p.merchant))
                return
            }
        }
        captureNotice(rinvEstornoText(100L + rnd.nextLong(200_000), RINV_MERCHANTS.random(rnd)))
    }

    suspend fun captureOtherAction() {
        val text = if (rnd.nextBoolean()) "Recebemos seu pagamento no valor de R$ ${rinvBrl(1000L + rnd.nextLong(90_000))}. Obrigado!"
        else "Seu limite aumentou para R$ 5.000,00"
        captureNotice(text)
    }

    fun randomForm(merchant: String): TransactionForm {
        val budget = if (budgets.isEmpty() || rnd.nextInt(14) == 0) (goneBudgets + RINV_UNKNOWN_BUDGET).random(rnd) else budgets.keys.random(rnd)
        val tags = if (rnd.nextInt(12) == 0) listOf("", "  ", " ").take(rnd.nextInt(3)) else List(1 + rnd.nextInt(3)) { RINV_TAGS.random(rnd) }
        val total = if (rnd.nextInt(14) == 0) -rnd.nextLong(2) * 100 else 100L + rnd.nextLong(300_000)
        val n = when (rnd.nextInt(20)) {
            0 -> if (rnd.nextBoolean()) 0 else 401
            1, 2, 3, 4, 5 -> 2 + rnd.nextInt(RINV_HORIZON - 1)
            else -> 1
        }
        var date = today.rinvDays(-rnd.nextInt(160))
        if (date < RINV_DATE_MIN) date = RINV_DATE_MIN
        return TransactionForm(
            budget = budget, tags = tags, total = Cents(total), installments = n, merchant = merchant,
            date = date, time = LocalTime(rnd.nextInt(24), rnd.nextInt(60)), alias = "",
        )
    }

    /** Db.accept KDoc: refused unless the budget exists, ≥1 tag after cleaning, total > 0, installments 1..400. */
    fun valid(f: TransactionForm) =
        f.budget in budgets && rinvCleanTags(f.tags).isNotEmpty() && f.total.v > 0 && f.installments in 1..400

    fun describe(f: TransactionForm) =
        "budget=${f.budget.v} tags=${f.tags} total=${f.total.v} n=${f.installments} \"${f.merchant}\" ${f.date} ${f.time}"

    /**
     * previewAccept for [f]: writes nothing; zero when the budget or the inputs aren't valid; otherwise
     * for the month of the first installment (the purchase date), and "before" is today's real margin
     * of the budget in that month. Null when zero.
     */
    suspend fun acceptPreview(f: TransactionForm, replacing: TransactionId): Preview? {
        val v0 = db.version.value
        val p = db.previewAccept(f.budget, f.date, f.total, f.installments, today, replacing)
        assertEquals(v0, db.version.value, "previewAccept writes nothing")
        if (f.budget !in budgets || f.total.v <= 0 || f.installments !in 1..400) {
            assertEquals(Preview(), p, "previewAccept is zero for an invalid budget or input (${describe(f)})")
            return null
        }
        assertEquals(mo(f.date), p.month, "previewAccept month = budget month of the purchase date")
        assertEquals(remaining(f.budget, p.month), p.before, "previewAccept before = real margin now")
        return p
    }

    /** The transaction as [f] described it (Db.accept KDoc, TransactionForm and TransactionRow KDoc). */
    suspend fun verifyTx(id: TransactionId, f: TransactionForm, notice: NoticeId, estorno: NoticeId) {
        val d = db.transaction(id)
        val r = d.row
        assertEquals(id, r.id, "transaction ${id.v} exists")
        assertEquals(notice, r.notice, "transaction notice")
        assertEquals(f.budget, r.budget, "transaction budget")
        assertEquals(budgets[f.budget], r.budgetName, "transaction budget name")
        assertEquals(f.merchant.trim(), r.merchant, "transaction merchant (bank text, trimmed)")
        assertEquals(f.total, r.total, "transaction total")
        assertEquals(f.installments, r.installments, "transaction installments")
        assertEquals(f.date, r.date, "transaction date")
        assertEquals(f.time, r.time, "transaction time")
        assertEquals(rinvCleanTags(f.tags).sorted(), r.tags, "transaction tags: cleaned and sorted")
        assertEquals(estorno, r.estorno, "transaction estorno")
        assertEquals(f.installments, d.installments.size, "one installment row per installment")
        assertEquals(f.total.v, d.installments.sumOf { it.cents.v }, "Σ installments = total")
    }

    suspend fun acceptAction() {
        val pend = db.pending()
        val row = if (pend.isEmpty() || rnd.nextInt(10) == 0) null else pend.random(rnd)
        val pick = row?.id ?: (notices.filter { n -> pend.none { it.id == n } } + RINV_UNKNOWN_NOTICE).random(rnd)
        val merchant = row?.parsed?.merchant?.ifEmpty { null } ?: RINV_MERCHANTS.random(rnd)
        val f = randomForm(merchant)
        val expect = row != null && valid(f)
        val p = acceptPreview(f, TransactionId(0))
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
        verifyTx(id, f, pick, NoticeId(0))
        assertEquals(NoticeStatus.PROCESSED, status(pick), "accepted notice is processed")
        assertNotNull(p, "a valid form has a preview")
        assertEquals(p.after, remaining(f.budget, p.month), "previewAccept after = real margin after accept")
    }

    suspend fun addManualAction() {
        val f = randomForm(RINV_MERCHANTS.random(rnd))
        val expect = valid(f)
        val p = acceptPreview(f, TransactionId(0))
        val before = snap()
        val id = db.addManual(f).transaction
        log("addManual ${describe(f)} -> ${id.v}")
        assertEquals(expect, id.v != 0L, "addManual accepted/refused (same rules as accept)")
        if (id.v == 0L) {
            assertUnchanged(before, "refused addManual")
            return
        }
        assertFalse(id in txIds, "addManual returns an id not in use")
        txIds.add(id)
        verifyTx(id, f, NoticeId(0), NoticeId(0))
        assertFalse(db.transaction(id).row.corrected, "a manual transaction is never corrected")
        assertNotNull(p, "a valid form has a preview")
        assertEquals(p.after, remaining(f.budget, p.month), "previewAccept after = real margin after addManual")
    }

    suspend fun editAction() {
        val known = txIds.isNotEmpty() && rnd.nextInt(12) != 0
        val id = if (known) txIds.random(rnd) else (goneTx + RINV_UNKNOWN_TX).random(rnd)
        val old = if (known) db.transaction(id).row else null
        val f = randomForm(if (old != null && rnd.nextBoolean()) old.merchant else RINV_MERCHANTS.random(rnd))
        val expect = old != null && valid(f)
        val p = acceptPreview(f, id)
        val before = snap()
        val ok = db.edit(id, f)
        log("edit ${id.v} known=$known refunded=${old?.refunded} ${describe(f)} -> $ok")
        assertEquals(expect, ok, "edit accepted/refused (same rules as accept; false if unknown)")
        if (!ok) {
            assertUnchanged(before, "refused edit")
            return
        }
        old!!
        // A registered estorno stays (Db.edit KDoc); the notice is the same.
        verifyTx(id, f, old.notice, old.estorno)
        if (old.notice.v == 0L) assertFalse(db.transaction(id).row.corrected, "a manual transaction is never corrected")
        // Ambiguity: the preview's "after" for an edit of a refunded transaction isn't specified.
        if (!old.refunded) {
            assertNotNull(p, "a valid form has a preview")
            assertEquals(p.after, remaining(f.budget, p.month), "previewAccept(replacing) after = real margin after edit")
        }
    }

    suspend fun deleteTransactionAction() {
        val known = txIds.isNotEmpty() && rnd.nextInt(10) != 0
        val id = if (known) txIds.random(rnd) else (goneTx + RINV_UNKNOWN_TX).random(rnd)
        val r = if (known) db.transaction(id).row else null
        val before = snap()
        db.deleteTransaction(id)
        log("deleteTransaction ${id.v} known=$known notice=${r?.notice?.v} estorno=${r?.estorno?.v}")
        if (r == null) {
            assertUnchanged(before, "deleteTransaction of an unknown id")
            return
        }
        txIds.remove(id)
        goneTx.add(id)
        assertEquals(TransactionDetail(), db.transaction(id), "zero detail after delete")
        if (r.notice.v != 0L) assertEquals(NoticeStatus.PENDING, status(r.notice), "spec §2.8: its notice is back in the Pauta")
        if (r.refunded) assertEquals(NoticeStatus.PENDING, status(r.estorno), "spec §2.8, §6: its estorno is back in the Pauta")
    }

    suspend fun dismissAction() {
        val pend = db.pending().map { it.id }.toSet()
        val pick = if (pend.isNotEmpty() && rnd.nextInt(4) != 0) pend.random(rnd) else (notices + RINV_UNKNOWN_NOTICE).random(rnd)
        val expect = pick in pend
        val before = snap()
        val ok = db.dismiss(pick)
        log("dismiss ${pick.v} pending=$expect -> $ok")
        assertEquals(expect, ok, "dismiss: only a pending notice")
        if (!ok) {
            assertUnchanged(before, "refused dismiss")
            return
        }
        dismissedModel.add(pick)
        assertEquals(NoticeStatus.DISMISSED, status(pick), "dismissed notice")
    }

    suspend fun restoreAction() {
        val pick = if (dismissedModel.isNotEmpty() && rnd.nextInt(10) < 7) dismissedModel.random(rnd) else (notices + RINV_UNKNOWN_NOTICE).random(rnd)
        val expect = pick in dismissedModel
        val before = snap()
        val ok = db.restore(pick)
        log("restore ${pick.v} dismissed=$expect -> $ok")
        assertEquals(expect, ok, "restore: only a dismissed notice")
        if (!ok) {
            assertUnchanged(before, "refused restore")
            return
        }
        dismissedModel.remove(pick)
        assertEquals(NoticeStatus.PENDING, status(pick), "restored notice is pending")
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
        val e = eRow?.id ?: (notices.filter { n -> pend.none { it.id == n } } + RINV_UNKNOWN_NOTICE).random(rnd)
        val txs = db.transactions()
        val free = txs.filter { !it.refunded }
        val t = when {
            free.isNotEmpty() && rnd.nextInt(10) < 8 -> free.random(rnd).id
            else -> (txs.filter { it.refunded }.map { it.id } + goneTx + RINV_UNKNOWN_TX).random(rnd)
        }
        val tRow = txs.firstOrNull { it.id == t }

        val v0 = db.version.value
        val p = db.previewRefund(t, today)
        assertEquals(v0, db.version.value, "previewRefund writes nothing")
        if (tRow == null || tRow.refunded) {
            assertEquals(Preview(), p, "previewRefund is zero for an unknown or refunded transaction")
        } else {
            assertEquals(mo(tRow.date), p.month, "previewRefund month = budget month of the first installment")
            assertEquals(remaining(tRow.budget, p.month), p.before, "previewRefund before = real margin now")
            val there = db.transaction(t).installments.filter { mo(it.date) == p.month }
            assertEquals(p.before.v + there.sumOf { it.cents.v }, p.after.v, "previewRefund after = before + the installment dated in that month")
        }

        // Ambiguity: the KDoc doesn't say whether a pending notice not parsed as an estorno may be registered
        // as one; only estorno texts get a predicted success.
        val expect: Boolean? = when {
            eRow == null -> false
            tRow == null || tRow.refunded -> false
            eRow.parsed.isRefund -> true
            else -> null
        }
        val before = snap()
        val ok = db.registerRefund(e, t)
        log("registerRefund estorno=${e.v} tx=${t.v} (known=${tRow != null} refunded=${tRow?.refunded}) -> $ok")
        if (expect != null) assertEquals(expect, ok, "registerRefund(notice, transaction) accepted/refused (KDoc)")
        if (!ok) {
            assertUnchanged(before, "refused registerRefund")
            return
        }
        tRow!!
        assertEquals(e, db.transaction(t).row.estorno, "the refunded transaction names its estorno")
        assertEquals(NoticeStatus.PROCESSED, status(e), "estorno notice is processed")
        assertEquals(p.after, remaining(tRow.budget, p.month), "previewRefund after = real margin after registerRefund")
    }

    suspend fun registerRefundNoticeAction() {
        val pend = db.pending()
        val pendIds = pend.map { it.id }.toSet()
        val eRow = pickEstorno(pend)
        val e = eRow?.id ?: (notices.filter { it !in pendIds } + RINV_UNKNOWN_NOTICE).random(rnd)
        val candidates = if (eRow != null) db.refundCandidates(e).filter { it.notice.v != 0L }.map { it.notice } else emptyList()
        val p = when {
            candidates.isNotEmpty() && rnd.nextInt(4) != 0 -> candidates.random(rnd)
            rnd.nextInt(5) == 0 -> e
            else -> (notices + RINV_UNKNOWN_NOTICE).random(rnd)
        }
        val pRow = pend.firstOrNull { it.id == p }
        // Ambiguity: registering against a notice that is itself parsed as an estorno, or with an estorno
        // not parsed as one, isn't specified: no prediction of success there.
        val expect: Boolean? = when {
            eRow == null || pRow == null || e == p -> false
            !eRow.parsed.isRefund || pRow.parsed.isRefund -> null
            else -> true
        }
        val before = snap()
        val ok = db.registerRefund(e, p)
        log("registerRefund estorno=${e.v} notice=${p.v} -> $ok")
        if (expect != null) assertEquals(expect, ok, "registerRefund(notice, notice) accepted/refused (KDoc)")
        if (!ok) {
            assertUnchanged(before, "refused registerRefund")
            return
        }
        noticeRefunds[e] = p
        assertEquals(NoticeStatus.PROCESSED, status(e), "spec §6: the estorno leaves the Pauta")
        assertEquals(NoticeStatus.PROCESSED, status(p), "spec §6: the purchase notice leaves the Pauta")
    }

    suspend fun undoRefundAction() {
        val txs = db.transactions()
        val onTx = txs.filter { it.refunded }.associateBy { it.estorno }
        val registered = onTx.keys + noticeRefunds.keys
        val pick = if (registered.isNotEmpty() && rnd.nextInt(10) < 8) registered.random(rnd) else (notices + RINV_UNKNOWN_NOTICE).random(rnd)
        val expect = pick in registered
        val before = snap()
        val ok = db.undoRefund(pick)
        log("undoRefund ${pick.v} registered=$expect -> $ok")
        assertEquals(expect, ok, "undoRefund: true only for a registered estorno")
        if (!ok) {
            assertUnchanged(before, "refused undoRefund")
            return
        }
        assertEquals(NoticeStatus.PENDING, status(pick), "spec §6: the estorno is back in the Pauta")
        val t = onTx[pick]
        if (t != null) assertEquals(NoticeId(0), db.transaction(t.id).row.estorno, "spec §6: the purchase counts again")
        val purchase = noticeRefunds.remove(pick)
        if (purchase != null) assertEquals(NoticeStatus.PENDING, status(purchase), "spec §6: the purchase notice is back in the Pauta")
    }

    suspend fun createBudgetAction() {
        budgetCounter++
        val name = "Verba $budgetCounter"
        val limit = 20_000L + rnd.nextLong(500_000)
        val id = db.createBudget(name, Cents(limit), RINV_BUDGET_DAY)
        log("createBudget \"$name\" $limit -> ${id.v}")
        assertTrue(id.v != 0L, "createBudget of a new name")
        budgets[id] = name
        ensureHorizon()
    }

    suspend fun deleteBudgetAction() {
        val known = budgets.isNotEmpty() && rnd.nextInt(6) != 0
        val id = if (known) budgets.keys.random(rnd) else (goneBudgets + RINV_UNKNOWN_BUDGET).random(rnd)
        val before = snap()
        db.deleteBudget(id)
        log("deleteBudget ${id.v} known=$known")
        if (!known) {
            assertUnchanged(before, "deleteBudget of an unknown id")
            return
        }
        budgets.remove(id)
        goneBudgets.add(id)
        val removed = before.txs.filter { it.budget == id }
        for (r in removed) {
            txIds.remove(r.id)
            goneTx.add(r.id)
        }
        assertEquals(before.txs.filter { it.budget != id }.map { it.id }.toSet(), db.transactions().map { it.id }.toSet(), "spec §3: the budget's transactions are deleted, no other")
        assertEquals(BudgetDetail(), db.budgetDetail(id, mo(today), today), "deleted budget reads as zero")
        for (r in removed) {
            if (r.notice.v != 0L) assertEquals(NoticeStatus.PENDING, status(r.notice), "spec §3: the notices go back to the Pauta")
            if (r.refunded) assertEquals(NoticeStatus.PENDING, status(r.estorno), "the estorno of a deleted transaction goes back to the Pauta")
        }
    }

    suspend fun deleteTagAction() {
        val tags = db.tags()
        val t = if (tags.isEmpty() || rnd.nextInt(10) == 0) null else tags.random(rnd)
        val before = snap()
        db.deleteTag(t?.id ?: RINV_UNKNOWN_TAG)
        log("deleteTag ${t?.id?.v ?: RINV_UNKNOWN_TAG.v} \"${t?.name}\"")
        if (t == null) {
            assertUnchanged(before, "deleteTag of an unknown id")
            return
        }
        val removed = before.txs.filter { it.tags == listOf(t.name) }
        for (r in removed) {
            txIds.remove(r.id)
            goneTx.add(r.id)
        }
        val after = db.transactions().associateBy { it.id }
        assertEquals(before.txs.filter { it !in removed }.map { it.id }.toSet(), after.keys, "spec §2.9: only the transactions left without tags are deleted")
        for (r in before.txs) {
            val a = after[r.id] ?: continue
            assertEquals(r.tags - t.name, a.tags, "deleteTag: the tag leaves transaction ${r.id.v}")
        }
        assertTrue(db.tags().none { it.id == t.id }, "deleteTag: the tag is gone")
        for (r in removed) {
            if (r.notice.v != 0L) assertEquals(NoticeStatus.PENDING, status(r.notice), "spec §2.9: the notices go back to the Pauta")
            if (r.refunded) assertEquals(NoticeStatus.PENDING, status(r.estorno), "the estorno of a deleted transaction goes back to the Pauta")
        }
    }

    suspend fun mergeTagsAction() {
        val tags = db.tags()
        val from = if (tags.isEmpty() || rnd.nextInt(10) == 0) null else tags.random(rnd)
        val into = if (tags.isEmpty() || rnd.nextInt(10) == 0) null else if (rnd.nextInt(8) == 0) from else tags.random(rnd)
        val fromId = from?.id ?: RINV_UNKNOWN_TAG
        val intoId = into?.id ?: RINV_UNKNOWN_TAG
        val expect = from != null && into != null && from.id != into.id
        val before = snap()
        val ok = db.mergeTags(fromId, intoId)
        log("mergeTags ${fromId.v} \"${from?.name}\" -> ${intoId.v} \"${into?.name}\" -> $ok")
        assertEquals(expect, ok, "mergeTags: false if either is unknown or they are the same")
        if (!ok) {
            assertUnchanged(before, "refused mergeTags")
            return
        }
        from!!
        into!!
        val after = db.transactions().associateBy { it.id }
        assertEquals(before.txs.map { it.id }.toSet(), after.keys, "mergeTags deletes no transaction")
        for (r in before.txs) {
            val want = if (from.name in r.tags) (r.tags - from.name + into.name).distinct().sorted() else r.tags
            assertEquals(want, after.getValue(r.id).tags, "mergeTags: tags of transaction ${r.id.v}")
        }
        assertTrue(db.tags().none { it.id == from.id }, "mergeTags deletes the merged tag")
    }

    suspend fun advanceTodayAction() {
        val n = 1 + rnd.nextInt(25)
        today = today.rinvDays(n)
        log("today += $n")
        ensureHorizon()
    }

    // ---- invariants ----

    suspend fun checkAll() {
        val txs = db.transactions()
        val ids = txs.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "transactions() lists each transaction once")
        assertEquals(txIds, ids.toSet(), "transactions() lists exactly the transactions alive")
        val sorted = txs.sortedWith(compareByDescending<TransactionRow> { it.date }.thenByDescending { it.time }.thenByDescending { it.id.v })
        assertEquals(sorted.map { it.id }, ids, "transactions(): newest (date, time, then id) first")

        val tagRows = db.tags()
        val tagNames = tagRows.map { it.name }
        assertEquals(tagNames.size, tagNames.toSet().size, "tag names are unique")

        // Every transaction: an existing budget, ≥1 tag, installments that add up to the total.
        val details = HashMap<TransactionId, TransactionDetail>()
        for (r in txs) {
            assertEquals(Installment(), r.now, "transactions() without today: now is zero (${r.id.v})")
            val d = db.transaction(r.id)
            assertEquals(r, d.row, "transaction(${r.id.v}).row = its transactions() row, now zero")
            details[r.id] = d
            assertTrue(r.budget in budgets, "transaction ${r.id.v} belongs to an existing budget (${r.budget.v})")
            assertEquals(budgets[r.budget], r.budgetName, "transaction ${r.id.v} budget name")
            assertTrue(r.tags.isNotEmpty(), "transaction ${r.id.v} has at least one tag")
            assertEquals(r.tags.distinct().sorted(), r.tags, "transaction ${r.id.v} tags sorted and distinct")
            assertTrue(tagNames.containsAll(r.tags), "transaction ${r.id.v} tags exist")
            val inst = d.installments
            assertEquals(r.installments, inst.size, "transaction ${r.id.v}: installments count = row.installments")
            assertEquals((1..r.installments).toList(), inst.map { it.k }, "transaction ${r.id.v}: k = 1..n")
            assertEquals(r.total.v, inst.sumOf { it.cents.v }, "transaction ${r.id.v}: Σ installments = total")
            assertEquals(r.date, inst.first().date, "transaction ${r.id.v}: the first installment is dated the purchase date")
            val m1 = mo(inst.first().date)
            for ((i, x) in inst.withIndex()) {
                assertEquals(m1.rinvShift(i), mo(x.date), "spec §6: installment ${x.k} of ${r.id.v} counts in the ${x.k}-th month from the purchase")
            }
            if (r.notice.v == 0L) {
                assertFalse(r.corrected, "manual transaction ${r.id.v} is never corrected")
                assertEquals(NoticeRow(), d.notice, "manual transaction ${r.id.v}: zero notice")
            } else {
                assertEquals(r.notice, d.notice.id, "transaction ${r.id.v}: its notice exists")
            }
        }

        // Notices: exactly one status each, derived as in architecture §5.
        val pend = db.pending()
        val dis = db.dismissed()
        assertEquals(pend.size, db.pendingCount(), "pendingCount() = pending().size")
        val pendIds = pend.map { it.id }
        val disIds = dis.map { it.id }
        assertEquals(pendIds.size, pendIds.toSet().size, "pending() lists each notice once")
        assertEquals(disIds.size, disIds.toSet().size, "dismissed() lists each notice once")
        for (n in pend) assertEquals(NoticeStatus.PENDING, n.status, "pending() row ${n.id.v} status")
        for (n in dis) assertEquals(NoticeStatus.DISMISSED, n.status, "dismissed() row ${n.id.v} status")
        assertTrue(pendIds.intersect(disIds.toSet()).isEmpty(), "pending and dismissed are disjoint")
        assertTrue(notices.containsAll(pendIds) && notices.containsAll(disIds), "pending and dismissed list only captured notices")
        assertEquals(dismissedModel, disIds.toSet(), "dismissed() = the notices dismissed and not restored")

        val refs = ArrayList<NoticeId>()
        for (r in txs) {
            if (r.notice.v != 0L) refs.add(r.notice)
            if (r.estorno.v != 0L) refs.add(r.estorno)
        }
        for ((e, p) in noticeRefunds) {
            refs.add(e)
            refs.add(p)
        }
        assertEquals(refs.size, refs.toSet().size, "each notice is referenced at most once (by a transaction or an estorno)")
        val processed = notices.toSet() - pendIds.toSet() - disIds.toSet()
        assertEquals(refs.toSet(), processed, "processed = referenced by a transaction or an estorno (architecture §5)")
        for (n in notices) {
            val want = when (n) {
                in processed -> NoticeStatus.PROCESSED
                in dismissedModel -> NoticeStatus.DISMISSED
                else -> NoticeStatus.PENDING
            }
            val row = db.notice(n)
            assertEquals(n, row.id, "notice ${n.v} exists")
            assertEquals(want, row.status, "notice ${n.v} status")
        }
        // No estorno points at nothing: every TransactionRow.estorno is a processed notice.
        for (r in txs) if (r.refunded) {
            assertTrue(r.estorno in notices, "estorno ${r.estorno.v} of ${r.id.v} is a captured notice")
            assertEquals(NoticeStatus.PROCESSED, db.notice(r.estorno).status, "estorno ${r.estorno.v} of ${r.id.v} is processed")
        }

        // Tag use counts (TagRow KDoc).
        for (t in tagRows) {
            assertEquals(txs.count { t.name in it.tags }, t.uses, "tag \"${t.name}\" uses")
            assertEquals(txs.count { it.tags == listOf(t.name) }, t.soleUses, "tag \"${t.name}\" sole uses")
        }

        // Spent of every budget in every month = Σ installments not refunded dated in that month (spec §3, §6).
        val expected = HashMap<Pair<BudgetId, YearMonth>, Long>()
        for (r in txs) if (!r.refunded) {
            for (x in details.getValue(r.id).installments) {
                val key = r.budget to mo(x.date)
                expected[key] = (expected[key] ?: 0L) + x.cents.v
            }
        }
        val months = ArrayList<YearMonth>()
        var m = mo(RINV_DATE_MIN)
        val last = mo(today).rinvShift(RINV_HORIZON)
        while (m <= last) {
            months.add(m)
            m = m.rinvShift(1)
        }
        for (month in months) {
            val ov = db.budgetsOverview(month, today, BudgetOrder.MANUAL)
            assertEquals(budgets.keys, ov.map { it.id }.toSet(), "$month: every budget has a limit row")
            for (s in ov) {
                assertEquals(expected[s.id to month] ?: 0L, s.month.spent.v, "$month budget ${s.id.v}: spent = Σ installments not refunded in the month")
                assertEquals(s.month.limit.v - s.month.spent.v, s.month.remaining.v, "$month budget ${s.id.v}: margem = cota − gasto")
            }
        }
        if (budgets.isNotEmpty()) {
            for (month in listOf(mo(today), months.random(rnd))) checkDetail(budgets.keys.random(rnd), month, txs, details, expected)
        }

        // Lançamentos: `now` is the installment dated in today's budget month (and in any other day's).
        checkNow(today, txs, details)
        checkNow(today.rinvDays(rnd.nextInt(1000) - 300), txs, details)

        // refundCandidates of a pending estorno.
        val estornos = pend.filter { it.parsed.isRefund }
        if (estornos.isNotEmpty()) checkCandidates(estornos.random(rnd), txs, pend)
    }

    suspend fun checkDetail(
        b: BudgetId,
        month: YearMonth,
        txs: List<TransactionRow>,
        details: Map<TransactionId, TransactionDetail>,
        expected: Map<Pair<BudgetId, YearMonth>, Long>,
    ) {
        val d = db.budgetDetail(b, month, today)
        assertEquals(b, d.id, "budgetDetail ${b.v} $month id")
        assertEquals(expected[b to month] ?: 0L, d.month.spent.v, "budgetDetail ${b.v} $month: spent = Σ installments not refunded in the month")
        assertEquals(d.month.limit.v - d.month.spent.v, d.month.remaining.v, "budgetDetail ${b.v} $month: margem = cota − gasto")
        assertEquals(d.month.spent.v, d.lines.filter { !it.row.refunded }.sumOf { it.cents.v }, "budgetDetail ${b.v} $month: Σ lines not refunded = spent")
        val want = txs.filter { r -> r.budget == b && details.getValue(r.id).installments.any { mo(it.date) == month } }.map { it.id }.toSet()
        assertEquals(want, d.lines.map { it.row.id }.toSet(), "budgetDetail ${b.v} $month: one line per transaction with an installment in the month")
        assertEquals(d.lines.size, want.size, "budgetDetail ${b.v} $month: one line per transaction")
        for (line in d.lines) {
            assertEquals(Installment(), line.row.now, "budgetDetail line ${line.row.id.v}: now is zero outside transactions(today)")
            val x = details.getValue(line.row.id).installments.single { mo(it.date) == month }
            assertEquals(Triple(x.k, x.date, x.cents), Triple(line.k, line.date, line.cents), "budgetDetail line ${line.row.id.v}: the installment dated in $month")
        }
    }

    suspend fun checkNow(day: LocalDate, txs: List<TransactionRow>, details: Map<TransactionId, TransactionDetail>) {
        val rows = db.transactions(day)
        assertEquals(txs, rows.map { it.copy(now = Installment()) }, "transactions($day) = transactions() with now filled in")
        val month = mo(day)
        for (r in rows) {
            val inMonth = details.getValue(r.id).installments.filter { mo(it.date) == month }
            assertTrue(inMonth.size <= 1, "D7: at most one installment of ${r.id.v} in $month")
            // k > 0: one of the purchase's installments (same k, date and value), dated in the month of `day`;
            // k = 0: none of its installments is dated in that month. Refunded or not, the same.
            assertEquals(inMonth.singleOrNull() ?: Installment(), r.now, "transactions($day): now of ${r.id.v} (month $month)")
        }
    }

    /** refundCandidates KDoc: transactions not refunded and pending notices, not the estorno itself nor estornos. */
    suspend fun checkCandidates(e: NoticeRow, txs: List<TransactionRow>, pend: List<NoticeRow>) {
        val cands = db.refundCandidates(e.id)
        for (c in cands) assertTrue((c.transaction.v != 0L) != (c.notice.v != 0L), "candidate $c: exactly one id is non-zero")
        val onTx = cands.filter { it.transaction.v != 0L }
        val onNotice = cands.filter { it.notice.v != 0L }
        assertEquals(onTx.size, onTx.map { it.transaction }.toSet().size, "refundCandidates: each transaction once")
        assertEquals(onNotice.size, onNotice.map { it.notice }.toSet().size, "refundCandidates: each notice once")
        val byId = txs.associateBy { it.id }
        for (c in onTx) {
            val r = assertNotNull(byId[c.transaction], "candidate transaction ${c.transaction.v} exists")
            assertFalse(r.refunded, "refundCandidates never includes a refunded purchase (${r.id.v})")
            assertEquals(r.name, c.name, "candidate ${r.id.v} name: alias, else merchant")
            assertEquals(r.total, c.total, "candidate ${r.id.v} total")
            assertEquals(r.date, c.date, "candidate ${r.id.v} date")
        }
        val pendById = pend.associateBy { it.id }
        for (c in onNotice) {
            assertTrue(c.notice != e.id, "refundCandidates never includes the estorno itself")
            val n = assertNotNull(pendById[c.notice], "refundCandidates never includes a notice not pending (${c.notice.v})")
            assertFalse(n.parsed.isRefund, "refundCandidates never includes a notice parsed as an estorno (${n.id.v})")
        }
        assertEquals(txs.filter { !it.refunded }.map { it.id }.toSet(), onTx.map { it.transaction }.toSet(), "refundCandidates: every transaction not refunded")
        assertEquals(
            pend.filter { it.id != e.id && !it.parsed.isRefund }.map { it.id }.toSet(), onNotice.map { it.notice }.toSet(),
            "refundCandidates: every pending notice but the estorno and other estornos",
        )
        val firstUnlikely = cands.indexOfFirst { !it.likely }
        if (firstUnlikely >= 0) assertTrue(cands.drop(firstUnlikely).none { it.likely }, "refundCandidates: likely ones first")
    }
}

class RecadoInvariantsTest {

    private fun randomRun(seed: Int, startDay: Int, steps: Int = 120) = runTest(timeout = 90.seconds) {
        withRecadoDb { db -> RecadoInvariantRun(db, seed, startDay).run(steps) }
    }

    @Test fun `random recado actions keep the invariants with seed 5 and month start day 1`() = randomRun(5, 1)

    @Test fun `random recado actions keep the invariants with seed 17 and month start day 31`() = randomRun(17, 31)

    @Test fun `random recado actions keep the invariants with seed 42 and month start day 29`() = randomRun(42, 29)

    @Test fun `random recado actions keep the invariants with seed 909 and month start day 30`() = randomRun(909, 30)

    @Test fun `random recado actions keep the invariants with seed 2026 and month start day 10`() = randomRun(2026, 10)
}
