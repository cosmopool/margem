@file:OptIn(ExperimentalAtomicApi::class)

package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.onDay
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Concorrência e ciclo de vida da margem honesta (spec §2.13, §3, §4, §5, §7.1; margem-plan M1–M8; architecture
// D29, D59, D68–D73), through the public API only. D71: budgetsOverview and budgetDetail now create the previstos'
// occurrences up to max(today + 10, end of today's budget month) before reading, a write that doesn't bump version,
// like pending(today) and planned(today). These tests race that creation against the reads, against Lançar /
// Pular / Apagar previsto on the same occurrence, across close + open of the same file and across the virada of
// the budget month. A month read is always one of the states the spec allows: an occurrence either reserves at the
// previsto's value (open), or counts as gasto fixo at the real value (lançada), or neither when skipped — never
// both, never neither while it is open or lançada.
//
// Scenario (the book's day): sexta, 9 out 2026; month start day 1 (budget month = calendar month), weeks from
// Monday (defaults). Every budget has cota R$ 3.000,00 = 300.000 cents. Expected values are by hand from spec §3:
//   margem (remaining) = cota − gasto do mês − previstos em aberto do mês        (M1)
//   previstos do mês = open + fixed; cota livre = max(0, cota − previstos do mês) (M2, M3)
//   gasto variável = gasto do mês − lançamentos de previsto                      (M3, M4)
//
// Work runs on Dispatchers.Default (real threads) under a wall-clock withTimeout, so a deadlock fails.

private val RC_TZ = TimeZone.of("America/Sao_Paulo")
/** Sexta, 9 out 2026: the book's day; previstos are saved on it (Salvar previsto needs a date after it, D64). */
private val RC_TODAY = LocalDate(2026, 10, 9)
/** Terça, 3 nov 2026: D71 fills up to max(13 nov, 30 nov) = 30 nov; R1 still lists up to 13 nov. */
private val RC_NOV3 = LocalDate(2026, 11, 3)
private val RC_OCT = YearMonth(2026, 10)
private val RC_NOV = YearMonth(2026, 11)
private val RC_DEC = YearMonth(2026, 12)
private const val RC_NU = "com.nu.production"
/** Cota R$ 3.000,00. */
private const val RC_LIMIT = 300_000L

/** A Nubank purchase push of R$ [value] (as the bank writes it, "2.100,00") in [merchant] at noon of [date]; [n] makes key and `when` unique. */
private fun rcPush(n: Int, merchant: String, value: String, date: LocalDate): RawNotice {
    val at = date.atTime(12, 0).toInstant(RC_TZ).toEpochMilliseconds() + n
    return RawNotice(
        packageName = RC_NU, appLabel = "Nubank", key = "0|$RC_NU|$n|null|10001", postedAt = at, whenMs = at,
        title = "Compra aprovada", text = "Compra de R$ $value APROVADA em $merchant para o cartão com final 1234.",
    )
}

/** The month fields the margem honesta defines (Types.kt BudgetMonth), in cents. */
private data class Reserve(val spent: Long, val open: Long, val fixed: Long, val freeLimit: Long, val variable: Long, val remaining: Long)

private fun reserveOf(m: BudgetMonth) = Reserve(m.spent.v, m.open.v, m.fixed.v, m.freeLimit.v, m.variable.v, m.remaining.v)

/** One line of "Previstos de <mês>" (PlannedLine), reduced to what the spec fixes. */
private data class Line(val planned: PlannedId, val date: LocalDate, val cents: Long, val launched: LocalDate)

private fun linesOf(d: BudgetDetail) = d.plannedLines.map { Line(it.planned.planned, it.planned.date, it.cents.v, it.launched) }

class ReservaConcurrencyTest {
    private val files = mutableListOf<String>()
    private val opened = mutableListOf<Db>()

    /** onWrite calls of the Db of each test. */
    private val writes = AtomicInt(0)

    @AfterTest
    fun cleanup() {
        for (db in opened) runCatching { db.close() }
        opened.clear()
        for (f in files) for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            runCatching { SystemFileSystem.delete(Path(f + suffix), mustExist = false) }
        }
    }

    // withTimeout inside Dispatchers.Default uses wall-clock time, not runTest's virtual time.
    private fun conc(block: suspend CoroutineScope.() -> Unit): TestResult = runTest(timeout = 120.seconds) {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
    }

    private fun tempPath(): String {
        val p = Path(SystemTemporaryDirectory, "reserva-concurrency-${Random.nextLong().toULong()}.db").toString()
        files += p
        return p
    }

    private fun openDb(path: String): Db =
        Db(BundledSQLiteDriver(), path, onWrite = { writes.incrementAndFetch() }, timeZone = RC_TZ).also { opened += it }

    private fun closeDb(db: Db) {
        opened.remove(db)
        db.close()
    }

    private suspend fun source(db: Db) {
        assertTrue(db.addSource(RC_NU, "Nubank", RC_TODAY.atTime(8, 0).toInstant(RC_TZ).toEpochMilliseconds()))
    }

    /** A budget with cota R$ 3.000,00 created on 9 out (its first month: October). */
    private suspend fun budget(db: Db, name: String): BudgetId {
        val id = db.createBudget(name, Cents(RC_LIMIT), RC_TODAY)
        assertNotEquals(0L, id.v, "createBudget $name")
        return id
    }

    /** An ordinary manual lançamento (gasto variável) dated [date] ≤ 9 out. */
    private suspend fun spend(db: Db, budget: BudgetId, cents: Long, date: LocalDate): TransactionId {
        val saved = db.addManual(
            TransactionForm(budget = budget, tags = listOf("mercado"), total = Cents(cents), merchant = "PADARIA", date = date, time = LocalTime(10, 0)),
            RC_TODAY,
        )
        assertNotEquals(0L, saved.transaction.v, "spend on $date")
        assertEquals(0L, saved.planned.v)
        return saved.transaction
    }

    /** Salvar previsto (spec §7.3, D64): a manual form dated after 9 out writes only the previsto, first occurrence on [date]. */
    private suspend fun savePlanned(db: Db, budget: BudgetId, name: String, cents: Long, date: LocalDate, repeats: Repeats): PlannedId {
        val saved = db.addManual(
            TransactionForm(
                budget = budget, tags = listOf("casa"), total = Cents(cents), merchant = name, date = date,
                time = LocalTime(12, 0), caption = name, repeats = repeats,
            ),
            RC_TODAY,
        )
        assertEquals(0L, saved.transaction.v, "Salvar previsto writes no lançamento: $name")
        assertNotEquals(0L, saved.planned.v, "Salvar previsto: $name")
        return saved.planned
    }

    private suspend fun captureId(db: Db, raw: RawNotice): NoticeId {
        assertTrue(capture(db, raw), "captured: ${raw.key}")
        return db.pending().single { it.raw.key == raw.key }.id
    }

    /**
     * "É um previsto? Ligar" / Lançar previsto (D62): the recado's value, bank text, date and time as read, the
     * previsto's budget and name, closing [occurrence]. Nothing differs from the recado's Parsed: never an emenda.
     */
    private suspend fun launchForm(db: Db, recado: NoticeId, budget: BudgetId, occurrence: OccurrenceId, name: String): TransactionForm {
        val p = db.notice(recado).parsed
        return TransactionForm(
            budget = budget, tags = listOf("casa"), total = p.cents, merchant = p.merchant, date = p.date,
            time = p.time, caption = name, occurrence = occurrence,
        )
    }

    private suspend fun summaryOf(db: Db, budget: BudgetId, month: YearMonth, today: LocalDate): BudgetMonth =
        db.budgetsOverview(month, today, BudgetOrder.MANUAL).single { it.id == budget }.month

    /** Runs every task at once, launched in an order shuffled by [rnd]; the results come in the tasks' own order. */
    private suspend fun <T> CoroutineScope.race(rnd: Random, tasks: List<suspend () -> T>): List<T> {
        val running = MutableList<Deferred<T>?>(tasks.size) { null }
        for (i in tasks.indices.shuffled(rnd)) running[i] = async { tasks[i]() }
        return running.map { it!!.await() }
    }

    /** Spec §4: Σ passos = cota livre; §5: a month with variable 0 and margem ≥ 0 is No ritmo. */
    private fun assertStepsShareFree(m: BudgetMonth, at: String) {
        assertEquals(m.freeLimit.v, m.weeks.sumOf { it.step.v }, "Σ passos = cota livre (spec §4): $at")
    }

    // ---- The occurrence creation of D71 against every read ----

    @Test
    fun `fill racing Verbas Pauta and A caminho creates each occurrence of the month once and reserves it once`() = conc {
        val db = openDb(tempPath())
        val casa = budget(db, "Casa")
        val aluguel = savePlanned(db, casa, "Aluguel", 200_000, LocalDate(2026, 10, 28), Repeats.MONTHLY)
        val internet = savePlanned(db, casa, "Internet", 9_990, LocalDate(2026, 10, 12), Repeats.MONTHLY)
        val condominio = savePlanned(db, casa, "Condomínio", 50_000, LocalDate(2026, 11, 20), Repeats.ONCE)
        db.ensureMonths(RC_NOV)
        val v0 = db.version.value
        val w0 = writes.load()

        val seed = 71
        val rnd = Random(seed)
        val tasks: List<suspend () -> Any> = List(40) { i ->
            when (i % 5) {
                0 -> suspend { db.budgetsOverview(RC_NOV, RC_NOV3, BudgetOrder.MANUAL) }
                1 -> suspend { db.budgetDetail(casa, RC_NOV, RC_NOV3) }
                2 -> suspend { db.budgetDetail(casa, RC_OCT, RC_NOV3) }
                3 -> suspend { db.planned(RC_NOV3) }
                else -> suspend { db.pending(RC_NOV3) }
            }
        }
        val got = race(rnd, tasks)

        // November on 3 nov (M1, M2, D71): the fill reaches max(3 nov + 10 = 13 nov, end of November = 30 nov) = 30 nov,
        // so every November occurrence exists before the read: Internet 12 nov (9.990), Condomínio 20 nov (50.000, its
        // only one), Aluguel 28 nov (200.000, past the R1 window but in the month). open = 9.990 + 50.000 + 200.000 =
        // 259.990. Nothing spent: spent = fixed = variable = 0. cota livre = 300.000 − 259.990 = 40.010;
        // margem = 300.000 − 0 − 259.990 = 40.010.
        val nov = Reserve(spent = 0, open = 259_990, fixed = 0, freeLimit = 40_010, variable = 0, remaining = 40_010)
        val novLines = listOf(
            Line(internet, LocalDate(2026, 11, 12), 9_990, NO_DATE),
            Line(condominio, LocalDate(2026, 11, 20), 50_000, NO_DATE),
            Line(aluguel, LocalDate(2026, 11, 28), 200_000, NO_DATE),
        )
        // October read on 3 nov: Internet 12 out and Aluguel 28 out are still open, late, and keep reserving in their own
        // month (spec §2.13 "Sem recado", M8): open = 9.990 + 200.000 = 209.990; cota livre = margem = 300.000 − 209.990 = 90.010.
        val oct = Reserve(spent = 0, open = 209_990, fixed = 0, freeLimit = 90_010, variable = 0, remaining = 90_010)
        val octLines = listOf(
            Line(internet, LocalDate(2026, 10, 12), 9_990, NO_DATE),
            Line(aluguel, LocalDate(2026, 10, 28), 200_000, NO_DATE),
        )
        // A caminho on 3 nov (R1 unchanged by D71): open occurrences dated up to 13 nov, oldest first.
        val caminho = listOf(internet to LocalDate(2026, 10, 12), aluguel to LocalDate(2026, 10, 28), internet to LocalDate(2026, 11, 12))

        val ids = HashMap<Pair<PlannedId, LocalDate>, OccurrenceId>()
        fun sameId(p: Planned, at: String) {
            val before = ids.getOrPut(p.planned to p.date) { p.occurrence }
            assertEquals(before, p.occurrence, "one occurrence per previsto and month, the same in every read (D59): $at")
        }
        for ((i, r) in got.withIndex()) {
            val at = "seed $seed task $i"
            when (i % 5) {
                0 -> {
                    @Suppress("UNCHECKED_CAST")
                    val m = (r as List<BudgetSummary>).single { it.id == casa }.month
                    assertEquals(nov, reserveOf(m), "overview of November: every occurrence of the month reserves once: $at")
                    assertEquals(PaceStatus.ON_PACE, m.status, "variable 0 ≤ ideal and margem ≥ 0 (spec §5): $at")
                    assertStepsShareFree(m, at)
                }
                1 -> {
                    val d = r as BudgetDetail
                    assertEquals(nov, reserveOf(d.month), "detail of November: $at")
                    assertEquals(novLines, linesOf(d), "Previstos de novembro, by day, each once: $at")
                    assertTrue(d.plannedLines.none { it.planned.late }, "3 nov is before every November line: $at")
                    d.plannedLines.forEach { sameId(it.planned, at) }
                }
                2 -> {
                    val d = r as BudgetDetail
                    assertEquals(oct, reserveOf(d.month), "detail of October read on 3 nov: $at")
                    assertEquals(octLines, linesOf(d), "Previstos de outubro: $at")
                    assertTrue(d.plannedLines.all { it.planned.late }, "both October lines are before 3 nov (Planned.late): $at")
                    d.plannedLines.forEach { sameId(it.planned, at) }
                }
                3 -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = r as List<Planned>
                    assertEquals(caminho, list.map { it.planned to it.date }, "A caminho on 3 nov: $at")
                    list.forEach { sameId(it, at) }
                }
                else -> assertTrue((r as List<*>).isEmpty(), "no recado in the Pauta: $at")
            }
        }
        assertEquals(v0, db.version.value, "creating occurrences never bumps version (D71, like D30): seed $seed")
        assertEquals(w0, writes.load(), "nor calls onWrite: seed $seed")

        // On 20 nov R1 reaches 30 nov: Aluguel 28 nov and Condomínio 20 nov show, with the ids the details read.
        val later = db.planned(LocalDate(2026, 11, 20))
        for (p in later) sameId(p, "planned on 20 nov")
        assertEquals(1, later.count { it.planned == aluguel && it.date == LocalDate(2026, 11, 28) })
        assertEquals(nov, reserveOf(summaryOf(db, casa, RC_NOV, RC_NOV3)), "a second read creates nothing new")
        assertEquals(v0, db.version.value)
    }

    @Test
    fun `many Verbas reads that each have occurrences to create never deadlock and never bump version`() = conc {
        val db = openDb(tempPath())
        val casa = budget(db, "Casa")
        // Fatura, R$ 1.000,00 on day 31, Todo mês: a month without day 31 uses its last day (P8).
        val fatura = savePlanned(db, casa, "Fatura", 100_000, LocalDate(2026, 10, 31), Repeats.MONTHLY)
        val months = List(24) { RC_NOV.plus(it, DateTimeUnit.MONTH) }
        db.ensureMonths(months.last())
        val v0 = db.version.value
        val w0 = writes.load()

        // Each month read on its day 1: D71 reaches its last day, so each call may have rows to create while the
        // others read and write. Two readers only (D29): a creation that held a reader while waiting would deadlock here.
        val seed = 7171
        val rnd = Random(seed)
        val tasks: List<suspend () -> Any> = months.flatMap { m ->
            val today = m.onDay(1)
            listOf(
                suspend { db.budgetsOverview(m, today, BudgetOrder.RISK) },
                suspend { db.budgetDetail(casa, m, today) },
                suspend { db.planned(today) },
                suspend { db.pending(today) },
            )
        }
        val got = race(rnd, tasks)

        // Every month: only Fatura, open at 100.000 on its last day. cota livre = margem = 300.000 − 100.000 = 200.000.
        val each = Reserve(spent = 0, open = 100_000, fixed = 0, freeLimit = 200_000, variable = 0, remaining = 200_000)
        for ((i, r) in got.withIndex()) {
            val m = months[i / 4]
            val at = "seed $seed task $i month $m"
            when (i % 4) {
                0 -> {
                    @Suppress("UNCHECKED_CAST")
                    assertEquals(each, reserveOf((r as List<BudgetSummary>).single { it.id == casa }.month), at)
                }
                1 -> {
                    val d = r as BudgetDetail
                    assertEquals(each, reserveOf(d.month), at)
                    // P8: day 31 → the month's last day.
                    assertEquals(listOf(Line(fatura, m.onDay(m.numberOfDays), 100_000, NO_DATE)), linesOf(d), at)
                }
            }
        }
        assertEquals(v0, db.version.value, "seed $seed")
        assertEquals(w0, writes.load(), "seed $seed")
        for (m in months) {
            assertEquals(1, db.budgetDetail(casa, m, m.onDay(1)).plannedLines.size, "one Fatura per month, never two: $m")
        }
    }

    // ---- Lançar, Pular and Apagar on one occurrence while Verbas reads ----

    @Test
    fun `Lançar and Pular racing on one occurrence while Verbas reads show it reserving or closed and never both`() = conc {
        val db = openDb(tempPath())
        source(db)
        val seed = 72
        val rnd = Random(seed)

        // By hand (spec §3; M1–M4), cota 300.000 with R$ 40,00 spent on 2 out and Aluguel R$ 2.000,00 on 12 out:
        // OPEN: spent 4.000, open 200.000, fixed 0, cota livre 300.000 − 200.000 = 100.000, variable 4.000,
        //       margem 300.000 − 4.000 − 200.000 = 96.000 (spec §3's example: margem R$ 960 with R$ 40 spent).
        // LAUNCHED at R$ 2.100,00: spent 4.000 + 210.000 = 214.000, open 0, fixed 210.000, cota livre 300.000 − 210.000
        //       = 90.000, variable 214.000 − 210.000 = 4.000, margem 300.000 − 214.000 − 0 = 86.000 (M4: the R$ 100,00 more
        //       lowers the cota livre, never becomes gasto variável).
        // SKIPPED: spent 4.000, open 0, fixed 0, cota livre 300.000, variable 4.000, margem 296.000 (M2: minus the skipped).
        val open = Reserve(spent = 4_000, open = 200_000, fixed = 0, freeLimit = 100_000, variable = 4_000, remaining = 96_000)
        val launched = Reserve(spent = 214_000, open = 0, fixed = 210_000, freeLimit = 90_000, variable = 4_000, remaining = 86_000)
        val skipped = Reserve(spent = 4_000, open = 0, fixed = 0, freeLimit = 300_000, variable = 4_000, remaining = 296_000)
        val states = listOf(open, launched, skipped)

        repeat(4) { round ->
            val casa = budget(db, "Casa $round")
            spend(db, casa, 4_000, LocalDate(2026, 10, 2))
            val aluguel = savePlanned(db, casa, "Aluguel", 200_000, LocalDate(2026, 10, 12), Repeats.MONTHLY)
            val occ = db.planned(RC_TODAY).single { it.planned == aluguel && it.date == LocalDate(2026, 10, 12) }.occurrence
            val recados = List(4) { i -> captureId(db, rcPush(round * 10 + i, "IMOBILIARIA R$round N$i", "2.100,00", RC_TODAY)) }
            val forms = recados.map { launchForm(db, it, casa, occ, "Aluguel") }
            for (f in forms) assertEquals(Cents(210_000), f.total, "the recado reads R$ 2.100,00")
            val linesOpen = listOf(Line(aluguel, LocalDate(2026, 10, 12), 200_000, NO_DATE))
            // The lançamento is dated 9 out (the recado's day, same budget month as 12 out: no D72 move).
            val linesLaunched = listOf(Line(aluguel, LocalDate(2026, 10, 12), 210_000, RC_TODAY))
            assertEquals(open, reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)), "round $round before the race")

            val done = AtomicBoolean(false)
            val readers = List(3) { k ->
                async {
                    val seen = ArrayList<Int>()
                    do {
                        val at = "seed $seed round $round reader $k"
                        val m = summaryOf(db, casa, RC_OCT, RC_TODAY)
                        val s = states.indexOf(reserveOf(m))
                        if (s < 0) fail("overview: neither open, lançada nor skipped (both or neither counted): ${reserveOf(m)} $at")
                        // The smallest cota livre here is R$ 900: on 9 out week 1 (1–4 out) is closed, ≥ 900 × 4 ÷ 31 > R$ 40.
                        assertEquals(PaceStatus.ON_PACE, m.status, at)
                        if (seen.lastOrNull() != s) seen += s
                        val d = db.budgetDetail(casa, RC_OCT, RC_TODAY)
                        val ds = states.indexOf(reserveOf(d.month))
                        if (ds < 0) fail("detail: ${reserveOf(d.month)} $at")
                        val lines = linesOf(d)
                        assertEquals(listOf(linesOpen, linesLaunched, emptyList())[ds], lines, "the detail's lines agree with its month: $at")
                        assertEquals(d.month.open.v, d.plannedLines.filter { it.open }.sumOf { it.cents.v }, "Σ open lines = month.open: $at")
                        assertEquals(d.month.spent.v, d.lines.filter { !it.row.refunded }.sumOf { it.cents.v }, at)
                        if (seen.lastOrNull() != ds) seen += ds
                    } while (!done.load())
                    seen
                }
            }
            delay(5)
            val tasks: List<suspend () -> Long> =
                recados.indices.map { i -> suspend { db.accept(recados[i], forms[i]).v } } +
                    suspend {
                        val manual = forms[0].copy(merchant = "")
                        db.addManual(manual, RC_TODAY).transaction.v
                    } +
                    List(2) { suspend { if (db.skipOccurrence(occ)) -1L else 0L } }
            val got = race(rnd, tasks)
            done.store(true)
            val seen = readers.map { it.await() }
            val at = "seed $seed round $round: $got"

            val closes = got.filter { it > 0L }
            val skips = got.count { it == -1L }
            assertEquals(1, closes.size + skips, "exactly one of Lançar and Pular takes the occurrence: $at")
            val final = if (closes.isNotEmpty()) 1 else 2
            for ((k, s) in seen.withIndex()) {
                assertTrue(s.all { it == 0 || it == final }, "reader $k saw a state of the loser: $s $at")
                assertTrue(s.indexOf(final).let { f -> f < 0 || s.drop(f).all { it == final } }, "reader $k saw the occurrence reopen: $s $at")
            }
            assertEquals(states[final], reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)), at)
            if (final == 1) {
                val tx = TransactionId(closes.single())
                val row = db.transaction(tx).row
                assertTrue(row.fixed, "closing an occurrence makes it fixed (D69): $at")
                assertEquals(Cents(210_000), row.total, at)
                assertEquals(linesLaunched, linesOf(db.budgetDetail(casa, RC_OCT, RC_TODAY)), at)
                // S1 + M1: deleting the lançamento reopens the occurrence and it reserves again.
                db.deleteTransaction(tx)
                assertEquals(open, reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)), "deleted lançamento: reserves again $at")
                assertEquals(linesOpen, linesOf(db.budgetDetail(casa, RC_OCT, RC_TODAY)), at)
            } else {
                assertEquals(emptyList(), linesOf(db.budgetDetail(casa, RC_OCT, RC_TODAY)), "skipped ones are left out: $at")
                assertFalse(db.skipOccurrence(occ), at)
            }
        }
    }

    @Test
    fun `Apagar previsto while Verbas reads stops the open reserve at once and keeps the lançado one fixed`() = conc {
        val db = openDb(tempPath())
        source(db)
        db.ensureMonths(RC_NOV)
        val seed = 73
        val rnd = Random(seed)

        // By hand (spec §3, M1–M4, D69): R$ 40,00 spent on 2 out; Aluguel R$ 2.000,00 Todo mês on day 12 (open);
        // Luz R$ 150,00 Só uma vez on 15 out, lançada on 9 out at R$ 120,00 (fixed 12.000).
        // BEFORE: spent 4.000 + 12.000 = 16.000, open 200.000, fixed 12.000, cota livre 300.000 − 212.000 = 88.000,
        //         variable 4.000, margem 300.000 − 16.000 − 200.000 = 84.000.
        // AFTER Apagar Aluguel (its open occurrences stop reserving): open 0, cota livre 300.000 − 12.000 = 288.000,
        //         margem 300.000 − 16.000 = 284.000; spent, fixed, variable unchanged.
        // Apagar Luz changes nothing in the month: its lançamento stays and keeps counting, still fixed (D69).
        val before = Reserve(spent = 16_000, open = 200_000, fixed = 12_000, freeLimit = 88_000, variable = 4_000, remaining = 84_000)
        val after = Reserve(spent = 16_000, open = 0, fixed = 12_000, freeLimit = 288_000, variable = 4_000, remaining = 284_000)

        repeat(3) { round ->
            val casa = budget(db, "Casa $round")
            db.ensureMonths(RC_NOV)
            spend(db, casa, 4_000, LocalDate(2026, 10, 2))
            val aluguel = savePlanned(db, casa, "Aluguel", 200_000, LocalDate(2026, 10, 12), Repeats.MONTHLY)
            val luz = savePlanned(db, casa, "Luz", 15_000, LocalDate(2026, 10, 15), Repeats.ONCE)
            val luzOcc = db.planned(RC_TODAY).single { it.planned == luz }.occurrence
            val recado = captureId(db, rcPush(round, "ENEL R$round", "120,00", RC_TODAY))
            val tx = db.accept(recado, launchForm(db, recado, casa, luzOcc, "Luz"))
            assertNotEquals(0L, tx.v, "Luz lançada round $round")
            assertEquals(before, reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)), "round $round before")

            val done = AtomicBoolean(false)
            // Readers on 9 out and on 3 nov: the 3 nov ones also create Aluguel 12 nov (D71) while it is being deleted.
            val readers = List(4) { k ->
                async {
                    val today = if (k % 2 == 0) RC_TODAY else RC_NOV3
                    val seen = ArrayList<Int>()
                    var lastNov = Long.MAX_VALUE
                    do {
                        val at = "seed $seed round $round reader $k"
                        val r = reserveOf(summaryOf(db, casa, RC_OCT, today))
                        val s = listOf(before, after).indexOf(r)
                        if (s < 0) fail("October: neither before nor after Apagar Aluguel: $r $at")
                        if (seen.lastOrNull() != s) seen += s
                        val d = db.budgetDetail(casa, RC_OCT, today)
                        assertTrue(d.month.open.v == 200_000L || d.month.open.v == 0L, "detail open ${d.month.open} $at")
                        assertEquals(d.month.open.v, d.plannedLines.filter { it.open }.sumOf { it.cents.v }, "Σ open lines = month.open: $at")
                        assertEquals(Cents(12_000), d.month.fixed, "the lançado Luz stays fixed: $at")
                        if (today == RC_NOV3) {
                            // November on 3 nov: Aluguel 12 nov reserves 200.000 until Apagar, then nothing; never back.
                            val nov = summaryOf(db, casa, RC_NOV, today).open.v
                            assertTrue(nov == 200_000L || nov == 0L, "November open $nov $at")
                            assertTrue(nov <= lastNov, "a deleted previsto reserved again in November: $at")
                            lastNov = nov
                            db.planned(today)
                        }
                    } while (!done.load())
                    seen
                }
            }
            delay(5)
            race(rnd, listOf(suspend { db.deletePlanned(aluguel) }, suspend { db.deletePlanned(luz) }))
            done.store(true)
            val seen = readers.map { it.await() }
            val at = "seed $seed round $round"
            for ((k, s) in seen.withIndex()) assertTrue(s == listOf(0, 1) || s == listOf(0) || s == listOf(1), "reader $k went back: $s $at")

            assertEquals(after, reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)), at)
            assertEquals(after, reserveOf(summaryOf(db, casa, RC_OCT, RC_NOV3)), at)
            // Whatever the order of the fill and the delete, no occurrence of Aluguel is left to reserve in November.
            val nov = summaryOf(db, casa, RC_NOV, RC_NOV3)
            assertEquals(Reserve(0, 0, 0, RC_LIMIT, 0, RC_LIMIT), reserveOf(nov), at)
            assertEquals(emptyList(), db.budgetDetail(casa, RC_OCT, RC_NOV3).plannedLines, "every occurrence went with its previsto: $at")
            assertTrue(db.planned(RC_NOV3).none { it.planned == aluguel || it.planned == luz }, at)
            assertTrue(db.transaction(tx).row.fixed, "stays fixed when its previsto is deleted (D69): $at")
            // Its lançamento has no origin any more: deleting it reopens nothing and leaves only the R$ 40,00 —
            // spent 4.000, margem 300.000 − 4.000 = 296.000, cota livre 300.000.
            db.deleteTransaction(tx)
            assertEquals(Reserve(4_000, 0, 0, RC_LIMIT, 4_000, 296_000), reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)), at)
            assertTrue(db.planned(RC_NOV3).none { it.planned == luz }, at)
        }
    }

    // ---- Lifecycle: close + open, and the virada ----

    @Test
    fun `closing and reopening the file keeps fixed lançamentos and the open reserve`() = conc {
        val path = tempPath()
        var db = openDb(path)
        source(db)
        val casa = budget(db, "Casa")
        spend(db, casa, 4_000, LocalDate(2026, 10, 2))
        val aluguel = savePlanned(db, casa, "Aluguel", 200_000, LocalDate(2026, 10, 12), Repeats.MONTHLY)
        val internet = savePlanned(db, casa, "Internet", 9_990, LocalDate(2026, 10, 18), Repeats.MONTHLY)
        val luz = savePlanned(db, casa, "Luz", 15_000, LocalDate(2026, 10, 16), Repeats.ONCE)
        val caminho = db.planned(RC_TODAY)
        val aluguelOcc = caminho.single { it.planned == aluguel }.occurrence
        val luzOcc = caminho.single { it.planned == luz }.occurrence
        assertTrue(db.skipOccurrence(luzOcc))
        val recado = captureId(db, rcPush(1, "IMOBILIARIA", "2.100,00", RC_TODAY))
        val tx = db.accept(recado, launchForm(db, recado, casa, aluguelOcc, "Aluguel"))
        assertNotEquals(0L, tx.v)

        // By hand, October on 9 out (spec §3, M1–M4): spent = 4.000 + 210.000 = 214.000; fixed = 210.000 (Aluguel lançado
        // at R$ 2.100,00); open = 9.990 (Internet 18 out; Luz skipped reserves nothing); previstos do mês = 219.990;
        // cota livre = 300.000 − 219.990 = 80.010; variable = 214.000 − 210.000 = 4.000; margem = 300.000 − 214.000 − 9.990 = 76.010.
        val state = Reserve(spent = 214_000, open = 9_990, fixed = 210_000, freeLimit = 80_010, variable = 4_000, remaining = 76_010)
        val lines = listOf(
            Line(aluguel, LocalDate(2026, 10, 12), 210_000, RC_TODAY),
            Line(internet, LocalDate(2026, 10, 18), 9_990, NO_DATE),
        )
        suspend fun snap(d: Db) = listOf(
            d.budgetsOverview(RC_OCT, RC_TODAY, BudgetOrder.RISK),
            d.budgetDetail(casa, RC_OCT, RC_TODAY),
            d.transactions(RC_TODAY),
            d.planned(RC_TODAY),
        )
        assertEquals(state, reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)))
        assertEquals(lines, linesOf(db.budgetDetail(casa, RC_OCT, RC_TODAY)))
        val first = snap(db)
        closeDb(db)
        repeat(3) { round ->
            db = openDb(path)
            assertEquals(first, snap(db), "reopen $round: the same months, lines and lançamentos")
            assertEquals(state, reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)), "reopen $round")
            assertTrue(db.transaction(tx).row.fixed, "fixed is on disk (D69): reopen $round")
            closeDb(db)
        }

        // Reopened on the virada (1 nov): November gets Aluguel 12 nov and Internet 18 nov (D71: up to 30 nov):
        // open = 200.000 + 9.990 = 209.990; cota livre = margem = 300.000 − 209.990 = 90.010. October stays as it was:
        // Internet 18 out is late and keeps reserving there (M8).
        db = openDb(path)
        db.ensureMonths(RC_NOV)
        val nov1 = LocalDate(2026, 11, 1)
        assertEquals(Reserve(0, 209_990, 0, 90_010, 0, 90_010), reserveOf(summaryOf(db, casa, RC_NOV, nov1)))
        assertEquals(state, reserveOf(db.budgetDetail(casa, RC_OCT, nov1).month))
        assertTrue(db.budgetDetail(casa, RC_OCT, nov1).plannedLines.single { it.open }.planned.late)

        // S1 + M1 after reopening: deleting the lançamento brings Aluguel 12 out back to reserving at the previsto's value:
        // spent 4.000, open 200.000 + 9.990 = 209.990, fixed 0, cota livre 90.010, margem 300.000 − 4.000 − 209.990 = 86.010.
        db.deleteTransaction(tx)
        val reopened = Reserve(spent = 4_000, open = 209_990, fixed = 0, freeLimit = 90_010, variable = 4_000, remaining = 86_010)
        assertEquals(reopened, reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)))
        val second = snap(db)
        closeDb(db)
        db = openDb(path)
        assertEquals(second, snap(db), "the reopened occurrence is derived from what is on disk")
        assertEquals(reopened, reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)))
    }

    @Test
    fun `the virada between calls moves the reserve to the new month and a late one stays in its own`() = conc {
        val db = openDb(tempPath())
        source(db)
        val casa = budget(db, "Casa")
        val aluguel = savePlanned(db, casa, "Aluguel", 200_000, LocalDate(2026, 10, 28), Repeats.MONTHLY)
        val internet = savePlanned(db, casa, "Internet", 9_990, LocalDate(2026, 11, 3), Repeats.MONTHLY)
        db.ensureMonths(RC_NOV)
        val v0 = db.version.value
        val w0 = writes.load()

        // 9 out: October reserves Aluguel 28 out (open = 200.000; cota livre = margem = 100.000). November has only
        // Internet 3 nov (its first occurrence, saved): on 9 out the fill stops at max(19 out, 31 out) = 31 out, so Aluguel
        // 28 nov doesn't exist yet: open = 9.990, cota livre = margem = 300.000 − 9.990 = 290.010.
        val octOpen = Reserve(spent = 0, open = 200_000, fixed = 0, freeLimit = 100_000, variable = 0, remaining = 100_000)
        val novEarly = Reserve(spent = 0, open = 9_990, fixed = 0, freeLimit = 290_010, variable = 0, remaining = 290_010)
        // From 1 nov the fill reaches 30 nov: Aluguel 28 nov + Internet 3 nov = 209.990; cota livre = margem = 90.010.
        val novFull = Reserve(spent = 0, open = 209_990, fixed = 0, freeLimit = 90_010, variable = 0, remaining = 90_010)
        assertEquals(octOpen, reserveOf(summaryOf(db, casa, RC_OCT, RC_TODAY)))
        assertEquals(novEarly, reserveOf(db.budgetDetail(casa, RC_NOV, RC_TODAY).month))

        // The virada between calls: reads on 31 out (fill up to max(10 nov, 31 out) = 10 nov) and on 1 nov (up to 30 nov) at once.
        val seed = 74
        val rnd = Random(seed)
        val oct31 = LocalDate(2026, 10, 31)
        val nov1 = LocalDate(2026, 11, 1)
        val tasks: List<suspend () -> Pair<LocalDate, Pair<Reserve, Reserve>>> = List(32) { i ->
            val today = if (i % 2 == 0) oct31 else nov1
            suspend {
                val o = if (i % 4 < 2) summaryOf(db, casa, RC_OCT, today) else db.budgetDetail(casa, RC_OCT, today).month
                val n = if (i % 4 < 2) summaryOf(db, casa, RC_NOV, today) else db.budgetDetail(casa, RC_NOV, today).month
                today to (reserveOf(o) to reserveOf(n))
            }
        }
        for ((i, r) in race(rnd, tasks).withIndex()) {
            val (today, months) = r
            val at = "seed $seed task $i on $today"
            assertEquals(octOpen, months.first, "October keeps Aluguel 28 out, open or late: $at")
            if (today == nov1) {
                assertEquals(novFull, months.second, "on 1 nov every November occurrence reserves (D71): $at")
            } else {
                assertTrue(months.second == novEarly || months.second == novFull, "November on 31 out: Aluguel 28 nov once or not yet: ${months.second} $at")
            }
        }
        assertEquals(novFull, reserveOf(summaryOf(db, casa, RC_NOV, nov1)))
        assertEquals(v0, db.version.value, "no read bumped: seed $seed")
        assertEquals(w0, writes.load())

        // 3 nov: Aluguel 28 out is late and lançado with a recado of 3 nov at R$ 2.000,00 (M8, D72): the lançamento
        // takes 28 out with the recado's time, not corrected, fixed. October: spent = fixed = 200.000, open 0,
        // cota livre = 300.000 − 200.000 = 100.000, variable 0, margem 300.000 − 200.000 − 0 = 100.000.
        // November doesn't change: the recado's day doesn't make it count there.
        val late = db.planned(RC_NOV3).single { it.planned == aluguel && it.date == LocalDate(2026, 10, 28) }
        assertTrue(late.late)
        val recado = captureId(db, rcPush(1, "IMOBILIARIA", "2.000,00", RC_NOV3))
        val v1 = db.version.value
        val tx = db.accept(recado, launchForm(db, recado, casa, late.occurrence, "Aluguel"))
        assertNotEquals(0L, tx.v)
        val row = db.transaction(tx).row
        assertEquals(LocalDate(2026, 10, 28) to LocalTime(12, 0), row.date to row.time, "the occurrence's day, the recado's time (D72)")
        assertFalse(row.corrected, "the move is not an emenda (M8)")
        assertTrue(row.fixed, "D69")
        val octLaunched = Reserve(spent = 200_000, open = 0, fixed = 200_000, freeLimit = 100_000, variable = 0, remaining = 100_000)
        assertEquals(octLaunched, reserveOf(summaryOf(db, casa, RC_OCT, RC_NOV3)))
        assertEquals(listOf(Line(aluguel, LocalDate(2026, 10, 28), 200_000, LocalDate(2026, 10, 28))), linesOf(db.budgetDetail(casa, RC_OCT, RC_NOV3)))
        assertEquals(novFull, reserveOf(summaryOf(db, casa, RC_NOV, RC_NOV3)))
        assertEquals(v1 + 1, db.version.value, "only Lançar bumped")

        // Next virada, 1 dez: December gets Internet 3 dez + Aluguel 28 dez = 209.990 (D71); November's occurrences, never
        // lançadas, are late and keep reserving in November (M8); October stays lançado.
        db.ensureMonths(RC_DEC)
        val dec1 = LocalDate(2026, 12, 1)
        assertEquals(novFull, reserveOf(summaryOf(db, casa, RC_DEC, dec1)))
        val novDetail = db.budgetDetail(casa, RC_NOV, dec1)
        assertEquals(novFull, reserveOf(novDetail.month))
        assertEquals(
            listOf(Line(internet, LocalDate(2026, 11, 3), 9_990, NO_DATE), Line(aluguel, LocalDate(2026, 11, 28), 200_000, NO_DATE)),
            linesOf(novDetail),
        )
        assertTrue(novDetail.plannedLines.all { it.planned.late }, "both before 1 dez")
        assertEquals(
            listOf(Line(internet, LocalDate(2026, 12, 3), 9_990, NO_DATE), Line(aluguel, LocalDate(2026, 12, 28), 200_000, NO_DATE)),
            linesOf(db.budgetDetail(casa, RC_DEC, dec1)),
        )
        assertEquals(octLaunched, reserveOf(summaryOf(db, casa, RC_OCT, dec1)))
        assertEquals(v1 + 1, db.version.value, "the December fill didn't bump")
    }
}
