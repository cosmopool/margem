package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Block 4 (ciclo do recado) fuzz: hostile forms on accept / addManual / edit, hostile id pairs on
// registerRefund (both forms) / undoRefund / previewRefund, and transactions(today) at extreme days.
// Fixed seeds, bounded iterations, a real SQLite file per Db, a wall-clock timeout. Expected values
// come from the spec (§2, §3, §6, §7.3, §7.4), the architecture (D7, D28, §5) and the KDoc of Db.kt /
// Types.kt, never from the implementation. Every failure message carries the seed, the iteration and
// the input.

private val RF_TODAY = LocalDate(2026, 10, 14)
private val RF_TODAY_MONTH = YearMonth(2026, 10) // month start day 1: the calendar month (spec §3)
private const val RF_PKG = "com.banco.recado"

/** A whole minute (epoch ms): a time read from the notice's own time then carries no seconds. */
private const val RF_NOW = 1_760_000_040_000L

/**
 * The largest total a form accepts: "the largest value parseBrl can return: 15 integer digits and
 * 2 decimals. Forms and limits are capped to it" (KDoc of MAX_CENTS in Extract.kt).
 */
private const val RF_MAX = 99_999_999_999_999_999L

/**
 * Dates the contract doesn't refuse but that a text-ISO store or LocalDate arithmetic may not
 * handle (year ≤ 0 or ≥ 10000): refusing them is allowed, crashing is not, and an accepted one must
 * read back as given. Built defensively: a platform whose LocalDate can't hold one just skips it.
 */
private val RF_BEYOND: List<LocalDate> = listOf(
    Triple(0, 1, 1), Triple(-1, 12, 31), Triple(-10000, 2, 29), Triple(10000, 1, 1),
    Triple(99999, 12, 31), Triple(999_999, 12, 31), Triple(-999_999, 1, 1),
).mapNotNull { (y, m, d) -> runCatching { LocalDate(y, m, d) }.getOrNull() }

/** Purchase dates the KDoc has no reason to refuse: years 1..9999. */
private fun rfSaneYear(d: LocalDate) = d.year in 1..9999

/** Real threads under a wall-clock timeout (withTimeout inside Dispatchers.Default), so a deadlock fails. */
private fun rfRealTime(block: suspend () -> Unit) = runTest(timeout = 120.seconds) {
    withContext(Dispatchers.Default) { withTimeout(90.seconds) { block() } }
}

/** A fresh Db on a temporary file, closed and deleted (with -wal/-shm) afterwards. */
private suspend fun rfWithDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-rfuzz-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path)
        try {
            block(db)
        } finally {
            db.close()
        }
    } finally {
        for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
    }
}

/** Runs one call of the API; any exception (but cancellation/timeout) fails with [where]. */
private suspend fun <T> rfNoThrow(where: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fail("$where threw $e", e)
}

/** Escaped, truncated rendering of [s] for failure messages. */
private fun rfShow(s: String): String = buildString {
    append('"')
    for (c in s.take(80)) {
        if (c.code in 0x20..0x7E && c != '"' && c != '\\') append(c)
        else append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }
    append('"')
    if (s.length > 80) append("…(${s.length} chars)")
}

private fun rfShowForm(f: TransactionForm) =
    "TransactionForm(budget=${f.budget.v}, tags=${f.tags.map(::rfShow)}, total=${f.total.v}, installments=${f.installments}, " +
        "merchant=${rfShow(f.merchant)}, date=${f.date}, time=${f.time}, alias=${rfShow(f.alias)})"

/**
 * A text as the Db stores and reads it back (D28): U+FEFF, U+FFFE, U+FFFF and lone surrogates
 * become U+FFFD; everything else is exact.
 */
