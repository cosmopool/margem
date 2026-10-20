package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Contracts that `make mutation` (D48) found unchecked: a mutant of the code or of its SQL survived the
 * whole suite. Each test goes through the public API and states the rule the mutant broke.
 */
class MutationKillTest {

    private val path = temp("db")
    private val db = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))
    private val files = mutableListOf(path)

    @AfterTest
    fun tearDown() {
        db.close()
        for (p in files) for (s in listOf("", "-wal", "-shm", "-journal", ".import")) SystemFileSystem.delete(Path(p + s), mustExist = false)
    }

    private fun temp(tag: String) = Path(SystemTemporaryDirectory, "margem-mk-$tag-${Random.nextLong().toULong()}.db").toString()

    private fun form(budget: BudgetId, merchant: String, date: LocalDate, time: LocalTime = NOON, tags: List<String> = listOf("x"), alias: String = "") =
        TransactionForm(budget, tags, Cents(1000), 1, merchant, date, time, alias)

    private fun bank(key: String, at: Long, text: String) =
        RawNotice(packageName = BANK, appLabel = "Banco", key = key, postedAt = at, title = "Banco", text = text)

    // ---- Dedupe (D4) ----

    @Test
    fun `the latest notice of a key is the newest one when two were posted at the same time`() = runTest {
        db.addSource(BANK, "Banco", 0)
        assertTrue(capture(db, bank("k", T, "Compra de R$ 10,00 em A")))
        assertTrue(capture(db, bank("k", T, "Compra de R$ 20,00 em B")))
        // The latest of the key is B, so A again is not a re-post of it.
        assertTrue(capture(db, bank("k", T + 1_000, "Compra de R$ 10,00 em A")))
        assertEquals(3, db.pendingCount())
    }

    @Test
    fun `pauta and riscados put the later of two notices posted at the same time first`() = runTest {
        db.addSource(BANK, "Banco", 0)
        assertTrue(capture(db, bank("a", T, "Compra de R$ 10,00 em A")))
        assertTrue(capture(db, bank("b", T, "Compra de R$ 20,00 em B")))
        val order = db.pending().map { it.parsed.merchant }
        assertEquals(listOf("B", "A"), order)
        for (n in db.pending()) assertTrue(db.dismiss(n.id))
        assertEquals(order, db.dismissed().map { it.parsed.merchant })
    }

    // ---- Suggestion (spec §2.5) ----

    @Test
    fun `the suggestion comes from the merchant's latest transaction - by date then time then id`() = runTest {
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        val b = db.createBudget("B", Cents(100_000), OCT_6)

        db.addManual(form(b, "Feira", OCT_6, tags = listOf("nova")))
        db.addManual(form(a, "feira ", LocalDate(2026, 10, 5), tags = listOf("velha")))
        assertEquals(b to listOf("nova"), db.suggestion("FEIRA").let { it.budget to it.tags })

        db.addManual(form(a, "Padaria", OCT_6, LocalTime(9, 0), tags = listOf("tarde")))
        db.addManual(form(b, "Padaria", OCT_6, LocalTime(8, 0), tags = listOf("cedo")))
        assertEquals(a to listOf("tarde"), db.suggestion("padaria").let { it.budget to it.tags })

        db.addManual(form(a, "Banca", OCT_6, tags = listOf("primeira")))
        db.addManual(form(b, "Banca", OCT_6, tags = listOf("segunda")))
        assertEquals(b to listOf("segunda"), db.suggestion("Banca").let { it.budget to it.tags })
    }

    @Test
    fun `a blank merchant has no suggestion and takes no alias`() = runTest {
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        db.addManual(form(a, "", OCT_6, alias = "Feira"))
        db.addManual(form(a, "  ", OCT_6))
        assertEquals(Suggestion(), db.suggestion(""))
        assertEquals(Suggestion(), db.suggestion("   "))
        assertEquals(listOf("", ""), db.transactions().map { it.alias })
    }

    // ---- Refunds (spec §6) ----

    @Test
    fun `an unknown estorno has no candidates`() = runTest {
        db.addSource(BANK, "Banco", 0)
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        db.addManual(form(a, "Feira", OCT_6))
        capture(db, bank("k", T, "Compra de R$ 10,00 em A"))
        assertEquals(emptyList(), db.refundCandidates(NoticeId(999)))
    }

    @Test
    fun `a purchase posted at the same moment as the estorno is likely - and candidates go newest first by time`() = runTest {
        db.addSource(BANK, "Banco", 0)
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        assertTrue(capture(db, bank("p", T, "Compra de R$ 10,00 em LOJA")))
        val purchase = db.pending().single().id
        assertTrue(capture(db, bank("e", T, "Estorno de R$ 10,00 em LOJA")))
        val estorno = db.pending().first { it.parsed.isRefund }.id
        assertTrue(capture(db, bank("n", T - 1_000, "Compra de R$ 5,00 em BANCA às 21:00")))
        val night = db.pending().first { it.parsed.merchant == "BANCA" }.id
        db.addManual(form(a, "Feira", OCT_6, LocalTime(20, 0)))

        val c = db.refundCandidates(estorno)
        assertEquals(purchase, c.first().notice)
        assertTrue(c.first().likely)
        // Not likely, same day: the notice read at 21:00 comes before the transaction of 20:00.
        assertEquals(listOf(night.v, 0L), c.drop(1).map { it.notice.v })
        assertEquals(listOf(false, false), c.drop(1).map { it.likely })
    }

    @Test
    fun `likely needs a value - and the merchant only when the estorno names one`() = runTest {
        db.addSource(BANK, "Banco", 0)
        capture(db, bank("a", T - 3_000, "Compra de R$ 10,00 em LOJA"))
        capture(db, bank("b", T - 2_000, "Compra de R$ 10,00 em OUTRA"))
        capture(db, bank("c", T - 1_000, "Aviso do banco"))
        capture(db, bank("e1", T, "Estorno de R$ 10,00 em LOJA"))
        capture(db, bank("e2", T, "Estorno de R$ 10,00 efetuado"))
        capture(db, bank("e3", T, "Seu estorno foi efetuado"))
        val byKey = db.pending().associate { it.raw.key to it.id }
        suspend fun likely(estorno: String) = db.refundCandidates(byKey.getValue(estorno)).filter { it.likely }.map { it.notice }.toSet()

        assertEquals(setOf(byKey.getValue("a")), likely("e1"))
        assertEquals(setOf(byKey.getValue("a"), byKey.getValue("b")), likely("e2"))
        // No value read in e3: not even the notice without a value is likely.
        assertEquals(emptySet(), likely("e3"))
    }

    // ---- Steps (spec §4) ----

    @Test
    fun `a step can only be set on a week start of the month`() = runTest {
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        val starts = db.budgetDetail(a, YearMonth(2026, 10), OCT_6).month.weeks.map { it.start }
        assertFalse(LocalDate(2026, 10, 7) in starts)
        assertFalse(db.setStep(a, YearMonth(2026, 10), LocalDate(2026, 10, 7), Cents(0)))
        assertTrue(db.setStep(a, YearMonth(2026, 10), starts[1], Cents(0)))
    }

    // ---- Risk order (spec §8.1, §7.1) ----

    @Test
    fun `risk order - overrun by excess - ahead by distance to the ideal - on pace by margin share of the limit - then name`() = runTest {
        val today = LocalDate(2026, 10, 15)
        val spend = listOf(
            Triple("Gama", 1_000L, 0L), Triple("Beta", 1_000L, 0L),
            Triple("Alfa", 1_000L, 100L), Triple("Zeta", 100_000L, 40_000L),
            Triple("Pão", 100_000L, 60_000L), Triple("Uva", 100_000L, 90_000L),
            Triple("Ovo", 1_000L, 1_100L), Triple("Mel", 1_000L, 2_000L),
        )
        for ((name, limit, spent) in spend) {
            val id = db.createBudget(name, Cents(limit), today)
            if (spent > 0) db.addManual(TransactionForm(id, listOf("x"), Cents(spent), 1, name, LocalDate(2026, 10, 10), NOON))
        }
        val order = db.budgetsOverview(YearMonth(2026, 10), today, BudgetOrder.RISK)
        assertEquals(listOf("Mel", "Ovo", "Uva", "Pão", "Zeta", "Alfa", "Beta", "Gama"), order.map { it.name })
        val s = order.map { it.month.status }
        assertEquals(listOf(PaceStatus.OVERRUN, PaceStatus.OVERRUN, PaceStatus.AHEAD, PaceStatus.AHEAD) + List(4) { PaceStatus.ON_PACE }, s)
    }

    @Test
    fun `on pace the smaller margin share goes first whatever the manual order`() = runTest {
        val today = LocalDate(2026, 10, 15)
        // Zeta keeps 60% of its limit, Alfa 90%: Zeta first, in either manual order.
        for (names in listOf(listOf("Zeta", "Alfa"), listOf("Alfa", "Zeta"))) {
            val ids = names.map { name ->
                val (limit, spent) = if (name == "Zeta") 100_000L to 40_000L else 1_000L to 100L
                db.createBudget(name, Cents(limit), today).also {
                    db.addManual(TransactionForm(it, listOf("x"), Cents(spent), 1, name, LocalDate(2026, 10, 10), NOON))
                }
            }
            val order = db.budgetsOverview(YearMonth(2026, 10), today, BudgetOrder.RISK)
            assertEquals(listOf("Zeta", "Alfa"), order.map { it.name }, "manual order $names")
            assertEquals(listOf(PaceStatus.ON_PACE, PaceStatus.ON_PACE), order.map { it.month.status })
            ids.forEach { db.deleteBudget(it) }
        }
    }

    // ---- Limits and their bounds (spec §3, §4) ----

    @Test
    fun `values above the largest typed value are refused by previews and limits`() = runTest {
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        assertEquals(Preview(), db.previewAccept(a, OCT_6, Cents(0), 1, OCT_6))
        assertNotEquals(Preview(), db.previewAccept(a, OCT_6, Cents(1), 1, OCT_6))
        assertEquals(Preview(), db.previewAccept(a, OCT_6, Cents(1_000), 0, OCT_6))
        assertNotEquals(Preview(), db.previewAccept(a, OCT_6, Cents(MAX), 1, OCT_6))
        assertEquals(Preview(), db.previewAccept(a, OCT_6, Cents(MAX + 1), 1, OCT_6))
        assertNotEquals(Preview(), db.previewAccept(a, OCT_6, Cents(40_000), 400, OCT_6))
        assertEquals(Preview(), db.previewAccept(a, OCT_6, Cents(40_100), 401, OCT_6))

        assertTrue(db.setLimit(a, Cents(MAX), OCT_6))
        assertFalse(db.setLimit(a, Cents(MAX + 1), OCT_6))
        assertEquals(Cents(MAX), db.budgetDetail(a, YearMonth(2026, 10), OCT_6).month.limit)
    }

    @Test
    fun `a zero limit drops no edits because it is refused`() = runTest {
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        val week = db.budgetDetail(a, YearMonth(2026, 10), OCT_6).month.weeks[1].start
        assertTrue(db.setStep(a, YearMonth(2026, 10), week, Cents(5_000)))
        assertTrue(db.limitDropsEdits(a, Cents(4_000), OCT_6))
        assertFalse(db.limitDropsEdits(a, Cents(0), OCT_6))
    }

    @Test
    fun `a new limit holds in today's month even before the budget's first month`() = runTest {
        val a = db.createBudget("A", Cents(100_000), LocalDate(2026, 11, 10))
        assertTrue(db.setLimit(a, Cents(50_000), OCT_6))
        assertEquals(Cents(50_000), db.budgetDetail(a, YearMonth(2026, 10), OCT_6).month.limit)
        assertEquals(Cents(50_000), db.budgetDetail(a, YearMonth(2026, 11), OCT_6).month.limit)
    }

    // ---- Months (D8, D30, D43) ----

    @Test
    fun `ensureMonths creates limit rows up to the month and not past it`() = runTest {
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        db.ensureMonths(YearMonth(2026, 11))
        assertEquals(listOf(YearMonth(2026, 10), YearMonth(2026, 11)), db.history(YearMonth(2026, 9), YearMonth(2027, 1)).map { it.month })
        assertEquals(setOf(a), db.history(YearMonth(2026, 9), YearMonth(2027, 1)).map { it.budget }.toSet())
    }

    @Test
    fun `a new start day keeps every budget in the old and the new current month`() = runTest {
        // Created in August; the screens never opened September or October.
        val a = db.createBudget("A", Cents(100_000), LocalDate(2026, 8, 10))
        // On Oct 14 the current month was October (day 1); with day 20 it is September.
        assertTrue(db.setMonthStartDay(20, LocalDate(2026, 10, 14)))
        val rows = db.history(YearMonth(2026, 8), YearMonth(2026, 12))
        assertEquals(listOf(YearMonth(2026, 8), YearMonth(2026, 9), YearMonth(2026, 10)), rows.map { it.month })
        assertEquals(listOf(Cents(100_000)), rows.map { it.limit }.distinct())
        assertEquals(listOf(a), db.budgetsOverview(YearMonth(2026, 9), LocalDate(2026, 10, 14), BudgetOrder.MANUAL).map { it.id })
    }

    // ---- Dates at the edge of the storable range (D37) ----

    @Test
    fun `spending on the first storable day is found by a range ending on it`() = runTest {
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        db.addManual(form(a, "Feira", FIRST))
        assertEquals(mapOf(FIRST to Cents(1000)), db.spentByDay(FIRST, FIRST))
    }

    // ---- Transactions ----

    @Test
    fun `without today no row has a current installment - not even one dated in the month of NO_DATE`() = runTest {
        val a = db.createBudget("A", Cents(100_000), OCT_6)
        db.addManual(form(a, "Antiga", LocalDate(1970, 1, 15)))
        assertEquals(Installment(), db.transactions().single().now)
        assertEquals(Installment(1, LocalDate(1970, 1, 15), Cents(1000)), db.transactions(LocalDate(1970, 1, 20)).single().now)
    }

    // ---- Budgets ----

    @Test
    fun `a budget can be renamed to its own name in another case`() = runTest {
        val a = db.createBudget("Mercado", Cents(100_000), OCT_6)
        db.createBudget("Casa", Cents(100_000), OCT_6)
        assertTrue(db.renameBudget(a, "MERCADO"))
        assertFalse(db.renameBudget(a, "casa"))
        assertEquals(listOf("MERCADO", "Casa"), db.budgetsOverview(YearMonth(2026, 10), OCT_6, BudgetOrder.MANUAL).map { it.name })
    }

    // ---- Writes and version (D6, D21) ----

    @Test
    fun `refused writes and writes that change nothing do not bump the version`() = runTest {
        var writes = 0
        val other = temp("version").also { files.add(it) }
        val d = Db(BundledSQLiteDriver(), other, onWrite = { writes++ })
        try {
            d.removeSource("nope")
            assertFalse(d.dismiss(NoticeId(99)))
            assertFalse(d.restore(NoticeId(99)))
            assertFalse(d.renameBudget(BudgetId(99), "X"))
            assertFalse(d.undoRefund(NoticeId(99)))
            d.deleteTransaction(TransactionId(99))
            assertEquals(0L to 0, d.version.value to writes)
            d.setTheme(Theme.DARK)
            assertEquals(1L to 1, d.version.value to writes)
        } finally {
            d.close()
        }
    }

    @Test
    fun `the database file is in WAL mode`() = runTest {
        db.createBudget("A", Cents(100_000), OCT_6)
        val c = BundledSQLiteDriver().open(path)
        try {
            assertEquals("wal", c.prepare("PRAGMA journal_mode").use { it.step(); it.getText(0) })
        } finally {
            c.close()
        }
    }

    // ---- Capture (D25) ----

    @Test
    fun `a notice of only whitespace is noise`() = runTest {
        db.addSource(BANK, "Banco", 0)
        assertFalse(capture(db, RawNotice(packageName = BANK, key = "w", postedAt = T, title = " ", text = "\n", bigText = "\t", subText = "  ")))
        assertTrue(capture(db, RawNotice(packageName = BANK, key = "w", postedAt = T, title = " ", text = "x")))
        assertEquals(1, db.pendingCount())
    }

    // ---- Settings and sources ----

    @Test
    fun `theme and both orders are saved`() = runTest {
        db.setTheme(Theme.DARK)
        db.setBudgetOrder(BudgetOrder.MANUAL)
        db.setWidgetOrder(BudgetOrder.AZ)
        val s = db.settings()
        assertEquals(Triple(Theme.DARK, BudgetOrder.MANUAL, BudgetOrder.AZ), Triple(s.theme, s.budgetOrder, s.widgetOrder))
        db.setTheme(Theme.LIGHT)
        assertEquals(Theme.LIGHT, db.settings().theme)
        assertEquals(4L, db.version.value)
    }

    @Test
    fun `a source keeps the time it was added when relabeled`() = runTest {
        db.addSource(BANK, "Banco", 123)
        assertEquals(123L, db.sources().single().addedAt)
        db.addSource(BANK, "Banco Novo", 999)
        assertEquals("Banco Novo" to 123L, db.sources().single().let { it.label to it.addedAt })
    }

    // ---- Deletes leave no orphan rows (spec §2.8, §2.9, §3) ----

    // No public read sees these rows, so only the exported file can show them: the one test that checks
    // Margem rows in the file itself (D48, decided 2026-10-20).
    @Test
    fun `deleting a budget - a transaction or a tag leaves no row pointing at it in the file`() = runTest {
        db.addSource(BANK, "Banco", 0)
        val gone = db.createBudget("Some", Cents(100_000), OCT_6)
        val kept = db.createBudget("Fica", Cents(100_000), OCT_6)
        fun three(budget: BudgetId, merchant: String, tags: List<String>) = form(budget, merchant, OCT_6, tags = tags).copy(installments = 3)
        db.addManual(three(gone, "A", listOf("x")))
        val single = db.addManual(three(kept, "B", listOf("x")))
        val refunded = db.addManual(three(kept, "C", listOf("x")))
        val onlyTagged = db.addManual(three(kept, "D", listOf("solo")))
        val survivor = db.addManual(three(kept, "E", listOf("x", "solo")))
        assertTrue(capture(db, bank("e", T, "Estorno de R$ 10,00")))
        assertTrue(db.registerRefund(db.pending().single().id, refunded))
        db.ensureMonths(YearMonth(2026, 12))

        db.deleteBudget(gone)
        db.deleteTransaction(single)
        db.deleteTransaction(refunded)
        db.deleteTag(db.tags().first { it.name == "solo" }.id)
        assertEquals(TransactionDetail(), db.transaction(onlyTagged), "setup: a transaction left without tags goes")

        val file = temp("orphans").also { files.add(it) }
        assertTrue(db.exportTo(file))
        val c = BundledSQLiteDriver().open(file)
        try {
            for ((what, sql) in listOf(
                "budget_months of a deleted budget" to "SELECT COUNT(*) FROM budget_months WHERE budget_id NOT IN (SELECT id FROM budgets)",
                "week_steps of a deleted budget" to "SELECT COUNT(*) FROM week_steps WHERE budget_id NOT IN (SELECT id FROM budgets)",
                "installments of a deleted transaction" to "SELECT COUNT(*) FROM installments WHERE transaction_id NOT IN (SELECT id FROM transactions)",
                "tag links of a deleted transaction" to "SELECT COUNT(*) FROM transaction_tags WHERE transaction_id NOT IN (SELECT id FROM transactions)",
                "tag links of a deleted tag" to "SELECT COUNT(*) FROM transaction_tags WHERE tag_id NOT IN (SELECT id FROM tags)",
                "estornos of a deleted transaction" to "SELECT COUNT(*) FROM refunds WHERE transaction_id <> 0 AND transaction_id NOT IN (SELECT id FROM transactions)",
            )) assertEquals(0L, c.long(sql), what)
            // What stays is still there: the checks above aren't passing on an empty file.
            assertEquals(3L, c.long("SELECT COUNT(*) FROM budget_months WHERE budget_id = ?", kept.v))
            assertEquals(3L, c.long("SELECT COUNT(*) FROM installments WHERE transaction_id = ?", survivor.v))
            assertEquals(1L, c.long("SELECT COUNT(*) FROM transaction_tags WHERE transaction_id = ?", survivor.v))
        } finally {
            c.close()
        }
    }

    // ---- Backup and import (D14, D40) ----

    @Test
    fun `exporting twice to the same file replaces it`() = runTest {
        val file = temp("backup").also { files.add(it) }
        db.createBudget("A", Cents(100_000), OCT_6)
        assertTrue(db.exportTo(file))
        db.createBudget("B", Cents(100_000), OCT_6)
        assertTrue(db.exportTo(file))
        db.deleteBudget(db.budgetsOverview(YearMonth(2026, 10), OCT_6, BudgetOrder.MANUAL).first().id)
        assertTrue(db.importFrom(file))
        assertEquals(listOf("A", "B"), db.budgetsOverview(YearMonth(2026, 10), OCT_6, BudgetOrder.MANUAL).map { it.name })
    }

    @Test
    fun `a backup from a newer schema version is refused`() = runTest {
        val file = temp("newer").also { files.add(it) }
        db.createBudget("A", Cents(100_000), OCT_6)
        assertTrue(db.exportTo(file))
        BundledSQLiteDriver().open(file).let { c ->
            c.execSQL("PRAGMA user_version = 2")
            c.close()
        }
        db.createBudget("B", Cents(100_000), OCT_6)
        assertFalse(db.importFrom(file))
        assertEquals(listOf("A", "B"), db.budgetsOverview(YearMonth(2026, 10), OCT_6, BudgetOrder.MANUAL).map { it.name })
    }

    @Test
    fun `importing a missing file is refused and leaves no file behind`() = runTest {
        val missing = temp("missing").also { files.add(it) }
        assertFalse(db.importFrom(missing))
        assertFalse(SystemFileSystem.exists(Path(missing)))
        val r = importCobudget(db, missing)
        assertFalse(r.opened)
        assertEquals(CobudgetImport(), r)
        assertFalse(SystemFileSystem.exists(Path(missing)))
    }

    // ---- Types ----

    @Test
    fun `cents add up`() {
        assertEquals(Cents(400), Cents(150) + Cents(250))
        assertEquals(Cents(-100), Cents(150) + Cents(-250))
    }

    private companion object {
        const val BANK = "com.bank"
        /** The largest value parseBrl reads: 15 integer digits and 2 decimals. */
        const val MAX = 99_999_999_999_999_999L
        /** 2026-10-06 11:43:59 in São Paulo. */
        const val T = 1_791_297_839_102L
        val OCT_6 = LocalDate(2026, 10, 6)
        val NOON = LocalTime(12, 0)
        val FIRST = LocalDate(1, 1, 1)
    }
}
