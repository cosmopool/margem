package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Invariants of the previsto (spec §2.12, §2.13, §3, §5, §7.2, §7.3, §7.4, §7.5, S1; glossary Previsto,
// Ligar; architecture D59–D64, §5), checked through the public API only, after every step of random,
// fixed-seed sequences of: captures of recados from two registered fontes; previstos created by
// "Salvar previsto" (addManual with a date ahead, ONCE or MONTHLY), by "Repete · Todo mês" in the
// Lançar recado (accept), in the lançamento manual (addManual) and in the edição (edit); Lançar previsto
// (accept of a recognized recado); "É um previsto? Ligar" (accept with an occurrence picked by hand);
// Lançar sem recado (addManual with the occurrence, R6/R6b); Pular este mês; Apagar previsto; Editar
// previsto; deleting lançamentos, verbas and marcadores; juntar marcadores; riscar/trazer de volta; and
// `today` moving forward across month turns. The month start day is set once, before anything else.
//
// The test keeps a model built only from the spec and the KDoc: every previsto (rule), its occurrences
// per calendar month (open, closed by one lançamento, or skipped; created once dated up to today + 10,
// R1, and never again for the same month, D59), its link (fonte + bank text, D59, D62) and every
// lançamento (all à vista). After each step:
// - planned(today) lists exactly the open occurrences the model expects, one per (previsto, month), each
//   field as the rule says (P8: the day clamped to the month's last day), and a closed or skipped one
//   never again; deleting the lançamento of a previsto reopens the same occurrence (S1);
// - NoticeRow.planned is the closest open occurrence of a previsto linked to the recado's fonte with the
//   same key, within 10 days (ties: earlier date, then lower id), always zero for an empty key (R2), and
//   zero in every read but pending; Planned.noticeInPauta agrees with it;
// - every Planned has ≥ 1 tag, an existing budget and a name ≤ 80 (D62);
// - spent (budgetsOverview, budgetDetail, history) and the Leitura (tagRanking, merchantRanking,
//   spentByDay) equal what the lançamentos alone give by spec §3/§7.5 (a previsto never counts).
//
// Bank-text keys are written by hand from the MerchantKey KDoc (spacing, case and accents ignored), never
// computed with MerchantKey or fold.

private val PVINV_TODAY0 = LocalDate(2026, 10, 9) // sexta-feira
/** Budgets are created "on" this day, so every month a lançamento can touch has a limit row. */
private val PVINV_BUDGET_DAY = LocalDate(2026, 6, 1)
/** No lançamento is ever dated before this. */
private val PVINV_FIRST_DAY = LocalDate(2026, 6, 1)
private val PVINV_TZ = TimeZone.of("America/Sao_Paulo")
private const val PVINV_NU = "com.nu.production"
private const val PVINV_AURORA = "br.com.bancoaurora"
private val PVINV_LABEL = linkedMapOf(PVINV_NU to "Nubank", PVINV_AURORA to "Banco Aurora")

/**
 * Every bank text the captures use, with its key written by hand from the MerchantKey KDoc (D61: "VIVO
 * FIBRA", "vivo fibra" and "VÍVO FIBRA" share a key; spacing anywhere is dropped). "" = no bank text read
 * (a Pix sent): the empty key, which never links nor is recognized (R2).
 */
private val PVINV_KEY: Map<String, String> = linkedMapOf(
    "VIVO FIBRA" to "vivofibra", "vivo fibra" to "vivofibra", "VÍVO FIBRA" to "vivofibra", "VivoFibra" to "vivofibra",
    "SMARTFIT" to "smartfit", "Smart Fit" to "smartfit", "SMÁRT FIT" to "smartfit",
    "NETFLIX" to "netflix", "Netflix" to "netflix",
    "ENEL" to "enel", "Énel" to "enel",
    "PADARIA SAO JOSE" to "padariasaojose", "Padaria São José" to "padariasaojose",
    "" to "",
)

/** Previsto names and legendas; some need the cleaning of a legenda (breaks become a space, edges trimmed). */
private val PVINV_NAMES = listOf(
    "Internet", "Academia", "Aluguel", "Conta de luz", "  Streaming  ", "Luz\nda casa", "Conta\r\nde água",
    "Plano\u2028do celular", "\tEscola ", "Condomínio",
)

/** 87 characters after cleaning: over the 80 of a legenda and of a previsto's name (D53, D62). */
private val PVINV_LONG = "Mensalidade " + "x".repeat(75)
private val PVINV_TAGS = listOf("casa", "contas", "internet", "lazer", "saude", "feira")
private val PVINV_UNKNOWN_BUDGET = BudgetId(987_654)
private val PVINV_UNKNOWN_TX = TransactionId(987_654)
private val PVINV_UNKNOWN_NOTICE = NoticeId(987_654)
private val PVINV_UNKNOWN_TAG = TagId(987_654)
private val PVINV_UNKNOWN_PLANNED = PlannedId(987_654)
private val PVINV_UNKNOWN_OCC = OccurrenceId(987_654)

private val PVINV_BREAKS = Regex("\r\n|\r|\n|\u2028|\u2029")

/** TransactionForm.caption KDoc (and D62 for the previsto's name): every break becomes one space, edges trimmed. */
private fun pvinvClean(s: String): String = PVINV_BREAKS.replace(s, " ").trim()

/** TransactionForm.tags KDoc: trimmed, lowercased, blanks and repeats dropped; rows show them sorted. */
private fun pvinvTags(tags: List<String>) = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()

private fun pvinvShow(s: String) = s.replace("\r", "\\r").replace("\n", "\\n").replace("\u2028", "\\u2028").replace("\t", "\\t")

private fun LocalDate.pvinvDays(n: Int): LocalDate = if (n >= 0) plus(n, DateTimeUnit.DAY) else minus(-n, DateTimeUnit.DAY)

private fun YearMonth.pvinvShift(n: Int): YearMonth {
    var m = this
    repeat(abs(n)) { m = if (n > 0) m.plusMonth() else m.minusMonth() }
    return m
}

/** P8 (Planned.day/date KDoc): the day in that calendar month, or the month's last day when it has none. */
private fun pvinvOccDate(m: YearMonth, day: Int) = LocalDate(m.year, m.month, minOf(day, m.numberOfDays))

/** spec §3: from day D (or the month's last day) to the eve of day D of the next month. */
private fun pvinvWindow(m: YearMonth, day: Int): Pair<LocalDate, LocalDate> =
    pvinvOccDate(m, day) to pvinvOccDate(m.plusMonth(), day).pvinvDays(-1)

/** spec §3: the budget month a date falls in. */
private fun pvinvMonthOf(d: LocalDate, day: Int): YearMonth {
    val ym = d.yearMonth
    return if (d >= pvinvWindow(ym, day).first) ym else ym.minusMonth()
}

private fun pvinvMonths(from: YearMonth, to: YearMonth): List<YearMonth> {
    val out = ArrayList<YearMonth>()
    var m = from
    while (m <= to) {
        out.add(m)
        m = m.plusMonth()
    }
    return out
}

/** "1.234,56" */
private fun pvinvBrl(c: Long): String {
    val reais = (c / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return "$reais,${(c % 100).toString().padStart(2, '0')}"
}

/** One real SQLite file per test, deleted afterwards with its -wal and -shm. */
private suspend fun withPrevistoInvariantsDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "PrevistoInvariants-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path, timeZone = PVINV_TZ)
        try {
            block(db)
        } finally {
            db.close()
        }
    } finally {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            runCatching { SystemFileSystem.delete(Path(path + suffix), mustExist = false) }
        }
    }
}

/** A previsto's link (D59): fonte (package) + bank text; both "" = not linked. */
private data class PvInvLink(val pkg: String = "", val text: String = "") {
    val on: Boolean get() = pkg.isNotEmpty()
}

private enum class PvInvState { OPEN, CLOSED, SKIPPED }

/** One month of a previsto (a `planned_dates` row, D59), from the moment it is due to be created. */
private class PvInvOcc {
    var state = PvInvState.OPEN
    var tx = TransactionId(0)
    /** 0 until a read lists it. */
    var id = OccurrenceId(0)
}

