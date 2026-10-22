package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Invariants of spec §2, §3, §4, §5, §6 and architecture §5, checked through the public API only,
// after every step of random (fixed-seed) action sequences, plus a few deterministic scenarios.

private const val NU = "com.nu.production"
private val TODAY = LocalDate(2026, 10, 14)
/** Epoch ms in early October 2026; notices are posted 10 minutes apart from here. */
private const val T0 = 1_791_000_000_000L
private val MERCHANTS = listOf("LOJA X", "PADARIA DO ZE", "MERCADO BOM PRECO", "FARMACIA SAO JOAO", "POSTO CENTRAL")
private val OTHER_TEXTS = listOf(
    "Seu cartão virtual foi criado.",
    "Confira as novidades do app.",
    "Seu limite foi atualizado. Veja no app.",
    "Você tem uma nova mensagem.",
)
private val TAG_POOL = listOf("padaria", " Mercado ", "CAFE", "feira", "farmacia", "lazer", "Transporte")
private val BUDGET_POOL = listOf("Mercado", "Casa", "Lazer", "Transporte", "Saude", "mercado", "CASA", "  ")

private enum class Kind { PURCHASE, ESTORNO, OTHER }

private class Known(val id: NoticeId, val kind: Kind, val raw: RawNotice)

private class BudgetModel(val name: String, val firstMonth: YearMonth)

private data class Snap(
    val txs: List<TransactionRow>,
    val pending: List<NoticeRow>,
    val dismissed: List<NoticeRow>,
    val tags: List<TagRow>,
)

/** "1.234,56" */
private fun brl(c: Long): String {
    val reais = (c / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return "$reais,${(c % 100).toString().padStart(2, '0')}"
}

private fun purchaseText(cents: Long, merchant: String) =
    "Compra de R$ ${brl(cents)} APROVADA em $merchant para o cartão com final 1234."

private fun estornoText(cents: Long, merchant: String) =
    "A compra em $merchant no valor de R$ ${brl(cents)} foi estornada no seu cartão."

private fun cleanTags(tags: List<String>) = tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()

private fun YearMonth.add(n: Int): YearMonth = if (n >= 0) plus(n, DateTimeUnit.MONTH) else minus(-n, DateTimeUnit.MONTH)

/** Spec §3: from day D to the eve of day D next month; a missing D is the month's last day. */
private fun monthStart(m: YearMonth, day: Int) = LocalDate(m.year, m.month, minOf(day, m.numberOfDays))

/** A real SQLite file per test, deleted afterwards with its -wal and -shm. */
private suspend fun withDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-inv-${Random.Default.nextLong().toULong()}.db").toString()
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

private suspend fun Db.captured(n: Int, text: String): NoticeId {
    val at = T0 + n * 600_000L
    val raw = RawNotice(packageName = NU, appLabel = "Nubank", key = "key-$n", postedAt = at, whenMs = at, title = "Nubank", text = text)
    assertTrue(capture(this, raw), "capture of notice $n")
    val row = pending().firstOrNull { it.raw.key == raw.key }
    assertTrue(row != null, "captured notice $n is pending")
    return row.id
}

private fun form(budget: BudgetId, tags: List<String>, cents: Long, installments: Int = 1, date: LocalDate = LocalDate(2026, 10, 10)) =
    TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = installments, merchant = "LOJA X", date = date, time = LocalTime(12, 0))

private suspend fun Db.spent(budget: BudgetId, month: YearMonth): Cents {
    ensureMonths(month)
    return budgetDetail(budget, month, TODAY).month.spent
}

/** One random run: a model of what the test did (notices it captured, budgets it created, notice↔notice estornos). */
private class InvariantRun(val db: Db, val seed: Int) {
    val rnd = Random(seed)
    val log = ArrayList<String>()
    var step = 0
    val notices = ArrayList<Known>()
    val budgets = LinkedHashMap<BudgetId, BudgetModel>()
    val goneBudgets = ArrayList<BudgetId>()
    /** estorno notice -> purchase notice, for estornos registered against a pending notice. */
    val noticeRefunds = LinkedHashMap<NoticeId, NoticeId>()
    var captures = 0
    var newTags = 0

