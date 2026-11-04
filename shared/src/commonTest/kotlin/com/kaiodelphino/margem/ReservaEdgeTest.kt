package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Edge cases of the margem honesta (spec §2.13, §3, §4, §5, §6; margem-plan M1–M8; architecture D68–D72):
 * cota livre exactly 0, previstos above the cota, month start days 29–31 and short months, an occurrence on
 * the month's last day, the recado across the virada (D72, late and early, and on edit), the estorno and the
 * parcelas of a fixed lançamento, an ordinary lançamento beside the open previsto, skipped occurrences, the
 * reserve beyond today + 10 (D71) and "no previsto = the rules of before". `budgetMonth` as a pure contract
 * (D23), the rest through the public `Db`.
 *
 * Expected values are computed by hand from the spec and the KDoc. Calendar used below:
 * - Oct 2026 starts on a Thursday (9 out = Friday). Weeks from Monday, month start 1: 1–4 (4 days), 5–11,
 *   12–18, 19–25 (7 each), 26–31 (6 days); 31 days.
 * - Passos (§4): cota livre × days ÷ 31 rounded down, the last automatic week takes the rest (D11):
 *   300000 → 38709 · 67741 · 67741 · 67741 · 58068 (Σ of the first four 241932 + 58064 = 299996, +4);
 *   100000 → 12903 · 22580 · 22580 · 22580 · 19357 (99997, +3); 90000 → 11612 · 20322 ×3 · 17422 (89997, +3);
 *   39999 → 5161 · 9032 ×3 · 7742 (39994 + … = 39995 before the rest, +4).
 * - Ritmo ideal of 9 out (§5) = week 1 whole + week 2 × 5 ÷ 7 (5–9 out), rounded down:
 *   300000 → 38709 + 48386 = 87095 · 100000 → 12903 + 16128 = 29031 · 90000 → 11612 + 14515 = 26127.
 * - Month start 31 (§3: a missing day = the month's last day): budget month 2026-09 = 30 set..30 out,
 *   2026-10 = 31 out..29 nov; Jan 2027 = 31 jan..27 fev (28 days, 31 jan is a Sunday).
 * - Month start 29: 2026-12 = 29 dez..28 jan 2027, 2027-01 = 29 jan..27 fev, 2027-02 = 28 fev..28 mar.
 * - Occurrence dates (P8, D59): the previsto's day in its calendar month, the last day when the month lacks it.
 */
class ReservaEdgeTest {

    // ---- budgetMonth, pure (D23, D68) ----

    private fun bm(
        limit: Long,
        month: YearMonth = OCT,
        startDay: Int = 1,
        today: LocalDate = TODAY,
        edited: Map<LocalDate, Long> = emptyMap(),
        spent: Map<LocalDate, Long> = emptyMap(),
        fixed: Map<LocalDate, Long> = emptyMap(),
        open: Long = 0,
    ) = budgetMonth(
        month, startDay, DayOfWeek.MONDAY, today, Cents(limit),
        edited.mapValues { Cents(it.value) }, spent.mapValues { Cents(it.value) }, fixed.mapValues { Cents(it.value) }, Cents(open),
    )

    /** The money of a month: what the margem honesta changes, in one comparable value. */
    private data class M(val spent: Long, val fixed: Long, val open: Long, val free: Long, val variable: Long, val remaining: Long)

    private fun m(b: BudgetMonth) = M(b.spent.v, b.fixed.v, b.open.v, b.freeLimit.v, b.variable.v, b.remaining.v)

    private fun BudgetMonth.steps() = weeks.map { it.step.v }

    @Test
    fun `cota livre exatamente zero não tem passos nem ritmo nem projeção`() {
        // §3: aluguel R$ 3.000 open in a cota of R$ 3.000 → cota livre max(0, 300000 − 300000) = 0; margem 300000 − 0 − 300000 = 0.
        val b = bm(limit = 300000, open = 300000)
        assertEquals(M(0, 0, 300000, 0, 0, 0), m(b))
        assertEquals(Cents(300000), b.planned, "M2: open 300000 + fixed 0")
        assertEquals(listOf(0L, 0, 0, 0, 0), b.steps(), "§4: the passos share a cota livre of 0")
        assertEquals(listOf(0L, 0, 0, 0, 0), b.weeks.map { it.max.v }, "Week.max = cota livre 0 − no edit")
        assertEquals(Cents(0), b.ideal)
        assertEquals(List(31) { Cents(0) }, b.idealByDay, "D44, D68: last day = cota livre = 0")
        // §5: margem 0 is not < 0, gasto variável 0 ≤ ideal 0.
        assertEquals(PaceStatus.ON_PACE, b.status)
        assertEquals(Cents(0), b.projection, "§5: no projection with ideal 0")

        // One cent of gasto variável: margem 300000 − 1 − 300000 = −1 → Fora da margem; still no projection.
        val one = bm(limit = 300000, open = 300000, spent = mapOf(TODAY to 1L))
        assertEquals(M(1, 0, 300000, 0, 1, -1), m(one))
        assertEquals(PaceStatus.OVERRUN, one.status)
        assertEquals(Cents(0), one.projection)
    }

    @Test
    fun `previsto lançado pelo valor da cota deixa cota livre e gasto variável em zero`() {
        // D68: fixed = 300000, planned = 0 + 300000, cota livre max(0, 300000 − 300000) = 0, variável 300000 − 300000 = 0,
        // margem 300000 − 300000 − 0 = 0.
        val b = bm(limit = 300000, fixed = mapOf(d(10, 5) to 300000L), spent = mapOf(d(10, 5) to 300000L))
        assertEquals(M(300000, 300000, 0, 0, 0, 0), m(b))
        assertEquals(PaceStatus.ON_PACE, b.status, "margem 0 ≥ 0 and variável 0 ≤ ideal 0")
        assertEquals(listOf(0L, 0, 0, 0, 0), b.weeks.map { it.spent.v }, "Week.spent is the gasto variável: 5–11 out has only the fixed")
        assertEquals(Cents(0), b.projection)
    }

    @Test
    fun `previstos acima da cota dão cota livre zero sem projeção e fora da margem`() {
        // Lançado 150000 + open 200000 = planned 350000 > cota 300000 → cota livre 0.
        // Margem = 300000 − (150000 + 2000) − 200000 = −52000 → Fora da margem (spent + open > limit).
        val b = bm(
            limit = 300000, open = 200000,
            fixed = mapOf(d(10, 2) to 150000L),
            spent = mapOf(d(10, 2) to 150000L, d(10, 6) to 2000L),
            edited = mapOf(d(10, 12) to 1L), // D70: 1 > cota livre 0 → ignored
        )
        assertEquals(M(152000, 150000, 200000, 0, 2000, -52000), m(b))
        assertEquals(Cents(350000), b.planned)
        assertEquals(PaceStatus.OVERRUN, b.status)
        assertEquals(listOf(0L, 0, 0, 0, 0), b.steps())
        assertEquals(List(5) { false }, b.weeks.map { it.edited }, "an edit over the cota livre doesn't count")
        assertEquals(Cents(0), b.ideal)
        assertEquals(Cents(0), b.projection, "M6: no projection when the ideal is 0")
    }

    @Test
    fun `início no dia 31 com fevereiro curto conta só os fixos dentro da janela`() {
        // Budget month Jan 2027 with start 31 = 31 jan..27 fev (28 days). Weeks from Monday: 31 jan (1 day),
        // 1–7, 8–14, 15–21 fev, 22–27 fev (6 days).
        val fixed = mapOf(d(1, 30, 2027) to 70000L, d(2, 27, 2027) to 100000L, d(2, 28, 2027) to 99L)
        val b = bm(
            limit = 300000, month = YearMonth(2027, 1), startDay = 31, today = d(2, 10, 2027), open = 60000,
            fixed = fixed, spent = fixed + mapOf(d(2, 3, 2027) to 50000L),
        )
        assertEquals(d(1, 31, 2027) to d(2, 27, 2027), b.start to b.end)
        // 30 jan and 28 fev are outside: fixed = 100000 (27 fev, the last day); spent = 100000 + 50000.
        // planned = 60000 + 100000 = 160000, cota livre 140000, variável 50000, margem 300000 − 150000 − 60000 = 90000.
        assertEquals(M(150000, 100000, 60000, 140000, 50000, 90000), m(b))
        // §4: 140000 ÷ 28 days = 5000 a day, exact: 5000 · 35000 · 35000 · 35000 · 30000.
        assertEquals(listOf(5000L, 35000, 35000, 35000, 30000), b.steps())
        assertEquals(listOf(0L, 50000, 0, 0, 0), b.weeks.map { it.spent.v }, "the fixed of 27 fev is out of Week.spent")
        // §5 on 10 fev: 5000 + 35000 + 35000 × 3 ÷ 7 (8–10 fev) = 5000 + 35000 + 15000 = 55000.
        assertEquals(Cents(55000), b.ideal)
        assertEquals(28, b.idealByDay.size)
        assertEquals(Cents(55000), b.idealByDay[10], "10 fev is index 10 from 31 jan")
        assertEquals(Cents(140000), b.idealByDay.last(), "D68: last day = cota livre")
        assertEquals(PaceStatus.ON_PACE, b.status, "variável 50000 ≤ ideal 55000")
        // M6: 50000 × 140000 ÷ 55000 = 127272,7… + planned 160000 → 287272 rounded down.
        assertEquals(Cents(287272), b.projection)
    }

    @Test
    fun `fixo no último dia do mês conta e o do dia seguinte não`() {
        // Oct 2026, start 1: 31 out is in, 30 set and 1 nov are not (KDoc: days outside the month are ignored).
        val fixed = mapOf(d(9, 30) to 50000L, d(10, 31) to 20000L, d(11, 1) to 50000L)
        val b = bm(limit = 300000, fixed = fixed, spent = fixed)
        // fixed 20000, planned 20000, cota livre 280000, variável 0, margem 300000 − 20000 − 0 = 280000.
        assertEquals(M(20000, 20000, 0, 280000, 0, 280000), m(b))
        assertEquals(Cents(0), b.weeks.last().spent, "26–31 out: the fixed is not gasto variável")
    }

    @Test
    fun `exatamente no ritmo com previsto lançado acima do valor projeta a cota`() {
        // M4: aluguel previsto 200000 lançado at 210000 → cota livre 300000 − 210000 = 90000; ideal of 9 out = 26127.
        val fixed = mapOf(d(10, 5) to 210000L)
        val on = bm(limit = 300000, fixed = fixed, spent = fixed + mapOf(d(10, 3) to 26127L))
        assertEquals(M(236127, 210000, 0, 90000, 26127, 63873), m(on))
        assertEquals(listOf(11612L, 20322, 20322, 20322, 17422), on.steps())
        assertEquals(Cents(26127), on.ideal)
        assertEquals(PaceStatus.ON_PACE, on.status, "variável 26127 ≤ ideal 26127")
        // M6: 26127 × 90000 ÷ 26127 + 210000 = 300000: exactly on pace projects the cota.
        assertEquals(Cents(300000), on.projection)

        // One cent more (§5, no tolerance): Acima do ritmo; 26128 × 90000 ÷ 26127 = 90003,4… + 210000 → 300003.
        val ahead = bm(limit = 300000, fixed = fixed, spent = fixed + mapOf(d(10, 3) to 26128L))
        assertEquals(PaceStatus.AHEAD, ahead.status)
        assertEquals(Cents(300003), ahead.projection)
    }

    @Test
    fun `edições que somam exatamente a cota livre valem e um centavo de previsto a mais as ignora`() {
        val edit = mapOf(d(10, 12) to 40000L)
        // Cota livre 300000 − 260000 = 40000 = the edit: not "more than" → it counts; the other weeks share 0.
        val fits = bm(limit = 300000, open = 260000, edited = edit)
        assertEquals(listOf(0L, 0, 40000, 0, 0), fits.steps())
        assertEquals(listOf(false, false, true, false, false), fits.weeks.map { it.edited })
        // Week.max (D70) = cota livre − other edits: 40000 for the edited week, 40000 − 40000 = 0 for the others.
        assertEquals(listOf(0L, 0, 40000, 0, 0), fits.weeks.map { it.max.v })
        assertEquals(List(5) { false }, fits.weeks.map { it.locked }, "four automatic weeks: none locked")

        // Cota livre 300000 − 260001 = 39999 < 40000 → the edit is ignored (D70), the passos are the default of 39999.
        val over = bm(limit = 300000, open = 260001, edited = edit)
        assertEquals(List(5) { false }, over.weeks.map { it.edited })
        assertEquals(listOf(5161L, 9032, 9032, 9032, 7742), over.steps())
        assertEquals(39999L, over.steps().sum(), "§4: Σ passos = cota livre")
    }

    @Test
    fun `sem previsto o budgetMonth é o de antes`() {
        val b = bm(limit = 300000, spent = mapOf(d(10, 2) to 4000L))
        assertEquals(M(4000, 0, 0, 300000, 4000, 296000), m(b))
        assertEquals(Cents(0), b.planned)
        assertEquals(listOf(38709L, 67741, 67741, 67741, 58068), b.steps())
        assertEquals(Cents(87095), b.ideal)
        assertEquals(PaceStatus.ON_PACE, b.status)
        // §5: 4000 × 300000 ÷ 87095 = 13778,0… → 13778.
        assertEquals(Cents(13778), b.projection)
        assertEquals(Cents(300000), b.idealByDay.last())
        assertEquals(listOf(4000L, 0, 0, 0, 0), b.weeks.map { it.spent.v })
    }

    // ---- Db (D68–D72) ----

    private var seq = 0

    private fun withDb(block: suspend (Db) -> Unit): TestResult = runTest(timeout = 120.seconds) {
        val path = Path(SystemTemporaryDirectory, "reserva-edge-${Random.nextLong().toULong()}.db").toString()
        val db = Db(BundledSQLiteDriver(), path, timeZone = TZ)
        try {
            assertTrue(db.addSource(NU, "Nubank", 0))
            block(db)
        } finally {
            db.close()
            for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
        }
    }

    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate(y, m, day)

    private suspend fun Db.budget(limit: Long, today: LocalDate, name: String = "Casa"): BudgetId =
        createBudget(name, Cents(limit), today).also { assertNotEquals(0L, it.v, "createBudget $name") }

    /** Salvar previsto (spec §2.13, D64): a manual with a date after [today]; only a previsto, first occurrence on [date]. */
    private suspend fun Db.previsto(
        budget: BudgetId, date: LocalDate, name: String, cents: Long, today: LocalDate, repeats: Repeats = Repeats.ONCE,
    ): PlannedId {
        val s = addManual(TransactionForm(budget, listOf("casa"), Cents(cents), 1, "", date, NOON, caption = name, repeats = repeats), today)
        assertEquals(TransactionId(0), s.transaction, "Salvar previsto $name: no lançamento")
        assertNotEquals(PlannedId(0), s.planned, "Salvar previsto $name")
        return s.planned
    }

    /** An ordinary lançamento manual (no occurrence). */
    private suspend fun Db.spend(
        budget: BudgetId, date: LocalDate, cents: Long, today: LocalDate, name: String = "Padaria", installments: Int = 1,
    ): TransactionId {
        val s = addManual(TransactionForm(budget, listOf("casa"), Cents(cents), installments, "", date, NOON, caption = name), today)
        assertNotEquals(TransactionId(0), s.transaction, "lançamento $name")
        return s.transaction
    }

    private suspend fun Db.occ(today: LocalDate, name: String, date: LocalDate? = null): Planned =
        planned(today).single { it.name == name && (date == null || it.date == date) }

    /** Lançar sem recado (spec §2.13): the manual with the occurrence, at [cents] on [date] at [time]. */
    private suspend fun Db.launch(
        p: Planned, date: LocalDate, cents: Long, today: LocalDate, time: LocalTime = NOON, installments: Int = 1,
    ): TransactionId {
        val s = addManual(
            TransactionForm(p.budget, p.tags, Cents(cents), installments, "", date, time, caption = p.name, occurrence = p.occurrence), today,
        )
        assertNotEquals(TransactionId(0), s.transaction, "Lançar sem recado ${p.name}")
        return s.transaction
    }

    /** Captures a Nubank notice dated [on] at [at] (São Paulo) and returns its id. */
    private suspend fun Db.post(on: LocalDate, text: String, at: LocalTime = NOON): NoticeId {
        val n = ++seq
        val ms = on.atTime(at).toInstant(TZ).toEpochMilliseconds() + n
        val key = "reserva-edge|$n"
        assertTrue(capture(this, RawNotice(packageName = NU, appLabel = "Nubank", key = key, postedAt = ms, whenMs = ms, title = "Nubank", text = text, bigText = text)), "capture $n")
        return pending().single { it.raw.key == key }.id
    }

    /** A Nubank purchase: no date in the text, so the recado's date and time are its `when` (São Paulo). */
    private suspend fun Db.purchase(on: LocalDate, merchant: String, cents: String, at: LocalTime): NoticeId =
        post(on, "Compra de R$ $cents APROVADA em $merchant para o cartão com final 1234.", at)

    private suspend fun Db.estorno(on: LocalDate): NoticeId =
        post(on, "A compra em IMOBILIARIA SOL no valor de R$ 2.100,00 foi estornada no seu cartão.")

    /** Lançar the recado as the occurrence [p] ("Lançar previsto" / "É um previsto? Ligar"): value, bank text, date and time of the recado. */
    private suspend fun Db.acceptAs(notice: NoticeId, p: Planned): TransactionId {
        val r = notice(notice).parsed
        val tx = accept(notice, TransactionForm(p.budget, p.tags, r.cents, 1, r.merchant, r.date, r.time, caption = p.name, occurrence = p.occurrence))
        assertNotEquals(TransactionId(0), tx, "accept as ${p.name}")
        return tx
    }

    private suspend fun Db.month(id: BudgetId, month: YearMonth, today: LocalDate): BudgetMonth = budgetDetail(id, month, today).month

    private suspend fun Db.row(tx: TransactionId): TransactionRow = transaction(tx).row

    @Test
    fun `cota livre zero no banco reserva a cota inteira e um centavo variável sai da margem`() = withDb { db ->
        val casa = db.budget(300000, TODAY)
        db.previsto(casa, d(10, 20), "Aluguel", 300000, TODAY)

        // M1–M3: open 300000, cota livre 0, margem 300000 − 0 − 300000 = 0.
        val b = db.month(casa, OCT, TODAY)
        assertEquals(M(0, 0, 300000, 0, 0, 0), m(b))
        assertEquals(listOf(0L, 0, 0, 0, 0), b.steps())
        assertEquals(PaceStatus.ON_PACE, b.status)
        assertEquals(Cents(0), b.projection)
        assertEquals(b, db.budgetsOverview(OCT, TODAY, BudgetOrder.MANUAL).single().month, "Verbas and the detail read the same month")

        db.spend(casa, TODAY, 1, TODAY)
        // Margem 300000 − 1 − 300000 = −1 → Fora da margem.
        val after = db.month(casa, OCT, TODAY)
        assertEquals(M(1, 0, 300000, 0, 1, -1), m(after))
        assertEquals(PaceStatus.OVERRUN, after.status)
        assertEquals(Cents(0), after.projection, "ideal 0: no projection")
    }

    @Test
    fun `previsto lançado acima da cota e outro em aberto dão cota livre zero e fora da margem`() = withDb { db ->
        val d1 = d(10, 1)
        val casa = db.budget(300000, d1)
        db.previsto(casa, d(10, 5), "Aluguel", 280000, d1)
        db.previsto(casa, d(10, 25), "Internet", 10000, d1)

        val tx = db.launch(db.occ(TODAY, "Aluguel"), d(10, 5), 320000, TODAY)
        assertTrue(db.row(tx).fixed, "D69: closing an occurrence makes it fixed")

        // fixed 320000, open 10000 → planned 330000 > 300000: cota livre 0; margem 300000 − 320000 − 10000 = −30000.
        val b = db.month(casa, OCT, TODAY)
        assertEquals(M(320000, 320000, 10000, 0, 0, -30000), m(b))
        assertEquals(Cents(330000), b.planned)
        assertEquals(PaceStatus.OVERRUN, b.status)
        assertEquals(listOf(0L, 0, 0, 0, 0), b.steps())
        assertEquals(Cents(0), b.ideal)
        assertEquals(Cents(0), b.projection)
    }

    @Test
    fun `reserva conta a ocorrência do dia 28 vista no dia 2 além de hoje mais 10`() = withDb { db ->
        val s20 = d(9, 20)
        val casa = db.budget(300000, s20)
        db.previsto(casa, d(9, 28), "Internet", 10000, s20, Repeats.MONTHLY)
        db.previsto(casa, d(10, 30), "Seguro", 5000, s20)

        val t = d(10, 2) // today + 10 = 12 out; end of today's budget month = 31 out (D71)
        db.ensureMonths(OCT)
        // Overview first: it creates the 28 out occurrence itself (D71). Open = 10000 (28 out) + 5000 (30 out).
        val ov = db.budgetsOverview(OCT, t, BudgetOrder.MANUAL).single().month
        assertEquals(M(0, 0, 15000, 285000, 0, 285000), m(ov))
        // The late 28 set keeps reserving in September (M8): margem 300000 − 0 − 10000.
        assertEquals(M(0, 0, 10000, 290000, 0, 290000), m(db.month(casa, SEP, t)))

        val lines = db.budgetDetail(casa, OCT, t).plannedLines
        assertEquals(listOf(d(10, 28) to 10000L, d(10, 30) to 5000L), lines.map { it.planned.date to it.cents.v })
        assertTrue(lines.all { it.open }, "both still open")
        // "A caminho" is still today + 10 (R1, plan §3): only the late 28 set.
        assertEquals(listOf("Internet" to d(9, 28)), db.planned(t).map { it.name to it.date })
    }

    @Test
    fun `o detalhe da verba também cria as ocorrências do mês antes de ler`() = withDb { db ->
        val s20 = d(9, 20)
        val casa = db.budget(300000, s20)
        db.previsto(casa, d(9, 28), "Internet", 10000, s20, Repeats.MONTHLY)

        val t = d(10, 2)
        db.ensureMonths(OCT)
        // D71: budgetDetail calls fill(today) first; 28 out > 12 out but ≤ 31 out → open 10000.
        assertEquals(M(0, 0, 10000, 290000, 0, 290000), m(db.month(casa, OCT, t)))
    }

    @Test
    fun `ocorrência no último dia reserva no mês dela e não no seguinte`() = withDb { db ->
        val casa = db.budget(300000, TODAY)
        db.previsto(casa, d(10, 31), "Aluguel", 200000, TODAY, Repeats.MONTHLY)
        assertEquals(Cents(200000), db.month(casa, OCT, TODAY).open, "31 out is the last day of October")

        val n1 = d(11, 1)
        db.ensureMonths(NOV)
        // D71 on 1 nov: up to 30 nov → the November occurrence (30 nov) exists; 31 out stays open and late.
        val nov = db.budgetDetail(casa, NOV, n1)
        assertEquals(d(11, 1) to d(11, 30), nov.month.start to nov.month.end)
        assertEquals(M(0, 0, 200000, 100000, 0, 100000), m(nov.month), "only 30 nov, not the late 31 out")
        assertEquals(listOf(d(11, 30)), nov.plannedLines.map { it.planned.date })

        val oct = db.budgetDetail(casa, OCT, n1)
        assertEquals(M(0, 0, 200000, 100000, 0, 100000), m(oct.month), "the late 31 out reserves once, in October (M8)")
        assertEquals(listOf(d(10, 31)), oct.plannedLines.map { it.planned.date })
        assertTrue(oct.plannedLines.single().planned.late, "31 out < 1 nov: late against the read's today")
    }

    @Test
    fun `com início no dia 31 o dia 30 de outubro é o último do mês da verba e reserva nele`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, TODAY))
        val casa = db.budget(300000, TODAY) // 9 out is in 2026-09 = 30 set..30 out
        db.previsto(casa, d(10, 30), "Aluguel", 200000, TODAY)
        db.previsto(casa, d(10, 31), "Internet", 10000, TODAY)
        db.ensureMonths(OCT)

        val sep = db.month(casa, SEP, TODAY)
        assertEquals(d(9, 30) to d(10, 30), sep.start to sep.end)
        assertEquals(M(0, 0, 200000, 100000, 0, 100000), m(sep), "30 out reserves in 2026-09; 31 out doesn't")
        val oct = db.month(casa, OCT, TODAY)
        assertEquals(d(10, 31) to d(11, 29), oct.start to oct.end)
        assertEquals(M(0, 0, 10000, 290000, 0, 290000), m(oct), "31 out is the first day of 2026-10")
    }

    @Test
    fun `com início no dia 31 o recado de 31 de outubro volta ao dia 30 e o de 2 de novembro fica`() = withDb { db ->
        assertTrue(db.setMonthStartDay(31, TODAY))
        val casa = db.budget(300000, TODAY)
        db.previsto(casa, d(10, 30), "Aluguel", 200000, TODAY)
        db.previsto(casa, d(10, 31), "Internet", 10000, TODAY)
        db.ensureMonths(OCT)

        val o31 = d(10, 31)
        val aluguel = db.occ(o31, "Aluguel")
        val internet = db.occ(o31, "Internet")

        // D72 by budget month, not calendar month: 31 out is in 2026-10, the occurrence (30 out) in 2026-09.
        val n1 = db.purchase(o31, "IMOBILIARIA SOL", "2.000,00", LocalTime(8, 0))
        val t1 = db.acceptAs(n1, aluguel)
        val r1 = db.row(t1)
        assertEquals(d(10, 30), r1.date, "M8: the occurrence's day")
        assertEquals(db.notice(n1).parsed.time, r1.time, "D72: the time it had")
        assertFalse(r1.corrected, "D72: the move is not an emenda")
        assertTrue(r1.fixed)

        // 2 nov and 31 out are both in 2026-10 (31 out..29 nov): the recado's date stays.
        val n2 = db.purchase(d(11, 2), "PROVEDOR NET", "100,00", LocalTime(8, 0))
        val t2 = db.acceptAs(n2, internet)
        assertEquals(d(11, 2), db.row(t2).date, "same budget month: no move")
        assertTrue(db.row(t2).fixed)

        val n2d = d(11, 2)
        // 2026-09: fixed 200000, cota livre 100000, margem 300000 − 200000 − 0.
        assertEquals(M(200000, 200000, 0, 100000, 0, 100000), m(db.month(casa, SEP, n2d)))
        // 2026-10: fixed 10000, cota livre 290000, margem 290000.
        assertEquals(M(10000, 10000, 0, 290000, 0, 290000), m(db.month(casa, OCT, n2d)))
    }

    @Test
    fun `com início no dia 29 o previsto do dia 29 cai em 28 de fevereiro no começo do mês seguinte da verba`() = withDb { db ->
        val j20 = d(1, 20, 2027)
        assertTrue(db.setMonthStartDay(29, j20))
        val casa = db.budget(300000, j20) // 20 jan is in 2026-12 = 29 dez..28 jan
        db.previsto(casa, d(1, 29, 2027), "Aluguel", 200000, j20, Repeats.MONTHLY)

        val f27 = d(2, 27, 2027) // last day of 2027-01; fill up to max(9 mar, 27 fev) creates 28 fev (P8: Feb has no 29)
        db.ensureMonths(YearMonth(2027, 2))

        val dec = db.month(casa, YearMonth(2026, 12), f27)
        assertEquals(d(12, 29) to d(1, 28, 2027), dec.start to dec.end)
        assertEquals(Cents(0), dec.open, "29 jan is not in 2026-12")

        val jan = db.budgetDetail(casa, YearMonth(2027, 1), f27)
        assertEquals(d(1, 29, 2027) to d(2, 27, 2027), jan.month.start to jan.month.end)
        assertEquals(M(0, 0, 200000, 100000, 0, 100000), m(jan.month), "only 29 jan (late), not 28 fev")
        assertEquals(listOf(d(1, 29, 2027)), jan.plannedLines.map { it.planned.date })

        val feb = db.budgetDetail(casa, YearMonth(2027, 2), f27)
        assertEquals(d(2, 28, 2027) to d(3, 28, 2027), feb.month.start to feb.month.end)
        assertEquals(M(0, 0, 200000, 100000, 0, 100000), m(feb.month), "28 fev is the first day of 2027-02")
        assertEquals(listOf(d(2, 28, 2027)), feb.plannedLines.map { it.planned.date })
    }

    @Test
    fun `previsto de 28 de outubro lançado com recado de 3 de novembro fica em outubro com a hora do recado`() = withDb { db ->
        val casa = db.budget(300000, TODAY)
        db.previsto(casa, d(10, 28), "Aluguel", 200000, TODAY)

        val n3 = d(11, 3)
        db.ensureMonths(NOV)
        val p = db.occ(n3, "Aluguel")
        assertTrue(p.late)
        val notice = db.purchase(n3, "IMOBILIARIA SOL", "2.050,00", LocalTime(14, 30))
        assertEquals(Cents(205000), db.notice(notice).parsed.cents)
        val tx = db.acceptAs(notice, p)

        val r = db.row(tx)
        assertEquals(d(10, 28), r.date, "M8, D72: paid late, counts on the occurrence's day")
        assertEquals(db.notice(notice).parsed.time, r.time)
        assertFalse(r.corrected, "no ‸ emendado")
        assertTrue(r.fixed)
        assertEquals(listOf(d(10, 28)), db.transaction(tx).installments.map { it.date })

        // October: fixed 205000 (M4: the real value), open 0, cota livre 95000, margem 300000 − 205000 − 0 = 95000.
        val oct = db.budgetDetail(casa, OCT, n3)
        assertEquals(M(205000, 205000, 0, 95000, 0, 95000), m(oct.month))
        val line = oct.plannedLines.single()
        assertEquals(Triple(p.occurrence, d(10, 28), 205000L), Triple(line.planned.occurrence, line.launched, line.cents.v), "D73: lançada line")
        assertFalse(line.open)
        // November: nothing.
        assertEquals(M(0, 0, 0, 300000, 0, 300000), m(db.month(casa, NOV, n3)))
    }

    @Test
    fun `previsto de 2 de novembro pago adiantado em 28 de outubro conta em novembro`() = withDb { db ->
        val casa = db.budget(300000, TODAY)
        db.previsto(casa, d(11, 2), "Internet", 12000, TODAY)

        val o28 = d(10, 28)
        db.ensureMonths(NOV)
        assertEquals(Cents(0), db.month(casa, OCT, o28).open)
        assertEquals(Cents(12000), db.month(casa, NOV, o28).open, "2 nov reserves in November")

        val p = db.occ(o28, "Internet")
        val notice = db.purchase(o28, "PROVEDOR NET", "119,90", LocalTime(9, 15))
        val tx = db.acceptAs(notice, p)
        val r = db.row(tx)
        assertEquals(d(11, 2), r.date, "M8: early alike")
        assertEquals(db.notice(notice).parsed.time, r.time)
        assertFalse(r.corrected)
        assertTrue(r.fixed)

        assertEquals(M(0, 0, 0, 300000, 0, 300000), m(db.month(casa, OCT, o28)))
        // November: fixed 11990, cota livre 300000 − 11990 = 288010, margem the same.
        assertEquals(M(11990, 11990, 0, 288010, 0, 288010), m(db.month(casa, NOV, o28)))
    }

    @Test
    fun `editar a data do lançamento de previsto para outro mês mantém o dia da ocorrência com a hora nova`() = withDb { db ->
        val casa = db.budget(300000, TODAY)
        val pid = db.previsto(casa, d(10, 28), "Aluguel", 200000, TODAY)
        val n3 = d(11, 3)
        db.ensureMonths(NOV)

        // Lançar sem recado on 3 nov at 10:00 → D72 moves it to 28 out, 10:00.
        val tx = db.launch(db.occ(n3, "Aluguel"), n3, 200000, n3, LocalTime(10, 0))
        assertEquals(d(10, 28) to LocalTime(10, 0), db.row(tx).let { it.date to it.time })

        // The edit form carries no occurrence (the origin is the stored one, never the form's).
        fun form(date: LocalDate, time: LocalTime) =
            TransactionForm(casa, listOf("casa"), Cents(200000), 1, "", date, time, caption = "Aluguel")

        assertTrue(db.edit(tx, form(d(11, 5), LocalTime(8, 0))))
        val late = db.row(tx)
        assertEquals(d(10, 28) to LocalTime(8, 0), late.date to late.time, "D72: the occurrence's date with the new time")
        assertFalse(late.corrected)
        assertTrue(late.fixed, "D69: kept by edit")
        assertEquals(M(0, 0, 0, 300000, 0, 300000), m(db.month(casa, NOV, n3)), "November not reserved twice")

        assertTrue(db.edit(tx, form(d(9, 30), LocalTime(7, 0))))
        assertEquals(d(10, 28) to LocalTime(7, 0), db.row(tx).let { it.date to it.time }, "to an earlier month too")

        assertTrue(db.edit(tx, form(d(10, 20), LocalTime(9, 0))))
        assertEquals(d(10, 20) to LocalTime(9, 0), db.row(tx).let { it.date to it.time }, "same budget month: the date edited")
        assertEquals(M(200000, 200000, 0, 100000, 0, 100000), m(db.month(casa, OCT, n3)))

        // Previsto deleted: no occurrence any more, so the date moves freely; the lançamento stays fixed (D69).
        db.deletePlanned(pid)
        assertTrue(db.edit(tx, form(d(11, 5), LocalTime(8, 0))))
        val free = db.row(tx)
        assertEquals(d(11, 5), free.date)
        assertTrue(free.fixed)
        assertEquals(M(0, 0, 0, 300000, 0, 300000), m(db.month(casa, OCT, n3)))
        // November: fixed 200000, cota livre 100000, margem 300000 − 200000 − 0 = 100000.
        assertEquals(M(200000, 200000, 0, 100000, 0, 100000), m(db.month(casa, NOV, n3)))
    }

    @Test
    fun `estorno do lançamento de previsto tira o valor do gasto e devolve a cota livre e os passos editados`() = withDb { db ->
        val d1 = d(10, 1)
        val casa = db.budget(300000, d1)
        assertTrue(db.setStep(casa, OCT, d(10, 12), Cents(150000)), "max 300000 before any previsto")
        db.previsto(casa, d(10, 5), "Aluguel", 200000, d1)

        // D70: cota livre 100000 < edit 150000 → ignored, the default passos of 100000.
        val before = db.month(casa, OCT, d1)
        assertEquals(List(5) { false }, before.weeks.map { it.edited })
        assertEquals(listOf(12903L, 22580, 22580, 22580, 19357), before.steps())

        db.spend(casa, d(10, 2), 4000, TODAY)
        val tx = db.launch(db.occ(TODAY, "Aluguel"), d(10, 5), 210000, TODAY)

        // M4: lançado at 210000 → cota livre 90000; spent 214000, variável 4000, margem 300000 − 214000 − 0 = 86000.
        val launched = db.month(casa, OCT, TODAY)
        assertEquals(M(214000, 210000, 0, 90000, 4000, 86000), m(launched))
        assertEquals(listOf(11612L, 20322, 20322, 20322, 17422), launched.steps(), "edit still ignored")
        assertEquals(Cents(26127), launched.ideal)
        assertEquals(PaceStatus.ON_PACE, launched.status, "variável 4000 ≤ 26127")

        val e = db.estorno(TODAY)
        assertTrue(db.registerRefund(e, tx))
        // §6: the refunded fixed counts nowhere (D68: "vale 0"); the occurrence stays closed, so nothing reserves.
        // Cota livre back to 300000; margem 300000 − 4000 − 0 = 296000.
        val refunded = db.budgetDetail(casa, OCT, TODAY)
        assertEquals(M(4000, 0, 0, 300000, 4000, 296000), m(refunded.month))
        assertTrue(db.row(tx).fixed, "still fixed, just refunded")
        // §4: the edit fits again and counts; the four automatic weeks share 150000 by 24 days: 25000 · 43750 · 43750 · 37500.
        assertEquals(listOf(false, false, true, false, false), refunded.month.weeks.map { it.edited })
        assertEquals(listOf(25000L, 43750, 150000, 43750, 37500), refunded.month.steps())
        // §5 on 9 out: 25000 + 43750 × 5 ÷ 7 = 25000 + 31250.
        assertEquals(Cents(56250), refunded.month.ideal)
        // D73: the lançada line adds what its lançamento adds to fixed: 0 when refunded.
        val line = refunded.plannedLines.single()
        assertEquals(d(10, 5) to 0L, line.launched to line.cents.v)

        // Desfazer estorno: everything back.
        assertTrue(db.undoRefund(e))
        val undone = db.month(casa, OCT, TODAY)
        assertEquals(M(214000, 210000, 0, 90000, 4000, 86000), m(undone))
        assertEquals(List(5) { false }, undone.weeks.map { it.edited })
    }

    @Test
    fun `parcelas do lançamento de previsto são fixas cada uma no seu mês e o estorno tira todas`() = withDb { db ->
        val d1 = d(10, 1)
        val casa = db.budget(300000, d1)
        val pid = db.previsto(casa, d(10, 5), "IPVA", 100000, d1)
        db.ensureMonths(DEC)

        val tx = db.launch(db.occ(TODAY, "IPVA"), d(10, 5), 100000, TODAY, installments = 3)
        // §6: 100000 in 3× → 33333 · 33333 · 33334, on 5 out, 5 nov, 5 dez.
        assertEquals(
            listOf(d(10, 5) to 33333L, d(11, 5) to 33333L, d(12, 5) to 33334L),
            db.transaction(tx).installments.map { it.date to it.cents.v },
        )
        val n10 = d(11, 10)
        db.spend(casa, n10, 5000, n10)

        // D68: each installment fixed in its month. Oct: cota livre 300000 − 33333 = 266667.
        assertEquals(M(33333, 33333, 0, 266667, 0, 266667), m(db.month(casa, OCT, n10)))
        // Nov: spent 33333 + 5000, variável 5000, margem 300000 − 38333 = 261667.
        assertEquals(M(38333, 33333, 0, 266667, 5000, 261667), m(db.month(casa, NOV, n10)))
        // Dec: 300000 − 33334 = 266666.
        assertEquals(M(33334, 33334, 0, 266666, 0, 266666), m(db.month(casa, DEC, n10)))

        // D73: the line is in the occurrence's month only, with the installment of that month.
        val oct = db.budgetDetail(casa, OCT, n10).plannedLines.single()
        assertEquals(d(10, 5) to 33333L, oct.launched to oct.cents.v)
        assertEquals(emptyList(), db.budgetDetail(casa, NOV, n10).plannedLines, "no occurrence dated in November")

        // Apagar previsto: the lançamento stays fixed (D69).
        db.deletePlanned(pid)
        assertTrue(db.row(tx).fixed)
        assertEquals(M(38333, 33333, 0, 266667, 5000, 261667), m(db.month(casa, NOV, n10)))
        assertEquals(emptyList(), db.budgetDetail(casa, OCT, n10).plannedLines, "the occurrence went with the previsto")

        // §6: an estorno of a parcelada takes every installment, past months included.
        assertTrue(db.registerRefund(db.estorno(n10), tx))
        assertEquals(M(0, 0, 0, 300000, 0, 300000), m(db.month(casa, OCT, n10)))
        assertEquals(M(5000, 0, 0, 300000, 5000, 295000), m(db.month(casa, NOV, n10)))
        assertEquals(M(0, 0, 0, 300000, 0, 300000), m(db.month(casa, DEC, n10)))
    }

    @Test
    fun `lançamento comum com o previsto em aberto é variável e a ocorrência continua reservando`() = withDb { db ->
        val casa = db.budget(300000, TODAY)
        db.previsto(casa, d(10, 15), "Aluguel", 200000, TODAY)
        val tx = db.spend(casa, TODAY, 200000, TODAY, name = "Aluguel no Pix")
        assertFalse(db.row(tx).fixed, "no occurrence: an ordinary lançamento")

        // M8: it counts in the month paid as gasto variável and the previsto keeps reserving:
        // margem 300000 − 200000 − 200000 = −100000 → Fora da margem.
        val b = db.budgetDetail(casa, OCT, TODAY)
        assertEquals(M(200000, 0, 200000, 100000, 200000, -100000), m(b.month))
        assertEquals(PaceStatus.OVERRUN, b.month.status)
        val line = b.plannedLines.single()
        assertTrue(line.open)
        assertEquals(200000L, line.cents.v)

        // Pular este mês: the reserve goes; margem 300000 − 200000 − 0 = 100000; variável 200000 > ideal 87095 → Acima do ritmo.
        assertTrue(db.skipOccurrence(db.occ(TODAY, "Aluguel").occurrence))
        val skipped = db.month(casa, OCT, TODAY)
        assertEquals(M(200000, 0, 0, 300000, 200000, 100000), m(skipped))
        assertEquals(Cents(87095), skipped.ideal)
        assertEquals(PaceStatus.AHEAD, skipped.status)
    }

    @Test
    fun `ocorrência pulada nunca reserva nem atrasada nem depois de editar o valor do previsto`() = withDb { db ->
        val s20 = d(9, 20)
        val casa = db.budget(300000, s20)
        val pid = db.previsto(casa, d(9, 25), "Academia", 12000, s20, Repeats.MONTHLY)

        val o15 = d(10, 15) // R1 up to 25 out: 25 set (late) and 25 out are listed
        db.ensureMonths(OCT)
        assertEquals(Cents(12000), db.month(casa, SEP, o15).open)
        assertEquals(Cents(12000), db.month(casa, OCT, o15).open)

        assertTrue(db.skipOccurrence(db.occ(o15, "Academia", d(9, 25)).occurrence))
        assertTrue(db.skipOccurrence(db.occ(o15, "Academia", d(10, 25)).occurrence))
        // M2: skipped ones are not previstos do mês.
        val sep = db.budgetDetail(casa, SEP, o15)
        val oct = db.budgetDetail(casa, OCT, o15)
        assertEquals(M(0, 0, 0, 300000, 0, 300000), m(sep.month))
        assertEquals(M(0, 0, 0, 300000, 0, 300000), m(oct.month))
        assertEquals(emptyList(), sep.plannedLines)
        assertEquals(emptyList(), oct.plannedLines)

        // Editar previsto to 15000: the skipped months still reserve nothing.
        val edited = db.addManual(
            TransactionForm(casa, listOf("casa"), Cents(15000), 1, "", d(10, 25), NOON, caption = "Academia", repeats = Repeats.MONTHLY, planned = pid),
            o15,
        )
        assertEquals(Saved(planned = pid), edited)
        assertEquals(Cents(0), db.month(casa, SEP, o15).open)
        assertEquals(Cents(0), db.month(casa, OCT, o15).open)

        // 1 nov: D71 creates 25 nov (> 11 nov), at the new value; the skipped October is never recreated.
        val n1 = d(11, 1)
        db.ensureMonths(NOV)
        assertEquals(Cents(15000), db.month(casa, NOV, n1).open)
        assertEquals(Cents(0), db.month(casa, OCT, n1).open)
    }

    @Test
    fun `sem previsto na verba o mês no banco é o de antes`() = withDb { db ->
        val casa = db.budget(300000, TODAY)
        val lazer = db.budget(50000, TODAY, "Lazer")
        db.previsto(lazer, d(10, 20), "Cinema", 3000, TODAY) // another budget's previsto never reserves here
        db.spend(casa, d(10, 2), 4000, TODAY)
        val refunded = db.spend(casa, d(10, 3), 1000, TODAY)
        assertTrue(db.registerRefund(db.estorno(TODAY), refunded))

        val detail = db.budgetDetail(casa, OCT, TODAY)
        val b = detail.month
        assertEquals(Cents(300000), b.limit)
        assertEquals(M(4000, 0, 0, 300000, 4000, 296000), m(b))
        assertEquals(Cents(0), b.planned)
        assertEquals(listOf(38709L, 67741, 67741, 67741, 58068), b.steps())
        assertEquals(db.previewBudget(Cents(300000), TODAY).steps(), b.steps())
        assertEquals(Cents(87095), b.ideal)
        assertEquals(Cents(87095), b.idealByDay[8], "9 out is index 8")
        assertEquals(PaceStatus.ON_PACE, b.status)
        assertEquals(Cents(13778), b.projection, "4000 × 300000 ÷ 87095 = 13778,0…")
        assertEquals(listOf(4000L, 0, 0, 0, 0), b.weeks.map { it.spent.v })
        assertEquals(emptyList(), detail.plannedLines)
        assertEquals(b, db.budgetsOverview(OCT, TODAY, BudgetOrder.MANUAL).single { it.id == casa }.month)
    }

    private companion object {
        const val NU = "com.nu.production"
        val TZ = TimeZone.of("America/Sao_Paulo")
        val TODAY = LocalDate(2026, 10, 9)
        val NOON = LocalTime(12, 0)
        val SEP = YearMonth(2026, 9)
        val OCT = YearMonth(2026, 10)
        val NOV = YearMonth(2026, 11)
        val DEC = YearMonth(2026, 12)
    }
}
