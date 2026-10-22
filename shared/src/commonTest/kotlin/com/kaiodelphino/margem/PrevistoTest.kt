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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toInstant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/** The previsto through the Db (spec §2.13, D59–D64): create, recognize, lançar, skip, delete, keys, migration. */
class PrevistoTest {

    private val tz = TimeZone.of("America/Sao_Paulo")
    private val path = Path(SystemTemporaryDirectory, "margem-previsto-${Random.nextLong().toULong()}.db").toString()
    private var db = Db(BundledSQLiteDriver(), path, timeZone = tz)

    @AfterTest
    fun tearDown() {
        db.close()
        for (p in listOf(path, "$path-wal", "$path-shm")) SystemFileSystem.delete(Path(p), mustExist = false)
    }

    private val oct9 = LocalDate(2026, 10, 9)
    private var posts = 0

    /** A Nubank purchase of [cents] at [merchant], posted on [date] at 10:00; returns its id. */
    private suspend fun recado(merchant: String, cents: String, date: LocalDate): NoticeId {
        db.addSource(NU, "Nubank", 0)
        val at = LocalDateTime(date, LocalTime(10, 0)).toInstant(tz).toEpochMilliseconds()
        val key = "previsto|${posts++}"
        val text = "Compra de R$ $cents APROVADA em $merchant para o cartão com final 1234."
        assertTrue(capture(db, RawNotice(packageName = NU, appLabel = "Nubank", key = key, postedAt = at, whenMs = at, title = "Nubank", text = text)))
        return db.pending().single { it.raw.key == key }.id
    }

    private suspend fun casa() = db.createBudget("Casa", Cents(93_000), oct9).also { assertNotEquals(0L, it.v) }

    private fun form(budget: BudgetId, total: Long, date: LocalDate, merchant: String = "", caption: String = "", repeats: Repeats = Repeats.ONCE) =
        TransactionForm(budget, listOf("contas"), Cents(total), 1, merchant, date, LocalTime(10, 0), caption = caption, repeats = repeats)

    /** The form "Lançar previsto" sends (D62): the previsto's budget, tags and name, the recado's value, bank text, date and time. */
    private fun launchForm(p: Planned, n: NoticeRow) = TransactionForm(
        p.budget, p.tags, n.parsed.cents, 1, n.parsed.merchant, n.parsed.date, n.parsed.time, caption = p.name, occurrence = p.occurrence,
    )

    @Test
    fun aDateAheadIsOnlyAPrevistoAndNeverCounts() = runTest {
        val casa = casa()
        val saved = db.addManual(form(casa, 18_000, LocalDate(2026, 10, 12), "Conta de luz"), oct9)
        assertEquals(TransactionId(0), saved.transaction)
        assertNotEquals(0L, saved.planned.v)
        assertEquals(emptyList(), db.transactions(oct9))
        db.ensureMonths(YearMonth(2026, 10))
        assertEquals(Cents(0), db.budgetsOverview(YearMonth(2026, 10), oct9, BudgetOrder.MANUAL).single().month.spent)
        val p = db.planned(oct9).single()
        assertEquals("Conta de luz", p.name)
        assertEquals(LocalDate(2026, 10, 12), p.date)
        assertEquals(listOf("contas"), p.tags)
        assertFalse(p.late)
        assertFalse(p.linked)
        // Without today, the same form is a plain lançamento (D64, contract note).
        assertNotEquals(0L, db.addManual(form(casa, 18_000, LocalDate(2026, 10, 12), "Conta de luz")).transaction.v)
    }