    fun log(s: String) {
        log.add("#$step $s")
    }

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
            throw AssertionError("seed=$seed step=$step: $e\n--- actions so far ---\n${log.joinToString("\n")}", e)
        }
    }

    suspend fun setup() {
        log("setup")
        assertTrue(db.addSource(NU, "Nubank", T0), "addSource")
        for ((name, limit) in listOf("Mercado" to 80_000L, "Casa" to 150_000L, "Lazer" to 30_000L)) {
            val id = db.createBudget(name, Cents(limit), TODAY)
            assertNotEquals(0L, id.v, "createBudget $name")
            budgets[id] = BudgetModel(name, db.monthOf(TODAY))
        }
        for (t in listOf("padaria", "cafe", "feira", "farmacia")) assertNotEquals(0L, db.createTag(t).v, "createTag $t")
        repeat(6) { capturePurchase() }
        repeat(2) { captureEstorno() }
        captureOther()
    }

    // ---- reads used by the checks ----

    suspend fun snap() = Snap(db.transactions(), db.pending(), db.dismissed(), db.tags())

    suspend fun status(id: NoticeId) = db.notice(id).status

    /** Runs a write; whatever the outcome, a refusal must leave transactions, Pauta, Riscados and tags as they were. */
    suspend fun <T> attempt(desc: String, expectOk: Boolean?, ok: (T) -> Boolean, action: suspend () -> T): T {
        val before = snap()
        val res = action()
        val success = ok(res)
        log("$desc -> $res")
        if (expectOk != null) assertEquals(expectOk, success, "$desc: accepted/refused")
        if (!success) assertEquals(before, snap(), "$desc was refused but changed state")
        return res
    }

    suspend fun assertPending(id: NoticeId, why: String) {
        if (id.v != 0L) assertEquals(NoticeStatus.PENDING, status(id), "notice ${id.v} pending after $why")
    }

    // ---- actions ----

    suspend fun act() {
        val r = rnd.nextInt(100)
        when {
            r < 13 -> capturePurchase()
            r < 18 -> captureEstorno()
            r < 21 -> captureOther()
            r < 23 -> captureRefused()
            r < 28 -> dismissAction()
            r < 31 -> restoreAction()
            r < 46 -> acceptAction()
            r < 51 -> addManualAction()
            r < 57 -> editAction()
            r < 61 -> deleteTransactionAction()
            r < 66 -> refundTransactionAction()
            r < 69 -> refundNoticeAction()
            r < 73 -> undoRefundAction()
            r < 75 -> createBudgetAction()
            r < 78 -> setLimitAction()
            r < 79 -> deleteBudgetAction()
            r < 81 -> createTagAction()
            r < 83 -> renameTagAction()
            r < 85 -> mergeTagsAction()
            r < 87 -> deleteTagAction()
            r < 96 -> setStepAction()
            r < 98 -> setMonthStartDayAction()
            else -> setWeekStartAction()
        }
    }

    suspend fun captureNotice(kind: Kind, text: String) {
        captures++
        val at = T0 + captures * 600_000L
        val raw = RawNotice(
            packageName = NU, appLabel = "Nubank", key = "0|$NU|$captures|null|10155", postedAt = at, whenMs = at,
            title = if (kind == Kind.ESTORNO) "Estorno" else "Nubank", text = text,
        )
        val saved = capture(db, raw)
        log("capture $kind \"$text\" -> $saved")
        assertTrue(saved, "capture of a new notice from a source")
        val row = db.pending().firstOrNull { it.raw.key == raw.key }
        assertTrue(row != null, "new notice is in the Pauta")
        assertEquals(raw.text, row.raw.text, "notice text")
        assertEquals(NoticeStatus.PENDING, row.status, "new notice status")
        notices.add(Known(row.id, kind, raw))
        log("  notice ${row.id.v}")
    }

    suspend fun capturePurchase() = captureNotice(Kind.PURCHASE, purchaseText(100L + rnd.nextLong(200_000), MERCHANTS.random(rnd)))

    suspend fun captureEstorno() {
        // Usually the estorno of a purchase already captured (same value and merchant), as banks send it.
        val bought = notices.filter { it.kind == Kind.PURCHASE }
        if (bought.isNotEmpty() && rnd.nextInt(4) != 0) {
            val p = db.notice(bought.random(rnd).id).parsed
            if (p.cents.v > 0 && p.merchant.isNotEmpty()) return captureNotice(Kind.ESTORNO, estornoText(p.cents.v, p.merchant))
        }
        captureNotice(Kind.ESTORNO, estornoText(100L + rnd.nextLong(50_000), MERCHANTS.random(rnd)))
    }

    suspend fun captureOther() = captureNotice(Kind.OTHER, OTHER_TEXTS.random(rnd))

    /** Noise and re-posts: refused (Capture.kt KDoc). */
    suspend fun captureRefused() {
        val at = T0 + (captures + 1) * 600_000L
        val raw = when (rnd.nextInt(3)) {
            0 -> RawNotice(packageName = "com.whatsapp", appLabel = "WhatsApp", key = "wa-$step", postedAt = at, whenMs = at, title = "Nubank", text = purchaseText(3150, "LOJA X"))
            1 -> RawNotice(packageName = NU, appLabel = "Nubank", key = "empty-$step", postedAt = at, whenMs = at)
            // Same key, content and when (≠ 0), an hour later: a re-post.
            else -> notices.random(rnd).raw.let { it.copy(postedAt = it.postedAt + 3_600_000L) }
        }
        attempt("capture refused pkg=${raw.packageName} key=${raw.key}", false, { it: Boolean -> it }) { capture(db, raw) }
    }

    suspend fun dismissAction() {
        val n = pickNotice(Kind.PURCHASE, Kind.OTHER, Kind.ESTORNO)
        val expect = status(n.id) == NoticeStatus.PENDING
        val ok = attempt("dismiss ${n.id.v}", expect, { it: Boolean -> it }) { db.dismiss(n.id) }
        if (ok) assertEquals(NoticeStatus.DISMISSED, status(n.id), "dismissed notice status")
    }

    suspend fun restoreAction() {
        val riscados = db.dismissed()
        val id = if (riscados.isNotEmpty() && rnd.nextInt(4) != 0) riscados.random(rnd).id else notices.random(rnd).id
        val expect = status(id) == NoticeStatus.DISMISSED
        val ok = attempt("restore ${id.v}", expect, { it: Boolean -> it }) { db.restore(id) }
        if (ok) assertEquals(NoticeStatus.PENDING, status(id), "restored notice status")
    }

    /** Mostly a pending notice of [kinds]; sometimes any notice (which may be refused). */
    suspend fun pickNotice(vararg kinds: Kind): Known {
        if (rnd.nextInt(5) != 0) {
            val pend = db.pending().map { it.id }.toSet()
            val good = notices.filter { it.kind in kinds && it.id in pend }
            if (good.isNotEmpty()) return good.random(rnd)
        }
        return notices.random(rnd)
    }

    fun tagName(): String = if (rnd.nextInt(12) == 0) "Novo${++newTags} " else TAG_POOL.random(rnd)

    fun randomForm(parsed: Parsed = Parsed()): TransactionForm {
        val budget = when (rnd.nextInt(20)) {
            0 -> BudgetId(0)
            1 -> if (goneBudgets.isNotEmpty()) goneBudgets.random(rnd) else BudgetId(987_654)
            else -> budgets.keys.random(rnd)
        }
        val tags = when (rnd.nextInt(20)) {
            0 -> emptyList()
            1 -> listOf("  ", "")
            else -> List(1 + rnd.nextInt(3)) { tagName() }
        }
        val installments = when (rnd.nextInt(25)) {
            0 -> 0
            1 -> 401
            else -> if (rnd.nextBoolean()) 1 else 1 + rnd.nextInt(12)
        }
        val alias = if (rnd.nextInt(10) == 0) "Apelido ${rnd.nextInt(3)}" else ""
        // Sometimes exactly what the notice says (not emendado).
        if (parsed.cents.v > 0 && rnd.nextInt(3) == 0) {
            return TransactionForm(budget, tags, parsed.cents, installments, parsed.merchant, parsed.date, parsed.time, alias)
        }
        val total = when (rnd.nextInt(25)) {
            0 -> 0L
            1 -> -500L
            else -> 1L + rnd.nextLong(300_000)
        }
        return TransactionForm(
            budget = budget,
            tags = tags,
            total = Cents(total),
            installments = installments,
            merchant = MERCHANTS.random(rnd),
            date = LocalDate(2026, 7, 1).plus(rnd.nextInt(184), DateTimeUnit.DAY),
            time = LocalTime(rnd.nextInt(24), rnd.nextInt(60)),
            alias = alias,
        )
    }

    fun formValid(f: TransactionForm) =
        f.budget in budgets && cleanTags(f.tags).isNotEmpty() && f.total.v > 0 && f.installments in 1..400

    fun describe(f: TransactionForm) =
        "budget=${f.budget.v} tags=${f.tags} total=${f.total.v} n=${f.installments} merchant=${f.merchant} date=${f.date} ${f.time} alias=${f.alias}"

    /** The transaction as accept/addManual/edit must have written it (Db.accept KDoc). */
    suspend fun verifyTx(id: TransactionId, f: TransactionForm, notice: NoticeId, estorno: NoticeId, parsed: Parsed?) {
        val d = db.transaction(id)
        val r = d.row
        assertEquals(id, r.id, "transaction id")
        assertEquals(notice, r.notice, "transaction notice")
        assertEquals(notice, d.notice.id, "detail notice")
        assertEquals(f.budget, r.budget, "transaction budget")
        assertEquals(budgets.getValue(f.budget).name, r.budgetName, "transaction budget name")
        assertEquals(f.merchant, r.merchant, "transaction merchant")
        assertEquals(f.total, r.total, "transaction total")
        assertEquals(f.installments, r.installments, "transaction installments")
        assertEquals(f.date, r.date, "transaction date")
        assertEquals(f.time, r.time, "transaction time")
        assertEquals(cleanTags(f.tags).sorted(), r.tags, "transaction tags (cleaned, sorted)")
        assertEquals(estorno, r.estorno, "transaction estorno")
        if (f.alias.isNotBlank() && f.merchant.isNotBlank()) assertEquals(f.alias.trim(), r.alias, "alias named by the form")
        if (parsed == null) {
            assertFalse(r.corrected, "manual transactions are never corrected")
        } else if (f.total != parsed.cents || f.date != parsed.date || f.time != parsed.time) {
            assertTrue(r.corrected, "corrected when total, date or time differ from Parsed")
        } else if (f.merchant == parsed.merchant) {
            assertFalse(r.corrected, "not corrected when equal to Parsed")
        }
        // Installment k is in the k-th budget month from the purchase's (D7); k = 1 is the purchase date.
        val n = f.installments
        assertEquals(n, d.installments.size, "installment rows")
        val first = db.monthOf(f.date)
        val startDay = db.settings().monthStartDay
        for ((i, inst) in d.installments.withIndex()) {
            assertEquals(i + 1, inst.k, "installment k")
            val each = f.total.v / n
            assertEquals(if (inst.k < n) each else f.total.v - each * (n - 1), inst.cents.v, "installment ${inst.k} cents")
            assertEquals(first.add(i), db.monthOf(inst.date), "installment ${inst.k} month")
            if (startDay == 1) assertEquals(f.date.plus(i, DateTimeUnit.MONTH), inst.date, "installment ${inst.k} date")
        }
        assertEquals(f.date, d.installments.first().date, "first installment on the purchase date")
    }

    suspend fun acceptAction() {
        val n = pickNotice(Kind.PURCHASE, Kind.OTHER)
        val parsed = db.notice(n.id).parsed
        val f = randomForm(parsed)
        val expect = status(n.id) == NoticeStatus.PENDING && formValid(f)
        val id = attempt("accept ${n.id.v} ${describe(f)}", expect, { it: TransactionId -> it.v != 0L }) { db.accept(n.id, f) }
        if (id.v != 0L) {
            assertEquals(NoticeStatus.PROCESSED, status(n.id), "accepted notice status")
            verifyTx(id, f, n.id, NoticeId(0), parsed)
        }
    }

    suspend fun addManualAction() {
        val f = randomForm()
        val id = attempt("addManual ${describe(f)}", formValid(f), { it: TransactionId -> it.v != 0L }) { db.addManual(f).transaction }
        if (id.v != 0L) verifyTx(id, f, NoticeId(0), NoticeId(0), null)
    }

    suspend fun pickTx(): TransactionRow {
        val txs = db.transactions()
        return if (txs.isEmpty() || rnd.nextInt(12) == 0) TransactionRow(id = TransactionId(987_654)) else txs.random(rnd)
    }

    suspend fun editAction() {
        val t = pickTx()
        val parsed = if (t.notice.v != 0L) db.notice(t.notice).parsed else null
        val f = randomForm(parsed ?: Parsed())
        val exists = t.id.v != 987_654L
        val ok = attempt("edit ${t.id.v} ${describe(f)}", exists && formValid(f), { it: Boolean -> it }) { db.edit(t.id, f) }
        if (ok) verifyTx(t.id, f, t.notice, t.estorno, parsed)
    }

    suspend fun deleteTransactionAction() {
        val t = pickTx()
        val before = snap()
        db.deleteTransaction(t.id)
        log("deleteTransaction ${t.id.v} (notice ${t.notice.v}, estorno ${t.estorno.v})")
        if (before.txs.none { it.id == t.id }) {
            assertEquals(before, snap(), "deleting an unknown transaction changed state")
            return
        }
        assertEquals(0L, db.transaction(t.id).row.id.v, "deleted transaction is gone")
        assertPending(t.notice, "deleting its transaction")
        assertPending(t.estorno, "deleting the refunded transaction (spec §2.8, §6)")
        assertEquals(
            before.txs.filter { it.id != t.id }.associateBy { it.id },
            db.transactions().associateBy { it.id },
            "other transactions unchanged",
        )
    }

    suspend fun refundTransactionAction() {
        val e = pickNotice(Kind.ESTORNO)
        val txs = db.transactions()
        val t = when {
            txs.isEmpty() || rnd.nextInt(12) == 0 -> TransactionRow(id = TransactionId(987_654))
            rnd.nextInt(5) == 0 -> txs.random(rnd)
            else -> txs.filter { !it.refunded }.randomOrNull(rnd) ?: txs.random(rnd)
        }
        val valid = status(e.id) == NoticeStatus.PENDING && txs.any { it.id == t.id && !it.refunded }
        // The KDoc doesn't require the estorno to be parsed as one: only predict success for estorno texts.
        val expect = if (!valid) false else if (e.kind == Kind.ESTORNO) true else null
        val ok = attempt("registerRefund estorno=${e.id.v} tx=${t.id.v}", expect, { it: Boolean -> it }) { db.registerRefund(e.id, t.id) }
        if (ok) {
            assertEquals(e.id, db.transaction(t.id).row.estorno, "refunded transaction names its estorno")
            assertEquals(NoticeStatus.PROCESSED, status(e.id), "estorno notice status")
        }
    }

    suspend fun refundNoticeAction() {
        val e = pickNotice(Kind.ESTORNO)
        val p = if (rnd.nextInt(10) == 0) e else pickNotice(Kind.PURCHASE)
        val valid = e.id != p.id && status(e.id) == NoticeStatus.PENDING && status(p.id) == NoticeStatus.PENDING
        val expect = if (!valid) false else if (e.kind == Kind.ESTORNO && p.kind == Kind.PURCHASE) true else null
        val ok = attempt("registerRefund estorno=${e.id.v} notice=${p.id.v}", expect, { it: Boolean -> it }) { db.registerRefund(e.id, p.id) }
        if (ok) {
            noticeRefunds[e.id] = p.id
            assertEquals(NoticeStatus.PROCESSED, status(e.id), "estorno notice status")
            assertEquals(NoticeStatus.PROCESSED, status(p.id), "purchase notice status")
        }
    }

    suspend fun undoRefundAction() {
        val txs = db.transactions()
        val registered = txs.filter { it.refunded }.map { it.estorno } + noticeRefunds.keys
        val e = if (registered.isNotEmpty() && rnd.nextInt(5) != 0) registered.random(rnd) else notices.random(rnd).id
        val ok = attempt("undoRefund ${e.v}", e in registered, { it: Boolean -> it }) { db.undoRefund(e) }
        if (!ok) return
        assertPending(e, "undoRefund (spec §6)")
        val purchase = noticeRefunds.remove(e)
        if (purchase != null) assertPending(purchase, "undoRefund of a notice purchase")
        val t = txs.firstOrNull { it.estorno == e }
        if (t != null) assertEquals(0L, db.transaction(t.id).row.estorno.v, "transaction counts again after undoRefund")
    }

    suspend fun createBudgetAction() {
        val name = BUDGET_POOL.random(rnd)
        val limit = if (rnd.nextInt(8) == 0) 0L - rnd.nextInt(2) * 100 else 10_000L + rnd.nextLong(400_000)
        val expect = name.isNotBlank() && budgets.values.none { it.name.equals(name.trim(), ignoreCase = true) } && limit > 0
        val id = attempt("createBudget \"$name\" ${limit}", expect, { it: BudgetId -> it.v != 0L }) { db.createBudget(name, Cents(limit), TODAY) }
        if (id.v == 0L) return
        val m0 = db.monthOf(TODAY)
        budgets[id] = BudgetModel(name.trim(), m0)
        db.ensureMonths(m0)
        val d = db.budgetDetail(id, m0, TODAY)
        assertEquals(name.trim(), d.name, "new budget name")
        assertEquals(Cents(limit), d.month.limit, "new budget limit in today's month")
    }

    suspend fun setLimitAction() {
        val id = when (rnd.nextInt(10)) {
            0 -> if (goneBudgets.isNotEmpty()) goneBudgets.random(rnd) else BudgetId(987_654)
            else -> budgets.keys.random(rnd)
        }
        val limit = if (rnd.nextInt(8) == 0) 0L else 5_000L + rnd.nextLong(300_000)
        val m0 = db.monthOf(TODAY)
        val prev = m0.add(-1)
        val model = budgets[id]
        val prevLimit = if (model != null && prev >= model.firstMonth) {
            db.ensureMonths(prev)
            db.budgetDetail(id, prev, TODAY).month.limit
        } else Cents(0)
        val ok = attempt("setLimit ${id.v} $limit", model != null && limit > 0, { it: Boolean -> it }) { db.setLimit(id, Cents(limit), TODAY) }
        if (!ok || model == null) return
        // From today's month onward (spec §3); earlier months keep theirs.
        val from = maxOf(m0, model.firstMonth)
        for (i in 0..3) {
            val m = from.add(i)
            db.ensureMonths(m)
            assertEquals(Cents(limit), db.budgetDetail(id, m, TODAY).month.limit, "limit of $m after setLimit")
        }
        if (prev >= model.firstMonth) assertEquals(prevLimit, db.budgetDetail(id, prev, TODAY).month.limit, "limit of past month $prev kept")
    }

    suspend fun deleteBudgetAction() {
        if (budgets.size < 2) return createBudgetAction()
        val id = if (rnd.nextInt(6) == 0) BudgetId(987_654) else budgets.keys.random(rnd)
        val before = snap()
        db.deleteBudget(id)
        log("deleteBudget ${id.v}")
        if (id !in budgets) {
            assertEquals(before, snap(), "deleting an unknown budget changed state")
            return
        }
        budgets.remove(id)
        goneBudgets.add(id)
        val after = db.transactions()
        assertTrue(after.none { it.budget == id }, "no transaction of a deleted budget")
        for (t in before.txs.filter { it.budget == id }) {
            assertPending(t.notice, "deleting its budget (spec §3)")
            assertPending(t.estorno, "deleting the budget of a refunded transaction")
        }
        assertEquals(
            before.txs.filter { it.budget != id }.associateBy { it.id },
            after.associateBy { it.id },
            "transactions of other budgets unchanged",
        )
        val m0 = db.monthOf(TODAY)
        db.ensureMonths(m0)
        assertEquals(0L, db.budgetDetail(id, m0, TODAY).id.v, "deleted budget detail is zero")
        assertTrue(db.budgetsOverview(m0, TODAY, BudgetOrder.AZ).none { it.id == id }, "deleted budget not in overview")
    }

    suspend fun createTagAction() {
        val name = if (rnd.nextInt(6) == 0) "   " else tagName()
        val clean = name.trim().lowercase()
        val before = db.tags()
        val id = attempt("createTag \"$name\"", clean.isNotEmpty(), { it: TagId -> it.v != 0L }) { db.createTag(name) }
        if (id.v == 0L) return
        val existing = before.firstOrNull { it.name == clean }
        if (existing != null) assertEquals(existing.id, id, "createTag of a used name returns the existing tag")
        assertTrue(db.tags().any { it.id == id && it.name == clean }, "created tag listed")
    }

    suspend fun pickTag(tags: List<TagRow>): TagId =
        if (tags.isEmpty() || rnd.nextInt(10) == 0) TagId(987_654) else tags.random(rnd).id

    suspend fun renameTagAction() {
        val tags = db.tags()
        val id = pickTag(tags)
        val name = if (rnd.nextInt(8) == 0) "  " else tagName()
        val clean = name.trim().lowercase()
        val expect = tags.any { it.id == id } && clean.isNotEmpty() && tags.none { it.id != id && it.name == clean }
        val ok = attempt("renameTag ${id.v} \"$name\"", expect, { it: Boolean -> it }) { db.renameTag(id, name) }
        if (!ok) return
        val after = db.tags().firstOrNull { it.id == id }
        assertEquals(clean, after?.name, "renamed tag")
        assertEquals(tags.first { it.id == id }.uses, after?.uses, "renaming keeps uses")
    }

    suspend fun mergeTagsAction() {
        val tags = db.tags()
        val from = pickTag(tags)
        val into = if (rnd.nextInt(8) == 0) from else pickTag(tags)
        val fromName = tags.firstOrNull { it.id == from }?.name
        val intoName = tags.firstOrNull { it.id == into }?.name
        val before = db.transactions()
        val ok = attempt("mergeTags ${from.v} -> ${into.v}", fromName != null && intoName != null && from != into, { it: Boolean -> it }) {
            db.mergeTags(from, into)
        }
        if (!ok || fromName == null || intoName == null) return
        assertTrue(db.tags().none { it.id == from }, "merged tag deleted")
        val after = db.transactions().associateBy { it.id }
        assertEquals(before.map { it.id }.toSet(), after.keys, "merging deletes no transaction")
        for (t in before) {
            val want = if (fromName in t.tags) (t.tags - fromName + intoName).distinct().sorted() else t.tags
            assertEquals(want, after.getValue(t.id).tags, "tags of ${t.id.v} after merge")
        }
    }

    suspend fun deleteTagAction() {
        val tags = db.tags()
        val id = pickTag(tags)
        val name = tags.firstOrNull { it.id == id }?.name
        val before = snap()
        db.deleteTag(id)
        log("deleteTag ${id.v} ($name)")
        if (name == null) {
            assertEquals(before, snap(), "deleting an unknown tag changed state")
            return
        }
        assertTrue(db.tags().none { it.id == id }, "deleted tag gone")
        val after = db.transactions().associateBy { it.id }
        for (t in before.txs) {
            if (t.tags == listOf(name)) {
                assertTrue(t.id !in after, "transaction ${t.id.v} left without tags is deleted (spec §2.9)")
                assertPending(t.notice, "deleting the only tag of its transaction")
                assertPending(t.estorno, "deleting the only tag of a refunded transaction")
            } else {
                assertEquals(t.tags - name, after[t.id]?.tags, "transaction ${t.id.v} loses the deleted tag")
            }
        }
    }

    suspend fun setStepAction() {
        val id = budgets.keys.random(rnd)
        val model = budgets.getValue(id)
        val m0 = db.monthOf(TODAY)
        val month = listOf(m0.add(-1), m0, m0, m0.add(1)).random(rnd)
        if (month < model.firstMonth) return
        db.ensureMonths(month)
        val d = db.budgetDetail(id, month, TODAY)
        val weeks = d.month.weeks
        val badDate = rnd.nextInt(10) == 0
        val ws = if (badDate) d.month.start.minus(3, DateTimeUnit.DAY) else weeks.random(rnd).start
        val othersEdited = weeks.filter { it.edited && it.start != ws }.sumOf { it.step.v }
        val max = (d.month.limit.v - othersEdited).coerceAtLeast(0)
        val freeOthers = weeks.count { !it.edited && it.start != ws }
        val step = when (rnd.nextInt(6)) {
            0 -> -100L
            1 -> max + 1 + rnd.nextLong(1_000)
            2 -> max
            else -> rnd.nextLong(max + 1)
        }
        // Refused when < 0, > limit − Σ other edited weeks, or the only week left automatic (spec §4, KDoc).
        val expect = if (badDate) null else step >= 0 && step <= max && freeOthers > 0
        val ok = attempt("setStep ${id.v} $month $ws $step (max $max, free others $freeOthers)", expect, { it: Boolean -> it }) {
            db.setStep(id, month, ws, Cents(step))
        }
        val after = db.budgetDetail(id, month, TODAY).month.weeks
        if (!ok) {
            assertEquals(weeks, after, "refused setStep changed the weeks")
            return
        }
        val w = after.first { it.start == ws }
        assertEquals(Cents(step), w.step, "edited step")
        assertTrue(w.edited, "edited week flagged")
    }

    /** Spec §4, D10: changing the month or week start deletes step edits from today's month onward. */
    suspend fun assertNoEditsFromToday(why: String) {
        val m0 = db.monthOf(TODAY)
        for ((id, model) in budgets) {
            for (i in 0..2) {
                val m = maxOf(m0, model.firstMonth).add(i)
                db.ensureMonths(m)
                val weeks = db.budgetDetail(id, m, TODAY).month.weeks
                assertTrue(weeks.none { it.edited }, "no step edits in $m of budget ${id.v} after $why")
            }
        }
    }

    suspend fun setMonthStartDayAction() {
        val day = when (rnd.nextInt(10)) {
            0 -> 0
            1 -> 32
            2 -> 1
            else -> 1 + rnd.nextInt(31)
        }
        val old = db.settings().monthStartDay
        val ok = attempt("setMonthStartDay $day", day in 1..31, { it: Boolean -> it }) { db.setMonthStartDay(day, TODAY) }
        if (!ok) return
        assertEquals(day, db.settings().monthStartDay, "monthStartDay")
        // Spec §4: *changing* the day deletes edits; picking the day already set changes nothing.
        if (day != old) assertNoEditsFromToday("setMonthStartDay")
    }

    suspend fun setWeekStartAction() {
        val day = DayOfWeek.entries.random(rnd)
        val old = db.settings().weekStart
        db.setWeekStart(day, TODAY)
        log("setWeekStart $day")
        assertEquals(day, db.settings().weekStart, "weekStart")
        if (day != old) assertNoEditsFromToday("setWeekStart")
    }

    // ---- invariants ----

    suspend fun checkAll(full: Boolean) {
        val txs = db.transactions()
        val details = txs.associate { it.id to db.transaction(it.id) }
        checkTransactions(txs, details)
        checkNotices(txs)
        checkTags(txs)
        val m0 = db.monthOf(TODAY)
        val months = if (full) (-3..14).map { m0.add(it) } else listOf(m0, m0.add(rnd.nextInt(-3, 15))).distinct()
        val settings = db.settings()
        val budgetMonths = HashMap<Pair<BudgetId, YearMonth>, BudgetMonth>()
        for (m in months) checkMonth(m, settings, txs, details, full, budgetMonths)
        checkHistory(if (full) months else listOf(m0), budgetMonths)
        checkOverview(m0, budgetMonths)
    }

    fun checkTransactions(txs: List<TransactionRow>, details: Map<TransactionId, TransactionDetail>) {
        for (i in 1 until txs.size) {
            val a = txs[i - 1]
            val b = txs[i]
            assertTrue(a.date > b.date || (a.date == b.date && a.time >= b.time), "transactions() newest first: ${a.id.v} before ${b.id.v}")
        }
        assertEquals(txs.size, txs.map { it.id }.toSet().size, "transaction ids unique")
        for (t in txs) {
            val d = details.getValue(t.id)
            assertEquals(t, d.row, "transaction(${t.id.v}).row agrees with transactions()")
            assertNotEquals(0L, t.id.v, "transaction id")
            assertTrue(t.tags.isNotEmpty(), "transaction ${t.id.v} has ≥1 tag (spec §2.4)")
            assertEquals(t.tags.distinct().sorted(), t.tags, "transaction ${t.id.v} tags sorted and distinct")
            val budget = budgets[t.budget]
            assertTrue(budget != null, "transaction ${t.id.v} has an existing budget (${t.budget.v})")
            assertTrue(t.budgetName.isNotEmpty(), "transaction ${t.id.v} budget name")
            assertEquals(budget.name, t.budgetName, "transaction ${t.id.v} budget name")
            assertTrue(t.total.v > 0, "transaction ${t.id.v} total > 0")
            assertTrue(t.installments in 1..400, "transaction ${t.id.v} installments in 1..400")
            assertEquals(t.notice, d.notice.id, "transaction ${t.id.v} detail notice")
            val inst = d.installments
            assertEquals(t.installments, inst.size, "transaction ${t.id.v} installment rows")
            assertEquals((1..t.installments).toList(), inst.map { it.k }, "transaction ${t.id.v} installments k = 1..n")
            assertEquals(t.total.v, inst.sumOf { it.cents.v }, "transaction ${t.id.v} installments sum to total")
            val each = t.total.v / t.installments
            for (x in inst) {
                assertEquals(if (x.k < t.installments) each else t.total.v - each * (t.installments - 1), x.cents.v,
                    "transaction ${t.id.v} installment ${x.k}: total / n rounded down, last absorbs the rest")
            }
            assertEquals(t.date, inst.first().date, "transaction ${t.id.v} first installment on its date")
            for (i in 1 until inst.size) assertTrue(inst[i - 1].date < inst[i].date, "transaction ${t.id.v} installments in date order")
        }
    }

    suspend fun checkNotices(txs: List<TransactionRow>) {
        val pending = db.pending()
        val dismissed = db.dismissed()
        assertEquals(pending.size, db.pendingCount(), "pendingCount() == pending().size")
        for (i in 1 until pending.size) {
            assertTrue(pending[i - 1].raw.postedAt >= pending[i].raw.postedAt, "pending() newest first")
        }
        assertTrue(pending.all { it.status == NoticeStatus.PENDING }, "pending() rows are PENDING")
        assertTrue(dismissed.all { it.status == NoticeStatus.DISMISSED }, "dismissed() rows are DISMISSED")
        val pendingIds = pending.map { it.id }
        val dismissedIds = dismissed.map { it.id }
        assertEquals(pendingIds.size, pendingIds.toSet().size, "pending() ids unique")
        assertEquals(dismissedIds.size, dismissedIds.toSet().size, "dismissed() ids unique")
        val known = notices.map { it.id }.toSet()
        assertTrue(known.containsAll(pendingIds), "pending() only has captured notices")
        assertTrue(known.containsAll(dismissedIds), "dismissed() only has captured notices")

        // Every reference to a notice, from a transaction or an estorno; each notice at most once.
        val refs = txs.filter { it.notice.v != 0L }.map { it.notice } +
            txs.filter { it.refunded }.map { it.estorno } +
            noticeRefunds.keys + noticeRefunds.values
        assertEquals(refs.size, refs.toSet().size, "a notice is referenced at most once: $refs")
        val referenced = refs.toSet()
        assertTrue(referenced.all { it in known }, "references point to captured notices")

        for (n in notices) {
            val row = db.notice(n.id)
            assertEquals(n.id, row.id, "notice(${n.id.v}) exists")
            val st = row.status
            if (n.id in referenced) {
                assertEquals(NoticeStatus.PROCESSED, st, "notice ${n.id.v} referenced -> PROCESSED")
            } else {
                assertNotEquals(NoticeStatus.PROCESSED, st, "notice ${n.id.v} not referenced -> not PROCESSED")
            }
            assertEquals(st == NoticeStatus.PENDING, n.id in pendingIds, "notice ${n.id.v} ($st) in pending() iff PENDING")
            assertEquals(st == NoticeStatus.DISMISSED, n.id in dismissedIds, "notice ${n.id.v} ($st) in dismissed() iff DISMISSED")
        }
    }

    suspend fun checkTags(txs: List<TransactionRow>) {
        val tags = db.tags()
        val names = tags.map { it.name }
        assertEquals(names.sorted(), names, "tags() sorted by name")
        assertEquals(names.size, names.toSet().size, "tag names unique")
        for (t in tags) {
            assertNotEquals(0L, t.id.v, "tag id")
            assertEquals(t.name.trim().lowercase(), t.name, "tag name trimmed and lowercase")
            assertEquals(txs.count { t.name in it.tags }, t.uses, "uses of tag ${t.name}")
            assertEquals(txs.count { it.tags == listOf(t.name) }, t.soleUses, "soleUses of tag ${t.name}")
        }
        for (t in txs) assertTrue(names.containsAll(t.tags), "tags of transaction ${t.id.v} exist: ${t.tags}")
    }

    suspend fun checkMonth(
        m: YearMonth,
        settings: Settings,
        txs: List<TransactionRow>,
        details: Map<TransactionId, TransactionDetail>,
        full: Boolean,
        out: MutableMap<Pair<BudgetId, YearMonth>, BudgetMonth>,
    ) {
        db.ensureMonths(m)
        val start = monthStart(m, settings.monthStartDay)
        val end = monthStart(m.add(1), settings.monthStartDay).minus(1, DateTimeUnit.DAY)
        if (full) {
            assertEquals(m, db.monthOf(start), "monthOf($start)")
            assertEquals(m, db.monthOf(end), "monthOf($end)")
            assertEquals(m.add(1), db.monthOf(end.plus(1, DateTimeUnit.DAY)), "monthOf(day after $end)")
        }
        // Σ of the installments of non-refunded transactions dated in start..end, for one budget or all.
        fun spentIn(from: LocalDate, to: LocalDate, budget: BudgetId?) = txs
            .filter { !it.refunded && (budget == null || it.budget == budget) }
            .sumOf { t -> details.getValue(t.id).installments.filter { it.date in from..to }.sumOf { it.cents.v } }

        var sumOfBudgets = 0L
        var allBudgetsHaveRow = true
        for ((id, model) in budgets) {
            if (m < model.firstMonth) {
                allBudgetsHaveRow = false
                continue
            }
            val what = "budget ${id.v} (${model.name}) in $m"
            val d = db.budgetDetail(id, m, TODAY)
            assertEquals(id, d.id, "$what: detail id")
            assertEquals(model.name, d.name, "$what: name")
            val bm = d.month
            out[id to m] = bm
            assertEquals(m, bm.month, "$what: month")
            assertEquals(start, bm.start, "$what: start (spec §3)")
            assertEquals(end, bm.end, "$what: end (spec §3)")
            assertTrue(bm.limit.v > 0, "$what: limit > 0")

            // Weeks: contiguous calendar weeks cut to the month (spec §4), steps summing to the limit.
            val weeks = bm.weeks
            assertTrue(weeks.isNotEmpty(), "$what: weeks")
            assertEquals(start, weeks.first().start, "$what: first week starts the month")
            assertEquals(end, weeks.last().end, "$what: last week ends the month")
            for ((i, w) in weeks.withIndex()) {
                assertTrue(w.start.daysUntil(w.end) in 0..6, "$what: week ${w.start}..${w.end} is 1..7 days")
                if (i > 0) {
                    assertEquals(weeks[i - 1].end.plus(1, DateTimeUnit.DAY), w.start, "$what: weeks contiguous, no overlap")
                    assertEquals(settings.weekStart, w.start.dayOfWeek, "$what: week ${w.start} starts on the week start day")
                }
                assertTrue(w.step.v >= 0, "$what: step of ${w.start} ≥ 0")
                assertEquals(spentIn(w.start, w.end, id), w.spent.v, "$what: spent of week ${w.start}")
            }
            assertTrue(weeks.any { !it.edited }, "$what: one week always stays automatic")
            assertEquals(bm.limit.v, weeks.sumOf { it.step.v }, "$what: Σ steps == limit")

            // Gasto do mês, margem and estado (spec §3, §5).
            val spent = spentIn(start, end, id)
            assertEquals(spent, bm.spent.v, "$what: spent")
            assertEquals(bm.spent.v, weeks.sumOf { it.spent.v }, "$what: Σ weeks.spent == spent")
            assertEquals(bm.limit - bm.spent, bm.remaining, "$what: remaining == limit − spent")
            val status = when {
                bm.spent > bm.limit -> PaceStatus.OVERRUN
                bm.spent > bm.ideal -> PaceStatus.AHEAD
                else -> PaceStatus.ON_PACE
            }
            assertEquals(status, bm.status, "$what: status (spent ${bm.spent.v}, ideal ${bm.ideal.v}, limit ${bm.limit.v})")
            val inMonth = txs.filter { t -> t.budget == id && details.getValue(t.id).installments.any { it.date in start..end } }
            assertEquals(inMonth.map { it.id }.toSet(), d.lines.map { it.row.id }.toSet(), "$what: detail transactions (refunded included)")
            sumOfBudgets += bm.spent.v
        }

        // Leitura: spent per day over the month window, from the installments' own dates.
        val byDay = db.spentByDay(start, end)
        val expected = HashMap<LocalDate, Long>()
        for (t in txs) {
            if (t.refunded) continue
            for (x in details.getValue(t.id).installments) if (x.date in start..end) expected[x.date] = (expected[x.date] ?: 0L) + x.cents.v
        }
        assertEquals(expected.filterValues { it != 0L }, byDay.mapValues { it.value.v }, "spentByDay over $m")
        if (allBudgetsHaveRow) assertEquals(sumOfBudgets, byDay.values.sumOf { it.v }, "Σ spentByDay over $m == Σ budgets' spent")
    }

    suspend fun checkHistory(months: List<YearMonth>, budgetMonths: Map<Pair<BudgetId, YearMonth>, BudgetMonth>) {
        val from = months.min()
        val to = months.max()
        val rows = db.history(from, to)
        assertEquals(rows.size, rows.map { it.budget to it.month }.toSet().size, "history rows unique per budget and month")
        for (r in rows) {
            assertTrue(r.month in from..to, "history row month ${r.month} in range")
            assertTrue(r.budget in budgets, "history row of an existing budget ${r.budget.v}")
            val bm = budgetMonths[r.budget to r.month] ?: continue
            assertEquals(bm.spent, r.spent, "history spent of ${r.budget.v} in ${r.month} == budgetDetail")
            assertEquals(bm.limit, r.limit, "history limit of ${r.budget.v} in ${r.month} == budgetDetail")
            assertEquals(budgets.getValue(r.budget).name, r.name, "history name")
        }
        for ((key, _) in budgetMonths) {
            if (key.second in from..to) assertTrue(rows.any { it.budget == key.first && it.month == key.second }, "history has ${key.first.v} in ${key.second}")
        }
    }

    suspend fun checkOverview(m0: YearMonth, budgetMonths: Map<Pair<BudgetId, YearMonth>, BudgetMonth>) {
        val az = db.budgetsOverview(m0, TODAY, BudgetOrder.AZ)
        assertEquals(az.map { it.name.lowercase() }.sorted(), az.map { it.name.lowercase() }, "overview A–Z order")
        for (s in az) {
            assertTrue(s.id in budgets, "overview budget ${s.id.v} exists")
            val bm = budgetMonths[s.id to m0]
            if (bm != null) assertEquals(bm, s.month, "overview month of ${s.id.v} == budgetDetail")
        }
        for ((id, model) in budgets) {
            if (m0 >= model.firstMonth) assertTrue(az.any { it.id == id }, "overview has budget ${id.v}")
        }
    }
}

