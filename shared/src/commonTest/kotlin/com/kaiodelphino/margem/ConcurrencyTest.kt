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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

private val TZ = TimeZone.of("America/Sao_Paulo")
private val TODAY = LocalDate(2026, 10, 14)
private val MONTH = YearMonth(2026, 10)
private const val PKG = "br.com.aurora"
private const val T0 = 1_792_000_000_000L // around 2026-10-14

/**
 * Concurrency and lifecycle of the Db (architecture D6, D14, D21, D29, D30): one writer, readers that
 * don't wait for it, version/onWrite bookkeeping, backup under load, reopening.
 * Work runs on Dispatchers.Default (real threads); every test is bounded by withTimeout so a deadlock fails.
 */
class ConcurrencyTest {
    private val files = mutableListOf<String>()
    private val opened = mutableListOf<Db>()

    /** onWrite calls of the main Db of each test. */
    private val writes = AtomicInt(0)

    @AfterTest
    fun cleanup() {
        for (db in opened) runCatching { db.close() }
        opened.clear()
        for (f in files) for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            runCatching { SystemFileSystem.delete(Path(f + suffix), mustExist = false) }
        }
    }

    // Real threads under a real-time timeout: withTimeout inside Dispatchers.Default uses wall-clock time,
    // not runTest's virtual time.
    private fun conc(block: suspend CoroutineScope.() -> Unit): TestResult = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) { withTimeout(20.seconds) { block() } }
    }

    private fun tempPath(): String {
        val p = Path(SystemTemporaryDirectory, "margem-concurrency-${Random.nextLong().toULong()}.db").toString()
        files += p
        return p
    }

    private fun openDb(path: String, counter: AtomicInt = writes): Db =
        Db(BundledSQLiteDriver(), path, onWrite = { counter.incrementAndFetch() }, timeZone = TZ).also { opened += it }

    private fun closeDb(db: Db) {
        opened.remove(db)
        db.close()
    }

    private fun writeFile(path: String, bytes: ByteArray) {
        SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }
    }

    /** Distinct notice [i]: own key, content and when. */
    private fun notice(i: Int, key: String = "0|$PKG|$i|null|10001") = RawNotice(
        packageName = PKG, appLabel = "Banco Aurora", key = key, postedAt = T0 + i, whenMs = T0 + i * 1000L,
        title = "Compra aprovada", text = "Compra de R$ $i,00 aprovada em LOJA $i",
    )

    private fun form(budget: BudgetId, total: Long = 1000) = TransactionForm(
        budget = budget, tags = listOf("mercado"), total = Cents(total), installments = 1,
        merchant = "LOJA", date = TODAY, time = LocalTime(12, 0),
    )

    /** A source to capture from and one budget with a limit in [MONTH]. */
    private suspend fun seeded(db: Db, name: String = "Mercado"): BudgetId {
        assertTrue(db.addSource(PKG, "Banco Aurora", T0))
        val budget = db.createBudget(name, Cents(100_000), TODAY)
        assertNotEquals(0L, budget.v)
        return budget
    }

    private suspend fun idOf(db: Db, key: String): NoticeId = db.pending().first { it.raw.key == key }.id

    private data class State(
        val pending: Set<String>,
        val dismissed: Set<String>,
        val transactions: List<TransactionRow>,
        val budgets: List<BudgetSummary>,
        val settings: Settings,
        val sources: List<Source>,
    )

    private suspend fun state(db: Db) = State(
        db.pending().map { it.raw.key }.toSet(),
        db.dismissed().map { it.raw.key }.toSet(),
        db.transactions(),
        db.budgetsOverview(MONTH, TODAY, BudgetOrder.AZ),
        db.settings(),
        db.sources(),
    )

    /**
     * One reader pass over a Db where only captures and accepts happen (all accepts: [budget], TODAY, à vista).
     * Each call returns a whole state; transactions are read before pending, so a notice already processed
     * can never show up as pending afterwards. Returns the transaction count and the budget's spent.
     */
    private suspend fun checkWholeState(db: Db, budget: BudgetId): Pair<Int, Long> {
        val txs = db.transactions()
        val pending = db.pending()
        assertEquals(txs.size, txs.map { it.id }.toSet().size, "a transaction listed twice: $txs")
        assertEquals(pending.size, pending.map { it.id }.toSet().size, "a notice listed twice in the Pauta")
        assertTrue(pending.all { it.status == NoticeStatus.PENDING }, "a non-pending notice in the Pauta")
        val processed = txs.map { it.notice }.filter { it.v != 0L }.toSet()
        val both = pending.filter { it.id in processed }.map { it.id }
        assertTrue(both.isEmpty(), "notices both pending and launched: $both")

        val detail = db.budgetDetail(budget, MONTH, TODAY)
        val counted = detail.lines.filter { !it.row.refunded }.sumOf { it.cents.v }
        assertEquals(counted, detail.month.spent.v, "budgetDetail torn: spent differs from its own transactions")
        assertEquals(detail.month.limit.v - detail.month.spent.v, detail.month.remaining.v)

        val overview = db.budgetsOverview(MONTH, TODAY, BudgetOrder.AZ)
        assertEquals(listOf(budget), overview.map { it.id })
        return txs.size to overview.single().month.spent.v
    }

    // ---- Serialized writes ----

    @Test
    fun concurrentCapturesOfDistinctNoticesAreAllSavedWhileCountsOnlyGrow() = conc {
        val db = openDb(tempPath())
        seeded(db)
        val n = 60
        val v0 = db.version.value
        val w0 = writes.load()
        val done = AtomicBoolean(false)

        val readers = List(3) {
            launch {
                var lastCount = -1
                var lastSize = -1
                var lastVersion = -1L
                do {
                    val version = db.version.value
                    assertTrue(version >= lastVersion, "version went back: $lastVersion -> $version")
                    lastVersion = version
                    val count = db.pendingCount()
                    assertTrue(count >= lastCount, "pendingCount shrank while only inserting: $lastCount -> $count")
                    lastCount = count
                    val size = db.pending().size
                    assertTrue(size >= lastSize, "pending() shrank while only inserting: $lastSize -> $size")
                    assertTrue(size >= count, "pending() read after pendingCount() can't have fewer: $count > $size")
                    lastSize = size
                } while (!done.load())
            }
        }
        val saved = List(n) { i -> async { capture(db, notice(i)) } }.awaitAll()
        done.store(true)
        readers.joinAll()

        assertTrue(saved.all { it }, "every distinct notice is saved: $saved")
        assertEquals(n, db.pendingCount())
        assertEquals((0 until n).map { notice(it).key }.toSet(), db.pending().map { it.raw.key }.toSet())
        assertEquals(v0 + n, db.version.value, "one bump per saved notice")
        assertEquals(w0 + n, writes.load(), "one onWrite per saved notice")
    }

    @Test
    fun concurrentCapturesOfTheSameNotificationSaveItOnce() = conc {
        val db = openDb(tempPath())
        seeded(db)
        val v0 = db.version.value
        val w0 = writes.load()

        // Same key, content and when (≠ 0): a re-post however it races.
        val raw = notice(1)
        val saved = List(24) { async { capture(db, raw) } }.awaitAll()
        assertEquals(1, saved.count { it }, "exactly one capture returns true: $saved")
        assertEquals(listOf(raw.key), db.pending().map { it.raw.key })

        // Same key and content, no when, post times seconds apart: re-posts inside the window.
        val repost = raw.copy(key = "0|$PKG|2|null|10001", whenMs = 0, text = "Pix enviado de R$ 9,00")
        val savedReposts = List(24) { i -> async { capture(db, repost.copy(postedAt = T0 + 5_000 + i * 1_000L)) } }.awaitAll()
        assertEquals(1, savedReposts.count { it }, "exactly one re-post inside the window is saved: $savedReposts")
        assertEquals(2, db.pendingCount())

        // Refused captures may or may not bump (contract silent); onWrite always follows the bumps.
        assertTrue(db.version.value >= v0 + 2)
        assertEquals(db.version.value - v0, (writes.load() - w0).toLong(), "onWrite once per version bump")
    }

    @Test
    fun concurrentAcceptsOfOneNoticeMakeExactlyOneTransaction() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        assertTrue(capture(db, notice(1)))
        val id = idOf(db, notice(1).key)

        val ids = List(20) { async { db.accept(id, form(budget)) } }.awaitAll()
        val winners = ids.filter { it.v != 0L }
        assertEquals(1, winners.size, "exactly one accept wins: $ids")
        assertEquals(winners, db.transactions().map { it.id })
        assertEquals(id, db.transactions().single().notice)
        assertEquals(0, db.pendingCount())
        assertEquals(NoticeStatus.PROCESSED, db.notice(id).status)
        assertEquals(1000L, db.budgetDetail(budget, MONTH, TODAY).month.spent.v, "counted once")
        assertEquals(1, db.tags().single { it.name == "mercado" }.uses)
    }

    @Test
    fun acceptRacingDismissOnOneNoticeLetsExactlyOneWin() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        repeat(12) { round ->
            val raw = notice(100 + round)
            assertTrue(capture(db, raw))
            val id = idOf(db, raw.key)
            val accepted = async { db.accept(id, form(budget)) }
            val dismissed = async { db.dismiss(id) }
            val txId = accepted.await()
            val wasDismissed = dismissed.await()
            assertTrue((txId.v != 0L) != wasDismissed, "round $round: exactly one of accept/dismiss wins ($txId, $wasDismissed)")
            val expected = if (wasDismissed) NoticeStatus.DISMISSED else NoticeStatus.PROCESSED
            assertEquals(expected, db.notice(id).status, "round $round")
        }
        assertEquals(0, db.pendingCount())
        assertEquals(12, db.transactions().size + db.dismissed().size)
    }

    @Test
    fun concurrentRefundsOnOnePurchaseLetExactlyOneWin() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        assertTrue(capture(db, notice(1)))
        val purchase = db.accept(idOf(db, notice(1).key), form(budget))
        assertNotEquals(0L, purchase.v)
        val estornos = (2..9).map { i ->
            val raw = notice(i).copy(text = "Estorno de R$ 10,00 em LOJA $i")
            assertTrue(capture(db, raw))
            idOf(db, raw.key)
        }

        // Several estornos on the same purchase: a transaction is refunded once.
        val results = estornos.map { e -> async { db.registerRefund(e, purchase) } }.awaitAll()
        assertEquals(1, results.count { it }, "one estorno wins the purchase: $results")
        val winner = estornos[results.indexOf(true)]
        assertEquals(winner, db.transaction(purchase).row.estorno)
        assertEquals(0L, db.budgetDetail(budget, MONTH, TODAY).month.spent.v)
        assertEquals(estornos.size - 1, db.pendingCount(), "the losing estornos stay pending")

        // One estorno on several purchases: it stops being pending after the first.
        assertTrue(db.undoRefund(winner))
        val others = (20..25).map { i ->
            assertTrue(capture(db, notice(i)))
            db.accept(idOf(db, notice(i).key), form(budget)).also { assertNotEquals(0L, it.v) }
        }
        val targets = others + purchase
        val oneEstorno = targets.map { t -> async { db.registerRefund(winner, t) } }.awaitAll()
        assertEquals(1, oneEstorno.count { it }, "one purchase gets the estorno: $oneEstorno")
        assertEquals(1, db.transactions().count { it.refunded })
    }

    @Test
    fun concurrentCreatesOfOneNameMakeOne() = conc {
        val db = openDb(tempPath())
        val names = List(16) { i -> listOf("Mercado", "MERCADO", "mercado", "MerCado")[i % 4] }
        val budgets = names.map { name -> async { db.createBudget(name, Cents(50_000), TODAY) } }.awaitAll()
        assertEquals(1, budgets.count { it.v != 0L }, "names are unique ignoring case: $budgets")
        assertEquals(1, db.budgetsOverview(MONTH, TODAY, BudgetOrder.AZ).size)

        val tags = List(16) { i -> async { db.createTag(if (i % 2 == 0) "Feira" else " feira ") } }.awaitAll()
        assertTrue(tags.all { it.v != 0L && it == tags[0] }, "every caller gets the one tag: $tags")
        assertEquals(1, db.tags().count { it.name == "feira" })
    }

    // ---- D29: reads proceed during writes ----

    @Test
    fun readsRunDuringWritesAndAlwaysSeeAWholeState() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val writers = 4
        val perWriter = 15
        val done = AtomicBoolean(false)
        val passes = AtomicInt(0)

        val readers = List(3) {
            launch {
                var lastTx = -1
                var lastSpent = -1L
                var lastVersion = -1L
                do {
                    val version = db.version.value
                    assertTrue(version >= lastVersion, "version went back: $lastVersion -> $version")
                    lastVersion = version
                    val (tx, spent) = checkWholeState(db, budget)
                    assertTrue(tx >= lastTx, "transactions shrank while only adding: $lastTx -> $tx")
                    assertTrue(spent >= lastSpent, "spent shrank while only adding: $lastSpent -> $spent")
                    lastTx = tx
                    lastSpent = spent
                    passes.incrementAndFetch()
                } while (!done.load())
            }
        }
        List(writers) { w ->
            async {
                for (i in 0 until perWriter) {
                    val raw = notice(w * 100 + i)
                    assertTrue(capture(db, raw))
                    if (i % 2 == 0) assertNotEquals(0L, db.accept(idOf(db, raw.key), form(budget)).v)
                }
            }
        }.awaitAll()
        done.store(true)
        readers.joinAll()

        assertTrue(passes.load() >= 3)
        val launched = writers * ((perWriter + 1) / 2)
        assertEquals(launched, db.transactions().size)
        assertEquals(writers * perWriter - launched, db.pendingCount())
        assertEquals(launched * 1000L, checkWholeState(db, budget).second)
    }

    // ---- D6/D21: version and onWrite ----

    @Test
    fun everySuccessfulWriteBumpsVersionOnceAndCallsOnWriteOnce() = conc {
        val db = openDb(tempPath())
        var version = db.version.value
        var calls = writes.load()

        suspend fun write(name: String, block: suspend () -> Boolean) {
            assertTrue(block(), "$name should succeed")
            version++
            calls++
            assertEquals(version, db.version.value, "version after $name")
            assertEquals(calls, writes.load(), "onWrite after $name")
        }

        var budget = BudgetId(0)
        var tx = TransactionId(0)
        write("addSource") { db.addSource(PKG, "Banco Aurora", T0) }
        write("createBudget") { db.createBudget("Mercado", Cents(60_000), TODAY).also { budget = it }.v != 0L }
        write("capture 1") { capture(db, notice(1)) }
        write("capture 2") { capture(db, notice(2)) }
        write("capture estorno") { capture(db, notice(3).copy(text = "Estorno de R$ 10,00 em LOJA 1")) }
        val n2 = idOf(db, notice(2).key)
        write("dismiss") { db.dismiss(n2) }
        write("restore") { db.restore(n2) }
        write("accept") { db.accept(idOf(db, notice(1).key), form(budget)).also { tx = it }.v != 0L }
        write("addManual") { db.addManual(form(budget, 2500)).transaction.v != 0L }
        write("edit") { db.edit(tx, form(budget, 1200)) }
        val estorno = idOf(db, notice(3).key)
        write("registerRefund") { db.registerRefund(estorno, tx) }
        write("undoRefund") { db.undoRefund(estorno) }
        write("createTag") { db.createTag("feira").v != 0L }
        write("setAlias") { db.setAlias("LOJA", "Loja do Zé"); true }
        write("setTheme") { db.setTheme(Theme.DARK); true }
        write("setBudgetOrder") { db.setBudgetOrder(BudgetOrder.AZ); true }
        write("setLimit") { db.setLimit(budget, Cents(80_000), TODAY) }
        write("deleteTransaction") { db.deleteTransaction(tx); true }

        // Opening the Pauta writes without a bump: the open Pauta has nothing to re-read (Db.markPautaSeen KDoc).
        db.markPautaSeen(T0 + 10_000)
        assertEquals(version to calls, db.version.value to writes.load(), "markPautaSeen doesn't bump nor call onWrite")

        // Reads never bump.
        db.pending(); db.pendingCount(); db.dismissed(); db.transactions(); db.tags(); db.sources(); db.settings()
        db.budgetsOverview(MONTH, TODAY, BudgetOrder.RISK); db.budgetDetail(budget, MONTH, TODAY)
        db.history(MONTH, MONTH); db.tagRanking(TODAY, TODAY); db.merchantRanking(TODAY, TODAY)
        db.spentByDay(TODAY, TODAY); db.suggestion("LOJA"); db.refundCandidates(estorno); db.monthOf(TODAY)
        db.previewAccept(budget, TODAY, Cents(100), 1, TODAY)
        assertEquals(version, db.version.value, "reads don't bump")
        assertEquals(calls, writes.load(), "reads don't call onWrite")

        // Refused writes: the contract doesn't say whether they bump; onWrite still follows version exactly.
        assertEquals(0L, db.accept(NoticeId(999_999), form(budget)).v)
        assertEquals(0L, db.addManual(form(budget).copy(tags = emptyList())).transaction.v)
        assertFalse(db.dismiss(NoticeId(999_999)))
        assertEquals(0L, db.createBudget("mercado", Cents(1), TODAY).v)
        assertFalse(capture(db, notice(1)))
        assertEquals(db.version.value - version, (writes.load() - calls).toLong(), "onWrite once per version bump")
    }

    @Test
    fun versionOnlyGrowsAndCountsEveryConcurrentWrite() = conc {
        val db = openDb(tempPath())
        seeded(db)
        val n = 60
        val v0 = db.version.value
        val w0 = writes.load()
        val target = v0 + n
        val seen = async(start = CoroutineStart.UNDISPATCHED) {
            db.version.transformWhile { emit(it); it < target }.toList()
        }

        val results = List(n) { i ->
            async {
                when (i % 3) {
                    0 -> capture(db, notice(i))
                    1 -> db.createTag("marcador $i").v != 0L
                    else -> db.addSource("app.fonte$i", "Fonte $i", T0)
                }
            }
        }.awaitAll()
        assertTrue(results.all { it }, "every write succeeds: $results")

        val values = seen.await()
        assertEquals(v0, values.first())
        assertEquals(target, values.last())
        assertTrue(values.zipWithNext().all { (a, b) -> a < b }, "version must only grow: $values")
        assertEquals(target, db.version.value)
        assertEquals(w0 + n, writes.load())
    }

    // ---- D30: ensureMonths ----

    @Test
    fun ensureMonthsIsSilentAndConcurrentCallsEqualOneCall() = conc {
        val last = YearMonth(2027, 3)
        val months = listOf(
            YearMonth(2026, 8), YearMonth(2026, 10), YearMonth(2026, 11), YearMonth(2026, 12),
            YearMonth(2027, 1), YearMonth(2027, 2), last,
        )
        suspend fun setup(db: Db) {
            assertNotEquals(0L, db.createBudget("Mercado", Cents(60_000), TODAY).v)
            assertNotEquals(0L, db.createBudget("Casa", Cents(30_000), TODAY).v)
        }

        val reference = openDb(tempPath(), AtomicInt(0))
        setup(reference)
        reference.ensureMonths(last)

        val db = openDb(tempPath())
        setup(db)
        val v0 = db.version.value
        val w0 = writes.load()
        val done = AtomicBoolean(false)
        val reader = launch {
            do {
                val rows = db.history(YearMonth(2026, 8), last)
                assertEquals(rows.size, rows.map { it.name to it.month }.toSet().size, "a limit row twice: $rows")
            } while (!done.load())
        }
        List(42) { i -> async { db.ensureMonths(if (i % 3 == 0) last else months[i % months.size]) } }.awaitAll()
        done.store(true)
        reader.join()

        assertEquals(v0, db.version.value, "ensureMonths doesn't bump version")
        assertEquals(w0, writes.load(), "ensureMonths doesn't call onWrite")

        fun view(rows: List<HistoryRow>) = rows.map { listOf(it.name, it.month, it.limit, it.spent) }.toSet()
        val history = db.history(YearMonth(2026, 8), last)
        assertEquals(12, history.size, "2 budgets × 2026-10..2027-03: $history")
        assertTrue(history.none { it.month < MONTH }, "months before a budget's first row stay without one")
        assertEquals(view(reference.history(YearMonth(2026, 8), last)), view(history))
        assertEquals(
            reference.budgetsOverview(last, TODAY, BudgetOrder.AZ).map { it.name to it.month },
            db.budgetsOverview(last, TODAY, BudgetOrder.AZ).map { it.name to it.month },
        )

        db.ensureMonths(last)
        assertEquals(view(history), view(db.history(YearMonth(2026, 8), last)), "again: nothing changes")
        assertEquals(v0, db.version.value)
    }

    // ---- D14: backup under load ----

    @Test
    fun exportWhileWritingGivesWholeCopiesThatImport() = conc {
        val db = openDb(tempPath())
        val budget = seeded(db)
        val total = 45
        val exportPaths = List(4) { tempPath() }

        val writer = async {
            for (i in 0 until total) {
                val raw = notice(i)
                assertTrue(capture(db, raw))
                if (i % 3 == 0) assertNotEquals(0L, db.accept(idOf(db, raw.key), form(budget)).v)
            }
        }
        val exporter = async {
            for (p in exportPaths) {
                assertTrue(db.exportTo(p), "export to $p")
                delay(5)
            }
        }
        writer.await()
        exporter.await()

        val finalKeys = (db.pending().map { it.raw.key } + db.transactions().map { db.notice(it.notice).raw.key }).toSet()
        val finalTxs = db.transactions().associateBy { it.id }
        assertEquals(total, finalKeys.size)

        var lastNotices = -1
        for (p in exportPaths) {
            val counter = AtomicInt(0)
            val copy = openDb(tempPath(), counter)
            val v0 = copy.version.value
            val c0 = counter.load()
            assertTrue(copy.importFrom(p), "an export imports: $p")
            assertEquals(v0 + 1, copy.version.value, "import bumps version once")
            assertEquals(c0 + 1, counter.load(), "import calls onWrite once")

            checkWholeState(copy, budget)
            val txs = copy.transactions()
            for (t in txs) {
                assertEquals(finalTxs[t.id], t, "an exported transaction is the one the source has")
                assertEquals(NoticeStatus.PROCESSED, copy.notice(t.notice).status)
            }
            val keys = copy.pending().map { it.raw.key } + txs.map { copy.notice(it.notice).raw.key }
            assertTrue(finalKeys.containsAll(keys), "export has only notices the source has")
            assertTrue(keys.size >= lastNotices, "later exports never have fewer notices: $lastNotices -> ${keys.size}")
            lastNotices = keys.size
            assertEquals(listOf(PKG), copy.sources().map { it.packageName })
        }
    }

    @Test
    fun importWhileReadingSwapsWholeStatesWithoutDeadlock() = conc {
        val other = openDb(tempPath(), AtomicInt(0))
        val casa = seeded(other, "Casa")
        for (i in 0 until 6) assertTrue(capture(other, notice(i, key = "a-$i")))
        assertNotEquals(0L, other.accept(idOf(other, "a-0"), form(casa)).v)
        assertNotEquals(0L, other.accept(idOf(other, "a-1"), form(casa)).v)
        val backup = tempPath()
        assertTrue(other.exportTo(backup))
        closeDb(other)
        val newKeys = (2 until 6).map { "a-$it" }.toSet()

        val path = tempPath()
        val db = openDb(path)
        seeded(db, "Lazer")
        for (i in 100 until 103) assertTrue(capture(db, notice(i, key = "b-$i")))
        val oldKeys = db.pending().map { it.raw.key }.toSet()
        val v0 = db.version.value
        val w0 = writes.load()
        val done = AtomicBoolean(false)
        val passes = AtomicInt(0)

        val readers = List(3) {
            launch {
                var lastVersion = -1L
                do {
                    val version = db.version.value
                    assertTrue(version >= lastVersion, "version went back: $lastVersion -> $version")
                    lastVersion = version
                    val keys = db.pending().map { it.raw.key }.toSet()
                    assertTrue(keys == oldKeys || keys == newKeys, "pending is the old or the new state, never a mix: $keys")
                    val names = db.budgetsOverview(MONTH, TODAY, BudgetOrder.AZ).map { it.name }
                    assertTrue(names == listOf("Lazer") || names == listOf("Casa"), "budgets: $names")
                    val txs = db.transactions().size
                    assertTrue(txs == 0 || txs == 2, "transactions: $txs")
                    passes.incrementAndFetch()
                } while (!done.load())
            }
        }
        delay(20)
        assertTrue(db.importFrom(backup))
        done.store(true)
        readers.joinAll()

        assertTrue(passes.load() >= 3)
        assertEquals(v0 + 1, db.version.value, "import bumps version once")
        assertEquals(w0 + 1, writes.load(), "import calls onWrite once")
        assertEquals(newKeys, db.pending().map { it.raw.key }.toSet())
        assertEquals(2, db.transactions().size)
        assertEquals(listOf("Casa"), db.budgetsOverview(MONTH, TODAY, BudgetOrder.AZ).map { it.name })

        // Usable after the swap, and the swap is on disk.
        assertTrue(capture(db, notice(200, key = "c-200")))
        closeDb(db)
        val reopened = openDb(path)
        assertEquals(newKeys + "c-200", reopened.pending().map { it.raw.key }.toSet())
        assertEquals(2, reopened.transactions().size)
    }

    @Test
    fun importOfANonDatabaseIsRefusedAndKeepsTheData() = conc {
        val path = tempPath()
        val db = openDb(path)
        val budget = seeded(db)
        for (i in 0 until 3) assertTrue(capture(db, notice(i)))
        assertNotEquals(0L, db.accept(idOf(db, notice(0).key), form(budget)).v)
        val before = state(db)

        val random = tempPath().also { writeFile(it, Random(7).nextBytes(4096)) }
        val empty = tempPath().also { writeFile(it, ByteArray(0)) }
        val fakeHeader = tempPath().also { writeFile(it, "SQLite format 3\u0000".encodeToByteArray() + Random(11).nextBytes(4096)) }
        val missing = tempPath()
        for (bad in listOf(random, empty, fakeHeader, missing)) {
            val v0 = db.version.value
            val w0 = writes.load()
            assertFalse(db.importFrom(bad), "not a Margem database: $bad")
            assertEquals(db.version.value - v0, (writes.load() - w0).toLong(), "onWrite once per version bump")
            assertEquals(before, state(db), "data untouched after refusing $bad")
        }

        // Still usable, and the file on disk is still the old database.
        assertTrue(capture(db, notice(50)))
        assertNotEquals(0L, db.accept(idOf(db, notice(50).key), form(budget)).v)
        val after = state(db)
        assertEquals(before.transactions.size + 1, after.transactions.size)
        closeDb(db)
        assertEquals(after, state(openDb(path)))
    }

    // ---- Lifecycle ----

    @Test
    fun reopeningKeepsTheDataAndMigratingAgainChangesNothing() = conc {
        val path = tempPath()
        val first = openDb(path)
        val budget = seeded(first)
        for (i in 0 until 4) assertTrue(capture(first, notice(i)))
        assertNotEquals(0L, first.accept(idOf(first, notice(0).key), form(budget)).v)
        assertTrue(first.dismiss(idOf(first, notice(1).key)))
        first.setTheme(Theme.DARK)
        first.setAlias("LOJA", "Loja do Zé")
        val before = state(first)
        closeDb(first)

        repeat(3) { round ->
            val db = openDb(path)
            assertEquals(before, state(db), "reopen $round")
            closeDb(db)
        }

        val db = openDb(path)
        assertFalse(capture(db, notice(2)), "a re-post is still recognized after reopening")
        assertTrue(capture(db, notice(10)))
        assertNotEquals(0L, db.accept(idOf(db, notice(10).key), form(budget)).v)
        assertEquals(before.transactions.size + 1, db.transactions().size)
        assertTrue(db.transactions().all { it.name == "Loja do Zé" }, "the alias survives reopening")
    }

    @Test
    fun aDbOpenedRightAfterCloseWorksEvenAfterConcurrentWrites() = conc {
        val path = tempPath()
        val first = openDb(path)
        val budget = seeded(first)
        List(20) { i -> async { capture(first, notice(i)) } }.awaitAll().forEach { assertTrue(it) }
        closeDb(first)

        val second = openDb(path)
        assertEquals(20, second.pendingCount())
        List(10) { i -> async { second.accept(idOf(second, notice(i).key), form(budget)) } }
            .awaitAll().forEach { assertNotEquals(0L, it.v) }
        assertEquals(10, second.pendingCount())
        assertEquals(10_000L, second.budgetDetail(budget, MONTH, TODAY).month.spent.v)
    }
}