/** The model of one previsto: the rule (D59, D64) and its months. */
private class PvInvPlan(
    val label: String,
    var name: String,
    var budget: BudgetId,
    var tags: List<String>,
    var cents: Long,
    var day: Int,
    var repeats: Repeats,
    /** The calendar month of the first occurrence. */
    val first: YearMonth,
    var link: PvInvLink,
) {
    /** 0 until known: accept and edit don't return the id of the previsto "Todo mês" creates. */
    var id = PlannedId(0)
    var everMonthly = repeats == Repeats.MONTHLY
    /** S1 ambiguity: the other link a reopened occurrence may show (the one before the deleted lançamento). */
    var linkAlt: PvInvLink? = null
    val occs = LinkedHashMap<YearMonth, PvInvOcc>()

    /** Went from "Todo mês" to "Só uma vez" (spec §2.13: how to stop it). */
    val stopped: Boolean get() = repeats == Repeats.ONCE && everMonthly

    override fun toString() = "previsto ${id.v} ($label)"
}

/** The model of one lançamento (all à vista). [plan]/[month] = the occurrence it closed (its origin). */
private class PvInvTx(
    val id: TransactionId,
    val notice: NoticeId,
    var budget: BudgetId,
    var tags: List<String>,
    var cents: Long,
    val date: LocalDate,
    val time: LocalTime,
    val merchant: String,
    val caption: String,
    var plan: PvInvPlan?,
    val month: YearMonth,
    /** The previsto's link before this lançamento's accept rewrote it; null when it didn't change it. */
    val linkAlt: PvInvLink?,
) {
    /** D65: the previsto this lançamento's "Repete" created; null when none (or deleted). */
    var started: PvInvPlan? = null
}

/** What a refused write must leave untouched ("nothing written", and no version bump: Db.version KDoc). */
private data class PvInvSnap(
    val txs: List<TransactionRow>,
    val planned: List<Planned>,
    val pending: List<NoticeRow>,
    val version: Long,
)

private class PrevistoInvariantRun(val db: Db, val seed: Int, val startDay: Int) {
    val rnd = Random(seed)
    val log = ArrayList<String>()
    var step = 0
    var action = "setup"
    var today = PVINV_TODAY0
    var captures = 0
    var budgetCounter = 0
    var centsCounter = 0

    val budgets = LinkedHashMap<BudgetId, String>()
    val goneBudgets = ArrayList<BudgetId>()
    val plans = ArrayList<PvInvPlan>()
    val deletedPlans = LinkedHashSet<PlannedId>()
    val occIds = HashMap<OccurrenceId, Pair<PvInvPlan, YearMonth>>()
    val deletedOccs = LinkedHashSet<OccurrenceId>()
    val txs = LinkedHashMap<TransactionId, PvInvTx>()
    val goneTx = ArrayList<TransactionId>()
    /** Fonte and bank text of every captured recado. */
    val noticeInfo = LinkedHashMap<NoticeId, PvInvLink>()
    var lastRows: List<Planned> = emptyList()

    fun log(s: String) {
        log.add("#$step [$today] $s")
    }

    /**
     * Every previsto value comes from here, so no two previstos ever share one: the previsto that accept or
     * edit creates (whose id they don't return) is found by its value when planned(today) first lists it.
     */
    fun uniqueCents(): Long {
        centsCounter++
        return 4_000L + centsCounter * 1_237L
    }

    fun keyOf(text: String): String = PVINV_KEY[text] ?: fail("bank text \"$text\" is not in the hand-made key table")

    fun pkgOf(label: String): String = PVINV_LABEL.entries.firstOrNull { it.value == label }?.key ?: fail("linkSource \"$label\" is the label of no fonte")

    fun planOf(id: PlannedId): PvInvPlan = plans.firstOrNull { it.id == id } ?: fail("previsto ${id.v} is not alive in the model")

    fun cur(): YearMonth = pvinvMonthOf(today, startDay)

    fun randomTags(): List<String> = List(1 + rnd.nextInt(2)) { PVINV_TAGS.random(rnd) }

    fun pickGoneBudget(): BudgetId = (goneBudgets + PVINV_UNKNOWN_BUDGET).random(rnd)

    /** Db.accept KDoc (and addManual: same rules): budget exists, ≥ 1 tag after cleaning, total > 0, installments 1..400, legenda ≤ 80. */
    fun formOk(f: TransactionForm) =
        f.budget in budgets && pvinvTags(f.tags).isNotEmpty() && f.total.v > 0 && f.installments in 1..400 &&
            pvinvClean(f.caption).length <= MAX_CAPTION

    /** TransactionForm.repeats/planned KDoc: the legenda, else (no alias is ever set here) the bank text, cleaned like a legenda. */
    fun planName(f: TransactionForm) = pvinvClean(f.caption).ifEmpty { pvinvClean(f.merchant) }

    fun describe(f: TransactionForm) =
        "budget=${f.budget.v} tags=${f.tags} total=${f.total.v} \"${pvinvShow(f.merchant)}\" ${f.date} ${f.time} " +
            "caption=\"${pvinvShow(f.caption)}\" occurrence=${f.occurrence.v} ${f.repeats} planned=${f.planned.v}"