class InvariantsTest {

    private fun randomRun(seed: Int, steps: Int = 120) = runTest(timeout = 90.seconds) {
        withDb { db -> InvariantRun(db, seed).run(steps) }
    }

    @Test fun randomActionsSeed1() = randomRun(1)

    @Test fun randomActionsSeed7() = randomRun(7)

    @Test fun randomActionsSeed42() = randomRun(42)

    @Test fun randomActionsSeed2026() = randomRun(2026)

    /** Spec §2.8, §6: deleting a refunded transaction deletes its estorno; both notices go back to the Pauta. */
    @Test
    fun deletingARefundedTransactionReturnsBothNotices() = runTest(timeout = 30.seconds) {
        withDb { db ->
            assertTrue(db.addSource(NU, "Nubank", T0))
            val budget = db.createBudget("Mercado", Cents(60_000), TODAY)
            val p = db.captured(1, purchaseText(3150, "LOJA X"))
            val e = db.captured(2, estornoText(3150, "LOJA X"))
            val tx = db.accept(p, form(budget, listOf("padaria"), 3150))
            assertNotEquals(0L, tx.v)
            assertTrue(db.registerRefund(e, tx))
            assertEquals(e, db.transaction(tx).row.estorno)
            assertEquals(NoticeStatus.PROCESSED, db.notice(p).status)
            assertEquals(NoticeStatus.PROCESSED, db.notice(e).status)
            assertEquals(0, db.pendingCount())
            assertEquals(Cents(0), db.spent(budget, db.monthOf(TODAY)))

            db.deleteTransaction(tx)

            assertEquals(emptyList(), db.transactions())
            assertEquals(0L, db.transaction(tx).row.id.v)
            assertEquals(setOf(p, e), db.pending().map { it.id }.toSet())
            assertEquals(NoticeStatus.PENDING, db.notice(p).status)
            assertEquals(NoticeStatus.PENDING, db.notice(e).status)
            assertFalse(db.undoRefund(e), "the estorno was deleted with the transaction")
            // Both are free again: the estorno can now cancel the pending purchase notice.
            assertTrue(db.registerRefund(e, p))
            assertEquals(0, db.pendingCount())
        }
    }

