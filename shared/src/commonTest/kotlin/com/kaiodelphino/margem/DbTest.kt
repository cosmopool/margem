package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/** The Db through its public API, on a real SQLite file: capture → Pauta → lançar → verba, and every rule of §5. */
class DbTest {

    private val path = Path(SystemTemporaryDirectory, "margem-${Random.nextLong().toULong()}.db").toString()
    private var writes = 0
    private val db = Db(BundledSQLiteDriver(), path, onWrite = { writes++ }, timeZone = TimeZone.of("America/Sao_Paulo"))

    @AfterTest
    fun tearDown() {
        db.close()
        for (p in listOf(path, "$path-wal", "$path-shm", "$path.copy")) SystemFileSystem.delete(Path(p), mustExist = false)
    }

    private fun nu(text: String, at: Long = T, key: String = "nu|$at") =
        RawNotice(packageName = NUBANK, appLabel = "Nu", key = key, postedAt = at, whenMs = at, title = "Nubank", text = text, bigText = text)

    /** Captures and returns the new notice's id. */
    private suspend fun post(raw: RawNotice): NoticeId {
        assertTrue(capture(db, raw))
        return db.pending().first().id
    }

    /** Lança with exactly what was read from the notice. */
    private suspend fun acceptAsRead(id: NoticeId, budget: BudgetId, tags: List<String> = listOf("mercado"), installments: Int = 1): TransactionId {
        val p = db.notice(id).parsed
        return db.accept(id, TransactionForm(budget, tags, p.cents, installments, p.merchant, p.date, p.time))
    }

    private suspend fun setUp(): BudgetId {
        db.addSource(NUBANK, "Nu", 0)
        return db.createBudget("Mercado", Cents(60000), OCT_6)
    }

    @Test
    fun `a captured purchase goes from the Pauta to its budget month`() = runTest {
        val budget = setUp()
        val id = post(nu(PURCHASE))

        val notice = db.pending().single()
        assertEquals(Parsed(Cents(3150), "GIPL COMERCIO DE ALIME", OCT_6, LocalTime(11, 43)), notice.parsed)
        assertEquals("Nu", notice.raw.appLabel)
        assertTrue(notice.isNew)
        assertFalse(notice.looksLikeAd)
        db.markPautaSeen(T)
        assertFalse(db.pending().single().isNew)

        val tx = db.accept(id, TransactionForm(budget, listOf(" Feira ", "MERCADO", "feira", " "), Cents(3150), 1, "GIPL COMERCIO DE ALIME", OCT_6, LocalTime(11, 43)))
        assertNotEquals(TransactionId(0), tx)
        assertEquals(0, db.pendingCount())
        assertEquals(NoticeStatus.PROCESSED, db.notice(id).status)
        val row = db.transactions().single()
        assertEquals(listOf("feira", "mercado"), row.tags)
        assertEquals("Mercado" to Cents(3150), row.budgetName to row.total)
        assertFalse(row.corrected)
        assertEquals(TransactionId(0), acceptAsRead(id, budget))

        db.ensureMonths(YearMonth(2026, 10))
        val month = db.budgetsOverview(YearMonth(2026, 10), OCT_6, BudgetOrder.RISK).single().month
        assertEquals(Cents(3150) to Cents(56850), month.spent to month.remaining)
        assertEquals(Suggestion(budget, listOf("feira", "mercado")), db.suggestion("gipl  comercio de ALIME"))
        assertEquals(Suggestion(), db.suggestion("outra loja"))
    }

    @Test
    fun `lançar is refused without a tag a value a budget or valid installments`() = runTest {
        val budget = setUp()
        val id = post(nu(PURCHASE))
        val ok = TransactionForm(budget, listOf("mercado"), Cents(3150), 1, "GIPL", OCT_6, LocalTime(11, 43))
        for (bad in listOf(
            ok.copy(tags = listOf(" ", "")), ok.copy(total = Cents(0)), ok.copy(total = Cents(-5)), ok.copy(budget = BudgetId(99)),
            ok.copy(installments = 0), ok.copy(installments = 401),
        )) assertEquals(TransactionId(0), db.accept(id, bad), bad.toString())
        assertTrue(db.transactions().isEmpty())
        assertEquals(listOf(id), db.pending().map { it.id })

        assertNotEquals(TransactionId(0), db.accept(id, ok.copy(installments = 400)))
    }

    @Test
    fun `emendas mark the transaction corrected and an alias names the merchant everywhere`() = runTest {
        val budget = setUp()
        val first = post(nu(UBER, T))
        val tx = db.accept(first, TransactionForm(budget, listOf("uber"), Cents(994), 1, "DL*UberRides", OCT_6, LocalTime(11, 43), alias = " Uber "))
        assertTrue(db.transactions().single().corrected) // the merchant was read as "DL *UberRides"
        assertEquals("Uber", db.transactions().single().name)

        val second = post(nu(UBER, T + MIN))
        assertEquals("Uber", db.pending().single { it.id == second }.alias)

        val p = db.notice(first).parsed
        assertTrue(db.edit(tx, TransactionForm(budget, listOf("uber"), p.cents, 1, p.merchant, p.date, p.time)))
        assertFalse(db.transaction(tx).row.corrected) // back to what was read
        assertEquals(first, db.transaction(tx).notice.id)

        val manual = db.addManual(TransactionForm(budget, listOf("feira"), Cents(2000), 1, "Feira", OCT_6, LocalTime(9, 0)))
        assertFalse(db.transaction(manual).row.corrected)
        assertEquals(NoticeRow(), db.transaction(manual).notice)

        db.setAlias("dl *uberrides", "")
        assertEquals("DL *UberRides", db.transactions().single { it.id == tx }.name)
    }

