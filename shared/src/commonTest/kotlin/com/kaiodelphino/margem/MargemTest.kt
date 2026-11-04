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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toInstant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * The margem honesta through the Db (margem-plan M1–M8, D68–D73): the open previstos reserve, their lançamentos are
 * fixed and stay in the occurrence's month, the ritmo is of the cota livre. Month start day 1, weeks from Monday.
 */
class MargemTest {

    private val tz = TimeZone.of("America/Sao_Paulo")
    private val path = Path(SystemTemporaryDirectory, "margem-margem-${Random.nextLong().toULong()}.db").toString()
    private val db = Db(BundledSQLiteDriver(), path, timeZone = tz)

    @AfterTest
    fun tearDown() {
        db.close()
        for (p in listOf(path, "$path-wal", "$path-shm")) SystemFileSystem.delete(Path(p), mustExist = false)
    }

    private val oct = YearMonth(2026, 10)
    private val nov = YearMonth(2026, 11)
    private val oct2 = LocalDate(2026, 10, 2)
    private var posts = 0

    private suspend fun casa(today: LocalDate = oct2) = db.createBudget("Casa", Cents(300_000), today).also { assertNotEquals(0L, it.v) }

    private fun form(budget: BudgetId, total: Long, date: LocalDate, caption: String = "", repeats: Repeats = Repeats.ONCE, n: Int = 1) =
        TransactionForm(budget, listOf("casa"), Cents(total), n, "", date, LocalTime(10, 0), caption = caption, repeats = repeats)

    /** Salvar previsto (D64): a date ahead of [today]. */
    private suspend fun previsto(budget: BudgetId, total: Long, date: LocalDate, today: LocalDate, repeats: Repeats = Repeats.ONCE, caption: String = "Aluguel"): PlannedId =
        db.addManual(form(budget, total, date, caption, repeats), today).planned.also { assertNotEquals(0L, it.v) }

    private suspend fun month(budget: BudgetId, m: YearMonth, today: LocalDate): BudgetMonth {
        db.ensureMonths(m)
        return db.budgetsOverview(m, today, BudgetOrder.MANUAL).single { it.id == budget }.month
    }

    /** A Nubank recado posted on [date] at [time]: a purchase, or an estorno when [refund]. */
    private suspend fun recado(merchant: String, cents: String, date: LocalDate, time: LocalTime = LocalTime(10, 0), refund: Boolean = false): NoticeRow {
        db.addSource(NU, "Nubank", 0)
        val at = LocalDateTime(date, time).toInstant(tz).toEpochMilliseconds()
        val key = "margem|${posts++}"
        val text = if (refund) "A compra em $merchant no valor de R$ $cents foi estornada no seu cartão."
            else "Compra de R$ $cents APROVADA em $merchant para o cartão com final 1234."
        assertTrue(capture(db, RawNotice(packageName = NU, appLabel = "Nubank", key = key, postedAt = at, whenMs = at, title = "Nubank", text = text)))
        return db.pending().single { it.raw.key == key }
    }

    @Test
    fun `every occurrence of the month reserves - a day 28 previsto seen on day 2 - and never a skipped one`() = runTest {
        val casa = casa(LocalDate(2026, 9, 20))
        previsto(casa, 200_000, LocalDate(2026, 9, 28), LocalDate(2026, 9, 20), Repeats.MONTHLY)
        // D71: on 2 out the 28 out occurrence is past today + 10 but in today's month: it is created and reserves (M1).
        val m = month(casa, oct, oct2)
        assertEquals(Cents(200_000), m.open)
        assertEquals(Cents(100_000), m.freeLimit)
        assertEquals(Cents(100_000), m.remaining)
        // The late September one keeps reserving in September (M8), not in October.
        assertEquals(Cents(200_000), month(casa, YearMonth(2026, 9), oct2).open)
        // "A caminho" still shows today + 10 only (R1): the late 28 set, not 28 out.
        assertEquals(listOf(LocalDate(2026, 9, 28)), db.planned(oct2).map { it.date })

        val october = db.budgetDetail(casa, oct, oct2).plannedLines.single()
        assertTrue(db.skipOccurrence(october.planned.occurrence))
        assertEquals(Cents(0), month(casa, oct, oct2).open)
        assertEquals(Cents(300_000), month(casa, oct, oct2).freeLimit)
        assertEquals(emptyList(), db.budgetDetail(casa, oct, oct2).plannedLines)
    }