    suspend fun run(steps: Int) {
        try {
            setup()
            action = "check after setup"
            checkAll()
            for (i in 1..steps) {
                step = i
                act()
                checkAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AssertionError(
                "seed=$seed startDay=$startDay step=$step action=$action today=$today: ${e.message}\n" +
                    "--- actions so far ---\n${log.joinToString("\n")}",
                e,
            )
        }
    }

    suspend fun setup() {
        log("setup startDay=$startDay")
        for ((pkg, label) in PVINV_LABEL) assertTrue(db.addSource(pkg, label, 0L), "addSource $pkg")
        assertTrue(db.setMonthStartDay(startDay, today), "setMonthStartDay($startDay)")
        repeat(3) { createBudgetAction() }
        // Linked previstos from "Repete · Todo mês" in the Lançar recado (spec §2.13, D64): first occurrence next month.
        for ((pkg, merchant) in listOf(PVINV_NU to "VIVO FIBRA", PVINV_AURORA to "SMARTFIT", PVINV_NU to "ENEL", PVINV_AURORA to "Netflix")) {
            val r = captureNotice(pkg, merchant, 2_000L + rnd.nextLong(30_000), today.pvinvDays(-(15 + rnd.nextInt(21))))
            acceptWith(r, monthly = true, refuse = false)
        }
        repeat(2) { futureAction(refuse = false) }
        manualAction(refuse = false, monthly = true)
        repeat(4) { captureAction() }
    }

    // ---- reads with their invariants ----

    /**
     * Triage 2026-10-22 (user's decision, D59): a read with today lists an open occurrence only once it is due by
     * today + 10 (its day may have been edited further away). Unknown to the model: assumed due.
     */
    fun due(o: OccurrenceId): Boolean = occIds[o]?.let { (p, m) -> pvinvOccDate(m, p.day) <= today.pvinvDays(10) } ?: true

    /** Marks the occurrences planned(today)/pending(today) create: dated up to today + 10 (R1), ONCE only its first. */
    fun markCreated() {
        val horizon = today.pvinvDays(10)
        for (p in plans) {
            var m = p.first
            while (pvinvOccDate(m, p.day) <= horizon) {
                p.occs.getOrPut(m) { PvInvOcc() }
                if (p.repeats == Repeats.ONCE) break
                m = m.plusMonth()
            }
        }
    }

    /** planned(today), checked against the model (Db.planned KDoc, Planned KDoc, D59, D62). */
    suspend fun readPlanned(): List<Planned> {
        val v0 = db.version.value
        val rows = db.planned(today)
        assertEquals(v0, db.version.value, "planned(today) creates occurrences without bumping version (Db.planned KDoc)")
        markCreated()
        // The previstos accept/edit created: found by their (unique) value.
        for (row in rows) {
            if (plans.any { it.id == row.planned }) continue
            assertFalse(row.planned in deletedPlans, "a deleted previsto is listed in planned(today): $row")
            val p = plans.firstOrNull { it.id.v == 0L && it.cents == row.cents.v }
                ?: fail("planned(today) lists a previsto no action created (or one already deleted): $row")
            p.id = row.planned
            log("  found $p in planned(today)")
        }
        val seenMonths = HashSet<Pair<PlannedId, YearMonth>>()
        val seenOccs = HashSet<OccurrenceId>()
        for (row in rows) {
            val p = planOf(row.planned)
            val mapped = occIds[row.occurrence]
            val m: YearMonth
            if (mapped != null) {
                assertTrue(mapped.first === p, "occurrence ${row.occurrence.v} belongs to ${mapped.first}, now listed under $p")
                m = mapped.second
            } else {
                m = row.date.yearMonth
                var o = p.occs[m]
                if (o == null) {
                    // Ambiguity: the write may create the first occurrence's row at once ("Salvar previsto" puts it
                    // in Lançamentos, §7.3) instead of waiting for today + 10: tolerated for the first month only.
                    assertEquals(
                        p.first, m,
                        "$p: occurrence of $m listed before it was due (spec §7.4: the next month's enters A caminho " +
                            "10 days before its day; a ONCE previsto never gets a second one): $row",
                    )
                    o = PvInvOcc()
                    p.occs[m] = o
                }
                assertEquals(
                    OccurrenceId(0), o.id,
                    "$p month $m: a second occurrence ${row.occurrence.v} for the same month (D59: a month that has its row never gets another)",
                )
                o.id = row.occurrence
                occIds[row.occurrence] = p to m
            }
            assertTrue(seenMonths.add(p.id to m), "two occurrences of $p in $m in one planned(today): $rows")
            assertTrue(seenOccs.add(row.occurrence), "occurrence ${row.occurrence.v} listed twice in one planned(today)")
            val o = p.occs.getValue(m)
            assertEquals(
                PvInvState.OPEN, o.state,
                "$p month $m (occurrence ${row.occurrence.v}) is ${o.state} (lançamento ${o.tx.v}) but planned(today) lists it as open: $row",
            )

            // The link (D59, D62): "" and "" when not linked; the fonte's label and the raw bank text when linked.
            if (!row.linked) {
                assertEquals("", row.linkText, "not linked: linkText \"\" ($row)")
                assertEquals("", row.linkSource, "not linked: linkSource \"\" ($row)")
            }
            val seen = if (row.linked) PvInvLink(pkgOf(row.linkSource), row.linkText) else PvInvLink()
            if (seen != p.link) {
                val alt = p.linkAlt
                assertTrue(
                    alt != null && seen == alt,
                    "$p link: expected ${p.link}${if (alt != null) " (or $alt, the link before the deleted lançamento, S1)" else ""}, read $seen",
                )
                log("  S1 reading: $p shows the link $seen")
                p.link = seen
            }
            p.linkAlt = null

            assertTrue(row.tags.isNotEmpty(), "every previsto has at least one marcador (spec §2.13 Criar): $row")
            assertTrue(row.budget in budgets, "every previsto's verba exists (D59: apagar verba apaga os previstos dela): $row")
            assertTrue(row.name.length <= MAX_CAPTION, "a previsto's name is at most 80 (D62): $row")
            val date = pvinvOccDate(m, p.day)
            val expected = Planned(
                occurrence = row.occurrence,
                planned = p.id,
                name = p.name,
                budget = p.budget,
                budgetName = budgets[p.budget] ?: "",
                tags = p.tags,
                cents = Cents(p.cents),
                day = p.day,
                repeats = p.repeats,
                date = date,
                next = if (p.repeats == Repeats.MONTHLY) pvinvOccDate(m.plusMonth(), p.day) else NO_DATE,
                linked = p.link.on,
                linkText = p.link.text,
                linkSource = if (p.link.on) PVINV_LABEL.getValue(p.link.pkg) else "",
                late = date < today,
                noticeInPauta = row.noticeInPauta,
            )
            assertEquals(expected, row, "$p month $m: the row of planned(today) (Planned KDoc, P8, P10)")
        }
        // Every open occurrence due by today + 10 is listed (Db.planned KDoc: every open occurrence).
        for (p in plans) {
            if (p.id.v == 0L) {
                assertTrue(
                    p.occs.isEmpty(),
                    "$p has occurrences dated up to today + 10 (${p.occs.keys}) but planned(today) doesn't list it (R1)",
                )
                continue
            }
            for ((m, o) in p.occs) {
                if (o.state != PvInvState.OPEN) continue
                // Ambiguity: after "Todo mês" → "Só uma vez", the rows already created for later months may go either way.
                if (p.stopped && m != p.first) continue
                // Triage 2026-10-22 (user's decision, D59): every read with today lists only what is due by today + 10,
                // so a month whose day was edited past that window waits, open, until it is due.
                if (pvinvOccDate(m, p.day) > today.pvinvDays(10)) continue
                assertTrue(
                    o.id.v != 0L && rows.any { it.occurrence == o.id },
                    "$p month $m is open (dated ${pvinvOccDate(m, p.day)}, due by today + 10) but missing from planned(today)",
                )
            }
        }
        val sorted = rows.sortedWith(compareBy<Planned>({ it.date }, { it.occurrence.v }))
        assertEquals(sorted.map { it.occurrence }, rows.map { it.occurrence }, "planned(today): oldest date first, ties by occurrence id")
        lastRows = rows
        return rows
    }

    /** NoticeRow.planned KDoc (D60, D62): the closest open occurrence of a previsto linked to the same fonte and key, within 10 days. */
    fun recognition(r: NoticeRow, rows: List<Planned>): Planned {
        val key = keyOf(r.parsed.merchant)
        if (key.isEmpty()) return Planned()
        val d = r.parsed.date
        return rows.filter { o ->
            val p = planOf(o.planned)
            p.link.on && p.link.pkg == r.raw.packageName && keyOf(p.link.text) == key && abs(o.date.daysUntil(d)) <= 10
        }.minWithOrNull(compareBy<Planned>({ abs(it.date.daysUntil(d)) }, { it.date }, { it.occurrence.v })) ?: Planned()
    }

    /** pending(today), checked against planned(today) and the model (Db.pending KDoc, NoticeRow.planned KDoc). */
    suspend fun readPending(): List<NoticeRow> {
        val rows = readPlanned()
        val v0 = db.version.value
        val prs = db.pending(today)
        assertEquals(v0, db.version.value, "pending(today) creates occurrences without bumping version (Db.pending KDoc)")
        val expected = HashMap<NoticeId, Planned>()
        for (r in prs) {
            assertEquals(NoticeStatus.PENDING, r.status, "pending() lists only pending recados (${r.id.v})")
            val key = keyOf(r.parsed.merchant)
            val got = r.planned
            if (key.isEmpty()) assertEquals(Planned(), got, "R2: recado ${r.id.v} without bank text is never recognized")
            if (got.occurrence.v != 0L) {
                val row = rows.firstOrNull { it.occurrence == got.occurrence }
                assertNotNull(row, "recado ${r.id.v} is recognized as occurrence ${got.occurrence.v}, which is not open (not in planned(today))")
                val p = planOf(got.planned)
                assertTrue(
                    p.link.on && p.link.pkg == r.raw.packageName,
                    "recado ${r.id.v} of ${r.raw.packageName} recognized by $p, linked to ${p.link}: only the same fonte (spec §2.13 Reconhecer)",
                )
                assertEquals(key, keyOf(p.link.text), "recado ${r.id.v} \"${r.parsed.merchant}\" recognized by $p linked to \"${p.link.text}\": only the same key")
                assertTrue(
                    abs(got.date.daysUntil(r.parsed.date)) <= 10,
                    "recado ${r.id.v} of ${r.parsed.date} recognized as the occurrence of ${got.date}: only within 10 days",
                )
            }
            val exp = recognition(r, rows)
            expected[r.id] = exp
            assertEquals(
                exp, got,
                "recado ${r.id.v} (${r.raw.packageName} \"${r.parsed.merchant}\" ${r.parsed.date}): NoticeRow.planned = the closest " +
                    "open occurrence linked to the same fonte and key, within 10 days (ties: earlier date, then lower id)",
            )
        }
        for (row in rows) {
            val exp = prs.any { expected.getValue(it.id).occurrence == row.occurrence }
            assertEquals(exp, row.noticeInPauta, "occurrence ${row.occurrence.v}: noticeInPauta iff a pending recado is recognized as it (Planned KDoc)")
        }
        // Db.pending KDoc: with NO_DATE nothing is created, the existing open occurrences are still recognized, late is false.
        val plain = db.pending()
        assertEquals(
            prs.map { it.id to it.planned.copy(late = false) }, plain.map { it.id to it.planned },
            "pending() without today recognizes the same existing occurrences, with late false",
        )
        return prs
    }

    suspend fun snap() = PvInvSnap(db.transactions(), db.planned(today), db.pending(today), db.version.value)

    suspend fun assertUnchanged(before: PvInvSnap, what: String) {
        assertEquals(before, snap(), "$what: nothing written and no version bump")
    }

    // ---- model updates ----

    fun newTx(id: TransactionId, notice: NoticeId, f: TransactionForm, plan: PvInvPlan?, month: YearMonth, linkAlt: PvInvLink?) {
        assertFalse(id in txs || id in goneTx, "lançamento id ${id.v} was never used before (Db.accept KDoc)")
        if (plan != null) assertTrue(txs.values.none { it.plan === plan && it.month == month }, "model: $plan month $month closed by one lançamento only")
        txs[id] = PvInvTx(id, notice, f.budget, pvinvTags(f.tags), f.total.v, f.date, f.time, f.merchant.trim(), pvinvClean(f.caption), plan, month, linkAlt)
    }

    fun closeOcc(p: PvInvPlan, m: YearMonth, tx: TransactionId) {
        val o = p.occs.getValue(m)
        assertEquals(PvInvState.OPEN, o.state, "model: $p month $m was open")
        o.state = PvInvState.CLOSED
        o.tx = tx
    }

    /** A lançamento is gone: its occurrence, when its previsto is alive, is open again (S1). Returns that occurrence. */
    fun dropTx(t: PvInvTx): OccurrenceId? {
        txs.remove(t.id)
        goneTx.add(t.id)
        val p = t.plan ?: return null
        if (p !in plans) return null
        val o = p.occs.getValue(t.month)
        assertEquals(PvInvState.CLOSED, o.state, "model: $p month ${t.month} was closed by ${t.id.v}")
        assertEquals(t.id, o.tx, "model: $p month ${t.month} was closed by ${t.id.v}")
        o.state = PvInvState.OPEN
        o.tx = TransactionId(0)
        val alt = t.linkAlt
        if (alt != null && alt != p.link) p.linkAlt = alt
        log("  S1: $p month ${t.month} (occurrence ${o.id.v}) is open again")
        return o.id
    }

    /** Apagar previsto (Db.deletePlanned KDoc): the rule and every occurrence; its lançamentos stay with no origin. */
    fun dropPlan(p: PvInvPlan) {
        plans.remove(p)
        if (p.id.v != 0L) deletedPlans.add(p.id)
        for (o in p.occs.values) {
            if (o.id.v != 0L) {
                occIds.remove(o.id)
                deletedOccs.add(o.id)
            }
        }
        for (t in txs.values) {
            if (t.plan === p) t.plan = null
            if (t.started === p) t.started = null
        }
        log("  $p is deleted")
    }

    fun notOpenOccurrences(): List<OccurrenceId> =
        plans.flatMap { p -> p.occs.values.filter { it.state != PvInvState.OPEN && it.id.v != 0L }.map { it.id } } +
            deletedOccs.filter { it !in occIds } + PVINV_UNKNOWN_OCC

    // ---- actions ----

    suspend fun act() {
        val r = rnd.nextInt(100)
        when {
            r < 9 -> { action = "capture"; captureAction() }
            r < 17 -> { action = "capture near an occurrence"; captureNearAction() }
            r < 27 -> { action = "Lançar previsto"; launchPlannedAction() }
            r < 32 -> { action = "Ligar"; linkAction() }
            r < 38 -> { action = "accept"; acceptAction() }
            r < 45 -> { action = "Salvar previsto"; futureAction() }
            r < 49 -> { action = "addManual"; manualAction() }
            r < 54 -> { action = "Lançar sem recado"; withoutNoticeAction() }
            r < 58 -> { action = "close a non-open occurrence"; refusedCloseAction() }
            r < 63 -> { action = "Pular este mês"; skipAction() }
            r < 66 -> { action = "Apagar previsto"; deletePlannedAction() }
            r < 72 -> { action = "deleteTransaction"; deleteTransactionAction() }
            r < 76 -> { action = "edit"; editTransactionAction() }
            r < 82 -> { action = "Editar previsto"; editPlannedAction() }
            r < 84 -> { action = "deleteBudget"; deleteBudgetAction() }
            r < 86 -> { action = "createBudget"; createBudgetAction() }
            r < 88 -> { action = "deleteTag"; deleteTagAction() }
            r < 90 -> { action = "mergeTags"; mergeTagsAction() }
            r < 91 -> { action = "dismiss or restore"; dismissAction() }
            else -> { action = "advance today"; advanceTodayAction() }
        }
    }

    suspend fun captureNotice(pkg: String, merchant: String, cents: Long, date: LocalDate): NoticeRow {
        captures++
        val at = date.atStartOfDayIn(PVINV_TZ).toEpochMilliseconds() + 8 * 3_600_000L + (captures % 600) * 60_000L
        val text = when {
            pkg == PVINV_NU && merchant.isEmpty() -> "Pix de R$ ${pvinvBrl(cents)} da conta 0001 foi realizado com sucesso."
            pkg == PVINV_NU -> "Compra de R$ ${pvinvBrl(cents)} APROVADA em $merchant para o cartão com final 1234."
            merchant.isEmpty() -> "Pix enviado no valor de R$ ${pvinvBrl(cents)}."
            else -> "Compra aprovada de R$ ${pvinvBrl(cents)} em $merchant."
        }
        val label = PVINV_LABEL.getValue(pkg)
        val raw = RawNotice(
            packageName = pkg, appLabel = label, key = "pvinv-$captures", postedAt = at, whenMs = at,
            title = label, text = text, bigText = text,
        )
        assertTrue(capture(db, raw), "capture of notice $captures (a fonte, unique key and content)")
        val row = assertNotNull(db.pending().firstOrNull { it.raw.key == raw.key }, "captured notice $captures is pending")
        assertEquals(Cents(cents), row.parsed.cents, "notice $captures: value read from \"$text\"")
        assertEquals(merchant, row.parsed.merchant, "notice $captures: bank text read from \"$text\"")
        assertEquals(date, row.parsed.date, "notice $captures: date from its time")
        noticeInfo[row.id] = PvInvLink(pkg, merchant)
        log("capture ${row.id.v} $pkg \"$merchant\" $cents $date")
        return row
    }

    suspend fun captureAction() {
        captureNotice(PVINV_LABEL.keys.random(rnd), PVINV_KEY.keys.random(rnd), 500L + rnd.nextLong(90_000), today.pvinvDays(-rnd.nextInt(3)))
    }

    /** A recado around an open occurrence of a linked previsto: mostly its fonte and key, dated up to 13 days off. */
    suspend fun captureNearAction() {
        val rows = readPlanned().filter { planOf(it.planned).link.on }
        if (rows.isEmpty()) {
            captureAction()
            return
        }
        val o = rows.random(rnd)
        val link = planOf(o.planned).link
        val pkg = if (rnd.nextInt(6) == 0) PVINV_LABEL.keys.first { it != link.pkg } else link.pkg
        val key = keyOf(link.text)
        val merchant = if (rnd.nextInt(6) == 0) PVINV_KEY.keys.random(rnd) else PVINV_KEY.entries.filter { it.value == key }.map { it.key }.random(rnd)
        var date = o.date.pvinvDays(rnd.nextInt(-13, 14))
        if (date > today) date = today
        captureNotice(pkg, merchant, 500L + rnd.nextLong(90_000), date)
    }

    /** Lançar previsto (spec §2.13, §7.2; D62): verba, marcadores and name from the previsto; value, bank text, date and time from the recado. */
    suspend fun launchPlannedAction() {
        val prs = readPending().filter { it.planned.occurrence.v != 0L }
        if (prs.isEmpty()) {
            captureNearAction()
            return
        }
        val r = prs.random(rnd)
        val o = r.planned
        val (p, m) = occIds.getValue(o.occurrence)
        val f = TransactionForm(
            budget = o.budget, tags = o.tags, total = r.parsed.cents, merchant = r.parsed.merchant,
            date = r.parsed.date, time = r.parsed.time, caption = o.name, occurrence = o.occurrence,
        )
        val id = db.accept(r.id, f)
        log("Lançar previsto: recado ${r.id.v} as $p month $m ${describe(f)} -> ${id.v}")
        assertTrue(id.v != 0L, "Lançar previsto of a recognized recado is accepted (spec §2.13, §7.2; D62)")
        val tx = db.transaction(id).row
        assertEquals(o.name, tx.caption, "spec §2.12: the lançamento of a previsto is born with its name as legenda")
        assertFalse(tx.corrected, "spec §2.13 Valor diferente: Lançar previsto is not an emenda")
        assertEquals(NoticeStatus.PROCESSED, db.notice(r.id).status, "the recado of a lançamento is processed")
        closeOcc(p, m, id)
        val before = p.link
        // The recado's key is not empty (it was recognized): the link is (re)written from it (Db.accept KDoc).
        p.link = PvInvLink(r.raw.packageName, r.parsed.merchant)
        newTx(id, r.id, f, p, m, if (before != p.link) before else null)
        assertTrue(readPlanned().none { it.occurrence == o.occurrence }, "a launched occurrence leaves A caminho (closed)")
    }

    /** "É um previsto? Ligar" (spec §2.13 Ligar, §7.3; D62): any pending recado, any open occurrence. */
    suspend fun linkAction() {
        val prs = readPending()
        val rows = lastRows
        if (rows.isEmpty() || prs.isEmpty()) {
            captureAction()
            return
        }
        val o = rows.random(rnd)
        val r = prs.random(rnd)
        val (p, m) = occIds.getValue(o.occurrence)
        val f = TransactionForm(
            budget = if (rnd.nextInt(5) == 0) budgets.keys.random(rnd) else o.budget,
            tags = if (rnd.nextInt(5) == 0) randomTags() else o.tags,
            total = r.parsed.cents, merchant = r.parsed.merchant, date = r.parsed.date, time = r.parsed.time,
            caption = o.name, occurrence = o.occurrence,
        )
        val id = db.accept(r.id, f)
        log("Ligar: recado ${r.id.v} ${r.raw.packageName} \"${r.parsed.merchant}\" to $p month $m ${describe(f)} -> ${id.v}")
        assertTrue(id.v != 0L, "accept with an open occurrence is accepted (TransactionForm.occurrence KDoc)")
        closeOcc(p, m, id)
        val before = p.link
        // R2: an empty key writes no link; otherwise the recado's fonte and bank text replace the link.
        if (keyOf(r.parsed.merchant).isNotEmpty()) p.link = PvInvLink(r.raw.packageName, r.parsed.merchant)
        newTx(id, r.id, f, p, m, if (before != p.link) before else null)
    }

    suspend fun acceptAction() {
        val prs = readPending()
        val r = if (prs.isEmpty() || rnd.nextInt(12) == 0) null else prs.random(rnd)
        acceptWith(r, monthly = rnd.nextInt(3) == 0, refuse = rnd.nextInt(10) == 0)
    }

    /** Lançar recado, "Só uma vez" or "Todo mês" (D64): [r] null = a notice that is not pending (refused). */
    suspend fun acceptWith(r: NoticeRow?, monthly: Boolean, refuse: Boolean) {
        val notice = r?.id ?: (txs.values.map { it.notice }.filter { it.v != 0L } + PVINV_UNKNOWN_NOTICE).random(rnd)
        val merchant = r?.parsed?.merchant ?: "VIVO FIBRA"
        val kind = if (refuse) rnd.nextInt(3) else -1
        var caption = if (kind == 2) PVINV_LONG else if (rnd.nextBoolean()) "" else PVINV_NAMES.random(rnd)
        // Ambiguity avoided: a previsto with an empty name (no legenda, no bank text).
        if (monthly && kind != 2 && pvinvClean(caption).isEmpty() && merchant.isEmpty()) caption = PVINV_NAMES.random(rnd)
        val f = TransactionForm(
            budget = if (kind == 0) pickGoneBudget() else budgets.keys.random(rnd),
            tags = if (kind == 1) listOf(" ", "") else randomTags(),
            total = Cents(if (monthly) uniqueCents() else r?.parsed?.cents?.v ?: 1_000L),
            merchant = merchant,
            date = r?.parsed?.date ?: today,
            time = r?.parsed?.time ?: LocalTime(10, 0),
            caption = caption,
            repeats = if (monthly) Repeats.MONTHLY else Repeats.ONCE,
        )
        val expect = r != null && formOk(f)
        val before = snap()
        val id = db.accept(notice, f)
        log("accept notice=${notice.v} pending=${r != null} ${describe(f)} -> ${id.v}")
        if (!expect) {
            assertEquals(TransactionId(0), id, "accept refused (Db.accept KDoc)")
            assertUnchanged(before, "refused accept")
            return
        }
        assertTrue(id.v != 0L, "accept of a valid form")
        newTx(id, r.id, f, null, NO_MONTH, null)
        if (monthly) {
            // D64: named after the line, same verba/marcadores/value, day of the date, first occurrence next
            // month, linked from the recado's fonte and bank text unless its key is empty (R2).
            val link = if (keyOf(r.parsed.merchant).isEmpty()) PvInvLink() else PvInvLink(r.raw.packageName, r.parsed.merchant)
            val p = PvInvPlan(
                "Todo mês do recado ${r.id.v}", planName(f), f.budget, pvinvTags(f.tags), f.total.v, f.date.day,
                Repeats.MONTHLY, f.date.yearMonth.plusMonth(), link,
            )
            plans.add(p)
            txs.getValue(id).started = p
            log("  expects $p: day ${p.day} from ${p.first}, ${p.cents}, link ${p.link}")
        }
    }

    /** Salvar previsto (spec §2.13 Criar, §7.3; D64): addManual with a date ahead writes only a previsto. */
    suspend fun futureAction(refuse: Boolean = rnd.nextInt(8) == 0) {
        val kind = if (refuse) rnd.nextInt(4) else -1
        val date = today.pvinvDays(1 + rnd.nextInt(45))
        val repeats = if (rnd.nextBoolean()) Repeats.MONTHLY else Repeats.ONCE
        val f = TransactionForm(
            budget = if (kind == 0) pickGoneBudget() else budgets.keys.random(rnd),
            tags = if (kind == 1) emptyList() else randomTags(),
            total = Cents(if (kind == 3) 0L else uniqueCents()),
            merchant = PVINV_KEY.keys.filter { it.isNotEmpty() }.random(rnd),
            date = date,
            time = LocalTime(9, 30),
            caption = if (kind == 2) PVINV_LONG else if (rnd.nextInt(4) == 0) "" else PVINV_NAMES.random(rnd),
            repeats = repeats,
        )
        val expect = formOk(f) && planName(f).length <= MAX_CAPTION
        val before = snap()
        val saved = db.addManual(f, today)
        log("Salvar previsto ${describe(f)} -> $saved")
        if (!expect) {
            assertEquals(Saved(), saved, "refused Salvar previsto (Db.addManual KDoc)")
            assertUnchanged(before, "refused Salvar previsto")
            return
        }
        assertEquals(TransactionId(0), saved.transaction, "spec §2.13 Criar: a date ahead is only a previsto, no lançamento")
        assertTrue(saved.planned.v != 0L, "Salvar previsto returns the new previsto")
        assertTrue(plans.none { it.id == saved.planned }, "a new previsto id")
        val p = PvInvPlan("Salvar previsto $date", planName(f), f.budget, pvinvTags(f.tags), f.total.v, date.day, repeats, date.yearMonth, PvInvLink())
        p.id = saved.planned
        plans.add(p)
        assertEquals(before.txs, db.transactions(), "Salvar previsto writes no lançamento (spec §2.13: não conta)")
    }

    /** Lançamento manual of today or the past (D64): "Todo mês" also creates the previsto, first occurrence next month, unlinked. */
    suspend fun manualAction(refuse: Boolean = rnd.nextInt(10) == 0, monthly: Boolean = rnd.nextInt(3) == 0) {
        val kind = if (refuse) rnd.nextInt(3) else -1
        val date = today.pvinvDays(-rnd.nextInt(41))
        val merchant = PVINV_KEY.keys.random(rnd)
        var caption = if (kind == 2) PVINV_LONG else if (rnd.nextBoolean()) "" else PVINV_NAMES.random(rnd)
        if (monthly && kind != 2 && pvinvClean(caption).isEmpty() && merchant.isEmpty()) caption = PVINV_NAMES.random(rnd)
        val f = TransactionForm(
            budget = if (kind == 0) pickGoneBudget() else budgets.keys.random(rnd),
            tags = if (kind == 1) emptyList() else randomTags(),
            total = Cents(if (monthly) uniqueCents() else 500L + rnd.nextLong(60_000)),
            merchant = merchant,
            date = date,
            time = LocalTime(rnd.nextInt(24), rnd.nextInt(60)),
            caption = caption,
            repeats = if (monthly) Repeats.MONTHLY else Repeats.ONCE,
        )
        val expect = formOk(f)
        val before = snap()
        val saved = db.addManual(f, today)
        log("addManual ${describe(f)} -> $saved")
        if (!expect) {
            assertEquals(Saved(), saved, "refused addManual (Db.addManual KDoc)")
            assertUnchanged(before, "refused addManual")
            return
        }
        assertTrue(saved.transaction.v != 0L, "addManual of today or the past writes the lançamento")
        if (monthly) assertTrue(saved.planned.v != 0L, "addManual with Todo mês also returns the previsto (Saved KDoc)")
        else assertEquals(PlannedId(0), saved.planned, "addManual Só uma vez writes no previsto")
        newTx(saved.transaction, NoticeId(0), f, null, NO_MONTH, null)
        if (monthly) {
            assertTrue(plans.none { it.id == saved.planned }, "a new previsto id")
            val p = PvInvPlan(
                "Todo mês do manual ${saved.transaction.v}", planName(f), f.budget, pvinvTags(f.tags), f.total.v, date.day,
                Repeats.MONTHLY, date.yearMonth.plusMonth(), PvInvLink(),
            )
            p.id = saved.planned
            plans.add(p)
            txs.getValue(saved.transaction).started = p
        }
    }

    /** Lançar sem recado (R6) or paid before the day (R6b): manual with the occurrence; closes it, writes no link. */
    suspend fun withoutNoticeAction() {
        val rows = readPlanned()
        if (rows.isEmpty()) {
            futureAction()
            return
        }
        val o = rows.random(rnd)
        val (p, m) = occIds.getValue(o.occurrence)
        val date = if (o.date <= today) o.date else today
        val f = TransactionForm(
            budget = o.budget,
            tags = o.tags,
            total = if (rnd.nextInt(3) == 0) Cents(500L + rnd.nextLong(60_000)) else o.cents,
            merchant = if (rnd.nextBoolean()) "" else PVINV_KEY.keys.random(rnd),
            date = date,
            time = LocalTime(12, 0),
            caption = o.name,
            occurrence = o.occurrence,
        )
        val saved = db.addManual(f, today)
        log("Lançar sem recado: $p month $m ${describe(f)} -> $saved")
        assertTrue(saved.transaction.v != 0L, "a manual lançamento closing an open occurrence is accepted (R6, R6b)")
        assertEquals(PlannedId(0), saved.planned, "Lançar sem recado creates no previsto")
        closeOcc(p, m, saved.transaction)
        newTx(saved.transaction, NoticeId(0), f, p, m, null)
        assertTrue(readPlanned().none { it.occurrence == o.occurrence }, "a closed occurrence leaves A caminho")
    }

    /** TransactionForm.occurrence KDoc: refused when the occurrence is not open (skipped, closed, deleted, unknown). */
    suspend fun refusedCloseAction() {
        val prs = readPending()
        val occ = notOpenOccurrences().random(rnd)
        val before = snap()
        if (prs.isNotEmpty() && rnd.nextBoolean()) {
            val r = prs.random(rnd)
            val f = TransactionForm(
                budget = budgets.keys.random(rnd), tags = randomTags(), total = r.parsed.cents, merchant = r.parsed.merchant,
                date = r.parsed.date, time = r.parsed.time, occurrence = occ,
            )
            val id = db.accept(r.id, f)
            log("accept recado ${r.id.v} with the non-open occurrence ${occ.v} -> ${id.v}")
            assertEquals(TransactionId(0), id, "accept with an occurrence that is not open is refused (closed by exactly one lançamento, or skipped)")
        } else {
            val f = TransactionForm(
                budget = budgets.keys.random(rnd), tags = randomTags(), total = Cents(1_500), merchant = "",
                date = today, time = LocalTime(11, 0), occurrence = occ,
            )
            val saved = db.addManual(f, today)
            log("addManual with the non-open occurrence ${occ.v} -> $saved")
            assertEquals(Saved(), saved, "addManual with an occurrence that is not open is refused")
        }
        assertUnchanged(before, "refused close of occurrence ${occ.v}")
    }

    /** Pular este mês (P10, Db.skipOccurrence KDoc). */
    suspend fun skipAction() {
        val rows = readPlanned()
        val open = rows.isNotEmpty() && rnd.nextInt(5) != 0
        val id = if (open) rows.random(rnd).occurrence else notOpenOccurrences().random(rnd)
        val before = snap()
        val ok = db.skipOccurrence(id)
        log("skipOccurrence ${id.v} open=$open -> $ok")
        assertEquals(open, ok, "skipOccurrence: true only for an open occurrence")
        if (!ok) {
            assertUnchanged(before, "refused skipOccurrence")
            return
        }
        val (p, m) = occIds.getValue(id)
        p.occs.getValue(m).state = PvInvState.SKIPPED
        log("  $p month $m skipped")
        assertTrue(readPlanned().none { it.occurrence == id }, "Pular este mês: the occurrence won't come back")
        assertEquals(before.txs, db.transactions(), "Pular este mês writes no lançamento")
    }

    suspend fun deletePlannedAction() {
        readPlanned()
        val known = plans.filter { it.id.v != 0L }
        val p = if (known.isEmpty() || rnd.nextInt(7) == 0) null else known.random(rnd)
        val id = p?.id ?: (deletedPlans.toList() + PVINV_UNKNOWN_PLANNED).random(rnd)
        val before = snap()
        db.deletePlanned(id)
        log("deletePlanned ${id.v} known=${p != null}")
        if (p == null) {
            assertUnchanged(before, "deletePlanned of an unknown previsto: nothing changes")
            return
        }
        dropPlan(p)
        assertEquals(before.txs.map { it.id }, db.transactions().map { it.id }, "Apagar previsto: its lançamentos stay (Db.deletePlanned KDoc)")
        assertTrue(readPlanned().none { it.planned == id }, "Apagar previsto: every occurrence goes")
    }

    suspend fun deleteTransactionAction() {
        val t = if (txs.isEmpty() || rnd.nextInt(10) == 0) null else txs.values.random(rnd)
        val id = t?.id ?: (goneTx + PVINV_UNKNOWN_TX).random(rnd)
        val before = snap()
        db.deleteTransaction(id)
        log("deleteTransaction ${id.v} known=${t != null} origin=${t?.plan}")
        if (t == null) {
            assertUnchanged(before, "deleteTransaction of an unknown id")
            return
        }
        val reopened = dropTx(t)
        if (t.notice.v != 0L) assertEquals(NoticeStatus.PENDING, db.notice(t.notice).status, "spec §2.8: its recado is back in the Pauta")
        if (reopened != null && due(reopened)) {
            assertTrue(
                readPlanned().any { it.occurrence == reopened },
                "S1: deleting the lançamento of a previsto reopens occurrence ${reopened.v} (the same one) in A caminho",
            )
        }
    }

    /** Edição (Db.edit KDoc): the origin never changes; "Todo mês" creates a previsto linked from the lançamento's recado. */
    suspend fun editTransactionAction() {
        if (txs.isEmpty()) {
            manualAction()
            return
        }
        val t = txs.values.random(rnd)
        // D65 (2026-10-22, the user's decision after this round): "Repete" acts on the lançamento's own previsto (the
        // one it created, else the one it closed an occurrence of) and opens as that one is. Kept as it opens, but
        // now and then Todo mês → Só uma vez, which deletes it. "Só uma vez" → "Todo mês" is avoided (months between).
        val own = t.started?.takeIf { it in plans } ?: t.plan?.takeIf { it in plans }
        val drop = own != null && own.repeats == Repeats.MONTHLY && rnd.nextInt(6) == 0
        val monthly = if (own != null) own.repeats == Repeats.MONTHLY && !drop
            else rnd.nextInt(3) == 0 && (t.caption.isNotEmpty() || t.merchant.isNotEmpty())
        val kind = if (rnd.nextInt(10) == 0) rnd.nextInt(2) else -1
        val f = TransactionForm(
            budget = when {
                kind == 0 -> pickGoneBudget()
                rnd.nextInt(3) == 0 -> budgets.keys.random(rnd)
                else -> t.budget
            },
            tags = when {
                kind == 1 -> emptyList()
                rnd.nextInt(3) == 0 -> randomTags()
                else -> t.tags
            },
            total = Cents(if (monthly) uniqueCents() else if (rnd.nextBoolean()) t.cents else 500L + rnd.nextLong(60_000)),
            merchant = t.merchant,
            date = t.date,
            time = t.time,
            caption = t.caption,
            repeats = if (monthly) Repeats.MONTHLY else Repeats.ONCE,
        )
        val expect = formOk(f)
        val before = snap()
        val ok = db.edit(t.id, f)
        log("edit ${t.id.v} origin=${t.plan} ${describe(f)} -> $ok")
        assertEquals(expect, ok, "edit accepted/refused (same rules as accept)")
        if (!ok) {
            assertUnchanged(before, "refused edit")
            return
        }
        t.budget = f.budget
        t.tags = pvinvTags(f.tags)
        t.cents = f.total.v
        if (drop) dropPlan(own!!)
        if (monthly && own == null) {
            val info = if (t.notice.v == 0L) PvInvLink() else noticeInfo.getValue(t.notice)
            val link = if (info.on && keyOf(info.text).isNotEmpty()) info else PvInvLink()
            val p = PvInvPlan(
                "Todo mês da edição de ${t.id.v}", planName(f), f.budget, pvinvTags(f.tags), f.total.v, t.date.day,
                Repeats.MONTHLY, t.date.yearMonth.plusMonth(), link,
            )
            plans.add(p)
            t.started = p
            log("  expects $p: day ${p.day} from ${p.first}, ${p.cents}, link ${p.link}")
        }
    }

    /** Editar previsto (D64): addManual with planned ≠ 0 updates the rule only; the open occurrences follow the new day. */
    suspend fun editPlannedAction() {
        readPlanned()
        val known = plans.filter { it.id.v != 0L }
        val p = if (known.isEmpty() || rnd.nextInt(10) == 0) null else known.random(rnd)
        val id = p?.id ?: (deletedPlans.toList() + PVINV_UNKNOWN_PLANNED).random(rnd)
        val kind = if (rnd.nextInt(9) == 0) rnd.nextInt(4) else -1
        val day = if (rnd.nextInt(3) == 0) 28 + rnd.nextInt(4) else 1 + rnd.nextInt(28)
        val repeats = when {
            p == null -> Repeats.ONCE
            p.repeats == Repeats.MONTHLY -> if (rnd.nextInt(5) == 0) Repeats.ONCE else Repeats.MONTHLY
            // Ambiguity avoided: "Só uma vez" → "Todo mês" with the first month in the past (would the months between be created?).
            p.first >= today.yearMonth && rnd.nextInt(3) == 0 -> Repeats.MONTHLY
            else -> Repeats.ONCE
        }
        val byMerchant = kind != 2 && rnd.nextInt(5) == 0
        val name = PVINV_NAMES.random(rnd)
        val f = TransactionForm(
            budget = when {
                kind == 0 -> pickGoneBudget()
                p == null || rnd.nextBoolean() -> budgets.keys.random(rnd)
                else -> p.budget
            },
            tags = when {
                kind == 1 -> listOf("  ")
                p == null || rnd.nextBoolean() -> randomTags()
                else -> p.tags
            },
            total = Cents(if (kind == 3) 0L else uniqueCents()),
            merchant = if (byMerchant) name else "",
            // Only the day of the date counts (TransactionForm.planned KDoc); a past date keeps clear of "Salvar previsto".
            date = LocalDate(2026, 8, day),
            time = LocalTime(8, 0),
            caption = if (kind == 2) PVINV_LONG else if (byMerchant) "" else name,
            repeats = repeats,
            planned = id,
        )
        val newName = planName(f)
        val expect = p != null && formOk(f) && newName.isNotEmpty() && newName.length <= MAX_CAPTION
        val before = snap()
        val saved = db.addManual(f, today)
        log("Editar previsto ${id.v} known=${p != null} ${describe(f)} -> $saved")
        if (!expect) {
            assertEquals(Saved(), saved, "refused Editar previsto (Db.addManual KDoc)")
            assertUnchanged(before, "refused Editar previsto")
            return
        }
        assertEquals(Saved(planned = id), saved, "Editar previsto returns Saved(planned) and writes no lançamento (D64)")
        p.name = newName
        p.budget = f.budget
        p.tags = pvinvTags(f.tags)
        p.cents = f.total.v
        p.day = day
        p.repeats = repeats
        if (repeats == Repeats.MONTHLY) p.everMonthly = true
        assertEquals(before.txs, db.transactions(), "Editar previsto changes no lançamento")
    }

    suspend fun deleteBudgetAction() {
        val known = budgets.isNotEmpty() && rnd.nextInt(6) != 0
        val id = if (known) budgets.keys.random(rnd) else pickGoneBudget()
        val before = snap()
        db.deleteBudget(id)
        log("deleteBudget ${id.v} known=$known")
        if (!known) {
            assertUnchanged(before, "deleteBudget of an unknown id")
            return
        }
        budgets.remove(id)
        goneBudgets.add(id)
        // D59: apagar verba apaga os previstos dela (their lançamentos in other verbas stay); then its lançamentos.
        for (p in plans.filter { it.budget == id }) dropPlan(p)
        val reopened = txs.values.filter { it.budget == id }.mapNotNull { dropTx(it) }
        if (reopened.isNotEmpty()) {
            val rows = readPlanned()
            for (o in reopened.filter(::due)) assertTrue(rows.any { it.occurrence == o }, "S1: the lançamento deleted with its verba reopens occurrence ${o.v}")
        }
        if (budgets.size < 2) createBudgetAction()
    }

    suspend fun createBudgetAction() {
        budgetCounter++
        val name = "Verba $budgetCounter"
        val id = db.createBudget(name, Cents(50_000L + rnd.nextLong(400_000)), PVINV_BUDGET_DAY)
        log("createBudget \"$name\" -> ${id.v}")
        assertTrue(id.v != 0L, "createBudget of a new name")
        budgets[id] = name
        db.ensureMonths(cur().pvinvShift(2))
    }

    /** Db.deleteTag KDoc: lançamentos and previstos left without marcadores are deleted (the previsto like deletePlanned). */
    suspend fun deleteTagAction() {
        val all = db.tags()
        val t = if (all.isEmpty() || rnd.nextInt(10) == 0) null else all.random(rnd)
        val before = snap()
        db.deleteTag(t?.id ?: PVINV_UNKNOWN_TAG)
        log("deleteTag ${t?.id?.v ?: PVINV_UNKNOWN_TAG.v} \"${t?.name}\"")
        if (t == null) {
            assertUnchanged(before, "deleteTag of an unknown tag")
            return
        }
        for (p in plans.toList()) {
            p.tags = p.tags - t.name
            if (p.tags.isEmpty()) dropPlan(p)
        }
        val reopened = ArrayList<OccurrenceId>()
        for (x in txs.values.toList()) {
            x.tags = x.tags - t.name
            if (x.tags.isEmpty()) dropTx(x)?.let { reopened.add(it) }
        }
        if (reopened.isNotEmpty()) {
            val rows = readPlanned()
            for (o in reopened.filter(::due)) assertTrue(rows.any { it.occurrence == o }, "S1: the lançamento deleted with its last marcador reopens occurrence ${o.v}")
        }
    }

    /** Db.mergeTags KDoc: every link moves, the previstos' too. */
    suspend fun mergeTagsAction() {
        val all = db.tags()
        if (all.size < 2) {
            captureAction()
            return
        }
        val from = all.random(rnd)
        val into = all.filter { it.id != from.id }.random(rnd)
        assertTrue(db.mergeTags(from.id, into.id), "mergeTags of two different tags")
        log("mergeTags \"${from.name}\" into \"${into.name}\"")
        fun merged(tags: List<String>) = if (from.name in tags) (tags - from.name + into.name).distinct().sorted() else tags
        for (p in plans) p.tags = merged(p.tags)
        for (x in txs.values) x.tags = merged(x.tags)
    }

    suspend fun dismissAction() {
        if (rnd.nextBoolean()) {
            val prs = readPending()
            if (prs.isEmpty()) return
            val r = prs.random(rnd)
            assertTrue(db.dismiss(r.id), "dismiss a pending recado")
            log("dismiss ${r.id.v} (recognized as ${r.planned.occurrence.v})")
        } else {
            val d = db.dismissed()
            if (d.isEmpty()) return
            val r = d.random(rnd)
            assertTrue(db.restore(r.id), "restore a dismissed recado")
            log("restore ${r.id.v}")
        }
    }

    suspend fun advanceTodayAction() {
        val n = 1 + rnd.nextInt(14)
        today = today.pvinvDays(n)
        log("today += $n")
        db.ensureMonths(cur().pvinvShift(2))
    }

    // ---- invariants ----

    suspend fun checkAll() {
        val was = action
        action = "invariants after $was"
        readPending()
        checkTransactions()
        checkSpent()
        checkOtherReads()
        action = was
    }

    suspend fun checkTransactions() {
        val rows = db.transactions()
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "transactions() lists each lançamento once")
        assertEquals(
            txs.keys.toSet(), rows.map { it.id }.toSet(),
            "transactions() = the lançamentos made and not deleted; a previsto is not a lançamento (spec §2.13)",
        )
        for (r in rows) {
            val t = txs.getValue(r.id)
            assertEquals(t.budget, r.budget, "lançamento ${r.id.v}: verba")
            assertEquals(Cents(t.cents), r.total, "lançamento ${r.id.v}: value (Lançar previsto: the recado's, P7)")
            assertEquals(t.date, r.date, "lançamento ${r.id.v}: date")
            assertEquals(t.tags, r.tags, "lançamento ${r.id.v}: marcadores")
            assertEquals(t.notice, r.notice, "lançamento ${r.id.v}: recado")
            assertEquals(1, r.installments, "lançamento ${r.id.v}: à vista")
        }
    }