    @Test
    fun `re-posts are deduped and real repeats kept`() = runTest {
        db.addSource(BANK, "My Bank", 0)
        fun bank(postedAt: Long, whenMs: Long = postedAt, text: String = "You spent \$4.50 at Coffee") =
            RawNotice(packageName = BANK, key = "bank|1", postedAt = postedAt, whenMs = whenMs, title = "Card purchase", text = text)

        // Within the window, then a genuine repeat after it.
        assertTrue(capture(db, bank(1_000_000)))
        assertFalse(capture(db, bank(1_000_000 + 5_000, whenMs = 0)))
        assertTrue(capture(db, bank(1_000_000 + DUPLICATE_WINDOW_MS, whenMs = 0)))
        // Catch-up after a reconnect: the shade still holds an already saved post.
        assertFalse(capture(db, bank(1_000_000)))
        // Same `when` long after (re-post after a reboot); same text with a new `when` is a new purchase.
        assertFalse(capture(db, bank(1_000_000 + 10 * DUPLICATE_WINDOW_MS, whenMs = 1_000_000)))
        assertTrue(capture(db, bank(1_000_000 + 20 * DUPLICATE_WINDOW_MS)))
        // when = 0 never matches by itself.
        assertTrue(capture(db, bank(1_000_000 + 40 * DUPLICATE_WINDOW_MS, whenMs = 0)))
        assertTrue(capture(db, bank(1_000_000 + 60 * DUPLICATE_WINDOW_MS, whenMs = 0)))
        assertTrue(capture(db, bank(1_000_000 + 60 * DUPLICATE_WINDOW_MS + 1, text = "You spent \$12.00 at Grocer")))
        assertEquals(6, db.pendingCount())

        // Noise: not a source, or no text at all.
        assertFalse(capture(db, bank(9_000_000).copy(packageName = "com.chat")))
        assertFalse(capture(db, RawNotice(packageName = BANK, key = "bank|2", postedAt = 9_000_000)))
        assertEquals(6, db.pendingCount())
    }

    @Test
    fun `riscar and trazer de volta`() = runTest {
        val budget = setUp()
        val ad = post(nu("Conheça as novidades do seu cartão Nu Empresas.", T))
        val purchase = post(nu(PURCHASE, T + MIN))
        assertTrue(db.pending().single { it.id == ad }.looksLikeAd)

        assertTrue(db.dismiss(ad))
        assertFalse(db.dismiss(ad))
        assertEquals(listOf(purchase), db.pending().map { it.id })
        assertEquals(listOf(ad), db.dismissed().map { it.id })
        assertEquals(NoticeStatus.DISMISSED, db.notice(ad).status)

        assertTrue(db.restore(ad))
        assertFalse(db.restore(ad))
        assertEquals(setOf(ad, purchase), db.pending().map { it.id }.toSet())

        acceptAsRead(purchase, budget)
        assertFalse(db.dismiss(purchase))
        assertEquals(TransactionId(0), acceptAsRead(ad, budget)) // read no value: total 0 is refused
        assertEquals(NoticeRow(), db.notice(NoticeId(999)))
    }

    @Test
    fun `an estorno of a transaction takes all its installments out and can be undone`() = runTest {
        val budget = setUp()
        val older = acceptAsRead(post(nu(UBER, T)), budget)
        val purchase = acceptAsRead(post(nu(UBER, T + 3 * MIN)), budget, installments = 2)
        val estorno = post(nu(UBER_REFUND, T + 6 * MIN))
        assertTrue(db.notice(estorno).parsed.isRefund)

        val candidates = db.refundCandidates(estorno)
        assertEquals(
            RefundCandidate(transaction = purchase, name = "DL *UberRides", total = Cents(994), date = OCT_6, likely = true, merchant = "DL *UberRides"),
            candidates.first(),
        )
        assertTrue(candidates.single { it.transaction == older }.likely)

        assertTrue(db.registerRefund(estorno, purchase))
        assertFalse(db.registerRefund(estorno, older)) // the estorno is no longer pending
        assertEquals(0, db.pendingCount())
        assertEquals(estorno, db.transactions().single { it.id == purchase }.estorno)
        db.ensureMonths(YearMonth(2026, 11))
        assertEquals(Cents(994), db.budgetDetail(budget, YearMonth(2026, 10), OCT_6).month.spent) // only the older one
        assertEquals(Cents(0), db.budgetDetail(budget, YearMonth(2026, 11), OCT_6).month.spent) // its 2nd installment too
        assertTrue(db.refundCandidates(estorno).none { it.transaction == purchase })

        assertTrue(db.undoRefund(estorno))
        assertFalse(db.undoRefund(estorno))
        assertEquals(listOf(estorno), db.pending().map { it.id })
        assertEquals(Cents(497), db.budgetDetail(budget, YearMonth(2026, 11), OCT_6).month.spent)
    }