    @Test
    fun monthlyFromARecadoLinksAndRecognizesNextMonth() = runTest {
        val casa = casa()
        val sep = recado("VIVO FIBRA", "119,90", LocalDate(2026, 9, 10))
        val t = db.accept(sep, form(casa, 11_990, LocalDate(2026, 9, 10), "VIVO FIBRA", caption = "Internet", repeats = Repeats.MONTHLY))
        assertNotEquals(0L, t.v)
        // R1: the October occurrence (10 out) exists from 30 set on.
        val p = db.planned(oct9).single()
        assertEquals(LocalDate(2026, 10, 10), p.date)
        assertEquals("Internet", p.name)
        assertTrue(p.linked)
        assertEquals("VIVO FIBRA", p.linkText)
        assertEquals("Nubank", p.linkSource)
        assertEquals(LocalDate(2026, 11, 10), p.next)

        // Same fonte and key, accents and spacing aside, a day before: recognized (D60, D61).
        val oct = recado("VÍVO  FIBRA", "121,50", oct9)
        val row = db.pending(oct9).single { it.id == oct }
        assertEquals(p.occurrence, row.planned.occurrence)
        assertTrue(db.planned(oct9).single().noticeInPauta)

        // Lançar previsto: caption = name, the recado's value, not an emenda (P7, P9).
        val launched = db.accept(oct, launchForm(row.planned, row))
        val tx = db.transaction(launched).row
        assertEquals("Internet", tx.caption)
        assertEquals(Cents(12_150), tx.total)
        assertFalse(tx.corrected)
        assertEquals(emptyList(), db.planned(oct9).filter { it.date == LocalDate(2026, 10, 10) })

        // The occurrence is closed: a second lançamento of it is refused.
        val other = recado("VIVO FIBRA", "121,50", oct9)
        assertEquals(TransactionId(0), db.accept(other, launchForm(row.planned, row).copy(total = Cents(1))))

        // S1: deleting it brings the occurrence back, linked.
        db.deleteTransaction(launched)
        assertEquals(p.occurrence, db.planned(oct9).first().occurrence)
    }

    @Test
    fun theWindowIsTenDaysEitherSide() = runTest {
        val casa = casa()
        val sep = recado("ESTAPAR", "150,00", LocalDate(2026, 9, 15))
        db.accept(sep, form(casa, 15_000, LocalDate(2026, 9, 15), "ESTAPAR", repeats = Repeats.MONTHLY))
        // Occurrence on 15 out. 25 out is 10 days after (in), 26 out is 11 (out); 5 out is 10 before (in), 4 out out.
        for ((day, inside) in listOf(4 to false, 5 to true, 25 to true, 26 to false)) {
            val n = recado("ESTAPAR", "150,00", LocalDate(2026, 10, day))
            val row = db.pending(LocalDate(2026, 10, day)).single { it.id == n }
            assertEquals(inside, row.planned.occurrence.v != 0L, "recado on $day out")
            db.dismiss(n)
        }
    }

    @Test
    fun occurrencesExistUpToTodayPlusTen() = runTest {
        val casa = casa()
        db.addManual(form(casa, 8_990, LocalDate(2026, 10, 5), "Academia", repeats = Repeats.MONTHLY), LocalDate(2026, 10, 1))
        // 25 out + 10 = 4 nov: the 5 nov occurrence isn't there yet; 26 out + 10 = 5 nov: it is (R1).
        assertEquals(listOf(LocalDate(2026, 10, 5)), db.planned(LocalDate(2026, 10, 25)).map { it.date })
        assertEquals(listOf(LocalDate(2026, 10, 5), LocalDate(2026, 11, 5)), db.planned(LocalDate(2026, 10, 26)).map { it.date })
        // The late one and next month's coexist (P10); the late one is late.
        assertEquals(listOf(true, false), db.planned(LocalDate(2026, 10, 26)).map { it.late })
    }

    @Test
    fun dayThirtyOneUsesTheLastDayOfShortMonths() = runTest {
        val casa = casa()
        db.addManual(form(casa, 100_000, LocalDate(2027, 1, 31), "Aluguel", repeats = Repeats.MONTHLY), LocalDate(2027, 1, 20))
        val dates = db.planned(LocalDate(2027, 4, 25)).map { it.date }
        assertEquals(listOf(LocalDate(2027, 1, 31), LocalDate(2027, 2, 28), LocalDate(2027, 3, 31), LocalDate(2027, 4, 30)), dates)
    }