    @Test
    fun `lancar closes the occurrence at the real value - fixed and out of the ritmo`() = runTest {
        val casa = casa()
        previsto(casa, 200_000, LocalDate(2026, 10, 5), oct2)
        assertNotEquals(0L, db.addManual(form(casa, 4_000, LocalDate(2026, 10, 1), "Padaria")).transaction.v)
        // M1, M3: margem 3000 − 40 − 2000 = 960; cota livre 1000.
        val before = month(casa, oct, oct2)
        assertEquals(Cents(96_000), before.remaining)
        assertEquals(Cents(100_000), before.freeLimit)
        assertEquals(PaceStatus.ON_PACE, before.status)

        val p = db.planned(oct2).single()
        val t = db.addManual(form(casa, 210_000, p.date, "Aluguel").copy(occurrence = p.occurrence)).transaction
        assertNotEquals(0L, t.v)
        // M4: the whole 2100 is fixed; cota livre 900; the variável stays 40.
        val after = month(casa, oct, oct2)
        assertEquals(Cents(0), after.open)
        assertEquals(Cents(210_000), after.fixed)
        assertEquals(Cents(214_000), after.spent)
        assertEquals(Cents(4_000), after.variable)
        assertEquals(Cents(90_000), after.freeLimit)
        assertEquals(Cents(86_000), after.remaining)
        assertEquals(PaceStatus.ON_PACE, after.status)
        assertTrue(db.transactions(oct2).single { it.id == t }.fixed)
        assertFalse(db.transactions(oct2).single { it.id != t }.fixed)
        // Week.spent is the variável: the aluguel's week (5–11) shows nothing.
        assertEquals(Cents(0), after.weeks.single { it.start == LocalDate(2026, 10, 5) }.spent)
    }

    @Test
    fun `an estorno of a fixed lancamento counts 0 and its occurrence stays closed`() = runTest {
        val casa = casa()
        previsto(casa, 200_000, LocalDate(2026, 10, 5), oct2)
        val p = db.planned(oct2).single()
        val t = db.addManual(form(casa, 210_000, p.date, "Aluguel").copy(occurrence = p.occurrence)).transaction
        val estorno = recado("ALUGUEL", "2.100,00", LocalDate(2026, 10, 6), refund = true)
        assertTrue(db.registerRefund(estorno.id, t))
        val m = month(casa, oct, oct2)
        assertEquals(Cents(0), m.fixed)
        assertEquals(Cents(0), m.open)
        assertEquals(Cents(300_000), m.freeLimit)
        assertEquals(emptyList(), db.planned(oct2))
        assertEquals(PlannedLine(p.copy(late = false), Cents(0), p.date), db.budgetDetail(casa, oct, oct2).plannedLines.single())
    }

    @Test
    fun `a fixed lancamento in 3x is fixed in each month of its installments and stays fixed when its previsto goes`() = runTest {
        val casa = casa()
        val id = previsto(casa, 300_000, LocalDate(2026, 10, 5), oct2)
        val p = db.planned(oct2).single()
        assertNotEquals(0L, db.addManual(form(casa, 300_000, p.date, "Sofá", n = 3).copy(occurrence = p.occurrence)).transaction.v)
        for (m in listOf(oct, nov, YearMonth(2026, 12))) {
            val bm = month(casa, m, oct2)
            assertEquals(Cents(100_000), bm.fixed, "$m")
            assertEquals(Cents(0), bm.variable, "$m")
            assertEquals(Cents(200_000), bm.freeLimit, "$m")
        }
        db.deletePlanned(id)
        assertTrue(db.transactions(oct2).single().fixed)
        assertEquals(Cents(100_000), month(casa, nov, oct2).fixed)
        assertEquals(emptyList(), db.budgetDetail(casa, oct, oct2).plannedLines)
    }

    @Test
    fun `a late october occurrence lancada with a 3 nov recado lands on its october day and an edit to november too`() = runTest {
        val casa = casa(LocalDate(2026, 10, 20))
        previsto(casa, 200_000, LocalDate(2026, 10, 28), LocalDate(2026, 10, 20), Repeats.MONTHLY)
        val nov3 = LocalDate(2026, 11, 3)
        val n = recado("ALUGUEL", "2.000,00", nov3, LocalTime(9, 15))
        val p = db.planned(nov3).single()
        assertEquals(LocalDate(2026, 10, 28), p.date)
        assertTrue(p.late)
        val t = db.accept(n.id, TransactionForm(p.budget, p.tags, n.parsed.cents, 1, n.parsed.merchant, n.parsed.date, n.parsed.time, caption = p.name, occurrence = p.occurrence))
        assertNotEquals(0L, t.v)
        // M8, D72: the occurrence's day, the recado's time, no "‸ emendado".
        val row = db.transactions(nov3).single()
        assertEquals(LocalDate(2026, 10, 28), row.date)
        assertEquals(LocalTime(9, 15), row.time)
        assertFalse(row.corrected)
        assertTrue(row.fixed)
        assertEquals(Cents(200_000), month(casa, oct, nov3).fixed)
        // November keeps its own occurrence reserving, and nothing of the October bill.
        val november = month(casa, nov, nov3)
        assertEquals(Cents(0), november.fixed)
        assertEquals(Cents(200_000), november.open)

        // Edited to 10 nov at 11:00, keeping "Todo mês": still 28 out, with the new time.
        val edit = TransactionForm(casa, p.tags, Cents(200_000), 1, n.parsed.merchant, LocalDate(2026, 11, 10), LocalTime(11, 0), caption = p.name, repeats = Repeats.MONTHLY)
        assertTrue(db.edit(t, edit))
        val edited = db.transactions(nov3).single()
        assertEquals(LocalDate(2026, 10, 28), edited.date)
        assertEquals(LocalTime(11, 0), edited.time)
        assertTrue(edited.fixed)
        assertEquals(Cents(0), month(casa, nov, nov3).fixed)
    }