    @Test
    fun `an estorno of a notice takes both out of the Pauta and nothing counts`() = runTest {
        setUp()
        val purchase = post(nu(UBER, T))
        val other = post(nu(PURCHASE, T + MIN))
        val estorno = post(nu(UBER_REFUND, T + 6 * MIN))

        val candidates = db.refundCandidates(estorno)
        assertEquals(
            RefundCandidate(notice = purchase, name = "DL *UberRides", total = Cents(994), date = OCT_6, likely = true, merchant = "DL *UberRides"),
            candidates.first(),
        )
        assertFalse(candidates.single { it.notice == other }.likely)
        assertTrue(candidates.none { it.notice == estorno })

        assertFalse(db.registerRefund(estorno, estorno))
        assertTrue(db.registerRefund(estorno, purchase))
        assertEquals(listOf(other), db.pending().map { it.id })
        assertTrue(db.transactions().isEmpty())

        assertTrue(db.undoRefund(estorno))
        assertEquals(setOf(purchase, other, estorno), db.pending().map { it.id }.toSet())
    }

    @Test
    fun `deleting a refunded transaction brings back both notices`() = runTest {
        val budget = setUp()
        val notice = post(nu(UBER, T))
        val tx = acceptAsRead(notice, budget)
        val estorno = post(nu(UBER_REFUND, T + 3 * MIN))
        assertTrue(db.registerRefund(estorno, tx))

        db.deleteTransaction(tx)

        assertEquals(setOf(notice, estorno), db.pending().map { it.id }.toSet())
        assertFalse(db.undoRefund(estorno))
        assertEquals(TransactionDetail(), db.transaction(tx))
        assertEquals(listOf("mercado"), db.tags().map { it.name }) // tags outlive their transactions
    }

    @Test
    fun `installments count each in its own month and the last takes the cents`() = runTest {
        val budget = setUp()
        val tx = db.addManual(TransactionForm(budget, listOf("casa"), Cents(100001), 10, "Loja", OCT_6, LocalTime(10, 0)))

        val installments = db.transaction(tx).installments
        assertEquals((1..10).toList(), installments.map { it.k })
        assertEquals(List(9) { 10000L } + 10001L, installments.map { it.cents.v })
        assertEquals(LocalDate(2027, 7, 6), installments.last().date)
        assertEquals(10, db.transactions().single().installments)

        db.ensureMonths(YearMonth(2027, 8))
        val history = db.history(YearMonth(2026, 10), YearMonth(2027, 8))
        assertEquals(List(9) { 10000L } + 10001L + 0L, history.map { it.spent.v })
        assertEquals(YearMonth(2026, 10), history.first().month)
        assertEquals(Cents(60000), history.last().limit)

        // Leitura: by installment date.
        assertEquals(RankRow("casa", Cents(20000), 1), db.tagRanking(LocalDate(2026, 10, 1), LocalDate(2026, 11, 30)).single())
        assertEquals(RankRow("Loja", Cents(10000), 1), db.merchantRanking(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30)).single())
        assertEquals(mapOf(OCT_6 to Cents(10000), LocalDate(2026, 11, 6) to Cents(10000)), db.spentByDay(LocalDate(2026, 10, 1), LocalDate(2026, 11, 30)))