    @Test
    fun onceNeverRepeatsAndSkippedNeverComesBack() = runTest {
        val casa = casa()
        db.addManual(form(casa, 5_000, LocalDate(2026, 10, 20), "Presente"), oct9)
        db.addManual(form(casa, 8_990, LocalDate(2026, 10, 15), "Academia", repeats = Repeats.MONTHLY), oct9)
        val dec = LocalDate(2026, 12, 20)
        val before = db.planned(dec)
        assertEquals(1, before.count { it.name == "Presente" })
        val nov = before.single { it.name == "Academia" && it.date == LocalDate(2026, 11, 15) }
        assertTrue(db.skipOccurrence(nov.occurrence))
        assertFalse(db.skipOccurrence(nov.occurrence))
        repeat(3) { assertTrue(db.planned(dec).none { it.occurrence == nov.occurrence }) }
        assertEquals(listOf(LocalDate(2026, 10, 15), LocalDate(2026, 12, 15)), db.planned(dec).filter { it.name == "Academia" }.map { it.date })
    }

    @Test
    fun editingThePrevistoMovesOpenOccurrencesOnly() = runTest {
        val casa = casa()
        val saved = db.addManual(form(casa, 8_990, LocalDate(2026, 10, 5), "Academia", repeats = Repeats.MONTHLY), LocalDate(2026, 10, 1))
        val p = db.planned(oct9).single()
        // Lançar sem recado closes October.
        assertNotEquals(0L, db.addManual(form(casa, 8_990, p.date, caption = p.name).copy(occurrence = p.occurrence), oct9).transaction.v)
        val edited = db.addManual(form(casa, 9_990, LocalDate(2026, 10, 7), "Smart Fit", repeats = Repeats.MONTHLY).copy(planned = saved.planned), oct9)
        assertEquals(Saved(planned = saved.planned), edited)
        val nov = db.planned(LocalDate(2026, 10, 30)).single()
        assertEquals(LocalDate(2026, 11, 7), nov.date)
        assertEquals("Smart Fit", nov.name)
        assertEquals(Cents(9_990), nov.cents)
        assertEquals(LocalDate(2026, 10, 5), db.transactions().single().date)
    }

    @Test
    fun aRefusedFormWritesNothing() = runTest {
        val casa = casa()
        val n = recado("VIVO FIBRA", "119,90", oct9)
        // The previsto's name would be the 81-character alias: the whole form is refused (D64).
        val long = "a".repeat(81)
        assertEquals(TransactionId(0), db.accept(n, form(casa, 11_990, oct9, "VIVO FIBRA", repeats = Repeats.MONTHLY).copy(alias = long)))
        assertEquals(emptyList(), db.transactions())
        assertEquals(emptyList(), db.planned(oct9))
        assertEquals(n, db.pending().single().id)
        assertEquals(Saved(), db.addManual(form(casa, 1_000, LocalDate(2026, 10, 20), long), oct9))
        assertEquals(Saved(), db.addManual(form(casa, 1_000, oct9).copy(occurrence = OccurrenceId(999))))
        assertEquals(emptyList(), db.transactions())
    }

    @Test
    fun anEmptyBankTextNeverLinks() = runTest {
        val casa = casa()
        db.addManual(form(casa, 18_000, LocalDate(2026, 10, 12), "Conta de luz"), oct9)
        val p = db.planned(oct9).single()
        db.addSource(NU, "Nubank", 0)
        val at = LocalDateTime(oct9, LocalTime(10, 0)).toInstant(tz).toEpochMilliseconds()
        capture(db, RawNotice(packageName = NU, appLabel = "Nubank", key = "pix", postedAt = at, whenMs = at, text = "Pix de R$ 180,00 da conta 1 foi realizado com sucesso."))
        val n = db.pending(oct9).single()
        assertEquals("", n.parsed.merchant)
        assertNotEquals(0L, db.accept(n.id, launchForm(p, n)).v)
        // R2: the occurrence closes, no link is written.
        assertEquals(emptyList(), db.planned(oct9))
    }

