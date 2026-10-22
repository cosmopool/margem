package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Edge cases of the block 4 additions (ciclo do recado), through the public API only: lançar,
 * editar, lançamento manual, estorno (registerRefund, undoRefund, previewRefund), previewAccept and
 * Lançamentos (transactions(today) with TransactionRow.now).
 * Expected values come from spec §2, §3, §6, §7.2–§7.4, architecture D7 and the KDoc of the
 * contract, computed by hand.
 *
 * Month windows used below (spec §3: from day D to the eve of day D next month; a missing D is
 * that month's last day):
 * - D = 1: the calendar month.
 * - D = 15: 2026-09 = Sep 15..Oct 14 · 2026-10 = Oct 15..Nov 14 · 2026-08 = Aug 15..Sep 14 · 2026-07 = Jul 15..Aug 14.
 * - D = 29 (2027, Feb has 28 days): 2026-12 = Dec 29..Jan 28 · 2027-01 = Jan 29..Feb 27 · 2027-02 = Feb 28..Mar 28.
 * - D = 30 (2027): 2026-12 = Dec 30..Jan 29 · 2027-01 = Jan 30..Feb 27 · 2027-02 = Feb 28..Mar 29 · 2027-03 = Mar 30..Apr 29.
 * - D = 31 (2027): 2027-01 = Jan 31..Feb 27 · 2027-02 = Feb 28..Mar 30 · 2027-03 = Mar 31..Apr 29 ·
 *   2027-04 = Apr 30..May 30 · 2027-05 = May 31..Jun 29.
 */
class RecadoEdgeTest {

    private fun withDb(block: suspend (Db) -> Unit): TestResult = runTest {
        val path = Path(SystemTemporaryDirectory, "margem-recado-edge-${Random.nextLong().toULong()}.db").toString()
        val db = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))
        try {
            db.addSource(NU, "Nubank", 0)
            block(db)
        } finally {
            db.close()
            for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
        }
    }

    private fun day(y: Int, m: Int, d: Int) = LocalDate(y, m, d)
    private fun ym(y: Int, m: Int) = YearMonth(y, m)

    /** Captures notice [n] (its own key, posted [n] minutes after T) and returns its id. */
    private suspend fun Db.post(n: Int, text: String): NoticeId {
        val at = T + n * MIN
        val key = "recado-edge|$n"
        assertTrue(capture(this, RawNotice(packageName = NU, appLabel = "Nubank", key = key, postedAt = at, whenMs = at, title = "Nubank", text = text, bigText = text)), "capture $n")
        return pending().single { it.raw.key == key }.id
    }

    private suspend fun Db.purchase(n: Int, cents: String = "31,50", merchant: String = "LOJA X") =
        post(n, "Compra de R$ $cents APROVADA em $merchant para o cartão com final 1234.")

    private suspend fun Db.estorno(n: Int, cents: String = "31,50", merchant: String = "LOJA X") =
        post(n, "A compra em $merchant no valor de R$ $cents foi estornada no seu cartão.")

    private suspend fun Db.budget(name: String, limit: Long, today: LocalDate = TODAY): BudgetId =
        createBudget(name, Cents(limit), today).also { assertNotEquals(0L, it.v, "createBudget $name") }

    private fun form(
        budget: BudgetId, total: Long, installments: Int = 1, date: LocalDate = OCT_6, merchant: String = "LOJA X",
        time: LocalTime = NOON, tags: List<String> = listOf("teste"), alias: String = "",
    ) = TransactionForm(budget, tags, Cents(total), installments, merchant, date, time, alias)

    /** The form of exactly what was read from the notice. */
    private suspend fun Db.asRead(id: NoticeId, budget: BudgetId, installments: Int = 1, tags: List<String> = listOf("teste")): TransactionForm {
        val p = notice(id).parsed
        return TransactionForm(budget, tags, p.cents, installments, p.merchant, p.date, p.time)
    }

    private suspend fun Db.manual(budget: BudgetId, total: Long, installments: Int = 1, date: LocalDate = OCT_6): TransactionId =
        addManual(form(budget, total, installments, date)).transaction.also { assertNotEquals(0L, it.v, "addManual $total in $installments on $date") }

    /** ensureMonths + budgetDetail, as the detail screen does. */
    private suspend fun Db.month(budget: BudgetId, month: YearMonth): BudgetMonth {
        ensureMonths(month)
        return budgetDetail(budget, month, TODAY).month
    }

    private suspend fun Db.spent(budget: BudgetId, month: YearMonth): Long = month(budget, month).spent.v

    private suspend fun Db.installments(id: TransactionId): List<Installment> = transaction(id).installments

    private fun inst(k: Int, date: LocalDate, cents: Long) = Installment(k, date, Cents(cents))

    // ---- Parcelas: 1..400 (spec §6, KDoc of accept) ----

    @Test
    fun `parcelas 1 e 400 são aceitas e 0 e 401 recusadas ao lançar no manual e ao editar`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val id = db.purchase(1)
        val read = db.asRead(id, b)

        for (n in listOf(0, 401, -1)) {
            assertEquals(TransactionId(0), db.accept(id, read.copy(installments = n)), "accept $n")
            assertEquals(TransactionId(0), db.addManual(form(b, 3150, n)).transaction, "addManual $n")
            // KDoc of previewAccept: zero when the inputs aren't valid.
            assertEquals(Preview(), db.previewAccept(b, OCT_6, Cents(3150), n, TODAY), "previewAccept $n")
        }
        assertTrue(db.transactions().isEmpty())
        assertEquals(listOf(id), db.pending().map { it.id })

        val one = db.accept(id, read.copy(installments = 1))
        assertNotEquals(TransactionId(0), one)
        assertEquals(listOf(inst(1, read.date, read.total.v)), db.installments(one))

        // 100001 / 400 = 250 rem 1: 399 × 250 and the last 251 (spec §6, the last absorbs the cents).
        // Installment 400 is dated Oct 6 + 399 months = 33 years 3 months later = 2060-01-06 (D7).
        val many = db.manual(b, 100001, 400)
        val parts = db.installments(many)
        assertEquals((1..400).toList(), parts.map { it.k })
        assertEquals(inst(1, OCT_6, 250), parts.first())
        assertEquals(inst(2, day(2026, 11, 6), 250), parts[1])
        assertEquals(inst(400, day(2060, 1, 6), 251), parts.last())
        assertTrue(parts.dropLast(1).all { it.cents == Cents(250) })
        assertEquals(100001L, parts.sumOf { it.cents.v })
        assertEquals(400, db.transaction(many).row.installments)

        assertFalse(db.edit(many, form(b, 100001, 0)))
        assertFalse(db.edit(many, form(b, 100001, 401)))
        assertEquals(400, db.installments(many).size)
        assertTrue(db.edit(many, form(b, 100001, 1)))
        assertEquals(listOf(inst(1, OCT_6, 100001)), db.installments(many))
        assertTrue(db.edit(many, form(b, 100001, 400)))
        assertEquals(400, db.installments(many).size)
    }

    @Test
    fun `total menor que o número de parcelas deixa zeros e a última absorve tudo`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        // KDoc of accept: each is total / n rounded down (3 / 5 = 0), the last absorbs the remainder (3).
        val tx = db.manual(b, 3, 5)
        assertEquals(
            listOf(
                inst(1, OCT_6, 0), inst(2, day(2026, 11, 6), 0), inst(3, day(2026, 12, 6), 0),
                inst(4, day(2027, 1, 6), 0), inst(5, day(2027, 2, 6), 3),
            ),
            db.installments(tx),
        )
        assertEquals(0L, db.spent(b, ym(2026, 10)))
        assertEquals(0L, db.spent(b, ym(2027, 1)))
        assertEquals(3L, db.spent(b, ym(2027, 2)))
        // An installment of R$ 0,00 still falls in the month: k = 1, not the zero Installment.
        assertEquals(inst(1, OCT_6, 0), db.transactions(TODAY).single().now)
        // previewAccept: the month of the first installment loses 0.
        assertEquals(Preview(ym(2026, 10), Cents(60000), Cents(60000)), db.previewAccept(b, OCT_6, Cents(3), 5, TODAY))
    }

    @Test
    fun `mil reais em 3 vezes são 333 e 33 duas vezes e 333 e 34 na última`() = withDb { db ->
        val b = db.budget("Casa", 200000)
        val tx = db.manual(b, 100000, 3)
        assertEquals(listOf(inst(1, OCT_6, 33333), inst(2, day(2026, 11, 6), 33333), inst(3, day(2026, 12, 6), 33334)), db.installments(tx))
        assertEquals(33333L, db.spent(b, ym(2026, 10)))
        assertEquals(33333L, db.spent(b, ym(2026, 11)))
        assertEquals(33334L, db.spent(b, ym(2026, 12)))
        assertEquals(0L, db.spent(b, ym(2027, 1)))

        // R$ 1,00 in 3: 33 · 33 · 34.
        val small = db.manual(b, 100, 3, day(2026, 10, 7))
        assertEquals(listOf(33L, 33L, 34L), db.installments(small).map { it.cents.v })
    }

    // ---- D7: purchase on days 29–31, installments across February ----

    @Test
    fun `dia de início 1 com compra no dia 31 e no dia 30 atravessando fevereiro`() = withDb { db ->
        val b = db.budget("Casa", 500000, day(2027, 1, 15))
        // Jan 31 + 1 month = Feb 28 (2027 has no Feb 29), + 2 months = Mar 31; all inside the calendar months.
        val jan31 = db.manual(b, 30000, 3, day(2027, 1, 31))
        assertEquals(listOf(inst(1, day(2027, 1, 31), 10000), inst(2, day(2027, 2, 28), 10000), inst(3, day(2027, 3, 31), 10000)), db.installments(jan31))
        // Leap year: Jan 30 2028 + 1 month = Feb 29 2028.
        val leap = db.manual(b, 2000, 2, day(2028, 1, 30))
        assertEquals(listOf(inst(1, day(2028, 1, 30), 1000), inst(2, day(2028, 2, 29), 1000)), db.installments(leap))

        assertEquals(10000L, db.spent(b, ym(2027, 1)))
        assertEquals(10000L, db.spent(b, ym(2027, 2)))
        assertEquals(10000L, db.spent(b, ym(2027, 3)))
        assertEquals(0L, db.spent(b, ym(2027, 4)))
        assertEquals(1000L, db.spent(b, ym(2028, 1)))
        assertEquals(1000L, db.spent(b, ym(2028, 2)))
        assertEquals(0L, db.spent(b, ym(2028, 3)))
    }

    @Test
    fun `dia de início 30 leva a parcela que cai fora do mês para o dia mais próximo dentro dele`() = withDb { db ->
        assertTrue(db.setMonthStartDay(30, day(2027, 1, 15)))
        val b = db.budget("Casa", 500000, day(2027, 1, 15)) // month 2026-12 = Dec 30..Jan 29
        // Jan 29 is in 2026-12. k = 2: Jan 29 + 1 month = Feb 28, which is in 2027-02 (Feb 28..Mar 29),
        // not in the 2nd month 2027-01 (Jan 30..Feb 27): moved to its nearest day inside, Feb 27.
        // k = 3: Jan 29 + 2 months = Mar 29, inside the 3rd month 2027-02.
        val tx = db.manual(b, 30000, 3, day(2027, 1, 29))
        assertEquals(listOf(inst(1, day(2027, 1, 29), 10000), inst(2, day(2027, 2, 27), 10000), inst(3, day(2027, 3, 29), 10000)), db.installments(tx))
        assertEquals(10000L, db.spent(b, ym(2026, 12)))
        assertEquals(10000L, db.spent(b, ym(2027, 1)))
        assertEquals(10000L, db.spent(b, ym(2027, 2)))
        assertEquals(0L, db.spent(b, ym(2027, 3)))
    }

    @Test
    fun `dia de início 31 com compra no dia 31 e no dia 30`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, day(2027, 1, 31)))
        val b = db.budget("Casa", 500000, day(2027, 1, 31)) // month 2027-01 = Jan 31..Feb 27
        // Jan 31 → Feb 28 (first day of 2027-02) → Mar 31 (first day of 2027-03): nothing moves.
        val jan31 = db.manual(b, 30000, 3, day(2027, 1, 31))
        assertEquals(listOf(inst(1, day(2027, 1, 31), 10000), inst(2, day(2027, 2, 28), 10000), inst(3, day(2027, 3, 31), 10000)), db.installments(jan31))
        // Mar 30 is in 2027-02 (Feb 28..Mar 30). k = 2: Apr 30 is in 2027-04, not in 2027-03 (Mar 31..Apr 29):
        // moved to Apr 29. k = 3: May 30, inside 2027-04 (Apr 30..May 30).
        val mar30 = db.manual(b, 3000, 3, day(2027, 3, 30))
        assertEquals(listOf(inst(1, day(2027, 3, 30), 1000), inst(2, day(2027, 4, 29), 1000), inst(3, day(2027, 5, 30), 1000)), db.installments(mar30))

        assertEquals(10000L, db.spent(b, ym(2027, 1)))
        assertEquals(11000L, db.spent(b, ym(2027, 2))) // Feb 28 (10000) + Mar 30 (1000)
        assertEquals(11000L, db.spent(b, ym(2027, 3))) // Mar 31 (10000) + Apr 29 (1000)
        assertEquals(1000L, db.spent(b, ym(2027, 4))) // May 30
        assertEquals(0L, db.spent(b, ym(2027, 5)))
    }

    @Test
    fun `dia de início 29 em ano sem 29 de fevereiro`() = withDb { db ->
        assertTrue(db.setMonthStartDay(29, day(2027, 1, 10)))
        val b = db.budget("Casa", 500000, day(2027, 1, 10)) // month 2026-12 = Dec 29..Jan 28
        // Jan 28 is in 2026-12. k = 2: Feb 28 is in 2027-02 (Feb 28..Mar 28), not in 2027-01 (Jan 29..Feb 27): Feb 27.
        val tx = db.manual(b, 20000, 2, day(2027, 1, 28))
        assertEquals(listOf(inst(1, day(2027, 1, 28), 10000), inst(2, day(2027, 2, 27), 10000)), db.installments(tx))
        assertEquals(10000L, db.spent(b, ym(2026, 12)))
        assertEquals(10000L, db.spent(b, ym(2027, 1)))
        assertEquals(0L, db.spent(b, ym(2027, 2)))
    }

    // ---- Editar ----

    @Test
    fun `editar trocando de verba tira o gasto de uma e põe na outra`() = withDb { db ->
        val a = db.budget("Mercado", 60000)
        val b = db.budget("Casa", 50000)
        val id = db.purchase(1)
        val tx = db.accept(id, form(a, 30000, 3))
        assertNotEquals(TransactionId(0), tx)
        assertEquals(10000L, db.spent(a, ym(2026, 10)))
        assertEquals(10000L, db.spent(a, ym(2026, 11)))

        assertTrue(db.edit(tx, form(b, 30000, 3)))
        for (m in listOf(ym(2026, 10), ym(2026, 11), ym(2026, 12))) {
            assertEquals(0L, db.spent(a, m), "Mercado $m")
            assertEquals(10000L, db.spent(b, m), "Casa $m")
        }
        assertTrue(db.budgetDetail(a, ym(2026, 10), TODAY).lines.isEmpty())
        assertEquals(listOf(tx), db.budgetDetail(b, ym(2026, 10), TODAY).lines.map { it.row.id })
        val row = db.transactions().single()
        assertEquals(b to "Casa", row.budget to row.budgetName)
        assertEquals(id, row.notice)
    }

    @Test
    fun `editar um lançamento estornado mantém o estorno mesmo trocando de verba`() = withDb { db ->
        val a = db.budget("Mercado", 60000)
        val b = db.budget("Casa", 50000)
        val tx = db.manual(a, 10000)
        val e = db.estorno(1)
        assertTrue(db.registerRefund(e, tx))

        assertTrue(db.edit(tx, form(b, 20000)))
        assertEquals(e, db.transaction(tx).row.estorno)
        assertEquals(0L, db.spent(a, ym(2026, 10)))
        assertEquals(0L, db.spent(b, ym(2026, 10)))

        assertTrue(db.undoRefund(e))
        assertEquals(0L, db.spent(a, ym(2026, 10)))
        assertEquals(20000L, db.spent(b, ym(2026, 10)))
    }

    // ---- previewAccept ----

    @Test
    fun `previewAccept sem e com replacing`() = withDb { db ->
        val a = db.budget("Mercado", 60000)
        val b = db.budget("Casa", 50000)
        val tx = db.manual(a, 10000)
        db.ensureMonths(ym(2026, 11))

        // Without replacing: margin 60000 − 10000 = 50000 → 50000 − 4000.
        assertEquals(Preview(ym(2026, 10), Cents(50000), Cents(46000)), db.previewAccept(a, OCT_10, Cents(4000), 1, TODAY))
        // 3 × 30000 in Oct: the month of the first installment loses 10000.
        assertEquals(Preview(ym(2026, 10), Cents(50000), Cents(40000)), db.previewAccept(a, OCT_10, Cents(30000), 3, TODAY))
        // Replacing tx (10000): before includes it, after swaps it for 4000 → 50000 + 10000 − 4000.
        assertEquals(Preview(ym(2026, 10), Cents(50000), Cents(56000)), db.previewAccept(a, OCT_10, Cents(4000), 1, TODAY, replacing = tx))
        // Replacing with 3 × 30000: 50000 + 10000 − 10000.
        assertEquals(Preview(ym(2026, 10), Cents(50000), Cents(50000)), db.previewAccept(a, OCT_10, Cents(30000), 3, TODAY, replacing = tx))
        // Moving it to another budget: Casa never had it, so after = 50000 − 4000.
        assertEquals(Preview(ym(2026, 10), Cents(50000), Cents(46000)), db.previewAccept(b, OCT_10, Cents(4000), 1, TODAY, replacing = tx))
        // Moving it to November: tx has nothing there, so after = 60000 − 4000.
        assertEquals(Preview(ym(2026, 11), Cents(60000), Cents(56000)), db.previewAccept(a, day(2026, 11, 3), Cents(4000), 1, TODAY, replacing = tx))

        assertEquals(Preview(), db.previewAccept(BudgetId(987654), OCT_10, Cents(4000), 1, TODAY))
        assertEquals(Preview(), db.previewAccept(a, OCT_10, Cents(0), 1, TODAY))

        // The preview of the edit is what the edit does.
        val after = db.previewAccept(a, OCT_10, Cents(4000), 1, TODAY, replacing = tx).after
        assertTrue(db.edit(tx, form(a, 4000, date = OCT_10)))
        assertEquals(after, db.month(a, ym(2026, 10)).remaining)
    }

    // ---- previewRefund ----

    @Test
    fun `previewRefund à vista devolve o valor e bate com a margem depois do estorno`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val tx = db.manual(a, 10000)
        db.manual(a, 5000, date = OCT_10)
        val e = db.estorno(1)
        val version = db.version.value

        // Before: 60000 − 15000 = 45000; after: + 10000.
        val preview = db.previewRefund(tx, TODAY)
        assertEquals(Preview(ym(2026, 10), Cents(45000), Cents(55000)), preview)
        assertEquals(version, db.version.value) // writes nothing
        assertEquals(preview.before, db.month(a, ym(2026, 10)).remaining)

        assertTrue(db.registerRefund(e, tx))
        assertEquals(preview.after, db.month(a, ym(2026, 10)).remaining)

        assertEquals(Preview(), db.previewRefund(tx, TODAY)) // already refunded
        assertEquals(Preview(), db.previewRefund(TransactionId(987654), TODAY)) // unknown

        assertTrue(db.undoRefund(e))
        assertEquals(preview, db.previewRefund(tx, TODAY))
    }

    @Test
    fun `previewRefund de parcelada devolve só a parcela do mês da primeira parcela`() = withDb { db ->
        val a = db.budget("Casa", 60000, day(2026, 9, 1))
        // Sep 10, 3 × 100000: Sep 33333 · Oct 33333 · Nov 33334.
        val tx = db.manual(a, 100000, 3, day(2026, 9, 10))
        val e = db.estorno(1)
        db.ensureMonths(ym(2026, 11))
        val version = db.version.value

        // Purchase month 2026-09 (not today's): 60000 − 33333 = 26667 → + 33333.
        val preview = db.previewRefund(tx, TODAY)
        assertEquals(Preview(ym(2026, 9), Cents(26667), Cents(60000)), preview)
        assertEquals(version, db.version.value) // writes nothing
        assertEquals(preview.before, db.month(a, ym(2026, 9)).remaining)

        assertTrue(db.registerRefund(e, tx))
        assertEquals(preview.after, db.month(a, ym(2026, 9)).remaining)
        // Spec §6: the estorno takes every installment, past and future.
        assertEquals(0L, db.spent(a, ym(2026, 10)))
        assertEquals(0L, db.spent(a, ym(2026, 11)))
    }

    // ---- registerRefund refusals ----

    @Test
    fun `estornos repetidos ou sobre recados processados são recusados`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val p1 = db.purchase(1, "10,00", "LOJA A")
        val p2 = db.purchase(2, "20,00", "LOJA B")
        val p3 = db.purchase(3, "30,00", "LOJA C")
        val p4 = db.purchase(4, "40,00", "LOJA D")
        val tx1 = db.accept(p1, db.asRead(p1, a))
        val tx2 = db.accept(p2, db.asRead(p2, a))
        assertNotEquals(TransactionId(0), tx1)
        assertNotEquals(TransactionId(0), tx2)
        val e1 = db.estorno(11, "10,00", "LOJA A")
        val e2 = db.estorno(12, "20,00", "LOJA B")
        val e3 = db.estorno(13, "30,00", "LOJA C")

        suspend fun refused(what: String, block: suspend () -> Boolean) {
            val v = db.version.value
            assertFalse(block(), what)
            assertEquals(v, db.version.value, "$what bumped the version") // a refused write doesn't bump
        }

        assertTrue(db.registerRefund(e1, tx1))
        refused("second estorno on the same purchase") { db.registerRefund(e2, tx1) }
        assertEquals(e1, db.transaction(tx1).row.estorno)
        assertEquals(NoticeStatus.PENDING, db.notice(e2).status)
        refused("same estorno on another purchase") { db.registerRefund(e1, tx2) }
        refused("same estorno on a pending notice") { db.registerRefund(e1, p3) }
        assertFalse(db.transaction(tx2).row.refunded)
        refused("estorno on a launched notice") { db.registerRefund(e2, p1) }
        refused("launched notice as the estorno") { db.registerRefund(p2, tx1) }
        refused("launched notice as the estorno of a notice") { db.registerRefund(p2, p3) }
        refused("same id twice") { db.registerRefund(e2, e2) }
        refused("same purchase id twice") { db.registerRefund(p3, p3) }
        refused("unknown transaction") { db.registerRefund(e2, TransactionId(987654)) }
        refused("unknown notice") { db.registerRefund(e2, NoticeId(987654)) }
        refused("unknown estorno") { db.registerRefund(NoticeId(987654), tx2) }

        assertTrue(db.registerRefund(e2, p3))
        refused("estorno already used on a notice") { db.registerRefund(e2, tx2) }
        refused("purchase notice already cancelled") { db.registerRefund(e3, p3) }

        assertTrue(db.dismiss(e3))
        refused("dismissed estorno on a transaction") { db.registerRefund(e3, tx2) }
        refused("dismissed estorno on a notice") { db.registerRefund(e3, p4) }
        assertTrue(db.dismiss(p4))
        assertTrue(db.restore(e3))
        refused("estorno on a dismissed notice") { db.registerRefund(e3, p4) }

        assertEquals(listOf(e3), db.pending().map { it.id })
        assertFalse(db.transaction(tx2).row.refunded)
    }

    @Test
    fun `undoRefund duas vezes e a segunda é false`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val tx = db.manual(a, 10000)
        val e1 = db.estorno(1)
        assertTrue(db.registerRefund(e1, tx))
        assertTrue(db.undoRefund(e1))
        val v = db.version.value
        assertFalse(db.undoRefund(e1))
        assertEquals(v, db.version.value)
        assertFalse(db.transaction(tx).row.refunded)
        assertEquals(NoticeStatus.PENDING, db.notice(e1).status)
        assertEquals(10000L, db.spent(a, ym(2026, 10)))

        // Notice ↔ notice: both come back, then nothing is left to undo.
        val p = db.purchase(2, "20,00", "LOJA B")
        val e2 = db.estorno(3, "20,00", "LOJA B")
        assertTrue(db.registerRefund(e2, p))
        assertTrue(db.undoRefund(e2))
        assertEquals(NoticeStatus.PENDING, db.notice(p).status)
        assertEquals(NoticeStatus.PENDING, db.notice(e2).status)
        assertFalse(db.undoRefund(e2))

        assertFalse(db.undoRefund(NoticeId(987654)))
        assertFalse(db.undoRefund(p)) // a purchase notice is not an estorno
    }

    @Test
    fun `apagar lançamento estornado devolve à Pauta o recado da compra e o de estorno`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val p = db.purchase(1)
        val tx = db.accept(p, db.asRead(p, a, installments = 2))
        val e = db.estorno(2)
        assertTrue(db.registerRefund(e, tx))
        assertTrue(db.pending().isEmpty())

        db.deleteTransaction(tx)
        assertTrue(db.transactions().isEmpty())
        assertEquals(setOf(p, e), db.pending().map { it.id }.toSet())
        assertEquals(NoticeStatus.PENDING, db.notice(p).status)
        assertEquals(NoticeStatus.PENDING, db.notice(e).status)
        assertFalse(db.undoRefund(e)) // the estorno was deleted with it
        assertEquals(TransactionDetail(), db.transaction(tx))
        // Both pending again: they can now cancel each other.
        assertTrue(db.registerRefund(e, p))

        // A manual one: it simply goes, and its estorno comes back.
        val manual = db.manual(a, 5000)
        val e2 = db.estorno(3, "50,00")
        assertTrue(db.registerRefund(e2, manual))
        db.deleteTransaction(manual)
        assertEquals(listOf(e2), db.pending().map { it.id })
        assertTrue(db.transactions().isEmpty())
    }

    // ---- corrected (emendado) ----

    @Test
    fun `lançamento manual nunca é emendado`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val tx = db.manual(a, 1234, 3)
        assertFalse(db.transaction(tx).row.corrected)
        assertEquals(NoticeId(0), db.transaction(tx).row.notice)
        assertEquals(NoticeRow(), db.transaction(tx).notice)
        assertTrue(db.edit(tx, form(a, 999, 1, OCT_10, "OUTRA", LocalTime(8, 1))))
        assertFalse(db.transaction(tx).row.corrected)
        assertFalse(db.transactions().single().corrected)
    }

    @Test
    fun `emendado compara campo a campo valor estabelecimento data e hora com o lido`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val p = db.notice(db.purchase(0)).parsed
        assertNotEquals(Cents(0), p.cents)
        assertNotEquals("", p.merchant)
        fun nextMinute(t: LocalTime) = LocalTime((t.hour + (t.minute + 1) / 60) % 24, (t.minute + 1) % 60)
        val cases = listOf<Pair<String, (TransactionForm) -> TransactionForm>>(
            "as read" to { it },
            "total" to { it.copy(total = Cents(it.total.v + 1)) },
            "merchant" to { it.copy(merchant = it.merchant + " X") },
            "merchant emptied" to { it.copy(merchant = "") },
            "date" to { it.copy(date = it.date.minus(1, DateTimeUnit.DAY)) },
            "time" to { it.copy(time = nextMinute(it.time)) },
            "alias only" to { it.copy(alias = "Apelido") },
            "installments only" to { it.copy(installments = 2) },
            "tags only" to { it.copy(tags = listOf("outra")) },
        )
        val expected = mapOf(
            "as read" to false, "total" to true, "merchant" to true, "merchant emptied" to true, "date" to true,
            "time" to true, "alias only" to false, "installments only" to false, "tags only" to false,
        )
        for ((i, case) in cases.withIndex()) {
            val (name, change) = case
            val id = db.purchase(i + 1) // same text as notice 0: same Parsed except its own posting time
            val read = db.asRead(id, a)
            val tx = db.accept(id, change(read))
            assertNotEquals(TransactionId(0), tx, name)
            assertEquals(expected.getValue(name), db.transaction(tx).row.corrected, name)

            // edit recomputes it against the notice's Parsed.
            assertTrue(db.edit(tx, read))
            assertFalse(db.transaction(tx).row.corrected, "$name edited back")
            assertTrue(db.edit(tx, read.copy(total = Cents(read.total.v + 100))))
            assertTrue(db.transaction(tx).row.corrected, "$name edited to another total")
        }
    }

    // ---- Tags, merchant, Parece propaganda ----

    @Test
    fun `marcadores com caixa espaços e repetidos ficam limpos e únicos e só brancos são recusados`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val id = db.purchase(1)
        val read = db.asRead(id, a)

        for (blanks in listOf(emptyList(), listOf(""), listOf(" ", "   ", ""))) {
            assertEquals(TransactionId(0), db.accept(id, read.copy(tags = blanks)), "accept $blanks")
            assertEquals(TransactionId(0), db.addManual(form(a, 1000, tags = blanks)).transaction, "addManual $blanks")
        }
        assertTrue(db.transactions().isEmpty())
        assertEquals(listOf(id), db.pending().map { it.id })

        val tx = db.accept(id, read.copy(tags = listOf(" Feira ", "FEIRA", "feira", "  ", "Padaria", "padaria ", "")))
        assertNotEquals(TransactionId(0), tx)
        assertEquals(listOf("feira", "padaria"), db.transactions().single().tags)
        assertEquals(listOf("feira" to 1, "padaria" to 1), db.tags().map { it.name to it.uses })

        assertFalse(db.edit(tx, read.copy(tags = listOf(" ", ""))))
        assertEquals(listOf("feira", "padaria"), db.transaction(tx).row.tags)

        assertTrue(db.edit(tx, read.copy(tags = listOf("  CAFE", "cafe  ", "Feira"))))
        assertEquals(listOf("cafe", "feira"), db.transaction(tx).row.tags)
    }

    @Test
    fun `estabelecimento vazio é aceito`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val id = db.purchase(1)
        val tx = db.accept(id, db.asRead(id, a).copy(merchant = ""))
        assertNotEquals(TransactionId(0), tx)
        val row = db.transaction(tx).row
        assertEquals("" to "", row.merchant to row.name)

        val manual = db.addManual(form(a, 500, merchant = "")).transaction
        assertNotEquals(TransactionId(0), manual)
        assertEquals("", db.transaction(manual).row.merchant)
        assertEquals(2, db.transactions().size)
    }

    @Test
    fun `recado que parece propaganda é lançado com os valores informados`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val id = db.post(1, "Confira as novidades do app.")
        val notice = db.notice(id)
        assertTrue(notice.looksLikeAd, "precondition: nothing read from this text")
        assertEquals(Cents(0), notice.parsed.cents)

        val tx = db.accept(id, form(a, 2500, date = OCT_6, merchant = "FEIRA LIVRE", time = LocalTime(9, 0), tags = listOf("feira")))
        assertNotEquals(TransactionId(0), tx)
        val row = db.transaction(tx).row
        assertEquals(Cents(2500), row.total)
        assertEquals("FEIRA LIVRE", row.merchant)
        assertEquals(OCT_6 to LocalTime(9, 0), row.date to row.time)
        assertEquals(id, row.notice)
        assertTrue(row.corrected) // 2500 differs from the 0 that was read
        assertEquals(NoticeStatus.PROCESSED, db.notice(id).status)
        assertEquals(2500L, db.spent(a, ym(2026, 10)))
    }

    // ---- Lançamentos: transactions(today) and TransactionRow.now ----

    @Test
    fun `parcela do mês de hoje em Lançamentos`() = withDb { db ->
        val a = db.budget("Casa", 900000, day(2026, 7, 1))
        val avista = db.manual(a, 5000, 1, OCT_6)
        val first = db.manual(a, 30000, 3, day(2026, 10, 2)) // Oct 2 · Nov 2 · Dec 2
        val middle = db.manual(a, 120000, 10, day(2026, 8, 10)) // spec §6: R$ 1.200 in 10× = 120,00; Aug, Sep, Oct = k 3
        val last = db.manual(a, 100000, 3, day(2026, 8, 20)) // Aug 20 · Sep 20 · Oct 20 = 333,34
        val ended = db.manual(a, 30000, 3, day(2026, 7, 10)) // Jul · Aug · Sep
        val later = db.manual(a, 4000, 1, day(2026, 11, 20)) // a month after today's
        val laterParcelada = db.manual(a, 9000, 3, day(2026, 11, 3))
        val before = db.manual(a, 700, 1, day(2026, 9, 30))

        val expected = mapOf(
            avista to inst(1, OCT_6, 5000),
            first to inst(1, day(2026, 10, 2), 10000),
            middle to inst(3, day(2026, 10, 10), 12000),
            last to inst(3, day(2026, 10, 20), 33334),
            ended to Installment(),
            later to Installment(),
            laterParcelada to Installment(),
            before to Installment(),
        )
        val rows = db.transactions(TODAY)
        assertEquals(expected.keys, rows.map { it.id }.toSet())
        for (r in rows) assertEquals(expected.getValue(r.id), r.now, "now of ${r.id.v}")

        // Without today, every now is zero.
        assertTrue(db.transactions().all { it.now == Installment() })
        assertEquals(expected.keys, db.transactions().map { it.id }.toSet())

        // Refunded or not, it's the same.
        val e = db.estorno(1, "1.200,00")
        assertTrue(db.registerRefund(e, middle))
        val refunded = db.transactions(TODAY).single { it.id == middle }
        assertTrue(refunded.refunded)
        assertEquals(inst(3, day(2026, 10, 10), 12000), refunded.now)
        // Every other read keeps now zero.
        assertEquals(Installment(), db.transaction(middle).row.now)
    }

    @Test
    fun `parcela do mês de hoje com dia de início 15`() = withDb { db ->
        assertTrue(db.setMonthStartDay(15, TODAY))
        val a = db.budget("Casa", 900000, day(2026, 7, 20))
        // TODAY = Oct 14 is in 2026-09 = Sep 15..Oct 14.
        val sep20 = db.manual(a, 1000, 1, day(2026, 9, 20))
        val oct14 = db.manual(a, 2000, 1, day(2026, 10, 14))
        val oct15 = db.manual(a, 3000, 1, day(2026, 10, 15))
        val sep14 = db.manual(a, 4000, 1, day(2026, 9, 14))
        val aug20 = db.manual(a, 30000, 3, day(2026, 8, 20)) // Aug 20 (2026-08) · Sep 20 (2026-09) · Oct 20 (2026-10)
        val aug14 = db.manual(a, 60000, 3, day(2026, 8, 14)) // Aug 14 (2026-07) · Sep 14 (2026-08) · Oct 14 (2026-09)

        val now14 = db.transactions(TODAY).associate { it.id to it.now }
        assertEquals(inst(1, day(2026, 9, 20), 1000), now14[sep20])
        assertEquals(inst(1, day(2026, 10, 14), 2000), now14[oct14])
        assertEquals(Installment(), now14[oct15])
        assertEquals(Installment(), now14[sep14])
        assertEquals(inst(2, day(2026, 9, 20), 10000), now14[aug20])
        assertEquals(inst(3, day(2026, 10, 14), 20000), now14[aug14])

        // Oct 15 starts 2026-10 = Oct 15..Nov 14.
        val now15 = db.transactions(day(2026, 10, 15)).associate { it.id to it.now }
        assertEquals(Installment(), now15[sep20])
        assertEquals(Installment(), now15[oct14])
        assertEquals(inst(1, day(2026, 10, 15), 3000), now15[oct15])
        assertEquals(inst(3, day(2026, 10, 20), 10000), now15[aug20])
        assertEquals(Installment(), now15[aug14])
    }

    // ---- Apelido (spec §2.10) ----

    @Test
    fun `apelido em branco no formulário não remove o apelido existente`() = withDb { db ->
        val a = db.budget("Casa", 60000)
        val n1 = db.purchase(1, "12,00", "PADARIA DO ZE")
        val t1 = db.accept(n1, db.asRead(n1, a).copy(alias = "Padaria do Zé"))
        assertNotEquals(TransactionId(0), t1)
        assertEquals("Padaria do Zé", db.transaction(t1).row.name)

        for ((i, blank) in listOf("", " ", "   ").withIndex()) {
            val n = db.purchase(10 + i, "1${i},00", "PADARIA DO ZE")
            assertEquals("Padaria do Zé", db.notice(n).alias, "pending notice shows the alias")
            val t = db.accept(n, db.asRead(n, a).copy(alias = blank))
            assertNotEquals(TransactionId(0), t)
            assertEquals("Padaria do Zé", db.transaction(t).row.alias, "blank alias '$blank'")
        }
        assertTrue(db.edit(t1, db.asRead(n1, a).copy(alias = "")))
        assertTrue(db.transactions().all { it.alias == "Padaria do Zé" })

        val manual = db.addManual(form(a, 300, merchant = db.notice(n1).parsed.merchant, alias = " ")).transaction
        assertNotEquals(TransactionId(0), manual)
        assertEquals("Padaria do Zé", db.transaction(manual).row.name)
    }

    private companion object {
        const val NU = "com.nu.production"
        const val MIN = 60_000L
        /** 2026-10-06 11:43:59 in São Paulo: notices without a date in the text are read as Oct 6. */
        const val T = 1_791_297_839_102L
        val TODAY = LocalDate(2026, 10, 14)
        val OCT_6 = LocalDate(2026, 10, 6)
        val OCT_10 = LocalDate(2026, 10, 10)
        val NOON = LocalTime(12, 0)
    }
}