    @Test
    fun `an ordinary lancamento with the previsto open is variavel and the occurrence keeps reserving`() = runTest {
        val casa = casa()
        previsto(casa, 200_000, LocalDate(2026, 10, 5), oct2)
        assertNotEquals(0L, db.addManual(form(casa, 200_000, LocalDate(2026, 10, 5), "Aluguel")).transaction.v)
        val m = month(casa, oct, oct2)
        assertEquals(Cents(200_000), m.open)
        assertEquals(Cents(200_000), m.variable)
        assertEquals(Cents(0), m.fixed)
        // M1: 3000 − 2000 − 2000 = −1000.
        assertEquals(Cents(-100_000), m.remaining)
        assertEquals(PaceStatus.OVERRUN, m.status)
        assertFalse(db.transactions(oct2).single().fixed)
    }

    @Test
    fun `previstos de outubro lists the open and the lancada occurrences by day`() = runTest {
        val casa = casa()
        previsto(casa, 12_000, LocalDate(2026, 10, 20), oct2, Repeats.MONTHLY, caption = "Internet")
        previsto(casa, 200_000, LocalDate(2026, 10, 5), oct2)
        val aluguel = db.planned(oct2).single { it.name == "Aluguel" }
        assertNotEquals(0L, db.addManual(form(casa, 210_000, LocalDate(2026, 10, 4), "Aluguel").copy(occurrence = aluguel.occurrence)).transaction.v)
        val lines = db.budgetDetail(casa, oct, oct2).plannedLines
        assertEquals(listOf("Aluguel", "Internet"), lines.map { it.planned.name })
        assertEquals(PlannedLine(aluguel, Cents(210_000), LocalDate(2026, 10, 4)), lines[0])
        assertFalse(lines[0].open)
        val internet = lines[1]
        assertTrue(internet.open)
        assertEquals(Cents(12_000), internet.cents)
        assertEquals(NO_DATE, internet.launched)
        assertEquals(LocalDate(2026, 10, 20), internet.planned.date)
        assertEquals(Repeats.MONTHLY, internet.planned.repeats)
        assertEquals(LocalDate(2026, 11, 20), internet.planned.next)
        // Σ of the open lines = month.open (BudgetDetail KDoc).
        assertEquals(db.budgetDetail(casa, oct, oct2).month.open, Cents(lines.filter { it.open }.sumOf { it.cents.v }))
    }

    @Test
    fun `step edits and the limit are checked against the cota livre`() = runTest {
        val casa = casa()
        previsto(casa, 200_000, LocalDate(2026, 10, 25), oct2)
        val week = LocalDate(2026, 10, 5)
        // D70: Week.max = cota livre 1000 − the other edits.
        assertEquals(Cents(100_000), month(casa, oct, oct2).weeks.single { it.start == week }.max)
        assertFalse(db.setStep(casa, oct, week, Cents(100_001)))
        assertTrue(db.setStep(casa, oct, week, Cents(60_000)))
        assertEquals(Cents(60_000), month(casa, oct, oct2).weeks.single { it.start == week }.step)
        // A cota of 2500 leaves 500 free: the 600 edit no longer fits, so setLimit warns and drops it.
        assertTrue(db.limitDropsEdits(casa, Cents(250_000), oct2))
        assertFalse(db.limitDropsEdits(casa, Cents(260_000), oct2))
        assertTrue(db.setLimit(casa, Cents(250_000), oct2))
        assertFalse(month(casa, oct, oct2).weeks.any { it.edited })
    }

    @Test
    fun `a new previsto that leaves the edits over the cota livre stops them and deleting it brings them back`() = runTest {
        val casa = casa()
        val week = LocalDate(2026, 10, 5)
        assertTrue(db.setStep(casa, oct, week, Cents(150_000)))
        val id = previsto(casa, 200_000, LocalDate(2026, 10, 25), oct2)
        // M5, D70: 1500 edited > 1000 free → every week automatic, nothing deleted.
        assertFalse(month(casa, oct, oct2).weeks.any { it.edited })
        db.deletePlanned(id)
        assertEquals(Cents(150_000), month(casa, oct, oct2).weeks.single { it.start == week }.step)
    }

    private companion object {
        const val NU = "com.nu.production"
    }
}