    @Test
    fun deletesFollowBudgetsAndTags() = runTest {
        val casa = casa()
        val lazer = db.createBudget("Lazer", Cents(40_000), oct9)
        db.addManual(form(casa, 18_000, LocalDate(2026, 10, 12), "Luz"), oct9)
        db.addManual(form(lazer, 8_990, LocalDate(2026, 10, 15), "Academia").copy(tags = listOf("academia", "contas")), oct9)
        db.deleteBudget(casa)
        assertEquals(listOf("Academia"), db.planned(oct9).map { it.name })

        val tags = db.tags().associate { it.name to it.id }
        db.mergeTags(tags.getValue("contas"), tags.getValue("academia"))
        assertEquals(listOf("academia"), db.planned(oct9).single().tags)
        db.deleteTag(tags.getValue("academia"))
        assertEquals(emptyList(), db.planned(oct9))
    }

    @Test
    fun deletingAPrevistoKeepsItsLancamentos() = runTest {
        val casa = casa()
        val saved = db.addManual(form(casa, 8_990, LocalDate(2026, 10, 5), "Academia", repeats = Repeats.MONTHLY), LocalDate(2026, 10, 1))
        val p = db.planned(oct9).single()
        val t = db.addManual(form(casa, 8_990, p.date, caption = p.name).copy(occurrence = p.occurrence), oct9).transaction
        db.deletePlanned(saved.planned)
        assertEquals(emptyList(), db.planned(LocalDate(2026, 12, 1)))
        assertEquals(t, db.transactions().single().id)
        db.ensureMonths(YearMonth(2026, 10))
        assertEquals(Cents(8_990), db.budgetDetail(casa, YearMonth(2026, 10), oct9).month.spent)
    }

    @Test
    fun anAliasAppliesAcrossAccents() = runTest {
        val casa = casa()
        db.setAlias("CAFÉ DO PONTO", "Café")
        db.addManual(form(casa, 1_000, oct9, "cafe do ponto"))
        assertEquals("Café", db.transactions().single().alias)
        assertEquals(casa, db.suggestion("CAFE  DO PONTO").budget)
        // Two merchants with aliases differing only by accents are one ranking row (D50, D61).
        db.setAlias("PADARIA ZE", "Padaria do Zé")
        db.setAlias("PAG*JOSE", "padaria do ze")
        db.addManual(form(casa, 2_000, oct9, "PADARIA ZE"))
        db.addManual(form(casa, 3_000, oct9, "PAG*JOSE"))
        val rows = db.merchantRanking(oct9, oct9)
        assertEquals(Cents(5_000), rows.single { fold(it.name) == "padaria do ze" }.total)
    }

    @Test
    fun foldIsIdempotentAndNormalizationBlind() {
        for (s in listOf("Ação ÉLÉVÉ", "STRASSE Straße", "İstanbul ılık", "áè", "Ẑ ẞ")) assertEquals(fold(s), fold(fold(s)), s)
        assertEquals(fold("é"), fold("é"))
        assertEquals("loja estacao", fold("LOJA ESTAÇÃO"))
        assertEquals(MerchantKey.of("vivo fibra"), MerchantKey.of(" VÍVO  FIBRA "))
    }

    @Test
    fun aV3DatabaseMigratesToV4() = runTest {
        val casa = casa()
        db.addManual(form(casa, 1_000, oct9, "LOJA"))
        db.setAlias("LOJA", "Loja")
        db.close()
        BundledSQLiteDriver().open(path).let {
            it.execSQL("DROP TABLE planned")
            it.execSQL("DROP TABLE planned_tags")
            it.execSQL("DROP TABLE planned_dates")
            it.execSQL("DROP INDEX transactions_occurrence")
            it.execSQL("ALTER TABLE transactions DROP COLUMN occurrence_id")
            it.execSQL("ALTER TABLE merchant_aliases DROP COLUMN alias_key")
            it.execSQL("PRAGMA user_version = 3")
            it.close()
        }
        db = Db(BundledSQLiteDriver(), path, timeZone = tz)
        assertEquals("Loja", db.transactions().single().alias)
        assertEquals(emptyList(), db.planned(oct9))
        db.close()
        db = Db(BundledSQLiteDriver(), path, timeZone = tz)
        assertEquals(1, db.transactions().size)
    }

    private companion object {
        const val NU = "com.nu.production"
    }
}
