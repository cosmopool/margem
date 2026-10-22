package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Invariants of block 5, Ajustes e importação (spec §2.3, §2.9, §4, §7.6; architecture D3, D4, D6, D10,
// D14, D40, D41, §5), checked through the public API only (Db, capture, importCobudget) after every
// step of random, fixed-seed, bounded sequences mixing the block-5 actions (importCobudget of generated
// v7 files, refused imports, restore, dismiss, renameTag, mergeTags, deleteTag, setMonthStartDay +
// setWeekStart guarded by startDropsEdits, exportTo + importFrom) with the earlier ones (capture,
// createBudget, accept, addManual, registerRefund, undoRefund, deleteTransaction, setStep, clearStep).
//
// The test keeps a small model of the outside world, never of the algorithms under test:
// - Events: a notification as the bank sent it. Every capture or cobudget row is one occurrence of an
//   event, built so the capture dedupe (Capture.kt KDoc) merges two occurrences exactly when they are the
//   same event, whatever the order they arrive in:
//   * every event has its own `when` ≠ 0, shared by its occurrences (rule 1: same key, content and
//     when ≠ 0, however much later), or a key used by that event alone with when = 0;
//   * occurrences are posted within ±DUPLICATE_WINDOW_MS/4 of the event's base time, and base times are
//     3 × DUPLICATE_WINDOW_MS apart, so occurrences of different events are always ≥ 2.5 windows apart
//     and never meet rule 2 (same key and content, latest, < DUPLICATE_WINDOW_MS apart), while
//     occurrences of one when = 0 event are always < 1 window apart and the latest notice of that key
//     is always one of them.
//   So "which events entered" is known by hand: an event enters at its first occurrence that has text
//   (and, for a capture, comes from a source), and never again.
// - Which notices the test dismissed (and imported as dismissed), the estornos registered against a
//   notice (only visible through statuses), and the step edits stored (budget + week start date, D9).
// Statuses are then checked against architecture §5: processed = referenced by a transaction or an
// estorno; dismissed = what the test dismissed; pending = every other notice.

/** The source package; captures from any other package are refused (Capture.kt KDoc). */
private const val AINV_SRC = "com.nu.production"
private const val AINV_SRC_LABEL = "Nubank"
/** Never a source: its notices enter only through the cobudget import (D4: the source rule doesn't apply). */
private const val AINV_OTHER = "br.com.intermedium"
private const val AINV_OTHER_LABEL = "Inter"
private const val AINV_T0 = 1_760_000_000_000L
/** Base times of events: 3 windows apart (see the header). */
private const val AINV_GAP = 3 * DUPLICATE_WINDOW_MS
/** Occurrences are posted within ±JITTER of the event's base time: a quarter of the window. */
private const val AINV_JITTER = DUPLICATE_WINDOW_MS / 4
private val AINV_TODAY0 = LocalDate(2026, 10, 14)
/** Budgets are created "on" this day, so every month the test reads has a limit row (D30). */
private val AINV_BUDGET_DAY = LocalDate(2026, 3, 1)
private val AINV_TAG_POOL = listOf("feira", " Padaria ", "CAFE", "farmacia", "lazer", "casa", "padaria")
private val AINV_RENAME_POOL = listOf("mercado", " Feira Livre ", "ÔNIBUS", "presentes", "cafe", "Lazer", "viagem")
private val AINV_MERCHANTS = listOf("PADARIA SAO JOSE", "MERCADO BOM PRECO", "FARMACIA SAO JOAO", "Posto Ipê", "LOJA X")
private val AINV_UNKNOWN_TAG = TagId(987_654)
private val AINV_UNKNOWN_TX = TransactionId(987_654)

/** The v7 table, verbatim from the importCobudget KDoc. */
private const val AINV_V7_TABLE =
    "CREATE TABLE `captured_notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, " +
        "`postedAt` INTEGER NOT NULL, `notificationWhen` INTEGER NOT NULL DEFAULT 0, `title` TEXT, `text` TEXT, " +
        "`bigText` TEXT, `subText` TEXT, `textLines` TEXT, `category` TEXT, `channelId` TEXT, " +
        "`extrasJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `dismissed` INTEGER NOT NULL DEFAULT 0)"

/** v7 table without `notificationWhen`: not the columns the KDoc lists. */
private const val AINV_V7_TABLE_NO_WHEN =
    "CREATE TABLE `captured_notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, " +
        "`postedAt` INTEGER NOT NULL, `title` TEXT, `text` TEXT, " +
        "`bigText` TEXT, `subText` TEXT, `textLines` TEXT, `category` TEXT, `channelId` TEXT, " +
        "`extrasJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `dismissed` INTEGER NOT NULL DEFAULT 0)"

private fun YearMonth.ainvShift(n: Int): YearMonth = if (n >= 0) plus(n, DateTimeUnit.MONTH) else minus(-n, DateTimeUnit.MONTH)

private fun LocalDate.ainvDays(n: Int): LocalDate = if (n >= 0) plus(n, DateTimeUnit.DAY) else minus(-n, DateTimeUnit.DAY)

/** "1.234,56" */
private fun ainvBrl(c: Long): String {
    val reais = (c / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return "$reais,${(c % 100).toString().padStart(2, '0')}"
}

/** TransactionForm.tags KDoc: trimmed, lowercased, blanks and repeats dropped. */
private fun ainvClean(tags: List<String>) = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

private fun ainvTempPath(what: String) = Path(SystemTemporaryDirectory, "margem-ainv-$what-${Random.Default.nextLong().toULong()}.db").toString()

private fun ainvDelete(path: String) {
    for (suffix in listOf("", "-wal", "-shm", "-journal")) {
        runCatching { SystemFileSystem.delete(Path(path + suffix), mustExist = false) }
    }
}

/** Real threads under a wall-clock timeout (withTimeout inside Dispatchers.Default): importFrom closes and reopens connections, so a deadlock must fail, not hang. */
private fun ainvRealTime(block: suspend () -> Unit) = runTest(timeout = 600.seconds) {
    withContext(Dispatchers.Default) { withTimeout(540.seconds) { block() } }
}

/** A notification as the bank sent it (see the header). Text fields: null and "" are the same ("NULL text is """). */
private class AinvEvent(
    val idx: Int,
    val packageName: String,
    val appLabel: String,
    val key: String,
    val whenMs: Long,
    val base: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val textLines: String?,
) {
    val blank: Boolean get() = listOf(title, text, bigText, subText, textLines).all { it.isNullOrEmpty() }
    val fromSource: Boolean get() = packageName == AINV_SRC
}

/** One row of a generated cobudget backup: an occurrence of [event]. */
private class AinvRow(
    val id: Long,
    val event: AinvEvent,
    val postedAt: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val textLines: String?,
    val category: String?,
    val channelId: String?,
    val extrasJson: String,
    val dismissed: Int,
) {
    /** importCobudget KDoc: each row becomes a RawNotice field by field; NULL text is "". */
    fun raw() = RawNotice(
        packageName = event.packageName, appLabel = event.appLabel, key = event.key, postedAt = postedAt, whenMs = event.whenMs,
        title = title.orEmpty(), text = text.orEmpty(), bigText = bigText.orEmpty(), subText = subText.orEmpty(),
        textLines = textLines.orEmpty(), category = category.orEmpty(), channelId = channelId.orEmpty(), extrasJson = extrasJson,
    )

    val blank: Boolean get() = listOf(title, text, bigText, subText, textLines).all { it.isNullOrEmpty() }

    override fun toString() = "row(id=$id ev=${event.idx} key=${event.key} posted=$postedAt when=${event.whenMs} dismissed=$dismissed " +
        "text=${text?.take(40)} blank=$blank)"
}

/** Writes a cobudget backup: [table] (the v7 one by default), [rows], an ignored extra table, then `PRAGMA user_version`. */
private fun ainvWriteCobudget(path: String, rows: List<AinvRow>, userVersion: Int = 7, table: String = AINV_V7_TABLE) {
    val c = BundledSQLiteDriver().open(path)
    try {
        c.execSQL(table)
        // Another table of the cobudget schema: the import must ignore it (KDoc: "the other tables of the file are ignored").
        c.execSQL("CREATE TABLE `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `notificationId` INTEGER, `amountCents` INTEGER NOT NULL)")
        c.execSQL("INSERT INTO `transactions`(`notificationId`, `amountCents`) VALUES (1, 4600)")
        val withWhen = table.contains("`notificationWhen`")
        for (r in rows) {
            val cols = if (withWhen) "id, packageName, appLabel, notificationKey, postedAt, notificationWhen, title, text, bigText, subText, textLines, category, channelId, extrasJson, contentHash, dismissed"
            else "id, packageName, appLabel, notificationKey, postedAt, title, text, bigText, subText, textLines, category, channelId, extrasJson, contentHash, dismissed"
            val n = cols.split(",").size
            c.prepare("INSERT INTO captured_notifications($cols) VALUES (${List(n) { "?" }.joinToString(",")})").use { st ->
                var i = 1
                fun text(s: String?) {
                    if (s == null) st.bindNull(i) else st.bindText(i, s)
                    i++
                }
                st.bindLong(i++, r.id)
                text(r.event.packageName)
                text(r.event.appLabel)
                text(r.event.key)
                st.bindLong(i++, r.postedAt)
                if (withWhen) st.bindLong(i++, r.event.whenMs)
                text(r.title); text(r.text); text(r.bigText); text(r.subText); text(r.textLines)
                text(r.category); text(r.channelId); text(r.extrasJson)
                // Garbage on purpose: the KDoc says the cobudget's contentHash is ignored and recomputed (D32).
                text("cobudget-hash-${r.id % 3}")
                st.bindLong(i, r.dismissed.toLong())
                st.step()
            }
        }
        c.execSQL("PRAGMA user_version = $userVersion")
    } finally {
        c.close()
    }
}

private fun ainvWriteBytes(path: String, bytes: ByteArray) {
    SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }
}

/** The observable state that exportTo + importFrom must restore exactly (spec §7.6 Backup, D14). */
private data class AinvState(
    val pending: List<NoticeRow>,
    val dismissed: List<NoticeRow>,
    /** notice(id) of every notice the test knows, by id: covers the processed ones too. */
    val notices: List<NoticeRow>,
    val transactions: List<TransactionRow>,
    val tags: List<TagRow>,
    val settings: Settings,
    val sources: List<Source>,
    val overview: List<List<BudgetSummary>>,
)

/** The test's model of what the database holds (see the header); copied on export, put back on importFrom. */
private data class AinvModel(
    val entered: Map<Int, NoticeId>,
    val raws: Map<Int, RawNotice>,
    val dismissed: Set<NoticeId>,
    val noticeRefunds: Map<NoticeId, NoticeId>,
    val edits: Set<Pair<BudgetId, LocalDate>>,
    val budgets: List<BudgetId>,
    val budgetNames: Set<String>,
)

private class AinvBackup(
    val path: String,
    val state: AinvState,
    val model: AinvModel,
    val ref: LocalDate,
    val months: List<YearMonth>,
    val horizon: YearMonth,
)

private enum class AinvAct {
    CAPTURE_NEW, CAPTURE_REPOST, IMPORT_COBUDGET, REIMPORT_COBUDGET, IMPORT_COBUDGET_REFUSED,
    ACCEPT, ADD_MANUAL, DISMISS, RESTORE, REFUND_TX, REFUND_NOTICE, UNDO_REFUND, DELETE_TX,
    RENAME_TAG, MERGE_TAGS, DELETE_TAG, SET_STEP, CLEAR_STEP, START_PROBE, START_SAVE,
    EXPORT, IMPORT_FROM, IMPORT_FROM_REFUSED, CREATE_BUDGET, ADVANCE_TODAY,
}

/** How often each action is drawn; each test group skews it toward its area. */
private val AINV_MIXED = mapOf(
    AinvAct.CAPTURE_NEW to 9, AinvAct.CAPTURE_REPOST to 5, AinvAct.IMPORT_COBUDGET to 7, AinvAct.REIMPORT_COBUDGET to 2,
    AinvAct.IMPORT_COBUDGET_REFUSED to 2, AinvAct.ACCEPT to 12, AinvAct.ADD_MANUAL to 4, AinvAct.DISMISS to 7,
    AinvAct.RESTORE to 6, AinvAct.REFUND_TX to 4, AinvAct.REFUND_NOTICE to 3, AinvAct.UNDO_REFUND to 3,
    AinvAct.DELETE_TX to 3, AinvAct.RENAME_TAG to 4, AinvAct.MERGE_TAGS to 4, AinvAct.DELETE_TAG to 3,
    AinvAct.SET_STEP to 6, AinvAct.CLEAR_STEP to 2, AinvAct.START_PROBE to 3, AinvAct.START_SAVE to 4,
    AinvAct.EXPORT to 3, AinvAct.IMPORT_FROM to 3, AinvAct.IMPORT_FROM_REFUSED to 1, AinvAct.CREATE_BUDGET to 1,
    AinvAct.ADVANCE_TODAY to 2,
)

private val AINV_IMPORTS = AINV_MIXED + mapOf(
    AinvAct.IMPORT_COBUDGET to 25, AinvAct.REIMPORT_COBUDGET to 6, AinvAct.IMPORT_COBUDGET_REFUSED to 5,
    AinvAct.CAPTURE_REPOST to 10, AinvAct.RESTORE to 10, AinvAct.DISMISS to 10,
    AinvAct.SET_STEP to 1, AinvAct.START_SAVE to 1, AinvAct.START_PROBE to 1,
)

private val AINV_BACKUP = AINV_MIXED + mapOf(
    AinvAct.EXPORT to 12, AinvAct.IMPORT_FROM to 12, AinvAct.IMPORT_FROM_REFUSED to 4, AinvAct.START_SAVE to 6,
)

private val AINV_START_DAYS = AINV_MIXED + mapOf(
    AinvAct.SET_STEP to 25, AinvAct.CLEAR_STEP to 6, AinvAct.START_PROBE to 12, AinvAct.START_SAVE to 15,
    AinvAct.ADVANCE_TODAY to 5, AinvAct.IMPORT_COBUDGET to 2,
)

private val AINV_TAGS = AINV_MIXED + mapOf(
    AinvAct.ACCEPT to 20, AinvAct.ADD_MANUAL to 8, AinvAct.RENAME_TAG to 12, AinvAct.MERGE_TAGS to 12,
    AinvAct.DELETE_TAG to 9, AinvAct.REFUND_TX to 6, AinvAct.SET_STEP to 1, AinvAct.START_SAVE to 1,
)

private class AinvWrites {
    var n = 0
}

private class AjustesInvariantRun(
    var db: Db,
    val writes: AinvWrites,
    val seed: Int,
    val profile: String,
    val weights: Map<AinvAct, Int>,
) {
    val rnd = Random(seed)
    val log = ArrayList<String>()
    var step = 0
    var today = AINV_TODAY0

    val events = ArrayList<AinvEvent>()
    /** Event index → its notice, for the events that entered. */
    var entered = LinkedHashMap<Int, NoticeId>()
    /** Event index → the RawNotice of the occurrence that entered. */
    var raws = HashMap<Int, RawNotice>()
    var dismissedModel = HashSet<NoticeId>()
    /** Estorno notice → purchase notice, for registerRefund(NoticeId, NoticeId). */
    var noticeRefunds = LinkedHashMap<NoticeId, NoticeId>()
    /** Stored step edits: budget + week start date (D9). */
    var edits = LinkedHashSet<Pair<BudgetId, LocalDate>>()
    var budgets = ArrayList<BudgetId>()
    var budgetNames = HashSet<String>()

    val temps = ArrayList<String>()
    val backups = ArrayList<AinvBackup>()
    var lastCobudget: Pair<String, List<AinvRow>>? = null

    fun log(s: String) {
        log.add("#$step [$today] $s")
    }

    fun temp(what: String) = ainvTempPath(what).also { temps.add(it) }

    suspend fun run(steps: Int) {
        try {
            setup()
            checkAll()
            val bag = weights.flatMap { (a, w) -> List(w) { a } }
            for (i in 1..steps) {
                step = i
                act(bag[rnd.nextInt(bag.size)])
                checkAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AssertionError(
                "profile=$profile seed=$seed step=$step today=$today: $e\n--- last actions ---\n${log.takeLast(60).joinToString("\n")}", e,
            )
        } finally {
            temps.forEach(::ainvDelete)
        }
    }

    suspend fun setup() {
        log("setup")
        assertTrue(db.addSource(AINV_SRC, AINV_SRC_LABEL, AINV_T0), "addSource")
        repeat(3) { createBudget() }
        repeat(8) { captureNew() }
        importCobudget()
        repeat(4) { accept(valid = true) }
        addManual(valid = true)
    }

    // ---- model helpers ----

    fun model() = AinvModel(
        LinkedHashMap(entered), HashMap(raws), HashSet(dismissedModel), LinkedHashMap(noticeRefunds),
        LinkedHashSet(edits), ArrayList(budgets), HashSet(budgetNames),
    )

    fun restoreModel(m: AinvModel) {
        entered = LinkedHashMap(m.entered)
        raws = HashMap(m.raws)
        dismissedModel = HashSet(m.dismissed)
        noticeRefunds = LinkedHashMap(m.noticeRefunds)
        edits = LinkedHashSet(m.edits)
        budgets = ArrayList(m.budgets)
        budgetNames = HashSet(m.budgetNames)
    }

    suspend fun mo(d: LocalDate): YearMonth = db.monthOf(d)

    suspend fun horizon(): YearMonth = mo(today).ainvShift(4)

    suspend fun state(ref: LocalDate, months: List<YearMonth>): AinvState = AinvState(
        db.pending(),
        db.dismissed(),
        entered.values.sortedBy { it.v }.map { db.notice(it) },
        db.transactions(ref),
        db.tags(),
        db.settings(),
        db.sources(),
        months.map { db.budgetsOverview(it, ref, BudgetOrder.RISK) },
    )

    suspend fun quickState(): AinvState {
        val m = mo(today)
        db.ensureMonths(m)
        return state(today, listOf(m))
    }

    /** A refused write: nothing written and no version bump (Db KDoc: a refused write doesn't bump). */
    suspend fun assertUnchanged(before: AinvState, v0: Long, w0: Int, what: String) {
        assertEquals(v0, db.version.value, "$what: version must not move")
        assertEquals(w0, writes.n, "$what: onWrite must not be called")
        assertEquals(before, quickState(), "$what: nothing written")
    }

    /** A write that changed something: version moved (D6) and onWrite ran once per bump (D21). */
    fun assertBumped(v0: Long, w0: Int, what: String) {
        assertTrue(db.version.value > v0, "$what changed something: version must move (was $v0, now ${db.version.value})")
        assertEquals(db.version.value - v0, (writes.n - w0).toLong(), "$what: one onWrite per version bump (D21)")
    }

    fun newEvent(pkg: String = if (rnd.nextInt(10) < 6) AINV_SRC else AINV_OTHER): AinvEvent {
        val idx = events.size
        val base = AINV_T0 + (idx + 1) * AINV_GAP
        val label = if (pkg == AINV_SRC) AINV_SRC_LABEL else AINV_OTHER_LABEL
        val zeroWhen = rnd.nextInt(100) < 15
        val key = if (zeroWhen) "0|$pkg|z$idx|null|10$idx" else "0|$pkg|${1 + rnd.nextInt(3)}|null|1000${rnd.nextInt(3)}"
        // when ≠ 0 is the event's own time, a little before its post; distinct per event since bases are a GAP apart.
        val whenMs = if (zeroWhen) 0L else base - 1 - rnd.nextLong(60_000)
        if (rnd.nextInt(100) < 6) {
            return AinvEvent(idx, pkg, label, key, whenMs, base, null, if (rnd.nextBoolean()) "" else null, null, "", null)
                .also { events.add(it); log("event $idx blank key=$key") }
        }
        // Sometimes the same content as an earlier event of the same key (a repeat purchase): a different event.
        val twin = if (!zeroWhen && rnd.nextInt(100) < 20) events.lastOrNull { it.key == key && !it.blank } else null
        val ev = if (twin != null) {
            AinvEvent(idx, pkg, label, key, whenMs, base, twin.title, twin.text, twin.bigText, twin.subText, twin.textLines)
        } else {
            val cents = 100L + rnd.nextLong(200_000)
            val merchant = AINV_MERCHANTS.random(rnd)
            val text = when (rnd.nextInt(10)) {
                in 0..5 -> "Compra de R$ ${ainvBrl(cents)} APROVADA em $merchant para o cartão com final 1234."
                in 6..7 -> "A compra em $merchant no valor de R$ ${ainvBrl(cents)} foi estornada no seu cartão."
                8 -> "Recebemos seu pagamento no valor de R$ ${ainvBrl(cents)}. Obrigado!"
                else -> "Seu limite aumentou para R$ 5.000,00"
            }
            AinvEvent(
                idx, pkg, label, key, whenMs, base,
                title = if (rnd.nextInt(5) == 0) null else label,
                text = text,
                bigText = if (rnd.nextBoolean()) text else null,
                subText = if (rnd.nextInt(4) == 0) "Cartão final 1234" else null,
                textLines = if (rnd.nextInt(6) == 0) "linha um\nlinha dois" else null,
            )
        }
        events.add(ev)
        log("event $idx key=$key when=$whenMs twin=${twin?.idx} text=${ev.text?.take(50)}")
        return ev
    }

    /** null and "" are the same text: an occurrence may carry either for an empty field. */
    fun vary(s: String?): String? = if (s.isNullOrEmpty()) (if (rnd.nextBoolean()) null else "") else s

    fun occurrenceRow(ev: AinvEvent, id: Long, posted: Long = ev.base - AINV_JITTER + rnd.nextLong(2 * AINV_JITTER + 1)) = AinvRow(
        id = id, event = ev, postedAt = posted,
        title = vary(ev.title), text = vary(ev.text), bigText = vary(ev.bigText), subText = vary(ev.subText), textLines = vary(ev.textLines),
        // Not content: may differ between occurrences of the same event.
        category = listOf(null, "msg", "status").random(rnd), channelId = listOf(null, "transactions", "default").random(rnd),
        extrasJson = "{\"n\":${rnd.nextInt(1000)}}",
        dismissed = if (rnd.nextInt(10) < 3) 1 else 0,
    )

    suspend fun pendingIds(): List<NoticeId> = db.pending().map { it.id }

    // ---- actions ----

    suspend fun act(a: AinvAct) {
        when (a) {
            AinvAct.CAPTURE_NEW -> captureNew()
            AinvAct.CAPTURE_REPOST -> captureRepost()
            AinvAct.IMPORT_COBUDGET -> importCobudget()
            AinvAct.REIMPORT_COBUDGET -> reimportCobudget()
            AinvAct.IMPORT_COBUDGET_REFUSED -> importCobudgetRefused()
            AinvAct.ACCEPT -> accept(valid = rnd.nextInt(10) != 0)
            AinvAct.ADD_MANUAL -> addManual(valid = rnd.nextInt(10) != 0)
            AinvAct.DISMISS -> dismiss()
            AinvAct.RESTORE -> restore()
            AinvAct.REFUND_TX -> refundTx()
            AinvAct.REFUND_NOTICE -> refundNotice()
            AinvAct.UNDO_REFUND -> undoRefund()
            AinvAct.DELETE_TX -> deleteTx()
            AinvAct.RENAME_TAG -> renameTag()
            AinvAct.MERGE_TAGS -> mergeTags()
            AinvAct.DELETE_TAG -> deleteTag()
            AinvAct.SET_STEP -> setStep()
            AinvAct.CLEAR_STEP -> clearStep()
            AinvAct.START_PROBE -> startProbe()
            AinvAct.START_SAVE -> startSave()
            AinvAct.EXPORT -> export()
            AinvAct.IMPORT_FROM -> importFrom()
            AinvAct.IMPORT_FROM_REFUSED -> importFromRefused()
            AinvAct.CREATE_BUDGET -> createBudget()
            AinvAct.ADVANCE_TODAY -> {
                today = today.ainvDays(1 + rnd.nextInt(20))
                log("today -> $today")
            }
        }
    }

    /** Capture of one occurrence of [ev]: saved exactly when it has text, comes from a source and its event hasn't entered (Capture.kt KDoc). */
    suspend fun captureOccurrence(ev: AinvEvent) {
        val raw = occurrenceRow(ev, 0).raw()
        val expected = !ev.blank && ev.fromSource && ev.idx !in entered
        val v0 = db.version.value
        val w0 = writes.n
        val before = if (expected) null else quickState()
        val saved = capture(db, raw)
        log("capture ev=${ev.idx} posted=${raw.postedAt} -> $saved (expected $expected)")
        assertEquals(expected, saved, "capture of event ${ev.idx} (blank=${ev.blank} source=${ev.fromSource} entered=${ev.idx in entered}) raw=$raw")
        if (saved) {
            assertBumped(v0, w0, "capture")
            val rows = db.pending().filter { it.raw.key == ev.key && it.raw.whenMs == ev.whenMs }
            assertEquals(1, rows.size, "the captured notice is pending, once: $rows")
            entered[ev.idx] = rows[0].id
            raws[ev.idx] = raw
        } else {
            assertUnchanged(before!!, v0, w0, "refused capture")
        }
    }

    suspend fun captureNew() {
        captureOccurrence(newEvent(if (rnd.nextInt(10) < 8) AINV_SRC else AINV_OTHER))
    }

    suspend fun captureRepost() {
        if (events.isEmpty()) return captureNew()
        captureOccurrence(events.random(rnd))
    }

    fun genRows(): List<AinvRow> {
        val n = 1 + rnd.nextInt(12)
        val ids = (1L..5000L).shuffled(rnd).take(n)
        val rows = ArrayList<AinvRow>()
        for (i in 0 until n) {
            val r = rnd.nextInt(100)
            val enteredIdx = entered.keys.toList()
            val ev = when {
                r < 45 || events.isEmpty() -> newEvent()
                r < 70 && enteredIdx.isNotEmpty() -> events[enteredIdx.random(rnd)]
                r < 88 -> events.random(rnd)
                rows.isNotEmpty() -> rows.random(rnd).event
                else -> newEvent()
            }
            val earlier = rows.firstOrNull { it.event === ev }
            // Half the repeats inside one file share the posted time: then the id decides (KDoc: "in postedAt order (then id)").
            val row = if (earlier != null && rnd.nextBoolean()) occurrenceRow(ev, ids[i], earlier.postedAt) else occurrenceRow(ev, ids[i])
            rows.add(row)
        }
        return rows
    }

    /**
     * importCobudget of [rows] at [path]: the counts and the notices that enter, by hand from the KDoc. Rows
     * in postedAt order (then id); a row without any text is skipped; a row whose event is already in the
     * db, or entered earlier in this call, is a re-post and skipped; the others enter with their dismissed.
     */
    suspend fun importRows(path: String, rows: List<AinvRow>, what: String) {
        val ordered = rows.sortedWith(compareBy<AinvRow>({ it.postedAt }, { it.id }))
        val seen = HashSet(entered.keys)
        val entering = ArrayList<AinvRow>()
        var skipped = 0
        for (r in ordered) {
            if (r.blank || !seen.add(r.event.idx)) skipped++ else entering.add(r)
        }
        val expected = CobudgetImport(
            opened = true,
            pending = entering.count { it.dismissed == 0 },
            dismissed = entering.count { it.dismissed == 1 },
            skipped = skipped,
        )
        // pending + dismissed + skipped = every row of the file.
        assertEquals(rows.size, expected.pending + expected.dismissed + expected.skipped)

        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val got = importCobudget(db, path)
        log("$what ${rows.size} rows -> $got (expected $expected)\n    ${ordered.joinToString("\n    ")}")
        assertEquals(expected, got, "$what: counts (rows in order: $ordered)")

        if (entering.isEmpty()) {
            // KDoc: bumps version once "when something entered"; nothing entered, nothing written.
            assertUnchanged(before, v0, w0, "$what with nothing new")
            return
        }
        // D40: one write for the whole file, one version bump, one onWrite.
        assertEquals(v0 + 1, db.version.value, "$what: exactly one version bump for the whole file")
        assertEquals(w0 + 1, writes.n, "$what: exactly one onWrite for the whole file")

        // Nothing else changes: transactions, tags, settings, sources, and every notice already there.
        val after = quickState()
        assertEquals(before.transactions, after.transactions, "$what: transactions untouched")
        assertEquals(before.tags, after.tags, "$what: tags untouched")
        assertEquals(before.settings, after.settings, "$what: settings untouched")
        assertEquals(before.sources, after.sources, "$what: sources untouched (D3: sources are not imported)")
        assertEquals(before.notices, entered.values.sortedBy { it.v }.map { db.notice(it) }, "$what: notices already there untouched")

        val pendingRows = after.pending
        val dismissedRows = after.dismissed
        for (r in entering) {
            val list = if (r.dismissed == 1) dismissedRows else pendingRows
            val other = if (r.dismissed == 1) pendingRows else dismissedRows
            val match = list.filter { it.raw.key == r.event.key && it.raw.whenMs == r.event.whenMs }
            assertEquals(1, match.size, "$what: $r entered ${if (r.dismissed == 1) "Riscados" else "the Pauta"} once: $match")
            assertTrue(other.none { it.raw.key == r.event.key && it.raw.whenMs == r.event.whenMs }, "$what: $r not in the other list")
            assertEquals(r.raw(), match[0].raw, "$what: the notice is the row as a RawNotice (NULL → \"\")")
            assertTrue(match[0].id !in entered.values, "$what: a new notice id")
            entered[r.event.idx] = match[0].id
            raws[r.event.idx] = r.raw()
            if (r.dismissed == 1) dismissedModel.add(match[0].id)
        }
        assertEquals(before.pending.size + expected.pending, pendingRows.size, "$what: Pauta grew by the pending count")
        assertEquals(before.dismissed.size + expected.dismissed, dismissedRows.size, "$what: Riscados grew by the dismissed count")
    }

    suspend fun importCobudget() {
        val rows = genRows()
        val path = temp("cobudget")
        ainvWriteCobudget(path, rows.shuffled(rnd))
        lastCobudget = path to rows
        importRows(path, rows, "importCobudget")
    }

    /** KDoc: "Importing the same file again adds nothing" — unless an importFrom brought back an older db since. */
    suspend fun reimportCobudget() {
        val last = lastCobudget ?: return importCobudget()
        importRows(last.first, last.second, "re-importCobudget")
    }

    suspend fun importCobudgetRefused() {
        val ev = newEvent(AINV_SRC).let { if (it.blank) newEvent(AINV_SRC) else it }
        val row = occurrenceRow(ev, 1).let { AinvRow(it.id, it.event, it.postedAt, it.title, it.text, it.bigText, it.subText, it.textLines, it.category, it.channelId, it.extrasJson, 0) }
        val path = temp("refused")
        val kind = rnd.nextInt(7)
        when (kind) {
            0 -> ainvWriteCobudget(path, listOf(row), userVersion = 6)
            1 -> ainvWriteCobudget(path, listOf(row), userVersion = 8)
            2 -> ainvWriteCobudget(path, listOf(row), table = AINV_V7_TABLE_NO_WHEN)
            3 -> {
                val c = BundledSQLiteDriver().open(path)
                try {
                    c.execSQL("CREATE TABLE `notifications` (`id` INTEGER PRIMARY KEY, `text` TEXT)")
                    c.execSQL("INSERT INTO `notifications`(`text`) VALUES ('Compra de R$ 10,00 APROVADA em LOJA X')")
                    c.execSQL("PRAGMA user_version = 7")
                } finally {
                    c.close()
                }
            }
            4 -> ainvWriteBytes(path, ByteArray(4096) { rnd.nextInt(256).toByte() })
            5 -> assertTrue(db.exportTo(path), "export, to try it as a cobudget backup")
            else -> {} // no file at all
        }
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val got = importCobudget(db, path)
        log("importCobudget refused kind=$kind -> $got")
        // Types.kt KDoc: opened = false and every count 0; Capture.kt KDoc: nothing written, db untouched.
        assertEquals(CobudgetImport(), got, "importCobudget of a file that isn't a cobudget v7 backup (kind $kind)")
        assertUnchanged(before, v0, w0, "refused importCobudget kind $kind")
    }

    fun randomForm(): TransactionForm {
        val tags = List(1 + rnd.nextInt(3)) { AINV_TAG_POOL.random(rnd) }
        return TransactionForm(
            budget = budgets.random(rnd),
            tags = tags,
            total = Cents(1L + rnd.nextLong(200_000)),
            installments = if (rnd.nextInt(4) == 0) 2 + rnd.nextInt(11) else 1,
            merchant = AINV_MERCHANTS.random(rnd),
            date = today.ainvDays(-rnd.nextInt(90)),
            time = LocalTime(rnd.nextInt(24), rnd.nextInt(60)),
        )
    }

    /** A form [Db.accept] refuses: no tag left after cleaning (spec §2.4). */
    fun refusedForm() = randomForm().copy(tags = listOf("   ", ""))

    suspend fun checkNewTransaction(id: TransactionId, form: TransactionForm, notice: NoticeId, what: String) {
        val row = db.transactions(today).single { it.id == id }
        assertEquals(notice, row.notice, "$what: notice of the new transaction")
        assertEquals(ainvClean(form.tags).sorted(), row.tags, "$what: tags cleaned and sorted")
    }

    suspend fun accept(valid: Boolean) {
        val pend = pendingIds()
        val dis = db.dismissed().map { it.id }
        val useDismissed = !valid && dis.isNotEmpty() && rnd.nextBoolean()
        val target = when {
            useDismissed -> dis.random(rnd)
            pend.isEmpty() -> return captureNew()
            else -> pend.random(rnd)
        }
        val form = if (valid) randomForm() else if (useDismissed) randomForm() else refusedForm()
        val before = if (valid) null else quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val id = db.accept(target, form)
        log("accept notice=${target.v} valid=$valid dismissedTarget=$useDismissed tags=${form.tags} -> ${id.v}")
        if (valid) {
            assertNotEquals(TransactionId(0), id, "accept of a pending notice with a valid form")
            assertBumped(v0, w0, "accept")
            assertEquals(NoticeStatus.PROCESSED, db.notice(target).status, "accepted notice is processed")
            checkNewTransaction(id, form, target, "accept")
        } else {
            assertEquals(TransactionId(0), id, "accept refused (no tag, or a dismissed notice)")
            assertUnchanged(before!!, v0, w0, "refused accept")
        }
    }

    suspend fun addManual(valid: Boolean) {
        val form = if (valid) randomForm() else refusedForm()
        val before = if (valid) null else quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val id = db.addManual(form).transaction
        log("addManual valid=$valid tags=${form.tags} -> ${id.v}")
        if (valid) {
            assertNotEquals(TransactionId(0), id)
            assertBumped(v0, w0, "addManual")
            checkNewTransaction(id, form, NoticeId(0), "addManual")
        } else {
            assertEquals(TransactionId(0), id)
            assertUnchanged(before!!, v0, w0, "refused addManual")
        }
    }

    suspend fun dismiss() {
        val pend = pendingIds()
        val bad = pend.isEmpty() || rnd.nextInt(8) == 0
        val target = if (!bad) pend.random(rnd) else (db.dismissed().map { it.id } + db.transactions().map { it.notice }.filter { it.v != 0L }).randomOrNull(rnd) ?: return
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val ok = db.dismiss(target)
        log("dismiss ${target.v} (pending=${!bad}) -> $ok")
        assertEquals(!bad, ok, "Db.dismiss: only a pending notice")
        if (ok) {
            assertBumped(v0, w0, "dismiss")
            dismissedModel.add(target)
            assertEquals(NoticeStatus.DISMISSED, db.notice(target).status)
            assertEquals(before.pending.filter { it.id != target }.map { it.id }, pendingIds(), "dismiss takes only that notice from the Pauta")
        } else {
            assertUnchanged(before, v0, w0, "refused dismiss")
        }
    }

    suspend fun restore() {
        val dis = db.dismissed().map { it.id }
        val bad = dis.isEmpty() || rnd.nextInt(8) == 0
        val target = if (!bad) dis.random(rnd) else (pendingIds() + db.transactions().map { it.notice }.filter { it.v != 0L }).randomOrNull(rnd) ?: return
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val ok = db.restore(target)
        log("restore ${target.v} (dismissed=${!bad}) -> $ok")
        assertEquals(!bad, ok, "Db.restore: only a dismissed notice (spec §7.6 Trazer de volta)")
        if (ok) {
            assertBumped(v0, w0, "restore")
            dismissedModel.remove(target)
            // Spec §7.6: back to the Pauta at once, without Desfazer.
            assertEquals(NoticeStatus.PENDING, db.notice(target).status)
            assertEquals(before.dismissed.filter { it.id != target }.map { it.id }, db.dismissed().map { it.id }, "restore takes only that notice from Riscados")
        } else {
            assertUnchanged(before, v0, w0, "refused restore")
        }
    }

    suspend fun refundTx() {
        val pend = db.pending()
        val txs = db.transactions()
        val open = txs.filter { !it.refunded }
        if (pend.isEmpty() || txs.isEmpty()) return captureNew()
        val estorno = (pend.filter { it.parsed.isRefund }.ifEmpty { pend }).random(rnd).id
        val refused = open.isEmpty() || rnd.nextInt(6) == 0
        val purchase = if (!refused) open.random(rnd).id else (txs.filter { it.refunded }.map { it.id } + AINV_UNKNOWN_TX).random(rnd)
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val ok = db.registerRefund(estorno, purchase)
        log("registerRefund estorno=${estorno.v} tx=${purchase.v} (refused=$refused) -> $ok")
        assertEquals(!refused, ok, "registerRefund(notice, transaction): a pending notice and a transaction not yet refunded")
        if (ok) {
            assertBumped(v0, w0, "registerRefund")
            assertEquals(estorno, db.transactions().single { it.id == purchase }.estorno)
        } else {
            assertUnchanged(before, v0, w0, "refused registerRefund")
        }
    }

    suspend fun refundNotice() {
        val pend = pendingIds()
        if (pend.size < 2) return captureNew()
        val estorno = pend.random(rnd)
        val same = rnd.nextInt(6) == 0
        val purchase = if (same) estorno else (pend - estorno).random(rnd)
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val ok = db.registerRefund(estorno, purchase)
        log("registerRefund estorno=${estorno.v} notice=${purchase.v} -> $ok")
        assertEquals(!same, ok, "registerRefund(notice, notice): both pending and different")
        if (ok) {
            assertBumped(v0, w0, "registerRefund on a notice")
            noticeRefunds[estorno] = purchase
            assertEquals(NoticeStatus.PROCESSED, db.notice(estorno).status)
            assertEquals(NoticeStatus.PROCESSED, db.notice(purchase).status)
        } else {
            assertUnchanged(before, v0, w0, "refused registerRefund on the same notice")
        }
    }

    suspend fun undoRefund() {
        val onTx = db.transactions().filter { it.refunded }.map { it.estorno }
        val all = onTx + noticeRefunds.keys
        val refused = all.isEmpty() || rnd.nextInt(6) == 0
        val estorno = if (!refused) all.random(rnd) else pendingIds().randomOrNull(rnd) ?: return
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val ok = db.undoRefund(estorno)
        log("undoRefund ${estorno.v} -> $ok")
        assertEquals(!refused, ok, "undoRefund of a registered estorno (and only of one)")
        if (ok) {
            assertBumped(v0, w0, "undoRefund")
            assertEquals(NoticeStatus.PENDING, db.notice(estorno).status, "the estorno notice is pending again")
            noticeRefunds.remove(estorno)?.let { p -> assertEquals(NoticeStatus.PENDING, db.notice(p).status, "the purchase notice is pending again") }
            assertTrue(db.transactions().none { it.estorno == estorno })
        } else {
            assertUnchanged(before, v0, w0, "refused undoRefund")
        }
    }

    suspend fun deleteTx() {
        val txs = db.transactions(today)
        if (txs.isEmpty()) return
        val t = txs.random(rnd)
        val v0 = db.version.value
        val w0 = writes.n
        db.deleteTransaction(t.id)
        log("deleteTransaction ${t.id.v} notice=${t.notice.v} estorno=${t.estorno.v}")
        assertBumped(v0, w0, "deleteTransaction")
        assertEquals(txs.filter { it.id != t.id }, db.transactions(today), "only that transaction goes")
        // Spec §2.8: its notice and its estorno notice go back to the Pauta.
        if (t.notice.v != 0L) assertEquals(NoticeStatus.PENDING, db.notice(t.notice).status)
        if (t.estorno.v != 0L) assertEquals(NoticeStatus.PENDING, db.notice(t.estorno).status)
    }

    suspend fun renameTag() {
        val tags = db.tags()
        val txs = db.transactions(today)
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val r = rnd.nextInt(10)
        if (tags.isEmpty() || r == 0) {
            val id = if (tags.isEmpty() || rnd.nextBoolean()) AINV_UNKNOWN_TAG else tags.random(rnd).id
            val name = if (id == AINV_UNKNOWN_TAG) "qualquer" else "   "
            val ok = db.renameTag(id, name)
            log("renameTag ${id.v} \"$name\" -> $ok")
            assertFalse(ok, "renameTag: unknown tag or blank name")
            return assertUnchanged(before, v0, w0, "refused renameTag")
        }
        val tag = tags.random(rnd)
        // A name in another case/spacing of an existing tag, or a fresh one.
        val name = if (r < 4 && tags.size > 1) {
            val o = tags.filter { it.id != tag.id }.random(rnd).name
            if (rnd.nextBoolean()) " ${o.uppercase()} " else o
        } else AINV_RENAME_POOL.random(rnd)
        val clean = name.trim().lowercase()
        val clash = tags.any { it.id != tag.id && it.name == clean }
        val ok = db.renameTag(tag.id, name)
        log("renameTag ${tag.id.v} \"${tag.name}\" -> \"$name\" (clash=$clash) -> $ok")
        assertEquals(!clash, ok, "renameTag: false only for the name of another tag (KDoc)")
        if (!ok) return assertUnchanged(before, v0, w0, "refused renameTag")
        if (clean != tag.name) assertBumped(v0, w0, "renameTag")
        val tags1 = db.tags()
        assertEquals(clean, tags1.single { it.id == tag.id }.name, "renamed, trimmed and lowercased")
        assertEquals(tags.map { if (it.id == tag.id) it.copy(name = clean) else it }.sortedBy { it.name }, tags1, "only that tag's name changes")
        val expected = txs.map { t -> if (tag.name in t.tags) t.copy(tags = (t.tags - tag.name + clean).sorted()) else t }
        assertEquals(expected, db.transactions(today), "every transaction with the tag shows the new name; nothing else changes")
        assertEquals(before.pending, db.pending())
        assertEquals(before.dismissed, db.dismissed())
    }

    suspend fun mergeTags() {
        val tags = db.tags()
        val txs = db.transactions(today)
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        if (tags.size < 2 || rnd.nextInt(8) == 0) {
            val t = tags.randomOrNull(rnd)?.id ?: AINV_UNKNOWN_TAG
            val into = if (rnd.nextBoolean()) t else AINV_UNKNOWN_TAG
            val ok = db.mergeTags(t, into)
            log("mergeTags ${t.v} -> ${into.v} -> $ok")
            assertFalse(ok, "mergeTags: refused when the same tag or one is unknown")
            return assertUnchanged(before, v0, w0, "refused mergeTags")
        }
        val from = tags.random(rnd)
        val into = tags.filter { it.id != from.id }.random(rnd)
        val ok = db.mergeTags(from.id, into.id)
        log("mergeTags \"${from.name}\"(${from.uses}) -> \"${into.name}\"(${into.uses}) -> $ok")
        assertTrue(ok, "mergeTags of two existing tags")
        assertBumped(v0, w0, "mergeTags")
        // Spec §7.6 Juntar com: the transactions pass to [into]; [from] is gone; same transactions.
        val expected = txs.map { t -> if (from.name in t.tags) t.copy(tags = (t.tags - from.name + into.name).distinct().sorted()) else t }
        assertEquals(expected, db.transactions(today), "merge keeps every transaction, moving the tag")
        val tags1 = db.tags()
        assertTrue(tags1.none { it.id == from.id || it.name == from.name }, "the source tag is gone")
        assertEquals(tags.map { it.name } - from.name, tags1.map { it.name }, "only the source tag is gone")
        val union = txs.count { from.name in it.tags || into.name in it.tags }
        assertEquals(union, tags1.single { it.id == into.id }.uses, "into is used by the union of both tags' transactions")
        assertEquals(before.pending, db.pending(), "merge moves no notice")
        assertEquals(before.dismissed, db.dismissed())
    }

    suspend fun deleteTag() {
        val tags = db.tags()
        val txs = db.transactions(today)
        val pend0 = pendingIds().toSet()
        val dis0 = db.dismissed()
        val v0 = db.version.value
        val w0 = writes.n
        if (tags.isEmpty() || rnd.nextInt(10) == 0) {
            val before = quickState()
            db.deleteTag(AINV_UNKNOWN_TAG)
            log("deleteTag unknown")
            return assertUnchanged(before, v0, w0, "deleteTag of an unknown tag")
        }
        val tag = tags.random(rnd)
        // Spec §2.9: the transactions left without any tag are deleted; TagRow.soleUses counts them.
        val doomed = txs.filter { it.tags == listOf(tag.name) }
        assertEquals(tag.soleUses, doomed.size, "TagRow.soleUses = transactions with only this tag")
        db.deleteTag(tag.id)
        log("deleteTag \"${tag.name}\" uses=${tag.uses} sole=${tag.soleUses} -> doomed ${doomed.map { it.id.v }}")
        assertBumped(v0, w0, "deleteTag")
        val doomedIds = doomed.map { it.id }.toSet()
        val expected = txs.filter { it.id !in doomedIds }.map { t -> if (tag.name in t.tags) t.copy(tags = t.tags - tag.name) else t }
        assertEquals(expected, db.transactions(today), "exactly the transactions left without tags are deleted; the others lose the tag")
        assertEquals(tags.map { it.name } - tag.name, db.tags().map { it.name }, "only that tag is gone")
        // Their notices (and the estorno notices registered on them, as in spec §2.8) go back to the Pauta.
        val back = doomed.flatMap { listOf(it.notice, it.estorno) }.filter { it.v != 0L }.toSet()
        assertEquals(pend0 + back, pendingIds().toSet(), "the Pauta gains exactly the notices of the deleted transactions")
        assertEquals(dis0, db.dismissed(), "Riscados untouched")
    }

    suspend fun createBudget() {
        var name: String
        do {
            name = "Verba ${rnd.nextInt(1000)}"
        } while (name.lowercase() in budgetNames)
        val v0 = db.version.value
        val w0 = writes.n
        val id = db.createBudget(name, Cents(10_000L + rnd.nextLong(500_000)), AINV_BUDGET_DAY)
        log("createBudget \"$name\" -> ${id.v}")
        assertNotEquals(BudgetId(0), id, "createBudget with a new name")
        assertBumped(v0, w0, "createBudget")
        budgets.add(id)
        budgetNames.add(name.lowercase())
    }

    suspend fun setStep() {
        val b = budgets.random(rnd)
        val m = mo(today).ainvShift(rnd.nextInt(4) - 1)
        db.ensureMonths(m)
        val weeks = db.budgetDetail(b, m, today).month.weeks
        if (weeks.isEmpty()) {
            log("setStep: budget ${b.v} has no weeks in $m")
            return
        }
        val wk = weeks.random(rnd)
        val valid = !wk.locked && wk.max.v >= 0 && rnd.nextInt(6) != 0
        val stepValue = if (valid) Cents(rnd.nextLong(wk.max.v + 1)) else Cents(maxOf(0L, wk.max.v + 1 + rnd.nextLong(1000)))
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val ok = db.setStep(b, m, wk.start, stepValue)
        log("setStep b=${b.v} $m week=${wk.start} locked=${wk.locked} max=${wk.max.v} step=${stepValue.v} -> $ok")
        // Week.max / Week.locked KDoc: 0..max accepted, max + 1 refused, a locked week refused with any value.
        assertEquals(valid, ok, "setStep against Week.max=${wk.max.v} locked=${wk.locked}")
        if (!ok) return assertUnchanged(before, v0, w0, "refused setStep")
        if (!wk.edited || wk.step != stepValue) assertBumped(v0, w0, "setStep")
        edits.add(b to wk.start)
        val w1 = db.budgetDetail(b, m, today).month.weeks.single { it.start == wk.start }
        assertTrue(w1.edited, "the week is edited")
        assertEquals(stepValue, w1.step, "the edited step")
    }

    suspend fun clearStep() {
        val b = budgets.random(rnd)
        val m = mo(today).ainvShift(rnd.nextInt(4) - 1)
        db.ensureMonths(m)
        val weeks = db.budgetDetail(b, m, today).month.weeks
        if (weeks.isEmpty()) return
        val editedWeeks = weeks.filter { it.edited }
        val wk = if (editedWeeks.isNotEmpty() && rnd.nextInt(5) != 0) editedWeeks.random(rnd) else weeks.random(rnd)
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val ok = db.clearStep(b, m, wk.start)
        log("clearStep b=${b.v} $m week=${wk.start} edited=${wk.edited} -> $ok")
        assertEquals(wk.edited, ok, "clearStep: true exactly for an edited week")
        if (!ok) return assertUnchanged(before, v0, w0, "refused clearStep")
        assertBumped(v0, w0, "clearStep")
        edits.remove(b to wk.start)
        assertFalse(db.budgetDetail(b, m, today).month.weeks.single { it.start == wk.start }.edited)
    }

    /** Start of the budget month that holds [today], by the current month start day (BudgetMonth.start). */
    suspend fun todayMonthStart(): LocalDate {
        val m = mo(today)
        db.ensureMonths(m)
        val start = db.budgetDetail(budgets.first(), m, today).month.start
        assertNotEquals(NO_DATE, start, "budget ${budgets.first().v} has month $m")
        return start
    }

    fun pickDay(current: Int): Int = when (rnd.nextInt(8)) {
        0, 1 -> current
        2 -> 28 + rnd.nextInt(4)
        else -> 1 + rnd.nextInt(31)
    }

    fun pickWeek(current: DayOfWeek): DayOfWeek = if (rnd.nextBoolean()) current else DayOfWeek.entries.random(rnd)

    /** startDropsEdits alone (the screen's draft, then Cancelar): a pure read; checked where the answer is decidable without saving. */
    suspend fun startProbe() {
        val s = db.settings()
        val d = if (rnd.nextInt(6) == 0) listOf(0, 32, -1, 99).random(rnd) else pickDay(s.monthStartDay)
        val w = pickWeek(s.weekStart)
        val start = todayMonthStart()
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val drops = db.startDropsEdits(d, w, today)
        log("startDropsEdits($d, $w) probe -> $drops; edits=${edits.map { "${it.first.v}@${it.second}" }}")
        assertUnchanged(before, v0, w0, "startDropsEdits is a pure read")
        val changes = d in 1..31 && (d != s.monthStartDay || w != s.weekStart)
        if (!changes) {
            // KDoc: false when neither changes or the day is outside 1..31.
            assertFalse(drops, "startDropsEdits($d, $w) with settings ${s.monthStartDay}/${s.weekStart}")
            return
        }
        // The cut is the earlier of the two starts of today's month (D10, D41), so ≤ the current start:
        // an edit at or after the current start is always dropped.
        if (edits.any { it.second >= start }) assertTrue(drops, "an edit at or after $start would be dropped")
        // A budget month is at most 31 days and holds today, so the new start is ≥ today − 30: with no edit
        // after today − 32 and none at or after the current start, nothing is dropped.
        else if (edits.none { it.second > today.ainvDays(-32) }) assertFalse(drops, "no edit can be at or after the cut")
    }

    /** Ajustes › Início do mês e da semana › Salvar: startDropsEdits, then setMonthStartDay and setWeekStart (D41). */
    suspend fun startSave() {
        val s0 = db.settings()
        val d = pickDay(s0.monthStartDay)
        val w = pickWeek(s0.weekStart)
        val startBefore = todayMonthStart()
        val v0 = db.version.value
        val w0 = writes.n
        val drops = db.startDropsEdits(d, w, today)
        assertEquals(v0, db.version.value, "startDropsEdits is a pure read: version")
        assertEquals(w0, writes.n, "startDropsEdits is a pure read: onWrite")
        assertTrue(db.setMonthStartDay(d, today), "setMonthStartDay($d)")
        db.setWeekStart(w, today)
        val s1 = db.settings()
        assertEquals(s0.copy(monthStartDay = d, weekStart = w), s1, "only the two start settings change")
        val startAfter = todayMonthStart()
        val changed = d != s0.monthStartDay || w != s0.weekStart
        // D10/D41: the saves delete every edit from the start of today's month, by the old or the new day,
        // whichever is earlier; startDropsEdits says whether that deletes anything.
        val cut = minOf(startBefore, startAfter)
        val dropped = if (changed) edits.filter { it.second >= cut } else emptyList()
        log(
            "save start days ${s0.monthStartDay}/${s0.weekStart} -> $d/$w startBefore=$startBefore startAfter=$startAfter cut=$cut " +
                "drops=$drops dropped=${dropped.map { "${it.first.v}@${it.second}" }}",
        )
        assertEquals(dropped.isNotEmpty(), drops, "startDropsEdits($d, $w) agrees with what the saves delete (cut $cut, edits $edits)")
        edits.removeAll(dropped.toSet())
        if (changed) assertBumped(v0, w0, "saving new start days") else {
            // setMonthStartDay KDoc: the day already set changes nothing; Db.version: no change, no bump.
            assertEquals(v0, db.version.value, "saving the same start days changes nothing")
            assertEquals(w0, writes.n)
        }
        if (changed) {
            // Every month from today's on has no edit left.
            for (k in 0..3) {
                val m = mo(today).ainvShift(k)
                db.ensureMonths(m)
                for (b in budgets) {
                    val weeks = db.budgetDetail(b, m, today).month.weeks
                    assertTrue(weeks.none { it.edited }, "no step edit left in $m for budget ${b.v} after changing the start days")
                }
            }
        }
    }

    suspend fun export() {
        val path = temp("backup")
        val h = horizon()
        db.ensureMonths(h)
        val m0 = mo(today)
        val months = (-2..3).map { m0.ainvShift(it) }
        val s = state(today, months)
        val v0 = db.version.value
        val w0 = writes.n
        val ok = db.exportTo(path)
        log("exportTo $path -> $ok (backup #${backups.size})")
        assertTrue(ok, "exportTo a new file")
        assertEquals(v0, db.version.value, "exportTo writes nothing in the db: no version bump")
        assertEquals(w0, writes.n)
        assertEquals(s, state(today, months), "exportTo changes nothing")
        backups.add(AinvBackup(path, s, model(), today, months, h))
    }

    suspend fun importFrom() {
        if (backups.isEmpty()) return export()
        val b = if (rnd.nextInt(3) == 0) backups.random(rnd) else backups.last()
        // The KDoc doesn't say whether importFrom keeps the file it reads: import a fresh copy, keep the backup.
        val copy = temp("restore")
        SystemFileSystem.source(Path(b.path)).buffered().use { src ->
            SystemFileSystem.sink(Path(copy)).buffered().use { it.transferFrom(src) }
        }
        val v0 = db.version.value
        val ok = db.importFrom(copy)
        log("importFrom backup of ${b.ref} -> $ok")
        assertTrue(ok, "importFrom of an exportTo copy")
        // D14: reopens, migrates and bumps version.
        assertTrue(db.version.value > v0, "importFrom bumps version")
        restoreModel(b.model)
        db.ensureMonths(b.horizon)
        val got = state(b.ref, b.months)
        // Spec §7.6: importing restores the whole backup in place of the current data.
        assertEquals(b.state, got, "exportTo then importFrom restores exactly the observable state")
    }

    suspend fun importFromRefused() {
        val path = temp("notmargem")
        val kind = rnd.nextInt(4)
        when (kind) {
            0 -> ainvWriteCobudget(path, listOf(occurrenceRow(newEvent(AINV_SRC), 1)))
            1 -> ainvWriteBytes(path, ByteArray(2048) { rnd.nextInt(256).toByte() })
            2 -> ainvWriteBytes(path, ByteArray(0))
            else -> {} // no file
        }
        val before = quickState()
        val v0 = db.version.value
        val w0 = writes.n
        val ok = db.importFrom(path)
        log("importFrom not-a-Margem-db kind=$kind -> $ok")
        // Db.importFrom KDoc: false, with the current database untouched; spec §7.6: refused, nothing changes.
        assertFalse(ok, "importFrom of a file that isn't a Margem database (kind $kind)")
        assertUnchanged(before, v0, w0, "refused importFrom kind $kind")
    }

    // ---- invariants after every step ----

    suspend fun checkAll() {
        val v0 = db.version.value
        val w0 = writes.n
        val settings = db.settings()
        db.ensureMonths(horizon())
        val pending = db.pending()
        val dismissed = db.dismissed()
        val txs = db.transactions(today)
        val tags = db.tags()

        // Spec §2: every notice is exactly one of pending / dismissed / processed.
        assertEquals(pending.size, db.pendingCount(), "pendingCount = pending().size")
        val pIds = pending.map { it.id }
        val dIds = dismissed.map { it.id }
        assertEquals(pIds.size, pIds.toSet().size, "no notice twice in the Pauta")
        assertEquals(dIds.size, dIds.toSet().size, "no notice twice in Riscados")
        assertTrue(pIds.intersect(dIds.toSet()).isEmpty(), "no notice both pending and dismissed")
        assertTrue(pending.all { it.status == NoticeStatus.PENDING }, "Pauta rows are PENDING")
        assertTrue(dismissed.all { it.status == NoticeStatus.DISMISSED }, "Riscados rows are DISMISSED")
        // Spec §7.2 and §7.6: newest first.
        assertEquals(pending.sortedByDescending { it.raw.postedAt }.map { it.raw.postedAt }, pending.map { it.raw.postedAt }, "Pauta newest first")
        assertEquals(dismissed.sortedByDescending { it.raw.postedAt }.map { it.raw.postedAt }, dismissed.map { it.raw.postedAt }, "Riscados newest first")
        for (n in pending + dismissed) assertEquals(n.raw.postedAt > settings.pautaSeenAt, n.isNew, "isNew of ${n.id.v} (Settings.pautaSeenAt KDoc)")

        // Architecture §5: processed = referenced by a transaction or by an estorno.
        val refs = HashSet<NoticeId>()
        for (t in txs) {
            assertTrue(t.tags.isNotEmpty(), "spec §2.4: no transaction without a tag: $t")
            assertEquals(t.tags.sorted(), t.tags, "TransactionRow.tags sorted: $t")
            assertEquals(t.tags.distinct(), t.tags, "no tag twice on one transaction: $t")
            if (t.notice.v != 0L) assertTrue(refs.add(t.notice), "two transactions on notice ${t.notice.v}")
            if (t.estorno.v != 0L) assertTrue(refs.add(t.estorno), "notice ${t.estorno.v} used twice")
        }
        for ((e, p) in noticeRefunds) {
            assertTrue(refs.add(e), "estorno notice ${e.v} used twice")
            assertTrue(refs.add(p), "purchase notice ${p.v} used twice")
        }
        val known = entered.values.toSet()
        assertEquals(entered.size, known.size, "one notice per event")
        assertTrue(known.containsAll(refs), "every referenced notice is a notice the test knows: ${refs - known}")
        assertTrue(known.containsAll(dismissedModel), "dismissed model ⊆ known")
        assertTrue(refs.intersect(dismissedModel).isEmpty(), "a processed notice is never dismissed")
        assertEquals(dismissedModel, dIds.toSet(), "Riscados = what was dismissed (and imported dismissed) and not brought back")
        assertEquals(known - dismissedModel - refs, pIds.toSet(), "Pauta = every other notice")
        for (n in pending + dismissed) {
            val ev = entered.entries.single { it.value == n.id }.key
            assertEquals(raws[ev], n.raw, "notice ${n.id.v} is the occurrence that entered for event $ev")
        }
        val processedRows = refs.map { db.notice(it) }
        for (n in processedRows) {
            assertEquals(NoticeStatus.PROCESSED, n.status, "notice ${n.id.v} is referenced: processed")
            val ev = entered.entries.single { it.value == n.id }.key
            assertEquals(raws[ev], n.raw, "processed notice ${n.id.v} keeps its text")
        }
        for (id in dismissedModel.shuffled(rnd).take(3)) assertEquals(NoticeStatus.DISMISSED, db.notice(id).status)
        for (id in pIds.shuffled(rnd).take(3)) assertEquals(NoticeStatus.PENDING, db.notice(id).status)

        // No two notices the capture dedupe would merge (D4): same key and content with the same when ≠ 0,
        // or posted less than DUPLICATE_WINDOW_MS apart (by construction no two events are; see the header).
        val all = pending + dismissed + processedRows
        assertEquals(entered.size, all.size, "every notice is pending, dismissed or processed")
        for ((_, group) in all.groupBy { it.raw.key }) {
            for (i in group.indices) for (j in i + 1 until group.size) {
                val a = group[i].raw
                val b = group[j].raw
                if (listOf(a.title, a.text, a.bigText, a.subText, a.textLines) != listOf(b.title, b.text, b.bigText, b.subText, b.textLines)) continue
                assertFalse(a.whenMs != 0L && a.whenMs == b.whenMs, "notices ${group[i].id.v} and ${group[j].id.v}: same key, content and when")
                assertTrue(abs(a.postedAt - b.postedAt) >= DUPLICATE_WINDOW_MS, "notices ${group[i].id.v} and ${group[j].id.v}: same key and content within the window")
            }
        }

        // Tags (spec §7.6 Marcadores, TagRow KDoc): sorted, clean, unique; counts match the transactions.
        assertEquals(tags.sortedBy { it.name }, tags, "tags sorted by name")
        assertEquals(tags.size, tags.map { it.name }.toSet().size, "tag names unique")
        for (g in tags) {
            assertEquals(g.name.trim().lowercase(), g.name, "tag name clean: ${g.name}")
            assertTrue(g.name.isNotEmpty())
            assertEquals(txs.count { g.name in it.tags }, g.uses, "uses of ${g.name}")
            assertEquals(txs.count { it.tags == listOf(g.name) }, g.soleUses, "soleUses of ${g.name}")
        }
        val names = tags.map { it.name }.toSet()
        assertTrue(txs.all { names.containsAll(it.tags) }, "every tag of a transaction exists")

        // Step edits (D9, D10): in today's month and later every week shows edited exactly when an edit is
        // stored at its start; earlier months only show stored edits (D10: a stale one may be ignored).
        val m0 = mo(today)
        for (k in -2..3) {
            val m = m0.ainvShift(k)
            val overview = db.budgetsOverview(m, today, BudgetOrder.RISK)
            for (s in overview) {
                for (wk in s.month.weeks) {
                    val stored = (s.id to wk.start) in edits
                    if (k >= 0) assertEquals(stored, wk.edited, "budget ${s.id.v} $m week ${wk.start}: edited ⇔ stored edit")
                    else if (wk.edited) assertTrue(stored, "budget ${s.id.v} $m week ${wk.start} edited without a stored edit")
                }
                if (k >= 0) {
                    val auto = s.month.weeks.count { !it.edited }
                    for (wk in s.month.weeks) assertEquals(!wk.edited && auto == 1, wk.locked, "Week.locked KDoc, budget ${s.id.v} $m ${wk.start}")
                }
            }
        }

        // Reads never move version (D6), ensureMonths included (D30).
        assertEquals(v0, db.version.value, "version moved during reads")
        assertEquals(w0, writes.n, "onWrite ran during reads")
    }
}

/** A fresh Db on a temporary file, with an onWrite counter; closed and deleted afterwards. */
private suspend fun withAinvDb(block: suspend (Db, AinvWrites) -> Unit) {
    val path = ainvTempPath("main")
    val writes = AinvWrites()
    try {
        val db = Db(BundledSQLiteDriver(), path, onWrite = { writes.n++ }, timeZone = TimeZone.of("America/Sao_Paulo"))
        try {
            block(db, writes)
        } finally {
            db.close()
        }
    } finally {
        ainvDelete(path)
    }
}

private suspend fun ainvRun(profile: String, weights: Map<AinvAct, Int>, seeds: List<Int>, steps: Int) {
    for (seed in seeds) {
        withAinvDb { db, writes ->
            try {
                AjustesInvariantRun(db, writes, seed, profile, weights).run(steps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                fail("profile=$profile seed=$seed threw $e", e)
            }
        }
    }
}

class AjustesInvariantsTest {
    @Test
    fun `random block 5 sequences keep every notice in exactly one state`() = ainvRealTime {
        ainvRun("mixed", AINV_MIXED, listOf(5101, 5102, 5103), steps = 120)
    }

    @Test
    fun `random cobudget imports never duplicate a notice and count what entered`() = ainvRealTime {
        ainvRun("imports", AINV_IMPORTS, listOf(5201, 5202, 5203), steps = 100)
    }

    @Test
    fun `random export and import round trips restore the observable state`() = ainvRealTime {
        ainvRun("backup", AINV_BACKUP, listOf(5301, 5302, 5303), steps = 100)
    }

    @Test
    fun `random start day saves drop exactly what startDropsEdits announced`() = ainvRealTime {
        ainvRun("start-days", AINV_START_DAYS, listOf(5401, 5402, 5403), steps = 100)
    }

    @Test
    fun `random tag renames merges and deletes keep the transactions consistent`() = ainvRealTime {
        ainvRun("tags", AINV_TAGS, listOf(5501, 5502, 5503), steps = 100)
    }
}
