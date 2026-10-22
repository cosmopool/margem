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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Concorrência e ciclo de vida do previsto (spec §2.8, §2.13, §7.2, §7.3, §7.4, S1; architecture D6, D14,
// D21, D29, D30, D59–D64, §5), through the public API only. Lançar previsto (accept with an occurrence),
// the manual close (addManual with an occurrence), Pular este mês (skipOccurrence), Apagar previsto
// (deletePlanned) and Apagar lançamento (deleteTransaction) race on the same occurrence: exactly one closes
// it, never two lançamentos for one occurrence, never a lançamento on a skipped one. The occurrence creation
// of planned(today)/pending(today) (R1: a write that doesn't bump version) races itself and real writes.
// Lifecycle: reopening the same file, exportTo + importFrom. Open/closed is derived (D59): an occurrence is
// open while not skipped and no lançamento points at it, so deleting the one lançamento must reopen it — the
// observable proof that only one pointed at it.
//
// Work runs on Dispatchers.Default (real threads) under a wall-clock withTimeout, so a deadlock fails.

private val PC_TZ = TimeZone.of("America/Sao_Paulo")
/** Sexta, 9 out 2026: the day previstos are saved (Salvar previsto compares with it, D64). */
private val PC_TODAY = LocalDate(2026, 10, 9)
/** Segunda, 9 nov 2026: R1 creates up to 19 nov; October occurrences still open are late (P10). */
private val PC_NOV = LocalDate(2026, 11, 9)
private const val PC_NU = "com.nu.production"
private val PC_UNKNOWN_OCCURRENCE = OccurrenceId(987_654)
private val PC_UNKNOWN_PLANNED = PlannedId(987_654)

/** A Nubank purchase push of R$ 99,90 in [merchant] at noon of [date] (São Paulo); [n] makes key and `when` unique. */
private fun pcPush(n: Int, merchant: String, date: LocalDate): RawNotice {
    val at = date.atTime(12, 0).toInstant(PC_TZ).toEpochMilliseconds() + n
    return RawNotice(
        packageName = PC_NU, appLabel = "Nubank", key = "0|$PC_NU|$n|null|10001", postedAt = at, whenMs = at,
        title = "Compra aprovada", text = "Compra de R$ 99,90 APROVADA em $merchant para o cartão com final 1234.",
    )
}

/**
 * The form a screen builds for Lançar previsto (D62): budget and tags of the previsto, the name as legenda,
 * value, bank text, date and time of the recado, and the occurrence it closes.
 */
private fun pcAcceptForm(budget: BudgetId, merchant: String, date: LocalDate, occurrence: OccurrenceId, name: String) = TransactionForm(
    budget = budget, tags = listOf("casa"), total = Cents(9990), merchant = merchant, date = date,
    time = LocalTime(12, 0), caption = name, occurrence = occurrence,
)

/** Lançar sem recado / "É um previsto? Ligar" in the manual form (R6b): closes [occurrence], no bank text. */
private fun pcManualForm(budget: BudgetId, occurrence: OccurrenceId, date: LocalDate, name: String) = TransactionForm(
    budget = budget, tags = listOf("casa"), total = Cents(9990), merchant = "", date = date,
    time = LocalTime(12, 0), caption = name, occurrence = occurrence,
)

class PrevistoConcurrencyTest {
    private val files = mutableListOf<String>()
    private val opened = mutableListOf<Db>()

    /** onWrite calls of the main Db of each test. */
    private val writes = AtomicInt(0)

    @AfterTest
    fun cleanup() {
        for (db in opened) runCatching { db.close() }
        opened.clear()
        for (f in files) for (suffix in listOf("", "-wal", "-shm", "-journal", ".import")) {
            runCatching { SystemFileSystem.delete(Path(f + suffix), mustExist = false) }
        }
    }

    // withTimeout inside Dispatchers.Default uses wall-clock time, not runTest's virtual time.
    private fun conc(block: suspend CoroutineScope.() -> Unit): TestResult = runTest(timeout = 120.seconds) {
        withContext(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
    }

    private fun tempPath(): String {
        val p = Path(SystemTemporaryDirectory, "PrevistoConcurrency-${Random.nextLong().toULong()}.db").toString()
        files += p
        return p
    }

    private fun openDb(path: String, counter: AtomicInt = writes): Db =
        Db(BundledSQLiteDriver(), path, onWrite = { counter.incrementAndFetch() }, timeZone = PC_TZ).also { opened += it }

    private fun closeDb(db: Db) {
        opened.remove(db)
        db.close()
    }

    /** The Nubank source and one budget, Casa. */
    private suspend fun seeded(db: Db): BudgetId {
        assertTrue(db.addSource(PC_NU, "Nubank", PC_TODAY.atTime(8, 0).toInstant(PC_TZ).toEpochMilliseconds()))
        val budget = db.createBudget("Casa", Cents(500_000), PC_TODAY)
        assertNotEquals(0L, budget.v)
        return budget
    }

    private suspend fun captureId(db: Db, raw: RawNotice): NoticeId {
        assertTrue(capture(db, raw), "captured: ${raw.key}")
        return db.pending().single { it.raw.key == raw.key }.id
    }

    /** Salvar previsto (spec §7.3, D64): a manual form dated after today writes only the previsto. */
    private suspend fun savePlanned(db: Db, budget: BudgetId, name: String, date: LocalDate, repeats: Repeats): PlannedId {
        val saved = db.addManual(
            TransactionForm(
                budget = budget, tags = listOf("casa"), total = Cents(9990), merchant = name, date = date,
                time = LocalTime(12, 0), caption = name, repeats = repeats,
            ),
            PC_TODAY,
        )
        assertEquals(0L, saved.transaction.v, "Salvar previsto writes no lançamento: $name")
        assertNotEquals(0L, saved.planned.v, "Salvar previsto: $name")
        return saved.planned
    }

    /** The open occurrence of [planned] dated [date], as planned([today]) lists it. */
    private suspend fun occurrenceOf(db: Db, today: LocalDate, planned: PlannedId, date: LocalDate): Planned =
        db.planned(today).single { it.planned == planned && it.date == date }

    /** Runs every task at once, launched in an order shuffled by [rnd]; the results come in the tasks' own order. */
    private suspend fun <T> CoroutineScope.race(rnd: Random, tasks: List<suspend () -> T>): List<T> {
        val running = MutableList<Deferred<T>?>(tasks.size) { null }
        for (i in tasks.indices.shuffled(rnd)) running[i] = async { tasks[i]() }
        return running.map { it!!.await() }
    }

    private fun bit(b: Boolean): Long = if (b) 1L else 0L

    // ---- One occurrence, many closes ----

    @Test
    fun `concurrent Lançar previsto and manual closes of one occurrence close it exactly once`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val seed = 59
        val rnd = Random(seed)
        repeat(5) { round ->
            val day = 10 + round
            val name = "Internet $round"
            val p = savePlanned(db, budget, name, LocalDate(2026, 10, day), Repeats.MONTHLY)
            // R1 on 9 nov: dated up to 19 nov, so the October occurrence (late) and the November one both exist.
            val oct = occurrenceOf(db, PC_NOV, p, LocalDate(2026, 10, day))
            val nov = occurrenceOf(db, PC_NOV, p, LocalDate(2026, 11, day))
            val merchants = List(8) { "LOJA ${round * 10 + it}" }
            val recados = merchants.mapIndexed { i, m -> captureId(db, pcPush(round * 100 + i, m, PC_NOV)) }
            val tx0 = db.transactions().size
            val v0 = db.version.value
            val w0 = writes.load()

            val tasks: List<suspend () -> Long> =
                recados.indices.map { i -> suspend { db.accept(recados[i], pcAcceptForm(budget, merchants[i], PC_NOV, oct.occurrence, name)).v } } +
                    List(2) { suspend { db.addManual(pcManualForm(budget, oct.occurrence, PC_NOV, name), PC_NOV).transaction.v } }
            val got = race(rnd, tasks)
            val at = "seed $seed round $round: $got"

            // TransactionForm.occurrence: refused when the occurrence is not open (closed by another lançamento).
            val winners = got.indices.filter { got[it] != 0L }
            assertEquals(1, winners.size, "exactly one closes the occurrence: $at")
            val w = winners.single()
            assertEquals(v0 + 1, db.version.value, "one real write bumps, the refused ones don't (Db.version): $at")
            assertEquals(w0 + 1, writes.load(), "onWrite follows the bump (D21): $at")
            assertEquals(tx0 + 1, db.transactions().size, "one lançamento for one occurrence: $at")
            val open = db.planned(PC_NOV)
            assertTrue(open.none { it.occurrence == oct.occurrence }, "closed by the winner: $at")
            val next = open.single { it.occurrence == nov.occurrence }
            if (w < recados.size) {
                // accept (re)writes the previsto's link from the recado's fonte and bank text; a refused one writes nothing.
                assertTrue(next.linked, at)
                assertEquals(merchants[w], next.linkText, "the link is the winner's: $at")
                assertEquals("Nubank", next.linkSource, at)
                recados.forEachIndexed { i, n ->
                    val expected = if (i == w) NoticeStatus.PROCESSED else NoticeStatus.PENDING
                    assertEquals(expected, db.notice(n).status, "$at recado $i")
                }
            } else {
                // R6b: the manual close writes no link.
                assertFalse(next.linked, at)
                assertEquals("", next.linkText, at)
                recados.forEach { assertEquals(NoticeStatus.PENDING, db.notice(it).status, at) }
            }

            // S1: deleting the one lançamento reopens it, waiting since its day (P10).
            db.deleteTransaction(TransactionId(got[w]))
            val back = db.planned(PC_NOV).single { it.occurrence == oct.occurrence }
            assertEquals(LocalDate(2026, 10, day), back.date, at)
            assertTrue(back.late, "10+round out < 9 nov: $at")
            assertEquals(tx0, db.transactions().size, at)
        }
    }

    @Test
    fun `Lançar previsto racing Pular este mês lets exactly one of them close the occurrence`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val seed = 60
        val rnd = Random(seed)
        repeat(8) { round ->
            val date = LocalDate(2026, 10, 10 + round)
            val name = "Luz $round"
            val p = savePlanned(db, budget, name, date, Repeats.ONCE)
            val occ = occurrenceOf(db, PC_TODAY, p, date)
            val merchant = "ENEL $round"
            val n = captureId(db, pcPush(1000 + round, merchant, PC_TODAY))
            val tx0 = db.transactions().size
            val v0 = db.version.value
            val w0 = writes.load()

            val got = race(
                rnd,
                listOf(
                    suspend { db.accept(n, pcAcceptForm(budget, merchant, PC_TODAY, occ.occurrence, name)).v },
                    suspend { bit(db.skipOccurrence(occ.occurrence)) },
                    suspend { bit(db.skipOccurrence(occ.occurrence)) },
                    suspend { db.addManual(pcManualForm(budget, occ.occurrence, PC_TODAY, name), PC_TODAY).transaction.v },
                ),
            )
            val at = "seed $seed round $round: $got"
            assertEquals(1, got.count { it != 0L }, "exactly one of Lançar, Pular, Pular, manual wins: $at")
            assertEquals(v0 + 1, db.version.value, at)
            assertEquals(w0 + 1, writes.load(), at)
            assertTrue(db.planned(PC_TODAY).none { it.occurrence == occ.occurrence }, "no longer open: $at")

            if (got[1] != 0L || got[2] != 0L) {
                // Pular (P10): the occurrence won't come back, and nothing can close it any more.
                assertEquals(tx0, db.transactions().size, "Pular writes no lançamento: $at")
                assertEquals(NoticeStatus.PENDING, db.notice(n).status, at)
                val v1 = db.version.value
                assertEquals(0L, db.accept(n, pcAcceptForm(budget, merchant, PC_TODAY, occ.occurrence, name)).v, "skipped: Lançar refused: $at")
                assertEquals(Saved(), db.addManual(pcManualForm(budget, occ.occurrence, PC_TODAY, name), PC_TODAY), "skipped: manual refused: $at")
                assertFalse(db.skipOccurrence(occ.occurrence), "skipped is not open: $at")
                assertEquals(v1, db.version.value, "refused writes don't bump: $at")
            } else {
                val tx = TransactionId(if (got[0] != 0L) got[0] else got[3])
                assertEquals(tx0 + 1, db.transactions().size, at)
                assertFalse(db.skipOccurrence(occ.occurrence), "closed is not open: Pular refused: $at")
                // S1: the lançamento was never on a skipped occurrence — deleting it brings it back open.
                db.deleteTransaction(tx)
                assertEquals(listOf(occ.occurrence), db.planned(PC_TODAY).filter { it.planned == p }.map { it.occurrence }, at)
                assertTrue(db.skipOccurrence(occ.occurrence), "open again: Pular works: $at")
                assertTrue(db.planned(PC_TODAY).none { it.planned == p }, "Só uma vez: nothing comes back: $at")
            }
        }
    }

    @Test
    fun `deleting the lançamento of an occurrence racing a new close and a skip closes it at most once`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val seed = 62
        val rnd = Random(seed)
        repeat(8) { round ->
            val date = LocalDate(2026, 10, 10 + round)
            val name = "Gás $round"
            val merchant = "ULTRAGAZ $round"
            val p = savePlanned(db, budget, name, date, Repeats.ONCE)
            val occ = occurrenceOf(db, PC_TODAY, p, date)
            val a = captureId(db, pcPush(2000 + round, merchant, PC_TODAY))
            val b = captureId(db, pcPush(2100 + round, merchant, PC_TODAY))
            val txA = db.accept(a, pcAcceptForm(budget, merchant, PC_TODAY, occ.occurrence, name))
            assertNotEquals(0L, txA.v)
            val v0 = db.version.value
            val w0 = writes.load()

            // Any order: a close or a skip before the delete is refused (closed); after it, the first one wins.
            val got = race(
                rnd,
                listOf(
                    suspend {
                        db.deleteTransaction(txA)
                        1L
                    },
                    suspend { db.accept(b, pcAcceptForm(budget, merchant, PC_TODAY, occ.occurrence, name)).v },
                    suspend { bit(db.skipOccurrence(occ.occurrence)) },
                ),
            )
            val at = "seed $seed round $round: $got"
            val x = got[1]
            val skipped = got[2] != 0L
            assertFalse(x != 0L && skipped, "a lançamento on a skipped occurrence: $at")
            assertEquals(NoticeStatus.PENDING, db.notice(a).status, "§2.8: its recado is back in the Pauta: $at")
            assertEquals(v0 + 1 + bit(x != 0L) + bit(skipped), db.version.value, at)
            assertEquals(w0 + 1 + bit(x != 0L) + bit(skipped), writes.load().toLong(), at)
            val open = db.planned(PC_TODAY).filter { it.planned == p }.map { it.occurrence }
            when {
                x != 0L -> {
                    assertEquals(emptyList(), open, at)
                    assertEquals(NoticeStatus.PROCESSED, db.notice(b).status, at)
                    assertEquals(1, db.transactions().count { it.id == TransactionId(x) }, at)
                    db.deleteTransaction(TransactionId(x))
                    assertEquals(listOf(occ.occurrence), db.planned(PC_TODAY).filter { it.planned == p }.map { it.occurrence }, "S1: $at")
                    assertTrue(db.skipOccurrence(occ.occurrence), at)
                }
                skipped -> {
                    assertEquals(emptyList(), open, at)
                    assertEquals(NoticeStatus.PENDING, db.notice(b).status, at)
                    assertEquals(0L, db.accept(b, pcAcceptForm(budget, merchant, PC_TODAY, occ.occurrence, name)).v, at)
                }
                else -> {
                    assertEquals(listOf(occ.occurrence), open, "S1: open again, the same occurrence: $at")
                    assertEquals(NoticeStatus.PENDING, db.notice(b).status, at)
                    assertTrue(db.skipOccurrence(occ.occurrence), at)
                }
            }
            assertTrue(db.transactions().none { it.id == txA }, at)
        }
    }

    @Test
    fun `Apagar previsto racing Lançar previsto and Pular este mês leaves no occurrence and no origin behind`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val seed = 63
        val rnd = Random(seed)
        repeat(8) { round ->
            val date = LocalDate(2026, 10, 10 + round)
            val name = "Streaming $round"
            val merchant = "NETFLIX $round"
            val p = savePlanned(db, budget, name, date, Repeats.MONTHLY)
            val occ = occurrenceOf(db, PC_TODAY, p, date)
            val n = captureId(db, pcPush(3000 + round, merchant, PC_TODAY))
            val tx0 = db.transactions().size
            val v0 = db.version.value
            val w0 = writes.load()

            val got = race(
                rnd,
                listOf(
                    suspend {
                        db.deletePlanned(p)
                        1L
                    },
                    suspend { db.accept(n, pcAcceptForm(budget, merchant, PC_TODAY, occ.occurrence, name)).v },
                    suspend { bit(db.skipOccurrence(occ.occurrence)) },
                ),
            )
            val at = "seed $seed round $round: $got"
            val x = got[1]
            val skipped = got[2] != 0L
            assertFalse(x != 0L && skipped, "both closed it: $at")
            // Apagar previsto: the rule and every occurrence go; R1 creates nothing for it in later months.
            assertTrue(db.planned(PC_TODAY).none { it.planned == p }, at)
            assertTrue(db.planned(PC_NOV).none { it.planned == p }, "no later month comes back: $at")
            assertEquals(v0 + 1 + bit(x != 0L) + bit(skipped), db.version.value, at)
            assertEquals(w0 + 1 + bit(x != 0L) + bit(skipped), writes.load().toLong(), at)
            if (x != 0L) {
                // Its lançamentos stay and keep counting, with no origin: deleting one reopens nothing.
                assertEquals(tx0 + 1, db.transactions().size, at)
                assertEquals(NoticeStatus.PROCESSED, db.notice(n).status, at)
                db.deleteTransaction(TransactionId(x))
                assertTrue(db.planned(PC_NOV).none { it.planned == p }, "a lançamento with a dangling origin reopened something: $at")
                assertEquals(NoticeStatus.PENDING, db.notice(n).status, at)
            } else {
                assertEquals(tx0, db.transactions().size, at)
                assertEquals(NoticeStatus.PENDING, db.notice(n).status, at)
            }
            val v1 = db.version.value
            db.deletePlanned(p)
            assertEquals(v1, db.version.value, "Apagar again: unknown, nothing changes, no bump: $at")
        }
    }

    // ---- Occurrence creation (R1) ----

    @Test
    fun `planned and pending racing with different todays create each occurrence once and never bump version`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val internet = savePlanned(db, budget, "Internet", LocalDate(2026, 10, 15), Repeats.MONTHLY)
        val aluguel = savePlanned(db, budget, "Aluguel", LocalDate(2026, 10, 12), Repeats.ONCE)
        val condominio = savePlanned(db, budget, "Condomínio", LocalDate(2026, 11, 5), Repeats.MONTHLY)
        val fatura = savePlanned(db, budget, "Fatura", LocalDate(2026, 10, 31), Repeats.MONTHLY)
        val presente = savePlanned(db, budget, "Presente", LocalDate(2026, 12, 25), Repeats.ONCE)
        captureId(db, pcPush(1, "PADARIA", PC_TODAY))
        val v0 = db.version.value
        val w0 = writes.load()

        val dec31 = LocalDate(2026, 12, 31)
        // Few distinct todays, so many calls create the same rows at the same time.
        val todays = listOf(PC_TODAY, LocalDate(2026, 10, 20), LocalDate(2026, 11, 4), LocalDate(2026, 11, 25), LocalDate(2026, 12, 21), dec31)
        val seed = 64
        val rnd = Random(seed)
        val tasks: List<suspend () -> List<Planned>> = List(48) { i ->
            val t = todays[rnd.nextInt(todays.size)]
            when (i % 3) {
                0 -> suspend { db.planned(t) }
                1 -> suspend {
                    db.pending(t)
                    emptyList()
                }
                else -> suspend {
                    db.pending()
                    db.planned(t)
                }
            }
        }
        val seen = race(rnd, tasks)

        for ((i, list) in seen.withIndex()) {
            val at = "seed $seed task $i: ${list.map { it.planned.v to it.date }}"
            assertEquals(list.size, list.map { it.occurrence }.toSet().size, "an occurrence listed twice: $at")
            assertEquals(list.size, list.map { Triple(it.planned, it.date.year, it.date.month) }.toSet().size, "two rows for one previsto and month (D59): $at")
            assertEquals(list.sortedWith(compareBy<Planned>({ it.date }, { it.occurrence.v })), list, "oldest date first, ties by id: $at")
        }
        assertEquals(v0, db.version.value, "creating occurrences never bumps (R1, D30): seed $seed")
        assertEquals(w0, writes.load(), "nor calls onWrite (D6): seed $seed")

        // By hand, R1 on 31 dez: every occurrence dated up to 10 jan 2027; nothing closed, so every one is open.
        // Só uma vez never gets a second one; dia 31 uses the month's last day (P8); 15 jan is past the window.
        val expected = listOf(
            aluguel to LocalDate(2026, 10, 12),
            internet to LocalDate(2026, 10, 15),
            fatura to LocalDate(2026, 10, 31),
            condominio to LocalDate(2026, 11, 5),
            internet to LocalDate(2026, 11, 15),
            fatura to LocalDate(2026, 11, 30),
            condominio to LocalDate(2026, 12, 5),
            internet to LocalDate(2026, 12, 15),
            presente to LocalDate(2026, 12, 25),
            fatura to LocalDate(2026, 12, 31),
            condominio to LocalDate(2027, 1, 5),
        )
        val final = db.planned(dec31)
        assertEquals(expected, final.map { it.planned to it.date }, "seed $seed")
        assertEquals(final.map { it.date < dec31 }, final.map { it.late }, "late = date before today (Planned.late)")
        val ids = final.associate { (it.planned to it.date) to it.occurrence }
        for ((i, list) in seen.withIndex()) for (row in list) {
            assertEquals(ids[row.planned to row.date], row.occurrence, "seed $seed task $i: one row per previsto and month, the same in every read")
        }
        assertEquals(final, db.planned(dec31), "a second read creates nothing")
        assertEquals(v0, db.version.value)
        assertEquals(w0, writes.load())
    }

    @Test
    fun `occurrence creation racing Pular and Lançar previsto bumps version only for the real writes`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val ps = (0 until 6).map { savePlanned(db, budget, "Conta $it", LocalDate(2026, 10, 10 + it), Repeats.MONTHLY) }
        val oct = ps.mapIndexed { i, p -> occurrenceOf(db, PC_TODAY, p, LocalDate(2026, 10, 10 + i)) }
        // Two recados for each of Conta 3..5, with their own bank texts.
        val merchants = (3 until 6).map { i -> listOf("CONTA ${i}A", "CONTA ${i}B") }
        val recados = merchants.mapIndexed { j, pair -> pair.mapIndexed { k, m -> captureId(db, pcPush(4000 + j * 10 + k, m, PC_TODAY)) } }
        val v0 = db.version.value
        val w0 = writes.load()
        val seed = 65
        val rnd = Random(seed)
        val nov20 = LocalDate(2026, 11, 20)

        // Two Pular on each of Conta 0..2 (October), two Lançar previsto on each of Conta 3..5 (October), while
        // 24 reads on 9 nov and 20 nov create the November occurrences.
        val writeTasks: List<suspend () -> Long> =
            (0 until 3).flatMap { i -> List(2) { suspend { bit(db.skipOccurrence(oct[i].occurrence)) } } } +
                (0 until 3).flatMap { j ->
                    (0 until 2).map { k ->
                        suspend { db.accept(recados[j][k], pcAcceptForm(budget, merchants[j][k], PC_TODAY, oct[3 + j].occurrence, "Conta ${3 + j}")).v }
                    }
                }
        val readTasks: List<suspend () -> List<Planned>> = List(24) { i ->
            val t = if (i % 2 == 0) PC_NOV else nov20
            if (i % 3 == 0) {
                suspend {
                    db.pending(t)
                    emptyList()
                }
            } else {
                suspend { db.planned(t) }
            }
        }
        val reads = async { race(Random(seed + 1), readTasks) }
        val wrote = race(rnd, writeTasks)
        val seen = reads.await()
        val at = "seed $seed: $wrote"

        for (i in 0 until 6) assertEquals(1, wrote.subList(2 * i, 2 * i + 2).count { it != 0L }, "occurrence ${oct[i].date}: one of two wins: $at")
        assertEquals(v0 + 6, db.version.value, "3 Pular + 3 Lançar; creation and refused writes never bump: $at")
        assertEquals(w0 + 6, writes.load(), at)

        // By hand: on 20 nov R1 reaches 30 nov; every October occurrence is closed (skipped or launched).
        val final = db.planned(nov20)
        assertEquals(ps.indices.map { ps[it] to LocalDate(2026, 11, 10 + it) }, final.map { it.planned to it.date }, at)
        assertTrue(final.all { it.late }, "10..15 nov < 20 nov: $at")
        for (i in 0 until 3) assertFalse(final[i].linked, "Pular writes no link: $at")
        for (j in 0 until 3) {
            val k = if (wrote[6 + 2 * j] != 0L) 0 else 1
            assertTrue(final[3 + j].linked, at)
            assertEquals(merchants[j][k], final[3 + j].linkText, "the winning recado's bank text: $at")
            assertEquals(NoticeStatus.PROCESSED, db.notice(recados[j][k]).status, at)
            assertEquals(NoticeStatus.PENDING, db.notice(recados[j][1 - k]).status, at)
        }
        val known = (oct + final).associate { (it.planned to it.date) to it.occurrence }
        for ((i, list) in seen.withIndex()) {
            assertEquals(list.size, list.map { Triple(it.planned, it.date.year, it.date.month) }.toSet().size, "seed $seed read $i: two rows for one month")
            for (row in list) assertEquals(known[row.planned to row.date], row.occurrence, "seed $seed read $i: ${row.date}")
        }
    }

    // ---- Snapshots ----

    private data class Linked(val occurrence: OccurrenceId, val recado: NoticeId, val merchant: String, val name: String)

    /**
     * [n] previstos made by Lançar recado with Todo mês on 9 out (D64), linked to the recado's bank text, each
     * with a 9 nov recado of the same bank text pending and recognized (D60) on 9 nov.
     */
    private suspend fun linkedRounds(db: Db, budget: BudgetId, n: Int): List<Linked> {
        val all = (0 until n).map { r ->
            val merchant = "ACADEMIA $r"
            val name = "Academia $r"
            val first = captureId(db, pcPush(5000 + r, merchant, PC_TODAY))
            val tx = db.accept(
                first,
                TransactionForm(
                    budget = budget, tags = listOf("casa"), total = Cents(9990), merchant = merchant, date = PC_TODAY,
                    time = LocalTime(12, 0), caption = name, repeats = Repeats.MONTHLY,
                ),
            )
            assertNotEquals(0L, tx.v, "Lançar with Todo mês: $name")
            // D64: day 9, first occurrence in the month after 9 out's: 9 nov, inside the window of 9 nov (R1).
            val occ = db.planned(PC_NOV).single { it.name == name }
            assertEquals(LocalDate(2026, 11, 9), occ.date, name)
            assertTrue(occ.linked, name)
            assertEquals(merchant, occ.linkText, name)
            Linked(occ.occurrence, captureId(db, pcPush(5100 + r, merchant, PC_NOV)), merchant, name)
        }
        val pauta = db.pending(PC_NOV)
        for (l in all) assertEquals(l.occurrence, pauta.single { it.id == l.recado }.planned.occurrence, "D60: ${l.name} recognized")
        return all
    }

    @Test
    fun `a recognized recado and its occurrence leave together while Lançar previsto runs`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val links = linkedRounds(db, budget, 6)
        val occurrences = links.map { it.occurrence }.toSet()
        val recados = links.map { it.recado }.toSet()
        val v0 = db.version.value
        val w0 = writes.load()
        val done = AtomicBoolean(false)
        val passes = AtomicInt(0)

        // Lançar closes the occurrence and processes the recado in one write: one snapshot sees both or neither (D29).
        val readers = List(3) { k ->
            launch {
                var lastVersion = -1L
                var lastPauta: Set<NoticeId>? = null
                var lastOpen: Set<OccurrenceId>? = null
                do {
                    val version = db.version.value
                    assertTrue(version >= lastVersion, "reader $k: version went back")
                    lastVersion = version
                    val rows = if (k == 1) db.pending() else db.pending(PC_NOV)
                    for (l in links) {
                        val row = rows.singleOrNull { it.id == l.recado } ?: continue
                        assertEquals(l.occurrence, row.planned.occurrence, "reader $k: ${l.name} pending without its open occurrence")
                    }
                    val pauta = rows.map { it.id }.filter { it in recados }.toSet()
                    lastPauta?.let { assertTrue(it.containsAll(pauta), "reader $k: only closes happen, a launched recado came back") }
                    lastPauta = pauta
                    val open = db.planned(PC_NOV)
                    for (o in open) if (o.occurrence in occurrences) assertTrue(o.noticeInPauta, "reader $k: ${o.name} open without its recado in the Pauta")
                    val openSet = open.map { it.occurrence }.filter { it in occurrences }.toSet()
                    lastOpen?.let { assertTrue(it.containsAll(openSet), "reader $k: a closed occurrence came back") }
                    lastOpen = openSet
                    passes.incrementAndFetch()
                } while (!done.load())
            }
        }
        delay(10)
        val seed = 66
        val got = race(Random(seed), links.map { l -> suspend { db.accept(l.recado, pcAcceptForm(budget, l.merchant, PC_NOV, l.occurrence, l.name)).v } })
        done.store(true)
        readers.joinAll()

        assertTrue(got.all { it != 0L }, "each recognized recado launches its own occurrence: seed $seed $got")
        assertEquals(got.size, got.toSet().size, "seed $seed")
        assertTrue(passes.load() >= 3)
        assertEquals(v0 + links.size, db.version.value, "seed $seed")
        assertEquals(w0 + links.size, writes.load(), "seed $seed")
        assertTrue(db.pending(PC_NOV).none { it.id in recados }, "seed $seed")
        assertTrue(db.planned(PC_NOV).none { it.occurrence in occurrences }, "seed $seed")
    }

    @Test
    fun `duplicated previsto writes bump version once and refused ones never bump`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val seed = 67
        val rnd = Random(seed)
        val v0 = db.version.value
        val w0 = writes.load()

        val ps = race(rnd, List(10) { i -> suspend { savePlanned(db, budget, "Previsto $i", LocalDate(2026, 10, 10 + i), Repeats.MONTHLY).v } })
        assertEquals(10, ps.toSet().size, "ten distinct previstos: seed $seed $ps")
        assertEquals(v0 + 10, db.version.value, "one bump per Salvar previsto: seed $seed")
        assertEquals(w0 + 10, writes.load())
        val open = db.planned(PC_TODAY)
        assertEquals(10, open.size, "10..19 out, all inside the window of 9 out (R1)")
        assertEquals(v0 + 10, db.version.value, "creating them doesn't bump")

        val occ = open.first().occurrence
        val v1 = db.version.value
        val skips = race(rnd, List(16) { suspend { bit(db.skipOccurrence(occ)) } })
        assertEquals(1L, skips.sum(), "one Pular of the same occurrence wins: seed $seed $skips")
        assertEquals(v1 + 1, db.version.value, "seed $seed")

        val n = captureId(db, pcPush(1, "LOJA", PC_TODAY))
        val v2 = db.version.value
        val w2 = writes.load()
        val refused = race(
            rnd,
            List(8) { suspend { db.accept(n, pcAcceptForm(budget, "LOJA", PC_TODAY, occ, "Previsto 0")).v } } +
                List(4) { suspend { db.addManual(pcManualForm(budget, occ, PC_TODAY, "Previsto 0"), PC_TODAY).transaction.v } } +
                List(4) { suspend { bit(db.skipOccurrence(PC_UNKNOWN_OCCURRENCE)) } } +
                List(4) {
                    suspend {
                        db.deletePlanned(PC_UNKNOWN_PLANNED)
                        0L
                    }
                },
        )
        assertTrue(refused.all { it == 0L }, "skipped or unknown: every one refused: seed $seed $refused")
        assertEquals(v2, db.version.value, "refused writes never bump: seed $seed")
        assertEquals(w2, writes.load(), "nor call onWrite: seed $seed")
        assertEquals(NoticeStatus.PENDING, db.notice(n).status)

        val target = PlannedId(ps[1])
        val v3 = db.version.value
        race(
            rnd,
            List(16) {
                suspend {
                    db.deletePlanned(target)
                    0L
                }
            },
        )
        assertEquals(v3 + 1, db.version.value, "only the first Apagar changes something: seed $seed")
        assertTrue(db.planned(PC_TODAY).none { it.planned == target })
        assertEquals(8, db.planned(PC_TODAY).size, "10 − the skipped one − the deleted one")
    }

    // ---- Lifecycle ----

    private data class Life(
        val aluguelOct: OccurrenceId,
        val internetOct: OccurrenceId,
        val academiaNov: OccurrenceId,
        val internetNov: OccurrenceId,
        val recado: NoticeId,
        val other: NoticeId,
        val manual: TransactionId,
        val launched: TransactionId,
    )

    /**
     * Every previsto state at once, read on 9 nov (spec §2.13): Internet (Todo mês, dia 15) with 15 out closed by
     * a manual lançamento and 15 nov open; Aluguel (Só uma vez, 12 out) skipped; Academia (Todo mês, dia 9,
     * linked to SMARTFIT of Nubank) with 9 nov closed by Lançar previsto of its recado.
     */
    private suspend fun lifeState(db: Db, budget: BudgetId): Life {
        savePlanned(db, budget, "Internet", LocalDate(2026, 10, 15), Repeats.MONTHLY)
        savePlanned(db, budget, "Aluguel", LocalDate(2026, 10, 12), Repeats.ONCE)
        val first = captureId(db, pcPush(1, "SMARTFIT", PC_TODAY))
        val tx = db.accept(
            first,
            TransactionForm(
                budget = budget, tags = listOf("casa"), total = Cents(9990), merchant = "SMARTFIT", date = PC_TODAY,
                time = LocalTime(12, 0), caption = "Academia", repeats = Repeats.MONTHLY,
            ),
        )
        assertNotEquals(0L, tx.v)

        // By hand, R1 on 9 nov: up to 19 nov; late = before 9 nov (P10); Academia starts the month after 9 out (D64).
        val before = db.planned(PC_NOV)
        assertEquals(
            listOf(
                Triple("Aluguel", LocalDate(2026, 10, 12), true),
                Triple("Internet", LocalDate(2026, 10, 15), true),
                Triple("Academia", LocalDate(2026, 11, 9), false),
                Triple("Internet", LocalDate(2026, 11, 15), false),
            ),
            before.map { Triple(it.name, it.date, it.late) },
        )
        assertEquals(listOf(false, false, true, false), before.map { it.linked })
        assertEquals("SMARTFIT", before[2].linkText)
        assertEquals("Nubank", before[2].linkSource)

        val manual = db.addManual(pcManualForm(budget, before[1].occurrence, PC_NOV, "Internet"), PC_NOV).transaction
        assertNotEquals(0L, manual.v)
        assertTrue(db.skipOccurrence(before[0].occurrence))
        val recado = captureId(db, pcPush(2, "SMARTFIT", PC_NOV))
        assertEquals(before[2].occurrence, db.pending(PC_NOV).single { it.id == recado }.planned.occurrence, "D60")
        val launched = db.accept(recado, pcAcceptForm(budget, "SMARTFIT", PC_NOV, before[2].occurrence, "Academia"))
        assertNotEquals(0L, launched.v)
        val other = captureId(db, pcPush(3, "PADARIA", PC_NOV))
        assertEquals(listOf(before[3].occurrence), db.planned(PC_NOV).map { it.occurrence })
        return Life(before[0].occurrence, before[1].occurrence, before[2].occurrence, before[3].occurrence, recado, other, manual, launched)
    }

    private data class Snap(val open: List<Planned>, val pauta: List<NoticeRow>, val pautaNoDate: List<NoticeRow>, val transactions: List<TransactionRow>)

    private suspend fun snap(db: Db) = Snap(db.planned(PC_NOV), db.pending(PC_NOV), db.pending(), db.transactions())

    @Test
    fun `previstos occurrences and links survive closing and reopening and open or closed stays derived`() = conc {
        val path = tempPath()
        val first = openDb(path)
        val budget = seeded(first)
        val life = lifeState(first, budget)
        val before = snap(first)
        closeDb(first)

        repeat(3) { round ->
            val db = openDb(path)
            assertEquals(before, snap(db), "reopen $round")
            closeDb(db)
        }

        val db = openDb(path)
        val v0 = db.version.value
        assertFalse(db.skipOccurrence(life.aluguelOct), "Aluguel 12 out is still skipped")
        assertFalse(db.skipOccurrence(life.academiaNov), "Academia 9 nov is still closed")
        assertEquals(0L, db.accept(life.other, pcAcceptForm(budget, "PADARIA", PC_NOV, life.internetOct, "Internet")).v, "Internet 15 out is still closed")
        assertEquals(v0, db.version.value, "refused after reopening, no bump")

        // S1 after reopening: deleting Lançar previsto brings the occurrence back, waiting and linked, and the recado recognized.
        db.deleteTransaction(life.launched)
        val open = db.planned(PC_NOV)
        assertEquals(listOf(life.academiaNov, life.internetNov), open.map { it.occurrence })
        val academia = open[0]
        assertTrue(academia.linked)
        assertEquals("SMARTFIT", academia.linkText)
        assertTrue(academia.noticeInPauta)
        assertFalse(academia.late, "9 nov is not before 9 nov")
        assertEquals(life.academiaNov, db.pending(PC_NOV).single { it.id == life.recado }.planned.occurrence)
        assertEquals(life.academiaNov, db.pending().single { it.id == life.recado }.planned.occurrence, "NO_DATE still recognizes existing occurrences")

        db.deleteTransaction(life.manual)
        val all = db.planned(PC_NOV)
        assertEquals(listOf(life.internetOct, life.academiaNov, life.internetNov), all.map { it.occurrence })
        assertTrue(all[0].late, "Internet waits since 15 out")

        val after = snap(db)
        closeDb(db)
        assertEquals(after, snap(openDb(path)), "the derived state is the one on disk")
    }

    @Test
    fun `export and import keep previstos occurrences and links and import swaps whole previsto states under readers`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val life = lifeState(db, budget)
        val x = snap(db)
        val backup = tempPath()
        assertTrue(db.exportTo(backup))

        // State Y: Pular 15 nov, and S1 reopens Academia 9 nov.
        assertTrue(db.skipOccurrence(life.internetNov))
        db.deleteTransaction(life.launched)
        val y = snap(db)
        val xOpen = x.open.map { it.occurrence }
        val yOpen = y.open.map { it.occurrence }
        assertEquals(listOf(life.internetNov), xOpen)
        assertEquals(listOf(life.academiaNov), yOpen)
        val v0 = db.version.value
        val w0 = writes.load()
        val done = AtomicBoolean(false)
        val passes = AtomicInt(0)

        val readers = List(3) { k ->
            launch {
                do {
                    val open = db.planned(PC_NOV).map { it.occurrence }
                    assertTrue(open == xOpen || open == yOpen, "reader $k: the exported or the current previsto state, never a mix: $open")
                    db.pending(PC_NOV).singleOrNull { it.id == life.recado }?.let {
                        assertEquals(life.academiaNov, it.planned.occurrence, "reader $k: recado pending without its occurrence")
                    }
                    val txs = db.transactions().size
                    assertTrue(txs == x.transactions.size || txs == y.transactions.size, "reader $k: transactions $txs")
                    passes.incrementAndFetch()
                } while (!done.load())
            }
        }
        delay(20)
        assertTrue(db.importFrom(backup))
        done.store(true)
        readers.joinAll()

        assertTrue(passes.load() >= 3)
        assertEquals(v0 + 1, db.version.value, "import bumps once (D14)")
        assertEquals(w0 + 1, writes.load())
        assertEquals(x, snap(db), "import brings back the exported previstos, occurrences and links")

        // A fresh Db from the same backup: same occurrences, and S1 still works on them.
        val copy = openDb(tempPath(), AtomicInt(0))
        assertTrue(copy.importFrom(backup))
        assertEquals(x, snap(copy))
        assertFalse(copy.skipOccurrence(life.aluguelOct), "skipped in the backup stays skipped")
        copy.deleteTransaction(life.launched)
        val academia = copy.planned(PC_NOV).single { it.occurrence == life.academiaNov }
        assertTrue(academia.linked)
        assertEquals("SMARTFIT", academia.linkText)
        assertTrue(academia.noticeInPauta)
        assertEquals(life.academiaNov, copy.pending(PC_NOV).single { it.id == life.recado }.planned.occurrence)
    }

    @Test
    fun `exportTo while Lançar previsto runs writes a consistent previsto state`() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val links = linkedRounds(db, budget, 8)
        val baseTx = db.transactions().size
        val seed = 68
        val launching = async {
            race(
                Random(seed),
                links.mapIndexed { i, l ->
                    suspend {
                        delay(i * 3L)
                        db.accept(l.recado, pcAcceptForm(budget, l.merchant, PC_NOV, l.occurrence, l.name)).v
                    }
                },
            )
        }
        val backups = List(4) {
            delay(6)
            tempPath().also { assertTrue(db.exportTo(it)) }
        }
        assertTrue(launching.await().all { it != 0L }, "seed $seed")

        var lastOpen: Set<OccurrenceId>? = null
        for ((i, b) in backups.withIndex()) {
            val copy = openDb(b, AtomicInt(0))
            val pauta = copy.pending()
            val open = copy.planned(PC_NOV).map { it.occurrence }.filter { o -> links.any { it.occurrence == o } }.toSet()
            for (l in links) {
                val row = pauta.singleOrNull { it.id == l.recado }
                assertEquals(row != null, l.occurrence in open, "seed $seed backup $i: ${l.name} recado pending iff its occurrence open (one snapshot)")
                row?.let { assertEquals(l.occurrence, it.planned.occurrence, "seed $seed backup $i: ${l.name}") }
            }
            assertEquals(baseTx + links.size - open.size, copy.transactions().size, "seed $seed backup $i: one lançamento per closed occurrence")
            lastOpen?.let { assertTrue(it.containsAll(open), "seed $seed backup $i: a later backup reopened an occurrence") }
            lastOpen = open
            closeDb(copy)
        }
    }
}