    fun spent(b: BudgetId, from: LocalDate, to: LocalDate) =
        txs.values.filter { it.budget == b && it.date >= from && it.date <= to }.sumOf { it.cents }

    /** spec §3 Gasto do mês, §2.13: the lançamentos alone; previstos never count. */
    suspend fun checkSpent() {
        val cur = cur()
        for (k in -1..1) {
            val m = cur.pvinvShift(k)
            val (ws, we) = pvinvWindow(m, startDay)
            val ov = db.budgetsOverview(m, today, BudgetOrder.MANUAL)
            assertEquals(budgets.keys.toSet(), ov.map { it.id }.toSet(), "budgetsOverview($m) lists every verba (rows filled by ensureMonths)")
            for (s in ov) {
                val exp = spent(s.id, ws, we)
                assertEquals(exp, s.month.spent.v, "budgetsOverview($m) verba ${s.id.v}: spent = Σ of its lançamentos in $ws..$we; a previsto never counts")
                assertEquals(s.month.limit.v - exp, s.month.remaining.v, "budgetsOverview($m) verba ${s.id.v}: margem = cota − gasto")
                assertEquals(exp > s.month.limit.v, s.month.status == PaceStatus.OVERRUN, "budgetsOverview($m) verba ${s.id.v}: Fora da margem iff gasto > cota (§5)")
            }
        }
        if (budgets.isNotEmpty()) {
            val b = budgets.keys.random(rnd)
            val m = cur.pvinvShift(-rnd.nextInt(2))
            val (ws, we) = pvinvWindow(m, startDay)
            val d = db.budgetDetail(b, m, today)
            assertEquals(
                txs.values.filter { it.budget == b && it.date >= ws && it.date <= we }.map { it.id }.toSet(), d.lines.map { it.row.id }.toSet(),
                "budgetDetail(${b.v}, $m): one line per lançamento of the month, none for a previsto",
            )
            assertEquals(spent(b, ws, we), d.month.spent.v, "budgetDetail(${b.v}, $m): spent = Σ of its lançamentos")
            assertEquals(d.month.spent.v, d.lines.filter { !it.row.refunded }.sumOf { it.cents.v }, "budgetDetail(${b.v}, $m): Σ lines = spent")
        }
        val from = cur.pvinvShift(-3)
        val to = cur.pvinvShift(2)
        val hist = db.history(from, to)
        val pairs = budgets.keys.flatMap { b -> pvinvMonths(from, to).map { b to it } }.toSet()
        assertEquals(pairs, hist.map { it.budget to it.month }.toSet(), "history($from, $to): every verba in every month with a limit row")
        for (h in hist) {
            val (ws, we) = pvinvWindow(h.month, startDay)
            assertEquals(spent(h.budget, ws, we), h.spent.v, "history verba ${h.budget.v} ${h.month}: spent = Σ of its lançamentos; a previsto never counts")
        }
        val (cs, ce) = pvinvWindow(cur, startDay)
        checkLeitura(cs, ce, "the current month $cur")
        checkLeitura(PVINV_FIRST_DAY, today.pvinvDays(400), "every day up to a year ahead")
        checkLeitura(today.pvinvDays(1), today.pvinvDays(400), "the days after today, where only previstos are")
    }