private fun rfStored(s: String): String = buildString {
    for (i in s.indices) {
        val c = s[i]
        val lone = (c.isHighSurrogate() && (i + 1 >= s.length || !s[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !s[i - 1].isHighSurrogate()))
        append(if (c == '﻿' || c == '￾' || c == '￿' || lone) '�' else c)
    }
}

/** TransactionForm.tags: "trimmed, lowercased, blanks and repeats dropped", read back as stored (D28). */
private fun rfTags(tags: List<String>): List<String> =
    tags.map { rfStored(it.trim().lowercase()) }.filter { it.isNotBlank() }.distinct()

/** TransactionForm.merchant is "trimmed": the Db may store it as given or trimmed (see report). */
private fun rfMerchantReadings(m: String) = setOf(rfStored(m), rfStored(m.trim()))

/** What a non-blank alias may read back as: as given or trimmed (the KDoc doesn't say). */
private fun rfAliasReadings(a: String) = setOf(rfStored(a), rfStored(a.trim()))

/** The form rules of Db.accept: an existing budget, ≥ 1 tag after cleaning, total in 1..max, 1..400 installments, dates in range. */
private fun rfValid(f: TransactionForm, live: List<BudgetId>) =
    f.budget in live && rfTags(f.tags).isNotEmpty() && f.total.v in 1..RF_MAX && f.installments in 1..400 && rfInRange(f.date, f.installments)

/**
 * KDoc of Db.accept (decided in the block 4 triage): every installment dated in 0001-01-01..9999-12-31.
 * Installment k falls in the purchase's calendar month + (k − 1) — D7 only moves it inside that month's
 * budget window, never across a calendar month — so the last one is in range iff that month is.
 */
private fun rfInRange(date: LocalDate, n: Int): Boolean {
    if (n !in 1..400 || date.year < 1) return false
    return rfMonthPlus(YearMonth(date.year, date.month), n - 1) <= YearMonth(9999, 12)
}

private fun rfMonthPlus(m: YearMonth, n: Int): YearMonth {
    val idx = m.year.toLong() * 12 + m.month.ordinal + n
    return YearMonth(idx.floorDiv(12L).toInt(), idx.mod(12L).toInt() + 1)
}

// ---- hostile text ----

/** One hostile piece: lone surrogates, the D28 characters, emoji, combining marks, RTL, controls, spaces, SQL. */
private fun rfPiece(rnd: Random): String = when (rnd.nextInt(16)) {
    0 -> Char(rnd.nextInt(0xD800, 0xDC00)).toString() // lone high surrogate (unless a low one follows)
    1 -> Char(rnd.nextInt(0xDC00, 0xE000)).toString() // lone low surrogate
    2 -> listOf("﻿", "￾", "￿", "�").random(rnd)
    3 -> listOf(
        "😀", "👩‍👩‍👧", "🇧🇷", "❤️",
    ).random(rnd)
    4 -> "e" + Char(rnd.nextInt(0x300, 0x370)) + Char(rnd.nextInt(0x300, 0x370))
    5 -> listOf("‮", "‫", "⁧", "‏", "שלום", "مرحبا").random(rnd)
    6 -> Char(rnd.nextInt(0xC0, 0x250)).toString() // é, É, ç, ø, ß…
    7 -> Char(rnd.nextInt(0x4E00, 0xA000)).toString() // CJK
    8 -> Char(rnd.nextInt(0, 0x20)).toString() // controls, \u0000 and \n included
    9 -> listOf(" ", "\t", "\n", "\r\n", " ", "　", "​").random(rnd)
    10 -> listOf("'", "\"", "%", "_", "\\", "'; DROP TABLE transactions;--", "?", ":x").random(rnd)
    11 -> {
        val v = rnd.nextInt(0x10000, 0x110000) - 0x10000
        "" + Char(0xD800 + (v shr 10)) + Char(0xDC00 + (v and 0x3FF))
    }
    else -> Char(rnd.nextInt(0x21, 0x7F)).toString()
}

private fun rfHostile(rnd: Random, maxPieces: Int): String =
    buildString { repeat(rnd.nextInt(0, maxPieces + 1)) { append(rfPiece(rnd)) } }

/**
 * A hostile merchant. Non-blank ones carry "Q<c>Q" with a unique [c], so no two of them share the
 * alias key (spacing and case ignored) and each alias can be tracked per merchant.
 */
private fun rfMerchant(rnd: Random, c: Int): String = when (rnd.nextInt(10)) {
    0 -> "﻿Q${c}Q" + rfHostile(rnd, 4) // U+FEFF at the very start
    1 -> "Q${c}Q linha 1\nlinha 2\r\nlinha 3"
    2 -> "Q${c}Q" + "x".repeat(rnd.nextInt(1000, 6000))
    3 -> "  Q${c}Q com espaços \t"
    4 -> "Q${c}Q " + listOf("😀", "‮evil", "שלום", "é", "Z̵̶̷").random(rnd)
    5 -> "Q${c}Q" + Char(0xD800) + "x" + Char(0xDC00) + "￾￿"
    6 -> listOf("", " ", "   ", "\t\n").random(rnd)
    else -> "Q${c}Q" + rfHostile(rnd, 8)
}

private fun rfAlias(rnd: Random, c: Int): String = when (rnd.nextInt(10)) {
    0, 1, 2 -> ""
    3 -> listOf(" ", "\t", "\n ", " ", "　").random(rnd) // blank: leaves the alias as it is
    4 -> "Padaria do Zé $c"
    5 -> "﻿Apelido $c"
    6 -> "  Zé $c  "
    7 -> "😀 $c ‮evil"
    8 -> "A".repeat(3000) + c
    else -> "<" + rfHostile(rnd, 8) + ">"
}

private fun rfTagList(rnd: Random): List<String> = when (rnd.nextInt(14)) {
    0 -> emptyList()
    1 -> listOf("")
    2 -> listOf(" ", "\t\n", " ", "　", "\r\n ") // only blanks
    3 -> listOf("Café", " CAFÉ ", "café\t") // one tag
    4 -> listOf("😀", "👩‍👩‍👧")
    5 -> listOf("‮evil", "שלום", "مرحبا")
    6 -> listOf("é", "é", "Z" + "̵̶̷".repeat(30))
    7 -> listOf("﻿feira", "￾x", "x￿")
    8 -> listOf(Char(0xD800) + "alto", "baixo" + Char(0xDC00))
    9 -> listOf("t".repeat(5000))
    10 -> listOf("linha1\nlinha2", "a\u0000b")
    11 -> listOf("mercado", "MERCADO", " Mercado ", "")
    else -> List(rnd.nextInt(1, 5)) { rfHostile(rnd, 6) }
}

private fun rfDate(rnd: Random, beyond: Boolean): LocalDate = when (rnd.nextInt(if (beyond && RF_BEYOND.isNotEmpty()) 10 else 9)) {
    0, 1, 2, 3 -> {
        val y = rnd.nextInt(2000, 2041)
        val m = rnd.nextInt(1, 13)
        LocalDate(y, m, rnd.nextInt(1, YearMonth(y, m).numberOfDays + 1))
    }
    4 -> listOf(LocalDate(2024, 2, 29), LocalDate(2028, 2, 29), LocalDate(2000, 2, 29), LocalDate(1600, 2, 29), LocalDate(2400, 2, 29)).random(rnd)
    5 -> LocalDate(rnd.nextInt(1990, 2060), listOf(1, 3, 5, 7, 8, 10, 12).random(rnd), 31)
    6 -> listOf(LocalDate(1, 1, 1), LocalDate(1, 12, 31), LocalDate(1000, 6, 15), LocalDate(1899, 12, 31)).random(rnd)
    7 -> listOf(LocalDate(9999, 12, 31), LocalDate(9999, 1, 31), LocalDate(5000, 2, 28), LocalDate(2100, 2, 28)).random(rnd)
    8 -> RF_TODAY
    else -> RF_BEYOND.random(rnd)
}

private fun rfTime(rnd: Random): LocalTime = when (rnd.nextInt(4)) {
    0 -> LocalTime(0, 0)
    1 -> LocalTime(23, 59)
    else -> LocalTime(rnd.nextInt(24), rnd.nextInt(60))
}

/** A form plus what the KDoc says about it. [identity] = the merchant when not blank (for alias tracking). */
private class RfCase(val form: TransactionForm, val valid: Boolean, val mayRefuse: Boolean, val identity: String?)

/**
 * A hostile form. With [wantValid] every rule of Db.accept holds (texts and dates stay hostile);
 * otherwise budget, tags, total and installments are drawn from refused and accepted values alike.
 */
private fun rfCase(rnd: Random, live: List<BudgetId>, dead: List<BudgetId>, c: Int, wantValid: Boolean): RfCase {
    val budget = if (wantValid || rnd.nextInt(3) > 0) live.random(rnd) else dead.random(rnd)
    var tags = rfTagList(rnd)
    if (wantValid) {
        var tries = 0
        while (rfTags(tags).isEmpty() && tries++ < 20) tags = rfTagList(rnd)
        if (rfTags(tags).isEmpty()) tags = listOf("ok")
    }
    val total = if (wantValid) {
        when (rnd.nextInt(7)) {
            0 -> 1L
            1 -> RF_MAX
            2 -> RF_MAX - 1
            3 -> 399L
            else -> rnd.nextLong(1, 10_000_000)
        }
    } else {
        listOf(
            Long.MIN_VALUE, -1L, 0L, 1L, 99L, rnd.nextLong(1, 10_000_000), rnd.nextLong(1, 10_000_000),
            RF_MAX, RF_MAX + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
        ).random(rnd)
    }
    val n = if (wantValid) listOf(1, 1, 2, 3, 12, 399, 400, rnd.nextInt(1, 401)).random(rnd)
    else listOf(Int.MIN_VALUE, -1, 0, 1, 2, 12, 400, 401, Int.MAX_VALUE, rnd.nextInt(1, 401)).random(rnd)
    val merchant = rfMerchant(rnd, c)
    val form = TransactionForm(
        budget = budget, tags = tags, total = Cents(total), installments = n, merchant = merchant,
        date = rfDate(rnd, beyond = !wantValid || rnd.nextInt(10) == 0), time = rfTime(rnd), alias = rfAlias(rnd, c),
    )
    return RfCase(form, rfValid(form, live), mayRefuse = !rfSaneYear(form.date), identity = merchant.takeIf { it.isNotBlank() })
}

// ---- setup and checks ----

/** Captures [count] notices keyed "<prefix>|i", one minute apart from minute [from]; their ids in order. */
private suspend fun rfCapture(db: Db, prefix: String, count: Int, from: Int, text: (Int) -> String): List<NoticeId> {
    repeat(count) { i ->
        val at = RF_NOW + (from + i) * 60_000L
        val raw = RawNotice(
            packageName = RF_PKG, appLabel = "Banco Recado", key = "$prefix|$i", postedAt = at, whenMs = at,
            title = if (prefix == "estorno") "Estorno" else "Compra aprovada", text = text(i),
        )
        assertTrue(capture(db, raw), "setup: capture $prefix|$i")
    }
    val byKey = db.pending().associate { it.raw.key to it.id }
    return List(count) { i -> byKey["$prefix|$i"] ?: fail("setup: notice $prefix|$i missing from pending()") }
}

/**
 * Reads [id] back and checks it is what [form] wrote (D28 normalization), with the installments of
 * spec §6 / KDoc of accept: n of them, k = 1..n, each total / n rounded down, the last absorbing the
 * remainder, Σ = total. With [exactDates] (month start day 1, so no move inside the month is needed)
 * installment k is dated purchase date + (k − 1) months.
 */
private suspend fun rfCheckStored(
    db: Db, what: String, id: TransactionId, form: TransactionForm, notice: NoticeId, estorno: NoticeId,
    budgetName: String, exactDates: Boolean,
): TransactionDetail {
    val d = rfNoThrow("$what: transaction(${id.v})") { db.transaction(id) }
    val r = d.row
    assertEquals(id, r.id, "$what: row.id")
    assertEquals(notice, r.notice, "$what: row.notice")
    assertEquals(form.budget, r.budget, "$what: row.budget")
    assertEquals(budgetName, r.budgetName, "$what: row.budgetName")
    val merchants = rfMerchantReadings(form.merchant)
    assertTrue(r.merchant in merchants, "$what: merchant read back ${rfShow(r.merchant)}, expected one of ${merchants.map(::rfShow)}")
    assertEquals(form.total, r.total, "$what: row.total")
    assertEquals(form.installments, r.installments, "$what: row.installments")
    assertEquals(form.date, r.date, "$what: row.date")
    assertEquals(form.time, r.time, "$what: row.time")
    assertEquals(rfTags(form.tags).sorted(), r.tags.sorted(), "$what: row.tags")
    assertEquals(estorno, r.estorno, "$what: row.estorno")
    assertEquals(Installment(), r.now, "$what: transaction(id).row.now must be zero (only transactions(today) fills it)")
    assertEquals(notice, d.notice.id, "$what: detail.notice.id")
    if (notice.v != 0L) assertEquals(NoticeStatus.PROCESSED, d.notice.status, "$what: the notice of a transaction is processed")

    val n = form.installments
    val parts = d.installments.sortedBy { it.k }
    assertEquals(n, parts.size, "$what: number of installments = the one asked")
    for ((j, p) in parts.withIndex()) if (p.k != j + 1) fail("$what: installment ks ${parts.map { it.k }.take(20)}… are not 1..$n")
    var sum = 0L
    for (p in parts) sum += p.cents.v
    assertEquals(form.total.v, sum, "$what: Σ installments = total")
    val each = form.total.v / n
    for (p in parts) {
        val want = if (p.k < n) each else form.total.v - each * (n - 1)
        if (p.cents.v != want) fail("$what: installment ${p.k} of $n is ${p.cents.v}, expected $want (total / n rounded down, last absorbs)")
        if (exactDates) {
            val wantDate = form.date.plus(p.k - 1, DateTimeUnit.MONTH)
            if (p.date != wantDate) fail("$what: installment ${p.k} dated ${p.date}, expected $wantDate (purchase + ${p.k - 1} months)")
        }
    }
    return d
}

/** transactions(): newest (date, time, then id) first. */
private fun rfCheckOrder(what: String, rows: List<TransactionRow>) {
    for (j in 1 until rows.size) {
        val a = rows[j - 1]
        val b = rows[j]
        val c = compareValuesBy(a, b, { it.date }, { it.time }, { it.id.v })
        if (c <= 0) fail("$what: transactions() not newest first at $j: (${a.date} ${a.time} id=${a.id.v}) before (${b.date} ${b.time} id=${b.id.v})")
    }
}

/**
 * TransactionRow.now: the installment dated in today's budget month, zero when none is. [inMonth] are
 * the installments of the purchase dated in that month; at most one by D7 (with more, any of them).
 */
private fun rfCheckNow(what: String, now: Installment, inMonth: List<Installment>, all: List<Installment>) {
    when (inMonth.size) {
        0 -> assertEquals(Installment(), now, "$what: now must be zero (no installment in today's month)")
        1 -> assertEquals(inMonth[0], now, "$what: now must be the installment dated in today's month")
        else -> assertTrue(now in inMonth, "$what: now $now is none of the installments in today's month $inMonth")
    }
    if (now != Installment()) assertTrue(now in all, "$what: now $now is not a real installment of the purchase")
}

class RecadoFuzzTest {

    @Test
    fun `accept addManual and edit survive hostile forms and read back what was stored`() = rfRealTime {
        rfWithDb { db ->
            val seed = 0x4EC4D01L
            val rnd = Random(seed)
            assertTrue(db.addSource(RF_PKG, "Banco Recado", RF_NOW), "setup: addSource")
            assertEquals(1, db.settings().monthStartDay, "setup: default month start day")
            assertEquals(RF_TODAY_MONTH, db.monthOf(RF_TODAY), "setup: month of today with start day 1")
            assertEquals(Cents(RF_MAX), parseBrl("999999999999999,99"), "setup: RF_MAX is the largest parseBrl value")

            val purchases = rfCapture(db, "compra", 140, 0) { i ->
                "Compra de R$ ${1 + i % 500},${(i % 100).toString().padStart(2, '0')} em LOJA $i"
            }
            val estornos = rfCapture(db, "estorno", 30, 1000) { i -> "Estorno de R$ ${10 + i},00 efetuado" }

            val mercado = db.createBudget("Mercado", Cents(100_000), RF_TODAY)
            val casa = db.createBudget("Casa", Cents(250_000), RF_TODAY)
            val gone = db.createBudget("Apagada", Cents(1_000), RF_TODAY)
            assertTrue(mercado.v != 0L && casa.v != 0L && gone.v != 0L, "setup: createBudget")
            db.deleteBudget(gone)
            val live = listOf(mercado, casa)
            val names = mapOf(mercado to "Mercado", casa to "Casa")
            val dead = listOf(BudgetId(0), BudgetId(-1), gone, BudgetId(Long.MAX_VALUE), BudgetId(Long.MIN_VALUE))
            val badNotices = listOf(NoticeId(0), NoticeId(-1), NoticeId(Long.MIN_VALUE), NoticeId(Long.MAX_VALUE), NoticeId(estornos.maxOf { it.v } + 10_000))
            val badTxs = listOf(TransactionId(0), TransactionId(-1), TransactionId(Long.MIN_VALUE), TransactionId(Long.MAX_VALUE))

            class Tx(val notice: NoticeId, var identity: String?, var estorno: NoticeId)
            val txs = LinkedHashMap<TransactionId, Tx>()
            val aliasOf = HashMap<String, Set<String>>() // merchant → readings of its alias
            val free = purchases.toMutableList() // pending purchase notices
            val taken = mutableListOf<NoticeId>() // accepted ones
            val freeEstornos = estornos.toMutableList()
            var counter = 0

            repeat(260) { i ->
                val roll = rnd.nextInt(20)

                // Now and then an estorno lands on a transaction, so that edits have one to keep.
                if (roll >= 18 && freeEstornos.isNotEmpty() && txs.values.any { it.estorno.v == 0L }) {
                    val target = txs.entries.filter { it.value.estorno.v == 0L }.random(rnd)
                    val e = freeEstornos.removeAt(rnd.nextInt(freeEstornos.size))
                    val what = "seed=$seed i=$i registerRefund(NoticeId(${e.v}), TransactionId(${target.key.v}))"
                    assertTrue(rfNoThrow(what) { db.registerRefund(e, target.key) }, "$what: pending estorno on a transaction not refunded")
                    target.value.estorno = e
                    return@repeat
                }

                val kind = when {
                    roll < 8 -> "accept"
                    roll < 12 -> "addManual"
                    else -> "edit"
                }
                var notice = NoticeId(0)
                var editId = TransactionId(0)
                val targetOk: Boolean
                when (kind) {
                    "accept" -> {
                        val r = rnd.nextInt(10)
                        notice = when {
                            r < 8 && free.isNotEmpty() -> free.random(rnd)
                            r < 9 && taken.isNotEmpty() -> taken.random(rnd)
                            else -> badNotices.random(rnd)
                        }
                        targetOk = notice in free
                    }
                    "edit" -> {
                        editId = if (rnd.nextInt(8) > 0 && txs.isNotEmpty()) txs.keys.random(rnd) else badTxs.random(rnd)
                        targetOk = editId in txs
                    }
                    else -> targetOk = true
                }

                // One form in six copies the notice's Parsed: not emendado (KDoc: corrected compares to Parsed).
                val parsedOf = when (kind) {
                    "accept" -> if (notice in free || notice in taken) notice else NoticeId(0)
                    "edit" -> txs[editId]?.notice ?: NoticeId(0)
                    else -> NoticeId(0)
                }
                val asParsed = parsedOf.v != 0L && rnd.nextInt(6) == 0
                val case = if (asParsed) {
                    val p = db.notice(parsedOf).parsed
                    val f = TransactionForm(
                        budget = live.random(rnd), tags = listOf("mercado"), total = p.cents, installments = rnd.nextInt(1, 13),
                        merchant = p.merchant, date = p.date, time = p.time,
                    )
                    RfCase(f, rfValid(f, live), !rfSaneYear(f.date), p.merchant.takeIf { it.isNotBlank() })
                } else {
                    rfCase(rnd, live, dead, ++counter, wantValid = rnd.nextInt(3) > 0)
                }
                val form = case.form
                val target = when (kind) {
                    "accept" -> "NoticeId(${notice.v}), "
                    "edit" -> "TransactionId(${editId.v}), "
                    else -> ""
                }
                val what = "seed=$seed i=$i $kind($target${rfShowForm(form)})"

                val v0 = db.version.value
                val rows0 = db.transactions(RF_TODAY)
                val tags0 = db.tags()
                val pending0 = db.pending()
                val detail0 = if (kind == "edit") db.transaction(editId) else null

                val id: TransactionId
                val wrote: Boolean
                when (kind) {
                    "accept" -> {
                        id = rfNoThrow(what) { db.accept(notice, form) }
                        wrote = id.v != 0L
                    }
                    "addManual" -> {
                        id = rfNoThrow(what) { db.addManual(form) }
                        wrote = id.v != 0L
                    }
                    else -> {
                        id = editId
                        wrote = rfNoThrow(what) { db.edit(editId, form) }
                    }
                }

                if (!case.valid || !targetOk || (case.mayRefuse && !wrote)) {
                    if (wrote) fail("$what: must be refused (form valid=${case.valid}, target ok=$targetOk) but was written (id=${id.v})")
                    assertEquals(v0, db.version.value, "$what: refused but version bumped")
                    assertEquals(rows0, db.transactions(RF_TODAY), "$what: refused but transactions() changed")
                    assertEquals(tags0, db.tags(), "$what: refused but tags() changed")
                    assertEquals(pending0, db.pending(), "$what: refused but pending() changed")
                    if (detail0 != null) assertEquals(detail0, db.transaction(editId), "$what: refused edit changed the transaction")
                } else {
                    if (!wrote) fail("$what: must be accepted (every rule of the KDoc holds)")
                    if (kind != "edit") assertTrue(rows0.none { it.id == id }, "$what: new id ${id.v} already in use")
                    val tx = if (kind == "edit") {
                        txs.getValue(id).also { it.identity = case.identity }
                    } else {
                        Tx(notice, case.identity, NoticeId(0)).also { txs[id] = it }
                    }
                    if (kind == "accept") {
                        free.remove(notice)
                        taken.add(notice)
                    }
                    if (case.identity != null && form.alias.isNotBlank()) aliasOf[case.identity] = rfAliasReadings(form.alias)

                    val d = rfCheckStored(db, what, id, form, tx.notice, tx.estorno, names.getValue(form.budget), exactDates = true)
                    when {
                        tx.notice.v == 0L -> assertEquals(false, d.row.corrected, "$what: a manual transaction is never corrected")
                        asParsed -> assertEquals(false, d.row.corrected, "$what: total, merchant, date and time equal the Parsed: not corrected")
                        form.total != d.notice.parsed.cents -> assertEquals(true, d.row.corrected, "$what: total differs from Parsed ${d.notice.parsed.cents.v}: corrected")
                    }
                    if (kind == "accept") assertTrue(db.pending().none { it.id == notice }, "$what: accepted notice still in pending()")

                    val listed = db.transactions(RF_TODAY).firstOrNull { it.id == id } ?: fail("$what: missing from transactions(today)")
                    assertEquals(d.row, listed.copy(now = Installment()), "$what: transactions(today) row differs from transaction(id).row")
                    val inMonth = d.installments.filter { YearMonth(it.date.year, it.date.month) == RF_TODAY_MONTH }
                    rfCheckNow("$what: transactions($RF_TODAY)", listed.now, inMonth, d.installments)
                }

                // Every step: the list is exactly the model, newest first, with each estorno and alias kept.
                val rows = db.transactions(RF_TODAY)
                assertEquals(txs.keys, rows.map { it.id }.toSet(), "$what: transactions() ids")
                rfCheckOrder(what, rows)
                for (r in rows) {
                    val tx = txs.getValue(r.id)
                    assertEquals(tx.estorno, r.estorno, "$what: transaction ${r.id.v} estorno (a registered estorno stays through edits)")
                    assertEquals(tx.notice, r.notice, "$what: transaction ${r.id.v} notice")
                    val ident = tx.identity ?: continue
                    val want = aliasOf[ident] ?: setOf("")
                    if (r.alias !in want) {
                        fail("$what: transaction ${r.id.v} (merchant ${rfShow(ident)}) alias ${rfShow(r.alias)}, expected one of ${want.map(::rfShow)} (blank alias leaves it as it is)")
                    }
                }
            }

            // The end: every row of transactions(today) is its transaction(id).row plus the installment of today's month.
            val rows = db.transactions(RF_TODAY)
            for (r in rows) {
                val d = db.transaction(r.id)
                val what = "seed=$seed end transaction ${r.id.v}"
                assertEquals(d.row, r.copy(now = Installment()), "$what: transactions(today) row differs from transaction(id).row")
                val inMonth = d.installments.filter { YearMonth(it.date.year, it.date.month) == RF_TODAY_MONTH }
                rfCheckNow(what, r.now, inMonth, d.installments)
            }
            assertTrue(db.transactions().all { it.now == Installment() }, "seed=$seed transactions() without today: every now is zero")
        }
    }

    @Test
    fun `registerRefund undoRefund and previewRefund with hostile ids never throw and refuse what the KDoc refuses`() = rfRealTime {
        rfWithDb { db ->
            val seed = 0xE5704L
            val rnd = Random(seed)
            assertTrue(db.addSource(RF_PKG, "Banco Recado", RF_NOW), "setup: addSource")
            assertEquals(1, db.settings().monthStartDay, "setup: default month start day")
            val purchases = rfCapture(db, "compra", 40, 0) { i -> "Compra de R$ ${5 + i},90 em LOJA $i" }
            val estornos = rfCapture(db, "estorno", 30, 1000) { i -> "Estorno de R$ ${5 + i},90 efetuado" }
            val all = purchases + estornos
            val casa = db.createBudget("Casa", Cents(500_000), RF_TODAY)
            assertNotEquals(0L, casa.v, "setup: createBudget")
            val todayMonth = db.monthOf(RF_TODAY)
            db.ensureMonths(todayMonth)

            // The model (architecture §5): pending = not dismissed and referenced by nothing.
            val dismissed = HashSet<NoticeId>()
            val txNotice = LinkedHashMap<TransactionId, NoticeId>() // live transaction → its notice (0 = manual)
            val txEstorno = HashMap<TransactionId, NoticeId>() // refunded transaction → its estorno
            val refunds = HashMap<NoticeId, Pair<TransactionId, NoticeId>>() // estorno → (transaction or notice)
            val everTx = mutableListOf<TransactionId>()
            fun isPending(n: NoticeId) = n in all && n !in dismissed && n !in txNotice.values && n !in refunds &&
                refunds.values.none { it.second == n }
            fun status(n: NoticeId) = when {
                n in dismissed -> NoticeStatus.DISMISSED
                isPending(n) -> NoticeStatus.PENDING
                else -> NoticeStatus.PROCESSED
            }
            fun simpleForm(): TransactionForm = TransactionForm(
                budget = casa, tags = listOf("casa"), total = Cents(rnd.nextLong(1, 500_000)),
                installments = listOf(1, 1, 2, 3, 12).random(rnd), merchant = "LOJA ${rnd.nextInt(1000)}",
                date = if (rnd.nextBoolean()) LocalDate(2026, 10, rnd.nextInt(1, 15)) else LocalDate(rnd.nextInt(2024, 2028), rnd.nextInt(1, 13), rnd.nextInt(1, 29)),
                time = rfTime(rnd),
            )

            for (k in 0 until 15) {
                val id = db.accept(purchases[k], simpleForm())
                assertNotEquals(0L, id.v, "setup: accept ${purchases[k].v}")
                txNotice[id] = purchases[k]
                everTx += id
            }
            repeat(8) { k ->
                val id = db.addManual(simpleForm())
                assertNotEquals(0L, id.v, "setup: addManual $k")
                txNotice[id] = NoticeId(0)
                everTx += id
            }

            val badN = listOf(NoticeId(0), NoticeId(-1), NoticeId(Long.MIN_VALUE), NoticeId(Long.MAX_VALUE), NoticeId(all.maxOf { it.v } + 10_000))
            val badT = listOf(TransactionId(0), TransactionId(-1), TransactionId(Long.MIN_VALUE), TransactionId(Long.MAX_VALUE), TransactionId(everTx.maxOf { it.v } + 10_000))

            // Hostile ids alone: all refused, nothing written.
            run {
                val v0 = db.version.value
                val what = "seed=$seed prelude"
                val realTx = everTx.first()
                val pendingEstorno = estornos.first()
                for (e in badN + pendingEstorno) for (t in badT) {
                    assertEquals(false, rfNoThrow("$what registerRefund(NoticeId(${e.v}), TransactionId(${t.v}))") { db.registerRefund(e, t) }, "$what registerRefund(NoticeId(${e.v}), TransactionId(${t.v}))")
                }
                for (e in badN) {
                    assertEquals(false, rfNoThrow("$what registerRefund(NoticeId(${e.v}), TransactionId(${realTx.v}))") { db.registerRefund(e, realTx) }, "$what registerRefund(NoticeId(${e.v}), real transaction)")
                    for (p in badN + pendingEstorno) {
                        assertEquals(false, rfNoThrow("$what registerRefund(NoticeId(${e.v}), NoticeId(${p.v}))") { db.registerRefund(e, p) }, "$what registerRefund(NoticeId(${e.v}), NoticeId(${p.v}))")
                        assertEquals(false, rfNoThrow("$what registerRefund(NoticeId(${p.v}), NoticeId(${e.v}))") { db.registerRefund(p, e) }, "$what registerRefund(NoticeId(${p.v}), NoticeId(${e.v}))")
                    }
                    assertEquals(false, rfNoThrow("$what undoRefund(${e.v})") { db.undoRefund(e) }, "$what undoRefund(NoticeId(${e.v}))")
                }
                for (n in all.take(5) + estornos.take(5)) {
                    assertEquals(false, rfNoThrow("$what registerRefund(${n.v}, ${n.v})") { db.registerRefund(n, n) }, "$what registerRefund(NoticeId(${n.v}), the same NoticeId)")
                    assertEquals(false, rfNoThrow("$what undoRefund(${n.v})") { db.undoRefund(n) }, "$what undoRefund(NoticeId(${n.v})) with no estorno registered")
                }
                for (t in badT) {
                    for (today in listOf(RF_TODAY, LocalDate(1, 1, 1), LocalDate(9999, 12, 31)) + RF_BEYOND) {
                        assertEquals(Preview(), rfNoThrow("$what previewRefund(${t.v}, $today)") { db.previewRefund(t, today) }, "$what previewRefund(TransactionId(${t.v}), $today)")
                    }
                    rfNoThrow("$what deleteTransaction(${t.v})") { db.deleteTransaction(t) }
                }
                // The notice of a transaction is processed, so it can't be an estorno.
                assertEquals(false, db.registerRefund(txNotice.getValue(realTx), realTx), "$what registerRefund(its own notice, transaction)")
                assertEquals(v0, db.version.value, "$what: hostile ids bumped version")
            }

            fun anyNotice(): NoticeId = if (rnd.nextInt(6) == 0) badN.random(rnd) else all.random(rnd)
            fun pendingNotice(): NoticeId = all.filter(::isPending).randomOrNull(rnd) ?: anyNotice()
            fun anyTx(): TransactionId = if (rnd.nextInt(6) == 0) badT.random(rnd) else everTx.random(rnd)
            fun pickToday(): LocalDate = when (rnd.nextInt(7)) {
                0 -> RF_TODAY
                1 -> LocalDate(1, 1, 1)
                2 -> LocalDate(9999, 12, 31)
                3 -> LocalDate(2028, 2, 29)
                4 -> if (RF_BEYOND.isEmpty()) RF_TODAY else RF_BEYOND.random(rnd)
                else -> LocalDate(rnd.nextInt(1990, 2041), rnd.nextInt(1, 13), rnd.nextInt(1, 29))
            }
            var counter = 0

            repeat(400) { i ->
                val v0 = db.version.value
                val touched = mutableListOf<NoticeId>()
                var what: String
                val expect: Boolean
                val got: Boolean
                when (rnd.nextInt(15)) {
                    0, 1, 2 -> {
                        val e = if (rnd.nextInt(3) > 0) pendingNotice() else anyNotice()
                        val t = anyTx()
                        what = "seed=$seed i=$i registerRefund(NoticeId(${e.v}), TransactionId(${t.v}))"
                        expect = isPending(e) && t in txNotice && t !in txEstorno
                        got = rfNoThrow(what) { db.registerRefund(e, t) }
                        if (expect && got) {
                            refunds[e] = t to NoticeId(0)
                            txEstorno[t] = e
                        }
                        touched += e
                    }
                    3, 4 -> {
                        val e = if (rnd.nextInt(3) > 0) pendingNotice() else anyNotice()
                        val p = when (rnd.nextInt(5)) {
                            0 -> e
                            1 -> anyNotice()
                            else -> pendingNotice()
                        }
                        what = "seed=$seed i=$i registerRefund(NoticeId(${e.v}), NoticeId(${p.v}))"
                        expect = isPending(e) && isPending(p) && e != p
                        got = rfNoThrow(what) { db.registerRefund(e, p) }
                        if (expect && got) refunds[e] = TransactionId(0) to p
                        touched += e
                        touched += p
                    }
                    5, 6 -> {
                        val e = if (refunds.isNotEmpty() && rnd.nextInt(3) > 0) refunds.keys.random(rnd) else anyNotice()
                        what = "seed=$seed i=$i undoRefund(NoticeId(${e.v}))"
                        expect = e in refunds
                        got = rfNoThrow(what) { db.undoRefund(e) }
                        if (expect && got) {
                            val (t, p) = refunds.remove(e)!!
                            if (t.v != 0L) txEstorno.remove(t)
                            touched += p
                        }
                        touched += e
                    }
                    7, 8 -> {
                        val t = if (txEstorno.isNotEmpty() && rnd.nextInt(4) == 0) txEstorno.keys.random(rnd) else anyTx()
                        val today = pickToday()
                        what = "seed=$seed i=$i previewRefund(TransactionId(${t.v}), $today)"
                        val p = rfNoThrow(what) { db.previewRefund(t, today) }
                        assertEquals(v0, db.version.value, "$what: a preview writes nothing")
                        if (t !in txNotice || t in txEstorno) {
                            assertEquals(Preview(), p, "$what: zero for an unknown or refunded transaction")
                        } else if (rfSaneYear(today)) {
                            val d = db.transaction(t)
                            val first = d.installments.firstOrNull { it.k == 1 } ?: fail("$what: no installment 1")
                            assertEquals(d.row.date, first.date, "$what: installment 1 is dated the purchase date")
                            assertEquals(db.monthOf(d.row.date), p.month, "$what: month of the first installment")
                            assertEquals(first.cents.v, p.after.v - p.before.v, "$what: after = before + the installment of that month (before=${p.before.v} after=${p.after.v})")
                            if (p.month == todayMonth) {
                                val bd = db.budgetDetail(casa, todayMonth, RF_TODAY)
                                assertEquals(bd.month.remaining, p.before, "$what: before = margem of the budget in that month")
                            }
                        }
                        expect = true
                        got = true
                    }
                    9 -> {
                        val n = anyNotice()
                        what = "seed=$seed i=$i dismiss(NoticeId(${n.v}))"
                        expect = isPending(n)
                        got = rfNoThrow(what) { db.dismiss(n) }
                        if (expect && got) dismissed += n
                        touched += n
                    }
                    10 -> {
                        val n = if (dismissed.isNotEmpty() && rnd.nextBoolean()) dismissed.random(rnd) else anyNotice()
                        what = "seed=$seed i=$i restore(NoticeId(${n.v}))"
                        expect = n in dismissed
                        got = rfNoThrow(what) { db.restore(n) }
                        if (expect && got) dismissed -= n
                        touched += n
                    }
                    11 -> {
                        val t = anyTx()
                        what = "seed=$seed i=$i deleteTransaction(TransactionId(${t.v}))"
                        rfNoThrow(what) { db.deleteTransaction(t) }
                        expect = t in txNotice
                        got = expect
                        if (expect) {
                            touched += txNotice.remove(t)!!
                            val e = txEstorno.remove(t)
                            if (e != null) {
                                refunds.remove(e)
                                touched += e
                            }
                        }
                    }
                    12 -> {
                        val n = if (rnd.nextInt(3) > 0) pendingNotice() else anyNotice()
                        val f = simpleForm()
                        what = "seed=$seed i=$i accept(NoticeId(${n.v}), ${rfShowForm(f)})"
                        expect = isPending(n)
                        val id = rfNoThrow(what) { db.accept(n, f) }
                        got = id.v != 0L
                        if (expect && got) {
                            txNotice[id] = n
                            everTx += id
                        }
                        touched += n
                    }
                    13 -> {
                        val f = simpleForm()
                        what = "seed=$seed i=$i addManual(${rfShowForm(f)})"
                        expect = true
                        val id = rfNoThrow(what) { db.addManual(f) }
                        got = id.v != 0L
                        if (got) {
                            txNotice[id] = NoticeId(0)
                            everTx += id
                        }
                    }
                    else -> {
                        val t = if (txEstorno.isNotEmpty() && rnd.nextBoolean()) txEstorno.keys.random(rnd) else anyTx()
                        val case = if (rnd.nextInt(3) > 0) {
                            val f = simpleForm()
                            RfCase(f, true, false, f.merchant)
                        } else {
                            rfCase(rnd, listOf(casa), listOf(BudgetId(0), BudgetId(-1), BudgetId(Long.MAX_VALUE)), ++counter, wantValid = rnd.nextBoolean())
                        }
                        what = "seed=$seed i=$i edit(TransactionId(${t.v}), ${rfShowForm(case.form)})"
                        val before = db.transaction(t)
                        got = rfNoThrow(what) { db.edit(t, case.form) }
                        expect = t in txNotice && case.valid && !(case.mayRefuse && !got)
                        if (expect && got) {
                            rfCheckStored(db, what, t, case.form, txNotice.getValue(t), txEstorno[t] ?: NoticeId(0), "Casa", exactDates = true)
                        } else if (!got) {
                            assertEquals(before, db.transaction(t), "$what: refused edit changed the transaction")
                        }
                    }
                }
                what += " [pending=${all.filter(::isPending).map { it.v }} dismissed=${dismissed.map { it.v }} refunds=${refunds.map { (e, t) -> "${e.v}->${t.first.v}/${t.second.v}" }}]"
                assertEquals(expect, got, "$what: returned $got")
                if (!expect) assertEquals(v0, db.version.value, "$what: refused but version bumped")

                // The whole state against the model.
                assertEquals(all.filter(::isPending).toSet(), db.pending().map { it.id }.toSet(), "$what: pending()")
                assertEquals(dismissed.toSet(), db.dismissed().map { it.id }.toSet(), "$what: dismissed()")
                for (n in touched.distinct()) if (n in all) assertEquals(status(n), db.notice(n).status, "$what: status of NoticeId(${n.v})")
                val rows = db.transactions(RF_TODAY)
                assertEquals(txNotice.keys, rows.map { it.id }.toSet(), "$what: transactions() ids")
                for (r in rows) {
                    assertEquals(txNotice.getValue(r.id), r.notice, "$what: transaction ${r.id.v} notice")
                    assertEquals(txEstorno[r.id] ?: NoticeId(0), r.estorno, "$what: transaction ${r.id.v} estorno")
                }
            }
        }
    }

    @Test
    fun `transactions with extreme today never throws and now is zero or the installment of that month`() = rfRealTime {
        rfWithDb { db ->
            val seed = 0x70DA7L
            val rnd = Random(seed)
            assertTrue(db.addSource(RF_PKG, "Banco Recado", RF_NOW), "setup: addSource")
            val estornos = rfCapture(db, "estorno", 12, 0) { i -> "Estorno de R$ ${1 + i},00 efetuado" }.toMutableList()
            val budget = db.createBudget("Viagem", Cents(1_000_000), RF_TODAY)
            assertNotEquals(0L, budget.v, "setup: createBudget")

            val made = mutableListOf<TransactionId>()
            val installments = HashMap<TransactionId, List<Installment>>()

            for ((round, day) in listOf(1, 15, 29, 31).withIndex()) {
                if (round > 0) assertTrue(db.setMonthStartDay(day, RF_TODAY), "seed=$seed setMonthStartDay($day)")
                assertEquals(day, db.settings().monthStartDay, "seed=$seed month start day")
                val cache = HashMap<LocalDate, YearMonth>()
                suspend fun monthOf(d: LocalDate): YearMonth = cache.getOrPut(d) { rfNoThrow("seed=$seed day=$day monthOf($d)") { db.monthOf(d) } }
                // D7: the new day redates every installment, still one per month from the purchase's.
                for (id in made) {
                    val detail = db.transaction(id)
                    val first = monthOf(detail.row.date)
                    for (p in detail.installments) {
                        if (monthOf(p.date) != rfMonthPlus(first, p.k - 1)) fail("seed=$seed day=$day: transaction ${id.v} installment ${p.k} dated ${p.date} after the start day change")
                    }
                    installments[id] = detail.installments
                }

                // Purchases at extreme dates; Jan 1970 ones so that NO_DATE, read as a real day, would find one.
                // 9999-12-31 in 1× and 9999-01-31 in 12× end on the last storable day; in 13× it spills past it (refused).
                val purchases = listOf(
                    LocalDate(1, 1, 1) to (if (round == 0 || round == 3) 400 else 7),
                    LocalDate(9999, 12, 31) to 1,
                    LocalDate(9999, 1, 31) to 12,
                    LocalDate(9999, 12, 31) to (if (round == 0) 400 else 13),
                    LocalDate(2028, 2, 29) to 12,
                    LocalDate(2028, 1, 31) to 3,
                    LocalDate(1970, 1, 10) to 1,
                    LocalDate(1969, 12, 15) to 3,
                    RF_TODAY to 1,
                    LocalDate(rnd.nextInt(1990, 2041), rnd.nextInt(1, 13), rnd.nextInt(1, 29)) to rnd.nextInt(1, 25),
                )
                for ((date, n) in purchases) {
                    val form = TransactionForm(
                        budget = budget, tags = listOf("viagem"), total = Cents(rnd.nextLong(1, 10_000_000)), installments = n,
                        merchant = "LOJA $round $date", date = date, time = rfTime(rnd),
                    )
                    val what = "seed=$seed day=$day addManual(${rfShowForm(form)})"
                    val id = rfNoThrow(what) { db.addManual(form) }
                    if (!rfInRange(date, n)) {
                        assertEquals(0L, id.v, "$what: an installment past 9999-12-31 must be refused")
                        continue
                    }
                    assertNotEquals(0L, id.v, "$what: refused (every date is in range)")
                    val d = rfCheckStored(db, what, id, form, NoticeId(0), NoticeId(0), "Viagem", exactDates = day == 1)
                    // Spec §6: installment k counts in the k-th budget month from the purchase's (D7: moved inside it).
                    val first = monthOf(date)
                    for (p in d.installments) {
                        val m = monthOf(p.date)
                        if (m != rfMonthPlus(first, p.k - 1)) fail("$what: installment ${p.k} dated ${p.date} falls in $m, expected ${rfMonthPlus(first, p.k - 1)}")
                    }
                    made += id
                    installments[id] = d.installments
                }

                // Every other new purchase gets refunded: refunded or not, now is the same.
                for ((j, id) in made.takeLast(purchases.count { (d, n) -> rfInRange(d, n) }).withIndex()) {
                    if (j % 2 == 1 && estornos.isNotEmpty()) {
                        val e = estornos.removeAt(0)
                        assertTrue(db.registerRefund(e, id), "seed=$seed day=$day registerRefund(${e.v}, ${id.v})")
                    }
                }

                val someInstallment = installments.getValue(made.random(rnd)).random(rnd).date
                val sane = listOf(
                    RF_TODAY, LocalDate(2028, 2, 29), LocalDate(2028, 3, 1), LocalDate(2028, 1, 31), LocalDate(2029, 1, 31),
                    LocalDate(1, 1, 1), LocalDate(1, 2, 15), LocalDate(34, 4, 30), LocalDate(9999, 12, 31),
                    LocalDate(10_033, 3, 31), LocalDate(10_033, 4, 30), LocalDate(1970, 1, 2), LocalDate(1969, 12, 31),
                    LocalDate(1970, 1, 31), LocalDate(2000, 2, 29), LocalDate(2026, 10, 31), someInstallment,
                    LocalDate(rnd.nextInt(1990, 2041), rnd.nextInt(1, 13), rnd.nextInt(1, 29)),
                    LocalDate(rnd.nextInt(1, 9999), rnd.nextInt(1, 13), rnd.nextInt(1, 29)),
                )
                for (today in sane) {
                    val what = "seed=$seed day=$day transactions($today)"
                    val rows = rfNoThrow(what) { db.transactions(today) }
                    assertEquals(made.toSet(), rows.map { it.id }.toSet(), "$what: ids")
                    val month = monthOf(today)
                    for (r in rows) {
                        val parts = installments.getValue(r.id)
                        val inMonth = parts.filter { monthOf(it.date) == month }
                        rfCheckNow("$what: transaction ${r.id.v} (${r.date} in ${r.installments}, refunded=${r.refunded})", r.now, inMonth, parts)
                    }
                }
                for (today in RF_BEYOND) {
                    val what = "seed=$seed day=$day transactions($today)"
                    val rows = rfNoThrow(what) { db.transactions(today) }
                    assertEquals(made.toSet(), rows.map { it.id }.toSet(), "$what: ids")
                    for (r in rows) {
                        if (r.now != Installment() && r.now !in installments.getValue(r.id)) {
                            fail("$what: transaction ${r.id.v} now ${r.now} is neither zero nor one of its installments")
                        }
                    }
                }
                for ((what, rows) in listOf(
                    "seed=$seed day=$day transactions()" to rfNoThrow("transactions()") { db.transactions() },
                    "seed=$seed day=$day transactions(NO_DATE)" to rfNoThrow("transactions(NO_DATE)") { db.transactions(NO_DATE) },
                )) {
                    assertEquals(made.toSet(), rows.map { it.id }.toSet(), "$what: ids")
                    for (r in rows) assertEquals(Installment(), r.now, "$what: without today every now is zero (transaction ${r.id.v} dated ${r.date})")
                }
            }
        }
    }
}
