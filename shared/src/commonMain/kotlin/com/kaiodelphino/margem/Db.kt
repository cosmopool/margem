package com.kaiodelphino.margem

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateRange
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.onDay
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.math.abs

private const val MAX_INSTALLMENTS = 400

/**
 * The whole database (architecture D5, D6, D29): one writer connection under a Mutex, two
 * `query_only` readers, WAL. Every function runs on Dispatchers.IO. Nothing returns null: a lookup
 * by an unknown id returns the zero value, and a refused write returns false or a zero id.
 *
 * Months: a [YearMonth] names the budget month that starts on Settings.monthStartDay of that
 * calendar month (spec §3). Screens call [ensureMonths] before reading a month (D30): budgets
 * without a limit row for a month are absent from that month's reads.
 *
 * @param path the database file; created and migrated on open.
 * @param onWrite called after every write that bumped [version] (D21).
 * @param timeZone used to read a notice's date and time when its text has none.
 */
class Db(
    internal val driver: SQLiteDriver,
    private val path: String,
    private val onWrite: () -> Unit = {},
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val writeLock = Mutex()
    private lateinit var writer: SQLiteConnection
    private val readers = Channel<SQLiteConnection>(READERS)
    private val _version = MutableStateFlow(0L)

    init {
        open()
    }

    /**
     * Bumped once at the end of every write that changed something (except [ensureMonths], [markPautaSeen] and
     * the occurrences [pending] and [planned] create with a today); a refused write or one that changes nothing
     * doesn't bump. Screens re-query on change.
     */
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun open() {
        writer = driver.open(path)
        writer.execSQL("PRAGMA busy_timeout = 5000")
        writer.execSQL("PRAGMA journal_mode = WAL")
        migrate(writer)
        repeat(READERS) {
            val c = driver.open(path)
            c.execSQL("PRAGMA busy_timeout = 5000")
            c.execSQL("PRAGMA query_only = 1")
            readers.trySend(c)
        }
    }

    /**
     * Closes every connection, after the write in flight ends and the reads in flight hand their connections back.
     * The Db can't be used afterwards: a later read fails at once instead of waiting for a connection.
     */
    fun close(): Unit = runBlocking {
        writeLock.withLock {
            writer.close()
            repeat(READERS) { readers.receive().close() }
            readers.close()
        }
        Unit
    }

    /**
     * Runs [block] in one transaction on the writer. When it changed rows and [bump] is set,
     * bumps [version] and calls onWrite (D6, D21). An exception rolls everything back.
     */
    internal suspend fun <T> write(bump: Boolean = true, block: (SQLiteConnection) -> T): T = withContext(Dispatchers.IO) {
        var changed = false
        val result = writeLock.withLock {
            // total_changes() read inside the transaction: AndroidSQLiteDriver (tests, D28) runs a
            // statement outside one on another pooled connection, whose count never moves.
            writer.execSQL("BEGIN IMMEDIATE")
            val r = try {
                val before = writer.long("SELECT total_changes()")
                block(writer).also {
                    changed = writer.long("SELECT total_changes()") != before
                    writer.execSQL("COMMIT")
                }
            } catch (e: Throwable) {
                writer.execSQL("ROLLBACK")
                throw e
            }
            if (changed && bump) _version.value++
            r
        }
        if (changed && bump) onWrite()
        result
    }

    /** Runs [block] on a free reader, inside one read transaction: a consistent snapshot (D29). */
    internal suspend fun <T> read(block: (SQLiteConnection) -> T): T = withContext(Dispatchers.IO) {
        val c = readers.receive()
        try {
            c.execSQL("BEGIN")
            try {
                block(c)
            } finally {
                c.execSQL("COMMIT")
            }
        } finally {
            readers.send(c)
        }
    }

    // ---- Backup (D14) ----

    /** Writes a consistent copy of the database to [path] (VACUUM INTO). False if it can't be written. */
    suspend fun exportTo(path: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                if (SystemFileSystem.exists(Path(path))) SystemFileSystem.delete(Path(path))
                writer.exec("VACUUM INTO ?", path)
            }.isSuccess
        }
    }

    /**
     * Replaces the database with the file at [path] (a copy made by [exportTo]): closes writer and
     * readers, swaps the file (dropping -wal/-shm), reopens, migrates and bumps [version].
     * False, with the current database untouched, if [path] isn't a Margem database.
     */
    suspend fun importFrom(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!isMargemDb(path)) return@withContext false
        // Copy first, next to the database: a failed copy leaves the current one untouched.
        val incoming = Path("${this@Db.path}.import")
        val copied = runCatching {
            SystemFileSystem.source(Path(path)).buffered().use { src -> SystemFileSystem.sink(incoming).buffered().use { it.transferFrom(src) } }
        }.isSuccess
        if (!copied) {
            runCatching { SystemFileSystem.delete(incoming, mustExist = false) }
            return@withContext false
        }
        writeLock.withLock {
            // Wait for in-flight reads to hand their connections back, then close everything.
            val held = List(READERS) { readers.receive() }
            held.forEach { it.close() }
            writer.close()
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                SystemFileSystem.delete(Path(this@Db.path + suffix), mustExist = false)
            }
            SystemFileSystem.atomicMove(incoming, Path(this@Db.path))
            open()
            _version.value++
        }
        onWrite()
        true
    }

    /** Opens [path] on its own and checks it has our schema at a version we know. */
    private fun isMargemDb(path: String): Boolean {
        if (!SystemFileSystem.exists(Path(path))) return false
        return runCatching {
            val c = driver.open(path)
            try {
                val v = c.long("PRAGMA user_version")
                val tables = c.long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('notices', 'transactions', 'budgets')")
                v == SCHEMA_VERSION && tables == 3L
            } finally {
                c.close()
            }
        }.getOrDefault(false)
    }

    // ---- Months (D30) ----

    /**
     * Makes sure every budget has a limit row up to [month]: copies the latest earlier row and
     * fills skipped months. Months before a budget's first row stay without one. Also gives [month]
     * and every month skipped since the latest ceiling row before it a ceiling row with the default
     * ([Ceiling.standard]), not the previous month's value (D42); with no ceiling row at all, only
     * [month] gets one. Never fills backwards. Does not bump [version].
     */
    suspend fun ensureMonths(month: YearMonth): Unit = write(bump = false) { c ->
        for (id in c.query("SELECT id FROM budgets") { it.getLong(0) }) ensureMonth(c, id, month)
        ensureCeiling(c, month)
    }

    /** The budget month [date] falls in, by the current monthStartDay. */
    suspend fun monthOf(date: LocalDate): YearMonth = read { c -> monthOf(date, settings(c).monthStartDay) }

    // ---- Settings and sources ----

    suspend fun settings(): Settings = read(::settings)

    /**
     * Changes the month start day (1..31; false otherwise). Deletes the step edits from the start
     * of [today]'s month onward, by the old or the new day, whichever is earlier (spec §4, D10).
     * Every budget keeps a limit in [today]'s new month: the one of the old current month, unless it
     * already had one there (D43). The new current month without a ceiling row gets the default
     * (D46); ceiling rows that exist stay. Installment dates are recomputed with the new day, so
     * installment k stays in the k-th month (D7). The day already set changes nothing.
     */
    suspend fun setMonthStartDay(day: Int, today: LocalDate): Boolean {
        if (day !in 1..31) return false
        write { c ->
            val old = settings(c).monthStartDay
            if (old != day) {
                val was = monthOf(today, old)
                val now = monthOf(today, day)
                val cut = minOf(monthWindow(was, old).start, monthWindow(now, day).start)
                c.exec("DELETE FROM week_steps WHERE week_start >= ?", cut.low())
                // ensureMonths only fills forward: a current month that moved back would leave out the budgets created after it.
                for (id in c.query("SELECT id FROM budgets") { it.getLong(0) }) ensureMonth(c, id, was)
                c.exec(
                    "INSERT OR IGNORE INTO budget_months(budget_id, month, limit_cents) SELECT budget_id, ?, limit_cents FROM budget_months WHERE month = ?",
                    now.toString(), was.toString(),
                )
                // ensureMonths never fills backwards: a current month that moved back gets the default here (D46).
                c.exec("INSERT OR IGNORE INTO ceiling_months(month, cents) VALUES (?, ?)", now.toString(), standardCeiling(c))
                // Installment k must stay in the k-th month (spec §6): dates clamped by the old day can drift with the new one (D7).
                val dates = c.query("SELECT i.transaction_id, i.k, t.date FROM installments i JOIN transactions t ON t.id = i.transaction_id") {
                    Triple(it.getLong(0), it.getInt(1), LocalDate.parse(it.getText(2)))
                }
                for ((tx, k, date) in dates) {
                    c.exec("UPDATE installments SET date = ? WHERE transaction_id = ? AND k = ?", installmentDate(date, k, day).toString(), tx, k)
                }
                setSetting(c, "month_start_day", day.toString())
            }
        }
        return true
    }

    /** Changes the week start day; deletes step edits like [setMonthStartDay] (none for the day already set). */
    suspend fun setWeekStart(day: DayOfWeek, today: LocalDate): Unit = write { c ->
        val s = settings(c)
        if (s.weekStart != day) {
            c.exec("DELETE FROM week_steps WHERE week_start >= ?", monthWindow(monthOf(today, s.monthStartDay), s.monthStartDay).start.low())
            setSetting(c, "week_start", day.isoDayNumber.toString())
        }
    }

    /**
     * Would [setMonthStartDay] ([monthStartDay]) followed by [setWeekStart] ([weekStart]) delete any step
     * edit? Reads only (spec §4, D41): the screen asks before saving when true. False when neither changes,
     * when [monthStartDay] is outside 1..31, or when no edit falls at or after the cut those two would use.
     */
    suspend fun startDropsEdits(monthStartDay: Int, weekStart: DayOfWeek, today: LocalDate): Boolean {
        if (monthStartDay !in 1..31) return false
        return read { c ->
            val s = settings(c)
            val start = monthWindow(monthOf(today, s.monthStartDay), s.monthStartDay).start
            // setWeekStart after a new month day cuts at the new month's start, never before this one.
            val cut = when {
                monthStartDay != s.monthStartDay -> minOf(start, monthWindow(monthOf(today, monthStartDay), monthStartDay).start)
                weekStart != s.weekStart -> start
                else -> return@read false
            }
            c.long("SELECT EXISTS(SELECT 1 FROM week_steps WHERE week_start >= ?)", cut.low()) == 1L
        }
    }

    suspend fun setTheme(theme: Theme): Unit = write { setSetting(it, "theme", theme.name) }

    suspend fun setBudgetOrder(order: BudgetOrder): Unit = write { setSetting(it, "budget_order", order.name) }

    suspend fun setWidgetOrder(order: BudgetOrder): Unit = write { setSetting(it, "widget_order", order.name) }

    /** Opening the Pauta: notices posted up to [now] stop being new. Doesn't bump [version]: the open Pauta has nothing to re-read. */
    suspend fun markPautaSeen(now: Long): Unit = write(bump = false) { setSetting(it, "pauta_seen_at", now.toString()) }

    /** Sorted by label, ignoring case. */
    suspend fun sources(): List<Source> = read { c ->
        c.query("SELECT package, label, added_at FROM sources ORDER BY label COLLATE NOCASE, package") {
            Source(it.getText(0), it.getText(1), it.getLong(2))
        }
    }

    /** Adds or relabels a source. Blank package or label: refused (false). */
    suspend fun addSource(packageName: String, label: String, now: Long): Boolean {
        if (packageName.isBlank() || label.isBlank()) return false
        write { c ->
            c.exec(
                "INSERT INTO sources(package, label, added_at) VALUES (?, ?, ?) ON CONFLICT(package) DO UPDATE SET label = excluded.label",
                packageName, label, now,
            )
        }
        return true
    }

    /** Stops capturing from it; its notices stay. */
    suspend fun removeSource(packageName: String): Unit = write { it.exec("DELETE FROM sources WHERE package = ?", packageName) }

    // ---- Notices ----

    /**
     * Pending notices, newest (posted_at) first, each with the previsto it is recognized as
     * ([NoticeRow.planned], D60). With [today], first creates the previstos' occurrences dated up to
     * today + 10 days (R1; a write that doesn't bump [version], like [ensureMonths]) and reads
     * [Planned.late] against it; with NO_DATE nothing is created, existing open occurrences are still
     * recognized and `late` is false.
     */
    suspend fun pending(today: LocalDate = NO_DATE): List<NoticeRow> {
        if (today != NO_DATE) fill(today)
        return read { c ->
            val rows = notices(c, "WHERE status = 0 ORDER BY posted_at DESC, id DESC")
            val found = recognize(c, rows, plannedRows(c, today, "WHERE p.link_key != ''"))
            rows.map { n -> found[n.id.v]?.let { n.copy(planned = it.copy(noticeInPauta = true)) } ?: n }
        }
    }

    suspend fun pendingCount(): Int = read { c -> c.long("SELECT COUNT(*) FROM ($NOTICES) WHERE status = 0").toInt() }

    /** Riscados, newest first. */
    suspend fun dismissed(): List<NoticeRow> = read { c -> notices(c, "WHERE status = 1 ORDER BY posted_at DESC, id DESC") }

    /** Zero row when unknown. */
    suspend fun notice(id: NoticeId): NoticeRow = read { c -> notice(c, id.v) }

    /** Riscar: only a pending notice (false otherwise). */
    suspend fun dismiss(id: NoticeId): Boolean = write { c ->
        c.exec("UPDATE notices SET dismissed = 1 WHERE id = ? AND id IN (SELECT id FROM ($NOTICES) WHERE status = 0)", id.v)
        c.changes() > 0
    }

    /** Trazer de volta: a dismissed notice becomes pending (false if it wasn't dismissed). */
    suspend fun restore(id: NoticeId): Boolean = write { c ->
        c.exec("UPDATE notices SET dismissed = 0 WHERE id = ? AND dismissed = 1", id.v)
        c.changes() > 0
    }

    /** Budget and tags of the latest transaction whose merchant has the same key ([MerchantKey]: spacing, case and accents ignored). */
    suspend fun suggestion(merchant: String): Suggestion = read { c ->
        val key = MerchantKey.of(merchant)
        if (key.v.isEmpty()) return@read Suggestion()
        val last = c.query(
            "SELECT id, budget_id FROM transactions WHERE merchant_key = ? ORDER BY date DESC, time DESC, id DESC LIMIT 1", key,
        ) { it.getLong(0) to it.getLong(1) }.firstOrNull() ?: return@read Suggestion()
        Suggestion(BudgetId(last.second), tagNames(c, last.first))
    }

    // ---- Transactions ----

    /**
     * Lançar: turns a pending notice into a transaction. Refused (TransactionId(0), nothing written)
     * unless the notice is pending, [TransactionForm.budget] exists, at least one tag remains after
     * cleaning, total > 0, installments in 1..400, every installment dated in 0001-01-01..9999-12-31
     * (dates are stored as ISO text, which sorts only in that range) and the cleaned caption is at most
     * 80 characters (spec §2.12, D53; see [TransactionForm.caption]).
     *
     * Writes the transaction, under an id never used before (not even by a deleted transaction: a stale
     * edit screen can't open another one), its installments and its tags in one transaction: installment k
     * (1..n) is dated purchase date + (k−1) months, moved to the nearest day inside the k-th
     * budget month when that date falls outside it (start days 29–31); each is total / n rounded
     * down and the last absorbs the remainder. `corrected` when total, merchant, date or time
     * differ from the notice's [Parsed]. A non-blank alias names the merchant. The caption, cleaned as
     * [TransactionForm.caption] says, is stored as the legenda.
     *
     * Previstos (D62, D64): with [TransactionForm.occurrence], that occurrence must be open (else refused)
     * and is closed by this lançamento; the previsto's link is (re)written from this notice's fonte and bank
     * text when its key is not empty (R2). With MONTHLY, the new previsto is linked the same way, first
     * occurrence in the month after the purchase date's. [TransactionForm.planned] is ignored.
     */
    suspend fun accept(notice: NoticeId, form: TransactionForm): TransactionId = write { c ->
        if (c.long("SELECT COUNT(*) FROM ($NOTICES) WHERE id = ? AND status = 0", notice.v) == 0L) return@write TransactionId(0)
        TransactionId(saveTransaction(c, 0, notice.v, form, NO_DATE).transaction.v)
    }

    /**
     * Lançamento manual: same rules as [accept], with no notice; never corrected. Like [accept], under an id
     * never used before. Previstos (spec §2.13, D64), all parsed once and refused whole (Saved() zero, nothing
     * written) by the same rules, plus the previsto's: name (caption, else merchant) at most 80 after
     * cleaning like the caption, day of [TransactionForm.date] 1–31:
     * - [TransactionForm.planned] ≠ 0 (Editar previsto): updates that previsto (see the field); Saved(planned).
     *   Refused when it doesn't exist.
     * - date > [today] (Salvar previsto; never with NO_DATE): only a new previsto, unlinked, first occurrence
     *   on that date, repeating as [TransactionForm.repeats] says; Saved(planned). No lançamento.
     * - otherwise the lançamento, Saved(transaction); with [TransactionForm.occurrence] it closes that open
     *   occurrence without writing a link (R6b); with MONTHLY also the new previsto (first occurrence next
     *   month), Saved(transaction, planned).
     */
    suspend fun addManual(form: TransactionForm, today: LocalDate = NO_DATE): Saved = write { c -> saveTransaction(c, 0, 0, form, today) }

    /**
     * Replaces every field, installment and tag of a transaction; same rules as [accept]. corrected
     * is recomputed against the notice's [Parsed]. A registered estorno stays. False if refused or
     * unknown. A blank caption removes the legenda (unlike the alias, §2.10); a refused edit (too long
     * a caption among the rest) leaves the transaction as it was. The origin ([TransactionForm.occurrence])
     * never changes on edit. "Repete" acts on the transaction's previsto ([TransactionDetail.planned], D65):
     * MONTHLY on a MONTHLY one changes nothing, on a ONCE one makes it MONTHLY; ONCE on a MONTHLY one deletes
     * it like [deletePlanned]. With no previsto, MONTHLY creates one like [accept] does, linked from the
     * transaction's notice (none for a manual one).
     */
    suspend fun edit(id: TransactionId, form: TransactionForm): Boolean = write { c ->
        val notice = c.query("SELECT notice_id FROM transactions WHERE id = ?", id.v) { it.getLong(0) }.firstOrNull()
            ?: return@write false
        saveTransaction(c, id.v, notice, form.copy(planned = PlannedId(0)), NO_DATE) != Saved()
    }

    /**
     * Deletes the transaction with its installments, tag links and the estorno registered on it:
     * its notice and that estorno notice become pending again (spec §2.8). The legenda goes with the
     * transaction (spec §2.12): the notice returns to the Pauta without one, no extra warning. A lançamento
     * from a previsto reopens its occurrence, waiting and linked as before (spec S1): nothing to undo.
     */
    suspend fun deleteTransaction(id: TransactionId): Unit = write { c -> deleteTransactions(c, "id = ?", id.v) }

    /**
     * All transactions, newest (date, time, then id) first. With [today], each row's [TransactionRow.now]
     * is its installment dated in today's budget month (zero when none is); without it, every `now` is zero.
     */
    suspend fun transactions(today: LocalDate = NO_DATE): List<TransactionRow> = read { c ->
        val rows = transactionRows(c, "")
        if (today == NO_DATE || rows.isEmpty()) return@read rows
        val day = settings(c).monthStartDay
        val w = monthWindow(monthOf(today, day), day)
        // At most one installment of a transaction falls in a month (D7).
        val now = c.query(
            "SELECT transaction_id, k, date, cents FROM installments WHERE date BETWEEN ? AND ?", w.start.low(), w.endInclusive.high(),
        ) { it.getLong(0) to Installment(it.getInt(1), LocalDate.parse(it.getText(2)), Cents(it.getLong(3))) }.toMap()
        rows.map { r -> now[r.id.v]?.let { r.copy(now = it) } ?: r }
    }

    /** Zero detail when unknown. */
    suspend fun transaction(id: TransactionId): TransactionDetail = read { c ->
        val row = transactionRows(c, "WHERE t.id = ?", id.v).firstOrNull() ?: return@read TransactionDetail()
        val own = ownPlanned(c, id.v)
        TransactionDetail(
            planned = PlannedId(own?.first ?: 0), repeats = own?.second ?: Repeats.ONCE,
            day = own?.let { c.long("SELECT day FROM planned WHERE id = ?", it.first).toInt() } ?: 0,
            row = row,
            installments = c.query("SELECT k, date, cents FROM installments WHERE transaction_id = ? ORDER BY k", id.v) {
                Installment(it.getInt(0), LocalDate.parse(it.getText(1)), Cents(it.getLong(2)))
            },
            notice = if (row.notice.v == 0L) NoticeRow() else notice(c, row.notice.v),
        )
    }

    /**
     * Margin of [budget] in the month of [date] (the first installment's), before and after a
     * purchase of [total] in [installments]. For an edit, [replacing] is the transaction being
     * edited: "before" includes it as it is, "after" swaps it for the new values. A refunded
     * transaction stays refunded after the edit, so its margin doesn't move (after = before).
     * Zero when the budget or the inputs aren't valid.
     */
    suspend fun previewAccept(
        budget: BudgetId,
        date: LocalDate,
        total: Cents,
        installments: Int,
        today: LocalDate,
        replacing: TransactionId = TransactionId(0),
    ): Preview = read { c ->
        if (total.v !in 1..MAX_CENTS || installments !in 1..MAX_INSTALLMENTS) return@read Preview()
        if (c.long("SELECT COUNT(*) FROM budgets WHERE id = ?", budget.v) == 0L) return@read Preview()
        val s = settings(c)
        if (date !in FIRST_DATE..LAST_DATE || installmentDate(date, installments, s.monthStartDay) !in FIRST_DATE..LAST_DATE) return@read Preview()
        val month = monthOf(date, s.monthStartDay)
        val window = monthWindow(month, s.monthStartDay)
        val limit = c.long("SELECT COALESCE(MAX(limit_cents), 0) FROM budget_months WHERE budget_id = ? AND month = ?", budget.v, month.toString())
        val spent = spentIn(c, "t.budget_id = ?", budget.v, window.start, window.endInclusive)
        val old = spentIn(c, "t.budget_id = ? AND t.id = ?", budget.v, window.start, window.endInclusive, replacing.v)
        val before = limit - spent
        if (replacing.v != 0L && c.long("SELECT COUNT(*) FROM refunds WHERE transaction_id = ?", replacing.v) > 0L) return@read Preview(month, Cents(before), Cents(before))
        Preview(month, Cents(before), Cents(before + old - total.v / installments))
    }

    // ---- Estornos (spec §6) ----

    /**
     * Registers [estorno] (a pending notice) as cancelling [purchase], a transaction not yet
     * refunded. Its installments stop counting, in every month. False otherwise.
     */
    suspend fun registerRefund(estorno: NoticeId, purchase: TransactionId): Boolean = write { c ->
        if (!isPending(c, estorno.v)) return@write false
        if (c.long("SELECT COUNT(*) FROM transactions WHERE id = ?", purchase.v) == 0L) return@write false
        if (c.long("SELECT COUNT(*) FROM refunds WHERE transaction_id = ?", purchase.v) > 0L) return@write false
        c.exec("INSERT INTO refunds(estorno_notice_id, transaction_id, notice_id) VALUES (?, ?, 0)", estorno.v, purchase.v)
        true
    }

    /**
     * Registers [estorno] as cancelling [purchase], another pending notice: both leave the Pauta
     * and nothing counts. False unless both are pending and different.
     */
    suspend fun registerRefund(estorno: NoticeId, purchase: NoticeId): Boolean = write { c ->
        if (estorno == purchase || !isPending(c, estorno.v) || !isPending(c, purchase.v)) return@write false
        c.exec("INSERT INTO refunds(estorno_notice_id, transaction_id, notice_id) VALUES (?, 0, ?)", estorno.v, purchase.v)
        true
    }

    /**
     * Registrar estorno's preview (book 10.4, "Casa: margem R$ 738,70 → R$ 798,60"): the margin of
     * [purchase]'s budget in the budget month of its first installment (the purchase date), before and
     * after registering the estorno — after = before + the installment dated in that month, which stops
     * counting. Like [previewAccept], it doesn't need [today]'s month to be the purchase's. Writes nothing.
     * Zero when the transaction is unknown or already refunded.
     */
    suspend fun previewRefund(purchase: TransactionId, today: LocalDate): Preview = read { c ->
        val t = c.query(
            "SELECT budget_id, date FROM transactions t WHERE id = ? AND $NOT_REFUNDED", purchase.v,
        ) { it.getLong(0) to LocalDate.parse(it.getText(1)) }.firstOrNull() ?: return@read Preview()
        val day = settings(c).monthStartDay
        val month = monthOf(t.second, day)
        val window = monthWindow(month, day)
        val limit = c.long("SELECT COALESCE(MAX(limit_cents), 0) FROM budget_months WHERE budget_id = ? AND month = ?", t.first, month.toString())
        val before = limit - spentIn(c, "t.budget_id = ?", t.first, window.start, window.endInclusive)
        val back = spentIn(c, "t.budget_id = ? AND t.id = ?", t.first, window.start, window.endInclusive, purchase.v)
        Preview(month, Cents(before), Cents(before + back))
    }

    /** Desfazer estorno: the purchase counts again; the estorno (and a purchase notice) are pending again. */
    suspend fun undoRefund(estorno: NoticeId): Boolean = write { c ->
        c.exec("DELETE FROM refunds WHERE estorno_notice_id = ?", estorno.v)
        c.changes() > 0
    }

    /**
     * What [estorno] may cancel: transactions not refunded and pending notices (not the estorno
     * itself, nor notices parsed as estornos). Likely ones first, newest first within each group;
     * likely = same source, same value, same merchant key when the estorno names one, posted no later.
     */
    /**
     * The purchases an estorno can cancel (spec §6, §7.3): every unrefunded transaction and pending
     * non-estorno notice, most likely first. Each candidate carries its caption, alias and bank text for
     * the search (§7.3: legenda, apelido, texto do banco ou valor, sem diferença de maiúsculas e acentos)
     * and the "em …" credit; a pending notice has caption "" (recados have none).
     */
    suspend fun refundCandidates(estorno: NoticeId): List<RefundCandidate> = read { c ->
        val e = notice(c, estorno.v)
        if (e.id.v == 0L) return@read emptyList()
        val key = MerchantKey.of(e.parsed.merchant)
        fun likely(pkg: String, cents: Long, merchant: String, postedAt: Long) =
            e.parsed.cents.v > 0 && pkg == e.raw.packageName && cents == e.parsed.cents.v &&
                (key.v.isEmpty() || MerchantKey.of(merchant) == key) && postedAt <= e.raw.postedAt

        class Ranked(val candidate: RefundCandidate, val time: LocalTime)
        val ranked = ArrayList<Ranked>()
        for (t in transactionRows(c, "WHERE NOT EXISTS (SELECT 1 FROM refunds r WHERE r.transaction_id = t.id)")) {
            val n = if (t.notice.v == 0L) null else c.query("SELECT package, posted_at FROM notices WHERE id = ?", t.notice.v) {
                it.getText(0) to it.getLong(1)
            }.firstOrNull()
            val isLikely = n != null && likely(n.first, t.total.v, t.merchant, n.second)
            ranked.add(
                Ranked(
                    RefundCandidate(
                        transaction = t.id, name = t.name, total = t.total, date = t.date, likely = isLikely,
                        caption = t.caption, alias = t.alias, merchant = t.merchant,
                    ),
                    t.time,
                ),
            )
        }
        for (n in notices(c, "WHERE status = 0 AND id != ${estorno.v}")) {
            if (n.parsed.isRefund) continue
            val isLikely = likely(n.raw.packageName, n.parsed.cents.v, n.parsed.merchant, n.raw.postedAt)
            val name = n.alias.ifEmpty { n.parsed.merchant }
            ranked.add(
                Ranked(
                    RefundCandidate(
                        notice = n.id, name = name, total = n.parsed.cents, date = n.parsed.date, likely = isLikely,
                        alias = n.alias, merchant = n.parsed.merchant,
                    ),
                    n.parsed.time,
                ),
            )
        }
        ranked.sortedWith(
            compareByDescending<Ranked> { it.candidate.likely }.thenByDescending { it.candidate.date }.thenByDescending { it.time }
        ).map { it.candidate }
    }

    // ---- Budgets ----

    /**
     * New budget at the end of the manual order, with [limit] for [today]'s month, under an id never
     * used before (not even by a deleted budget: a stale screen or widget can't open another one). Refused
     * (BudgetId(0)) for a blank name, a name already used (ignoring case) or limit ≤ 0.
     */
    suspend fun createBudget(name: String, limit: Cents, today: LocalDate): BudgetId {
        val clean = storable(name.trim())
        if (clean.isEmpty() || limit.v !in 1..MAX_CENTS) return BudgetId(0)
        return write { c ->
            if (nameTaken(c, clean, 0)) return@write BudgetId(0)
            c.exec("INSERT INTO budgets(name, position) VALUES (?, (SELECT COALESCE(MAX(position), -1) + 1 FROM budgets))", clean)
            val id = c.lastId()
            c.exec(
                "INSERT INTO budget_months(budget_id, month, limit_cents) VALUES (?, ?, ?)",
                id, monthOf(today, settings(c).monthStartDay).toString(), limit.v,
            )
            BudgetId(id)
        }
    }

    /** False for a blank or already used name, or an unknown budget. */
    suspend fun renameBudget(id: BudgetId, name: String): Boolean {
        val clean = storable(name.trim())
        if (clean.isEmpty()) return false
        return write { c ->
            if (nameTaken(c, clean, id.v)) return@write false
            c.exec("UPDATE budgets SET name = ? WHERE id = ?", clean, id.v)
            c.changes() > 0
        }
    }

    /**
     * Changes the limit from [today]'s month onward; earlier months keep theirs. In each of those
     * months where the edited steps now sum more than the new limit, all edits are deleted (spec §4).
     * False for limit ≤ 0 or an unknown budget.
     */
    suspend fun setLimit(id: BudgetId, limit: Cents, today: LocalDate): Boolean {
        if (limit.v !in 1..MAX_CENTS) return false
        return write { c ->
            if (c.long("SELECT COUNT(*) FROM budgets WHERE id = ?", id.v) == 0L) return@write false
            val day = settings(c).monthStartDay
            val current = monthOf(today, day)
            ensureMonth(c, id.v, current)
            c.exec("INSERT OR IGNORE INTO budget_months(budget_id, month, limit_cents) VALUES (?, ?, ?)", id.v, current.toString(), limit.v)
            c.exec("UPDATE budget_months SET limit_cents = ? WHERE budget_id = ? AND month >= ?", limit.v, id.v, current.toString())
            for (w in editsOver(c, id.v, limit.v, today)) {
                c.exec("DELETE FROM week_steps WHERE budget_id = ? AND week_start BETWEEN ? AND ?", id.v, w.start.low(), w.endInclusive.high())
            }
            true
        }
    }

    /**
     * Whether [setLimit] with these arguments would delete step edits: some month from [today]'s on
     * has edited steps summing more than [limit] (spec §4). Writes nothing; the screen asks before
     * saving when true. False when [setLimit] would refuse (limit ≤ 0 or above the maximum, unknown budget).
     */
    suspend fun limitDropsEdits(id: BudgetId, limit: Cents, today: LocalDate): Boolean {
        if (limit.v !in 1..MAX_CENTS) return false
        return read { c -> editsOver(c, id.v, limit.v, today).isNotEmpty() }
    }

    /** Deletes the budget, its transactions (notices back to the Pauta), limits, step edits and previstos (D59). */
    suspend fun deleteBudget(id: BudgetId): Unit = write { c ->
        deleteTransactions(c, "budget_id = ?", id.v)
        deletePlanned(c, "budget_id = ?", id.v)
        c.exec("DELETE FROM budget_months WHERE budget_id = ?", id.v)
        c.exec("DELETE FROM week_steps WHERE budget_id = ?", id.v)
        c.exec("DELETE FROM budgets WHERE id = ?", id.v)
    }

    /** Manual order: [ids] must be exactly the existing budgets, in the new order. False otherwise. */
    suspend fun reorderBudgets(ids: List<BudgetId>): Boolean = write { c ->
        val existing = c.query("SELECT id FROM budgets") { it.getLong(0) }.toSet()
        if (ids.size != existing.size || ids.map { it.v }.toSet() != existing) return@write false
        ids.forEachIndexed { i, id -> c.exec("UPDATE budgets SET position = ? WHERE id = ?", i, id.v) }
        true
    }

    /**
     * Edits the step of the week starting on [weekStart] (a Week.start of that month). Refused
     * when the step is < 0 or > [Week.max] (limit − Σ other edited weeks), or when the week is
     * [Week.locked], the only one left not edited (one week always stays automatic). Editing an
     * already edited week is allowed.
     */
    suspend fun setStep(budget: BudgetId, month: YearMonth, weekStart: LocalDate, step: Cents): Boolean = write { c ->
        val s = settings(c)
        if (c.long("SELECT COUNT(*) FROM budget_months WHERE budget_id = ? AND month = ?", budget.v, month.toString()) == 0L) return@write false
        val bm = budgetMonth(c, s, budget.v, month, weekStart)
        if (step.v < 0 || step > maxStep(bm, weekStart)) return@write false
        c.exec("INSERT OR REPLACE INTO week_steps(budget_id, week_start, cents) VALUES (?, ?, ?)", budget.v, weekStart.toString(), step.v)
        true
    }

    /**
     * Voltar ao automático: removes the step edit of the week starting on [weekStart] in [month]; the
     * week goes back to sharing what the edits leave with the other automatic weeks (spec §4). False,
     * with nothing changed, when that week of that month has no edit or the budget is unknown.
     */
    suspend fun clearStep(budget: BudgetId, month: YearMonth, weekStart: LocalDate): Boolean = write { c ->
        val w = monthWindow(month, settings(c).monthStartDay)
        if (weekStart !in w) return@write false
        c.exec("DELETE FROM week_steps WHERE budget_id = ? AND week_start = ?", budget.v, weekStart.toString())
        c.changes() > 0
    }

    /**
     * Nova verba's preview (book 10.7): the [BudgetMonth] that a budget created on [today] with [limit]
     * would have in [today]'s month: default steps, ideal pace of today, nothing spent. Writes nothing.
     * Equal to [budgetDetail]'s month of that budget right after [createBudget] with the same limit
     * and day. Zero BudgetMonth when [createBudget] would refuse the limit (≤ 0 or above the maximum).
     */
    suspend fun previewBudget(limit: Cents, today: LocalDate): BudgetMonth {
        if (limit.v !in 1..MAX_CENTS) return BudgetMonth()
        return read { c ->
            val s = settings(c)
            budgetMonth(monthOf(today, s.monthStartDay), s.monthStartDay, s.weekStart, today, limit, emptyMap(), emptyMap())
        }
    }

    /** Budgets with a limit row in [month], with their [BudgetMonth], in [order]. */
    suspend fun budgetsOverview(month: YearMonth, today: LocalDate, order: BudgetOrder): List<BudgetSummary> = read { c ->
        val s = settings(c)
        val list = c.query(
            "SELECT b.id, b.name FROM budgets b JOIN budget_months m ON m.budget_id = b.id AND m.month = ? ORDER BY b.position, b.id",
            month.toString(),
        ) { it.getLong(0) to it.getText(1) }.map { (id, name) -> BudgetSummary(BudgetId(id), name, budgetMonth(c, s, id, month, today)) }
        when (order) {
            BudgetOrder.RISK -> riskOrder(list)
            BudgetOrder.MANUAL -> list
            BudgetOrder.AZ -> list.sortedBy { it.name.lowercase() }
        }
    }

    /**
     * One budget in [month]: its [BudgetMonth], the lines of the month ([BudgetDetail.lines]) and its
     * first month. Zero when unknown.
     */
    suspend fun budgetDetail(id: BudgetId, month: YearMonth, today: LocalDate): BudgetDetail = read { c ->
        val name = c.query("SELECT name FROM budgets WHERE id = ?", id.v) { it.getText(0) }.firstOrNull() ?: return@read BudgetDetail()
        val bm = budgetMonth(c, settings(c), id.v, month, today)
        val from = bm.start.low()
        val to = bm.end.high()
        val rows = transactionRows(
            c, "WHERE t.budget_id = ? AND EXISTS (SELECT 1 FROM installments i WHERE i.transaction_id = t.id AND i.date BETWEEN ? AND ?)", id.v, from, to,
        ).associateBy { it.id.v }
        // At most one installment of a transaction falls in a month (D7).
        val lines = c.query(
            "SELECT i.transaction_id, i.k, i.date, i.cents FROM installments i JOIN transactions t ON t.id = i.transaction_id " +
                "WHERE t.budget_id = ? AND i.date BETWEEN ? AND ? ORDER BY i.date, t.time, t.id",
            id.v, from, to,
        ) { MonthLine(rows.getValue(it.getLong(0)), it.getInt(1), LocalDate.parse(it.getText(2)), Cents(it.getLong(3))) }
        val first = c.query("SELECT MIN(month) FROM budget_months WHERE budget_id = ?", id.v) { if (it.isNull(0)) NO_MONTH else YearMonth.parse(it.getText(0)) }
        val planned = c.long("SELECT COUNT(*) FROM planned WHERE budget_id = ?", id.v).toInt()
        BudgetDetail(id, name, bm, lines, first.firstOrNull() ?: NO_MONTH, planned)
    }

    // ---- Teto (spec §3, §7.1; D42, D46) ----

    /**
     * The ceiling of [month], the default and the sum of the month's limits. A month without its
     * ceiling row reads cents 0 (no ceiling): screens call [ensureMonths] first. Writes nothing.
     */
    suspend fun ceiling(month: YearMonth): Ceiling = read { c -> ceiling(c, month) }

    /**
     * Sets the ceiling of [today]'s month to [cents]; 0 removes it. Refused (false, nothing written) for
     * cents < 0 or above the maximum value. First fills the months skipped up to the current one with the
     * old default (like [ensureMonths]). [onward] ("deste mês em diante"): the default becomes [cents],
     * the current month's row becomes [cents] and the rows of later months are deleted. Otherwise ("só este
     * mês"): only the current month's row changes; the next month gets the default again.
     * Desfazer (D46) = setCeiling(before.standard, onward = true) then setCeiling(before.cents, onward = false).
     */
    suspend fun setCeiling(cents: Cents, onward: Boolean, today: LocalDate): Boolean {
        if (cents.v !in 0..MAX_CENTS) return false
        write { c ->
            val current = monthOf(today, settings(c).monthStartDay)
            ensureCeiling(c, current)
            if (onward) {
                c.exec("INSERT INTO settings(key, value) VALUES ('ceiling', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value WHERE value != excluded.value", cents.v.toString())
                c.exec("DELETE FROM ceiling_months WHERE month > ?", current.toString())
            }
            // Upserts that only count as a change when the value differs (D6: no bump for nothing).
            c.exec(
                "INSERT INTO ceiling_months(month, cents) VALUES (?, ?) ON CONFLICT(month) DO UPDATE SET cents = excluded.cents WHERE cents != excluded.cents",
                current.toString(), cents.v,
            )
        }
        return true
    }

    /**
     * Nova verba and Editar verba's warning (spec §3): the [Ceiling] of [today]'s month as if [budget]'s
     * limit there were [limit] — [Ceiling.limits] = Σ of the other budgets' limits in that month + [limit].
     * [budget] 0 (or a budget without a row in that month) adds [limit] as a new one. The month's own ceiling
     * and default as they are (a missing ceiling row reads 0). [limit] ≤ 0 or above the maximum value: the
     * month as it is, like [ceiling] (the budget keeps its limit). Writes nothing. The screen warns when the
     * result's [Ceiling.over] > 0 and its limits are above [ceiling]'s for the month (the sum goes up).
     */
    suspend fun previewCeiling(budget: BudgetId, limit: Cents, today: LocalDate): Ceiling = read { c ->
        val month = monthOf(today, settings(c).monthStartDay)
        val now = ceiling(c, month)
        if (limit.v !in 1..MAX_CENTS) return@read now
        val own = c.long("SELECT COALESCE(MAX(limit_cents), 0) FROM budget_months WHERE budget_id = ? AND month = ?", budget.v, month.toString())
        now.copy(limits = Cents(now.limits.v - own + limit.v))
    }

    // ---- Tags ----

    /** With use counts, sorted by name. */
    suspend fun tags(): List<TagRow> = read { c ->
        c.query(
            "SELECT g.id, g.name, " +
                "(SELECT COUNT(*) FROM transaction_tags l WHERE l.tag_id = g.id), " +
                "(SELECT COUNT(*) FROM transaction_tags l WHERE l.tag_id = g.id " +
                "AND (SELECT COUNT(*) FROM transaction_tags o WHERE o.transaction_id = l.transaction_id) = 1), " +
                "(SELECT COUNT(*) FROM planned_tags l WHERE l.tag_id = g.id " +
                "AND (SELECT COUNT(*) FROM planned_tags o WHERE o.planned_id = l.planned_id) = 1) " +
                "FROM tags g ORDER BY g.name",
        ) { TagRow(TagId(it.getLong(0)), it.getText(1), it.getInt(2), it.getInt(3), it.getInt(4)) }
    }

    /** Trimmed and lowercased; returns the existing tag for a used name. TagId(0) for a blank one. */
    suspend fun createTag(name: String): TagId {
        val clean = name.trim().lowercase()
        if (clean.isEmpty()) return TagId(0)
        return write { c -> TagId(tagId(c, clean)) }
    }

    /** Trimmed and lowercased. False for a blank name, a name of another tag, or an unknown tag. */
    suspend fun renameTag(id: TagId, name: String): Boolean {
        val clean = name.trim().lowercase()
        if (clean.isEmpty()) return false
        return write { c ->
            if (c.long("SELECT COUNT(*) FROM tags WHERE name = ? AND id != ?", clean, id.v) > 0L) return@write false
            c.exec("UPDATE tags SET name = ? WHERE id = ?", clean, id.v)
            c.changes() > 0
        }
    }

    /**
     * Moves every link of [from] to [into], the previstos' too (no repeated pair), and deletes [from]. False if
     * either is unknown or they are the same.
     */
    suspend fun mergeTags(from: TagId, into: TagId): Boolean = write { c ->
        if (from == into || c.long("SELECT COUNT(*) FROM tags WHERE id IN (?, ?)", from.v, into.v) != 2L) return@write false
        c.exec("INSERT OR IGNORE INTO transaction_tags(transaction_id, tag_id) SELECT transaction_id, ? FROM transaction_tags WHERE tag_id = ?", into.v, from.v)
        c.exec("DELETE FROM transaction_tags WHERE tag_id = ?", from.v)
        c.exec("INSERT OR IGNORE INTO planned_tags(planned_id, tag_id) SELECT planned_id, ? FROM planned_tags WHERE tag_id = ?", into.v, from.v)
        c.exec("DELETE FROM planned_tags WHERE tag_id = ?", from.v)
        c.exec("DELETE FROM tags WHERE id = ?", from.v)
        true
    }

    /**
     * Deletes the tag; transactions left without tags are deleted and their notices go back to the Pauta;
     * previstos left without tags are deleted like [deletePlanned].
     */
    suspend fun deleteTag(id: TagId): Unit = write { c ->
        c.exec("DELETE FROM transaction_tags WHERE tag_id = ?", id.v)
        c.exec("DELETE FROM tags WHERE id = ?", id.v)
        deleteTransactions(c, "id NOT IN (SELECT transaction_id FROM transaction_tags)")
        c.exec("DELETE FROM planned_tags WHERE tag_id = ?", id.v)
        deletePlanned(c, "id NOT IN (SELECT planned_id FROM planned_tags)")
    }

    // ---- Previstos (spec §2.13, D59–D64) ----

    /**
     * "A caminho", the previsto sheet and "Qual previsto é este recado?": every open occurrence (not skipped,
     * not closed by a lançamento) dated up to [today] + 10 days (R1: a later one, even a first one saved
     * with a far date, shows from 10 days before its day), oldest date first (ties: occurrence id), [Planned.late] against [today] and
     * [Planned.noticeInPauta] like [pending]. First creates the occurrences dated up to [today] + 10 days (R1),
     * without bumping [version]: a ONCE previsto never gets a second one, a month that has its row (open,
     * closed or skipped) never gets another. Previstos never count in spent, pace, Leitura or widgets.
     */
    suspend fun planned(today: LocalDate): List<Planned> {
        fill(today)
        return read { c ->
            val rows = plannedRows(c, today, "")
            // Only a linked previsto can have its recado in the Pauta: without one, the Pauta isn't read (D60).
            val linked = rows.filter { it.linked }
            if (linked.isEmpty()) return@read rows
            val inPauta = recognize(c, notices(c, "WHERE status = 0"), linked).values.map { it.occurrence }.toSet()
            rows.map { if (it.occurrence in inPauta) it.copy(noticeInPauta = true) else it }
        }
    }

    /** R1's occurrence creation, without the writer when every one already exists: a read checks first. */
    private suspend fun fill(today: LocalDate) {
        if (read { c -> missingOccurrences(c, today).isNotEmpty() }) write(bump = false) { c -> fillPlanned(c, today) }
    }

    /** Pular este mês (P10): the open occurrence won't come back; the next month's still does. False when not open. */
    suspend fun skipOccurrence(id: OccurrenceId): Boolean = write { c ->
        c.exec("UPDATE planned_dates SET skipped = 1 WHERE id = ? AND skipped = 0 AND id NOT IN (SELECT occurrence_id FROM transactions)", id.v)
        c.changes() > 0
    }

    /**
     * Apagar previsto: the rule, its tags and every occurrence; its lançamentos stay and keep counting, with
     * no origin (occurrence 0). Unknown: nothing changes.
     */
    suspend fun deletePlanned(id: PlannedId): Unit = write { c -> deletePlanned(c, "id = ?", id.v) }

    // ---- Aliases ----

    /** Names the merchant (key: [MerchantKey], spacing, case and accents ignored) everywhere; a blank alias removes it. Blank merchant: ignored. */
    suspend fun setAlias(merchant: String, alias: String) {
        val key = MerchantKey.of(merchant)
        if (key.v.isEmpty()) return
        write { c -> saveAlias(c, key, alias) }
    }

    // ---- Leitura (spec §7.5) ----
    // Refunded transactions never count. Installments count on their own date.

    /** Spent × limit for every budget and month in [from]..[to] that has a limit row. */
    suspend fun history(from: YearMonth, to: YearMonth): List<HistoryRow> = read { c ->
        val day = settings(c).monthStartDay
        c.query(
            "SELECT b.id, b.name, m.month, m.limit_cents FROM budget_months m JOIN budgets b ON b.id = m.budget_id " +
                "WHERE m.month BETWEEN ? AND ? ORDER BY b.position, b.id, m.month",
            from.toString(), to.toString(),
        ) { HistoryRow(BudgetId(it.getLong(0)), it.getText(1), YearMonth.parse(it.getText(2)), Cents(it.getLong(3))) }
            .map {
                val w = monthWindow(it.month, day)
                it.copy(spent = Cents(spentIn(c, "t.budget_id = ?", it.budget.v, w.start, w.endInclusive)))
            }
    }

    /** Tags by value of installments dated in [from]..[to]; count = transactions with one there. */
    suspend fun tagRanking(from: LocalDate, to: LocalDate): List<RankRow> = read { c ->
        c.query(
            "SELECT g.name, SUM(i.cents), COUNT(DISTINCT t.id) FROM installments i JOIN transactions t ON t.id = i.transaction_id " +
                "JOIN transaction_tags l ON l.transaction_id = t.id JOIN tags g ON g.id = l.tag_id " +
                "WHERE i.date BETWEEN ? AND ? AND $NOT_REFUNDED GROUP BY g.id ORDER BY 2 DESC, g.name",
            from.low(), to.high(),
        ) { RankRow(it.getText(0), Cents(it.getLong(1)), it.getInt(2)) }
    }

    /**
     * Same, by merchant (spec §7.5): merchants with the same alias (ignoring case and accents: the alias key, D61) are one row,
     * named by the alias; merchants without one are grouped by key and named by the bank text.
     */
    suspend fun merchantRanking(from: LocalDate, to: LocalDate): List<RankRow> = read { c ->
        c.query(
            "SELECT COALESCE(MAX(a.alias), MAX(t.merchant)), SUM(i.cents), COUNT(DISTINCT t.id) FROM installments i " +
                "JOIN transactions t ON t.id = i.transaction_id LEFT JOIN merchant_aliases a ON a.merchant_key = t.merchant_key " +
                "WHERE i.date BETWEEN ? AND ? AND $NOT_REFUNDED " +
                "GROUP BY CASE WHEN a.alias IS NULL THEN 'k' || t.merchant_key ELSE 'a' || a.alias_key END ORDER BY 2 DESC, 1",
            from.low(), to.high(),
        ) { RankRow(it.getText(0), Cents(it.getLong(1)), it.getInt(2)) }
    }

    /** Spent per day in [from]..[to]; days without spending are absent. */
    suspend fun spentByDay(from: LocalDate, to: LocalDate): Map<LocalDate, Cents> = read { c ->
        c.query(
            "SELECT i.date, SUM(i.cents) FROM installments i JOIN transactions t ON t.id = i.transaction_id " +
                "WHERE i.date BETWEEN ? AND ? AND $NOT_REFUNDED GROUP BY i.date HAVING SUM(i.cents) > 0 ORDER BY i.date",
            from.low(), to.high(),
        ) { LocalDate.parse(it.getText(0)) to Cents(it.getLong(1)) }.toMap()
    }

    // ---- Private: shared by reads and writes ----

    private fun settings(c: SQLiteConnection): Settings {
        val m = c.query("SELECT key, value FROM settings") { it.getText(0) to it.getText(1) }.toMap()
        val d = Settings()
        return Settings(
            monthStartDay = m["month_start_day"]?.toIntOrNull() ?: d.monthStartDay,
            weekStart = m["week_start"]?.toIntOrNull()?.let { DayOfWeek(it) } ?: d.weekStart,
            theme = m["theme"]?.let { v -> Theme.entries.firstOrNull { it.name == v } } ?: d.theme,
            budgetOrder = m["budget_order"]?.let { v -> BudgetOrder.entries.firstOrNull { it.name == v } } ?: d.budgetOrder,
            widgetOrder = m["widget_order"]?.let { v -> BudgetOrder.entries.firstOrNull { it.name == v } } ?: d.widgetOrder,
            pautaSeenAt = m["pauta_seen_at"]?.toLongOrNull() ?: d.pautaSeenAt,
        )
    }

    private fun setSetting(c: SQLiteConnection, key: String, value: String) =
        c.exec("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", key, value)

    /** Copies the latest limit row before [month] into every month up to it (D8, D30). */
    private fun ensureMonth(c: SQLiteConnection, budget: Long, month: YearMonth) {
        val last = c.query(
            "SELECT month, limit_cents FROM budget_months WHERE budget_id = ? AND month <= ? ORDER BY month DESC LIMIT 1",
            budget, month.toString(),
        ) { YearMonth.parse(it.getText(0)) to it.getLong(1) }.firstOrNull() ?: return
        var m = last.first
        while (m < month) {
            m = m.plusMonth()
            c.exec("INSERT OR IGNORE INTO budget_months(budget_id, month, limit_cents) VALUES (?, ?, ?)", budget, m.toString(), last.second)
        }
    }

    /**
     * Gives [month] and the months skipped since the latest ceiling row before it the default (D42). With no
     * row at all, only [month]; with rows only after it, nothing (never fills backwards).
     */
    private fun ensureCeiling(c: SQLiteConnection, month: YearMonth) {
        val standard = standardCeiling(c)
        val last = c.query("SELECT month FROM ceiling_months WHERE month <= ? ORDER BY month DESC LIMIT 1", month.toString()) {
            YearMonth.parse(it.getText(0))
        }.firstOrNull()
        if (last == null) {
            if (c.long("SELECT COUNT(*) FROM ceiling_months") == 0L) c.exec("INSERT INTO ceiling_months(month, cents) VALUES (?, ?)", month.toString(), standard)
            return
        }
        var m: YearMonth = last
        while (m < month) {
            m = m.plusMonth()
            c.exec("INSERT OR IGNORE INTO ceiling_months(month, cents) VALUES (?, ?)", m.toString(), standard)
        }
    }

    private fun standardCeiling(c: SQLiteConnection) =
        c.query("SELECT value FROM settings WHERE key = 'ceiling'") { it.getText(0).toLongOrNull() ?: 0L }.firstOrNull() ?: 0L

    private fun ceiling(c: SQLiteConnection, month: YearMonth) = Ceiling(
        month = month,
        cents = Cents(c.long("SELECT cents FROM ceiling_months WHERE month = ?", month.toString())),
        standard = Cents(standardCeiling(c)),
        limits = Cents(c.long("SELECT COALESCE(SUM(limit_cents), 0) FROM budget_months WHERE month = ?", month.toString())),
    )

    private fun budgetMonth(c: SQLiteConnection, s: Settings, budget: Long, month: YearMonth, today: LocalDate): BudgetMonth {
        val w = monthWindow(month, s.monthStartDay)
        val limit = c.long("SELECT COALESCE(MAX(limit_cents), 0) FROM budget_months WHERE budget_id = ? AND month = ?", budget, month.toString())
        val edits = c.query(
            "SELECT week_start, cents FROM week_steps WHERE budget_id = ? AND week_start BETWEEN ? AND ?",
            budget, w.start.low(), w.endInclusive.high(),
        ) { LocalDate.parse(it.getText(0)) to Cents(it.getLong(1)) }.toMap()
        val spent = c.query(
            "SELECT i.date, SUM(i.cents) FROM installments i JOIN transactions t ON t.id = i.transaction_id " +
                "WHERE t.budget_id = ? AND i.date BETWEEN ? AND ? AND $NOT_REFUNDED GROUP BY i.date",
            budget, w.start.low(), w.endInclusive.high(),
        ) { LocalDate.parse(it.getText(0)) to Cents(it.getLong(1)) }.toMap()
        val bm = budgetMonth(month, s.monthStartDay, s.weekStart, today, Cents(limit), edits, spent)
        // No limit row (before the budget's first month): nothing to edit, every week locked (Week.locked).
        val hasRow = c.long("SELECT COUNT(*) FROM budget_months WHERE budget_id = ? AND month = ?", budget, month.toString()) > 0L
        return if (hasRow) bm else bm.copy(weeks = bm.weeks.map { it.copy(locked = true, max = Cents(0)) })
    }

    /** Windows of [budget]'s months, from [today]'s on, whose step edits sum more than [limit] (spec §4). */
    private fun editsOver(c: SQLiteConnection, budget: Long, limit: Long, today: LocalDate): List<LocalDateRange> {
        val day = settings(c).monthStartDay
        val edits = c.query(
            "SELECT week_start, cents FROM week_steps WHERE budget_id = ? AND week_start >= ?",
            budget, monthWindow(monthOf(today, day), day).start.low(),
        ) { LocalDate.parse(it.getText(0)) to it.getLong(1) }
        return edits.groupBy { monthOf(it.first, day) }.filter { (_, inMonth) -> inMonth.sumOf { it.second } > limit }.map { monthWindow(it.key, day) }
    }

    /** Σ installments in [from]..[to] of the non-refunded transactions matching [where] (over `t`). */
    private fun spentIn(c: SQLiteConnection, where: String, budget: Long, from: LocalDate, to: LocalDate, vararg more: Any): Long =
        c.long(
            "SELECT COALESCE(SUM(i.cents), 0) FROM installments i JOIN transactions t ON t.id = i.transaction_id " +
                "WHERE $where AND i.date BETWEEN ? AND ? AND $NOT_REFUNDED",
            budget, *more, from.low(), to.high(),
        )

    /** Budget names are unique ignoring case, accents' case included ("Zé" = "ZÉ"): NOCASE only folds ASCII. */
    private fun nameTaken(c: SQLiteConnection, name: String, except: Long): Boolean {
        val lower = name.lowercase()
        return c.query("SELECT id, name FROM budgets") { it.getLong(0) to it.getText(1) }.any { it.first != except && it.second.lowercase() == lower }
    }

    private fun isPending(c: SQLiteConnection, id: Long) = c.long("SELECT COUNT(*) FROM ($NOTICES) WHERE id = ? AND status = 0", id) > 0L

    private fun notice(c: SQLiteConnection, id: Long): NoticeRow = notices(c, "WHERE id = $id").firstOrNull() ?: NoticeRow()

    /** [NOTICES] rows filtered by [tail], parsed, with alias and new/ad flags. */
    private fun notices(c: SQLiteConnection, tail: String): List<NoticeRow> {
        val seen = settings(c).pautaSeenAt
        val aliases = aliases(c)
        return c.query("SELECT * FROM ($NOTICES) $tail") {
            val raw = RawNotice(
                packageName = it.getText(1), appLabel = it.getText(2), key = it.getText(3), postedAt = it.getLong(4), whenMs = it.getLong(5),
                title = it.getText(6), text = it.getText(7), bigText = it.getText(8), subText = it.getText(9), textLines = it.getText(10),
                category = it.getText(11), channelId = it.getText(12), extrasJson = it.getText(13),
            )
            val parsed = extract(raw, timeZone)
            NoticeRow(
                id = NoticeId(it.getLong(0)),
                raw = raw,
                parsed = parsed,
                alias = aliases[MerchantKey.of(parsed.merchant)] ?: "",
                status = NoticeStatus.entries[it.getInt(14)],
                isNew = raw.postedAt > seen,
                looksLikeAd = parsed.cents.v == 0L && parsed.merchant.isEmpty(),
            )
        }
    }

    /**
     * The previsto of transaction [id] and its repetition (D65): the one its "Repete" created (the latest), else the
     * one whose occurrence it closed; null when none.
     */
    private fun ownPlanned(c: SQLiteConnection, id: Long): Pair<Long, Repeats>? =
        c.query("SELECT id, repeats FROM planned WHERE source_transaction = ? ORDER BY id DESC LIMIT 1", id) {
            it.getLong(0) to Repeats.entries[it.getInt(1)]
        }.firstOrNull() ?: c.query(
            "SELECT p.id, p.repeats FROM transactions t JOIN planned_dates d ON d.id = t.occurrence_id JOIN planned p ON p.id = d.planned_id WHERE t.id = ?", id,
        ) { it.getLong(0) to Repeats.entries[it.getInt(1)] }.firstOrNull()

    /** Deletes the previstos matching [where] (over `planned`) with their tags and occurrences; their lançamentos lose their origin (D59). */
    private fun deletePlanned(c: SQLiteConnection, where: String, vararg args: Any) {
        val ids = "SELECT id FROM planned WHERE $where"
        c.exec("UPDATE transactions SET occurrence_id = 0 WHERE occurrence_id IN (SELECT id FROM planned_dates WHERE planned_id IN ($ids))", *args)
        c.exec("DELETE FROM planned_dates WHERE planned_id IN ($ids)", *args)
        c.exec("DELETE FROM planned_tags WHERE planned_id IN ($ids)", *args)
        c.exec("DELETE FROM planned WHERE $where", *args)
    }

    private fun aliases(c: SQLiteConnection) =
        c.query("SELECT merchant_key, alias FROM merchant_aliases") { MerchantKey.of(it.getText(0)) to it.getText(1) }.toMap()

    private fun tagNames(c: SQLiteConnection, transaction: Long) =
        c.query("SELECT g.name FROM transaction_tags l JOIN tags g ON g.id = l.tag_id WHERE l.transaction_id = ? ORDER BY g.name", transaction) { it.getText(0) }

    /** Transactions matching [where] (over `t`), newest first, with budget, alias, tags and estorno. */
    private fun transactionRows(c: SQLiteConnection, where: String, vararg args: Any): List<TransactionRow> {
        val rows = c.query(
            "SELECT t.id, t.notice_id, t.budget_id, b.name, t.merchant, COALESCE(a.alias, ''), t.total_cents, " +
                "(SELECT COUNT(*) FROM installments i WHERE i.transaction_id = t.id), t.date, t.time, " +
                "COALESCE((SELECT r.estorno_notice_id FROM refunds r WHERE r.transaction_id = t.id), 0), t.corrected, t.caption " +
                "FROM transactions t JOIN budgets b ON b.id = t.budget_id LEFT JOIN merchant_aliases a ON a.merchant_key = t.merchant_key " +
                "$where ORDER BY t.date DESC, t.time DESC, t.id DESC",
            *args,
        ) {
            TransactionRow(
                id = TransactionId(it.getLong(0)), notice = NoticeId(it.getLong(1)), budget = BudgetId(it.getLong(2)), budgetName = it.getText(3),
                merchant = it.getText(4), alias = it.getText(5), total = Cents(it.getLong(6)), installments = it.getInt(7),
                date = LocalDate.parse(it.getText(8)), time = LocalTime.parse(it.getText(9)), estorno = NoticeId(it.getLong(10)),
                corrected = it.getLong(11) != 0L, caption = it.getText(12),
            )
        }
        if (rows.isEmpty()) return rows
        val tags = HashMap<Long, MutableList<String>>()
        c.query("SELECT l.transaction_id, g.name FROM transaction_tags l JOIN tags g ON g.id = l.tag_id ORDER BY g.name") {
            tags.getOrPut(it.getLong(0)) { ArrayList() }.add(it.getText(1))
        }
        return rows.map { it.copy(tags = tags[it.id.v] ?: emptyList()) }
    }

    /**
     * Parses [form] and writes the transaction [id] (0 = new) of [notice] (0 = manual) with its
     * installments and tags, and the previsto it creates or edits (D64), all or nothing: every check
     * comes before the first write. [today] (NO_DATE = none) makes a later date a previsto only.
     * Returns what was written, or Saved() with nothing written when the form is refused.
     */
    private fun saveTransaction(c: SQLiteConnection, id: Long, notice: Long, form: TransactionForm, today: LocalDate): Saved {
        val tags = form.tags.map { it.trim().lowercase() }.filter(String::isNotEmpty).distinct()
        if (tags.isEmpty() || form.total.v !in 1..MAX_CENTS || form.installments !in 1..MAX_INSTALLMENTS) return Saved()
        if (c.long("SELECT COUNT(*) FROM budgets WHERE id = ?", form.budget.v) == 0L) return Saved()
        val day = settings(c).monthStartDay
        val dates = (1..form.installments).map { installmentDate(form.date, it, day) }
        if (dates.any { it !in FIRST_DATE..LAST_DATE }) return Saved()
        val merchant = storable(form.merchant.trim())
        val caption = cleanCaption(form.caption)
        if (caption.length > MAX_CAPTION) return Saved()
        val key = MerchantKey.of(merchant)

        // Previsto only (D64): Editar previsto, or a manual date ahead of today.
        val editing = id == 0L && notice == 0L && form.planned.v != 0L
        val ahead = id == 0L && notice == 0L && today != NO_DATE && form.date > today
        if (editing || ahead) {
            val name = plannedName(caption, "", merchant)
            if (name.length > MAX_CAPTION) return Saved()
            if (editing) {
                val oldDay = c.query("SELECT day FROM planned WHERE id = ?", form.planned.v) { it.getInt(0) }.firstOrNull() ?: return Saved()
                // The occurrence the form was opened on, while still open (else as 0): its date, cut to the month's
                // last day, keeps the day (P8); "Só uma vez" keeps it alone (spec §2.13, 2026-10-23).
                val opened = c.query(
                    "SELECT id, month FROM planned_dates WHERE id = ? AND planned_id = ? AND skipped = 0 AND id NOT IN (SELECT occurrence_id FROM transactions)",
                    form.occurrence.v, form.planned.v,
                ) { it.getLong(0) to YearMonth.parse(it.getText(1)) }.firstOrNull()
                val newDay = if (opened != null && form.date == occurrenceDate(opened.second, oldDay)) oldDay else form.date.day
                c.exec(
                    "UPDATE planned SET name = ?, budget_id = ?, cents = ?, day = ?, repeats = ? WHERE id = ?",
                    name, form.budget.v, form.total.v, newDay, form.repeats.ordinal, form.planned.v,
                )
                c.exec("DELETE FROM planned_tags WHERE planned_id = ?", form.planned.v)
                for (t in tags) c.exec("INSERT OR IGNORE INTO planned_tags(planned_id, tag_id) VALUES (?, ?)", form.planned.v, tagId(c, t))
                if (form.repeats == Repeats.ONCE) {
                    val open = "planned_id = ? AND skipped = 0 AND id NOT IN (SELECT occurrence_id FROM transactions)"
                    val keep = opened?.first ?: c.query("SELECT id FROM planned_dates WHERE $open ORDER BY month LIMIT 1", form.planned.v) { it.getLong(0) }.firstOrNull() ?: 0L
                    c.exec("DELETE FROM planned_dates WHERE $open AND id != ?", form.planned.v, keep)
                }
                return Saved(planned = form.planned)
            }
            // Linked and ahead at once (a contradictory form): refused, never a second previsto.
            if (form.occurrence.v != 0L) return Saved()
            return Saved(planned = PlannedId(insertPlanned(c, name, form, tags, form.date.yearMonth, "", MerchantKey.of(""), "")))
        }

        // The recado's own bank text links the previsto (D62, R2): never the amended form.merchant.
        val raw = if (notice != 0L) notice(c, notice).raw else RawNotice()
        val bank = if (notice != 0L) extract(raw, timeZone).merchant else ""
        val linkKey = MerchantKey.of(bank)
        val occurrence = if (id == 0L) form.occurrence.v else 0L
        val occurrencePlanned = if (occurrence == 0L) 0L else c.query(
            "SELECT planned_id FROM planned_dates WHERE id = ? AND skipped = 0 AND id NOT IN (SELECT occurrence_id FROM transactions)", occurrence,
        ) { it.getLong(0) }.firstOrNull() ?: return Saved()
        // D65: on edit, "Repete" acts on the transaction's own previsto; only without one does MONTHLY create.
        val own = if (id != 0L) ownPlanned(c, id) else null
        val monthlyName = if (form.repeats == Repeats.MONTHLY && own == null) {
            val alias = form.alias.trim().ifEmpty { aliases(c)[key].orEmpty() }
            plannedName(caption, alias, merchant).also { if (it.length > MAX_CAPTION) return Saved() }
        } else ""

        val corrected = notice != 0L && extract(raw, timeZone).let {
            it.cents != form.total || it.merchant != merchant || it.date != form.date || it.time != form.time
        }
        val txId = if (id == 0L) {
            c.exec(
                "INSERT INTO transactions(notice_id, budget_id, merchant, merchant_key, date, time, total_cents, corrected, caption, occurrence_id) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                notice, form.budget.v, merchant, key, form.date.toString(), form.time.toString(), form.total.v, corrected, caption, occurrence,
            )
            c.lastId()
        } else {
            c.exec(
                "UPDATE transactions SET budget_id = ?, merchant = ?, merchant_key = ?, date = ?, time = ?, total_cents = ?, corrected = ?, caption = ? WHERE id = ?",
                form.budget.v, merchant, key, form.date.toString(), form.time.toString(), form.total.v, corrected, caption, id,
            )
            c.exec("DELETE FROM installments WHERE transaction_id = ?", id)
            c.exec("DELETE FROM transaction_tags WHERE transaction_id = ?", id)
            id
        }
        val n = form.installments
        val each = form.total.v / n
        for (k in 1..n) {
            val cents = if (k == n) form.total.v - each * (n - 1) else each
            c.exec("INSERT INTO installments(transaction_id, k, date, cents) VALUES (?, ?, ?, ?)", txId, k, dates[k - 1].toString(), cents)
        }
        for (name in tags) c.exec("INSERT OR IGNORE INTO transaction_tags(transaction_id, tag_id) VALUES (?, ?)", txId, tagId(c, name))
        if (form.alias.isNotBlank() && key.v.isNotEmpty()) saveAlias(c, key, form.alias)
        if (occurrencePlanned != 0L && linkKey.v.isNotEmpty()) {
            // D65: the link this rewrites is kept on the lançamento, so deleting it gives it back.
            val p = "FROM planned WHERE id = $occurrencePlanned"
            c.exec(
                "UPDATE transactions SET prev_link_package = (SELECT link_package $p), prev_link_key = (SELECT link_key $p), " +
                    "prev_link_text = (SELECT link_text $p) WHERE id = ?",
                txId,
            )
            c.exec("UPDATE planned SET link_package = ?, link_key = ?, link_text = ? WHERE id = ?", raw.packageName, linkKey, bank, occurrencePlanned)
        }
        val planned = when {
            own != null && form.repeats == Repeats.MONTHLY && own.second == Repeats.ONCE -> {
                c.exec("UPDATE planned SET repeats = ? WHERE id = ?", Repeats.MONTHLY.ordinal, own.first)
                0L
            }
            own != null && form.repeats == Repeats.ONCE && own.second == Repeats.MONTHLY -> {
                deletePlanned(c, "id = ?", own.first)
                0L
            }
            own == null && form.repeats == Repeats.MONTHLY -> {
                val pkg = if (linkKey.v.isEmpty()) "" else raw.packageName
                insertPlanned(c, monthlyName, form, tags, form.date.yearMonth.plusMonth(), pkg, linkKey, if (linkKey.v.isEmpty()) "" else bank, txId)
            }
            else -> 0L
        }
        return Saved(TransactionId(txId), PlannedId(planned))
    }

    /**
     * A new previsto from [form] (budget, value, day of its date, repeats) named [name], with its first occurrence in
     * [first]; [source] is the lançamento whose "Repete" made it (D65), 0 for Salvar previsto.
     */
    private fun insertPlanned(
        c: SQLiteConnection, name: String, form: TransactionForm, tags: List<String>, first: YearMonth, pkg: String, key: MerchantKey, text: String,
        source: Long = 0,
    ): Long {
        c.exec(
            "INSERT INTO planned(name, budget_id, cents, day, repeats, link_package, link_key, link_text, source_transaction) VALUES (?,?,?,?,?,?,?,?,?)",
            name, form.budget.v, form.total.v, form.date.day, form.repeats.ordinal, pkg, key, text, source,
        )
        val id = c.lastId()
        for (t in tags) c.exec("INSERT OR IGNORE INTO planned_tags(planned_id, tag_id) VALUES (?, ?)", id, tagId(c, t))
        c.exec("INSERT INTO planned_dates(planned_id, month) VALUES (?, ?)", id, first.toString())
        return id
    }

    /**
     * R1: every Todo mês previsto gets its occurrences dated up to [today] + 10 days, from its latest row on
     * (like [ensureMonth]); a row that exists is never created again, Só uma vez never gets a second one.
     */
    private fun fillPlanned(c: SQLiteConnection, today: LocalDate) {
        for ((id, m) in missingOccurrences(c, today)) c.exec("INSERT OR IGNORE INTO planned_dates(planned_id, month) VALUES (?, ?)", id, m.toString())
    }

    /** The (previsto, month) rows [fillPlanned] would create for [today]. */
    private fun missingOccurrences(c: SQLiteConnection, today: LocalDate): List<Pair<Long, YearMonth>> {
        val until = LocalDate.fromEpochDays(minOf(today.toEpochDays() + 10, LAST_DATE.toEpochDays()))
        val rules = c.query(
            "SELECT p.id, p.day, MAX(d.month) FROM planned p JOIN planned_dates d ON d.planned_id = p.id WHERE p.repeats = ? GROUP BY p.id",
            Repeats.MONTHLY.ordinal,
        ) { Triple(it.getLong(0), it.getInt(1), YearMonth.parse(it.getText(2))) }
        val missing = ArrayList<Pair<Long, YearMonth>>()
        for ((id, day, last) in rules) {
            var m = last
            while (m < LAST_MONTH && occurrenceDate(m.plusMonth(), day) <= until) {
                m = m.plusMonth()
                missing += id to m
            }
        }
        return missing
    }

    /**
     * Open occurrences (not skipped, no lançamento) with their previsto, [where] over `p` (planned), oldest
     * date first then id, dated up to [today] + 10 (all with NO_DATE); `late` against [today] (never with
     * NO_DATE). noticeInPauta is left false.
     */
    private fun plannedRows(c: SQLiteConnection, today: LocalDate, where: String): List<Planned> {
        val rows = c.query(
            "SELECT d.id, p.id, p.name, p.budget_id, b.name, p.cents, p.day, p.repeats, d.month, p.link_key, p.link_text, " +
                "COALESCE(s.label, p.link_package) FROM planned_dates d JOIN planned p ON p.id = d.planned_id JOIN budgets b ON b.id = p.budget_id " +
                "LEFT JOIN sources s ON s.package = p.link_package " +
                "${if (where.isEmpty()) "WHERE" else "$where AND"} d.skipped = 0 AND d.id NOT IN (SELECT occurrence_id FROM transactions)",
        ) {
            val day = it.getInt(6)
            val month = YearMonth.parse(it.getText(8))
            val repeats = Repeats.entries[it.getInt(7)]
            val date = occurrenceDate(month, day)
            val linked = it.getText(9).isNotEmpty()
            Planned(
                occurrence = OccurrenceId(it.getLong(0)), planned = PlannedId(it.getLong(1)), name = it.getText(2),
                budget = BudgetId(it.getLong(3)), budgetName = it.getText(4), cents = Cents(it.getLong(5)), day = day, repeats = repeats,
                date = date, next = if (repeats == Repeats.MONTHLY) occurrenceDate(month.plusMonth(), day) else NO_DATE,
                linked = linked, linkText = if (linked) it.getText(10) else "", linkSource = if (linked) it.getText(11) else "",
                late = today != NO_DATE && date < today,
            )
        }
        if (rows.isEmpty()) return rows
        val tags = HashMap<Long, MutableList<String>>()
        c.query("SELECT l.planned_id, g.name FROM planned_tags l JOIN tags g ON g.id = l.tag_id ORDER BY g.name") {
            tags.getOrPut(it.getLong(0)) { ArrayList() }.add(it.getText(1))
        }
        // R1 (2026-10-22): with a today, only what is due by today + 10 shows, a far Salvar previsto included.
        val until = if (today == NO_DATE) LAST_DATE else LocalDate.fromEpochDays(minOf(today.toEpochDays() + 10, LAST_DATE.toEpochDays()))
        return rows.filter { it.date <= until }.map { it.copy(tags = tags[it.planned.v] ?: emptyList()) }
            .sortedWith(compareBy<Planned> { it.date }.thenBy { it.occurrence.v })
    }

    /**
     * D60: for each recado but an estorno, the linked open occurrence of the same fonte and key whose date is at most 10 days
     * from the recado's; the closest wins, then the earlier date, then the lower id. A loop, not a join: the
     * recado's merchant only exists after `extract`. Keyed by notice id; absent = not recognized.
     */
    private fun recognize(c: SQLiteConnection, notices: List<NoticeRow>, open: List<Planned>): Map<Long, Planned> {
        if (open.isEmpty() || notices.isEmpty()) return emptyMap()
        val links = c.query("SELECT id, link_package, link_key FROM planned WHERE link_key != ''") {
            it.getLong(0) to (it.getText(1) to MerchantKey.of(it.getText(2)))
        }.toMap()
        val byLink = HashMap<Pair<String, MerchantKey>, MutableList<Planned>>()
        for (p in open) links[p.planned.v]?.let { byLink.getOrPut(it) { ArrayList() }.add(p) }
        val found = HashMap<Long, Planned>()
        for (n in notices) {
            val key = MerchantKey.of(n.parsed.merchant)
            // An estorno is never the previsto's recado: its button is "Registrar estorno" (spec §2.8).
            if (key.v.isEmpty() || n.parsed.isRefund) continue
            val best = byLink[n.raw.packageName to key]?.filter { abs(it.date.toEpochDays() - n.parsed.date.toEpochDays()) <= 10 }
                ?.minWithOrNull(compareBy<Planned> { abs(it.date.toEpochDays() - n.parsed.date.toEpochDays()) }.thenBy { it.date }.thenBy { it.occurrence.v })
            if (best != null) found[n.id.v] = best
        }
        return found
    }

    /** The tag named [name] (already clean), created if new. */
    private fun tagId(c: SQLiteConnection, name: String): Long {
        c.query("SELECT id FROM tags WHERE name = ?", name) { it.getLong(0) }.firstOrNull()?.let { return it }
        c.exec("INSERT INTO tags(name) VALUES (?)", name)
        return c.lastId()
    }

    private fun saveAlias(c: SQLiteConnection, key: MerchantKey, alias: String) {
        if (alias.isBlank()) c.exec("DELETE FROM merchant_aliases WHERE merchant_key = ?", key)
        else c.exec(
            "INSERT OR REPLACE INTO merchant_aliases(merchant_key, alias, alias_key) VALUES (?, ?, ?)", key, alias.trim(), MerchantKey.of(alias),
        )
    }

    /** Deletes the transactions matching [where] with their installments, tag links and estornos (spec §2.8). */
    private fun deleteTransactions(c: SQLiteConnection, where: String, vararg args: Any) {
        val gone = c.query("SELECT id FROM transactions WHERE $where", *args) { it.getLong(0) }
        if (gone.isEmpty()) return
        val ids = gone.joinToString(",")
        // D65: a link this lançamento rewrote goes back to the one before, while the previsto still has the one it wrote.
        val relinked = c.query(
            "SELECT d.planned_id, t.notice_id, t.prev_link_package, t.prev_link_key, t.prev_link_text FROM transactions t " +
                "JOIN planned_dates d ON d.id = t.occurrence_id WHERE t.id IN ($ids) AND t.prev_link_key != ''",
        ) { listOf(it.getLong(0), it.getLong(1), it.getText(2), it.getText(3), it.getText(4)) }
        for ((planned, notice, pkg, key, text) in relinked) {
            val raw = notice(c, notice as Long).raw
            c.exec(
                "UPDATE planned SET link_package = ?, link_key = ?, link_text = ? WHERE id = ? AND link_package = ? AND link_key = ?",
                pkg, MerchantKey.of(key as String), text, planned, raw.packageName, MerchantKey.of(extract(raw, timeZone).merchant),
            )
        }
        // D65: the previsto its "Repete" made goes with it while none of its occurrences has a lançamento left.
        deletePlanned(
            c, "source_transaction IN ($ids) AND id NOT IN (SELECT d.planned_id FROM planned_dates d JOIN transactions t ON t.occurrence_id = d.id " +
                "WHERE t.id NOT IN ($ids))",
        )
        c.exec("DELETE FROM installments WHERE transaction_id IN ($ids)")
        c.exec("DELETE FROM transaction_tags WHERE transaction_id IN ($ids)")
        c.exec("DELETE FROM refunds WHERE transaction_id IN ($ids)")
        c.exec("UPDATE planned SET source_transaction = 0 WHERE source_transaction IN ($ids)")
        c.exec("DELETE FROM transactions WHERE id IN ($ids)")
    }

    private companion object {
        const val READERS = 2

        /** Every notice with its derived status: 0 pending, 1 dismissed, 2 processed (architecture §5). */
        const val NOTICES =
            "SELECT n.id, n.package, n.app_label, n.key, n.posted_at, n.when_ms, n.title, n.text, n.big_text, n.sub_text, " +
                "n.text_lines, n.category, n.channel_id, n.extras_json, " +
                "CASE WHEN EXISTS (SELECT 1 FROM transactions t WHERE t.notice_id = n.id) " +
                "OR EXISTS (SELECT 1 FROM refunds r WHERE r.estorno_notice_id = n.id OR r.notice_id = n.id) THEN 2 " +
                "WHEN n.dismissed = 1 THEN 1 ELSE 0 END AS status " +
                "FROM notices n"

        const val NOT_REFUNDED = "NOT EXISTS (SELECT 1 FROM refunds r WHERE r.transaction_id = t.id)"

        /**
         * v1: the schema of architecture §4, plus merchant_key on transactions (alias and suggestion lookups).
         * Budget and transaction ids are AUTOINCREMENT: a deleted one never comes back (D38).
         */
        /**
         * The one schema (D67, 2026-10-23: the old v1–v5 migrations dropped, nobody but the author used them).
         * The next change is a migration from this v1 (architecture §4).
         */
        val SCHEMA = listOf(
            "CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE sources(package TEXT PRIMARY KEY, label TEXT NOT NULL, added_at INTEGER NOT NULL)",
            "CREATE TABLE notices(id INTEGER PRIMARY KEY, package TEXT NOT NULL, app_label TEXT NOT NULL, key TEXT NOT NULL, " +
                "posted_at INTEGER NOT NULL, when_ms INTEGER NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, big_text TEXT NOT NULL, " +
                "sub_text TEXT NOT NULL, text_lines TEXT NOT NULL, category TEXT NOT NULL, channel_id TEXT NOT NULL, " +
                "extras_json TEXT NOT NULL, content_hash TEXT NOT NULL, dismissed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE, position INTEGER NOT NULL)",
            "CREATE TABLE budget_months(budget_id INTEGER NOT NULL, month TEXT NOT NULL, limit_cents INTEGER NOT NULL, PRIMARY KEY(budget_id, month))",
            "CREATE TABLE week_steps(budget_id INTEGER NOT NULL, week_start TEXT NOT NULL, cents INTEGER NOT NULL, PRIMARY KEY(budget_id, week_start))",
            "CREATE TABLE ceiling_months(month TEXT PRIMARY KEY, cents INTEGER NOT NULL)",
            // occurrence_id: the occurrence it closed (0 = none, D59); prev_link_*: the previsto's link before this
            // lançamento rewrote it, given back when it is deleted (D65).
            "CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, notice_id INTEGER NOT NULL, budget_id INTEGER NOT NULL, " +
                "merchant TEXT NOT NULL, merchant_key TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, " +
                "total_cents INTEGER NOT NULL, corrected INTEGER NOT NULL DEFAULT 0, caption TEXT NOT NULL DEFAULT '', " +
                "occurrence_id INTEGER NOT NULL DEFAULT 0, prev_link_package TEXT NOT NULL DEFAULT '', " +
                "prev_link_key TEXT NOT NULL DEFAULT '', prev_link_text TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE installments(transaction_id INTEGER NOT NULL, k INTEGER NOT NULL, date TEXT NOT NULL, cents INTEGER NOT NULL, " +
                "PRIMARY KEY(transaction_id, k))",
            "CREATE TABLE refunds(estorno_notice_id INTEGER PRIMARY KEY, transaction_id INTEGER NOT NULL, notice_id INTEGER NOT NULL)",
            "CREATE TABLE tags(id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE)",
            "CREATE TABLE transaction_tags(transaction_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(transaction_id, tag_id))",
            "CREATE TABLE merchant_aliases(merchant_key TEXT PRIMARY KEY, alias TEXT NOT NULL, alias_key TEXT NOT NULL DEFAULT '')",
            // source_transaction: the lançamento whose "Repete" created it (0 = Salvar previsto, D65).
            "CREATE TABLE planned(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, budget_id INTEGER NOT NULL, " +
                "cents INTEGER NOT NULL, day INTEGER NOT NULL, repeats INTEGER NOT NULL, link_package TEXT NOT NULL DEFAULT '', " +
                "link_key TEXT NOT NULL DEFAULT '', link_text TEXT NOT NULL DEFAULT '', source_transaction INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE planned_tags(planned_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(planned_id, tag_id))",
            "CREATE TABLE planned_dates(id INTEGER PRIMARY KEY AUTOINCREMENT, planned_id INTEGER NOT NULL, month TEXT NOT NULL, " +
                "skipped INTEGER NOT NULL DEFAULT 0, UNIQUE(planned_id, month))",
            "CREATE INDEX notices_key ON notices(key, posted_at)",
            "CREATE INDEX notices_posted_at ON notices(posted_at)",
            "CREATE INDEX transactions_notice ON transactions(notice_id)",
            "CREATE INDEX transactions_budget ON transactions(budget_id)",
            "CREATE INDEX transactions_merchant ON transactions(merchant_key)",
            "CREATE INDEX transactions_occurrence ON transactions(occurrence_id)",
            "CREATE INDEX installments_date ON installments(date)",
            "CREATE INDEX refunds_transaction ON refunds(transaction_id)",
            "CREATE INDEX refunds_notice ON refunds(notice_id)",
            "CREATE INDEX transaction_tags_tag ON transaction_tags(tag_id)",
            "CREATE INDEX planned_tags_tag ON planned_tags(tag_id)",
        )

        const val SCHEMA_VERSION = 1L

        /** Creates the schema on an empty file; later versions add one `if (v < N)` each, in a transaction that ends by writing N (§4). */
        fun migrate(c: SQLiteConnection) {
            val v = c.long("PRAGMA user_version")
            if (v < 1) {
                c.execSQL("BEGIN IMMEDIATE")
                SCHEMA.forEach(c::execSQL)
                c.execSQL("PRAGMA user_version = 1")
                c.execSQL("COMMIT")
            }
        }
    }
}