    /** spec §7.5: ranking and calendar of the lançamentos alone (à vista: each counts on its date). */
    suspend fun checkLeitura(from: LocalDate, to: LocalDate, what: String) {
        val inRange = txs.values.filter { it.date >= from && it.date <= to }
        val byDay = inRange.groupBy { it.date }.mapValues { (_, l) -> Cents(l.sumOf { it.cents }) }
        assertEquals(byDay, db.spentByDay(from, to), "spentByDay over $what ($from..$to): the lançamentos alone; a previsto never counts")

        val tagExp = HashMap<String, Pair<Long, Int>>()
        for (t in inRange) {
            for (g in t.tags) {
                val (s, c) = tagExp[g] ?: (0L to 0)
                tagExp[g] = (s + t.cents) to (c + 1)
            }
        }
        val tr = db.tagRanking(from, to)
        assertEquals(tr.size, tr.map { it.name }.toSet().size, "tagRanking over $what: one row per marcador")
        assertEquals(tagExp, tr.associate { it.name to (it.total.v to it.count) }, "tagRanking over $what ($from..$to): the lançamentos alone")
        assertTrue(tr.zipWithNext().all { (a, b) -> a.total.v >= b.total.v }, "tagRanking over $what: by value")

        val merchantExp = HashMap<String, Pair<Long, Int>>()
        for (t in inRange) {
            val k = keyOf(t.merchant)
            val (s, c) = merchantExp[k] ?: (0L to 0)
            merchantExp[k] = (s + t.cents) to (c + 1)
        }
        val mr = db.merchantRanking(from, to)
        val merchantGot = HashMap<String, Pair<Long, Int>>()
        for (row in mr) {
            val k = keyOf(row.name)
            assertFalse(k in merchantGot, "merchantRanking over $what: one row per key (no alias is ever set): $mr")
            merchantGot[k] = row.total.v to row.count
        }
        assertEquals(
            merchantExp.filterKeys { it.isNotEmpty() }, merchantGot.filterKeys { it.isNotEmpty() },
            "merchantRanking over $what ($from..$to): the lançamentos alone, grouped by key",
        )
        // Ambiguity: whether lançamentos without bank text get a row; when they do, it has their sum.
        if ("" in merchantGot) assertEquals(merchantExp[""], merchantGot[""], "merchantRanking over $what: the row without bank text")
        assertTrue(mr.zipWithNext().all { (a, b) -> a.total.v >= b.total.v }, "merchantRanking over $what: by value")
    }

