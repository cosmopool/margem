package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Edge cases of the previsto (spec §2.8, §2.12, §2.13, §7.2–§7.4; architecture D59–D64, §5), through the
 * public API only: the ±10-day recognition window, days 29–31, ONCE, the creation of occurrences up to
 * today + 10 (R1), closed and skipped months, editing the day, the late occurrence beside the next one,
 * the tie-break between previstos, the empty key (R2), the key fold, "Lançar previsto" through accept,
 * Desfazer / delete (S1), "Data à frente" through addManual(form, today), the refusals, previstos never
 * counting, deleteBudget / deleteTag / mergeTags / deletePlanned and the refusal of a backup older than the one schema.
 *
 * Expected values are computed by hand from the spec and the KDoc. Day arithmetic used below (2026 unless
 * said; September and November have 30 days, October and December 31, February 2027 has 28, 2028 is leap):
 * - distance(24 set, 5 out) = 6 + 5 = 11 · (25 set, 5 out) = 5 + 5 = 10 · (28 set, 5 out) = 2 + 5 = 7 ·
 *   (5 out, 15 out) = 10 · (5 out, 16 out) = 11.
 * - distance(28 set, 5 out) = 7 · (28 set, 8 out) = 2 + 8 = 10 · (28 set, 9 out) = 11 · (17 out, 28 out) = 11 ·
 *   (18 out, 28 out) = 10.
 * - today + 10 (R1): 9 out → 19 out · 16 out → 26 out · 25 out → 4 nov · 26 out → 5 nov · 29 out → 8 nov ·
 *   30 out → 9 nov · 2 nov → 12 nov · 26 nov → 6 dez · 31 dez → 10 jan 2027 · 10 mar 2027 → 20 mar 2027.
 *
 * Notices are Nubank purchases ("Compra de R$ … APROVADA em <bank text> para o cartão …"), whose text has no
 * date or time: the recado's date and time are its `when` read in America/Sao_Paulo.
 */
class PrevistoEdgeTest {

    private var seq = 0

    private fun withDb(block: suspend (Db) -> Unit): TestResult = runTest(timeout = 120.seconds) {
        val path = Path(SystemTemporaryDirectory, "PrevistoEdge-${Random.nextLong().toULong()}.db").toString()
        val db = Db(BundledSQLiteDriver(), path, timeZone = TZ)
        try {
            assertTrue(db.addSource(NU, "Nubank", 0))
            assertTrue(db.addSource(AURORA, "Aurora", 0))
            block(db)
        } finally {
            db.close()
            for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
        }
    }

    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate(y, m, day)

    /** Captures a notice of [pkg] dated [on] at [at] (São Paulo) and returns its id. */
    private suspend fun Db.post(on: LocalDate, text: String, at: LocalTime = NOON, pkg: String = NU): NoticeId {
        val n = ++seq
        val ms = on.atTime(at).toInstant(TZ).toEpochMilliseconds() + n
        val key = "previsto-edge|$n"
        val label = if (pkg == NU) "Nubank" else "Aurora"
        assertTrue(capture(this, RawNotice(packageName = pkg, appLabel = label, key = key, postedAt = ms, whenMs = ms, title = label, text = text, bigText = text)), "capture $n")
        return pending().single { it.raw.key == key }.id
    }

    /** A Nubank purchase: bank text [merchant], value [cents]. */
    private suspend fun Db.purchase(on: LocalDate, merchant: String, cents: String = "99,90", at: LocalTime = NOON) =
        post(on, "Compra de R$ $cents APROVADA em $merchant para o cartão com final 1234.", at)

    /** A Nubank Pix: no bank text is read (merchant ""), so its key is empty (R2). */
    private suspend fun Db.pix(on: LocalDate, cents: String = "120,00") =
        post(on, "Pix de R$ $cents da conta 1234-5 foi realizado com sucesso.")

    private suspend fun Db.budget(name: String): BudgetId =
        createBudget(name, Cents(300000), TODAY).also { assertNotEquals(0L, it.v, "createBudget $name") }

    /**
     * A "Todo mês" previsto linked to the Nubank bank text [merchant] (D64): the recado of [from] launched
     * with MONTHLY and caption [name], so the previsto is named [name], has the recado's value and the day of
     * [from], and its first occurrence is in the calendar month after [from]'s.
     */
    private suspend fun Db.linkedMonthly(
        from: LocalDate, merchant: String, name: String, budget: BudgetId, cents: String = "99,90", tags: List<String> = listOf("casa"),
    ): TransactionId {
        val id = purchase(from, merchant, cents)
        val p = notice(id).parsed
        val tx = accept(id, TransactionForm(budget, tags, p.cents, 1, p.merchant, p.date, p.time, caption = name, repeats = Repeats.MONTHLY))
        assertNotEquals(TransactionId(0), tx, "accept MONTHLY $name")
        return tx
    }

    /** A manual form: name in the caption (the manual has one field, spec §7.3), one installment at noon. */
    private fun manual(
        budget: BudgetId, date: LocalDate, name: String, cents: Long = 15000, repeats: Repeats = Repeats.ONCE,
        tags: List<String> = listOf("casa"), merchant: String = "", occurrence: OccurrenceId = OccurrenceId(0),
    ) = TransactionForm(
        budget = budget, tags = tags, total = Cents(cents), installments = 1, merchant = merchant, date = date, time = NOON,
        caption = name, occurrence = occurrence, repeats = repeats,
    )

    /**
     * "Lançar previsto" (D62's screen function): budget and tags of the previsto, caption = its name, value,
     * bank text, date and time of the recado, and the occurrence.
     */
    private suspend fun Db.launchForm(id: NoticeId, p: Planned): TransactionForm {
        val r = notice(id).parsed
        return TransactionForm(
            budget = p.budget, tags = p.tags, total = r.cents, installments = 1, merchant = r.merchant, date = r.date, time = r.time,
            caption = p.name, occurrence = p.occurrence,
        )
    }

    /** Posts a purchase of [merchant] on [on] (Nubank unless [pkg]), returns what pending(today) recognizes it as, then risca it. */
    private suspend fun Db.seen(on: LocalDate, merchant: String, today: LocalDate, at: LocalTime = NOON, pkg: String = NU): Planned {
        val id = if (pkg == NU) purchase(on, merchant, at = at) else post(on, "Compra de R$ 99,90 em $merchant.", at, pkg)
        val p = pending(today).single { it.id == id }.planned
        assertTrue(dismiss(id), "riscar")
        return p
    }

    private suspend fun Db.only(today: LocalDate, name: String): Planned = planned(today).single { it.name == name }

    /** Everything a refused write must leave as it was. [today] is read first so the occurrence creation is done. */
    private suspend fun Db.snapshot(today: LocalDate): List<Any> {
        planned(today)
        return listOf(version.value, transactions(), planned(today), tags(), pending().map { it.id })
    }

    // ---- Janela de 10 dias (spec §2.13 Reconhecer, D60) ----

    @Test
    fun `janela de 10 dias reconhece com 10 e não com 11 dos dois lados e atravessa a virada`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val today = d(10, 16) // creates up to 26 out: only 5 out (5 nov > 26 out)
        val occ = db.planned(today).single()
        assertEquals(d(10, 5), occ.date)