    /** Spec §2.9: deleting a tag deletes the transactions left without tags; their notices return. */
    @Test
    fun deletingATagDeletesTransactionsLeftWithoutTags() = runTest(timeout = 30.seconds) {
        withDb { db ->
            assertTrue(db.addSource(NU, "Nubank", T0))
            val budget = db.createBudget("Mercado", Cents(100_000), TODAY)
            val p1 = db.captured(1, purchaseText(1000, "LOJA X"))
            val p2 = db.captured(2, purchaseText(2000, "MERCADO BOM PRECO"))
            val p3 = db.captured(3, purchaseText(4000, "PADARIA DO ZE"))
            val e1 = db.captured(4, estornoText(1000, "LOJA X"))
            val t1 = db.accept(p1, form(budget, listOf("mercado"), 1000))
            val t2 = db.accept(p2, form(budget, listOf("Mercado", "feira"), 2000))
            val t3 = db.addManual(form(budget, listOf("mercado"), 8000)).transaction
            val t4 = db.accept(p3, form(budget, listOf("feira"), 4000))
            assertTrue(listOf(t1, t2, t3, t4).all { it.v != 0L })
            assertTrue(db.registerRefund(e1, t1))
            val mercado = db.tags().first { it.name == "mercado" }
            assertEquals(3, mercado.uses)
            assertEquals(2, mercado.soleUses)

            db.deleteTag(mercado.id)

            val txs = db.transactions().associateBy { it.id }
            assertEquals(setOf(t2, t4), txs.keys)
            assertEquals(listOf("feira"), txs.getValue(t2).tags)
            assertEquals(listOf("feira"), txs.getValue(t4).tags)
            assertEquals(setOf(p1, e1), db.pending().map { it.id }.toSet(), "notice of t1 and its estorno back in the Pauta")
            assertEquals(listOf(TagRow(db.tags().first().id, "feira", uses = 2, soleUses = 2)), db.tags())
            assertEquals(Cents(6000), db.spent(budget, db.monthOf(TODAY)))
        }
    }