        // The preview of another purchase in October sees this one's first installment.
        assertEquals(Preview(YearMonth(2026, 10), Cents(50000), Cents(45000)), db.previewAccept(budget, OCT_6, Cents(15000), 3, OCT_6))
        assertEquals(Preview(YearMonth(2026, 10), Cents(50000), Cents(55000)), db.previewAccept(budget, OCT_6, Cents(5000), 1, OCT_6, replacing = tx))
    }

    @Test
    fun `previewRefund gives back the purchase's installment of its first month`() = runTest {
        val budget = setUp()
        val cash = db.addManual(TransactionForm(budget, listOf("casa"), Cents(5990), 1, "Loja", OCT_6, LocalTime(10, 0)))
        val split = db.addManual(TransactionForm(budget, listOf("casa"), Cents(120000), 10, "Móveis", OCT_6, LocalTime(11, 0)))
        // October: 600,00 − 59,90 − 120,00 = 420,10; refunding the cash one gives its 59,90 back, the split one its 1st installment.
        assertEquals(Preview(YearMonth(2026, 10), Cents(42010), Cents(48000)), db.previewRefund(cash, NOV_20))
        assertEquals(Preview(YearMonth(2026, 10), Cents(42010), Cents(54010)), db.previewRefund(split, OCT_6))

        val estorno = post(nu(UBER_REFUND, T))
        assertTrue(db.registerRefund(estorno, cash))
        db.ensureMonths(YearMonth(2026, 10))
        assertEquals(Cents(48000), db.budgetDetail(budget, YearMonth(2026, 10), OCT_6).month.remaining)
        assertEquals(Preview(), db.previewRefund(cash, OCT_6))
        assertEquals(Preview(), db.previewRefund(TransactionId(999), OCT_6))
    }

    @Test
    fun `installments past 9999 are refused and a month crossing the edge still finds its own`() = runTest {
        val budget = setUp()
        val last = LocalDate(9999, 12, 31)
        // Dates are ISO text: "+10000-01-31" would sort before "0001-01-01".
        assertEquals(TransactionId(0), db.addManual(TransactionForm(budget, listOf("x"), Cents(200), 2, "Loja", last, LocalTime(10, 0))))
        assertEquals(Preview(), db.previewAccept(budget, last, Cents(200), 2, OCT_6))
        val tx = db.addManual(TransactionForm(budget, listOf("x"), Cents(200), 1, "Loja", last, LocalTime(10, 0)))
        assertNotEquals(TransactionId(0), tx)
        // With the month starting on the 15th, Dec 9999's month runs into year 10000.
        assertTrue(db.setMonthStartDay(15, OCT_6))
        assertEquals(Installment(1, last, Cents(200)), db.transactions(last).single().now)
        assertEquals(Installment(), db.transactions(LocalDate(10000, 3, 1)).single().now)
    }

    @Test
    fun `transactions of today carry the installment of today's month`() = runTest {
        val budget = setUp()
        val split = db.addManual(TransactionForm(budget, listOf("casa"), Cents(100001), 10, "Móveis", OCT_6, LocalTime(11, 0)))
        val later = db.addManual(TransactionForm(budget, listOf("casa"), Cents(500), 1, "Depois", LocalDate(2026, 12, 2), LocalTime(9, 0)))

        fun now(rows: List<TransactionRow>, id: TransactionId) = rows.single { it.id == id }.now
        assertEquals(Installment(1, OCT_6, Cents(10000)), now(db.transactions(OCT_6), split))
        assertEquals(Installment(2, LocalDate(2026, 11, 6), Cents(10000)), now(db.transactions(NOV_20), split))
        assertEquals(Installment(10, LocalDate(2027, 7, 6), Cents(10001)), now(db.transactions(LocalDate(2027, 7, 31)), split))
        assertEquals(Installment(), now(db.transactions(LocalDate(2027, 8, 1)), split))
        assertEquals(Installment(), now(db.transactions(OCT_6), later))
        assertEquals(Installment(), now(db.transactions(), split))
        // With the month starting on the 10th, Oct 9 is in Sep 10–Oct 9 (installment 1, Oct 6) and Oct 12 in Oct 10–Nov 9 (installment 2, Nov 6).
        assertTrue(db.setMonthStartDay(10, OCT_6))
        assertEquals(Installment(1, OCT_6, Cents(10000)), now(db.transactions(LocalDate(2026, 10, 9)), split))
        assertEquals(Installment(2, LocalDate(2026, 11, 6), Cents(10000)), now(db.transactions(LocalDate(2026, 10, 12)), split))
    }

    @Test
    fun `with a late month start day an installment lands inside its own month`() = runTest {
        val budget = setUp()
        assertTrue(db.setMonthStartDay(30, OCT_6))
        // Feb 2026 with day 30 starts on Feb 28 (no Feb 30) and ends on Mar 29; Mar 28 would still be February's.
        val tx = db.addManual(TransactionForm(budget, listOf("casa"), Cents(200), 2, "Loja", LocalDate(2026, 2, 28), LocalTime(10, 0)))
        assertEquals(listOf(LocalDate(2026, 2, 28), LocalDate(2026, 3, 30)), db.transaction(tx).installments.map { it.date })
        assertEquals(YearMonth(2026, 3), db.monthOf(LocalDate(2026, 3, 30)))
    }

    @Test
    fun `ensureMonths fills skipped months without bumping version and a new limit holds from now on`() = runTest {
        val budget = setUp()
        val version = db.version.value
        val calls = writes

        db.ensureMonths(YearMonth(2027, 1))
        db.ensureMonths(YearMonth(2027, 1))
        assertEquals(version, db.version.value)
        assertEquals(calls, writes)
        assertEquals(listOf(60000L, 60000, 60000, 60000), db.history(YearMonth(2026, 1), YearMonth(2027, 1)).map { it.limit.v })
        assertTrue(db.budgetsOverview(YearMonth(2026, 9), OCT_6, BudgetOrder.RISK).isEmpty()) // before it existed

        assertTrue(db.setLimit(budget, Cents(80000), LocalDate(2026, 12, 1)))
        assertEquals(version + 1, db.version.value)
        assertEquals(calls + 1, writes)
        assertEquals(listOf(60000L, 60000, 80000, 80000), db.history(YearMonth(2026, 10), YearMonth(2027, 1)).map { it.limit.v })
        assertFalse(db.setLimit(budget, Cents(0), OCT_6))
        assertFalse(db.setLimit(BudgetId(99), Cents(100), OCT_6))
        assertEquals(version + 1, db.version.value) // refused writes change nothing
    }

    @Test
    fun `steps are validated and a lower limit drops the edits it can't hold`() = runTest {
        val budget = setUp()
        val oct = YearMonth(2026, 10)
        db.ensureMonths(oct)
        // Oct 2026 from Monday: 1–4, 5–11, 12–18, 19–25, 26–31.
        val weeks = db.budgetDetail(budget, oct, OCT_6).month.weeks.map { it.start }
        assertEquals(listOf(1, 5, 12, 19, 26).map { LocalDate(2026, 10, it) }, weeks)

        assertFalse(db.setStep(budget, oct, LocalDate(2026, 10, 6), Cents(100))) // not a week start
        assertFalse(db.setStep(budget, oct, weeks[1], Cents(-1)))
        assertFalse(db.setStep(budget, oct, weeks[1], Cents(60001)))
        assertTrue(db.setStep(budget, oct, weeks[1], Cents(20000)))
        assertTrue(db.setStep(budget, oct, weeks[2], Cents(40000))) // max = 60000 − 20000
        assertTrue(db.setStep(budget, oct, weeks[3], Cents(0)))
        assertTrue(db.setStep(budget, oct, weeks[0], Cents(0)))
        assertFalse(db.setStep(budget, oct, weeks[4], Cents(0))) // the only automatic week left
        assertTrue(db.setStep(budget, oct, weeks[0], Cents(0))) // re-editing is fine
        assertEquals(listOf(0L, 20000, 40000, 0, 0), db.budgetDetail(budget, oct, OCT_6).month.weeks.map { it.step.v })

        assertTrue(db.setLimit(budget, Cents(60000), OCT_6)) // still fits
        assertEquals(4, db.budgetDetail(budget, oct, OCT_6).month.weeks.count { it.edited })
        assertTrue(db.setLimit(budget, Cents(59999), OCT_6))
        assertTrue(db.budgetDetail(budget, oct, OCT_6).month.weeks.none { it.edited })
    }

    @Test
    fun `weeks say how much they take and which one is locked and edits can go back to automatic`() = runTest {
        val budget = setUp()
        val oct = YearMonth(2026, 10)
        suspend fun weeks() = db.budgetDetail(budget, oct, OCT_6).month.weeks
        // Oct 2026 from Monday: 1–4 (4 days), 5–11, 12–18, 19–25 (7), 26–31 (6). R$ 600 by days, rounded
        // down: 600 × 4/31 = 77,41; 600 × 7/31 = 135,48; the last one takes the rest: 116,15 (spec §4).
        val default = listOf(7741L, 13548, 13548, 13548, 11615)
        assertEquals(default, weeks().map { it.step.v })
        assertTrue(weeks().all { it.max.v == 60000L && !it.locked })

        val start = weeks().map { it.start }
        assertTrue(db.setStep(budget, oct, start[1], Cents(20000)))
        assertEquals(listOf(40000L, 60000, 40000, 40000, 40000), weeks().map { it.max.v }) // 600 − 200 for the others
        assertFalse(db.setStep(budget, oct, start[2], Cents(40001)))
        assertTrue(db.setStep(budget, oct, start[2], Cents(10000)))
        assertTrue(db.setStep(budget, oct, start[3], Cents(10000)))
        assertTrue(db.setStep(budget, oct, start[0], Cents(10000)))
        assertEquals(listOf(false, false, false, false, true), weeks().map { it.locked })
        assertEquals(Cents(0), weeks()[4].max)
        assertEquals(Cents(30000), weeks()[1].max) // 600 − 100 − 100 − 100

        val v = db.version.value
        assertTrue(db.clearStep(budget, oct, start[2]))
        assertEquals(v + 1, db.version.value)
        assertTrue(weeks().none { it.locked })
        assertFalse(weeks()[2].edited)
        assertFalse(db.clearStep(budget, oct, start[2])) // no longer edited
        assertFalse(db.clearStep(budget, oct, LocalDate(2026, 10, 6))) // not a week start
        assertFalse(db.clearStep(budget, YearMonth(2026, 11), start[1])) // not a week of November
        assertFalse(db.clearStep(BudgetId(99), oct, start[1]))
        assertEquals(v + 1, db.version.value)
        for (w in listOf(0, 1, 3)) assertTrue(db.clearStep(budget, oct, start[w]))
        assertEquals(default, weeks().map { it.step.v })
    }

    @Test
    fun `a deleted budget's id never comes back`() = runTest {
        val first = db.createBudget("Mercado", Cents(100), OCT_6)
        val last = db.createBudget("Casa", Cents(100), OCT_6)
        db.deleteBudget(last)
        val next = db.createBudget("Casa", Cents(100), OCT_6)
        assertTrue(next.v > last.v && first.v < last.v)
    }

    @Test
    fun `a deleted transaction's id never comes back`() = runTest {
        val budget = setUp()
        val form = TransactionForm(budget, listOf("x"), Cents(100), 1, "Loja", OCT_6, LocalTime(10, 0))
        val first = db.addManual(form)
        val last = db.accept(post(nu(PURCHASE)), form)
        db.deleteTransaction(last)
        val next = db.addManual(form)
        assertTrue(next.v > last.v && first.v < last.v)
        assertEquals(TransactionDetail(), db.transaction(last))
    }

    @Test
    fun `limitDropsEdits says beforehand what setLimit deletes`() = runTest {
        val budget = setUp()
        val oct = YearMonth(2026, 10)
        db.ensureMonths(YearMonth(2026, 11))
        assertTrue(db.setStep(budget, oct, LocalDate(2026, 10, 5), Cents(20000)))
        assertTrue(db.setStep(budget, YearMonth(2026, 11), LocalDate(2026, 11, 2), Cents(25000)))

        val v = db.version.value
        assertFalse(db.limitDropsEdits(budget, Cents(25000), OCT_6)) // each month's edits fit
        assertTrue(db.limitDropsEdits(budget, Cents(24999), OCT_6)) // November's don't
        assertFalse(db.limitDropsEdits(budget, Cents(0), OCT_6))
        assertFalse(db.limitDropsEdits(BudgetId(99), Cents(1), OCT_6))
        // From December on there are no edits: nothing to drop.
        assertFalse(db.limitDropsEdits(budget, Cents(1), LocalDate(2026, 12, 1)))
        assertEquals(v, db.version.value)

        assertTrue(db.setLimit(budget, Cents(24999), OCT_6))
        assertTrue(db.budgetDetail(budget, oct, OCT_6).month.weeks.any { it.edited })
        assertTrue(db.budgetDetail(budget, YearMonth(2026, 11), OCT_6).month.weeks.none { it.edited })
    }

    @Test
    fun `previewBudget is the month a budget created today would have`() = runTest {
        val oct9 = LocalDate(2026, 10, 9)
        val preview = db.previewBudget(Cents(31000), oct9)
        // Book 10.7: R$ 310 in October → 40 · 70 · 70 · 70 · 60; ideal on the 9th = 40 + 70 × 5/7 = 90.
        assertEquals(listOf(4000L, 7000, 7000, 7000, 6000), preview.weeks.map { it.step.v })
        assertEquals(Cents(9000), preview.ideal)
        assertEquals(Cents(0), preview.spent)
        assertEquals(0L, db.version.value)
        assertEquals(BudgetMonth(), db.previewBudget(Cents(0), oct9))

        val id = db.createBudget("Presentes", Cents(31000), oct9)
        assertEquals(preview, db.budgetDetail(id, YearMonth(2026, 10), oct9).month)
    }

    @Test
    fun `the detail lists each transaction once with the installment of the month`() = runTest {
        db.addSource(NUBANK, "Nu", 0)
        val budget = db.createBudget("Casa", Cents(100000), LocalDate(2026, 8, 3))
        db.ensureMonths(YearMonth(2026, 10))
        val sofa = db.addManual(TransactionForm(budget, listOf("móveis"), Cents(120005), 10, "Movelaria Real", LocalDate(2026, 8, 15), LocalTime(10, 0)))
        val lamp = db.addManual(TransactionForm(budget, listOf("casa"), Cents(4500), 1, "Luz & Cia", LocalDate(2026, 10, 2), LocalTime(9, 0)))
        val uber = db.addManual(TransactionForm(budget, listOf("casa"), Cents(994), 1, "DL *UberRides", LocalDate(2026, 10, 20), LocalTime(9, 0)))
        assertTrue(db.registerRefund(post(nu(UBER_REFUND)), uber))

        val oct = db.budgetDetail(budget, YearMonth(2026, 10), LocalDate(2026, 10, 25))
        assertEquals(YearMonth(2026, 8), oct.firstMonth)
        // Oldest first by installment date: lamp (Oct 2), sofa's 3rd of 10 (Oct 15), the refunded Uber (Oct 20).
        assertEquals(listOf(lamp, sofa, uber), oct.lines.map { it.row.id })
        assertEquals(listOf(1, 3, 1), oct.lines.map { it.k })
        assertEquals(LocalDate(2026, 10, 15), oct.lines[1].date)
        assertEquals(listOf(4500L, 12000, 994), oct.lines.map { it.cents.v })
        assertTrue(oct.lines[2].row.refunded)
        assertEquals(Cents(16500), oct.month.spent) // the refunded one doesn't count
        // The last installment absorbs the cents: 1.200,05 − 9 × 120,00 = 120,05 (spec §6).
        val may = db.budgetDetail(budget, YearMonth(2027, 5), LocalDate(2026, 10, 25))
        assertEquals(listOf(10 to 12005L), may.lines.map { it.k to it.cents.v })
        assertEquals(NO_MONTH, db.budgetDetail(BudgetId(99), YearMonth(2026, 10), LocalDate(2026, 10, 25)).firstMonth)
    }

    @Test
    fun `changing the start days drops step edits from the current month on`() = runTest {
        db.addSource(NUBANK, "Nu", 0)
        val budget = db.createBudget("Mercado", Cents(60000), LocalDate(2026, 8, 1))
        db.ensureMonths(YearMonth(2026, 12))
        assertTrue(db.setStep(budget, YearMonth(2026, 10), LocalDate(2026, 10, 5), Cents(1000)))
        assertTrue(db.setStep(budget, YearMonth(2026, 11), LocalDate(2026, 11, 16), Cents(1000)))

        assertFalse(db.setMonthStartDay(0, NOV_20))
        assertTrue(db.setMonthStartDay(15, NOV_20))

        // September is now Sep 15 – Oct 14: the Oct 5 edit still matches a week and stays.
        val sep = db.budgetDetail(budget, YearMonth(2026, 9), NOV_20).month
        assertEquals(LocalDate(2026, 9, 15) to LocalDate(2026, 10, 14), sep.start to sep.end)
        assertTrue(sep.weeks.single { it.start == LocalDate(2026, 10, 5) }.edited)
        // November is now Nov 15 – Dec 14 and has a week on Nov 16, but that edit was dropped.
        assertTrue(db.budgetDetail(budget, YearMonth(2026, 11), NOV_20).month.weeks.none { it.edited })

        assertTrue(db.setStep(budget, YearMonth(2026, 11), LocalDate(2026, 11, 16), Cents(1000)))
        db.setWeekStart(DayOfWeek.SUNDAY, NOV_20)
        assertEquals(DayOfWeek.SUNDAY, db.settings().weekStart)
        assertTrue(db.budgetDetail(budget, YearMonth(2026, 11), NOV_20).month.weeks.none { it.edited })
    }

    @Test
    fun `a start day after today keeps every budget in the current month with its limit`() = runTest {
        val mercado = setUp()
        val casa = db.createBudget("Casa", Cents(100000), LocalDate(2026, 9, 6))
        assertTrue(db.setLimit(casa, Cents(150000), OCT_6))
        suspend fun current(): List<Pair<String, Long>> {
            val month = db.monthOf(OCT_6)
            db.ensureMonths(month)
            return db.budgetsOverview(month, OCT_6, BudgetOrder.AZ).map { it.name to it.month.limit.v }
        }

        // The 15th is after Oct 6: the current month goes back to Sep 15 – Oct 14.
        assertTrue(db.setMonthStartDay(15, OCT_6))
        assertEquals(YearMonth(2026, 9), db.monthOf(OCT_6))
        // Mercado had no September: it gets October's limit and September becomes its first month.
        // Casa already had a September limit, which stays.
        assertEquals(listOf("Casa" to 100000L, "Mercado" to 60000L), current())
        assertEquals(YearMonth(2026, 9), db.budgetDetail(mercado, YearMonth(2026, 9), OCT_6).firstMonth)

        assertTrue(db.setMonthStartDay(1, OCT_6))
        assertEquals(listOf("Casa" to 150000L, "Mercado" to 60000L), current())
    }

    @Test
    fun `deleting a budget or a tag sends their notices back to the Pauta`() = runTest {
        db.addSource(NUBANK, "Nu", 0)
        val a = db.createBudget("Casa", Cents(10000), OCT_6)
        val b = db.createBudget("casa ", Cents(10000), OCT_6)
        assertEquals(BudgetId(0), b) // names are unique ignoring case
        val c = db.createBudget("Lazer", Cents(10000), OCT_6)
        val n1 = post(nu(PURCHASE, T))
        val n2 = post(nu(UBER, T + MIN))
        acceptAsRead(n1, a, listOf("x"))
        val t2 = acceptAsRead(n2, c, listOf("x", "y"))

        db.deleteBudget(a)
        assertEquals(listOf(n1), db.pending().map { it.id })
        assertEquals(listOf(t2), db.transactions().map { it.id })

        val tags = db.tags().associateBy { it.name }
        assertEquals(TagRow(tags.getValue("x").id, "x", 1, 0), tags.getValue("x"))
        assertFalse(db.renameTag(tags.getValue("y").id, " X "))
        assertTrue(db.renameTag(tags.getValue("y").id, " Lazer "))
        assertTrue(db.mergeTags(tags.getValue("y").id, tags.getValue("x").id))
        assertEquals(listOf("x"), db.transactions().single().tags)
        assertEquals(1, db.tags().single().soleUses)

        db.deleteTag(tags.getValue("x").id)
        assertTrue(db.transactions().isEmpty())
        assertEquals(setOf(n1, n2), db.pending().map { it.id }.toSet())
    }

    @Test
    fun `export then import restores the database and junk is refused`() = runTest {
        val budget = setUp()
        val notice = post(nu(PURCHASE, T))
        acceptAsRead(notice, budget)
        val copy = "$path.copy"
        assertTrue(db.exportTo(copy))

        db.deleteBudget(budget)
        db.setTheme(Theme.DARK)
        assertTrue(db.transactions().isEmpty())

        val version = db.version.value
        assertTrue(db.importFrom(copy))
        assertEquals(version + 1, db.version.value)
        assertEquals(listOf("Mercado"), db.transactions().map { it.budgetName })
        assertEquals(Theme.SYSTEM, db.settings().theme)
        assertNotEquals(TransactionId(0), db.addManual(TransactionForm(budget, listOf("x"), Cents(1), 1, "", OCT_6, LocalTime(0, 0))))

        SystemFileSystem.sink(Path(copy)).buffered().use { it.write(ByteArray(4096) { i -> i.toByte() }) }
        assertFalse(db.importFrom(copy))
        assertFalse(db.importFrom("$path.missing"))
        assertEquals(2, db.transactions().size)
    }

    /** A cobudget backup (schema v7, the table importCobudget reads) at "$path.cobudget" with [rows]. */
    private fun cobudget(vararg rows: CobudgetRow, version: Int = 7): String {
        val file = "$path.cobudget"
        val c = BundledSQLiteDriver().open(file)
        c.exec(
            "CREATE TABLE `captured_notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, " +
                "`appLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, `postedAt` INTEGER NOT NULL, `notificationWhen` INTEGER NOT NULL DEFAULT 0, " +
                "`title` TEXT, `text` TEXT, `bigText` TEXT, `subText` TEXT, `textLines` TEXT, `category` TEXT, `channelId` TEXT, " +
                "`extrasJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `dismissed` INTEGER NOT NULL DEFAULT 0)",
        )
        for (r in rows) c.exec(
            "INSERT INTO captured_notifications(packageName, appLabel, notificationKey, postedAt, notificationWhen, title, text, bigText, " +
                "extrasJson, contentHash, dismissed) VALUES (?, 'Nu', ?, ?, ?, ?, ?, ?, '{}', 'old', ?)",
            r.pkg, r.key, r.at, r.at, r.title, r.text, r.text, r.dismissed,
        )
        c.exec("PRAGMA user_version = $version")
        c.close()
        return file
    }

    /** Like [nu]: title "Nubank" and the text also as big text. */
    private class CobudgetRow(
        val text: String, val at: Long, val dismissed: Boolean = false, val pkg: String = NUBANK, val key: String = "nu|$at", val title: String = "Nubank",
    )

    @Test
    fun `the cobudget import brings only notices and repeats stay out`() = runTest {
        setUp()
        val captured = post(nu(PURCHASE, T))
        val file = cobudget(
            CobudgetRow(UBER, T + MIN),                                // pending
            CobudgetRow("Pix recebido de Fulano", T + 2 * MIN, true),  // dismissed there: Riscados
            CobudgetRow(PURCHASE, T),                                  // the notice captured above: same key, content and when
            CobudgetRow("   ", T + 3 * MIN, title = ""),               // no text
            CobudgetRow(UBER_REFUND, T + 4 * MIN, pkg = "com.gone"),   // not a source: enters anyway (D4)
        )
        val version = db.version.value

        assertEquals(CobudgetImport(opened = true, pending = 2, dismissed = 1, skipped = 2), importCobudget(db, file))
        assertEquals(version + 1, db.version.value)
        assertEquals(listOf(UBER_REFUND, UBER, PURCHASE), db.pending().map { it.raw.text })
        assertEquals(captured, db.pending().last().id)
        assertEquals(listOf("Pix recebido de Fulano"), db.dismissed().map { it.raw.text })
        assertTrue(db.sources().none { it.packageName == "com.gone" })

        // Again: every notice is a re-post now, nothing is written.
        assertEquals(CobudgetImport(opened = true, skipped = 5), importCobudget(db, file))
        assertEquals(version + 1, db.version.value)
    }

    @Test
    fun `a file that is not a cobudget v7 backup is refused untouched`() = runTest {
        setUp()
        val old = cobudget(CobudgetRow(UBER, T), version = 6)
        assertEquals(CobudgetImport(), importCobudget(db, old))
        assertEquals(CobudgetImport(), importCobudget(db, "$path.missing"))
        val copy = "$path.copy"
        assertTrue(db.exportTo(copy))
        assertEquals(CobudgetImport(), importCobudget(db, copy))
        assertEquals(0, db.pendingCount())
    }

    @Test
    fun `startDropsEdits says when saving the start days deletes step edits`() = runTest {
        val budget = setUp()
        val oct = YearMonth(2026, 10)
        db.ensureMonths(oct)
        assertFalse(db.startDropsEdits(5, DayOfWeek.SUNDAY, OCT_6)) // no edits at all
        // Oct 2026 by day 1 with Monday weeks: Oct 1–4, 5–11, …; edit the week of Oct 5.
        assertTrue(db.setStep(budget, oct, LocalDate(2026, 10, 5), Cents(10000)))
        val version = db.version.value
        assertFalse(db.startDropsEdits(1, DayOfWeek.MONDAY, OCT_6))  // nothing changes
        assertFalse(db.startDropsEdits(0, DayOfWeek.SUNDAY, OCT_6))  // invalid day
        assertTrue(db.startDropsEdits(1, DayOfWeek.SUNDAY, OCT_6))   // week: cut at Oct 1
        assertTrue(db.startDropsEdits(10, DayOfWeek.MONDAY, OCT_6))  // month: cut at min(Oct 1, Sep 10)
        // Next month, the edit of October is in the past: nothing to delete.
        assertFalse(db.startDropsEdits(1, DayOfWeek.SUNDAY, NOV_20))
        assertEquals(version, db.version.value)
        db.setWeekStart(DayOfWeek.SUNDAY, OCT_6)
        assertFalse(db.startDropsEdits(1, DayOfWeek.MONDAY, OCT_6))
    }

    private companion object {
        const val NUBANK = "com.nu.production"
        const val BANK = "com.bank"
        const val MIN = 60_000L
        /** 2026-10-06 11:43:59 in São Paulo. */
        const val T = 1_791_297_839_102L
        val OCT_6 = LocalDate(2026, 10, 6)
        val NOV_20 = LocalDate(2026, 11, 20)
        const val PURCHASE = "Compra de R$ 31,50 APROVADA em GIPL COMERCIO DE ALIME para o cartão com final 1234."
        const val UBER = "Compra de R$ 9,94 APROVADA em DL          *UberRides no seu cartão Nu Empresas."
        const val UBER_REFUND = "A compra em DL          *UberRides no valor de R$ 9,94 foi estornada no seu cartão Nu Empresas."
    }
}