        // 24 set 23:30 in São Paulo (25 set in UTC): 11 days before 5 out by the local date.
        assertEquals(Planned(), db.seen(d(9, 24), "VIVO FIBRA", today, at = LocalTime(23, 30)), "24 set: 11 dias antes")
        assertEquals(occ.occurrence, db.seen(d(9, 25), "VIVO FIBRA", today).occurrence, "25 set: 10 dias antes")
        assertEquals(occ.occurrence, db.seen(d(9, 28), "VIVO FIBRA", today).occurrence, "28 set: 7 dias antes, outro mês")
        // 15 out 22:00 in São Paulo (16 out in UTC): 10 days after by the local date.
        assertEquals(occ.occurrence, db.seen(d(10, 15), "VIVO FIBRA", today, at = LocalTime(22, 0)).occurrence, "15 out: 10 dias depois")
        assertEquals(Planned(), db.seen(d(10, 16), "VIVO FIBRA", today), "16 out: 11 dias depois")
    }

    @Test
    fun `janela atravessa a virada para trás - previsto do dia 28 e recado de outubro`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(8, 28), "SMARTFIT", "Academia", casa) // first occurrence 28 set
        val today = d(10, 18) // creates up to 28 out: 28 set and 28 out
        val (sep, oct) = db.planned(today)
        assertEquals(d(9, 28) to d(10, 28), sep.date to oct.date)

        assertEquals(sep.occurrence, db.seen(d(10, 5), "SMARTFIT", today).occurrence, "5 out: 7 dias depois de 28 set")
        assertEquals(sep.occurrence, db.seen(d(10, 8), "SMARTFIT", today).occurrence, "8 out: 10 dias depois de 28 set")
        assertEquals(Planned(), db.seen(d(10, 9), "SMARTFIT", today), "9 out: 11 de 28 set e 19 de 28 out")
        assertEquals(Planned(), db.seen(d(10, 17), "SMARTFIT", today), "17 out: 19 de 28 set e 11 de 28 out")
        assertEquals(oct.occurrence, db.seen(d(10, 18), "SMARTFIT", today).occurrence, "18 out: 10 dias antes de 28 out")
    }

    @Test
    fun `fevereiro sem dia 31 conta a janela a partir do dia 28`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(1, 31, 2027), "ALUGUEL IMOB", "Aluguel", casa) // day 31, first occurrence February 2027
        val today = d(3, 10, 2027) // creates up to 20 mar 2027: 28 fev only (31 mar > 20 mar)
        val feb = db.planned(today).single()
        assertEquals(d(2, 28, 2027), feb.date, "P8: Feb 2027 has no 31, its last day")
        assertEquals(31, feb.day)
        assertEquals(d(3, 31, 2027), feb.next)

        assertEquals(feb.occurrence, db.seen(d(2, 18, 2027), "ALUGUEL IMOB", today).occurrence, "18 fev: 10 antes de 28 fev")
        assertEquals(Planned(), db.seen(d(2, 17, 2027), "ALUGUEL IMOB", today), "17 fev: 11 antes")
        assertEquals(feb.occurrence, db.seen(d(3, 10, 2027), "ALUGUEL IMOB", today).occurrence, "10 mar: 10 depois de 28 fev")
        assertEquals(Planned(), db.seen(d(3, 11, 2027), "ALUGUEL IMOB", today), "11 mar: 11 depois de 28 fev, 20 antes de 31 mar")
    }

    // ---- Dias 29–31 (spec §2.13 Repete, P8) ----

    @Test
    fun `dia 31 cai no último dia de cada mês curto e de fevereiro`() = withDb { db ->
        val casa = db.budget("Casa")
        val s = db.addManual(manual(casa, d(10, 31), "Aluguel", repeats = Repeats.MONTHLY), today = d(10, 25))
        assertEquals(TransactionId(0), s.transaction, "Data à frente: no lançamento")
        assertNotEquals(PlannedId(0), s.planned)

        // (today, expected date, expected next): each today is ≤ 10 days before the date (R1).
        val months = listOf(
            Triple(d(10, 25), d(10, 31), d(11, 30)),
            Triple(d(11, 20), d(11, 30), d(12, 31)),
            Triple(d(12, 21), d(12, 31), d(1, 31, 2027)),
            Triple(d(1, 21, 2027), d(1, 31, 2027), d(2, 28, 2027)),
            Triple(d(2, 18, 2027), d(2, 28, 2027), d(3, 31, 2027)),
            Triple(d(3, 21, 2027), d(3, 31, 2027), d(4, 30, 2027)),
        )
        for ((today, date, next) in months) {
            val p = db.planned(today).single()
            assertEquals(s.planned, p.planned, "$date")
            assertEquals(31, p.day, "$date")
            assertEquals(date to next, p.date to p.next, "on $today")
            assertTrue(db.skipOccurrence(p.occurrence), "pular $date")
        }
    }

    @Test
    fun `dias 29 e 30 em fevereiro comum e bissexto`() = withDb { db ->
        val casa = db.budget("Casa")
        val d30 = db.addManual(manual(casa, d(1, 30, 2027), "Trinta", repeats = Repeats.MONTHLY), today = d(1, 25, 2027)).planned
        assertNotEquals(PlannedId(0), d30)
        val p30 = db.planned(d(1, 25, 2027)).single { it.planned == d30 }
        assertEquals(d(1, 30, 2027) to d(2, 28, 2027), p30.date to p30.next, "30 jan → 28 fev 2027")

        val d29 = db.addManual(manual(casa, d(1, 29, 2028), "Vinte e nove", repeats = Repeats.MONTHLY), today = d(1, 20, 2028)).planned
        assertNotEquals(PlannedId(0), d29)
        val jan = db.planned(d(1, 20, 2028)).single { it.planned == d29 }
        assertEquals(d(1, 29, 2028) to d(2, 29, 2028), jan.date to jan.next, "2028 is leap: 29 fev exists")
        assertTrue(db.skipOccurrence(jan.occurrence))
        val feb = db.planned(d(2, 19, 2028)).single { it.planned == d29 }
        assertEquals(d(2, 29, 2028) to d(3, 29, 2028), feb.date to feb.next)
        assertEquals(29, feb.day)
    }

    // ---- Só uma vez (spec §2.13 Repete) ----

    @Test
    fun `só uma vez nunca ganha segunda ocorrência nem lançada nem esperando`() = withDb { db ->
        val casa = db.budget("Casa")
        val waiting = db.addManual(manual(casa, d(10, 15), "IPVA"), today = TODAY).planned
        val first = db.planned(TODAY).single { it.planned == waiting }
        assertEquals(Repeats.ONCE, first.repeats)
        assertEquals(d(10, 15) to NO_DATE, first.date to first.next, "ONCE: next is NO_DATE")
        // Waiting past its day for months: still one occurrence, late.
        for (today in listOf(d(11, 9), d(12, 9), d(1, 9, 2027), d(10, 9, 2027))) {
            val all = db.planned(today).filter { it.planned == waiting }
            assertEquals(listOf(first.occurrence), all.map { it.occurrence }, "on $today")
            assertTrue(all.single().late, "on $today")
        }

        // Linked and launched: nothing ever comes after it, and the next year's recado is a plain one.
        val iptu = db.addManual(manual(casa, d(10, 20), "IPTU"), today = TODAY).planned
        val occ = db.planned(d(10, 20)).single { it.planned == iptu }
        val r = db.purchase(d(10, 20), "PREFEITURA SP", "310,00")
        assertNotEquals(TransactionId(0), db.accept(r, db.launchForm(r, occ)))
        for (today in listOf(d(10, 21), d(11, 20), d(10, 15, 2027))) {
            assertTrue(db.planned(today).none { it.planned == iptu }, "on $today")
        }
        assertEquals(Planned(), db.seen(d(11, 20), "PREFEITURA SP", d(11, 20)))

        // Skipped: gone for good.
        assertTrue(db.skipOccurrence(first.occurrence))
        assertTrue(db.planned(d(10, 9, 2027)).isEmpty())
    }

    @Test
    fun `mudar para só uma vez para a repetição sem tirar a ocorrência em aberto`() = withDb { db ->
        val casa = db.budget("Casa")
        val pid = db.addManual(manual(casa, d(10, 12), "Revista", repeats = Repeats.MONTHLY), today = TODAY).planned
        val open = db.planned(d(10, 14)).single() // 12 out; 12 nov > 24 out is not created yet
        assertEquals(d(11, 12), open.next)

        val s = db.addManual(manual(casa, d(10, 12), "Revista").copy(planned = pid), today = d(10, 14))
        assertEquals(Saved(planned = pid), s, "Editar previsto: Saved(planned)")
        val once = db.planned(d(10, 14)).single()
        assertEquals(open.occurrence, once.occurrence)
        assertEquals(Repeats.ONCE to NO_DATE, once.repeats to once.next)
        assertEquals(listOf(open.occurrence), db.planned(d(12, 20)).map { it.occurrence }, "no 12 nov nor 12 dez")
    }

    // ---- R1: ocorrências até hoje + 10, só com hoje (D59, D60) ----

    @Test
    fun `R1 a ocorrência de 5 nov existe em 26 out e não em 25 out e só uma leitura com hoje a cria`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val r = db.purchase(d(10, 26), "VIVO FIBRA") // 21 days from 5 out, 10 before 5 nov

        assertEquals(Planned(), db.pending().single { it.id == r }.planned, "NO_DATE creates nothing: 5 nov doesn't exist")
        assertEquals(listOf(d(10, 5)), db.planned(d(10, 25)).map { it.date }, "25 out + 10 = 4 nov")
        assertEquals(Planned(), db.pending(d(10, 25)).single { it.id == r }.planned)
        assertEquals(Planned(), db.pending().single { it.id == r }.planned)

        val nov = db.pending(d(10, 26)).single { it.id == r }.planned
        assertEquals(d(11, 5), nov.date, "26 out + 10 = 5 nov: created and recognized")
        assertEquals(listOf(d(10, 5), d(11, 5)), db.planned(d(10, 26)).map { it.date })
        val again = db.pending().single { it.id == r }.planned
        assertEquals(nov.occurrence, again.occurrence, "NO_DATE still recognizes the occurrences that exist")
        assertFalse(again.late)
    }

    @Test
    fun `R1 hoje mais 10 inclui o décimo dia para o todo mês lançado hoje`() = withDb { db ->
        val casa = db.budget("Casa")
        val s = db.addManual(manual(casa, TODAY, "Academia", repeats = Repeats.MONTHLY), today = TODAY)
        assertNotEquals(TransactionId(0), s.transaction)
        assertNotEquals(PlannedId(0), s.planned)
        assertTrue(db.planned(TODAY).none { it.planned == s.planned }, "first occurrence is 9 nov, not October")
        assertTrue(db.planned(d(10, 29)).none { it.planned == s.planned }, "29 out + 10 = 8 nov")
        assertEquals(listOf(d(11, 9)), db.planned(d(10, 30)).filter { it.planned == s.planned }.map { it.date }, "30 out + 10 = 9 nov")
    }

    // ---- Mês lançado ou pulado não volta ----

    @Test
    fun `mês lançado ou pulado não volta depois de muitas leituras`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val r = db.purchase(d(10, 5), "VIVO FIBRA")
        val oct = db.pending(TODAY).single { it.id == r }.planned
        assertEquals(d(10, 5), oct.date)
        assertNotEquals(TransactionId(0), db.accept(r, db.launchForm(r, oct)))
        val nov = db.planned(d(11, 1)).single()
        assertEquals(d(11, 5), nov.date)
        assertTrue(db.skipOccurrence(nov.occurrence))

        var t = TODAY
        while (t <= d(12, 31)) {
            val dates = db.planned(t).map { it.date }
            assertTrue(d(10, 5) !in dates && d(11, 5) !in dates, "on $t: $dates")
            db.pending(t)
            t = t.plus(1, DateTimeUnit.DAY)
        }
        // 31 dez + 10 = 10 jan 2027: 5 dez (open, late) and 5 jan; read back on an earlier today too.
        assertEquals(listOf(d(12, 5), d(1, 5, 2027)), db.planned(d(12, 31)).map { it.date })
        // Triage 2026-10-22 (user's decision, architecture D59): a read with today shows only what is due by today + 10.
        assertEquals(emptyList(), db.planned(TODAY).map { it.date })
        assertEquals(Planned(), db.seen(d(11, 6), "VIVO FIBRA", d(12, 31)), "5 nov skipped; 5 dez is 29 days away")
    }

    // ---- Editar o dia (D59, D64) ----

    @Test
    fun `editar o dia move só as ocorrências em aberto`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val r = db.purchase(d(10, 5), "VIVO FIBRA")
        val oct = db.pending(TODAY).single { it.id == r }.planned
        val tx = db.accept(r, db.launchForm(r, oct))
        assertNotEquals(TransactionId(0), tx)
        val nov = db.planned(d(11, 1)).single()
        assertTrue(db.skipOccurrence(nov.occurrence))
        val dec = db.planned(d(11, 26)).single()
        assertEquals(d(12, 5), dec.date)

        val edit = TransactionForm(
            budget = casa, tags = listOf("casa"), total = Cents(9990), merchant = "", date = d(12, 20), time = NOON,
            caption = "Internet", repeats = Repeats.MONTHLY, planned = dec.planned,
        )
        assertEquals(Saved(planned = dec.planned), db.addManual(edit, today = d(11, 26)))

        // Triage 2026-10-22 (user's decision, D59): 20 dez shows from 10 dez on, not on 26 nov.
        assertEquals(emptyList(), db.planned(d(11, 26)))
        val moved = db.planned(d(12, 10)).single()
        assertEquals(dec.occurrence, moved.occurrence, "the same open occurrence")
        assertEquals(20, moved.day)
        assertEquals(d(12, 20) to d(1, 20, 2027), moved.date to moved.next)
        assertEquals(d(10, 5), db.transaction(tx).row.date, "the launched month keeps its lançamento as it was")
        // 31 dez + 10 = 10 jan: 20 jan isn't created yet; 10 jan + 10 = 20 jan: it is.
        assertEquals(listOf(d(12, 20)), db.planned(d(12, 31)).map { it.date })
        assertEquals(listOf(d(12, 20), d(1, 20, 2027)), db.planned(d(1, 10, 2027)).map { it.date })
    }

    @Test
    fun `editar o dia tira ou põe em atraso a ocorrência em aberto`() = withDb { db ->
        val casa = db.budget("Casa")
        val pid = db.addManual(manual(casa, d(10, 12), "Luz", repeats = Repeats.MONTHLY), today = TODAY).planned
        val late = db.planned(d(10, 14)).single()
        assertEquals(d(10, 12) to true, late.date to late.late)

        assertEquals(Saved(planned = pid), db.addManual(manual(casa, d(10, 20), "Luz", repeats = Repeats.MONTHLY).copy(planned = pid), today = d(10, 14)))
        val later = db.planned(d(10, 14)).single()
        assertEquals(Triple(late.occurrence, d(10, 20), false), Triple(later.occurrence, later.date, later.late))

        assertEquals(Saved(planned = pid), db.addManual(manual(casa, d(10, 3), "Luz", repeats = Repeats.MONTHLY).copy(planned = pid), today = d(10, 14)))
        val early = db.planned(d(10, 14)).single()
        assertEquals(Triple(late.occurrence, d(10, 3), true), Triple(early.occurrence, early.date, early.late))
    }

    // ---- Atrasado e o do mês seguinte (P10) ----

    @Test
    fun `atrasado e o do mês seguinte coexistem e o recado de 2 nov fica com novembro`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val both = db.planned(d(10, 26))
        assertEquals(listOf(d(10, 5) to true, d(11, 5) to false), both.map { it.date to it.late }, "oldest first")
        val (oct, nov) = both

        val r = db.purchase(d(11, 2), "VIVO FIBRA") // 28 days from 5 out, 3 from 5 nov
        assertEquals(nov.occurrence, db.pending(d(11, 2)).single { it.id == r }.planned.occurrence)
        assertEquals(listOf(false, true), db.planned(d(11, 2)).map { it.noticeInPauta })
        assertTrue(db.dismiss(r))

        val r2 = db.purchase(d(10, 12), "VIVO FIBRA") // 7 days from 5 out, 24 from 5 nov
        val withToday = db.pending(d(11, 2)).single { it.id == r2 }.planned
        assertEquals(oct.occurrence to true, withToday.occurrence to withToday.late)
        val noToday = db.pending().single { it.id == r2 }.planned
        assertEquals(oct.occurrence to false, noToday.occurrence to noToday.late, "without today, late is false")
        assertEquals(listOf(true, false), db.planned(d(11, 2)).map { it.noticeInPauta })
    }

    @Test
    fun `atrasado só depois do dia`() = withDb { db ->
        val casa = db.budget("Casa")
        db.addManual(manual(casa, d(10, 12), "Luz"), today = TODAY)
        assertFalse(db.planned(d(10, 12)).single().late, "on its day it isn't late")
        assertTrue(db.planned(d(10, 13)).single().late, "the day after it is")
    }

    // ---- Dois previstos para o mesmo recado (D60) ----

    @Test
    fun `dois previstos ficam com o dia mais próximo e o empate vai à data e depois ao id`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet A", casa)
        db.linkedMonthly(d(9, 15), "VIVO FIBRA", "Internet B", casa)
        val today = d(10, 16)
        val a = db.only(today, "Internet A")
        val b = db.only(today, "Internet B")
        assertEquals(d(10, 5) to d(10, 15), a.date to b.date)

        assertEquals(a.occurrence, db.seen(d(10, 9), "VIVO FIBRA", today).occurrence, "9 out: 4 × 6")
        assertEquals(b.occurrence, db.seen(d(10, 11), "VIVO FIBRA", today).occurrence, "11 out: 6 × 4")
        assertEquals(a.occurrence, db.seen(d(10, 10), "VIVO FIBRA", today).occurrence, "10 out: 5 × 5, the earlier date")
        assertEquals(b.occurrence, db.seen(d(10, 20), "VIVO FIBRA", today).occurrence, "20 out: 15 × 5")

        // Same day, same distance: the lower occurrence id.
        db.linkedMonthly(d(9, 20), "NET CLARO", "Net C", casa)
        db.linkedMonthly(d(9, 20), "NET CLARO", "Net D", casa)
        val tied = db.planned(d(10, 22)).filter { it.date == d(10, 20) }
        assertEquals(2, tied.size)
        val lower = tied.minBy { it.occurrence.v }.occurrence
        assertEquals(lower, db.seen(d(10, 22), "NET CLARO", d(10, 22)).occurrence)
        assertEquals(lower, db.seen(d(10, 18), "NET CLARO", d(10, 22)).occurrence)
    }

    // ---- R2: texto do banco vazio ----

    @Test
    fun `texto do banco vazio nunca liga nem é reconhecido`() = withDb { db ->
        val casa = db.budget("Casa")
        val pid = db.addManual(manual(casa, d(10, 15), "Diarista", cents = 12000, repeats = Repeats.MONTHLY), today = TODAY).planned
        val oct = db.planned(TODAY).single()
        assertFalse(oct.linked)

        // Launched by hand from a Pix: closes the occurrence, writes no link (R2).
        val p1 = db.pix(d(10, 15))
        assertNotEquals(TransactionId(0), db.accept(p1, db.launchForm(p1, oct)))
        val nov = db.planned(d(11, 5)).single()
        assertEquals(Triple(pid, false, ""), Triple(nov.planned, nov.linked, nov.linkText))

        // The next Pix, a day from 15 nov, is a plain recado.
        val p2 = db.pix(d(11, 14))
        assertEquals(Planned(), db.pending(d(11, 14)).single { it.id == p2 }.planned)
        assertFalse(db.planned(d(11, 14)).single().noticeInPauta)

        // "Todo mês" from a Pix: a previsto with no link.
        val p2p = db.notice(p2).parsed
        assertNotEquals(TransactionId(0), db.accept(p2, TransactionForm(casa, listOf("casa"), p2p.cents, 1, p2p.merchant, p2p.date, p2p.time, caption = "Pix mensal", repeats = Repeats.MONTHLY)))
        val q = db.only(d(12, 14), "Pix mensal")
        assertEquals(Triple(d(12, 14), false, ""), Triple(q.date, q.linked, q.linkText))

        // Neither a Pix nor an ad (no value, no bank text) of the same fonte is recognized next to them.
        val p3 = db.pix(d(12, 14))
        assertEquals(Planned(), db.pending(d(12, 14)).single { it.id == p3 }.planned)
        val ad = db.post(d(12, 14), "Seu limite aumentou! Aproveite.")
        assertEquals(Planned(), db.pending(d(12, 14)).single { it.id == ad }.planned)
        assertTrue(db.planned(d(12, 14)).none { it.noticeInPauta })

        assertEquals("", MerchantKey.of("").v)
        assertEquals("", MerchantKey.of(" \t  　").v, "only spaces: an empty key")
    }

    @Test
    fun `recado sem texto do banco lançado como previsto ligado mantém a ligação`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val oct = db.planned(TODAY).single()
        val p = db.pix(d(10, 6))
        assertNotEquals(TransactionId(0), db.accept(p, db.launchForm(p, oct)))

        val nov = db.planned(d(10, 26)).single()
        assertEquals(Triple(true, "VIVO FIBRA", "Nubank"), Triple(nov.linked, nov.linkText, nov.linkSource), "an empty key writes no link")
        assertEquals(nov.occurrence, db.seen(d(11, 4), "VIVO FIBRA", d(11, 4)).occurrence)
    }

    // ---- Chave: acentos, caixa e espaços (D61) ----

    @Test
    fun `chave ignora acentos caixa e espaços mas não a fonte nem outra letra`() = withDb { db ->
        assertEquals("vivofibra", MerchantKey.of("VIVO  FIBRA").v)
        assertEquals(MerchantKey.of("VIVO  FIBRA"), MerchantKey.of("vivo fibra"))
        assertEquals(MerchantKey.of("vivo fibra"), MerchantKey.of("VÍVO FIBRA"))
        assertEquals(MerchantKey.of("VÍVO FIBRA"), MerchantKey.of("VÍVO FIBRA"), "NFC and NFD")
        assertEquals(MerchantKey.of("vivo fibra"), MerchantKey.of("Vivo  Fibra　"))
        assertEquals("vivo fibra", fold("VÍVO FIBRA"), "fold keeps the spaces")
        assertEquals("strasse", MerchantKey.of("STRAßE").v)

        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val today = TODAY
        val occ = db.planned(today).single().occurrence
        for (text in listOf("vivo fibra", "VIVO  FIBRA", "VÍVO FIBRA", "VÍVO FIBRA", "VIVOFIBRA", "Vivo Fibra", "VIVO\tFIBRA")) {
            assertEquals(occ, db.seen(d(10, 6), text, today).occurrence, text)
        }
        assertEquals(Planned(), db.seen(d(10, 6), "VIVO FIBRAS", today))
        assertEquals(Planned(), db.seen(d(10, 6), "VIVA FIBRA", today))
        assertEquals(Planned(), db.seen(d(10, 6), "VIVO FIBRA", today, pkg = AURORA), "another fonte")

        // Linked from an accented bank text: the raw text is shown, the plain one matches.
        db.linkedMonthly(d(9, 7), "CAFÉ DO PONTO", "Café", casa)
        val cafe = db.only(today, "Café")
        assertEquals("CAFÉ DO PONTO", cafe.linkText)
        assertEquals(cafe.occurrence, db.seen(d(10, 8), "cafe do ponto", today).occurrence)
    }

    // ---- Lançar previsto via accept (D62, P7) ----

    @Test
    fun `lançar previsto grava a legenda e valor data e hora do recado sem emenda`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa, tags = listOf("internet", "casa"))
        val r = db.purchase(d(10, 6), "VIVO FIBRA", "104,90", at = LocalTime(7, 42))
        val r2 = db.purchase(d(10, 7), "VIVO FIBRA", "104,90")
        val p = db.pending(TODAY).single { it.id == r }.planned
        assertEquals(
            Planned(
                occurrence = p.occurrence, planned = p.planned, name = "Internet", budget = casa, budgetName = "Casa",
                tags = listOf("casa", "internet"), cents = Cents(9990), day = 5, repeats = Repeats.MONTHLY, date = d(10, 5),
                next = d(11, 5), linked = true, linkText = "VIVO FIBRA", linkSource = "Nubank", late = true, noticeInPauta = true,
            ),
            p,
        )

        val tx = db.accept(r, db.launchForm(r, p))
        assertNotEquals(TransactionId(0), tx)
        val row = db.transaction(tx).row
        assertEquals("Internet" to "VIVO FIBRA", row.caption to row.merchant)
        assertEquals(Cents(10490), row.total, "P7: the recado's value")
        assertEquals(d(10, 6) to LocalTime(7, 42), row.date to row.time)
        assertEquals(casa to listOf("casa", "internet"), row.budget to row.tags)
        assertFalse(row.corrected, "P7: not an emenda")
        assertEquals(r, row.notice)
        assertEquals(NoticeStatus.PROCESSED, db.notice(r).status)

        assertTrue(db.planned(TODAY).none { it.occurrence == p.occurrence }, "closed")
        assertEquals(Planned(), db.pending(TODAY).single { it.id == r2 }.planned, "the other recado finds no open occurrence")
        val nov = db.planned(d(10, 26)).single()
        assertEquals(d(11, 5) to Cents(9990), nov.date to nov.cents, "P7: the next month keeps the previsto's value")
        db.ensureMonths(YearMonth(2026, 10))
        assertEquals(Cents(10490), db.budgetDetail(casa, YearMonth(2026, 10), TODAY).month.spent)
    }

    @Test
    fun `ligar à mão reescreve a ligação pelo texto do banco do recado mesmo com o estabelecimento emendado`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val r = db.purchase(d(10, 5), "NET CLARO")
        assertEquals(Planned(), db.pending(TODAY).single { it.id == r }.planned, "another bank text: not recognized")
        val oct = db.planned(TODAY).single()
        assertNotEquals(TransactionId(0), db.accept(r, db.launchForm(r, oct).copy(merchant = "INTERNET DE CASA")))

        val nov = db.planned(d(10, 26)).single()
        assertEquals(Triple(true, "NET CLARO", "Nubank"), Triple(nov.linked, nov.linkText, nov.linkSource))
        val today = d(11, 4)
        assertEquals(nov.occurrence, db.seen(d(11, 4), "NET CLARO", today).occurrence)
        assertEquals(Planned(), db.seen(d(11, 4), "VIVO FIBRA", today), "the old link was overwritten")
        assertEquals(Planned(), db.seen(d(11, 4), "INTERNET DE CASA", today), "never the amended text")
        assertEquals(Planned(), db.seen(d(11, 4), "NET CLARO", today, pkg = AURORA), "the fonte is part of the link")

        // First link of a previsto made by Data à frente.
        val pid = db.addManual(manual(casa, d(11, 20), "Luz", repeats = Repeats.MONTHLY), today = today).planned
        val luz = db.planned(d(11, 10)).single { it.planned == pid }
        assertFalse(luz.linked)
        val r2 = db.purchase(d(11, 18), "ENEL SP", "187,50")
        assertNotEquals(TransactionId(0), db.accept(r2, db.launchForm(r2, luz)))
        val dec = db.planned(d(12, 10)).single { it.planned == pid }
        assertEquals(d(12, 20) to "ENEL SP", dec.date to dec.linkText)
        assertEquals(dec.occurrence, db.seen(d(12, 19), "enel sp", d(12, 10)).occurrence)
    }

    @Test
    fun `todo mês no accept e na edição liga pelo recado e não pelo formulário`() = withDb { db ->
        val casa = db.budget("Casa")
        val r = db.purchase(d(10, 12), "SMARTFIT", "119,90")
        val p = db.notice(r).parsed
        val tx = db.accept(r, TransactionForm(casa, listOf("academia"), p.cents, 1, "ACADEMIA DO BAIRRO", p.date, p.time, caption = "Academia", repeats = Repeats.MONTHLY))
        assertNotEquals(TransactionId(0), tx)
        val a = db.only(d(11, 2), "Academia") // 2 nov + 10 = 12 nov
        assertEquals(Triple(d(11, 12), "SMARTFIT", "Nubank"), Triple(a.date, a.linkText, a.linkSource))
        assertEquals(a.occurrence, db.seen(d(11, 10), "SMARTFIT", d(11, 2)).occurrence)
        assertEquals(Planned(), db.seen(d(11, 10), "ACADEMIA DO BAIRRO", d(11, 2)))

        val r2 = db.purchase(d(10, 13), "PADARIA REAL", "23,40")
        val p2 = db.notice(r2).parsed
        val tx2 = db.accept(r2, TransactionForm(casa, listOf("pão"), p2.cents, 1, p2.merchant, p2.date, p2.time))
        assertNotEquals(TransactionId(0), tx2)
        assertTrue(db.edit(tx2, TransactionForm(casa, listOf("pão"), p2.cents, 1, "PADOCA", p2.date, p2.time, caption = "Pão", repeats = Repeats.MONTHLY)))
        val pao = db.only(d(11, 3), "Pão")
        assertEquals(Triple(d(11, 13), true, "PADARIA REAL"), Triple(pao.date, pao.linked, pao.linkText))

        // A manual lançamento edited to "Todo mês": a previsto without a link.
        val tx3 = db.addManual(manual(casa, d(10, 8), "Feira"), today = TODAY).transaction
        assertNotEquals(TransactionId(0), tx3)
        assertTrue(db.edit(tx3, manual(casa, d(10, 8), "Feira", repeats = Repeats.MONTHLY)))
        val feira = db.only(d(10, 29), "Feira")
        assertEquals(Triple(d(11, 8), false, ""), Triple(feira.date, feira.linked, feira.linkText))
    }

    // ---- Ocorrência fora de aberto (D62, spec §7.3) ----

    @Test
    fun `ocorrência que não está mais em aberto recusa sem gravar nada`() = withDb { db ->
        val casa = db.budget("Casa")
        db.addManual(manual(casa, d(10, 15), "Luz", repeats = Repeats.MONTHLY), today = TODAY)
        db.addManual(manual(casa, d(10, 16), "Água"), today = TODAY)
        val luz = db.only(TODAY, "Luz")
        val agua = db.only(TODAY, "Água")
        val today = d(10, 20)

        // Skipped.
        assertTrue(db.skipOccurrence(luz.occurrence))
        val r = db.purchase(d(10, 15), "ENEL SP", "187,50")
        var before = db.snapshot(today)
        assertEquals(TransactionId(0), db.accept(r, db.launchForm(r, luz)), "pulada")
        assertEquals(Saved(), db.addManual(manual(casa, d(10, 15), "Luz", occurrence = luz.occurrence), today = today), "pulada, manual")
        assertEquals(before, db.snapshot(today))
        assertFalse(db.only(d(11, 5), "Luz").linked, "a refused accept writes no link")

        // Closed by another lançamento while the screen was open.
        val a = db.purchase(d(10, 16), "SABESP", "80,00")
        val b = db.purchase(d(10, 16), "SABESP", "81,00")
        val aForm = db.launchForm(a, agua)
        val bForm = db.launchForm(b, agua)
        assertNotEquals(TransactionId(0), db.accept(a, aForm))
        before = db.snapshot(today)
        assertEquals(TransactionId(0), db.accept(b, bForm), "fechada por outro lançamento")
        assertEquals(Saved(), db.addManual(manual(casa, d(10, 16), "Água", occurrence = agua.occurrence), today = today))
        assertEquals(TransactionId(0), db.accept(b, bForm.copy(occurrence = OccurrenceId(987654))), "unknown occurrence")
        assertEquals(before, db.snapshot(today))
        assertEquals(NoticeStatus.PENDING, db.notice(b).status)
    }

    // ---- Desfazer / apagar (S1) ----

    @Test
    fun `apagar o lançamento do previsto devolve a ocorrência em aberto e ligada`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val r = db.purchase(d(10, 5), "VIVO FIBRA")
        val p = db.pending(TODAY).single { it.id == r }.planned
        val tx = db.accept(r, db.launchForm(r, p))
        assertNotEquals(TransactionId(0), tx)
        assertTrue(db.planned(TODAY).isEmpty())

        db.deleteTransaction(tx)
        assertEquals(NoticeStatus.PENDING, db.notice(r).status)
        val back = db.pending(TODAY).single { it.id == r }.planned
        assertEquals(p.occurrence, back.occurrence, "the same occurrence, recognized again")
        val open = db.planned(TODAY).single()
        assertEquals(Triple(p.occurrence, true, true), Triple(open.occurrence, open.linked, open.noticeInPauta))
        // After the month turn it is the late one beside next month's.
        assertEquals(listOf(d(10, 5) to true, d(11, 5) to false), db.planned(d(10, 26)).map { it.date to it.late })
        assertNotEquals(TransactionId(0), db.accept(r, db.launchForm(r, back)), "launch it again")
    }

    @Test
    fun `apagar reabre também a ocorrência ligada à mão e a fechada sem recado`() = withDb { db ->
        val casa = db.budget("Casa")
        // Linked by hand the first time (no link before): after delete, open and linked (spec S1).
        val pid = db.addManual(manual(casa, d(10, 15), "Luz", repeats = Repeats.MONTHLY), today = TODAY).planned
        val luz = db.planned(TODAY).single { it.planned == pid }
        val r = db.purchase(d(10, 14), "ENEL SP", "187,50")
        val tx = db.accept(r, db.launchForm(r, luz))
        assertNotEquals(TransactionId(0), tx)
        db.deleteTransaction(tx)
        val again = db.planned(TODAY).single { it.planned == pid }
        assertEquals(Triple(luz.occurrence, true, "ENEL SP"), Triple(again.occurrence, again.linked, again.linkText))
        assertEquals(luz.occurrence, db.pending(TODAY).single { it.id == r }.planned.occurrence)

        // Lançar sem recado (R6, R6b): manual, closes without a link; delete reopens.
        val gas = db.addManual(manual(casa, d(10, 12), "Gás"), today = TODAY).planned
        val g = db.planned(TODAY).single { it.planned == gas }
        val s = db.addManual(manual(casa, d(10, 12), "Gás", occurrence = g.occurrence), today = d(10, 12))
        assertEquals(PlannedId(0), s.planned)
        assertNotEquals(TransactionId(0), s.transaction)
        assertEquals(NoticeId(0) to "Gás", db.transaction(s.transaction).row.let { it.notice to it.caption })
        assertTrue(db.planned(d(10, 12)).none { it.planned == gas })
        db.deleteTransaction(s.transaction)
        val reopened = db.planned(d(10, 12)).single { it.planned == gas }
        assertEquals(g.occurrence to false, reopened.occurrence to reopened.linked)
    }

    @Test
    fun `editar o lançamento não muda a origem`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        db.addManual(manual(casa, d(10, 18), "Água"), today = TODAY)
        val r = db.purchase(d(10, 5), "VIVO FIBRA")
        val p = db.pending(TODAY).single { it.id == r }.planned
        val agua = db.only(TODAY, "Água")
        val form = db.launchForm(r, p)
        val tx = db.accept(r, form)
        assertNotEquals(TransactionId(0), tx)

        // D65 (2026-10-22): the edit form opens "Repete" as its previsto is (Todo mês here); Só uma vez would delete it.
        assertEquals(Repeats.MONTHLY, db.transaction(tx).repeats)
        assertTrue(db.edit(tx, form.copy(total = Cents(11000), occurrence = OccurrenceId(0), repeats = Repeats.MONTHLY)))
        assertTrue(db.planned(TODAY).none { it.occurrence == p.occurrence }, "still closed by the edited lançamento")
        assertTrue(db.edit(tx, form.copy(occurrence = agua.occurrence, repeats = Repeats.MONTHLY)))
        assertTrue(db.planned(TODAY).any { it.occurrence == agua.occurrence }, "edit never closes another occurrence")
        assertTrue(db.planned(TODAY).none { it.occurrence == p.occurrence })

        db.deleteTransaction(tx)
        assertTrue(db.planned(TODAY).any { it.occurrence == p.occurrence }, "the origin was kept: delete reopens it")
    }

    // ---- Data à frente (spec §7.3, D64) ----

    @Test
    fun `data à frente grava só o previsto e hoje ou passado com todo mês grava os dois`() = withDb { db ->
        val casa = db.budget("Casa")
        val ahead = db.addManual(manual(casa, d(10, 10), "  Conta\nde luz ", cents = 18750, tags = listOf("Luz", "casa")), today = TODAY)
        assertEquals(TransactionId(0), ahead.transaction)
        assertNotEquals(PlannedId(0), ahead.planned)
        assertTrue(db.transactions().isEmpty(), "no lançamento")
        val p = db.planned(TODAY).single()
        assertEquals(
            Planned(
                occurrence = p.occurrence, planned = ahead.planned, name = "Conta de luz", budget = casa, budgetName = "Casa",
                tags = listOf("casa", "luz"), cents = Cents(18750), day = 10, repeats = Repeats.ONCE, date = d(10, 10), next = NO_DATE,
            ),
            p,
        )

        // Name from the merchant when there is no caption.
        val enel = db.addManual(manual(casa, d(10, 12), "", merchant = "ENEL"), today = TODAY)
        assertEquals(TransactionId(0), enel.transaction)
        assertEquals("ENEL", db.planned(TODAY).single { it.planned == enel.planned }.name)

        // Data à frente with MONTHLY: first occurrence on that date.
        val net = db.addManual(manual(casa, d(10, 17), "Internet", repeats = Repeats.MONTHLY), today = TODAY)
        val n = db.planned(TODAY).single { it.planned == net.planned }
        assertEquals(d(10, 17) to d(11, 17), n.date to n.next)

        // Today and the past are lançamentos; with MONTHLY also a previsto from next month.
        val now = db.addManual(manual(casa, TODAY, "Mercado"), today = TODAY)
        assertNotEquals(TransactionId(0), now.transaction)
        assertEquals(PlannedId(0), now.planned, "date = today is not ahead")
        val past = db.addManual(manual(casa, d(10, 1), "Escola", repeats = Repeats.MONTHLY), today = TODAY)
        assertNotEquals(TransactionId(0), past.transaction)
        assertNotEquals(PlannedId(0), past.planned)
        assertEquals(listOf(d(11, 1)), db.planned(d(10, 22)).filter { it.planned == past.planned }.map { it.date })

        // NO_DATE: no date is ahead.
        val noToday = db.addManual(manual(casa, d(12, 25), "Natal"))
        assertNotEquals(TransactionId(0), noToday.transaction)
        assertEquals(PlannedId(0), noToday.planned)
        assertTrue(db.planned(d(12, 20)).none { it.name == "Natal" })

        assertEquals(listOf("Escola", "Mercado", "Natal").sorted(), db.transactions().map { it.caption }.sorted())
    }

    // ---- Desfazer desfaz o que o lançamento fez (spec §2.13 S1, D65; 2026-10-23) ----

    @Test
    fun `desfazer o todo mês leva o previsto que ele criou e lançar de novo cria um só`() = withDb { db ->
        val casa = db.budget("Casa")
        val tx = db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        assertEquals(listOf("Internet"), db.planned(TODAY).map { it.name })

        db.deleteTransaction(tx)
        assertTrue(db.planned(d(10, 26)).isEmpty(), "nothing of it was launched: the previsto goes with the lançamento")
        val again = db.pending(TODAY).single()
        assertEquals(Planned(), again.planned)
        val p = again.parsed
        db.accept(again.id, TransactionForm(casa, listOf("casa"), p.cents, 1, p.merchant, p.date, p.time, caption = "Internet", repeats = Repeats.MONTHLY))
        assertEquals(1, db.planned(d(10, 26)).map { it.planned }.distinct().size, "one previsto, not two")
    }

    @Test
    fun `apagar o todo mês mantém o previsto que já tem lançamento`() = withDb { db ->
        val casa = db.budget("Casa")
        val tx = db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val r = db.purchase(d(10, 5), "VIVO FIBRA")
        assertNotEquals(TransactionId(0), db.accept(r, db.launchForm(r, db.pending(TODAY).single { it.id == r }.planned)))

        db.deleteTransaction(tx)
        assertEquals(listOf(d(11, 5)), db.planned(d(10, 26)).map { it.date }, "October was launched: the previsto stays")
    }

    @Test
    fun `desfazer um ligar à mão volta a ligação anterior`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val oct = db.only(TODAY, "Internet")
        val r = db.purchase(d(10, 6), "VIVO INTERNET")
        assertEquals(Planned(), db.pending(TODAY).single { it.id == r }.planned, "another bank text: not recognized")
        val tx = db.accept(r, db.launchForm(r, oct))
        assertEquals("VIVO INTERNET", db.planned(d(10, 26)).single().linkText, "ligar à mão rewrites the link (D62)")

        db.deleteTransaction(tx)
        assertEquals("VIVO FIBRA", db.only(TODAY, "Internet").linkText)
        assertEquals(Planned(), db.pending(TODAY).single { it.id == r }.planned)
        assertEquals("Internet", db.seen(d(10, 7), "VIVO FIBRA", TODAY).name, "the old bank text is recognized again")
    }

    // ---- Todo mês antigo e Só uma vez (spec §2.13, D64; 2026-10-23) ----

    @Test
    fun `todo mês num lançamento antigo cria todos os meses desde o seguinte`() = withDb { db ->
        // Intentional (review Q4): the months already gone come late; the person skips what they don't want.
        val casa = db.budget("Casa")
        db.addManual(manual(casa, d(3, 10), "Escola", repeats = Repeats.MONTHLY), today = TODAY)
        val rows = db.planned(TODAY)
        assertEquals((4..10).map { d(it, 10) }, rows.map { it.date })
        assertTrue(rows.all { it.late == it.date < TODAY })
    }

    @Test
    fun `editar previsto para só uma vez deixa só a ocorrência editada`() = withDb { db ->
        val casa = db.budget("Casa")
        val today = d(10, 30)
        val pid = db.addManual(manual(casa, d(10, 5), "Internet", repeats = Repeats.MONTHLY), today = d(10, 1)).planned
        val (oct, nov) = db.planned(today)
        assertEquals(d(10, 5) to d(11, 5), oct.date to nov.date)

        val s = db.addManual(manual(casa, d(11, 5), "Internet", occurrence = nov.occurrence).copy(planned = pid), today = today)
        assertEquals(Saved(planned = pid), s)
        assertEquals(listOf(nov.occurrence), db.planned(today).map { it.occurrence }, "the edited one stays, October goes")
        assertEquals(listOf(nov.occurrence), db.planned(d(12, 20)).map { it.occurrence }, "no December")

        // No occurrence given: the oldest open one stays.
        val luz = db.addManual(manual(casa, d(10, 7), "Luz", repeats = Repeats.MONTHLY), today = d(10, 1)).planned
        val (luzOct, _) = db.planned(today).filter { it.planned == luz }
        assertEquals(Saved(planned = luz), db.addManual(manual(casa, d(10, 7), "Luz").copy(planned = luz), today = today))
        assertEquals(listOf(luzOct.occurrence), db.planned(today).filter { it.planned == luz }.map { it.occurrence })
    }

    // ---- Estorno (spec §2.8, D60) ----

    @Test
    fun `estorno com o texto do banco ligado não é reconhecido e a compra do mesmo dia é`() = withDb { db ->
        val lazer = db.budget("Lazer")
        db.linkedMonthly(d(9, 5), "SMARTFIT", "Academia", lazer)
        val estorno = db.post(d(10, 6), "A compra em SMARTFIT no valor de R$ 99,90 foi estornada no seu cartão.")
        assertTrue(db.notice(estorno).parsed.isRefund, "the recado is an estorno")

        // §2.8: an estorno's button is "Registrar estorno", never "Lançar previsto".
        assertEquals(Planned(), db.pending(TODAY).single { it.id == estorno }.planned)
        assertFalse(db.only(TODAY, "Academia").noticeInPauta, "an estorno is not the previsto's recado")

        val compra = db.purchase(d(10, 6), "SMARTFIT")
        assertEquals("Academia", db.pending(TODAY).single { it.id == compra }.planned.name, "the purchase still is")
        assertEquals(Planned(), db.pending(TODAY).single { it.id == estorno }.planned)
        assertTrue(db.only(TODAY, "Academia").noticeInPauta)
    }

    // ---- Leituras da Pauta (D6, D60) ----

    @Test
    fun `marcar a pauta como vista não muda version`() = withDb { db ->
        // Opening the Pauta marks it seen; a bump would re-run pending(today) at once (D6: screens re-query on change).
        db.purchase(d(10, 6), "PADARIA")
        db.pending(TODAY)
        val before = db.version.value
        db.markPautaSeen(d(10, 9).atTime(NOON).toInstant(TZ).toEpochMilliseconds())
        assertEquals(before, db.version.value)
        assertFalse(db.pending(TODAY).single().isNew, "the visit is still recorded")
    }

    // ---- Recusas (D64) ----

    @Test
    fun `data à frente com ocorrência ligada é recusada sem gravar nada`() = withDb { db ->
        // Lançar sem recado or "É um previsto? Ligar" with a date after today: "linked" and "Salvar previsto" at once.
        val casa = db.budget("Casa")
        db.addManual(manual(casa, d(10, 5), "Internet", repeats = Repeats.MONTHLY), today = d(10, 1))
        val open = db.only(TODAY, "Internet")
        assertTrue(open.late)
        val before = db.snapshot(TODAY)
        for (repeats in Repeats.entries) {
            val form = manual(casa, d(10, 12), "Internet", repeats = repeats, occurrence = open.occurrence)
            assertEquals(Saved(), db.addManual(form, today = TODAY), "$repeats")
            assertEquals(before, db.snapshot(TODAY), "$repeats: no second previsto, the occurrence stays open")
        }
    }

    @Test
    fun `recusas de previsto não gravam nada`() = withDb { db ->
        val casa = db.budget("Casa")
        val ok = manual(casa, d(10, 15), "Luz", tags = listOf("nova"))
        val refused = listOf(
            "sem marcador" to ok.copy(tags = emptyList()),
            "marcadores em branco" to ok.copy(tags = listOf("  ", "")),
            "verba que não existe" to ok.copy(budget = BudgetId(9999)),
            "valor zero" to ok.copy(total = Cents(0)),
            "legenda de 81" to ok.copy(caption = "a".repeat(81)),
            "legenda que limpa em 81" to ok.copy(caption = "a".repeat(78) + "\nxy"),
            "nome do banco de 81" to ok.copy(caption = "", merchant = "m".repeat(81)),
            "todo mês hoje com nome de 81" to ok.copy(caption = "", merchant = "m".repeat(81), date = TODAY, repeats = Repeats.MONTHLY),
            "todo mês no passado sem marcador" to ok.copy(tags = emptyList(), date = d(10, 1), repeats = Repeats.MONTHLY),
        )
        val before = db.snapshot(TODAY)
        for ((why, form) in refused) {
            assertEquals(Saved(), db.addManual(form, today = TODAY), why)
            assertEquals(before, db.snapshot(TODAY), why)
        }
        assertTrue(db.tags().none { it.name == "nova" }, "a refused form creates no tag")

        // The edges that pass: 80 after cleaning, from the caption or the merchant.
        val a80 = db.addManual(ok.copy(caption = "  " + "a".repeat(80) + " \n"), today = TODAY).planned
        assertEquals("a".repeat(80), db.planned(TODAY).single { it.planned == a80 }.name)
        val m80 = db.addManual(ok.copy(caption = "", merchant = "m".repeat(80)), today = TODAY).planned
        assertEquals("m".repeat(80), db.planned(TODAY).single { it.planned == m80 }.name)

        // Editar previsto: unknown previsto, or an invalid rule, leave everything as it was.
        val kept = db.snapshot(TODAY)
        assertEquals(Saved(), db.addManual(ok.copy(planned = PlannedId(987654)), today = TODAY), "unknown previsto")
        assertEquals(Saved(), db.addManual(ok.copy(tags = emptyList(), planned = a80), today = TODAY))
        assertEquals(Saved(), db.addManual(ok.copy(caption = "b".repeat(81), planned = a80), today = TODAY))
        assertEquals(Saved(), db.addManual(ok.copy(budget = BudgetId(9999), planned = a80), today = TODAY))
        assertEquals(kept, db.snapshot(TODAY))
    }

    // ---- Previsto não conta (spec §2.13, architecture §5) ----

    @Test
    fun `previsto nunca conta em gasto histórico rankings e gasto por dia`() = withDb { db ->
        val casa = db.budget("Casa")
        db.addManual(manual(casa, d(10, 15), "Aluguel", cents = 250000, tags = listOf("aluguel"), merchant = "IMOBILIARIA"), today = TODAY)
        val s = db.addManual(manual(casa, d(10, 5), "", cents = 3000, tags = listOf("feira"), merchant = "FEIRA", repeats = Repeats.MONTHLY), today = TODAY)
        assertNotEquals(TransactionId(0), s.transaction)
        assertEquals(listOf(d(10, 15), d(11, 5)), db.planned(d(11, 9)).map { it.date }, "both previstos have occurrences")

        val oct = YearMonth(2026, 10)
        val nov = YearMonth(2026, 11)
        db.ensureMonths(nov)
        assertEquals(Cents(3000), db.budgetDetail(casa, oct, TODAY).month.spent)
        assertEquals(listOf(s.transaction), db.budgetDetail(casa, oct, TODAY).lines.map { it.row.id })
        assertEquals(Cents(3000), db.budgetsOverview(oct, TODAY, BudgetOrder.RISK).single().month.spent)
        assertEquals(Cents(0), db.budgetDetail(casa, nov, d(11, 9)).month.spent)
        assertEquals(listOf(Cents(3000), Cents(0)), db.history(oct, nov).map { it.spent })
        assertEquals(listOf(RankRow("feira", Cents(3000), 1)), db.tagRanking(d(10, 1), d(11, 30)))
        assertEquals(listOf(RankRow("FEIRA", Cents(3000), 1)), db.merchantRanking(d(10, 1), d(11, 30)))
        assertEquals(mapOf(d(10, 5) to Cents(3000)), db.spentByDay(d(10, 1), d(11, 30)))
        assertEquals(1, db.transactions().size)
    }

    // ---- Apagar verba, marcador, juntar, apagar previsto, pular ----

    @Test
    fun `apagar verba apaga os previstos dela e os recados voltam sem reconhecimento`() = withDb { db ->
        val casa = db.budget("Casa")
        val lazer = db.budget("Lazer")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        db.addManual(manual(lazer, d(10, 20), "Cinema", tags = listOf("lazer")), today = TODAY)
        val r = db.purchase(d(10, 5), "VIVO FIBRA")
        val p = db.pending(TODAY).single { it.id == r }.planned
        assertNotEquals(TransactionId(0), db.accept(r, db.launchForm(r, p)))
        val r2 = db.purchase(d(10, 6), "VIVO FIBRA")

        db.deleteBudget(casa)
        // Triage 2026-10-22 (user's decision, D59): Cinema (20 out) is due from 10 out on.
        assertEquals(listOf("Cinema"), db.planned(d(10, 10)).map { it.name })
        assertEquals(listOf("Cinema"), db.planned(d(10, 26)).map { it.name }, "5 nov never comes")
        val pend = db.pending(d(10, 26))
        assertEquals(3, pend.size, "the Sept lançamento's recado, the launched one and the plain one")
        assertTrue(r in pend.map { it.id } && r2 in pend.map { it.id })
        assertTrue(pend.all { it.planned == Planned() })
        assertTrue(db.transactions().isEmpty())
    }

    @Test
    fun `apagar marcador apaga o previsto sem marcador e juntar move os marcadores do previsto`() = withDb { db ->
        val casa = db.budget("Casa")
        db.addManual(manual(casa, d(10, 15), "Internet", tags = listOf("internet")), today = TODAY)
        db.addManual(manual(casa, d(10, 16), "Luz", tags = listOf("internet", "casa")), today = TODAY)
        db.addManual(manual(casa, d(10, 17), "Gás", tags = listOf("gas")), today = TODAY)
        db.addManual(manual(casa, d(10, 18), "Água", tags = listOf("agua", "gas")), today = TODAY)
        fun id(rows: List<TagRow>, name: String) = rows.single { it.name == name }.id

        db.deleteTag(id(db.tags(), "internet"))
        assertEquals(
            listOf("Luz" to listOf("casa"), "Gás" to listOf("gas"), "Água" to listOf("agua", "gas")),
            db.planned(TODAY).map { it.name to it.tags },
        )

        val tags = db.tags()
        assertTrue(db.mergeTags(id(tags, "gas"), id(tags, "agua")))
        assertEquals(
            listOf("Luz" to listOf("casa"), "Gás" to listOf("agua"), "Água" to listOf("agua")),
            db.planned(TODAY).map { it.name to it.tags },
            "moved, without a repeated pair",
        )
        val gas = db.only(d(10, 17), "Gás")
        val s = db.addManual(manual(casa, d(10, 17), "Gás", tags = gas.tags, occurrence = gas.occurrence), today = d(10, 17))
        assertEquals(listOf("agua"), db.transaction(s.transaction).row.tags)

        db.deleteTag(id(db.tags(), "agua"))
        assertEquals(listOf("Luz"), db.planned(d(10, 17)).map { it.name }, "Água had only agua")
        assertTrue(db.transactions().isEmpty(), "the lançamento with only agua went too")
    }

    @Test
    fun `apagar previsto deixa os lançamentos contando e nada reabre`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val r = db.purchase(d(10, 5), "VIVO FIBRA", "104,90")
        val p = db.pending(TODAY).single { it.id == r }.planned
        val tx = db.accept(r, db.launchForm(r, p))
        assertNotEquals(TransactionId(0), tx)
        val nov = db.planned(d(10, 26)).single()
        val row = db.transaction(tx).row

        var before = db.snapshot(d(10, 26))
        db.deletePlanned(PlannedId(987654))
        assertEquals(before, db.snapshot(d(10, 26)), "unknown: nothing changes")

        db.deletePlanned(nov.planned)
        assertTrue(db.planned(d(10, 26)).isEmpty())
        assertEquals(row, db.transaction(tx).row, "the lançamento stays as it was")
        db.ensureMonths(YearMonth(2026, 10))
        assertEquals(Cents(10490), db.budgetDetail(casa, YearMonth(2026, 10), TODAY).month.spent)
        assertFalse(db.skipOccurrence(nov.occurrence))
        assertEquals(Planned(), db.seen(d(11, 4), "VIVO FIBRA", d(11, 4)))

        db.deleteTransaction(tx)
        assertEquals(Planned(), db.pending(d(11, 4)).single { it.id == r }.planned)
        assertTrue(db.planned(d(11, 4)).isEmpty(), "no origin left to reopen")
        before = db.snapshot(d(11, 4))
        db.deletePlanned(nov.planned)
        assertEquals(before, db.snapshot(d(11, 4)), "deleting again changes nothing")
    }

    @Test
    fun `pular este mês some com a ocorrência e o mês seguinte continua`() = withDb { db ->
        val casa = db.budget("Casa")
        db.linkedMonthly(d(9, 5), "VIVO FIBRA", "Internet", casa)
        val (oct, nov) = db.planned(d(10, 26))
        assertTrue(db.skipOccurrence(oct.occurrence))
        assertFalse(db.skipOccurrence(oct.occurrence), "not open anymore")
        assertEquals(listOf(nov.occurrence), db.planned(d(10, 26)).map { it.occurrence })
        assertEquals(Planned(), db.seen(d(10, 7), "VIVO FIBRA", d(10, 26)), "5 out skipped, 5 nov is 29 days away")

        val r = db.purchase(d(11, 4), "VIVO FIBRA")
        val p = db.pending(d(11, 4)).single { it.id == r }.planned
        assertEquals(nov.occurrence, p.occurrence)
        assertNotEquals(TransactionId(0), db.accept(r, db.launchForm(r, p)))
        assertFalse(db.skipOccurrence(nov.occurrence), "closed by a lançamento")
        assertFalse(db.skipOccurrence(OccurrenceId(987654)), "unknown")
        assertEquals(listOf(d(12, 5)), db.planned(d(11, 26)).map { it.date })
    }

    // ---- Schema único (D67) ----

    @Test
    fun `backup de um banco anterior ao schema único é recusado`() = runTest(timeout = 120.seconds) {
        // D67 (2026-10-23): one schema, no migrations of the old versions; the real backup is converted once outside the app.
        val path = Path(SystemTemporaryDirectory, "PrevistoEdge-${Random.nextLong().toULong()}.db").toString()
        val old = Path(SystemTemporaryDirectory, "PrevistoEdge-v3-${Random.nextLong().toULong()}.db").toString()
        writeV3(old)
        val db = Db(BundledSQLiteDriver(), path, timeZone = TZ)
        try {
            assertFalse(db.importFrom(old))
            assertTrue(db.pending().isEmpty() && db.transactions().isEmpty(), "nothing imported")
        } finally {
            db.close()
            for (f in listOf(path, old)) for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(f + suffix), mustExist = false)
        }
    }

    /** A Margem database from before the one schema (D67): v3, one lançamento with a legenda, one pending recado, a teto. */
    private fun writeV3(file: String) {
        val c = BundledSQLiteDriver().open(file)
        for (ddl in V3_DDL) c.execSQL(ddl)
        val t = d(10, 6).atTime(LocalTime(11, 43)).toInstant(TZ).toEpochMilliseconds()
        c.exec("INSERT INTO settings(key, value) VALUES ('month_start_day', '1')")
        c.exec("INSERT INTO sources(package, label, added_at) VALUES (?, ?, ?)", NU, "Nubank", 0L)
        for ((id, text) in listOf(1L to V3_TEXT_1, 2L to V3_TEXT_2)) {
            c.exec(
                "INSERT INTO notices(id, package, app_label, key, posted_at, when_ms, title, text, big_text, sub_text, text_lines, " +
                    "category, channel_id, extras_json, content_hash, dismissed) VALUES (?, ?, 'Nubank', ?, ?, ?, 'Nubank', ?, '', '', '', '', '', '{}', 'old', 0)",
                id, NU, "previsto-v3|$id", t + id * 60_000L, t + id * 60_000L, text,
            )
        }
        c.exec("INSERT INTO budgets(id, name, position) VALUES (1, 'Mercado', 0)")
        c.exec("INSERT INTO budget_months(budget_id, month, limit_cents) VALUES (1, '2026-10', 60000)")
        c.exec("INSERT INTO ceiling_months(month, cents) VALUES ('2026-10', 410000)")
        c.exec(
            "INSERT INTO transactions(id, notice_id, budget_id, merchant, merchant_key, date, time, total_cents, corrected, caption) " +
                "VALUES (1, 1, 1, 'PADARIA DO ZE', 'padariadoze', '2026-10-06', '12:00', 3150, 0, 'Bolo da Ana')",
        )
        c.exec("INSERT INTO installments(transaction_id, k, date, cents) VALUES (1, 1, '2026-10-06', 3150)")
        c.exec("INSERT INTO tags(id, name) VALUES (1, 'feira')")
        c.exec("INSERT INTO transaction_tags(transaction_id, tag_id) VALUES (1, 1)")
        c.exec("INSERT INTO merchant_aliases(merchant_key, alias) VALUES ('padariadoze', 'Padaria do Zé')")
        c.execSQL("PRAGMA user_version = 3")
        c.close()
    }

    private companion object {
        const val NU = "com.nu.production"
        const val AURORA = "br.com.aurora"
        val TZ = TimeZone.of("America/Sao_Paulo")
        /** The book's sexta, 9 out 2026. */
        val TODAY = LocalDate(2026, 10, 9)
        val NOON = LocalTime(12, 0)
        const val V3_TEXT_1 = "Compra de R$ 31,50 APROVADA em PADARIA DO ZE para o cartão com final 1234."
        const val V3_TEXT_2 = "Compra de R$ 20,00 APROVADA em FEIRA DA ESQUINA para o cartão com final 1234."

        /** SCHEMA_V1 + V2 + V3 of Db.kt (copied): the database the previsto finds. */
        val V3_DDL = listOf(
            "CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE sources(package TEXT PRIMARY KEY, label TEXT NOT NULL, added_at INTEGER NOT NULL)",
            "CREATE TABLE notices(id INTEGER PRIMARY KEY, package TEXT NOT NULL, app_label TEXT NOT NULL, key TEXT NOT NULL, " +
                "posted_at INTEGER NOT NULL, when_ms INTEGER NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, big_text TEXT NOT NULL, " +
                "sub_text TEXT NOT NULL, text_lines TEXT NOT NULL, category TEXT NOT NULL, channel_id TEXT NOT NULL, " +
                "extras_json TEXT NOT NULL, content_hash TEXT NOT NULL, dismissed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE, position INTEGER NOT NULL)",
            "CREATE TABLE budget_months(budget_id INTEGER NOT NULL, month TEXT NOT NULL, limit_cents INTEGER NOT NULL, PRIMARY KEY(budget_id, month))",
            "CREATE TABLE week_steps(budget_id INTEGER NOT NULL, week_start TEXT NOT NULL, cents INTEGER NOT NULL, PRIMARY KEY(budget_id, week_start))",
            "CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, notice_id INTEGER NOT NULL, budget_id INTEGER NOT NULL, " +
                "merchant TEXT NOT NULL, merchant_key TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, " +
                "total_cents INTEGER NOT NULL, corrected INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE installments(transaction_id INTEGER NOT NULL, k INTEGER NOT NULL, date TEXT NOT NULL, cents INTEGER NOT NULL, " +
                "PRIMARY KEY(transaction_id, k))",
            "CREATE TABLE refunds(estorno_notice_id INTEGER PRIMARY KEY, transaction_id INTEGER NOT NULL, notice_id INTEGER NOT NULL)",
            "CREATE TABLE tags(id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE)",
            "CREATE TABLE transaction_tags(transaction_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(transaction_id, tag_id))",
            "CREATE TABLE merchant_aliases(merchant_key TEXT PRIMARY KEY, alias TEXT NOT NULL)",
            "CREATE INDEX notices_key ON notices(key, posted_at)",
            "CREATE INDEX notices_posted_at ON notices(posted_at)",
            "CREATE INDEX transactions_notice ON transactions(notice_id)",
            "CREATE INDEX transactions_budget ON transactions(budget_id)",
            "CREATE INDEX transactions_merchant ON transactions(merchant_key)",
            "CREATE INDEX installments_date ON installments(date)",
            "CREATE INDEX refunds_transaction ON refunds(transaction_id)",
            "CREATE INDEX refunds_notice ON refunds(notice_id)",
            "CREATE INDEX transaction_tags_tag ON transaction_tags(tag_id)",
            "CREATE TABLE ceiling_months(month TEXT PRIMARY KEY, cents INTEGER NOT NULL)",
            "ALTER TABLE transactions ADD COLUMN caption TEXT NOT NULL DEFAULT ''",
        )
    }
}