    /** Spec §3: deleting a budget deletes its transactions; their notices (and estornos) return to the Pauta. */
    @Test
    fun deletingABudgetReturnsItsNotices() = runTest(timeout = 30.seconds) {
        withDb { db ->
            assertTrue(db.addSource(NU, "Nubank", T0))
            val a = db.createBudget("Mercado", Cents(100_000), TODAY)
            val b = db.createBudget("Casa", Cents(100_000), TODAY)
            val p1 = db.captured(1, purchaseText(1000, "LOJA X"))
            val p2 = db.captured(2, purchaseText(2000, "MERCADO BOM PRECO"))
            val p3 = db.captured(3, purchaseText(3000, "POSTO CENTRAL"))
            val p4 = db.captured(4, purchaseText(4000, "FARMACIA SAO JOAO"))
            val e1 = db.captured(5, estornoText(1000, "LOJA X"))
            val e4 = db.captured(6, estornoText(4000, "FARMACIA SAO JOAO"))
            val t1 = db.accept(p1, form(a, listOf("feira"), 1000, installments = 3))
            val t2 = db.accept(p2, form(a, listOf("feira", "casa"), 2000))
            val t3 = db.accept(p3, form(b, listOf("casa"), 3000))
            val tm = db.addManual(form(a, listOf("feira"), 500)).transaction
            assertTrue(listOf(t1, t2, t3, tm).all { it.v != 0L })
            assertTrue(db.registerRefund(e1, t1))
            assertTrue(db.registerRefund(e4, p4))
            assertEquals(0, db.pendingCount())
            val m0 = db.monthOf(TODAY)

            db.deleteBudget(a)

            assertEquals(listOf(t3), db.transactions().map { it.id })
            assertEquals(setOf(p1, p2, e1), db.pending().map { it.id }.toSet())
            assertEquals(NoticeStatus.PROCESSED, db.notice(p4).status, "an estorno of another notice is untouched")
            assertEquals(NoticeStatus.PROCESSED, db.notice(e4).status)
            db.ensureMonths(m0)
            assertEquals(0L, db.budgetDetail(a, m0, TODAY).id.v)
            assertEquals(listOf(b), db.budgetsOverview(m0, TODAY, BudgetOrder.AZ).map { it.id })
            assertEquals(Cents(3000), db.spent(b, m0))
            val tags = db.tags().associateBy { it.name }
            assertEquals(1, tags.getValue("casa").uses)
            assertEquals(0, tags.getValue("feira").uses)
            assertTrue(db.history(m0, m0).none { it.budget == a })
            assertNotEquals(0L, db.createBudget("mercado", Cents(1000), TODAY).v, "the name of a deleted budget is free")
        }
    }