    /** NoticeRow.planned KDoc: zero in every read but pending. */
    suspend fun checkOtherReads() {
        for (n in db.dismissed()) assertEquals(Planned(), n.planned, "dismissed(): recado ${n.id.v} has no previsto (only pending reads recognize)")
        if (noticeInfo.isNotEmpty()) {
            val n = noticeInfo.keys.random(rnd)
            assertEquals(Planned(), db.notice(n).planned, "notice(${n.v}) has no previsto (only pending reads recognize)")
        }
        val withNotice = txs.values.filter { it.notice.v != 0L }
        if (withNotice.isNotEmpty()) {
            val t = withNotice.random(rnd)
            assertEquals(Planned(), db.transaction(t.id).notice.planned, "transaction(${t.id.v}).notice has no previsto")
        }
    }
}

class PrevistoInvariantsTest {

    private fun randomRun(seed: Int, startDay: Int, steps: Int = 160) = runTest(timeout = 240.seconds) {
        withPrevistoInvariantsDb { db -> PrevistoInvariantRun(db, seed, startDay).run(steps) }
    }

    @Test fun `random previsto actions keep the invariants with seed 7 and month start day 1`() = randomRun(7, 1)

    @Test fun `random previsto actions keep the invariants with seed 19 and month start day 31`() = randomRun(19, 31)

    @Test fun `random previsto actions keep the invariants with seed 64 and month start day 10`() = randomRun(64, 10)

    @Test fun `random previsto actions keep the invariants with seed 2026 and month start day 29`() = randomRun(2026, 29)

    @Test fun `random previsto actions keep the invariants with seed 4242 and month start day 15`() = randomRun(4242, 15)
}