// ---- SQL helpers: bind by position (Long, Int, String, Boolean) ----

/** Every line break of a legenda (spec §2.12): CRLF as one, then LF, CR and the invisible U+2028/U+2029. */
private val CLEAN_BREAKS = Regex("\\r\\n|[\\r\\n\\u2028\\u2029]")

/**
 * What the trim takes (spec §2.12, 2026-10-20): every invisible that makes a line look empty — ASCII
 * controls (space, tab, VT, FF…), NEL and the NBSP family (U+00A0, U+1680, U+2000–U+200A, U+202F,
 * U+205F, U+3000). An explicit set: `isWhitespace` differs between targets. In the middle of the text
 * they stay (only the five breaks of [CLEAN_BREAKS] become spaces).
 */
internal fun isEdgeSpace(c: Char): Boolean =
    c <= ' ' || c == '\u0085' || c == '\u00A0' || c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u202F' || c == '\u205F' || c == '\u3000'

/**
 * The text as SQLite gives it back (D28): its UTF-16 ↔ UTF-8 conversion drops a leading U+FEFF,
 * drops U+FFFE and turns U+FFFF and lone surrogates into U+FFFD. Every text is bound through this,
 * so what is read is always what was stored: those become U+FFFD.
 */
internal fun storable(s: String): String {
    fun bad(i: Int): Boolean {
        val c = s[i]
        return c == '\uFEFF' || c == '\uFFFE' || c == '\uFFFF' ||
            (c.isHighSurrogate() && (i + 1 >= s.length || !s[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !s[i - 1].isHighSurrogate()))
    }
    if (s.indices.none(::bad)) return s
    return buildString(s.length) { for (i in s.indices) append(if (bad(i)) '\uFFFD' else s[i]) }
}

private fun SQLiteStatement.bindAll(args: Array<out Any>) {
    args.forEachIndexed { i, a ->
        when (a) {
            is Long -> bindLong(i + 1, a)
            is Int -> bindLong(i + 1, a.toLong())
            is Boolean -> bindLong(i + 1, if (a) 1 else 0)
            is String -> bindText(i + 1, storable(a))
            is MerchantKey -> bindText(i + 1, storable(a.v))
            else -> error("can't bind ${a::class}")
        }
    }
}

internal fun SQLiteConnection.exec(sql: String, vararg args: Any) {
    prepare(sql).use { it.bindAll(args); it.step() }
}

internal fun <T> SQLiteConnection.query(sql: String, vararg args: Any, row: (SQLiteStatement) -> T): List<T> =
    prepare(sql).use { st ->
        st.bindAll(args)
        val out = ArrayList<T>()
        while (st.step()) out.add(row(st))
        out
    }

/** First column of the first row; 0 when there is none or it's NULL. */
internal fun SQLiteConnection.long(sql: String, vararg args: Any): Long =
    prepare(sql).use { st ->
        st.bindAll(args)
        if (st.step() && !st.isNull(0)) st.getLong(0) else 0L
    }

/** A legenda (and a previsto's name) as stored (spec §2.12, D53, D62): breaks to spaces, edges trimmed, storable. */
private fun cleanCaption(s: String): String = storable(CLEAN_BREAKS.replace(s, " ").trim(::isEdgeSpace))

/**
 * A previsto's name (D62, D64): the legenda, else the alias, else the merchant (the bank text, or the manual's name),
 * cleaned like the legenda. Over [MAX_CAPTION] the form is refused; screens check it first (spec §7.3).
 */
fun plannedName(caption: String, alias: String, merchant: String): String =
    cleanCaption(cleanCaption(caption).ifEmpty { alias.trim().ifEmpty { storable(merchant.trim()) } })

/** An occurrence's date (P8): [day] in [month], or the month's last day when it has none. */
private fun occurrenceDate(month: YearMonth, day: Int): LocalDate = month.onDay(minOf(day, month.numberOfDays))

private val LAST_MONTH = YearMonth(9999, 12)

/** The range of storable dates: ISO text sorts as dates only for years 0001..9999. */
private val FIRST_DATE = LocalDate(1, 1, 1)
private val LAST_DATE = LocalDate(9999, 12, 31)

/**
 * A date as the lower or upper bound of a SQL range, in text that compares right with every stored date:
 * clamped into the storable range, so a month crossing its edge still finds its rows, and past it on the
 * far side ("9999-12-32", "0000-12-31") so a range wholly outside it finds none.
 */
private fun LocalDate.low(): String = if (this > LAST_DATE) "9999-12-32" else maxOf(this, FIRST_DATE).toString()

private fun LocalDate.high(): String = if (this < FIRST_DATE) "0000-12-31" else minOf(this, LAST_DATE).toString()

private fun SQLiteConnection.changes() = long("SELECT changes()")

private fun SQLiteConnection.lastId() = long("SELECT last_insert_rowid()")