    /** Glossary/Ajustes: juntar marcadores keeps every transaction, now tagged with the surviving tag. */
    @Test
    fun mergingTagsKeepsTransactionsTagged() = runTest(timeout = 30.seconds) {
        withDb { db ->
            val budget = db.createBudget("Mercado", Cents(100_000), TODAY)
            val t1 = db.addManual(form(budget, listOf("cafe"), 1000)).transaction
            val t2 = db.addManual(form(budget, listOf("cafe", "padaria"), 2000)).transaction
            val t3 = db.addManual(form(budget, listOf("padaria"), 3000)).transaction
            assertTrue(listOf(t1, t2, t3).all { it.v != 0L })
            val tags = db.tags().associateBy { it.name }
            val cafe = tags.getValue("cafe").id
            val padaria = tags.getValue("padaria").id
            val before = db.transactions()
            assertFalse(db.mergeTags(cafe, cafe))
            assertFalse(db.mergeTags(cafe, TagId(987_654)))
            assertFalse(db.mergeTags(TagId(987_654), cafe))
            assertEquals(before, db.transactions())

            assertTrue(db.mergeTags(cafe, padaria))

            val txs = db.transactions()
            assertEquals(setOf(t1, t2, t3), txs.map { it.id }.toSet())
            assertTrue(txs.all { it.tags == listOf("padaria") }, "every transaction tagged padaria: $txs")
            assertEquals(listOf(TagRow(padaria, "padaria", uses = 3, soleUses = 3)), db.tags())
            assertEquals(Cents(6000), db.spent(budget, db.monthOf(TODAY)))
        }
    }

    /** Spec §6: an estorno of a parcelada takes every installment out, in every month; undo brings them back. */
    @Test
    fun refundOfAParceladaRemovesEveryInstallment() = runTest(timeout = 30.seconds) {
        withDb { db ->
            assertTrue(db.addSource(NU, "Nubank", T0))
            val budget = db.createBudget("Casa", Cents(500_000), TODAY)
            val p = db.captured(1, purchaseText(120_000, "LOJA X"))
            val e = db.captured(2, estornoText(120_000, "LOJA X"))
            val tx = db.accept(p, form(budget, listOf("casa"), 120_000, installments = 10, date = LocalDate(2026, 10, 5)))
            assertNotEquals(0L, tx.v)
            val first = db.monthOf(LocalDate(2026, 10, 5))
            val months = (0..9).map { first.add(it) }
            for (m in months) assertEquals(Cents(12_000), db.spent(budget, m), "installment in $m")

            assertTrue(db.registerRefund(e, tx))
            for (m in months) assertEquals(Cents(0), db.spent(budget, m), "refunded installment in $m")
            assertTrue(db.history(months.first(), months.last()).all { it.spent == Cents(0) })
            assertEquals(emptyMap(), db.spentByDay(LocalDate(2026, 1, 1), LocalDate(2027, 12, 31)))
            assertFalse(db.registerRefund(e, tx), "the estorno is no longer pending")

            assertTrue(db.undoRefund(e))
            assertEquals(NoticeStatus.PENDING, db.notice(e).status)
            assertEquals(NoticeStatus.PROCESSED, db.notice(p).status)
            for (m in months) assertEquals(Cents(12_000), db.spent(budget, m), "installment back in $m")
            assertFalse(db.undoRefund(e), "nothing left to undo")
        }
    }

    /** Spec §6: an estorno of a notice not yet launched takes both out of the Pauta; undo returns both. */
    @Test
    fun refundOfAPendingNoticeAndUndo() = runTest(timeout = 30.seconds) {
        withDb { db ->
            assertTrue(db.addSource(NU, "Nubank", T0))
            val budget = db.createBudget("Casa", Cents(500_000), TODAY)
            val p = db.captured(1, purchaseText(5000, "LOJA X"))
            val e = db.captured(2, estornoText(5000, "LOJA X"))
            val other = db.captured(3, OTHER_TEXTS[0])
            assertFalse(db.registerRefund(e, e), "an estorno can't cancel itself")

            assertTrue(db.registerRefund(e, p))
            assertEquals(listOf(other), db.pending().map { it.id })
            assertEquals(1, db.pendingCount())
            assertFalse(db.dismiss(p), "a processed notice can't be riscado")
            assertEquals(0L, db.accept(p, form(budget, listOf("casa"), 5000)).v, "a processed notice can't be lançado")
            assertEquals(emptyList(), db.transactions())

            assertTrue(db.undoRefund(e))
            assertEquals(setOf(p, e, other), db.pending().map { it.id }.toSet())
            assertEquals(3, db.pendingCount())
        }
    }
}
