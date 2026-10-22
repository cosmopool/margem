package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.onDay
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toInstant
import kotlinx.datetime.yearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Previsto (spec §2.13, architecture D59–D64) fuzz: hostile input only.
// - `fold` and `MerchantKey.of` (public pure contracts, D61): NFC vs NFD, stacked combining marks, RTL,
//   controls, lone surrogates, ß, İ, ı, the NBSP family, emoji. Never throws, fold(fold(x)) == fold(x),
//   NFC and NFD of the Latin table fold equal, a key has no edge-space char.
// - Hostile forms through the entry points of saveTransaction (Db.accept, Db.addManual(form, today),
//   Db.edit) with repeats / occurrence / planned set: unknown and closed occurrences, unknown previstos,
//   huge/zero/negative totals, dates at the storable edges, names over 80 after cleaning, blank names,
//   lots of tags. Never throws; refuses exactly what the KDoc refuses, and a refusal writes nothing
//   (every read compared before/after); what is accepted reads back sane.
// - Recognition with hostile bank texts: a recado whose bank text gives an empty key never gets
//   NoticeRow.planned (R2); the others follow fonte + key + the 10-day window (D60).
// - skipOccurrence / deletePlanned with hostile ids.
// Fixed seeds, bounded iterations, a real SQLite file per test, a wall-clock timeout. Expected values come
// from the spec, the architecture and the KDoc of Types.kt / Db.kt / Extract.kt, never from the
// implementation. Every failure message carries the seed, the step and the input.

private val PF_TODAY = LocalDate(2026, 10, 9) // sexta 9 out 2026
private val PF_TZ = TimeZone.of("America/Sao_Paulo")

/** The `today` of the "A caminho" reads: far enough that every previsto the fuzz makes is listed. */
private val PF_HORIZON = LocalDate(2027, 3, 31)

/** planned(today) creates the occurrences dated up to today + 10 days (R1). */
private val PF_HORIZON_REACH = PF_HORIZON.plus(10, DateTimeUnit.DAY)

private const val PF_PKG = "com.banco.previsto"
private const val PF_LABEL = "Banco Previsto"
private const val PF_PKG2 = "com.banco.outro"
private const val PF_LABEL2 = "Banco Outro"
private val PF_LABELS = mapOf(PF_PKG to PF_LABEL, PF_PKG2 to PF_LABEL2)

/** MAX_CENTS of Extract.kt: "Forms and limits are capped to it". */
private const val PF_MAX = 99_999_999_999_999_999L

/** MAX_CAPTION: a legenda and a previsto's name (D53, D62). */
private const val PF_CAP = 80

private val PF_SETUP_AT = LocalDate(2026, 10, 1).atTime(9, 0).toInstant(PF_TZ).toEpochMilliseconds()

/** Dates outside 0001-01-01..9999-12-31 (Db.accept refuses an installment dated there). */
private val PF_BEYOND: List<LocalDate> = listOf(Triple(10000, 1, 1), Triple(0, 12, 31), Triple(-1, 6, 15))
    .mapNotNull { (y, m, d) -> runCatching { LocalDate(y, m, d) }.getOrNull() }

/** Real threads under a wall-clock timeout (withTimeout inside Dispatchers.Default), so a hang fails. */
private fun pfRealTime(block: suspend () -> Unit) = runTest(timeout = 180.seconds) {
    withContext(Dispatchers.Default) { withTimeout(150.seconds) { block() } }
}

/** A fresh Db on a temporary file, closed and deleted (with -wal/-shm) afterwards. */
private suspend fun pfWithDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "Previsto-fuzz-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path, {}, PF_TZ)
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
private suspend fun <T> pfNoThrow(where: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fail("$where threw $e", e)
}

/** The same for the pure functions. */
private fun <T> pfPure(where: String, block: () -> T): T = try {
    block()
} catch (e: Throwable) {
    fail("$where threw $e", e)
}

/** Escaped, truncated rendering of [s] for failure messages. */
private fun pfShow(s: String): String = buildString {
    append('"')
    for (c in s.take(60)) {
        if (c.code in 0x20..0x7E && c != '"' && c != '\\') append(c)
        else append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }
    append('"')
    if (s.length > 60) append("…(${s.length} units)")
}

private fun pfShowForm(f: TransactionForm): String =
    "TransactionForm(budget=${f.budget.v}, tags=${if (f.tags.size > 6) "${f.tags.size} tags" else f.tags.map(::pfShow).toString()}, " +
        "total=${f.total.v}, installments=${f.installments}, merchant=${pfShow(f.merchant)}, date=${f.date}, time=${f.time}, " +
        "alias=${pfShow(f.alias)}, caption=${pfShow(f.caption)}, occurrence=${f.occurrence.v}, repeats=${f.repeats}, planned=${f.planned.v})"

// ---- the cleaning the contract describes ----

/**
 * isEdgeSpace (Db.kt KDoc, spec §2.12, decided 2026-10-20): ASCII controls and space, NEL and the NBSP
 * family. What MerchantKey.of drops anywhere and the legenda/name clean trims at the edges.
 */
private fun pfEdge(c: Char): Boolean =
    c <= ' ' || c == '\u0085' || c == ' ' || c == ' ' || c in ' '..' ' || c == ' ' || c == ' ' || c == '　'

/** Every line break (LF, CRLF, CR, U+2028, U+2029) becomes one space (TransactionForm.caption). */
private fun pfBreaks(s: String): String = buildString(s.length) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') {
            append(' ')
            i += 2
        } else {
            append(if (c == '\n' || c == '\r' || c == ' ' || c == ' ') ' ' else c)
            i++
        }
    }
}

/** The text as the Db stores and reads it back (D28, storable): U+FEFF, U+FFFE, U+FFFF and lone surrogates become U+FFFD. */
private fun pfStored(s: String): String = buildString(s.length) {
    for (i in s.indices) {
        val c = s[i]
        val lone = (c.isHighSurrogate() && (i + 1 >= s.length || !s[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !s[i - 1].isHighSurrogate()))
        append(if (c == '﻿' || c == '￾' || c == '￿' || lone) '�' else c)
    }
}

/** "Cleaned like the caption" (TransactionForm.caption, D53, D62, D64): breaks → space, edges trimmed, storable. */
private fun pfClean(s: String): String = pfStored(pfBreaks(s).trim(::pfEdge))

/** TransactionForm.tags: "trimmed, lowercased, blanks and repeats dropped", read back as stored. */
private fun pfTags(tags: List<String>): List<String> =
    tags.map { pfStored(it.trim().lowercase()) }.filter { it.isNotEmpty() }.distinct()

/** Db.accept: every installment dated in 0001-01-01..9999-12-31; installment k is in the calendar month + (k − 1). */
private fun pfInRange(date: LocalDate, n: Int): Boolean {
    if (n !in 1..400 || date.year < 1 || date.year > 9999) return false
    val idx = date.year.toLong() * 12 + date.month.ordinal + (n - 1)
    return idx.floorDiv(12L) <= 9999L
}

/** [day] in [ym], clamped to its last day (P8). */
private fun pfClamp(ym: YearMonth, day: Int): LocalDate = ym.onDay(minOf(day, ym.numberOfDays))

/** "First occurrence in the calendar month after [date]'s" (TransactionForm.repeats, D64). */
private fun pfFirstOcc(date: LocalDate): LocalDate = pfClamp(date.yearMonth.plusMonth(), date.day)

private fun pfDays(a: LocalDate, b: LocalDate): Int = abs(a.daysUntil(b))

// ---- the Latin table of fold's KDoc, with its canonical decompositions (Unicode data, typed by hand) ----

/** (precomposed letter, base letter, combining mark) for every letter of U+00C0–U+017F the KDoc table lists. */
private val PF_LATIN: List<Triple<Char, Char, Char>> = buildList {
    // U+00C0–U+00DD: capital at code, small at code + 0x20.
    fun c0(code: Int, base: Char, mark: Int) {
        add(Triple(Char(code), base.uppercaseChar(), Char(mark)))
        add(Triple(Char(code + 0x20), base, Char(mark)))
    }
    // U+0100–U+017E: capital at code, small at code + 1.
    fun ext(code: Int, base: Char, mark: Int) {
        add(Triple(Char(code), base.uppercaseChar(), Char(mark)))
        add(Triple(Char(code + 1), base, Char(mark)))
    }
    for ((k, m) in listOf(0x300, 0x301, 0x302, 0x303, 0x308, 0x30A).withIndex()) c0(0xC0 + k, 'a', m)
    c0(0xC7, 'c', 0x327)
    for ((k, m) in listOf(0x300, 0x301, 0x302, 0x308).withIndex()) {
        c0(0xC8 + k, 'e', m)
        c0(0xCC + k, 'i', m)
    }
    c0(0xD1, 'n', 0x303)
    for ((k, m) in listOf(0x300, 0x301, 0x302, 0x303, 0x308).withIndex()) c0(0xD2 + k, 'o', m)
    for ((k, m) in listOf(0x300, 0x301, 0x302, 0x308).withIndex()) c0(0xD9 + k, 'u', m)
    c0(0xDD, 'y', 0x301)
    add(Triple('ÿ', 'y', '̈'))
    ext(0x100, 'a', 0x304); ext(0x102, 'a', 0x306); ext(0x104, 'a', 0x328)
    ext(0x106, 'c', 0x301); ext(0x108, 'c', 0x302); ext(0x10A, 'c', 0x307); ext(0x10C, 'c', 0x30C)
    ext(0x10E, 'd', 0x30C)
    ext(0x112, 'e', 0x304); ext(0x114, 'e', 0x306); ext(0x116, 'e', 0x307); ext(0x118, 'e', 0x328); ext(0x11A, 'e', 0x30C)
    ext(0x11C, 'g', 0x302); ext(0x11E, 'g', 0x306); ext(0x120, 'g', 0x307); ext(0x122, 'g', 0x327)
    ext(0x124, 'h', 0x302)
    ext(0x128, 'i', 0x303); ext(0x12A, 'i', 0x304); ext(0x12C, 'i', 0x306); ext(0x12E, 'i', 0x328)
    add(Triple('İ', 'I', '̇'))
    ext(0x134, 'j', 0x302); ext(0x136, 'k', 0x327)
    ext(0x139, 'l', 0x301); ext(0x13B, 'l', 0x327); ext(0x13D, 'l', 0x30C)
    ext(0x143, 'n', 0x301); ext(0x145, 'n', 0x327); ext(0x147, 'n', 0x30C)
    ext(0x14C, 'o', 0x304); ext(0x14E, 'o', 0x306); ext(0x150, 'o', 0x30B)
    ext(0x154, 'r', 0x301); ext(0x156, 'r', 0x327); ext(0x158, 'r', 0x30C)
    ext(0x15A, 's', 0x301); ext(0x15C, 's', 0x302); ext(0x15E, 's', 0x327); ext(0x160, 's', 0x30C)
    ext(0x162, 't', 0x327); ext(0x164, 't', 0x30C)
    ext(0x168, 'u', 0x303); ext(0x16A, 'u', 0x304); ext(0x16C, 'u', 0x306); ext(0x16E, 'u', 0x30A); ext(0x170, 'u', 0x30B); ext(0x172, 'u', 0x328)
    ext(0x174, 'w', 0x302); ext(0x176, 'y', 0x302)
    add(Triple('Ÿ', 'Y', '̈'))
    ext(0x179, 'z', 0x301); ext(0x17B, 'z', 0x307); ext(0x17D, 'z', 0x30C)
}

private val PF_TO_NFD: Map<Char, String> = PF_LATIN.associate { (pre, base, mark) -> pre to "$base$mark" }
private val PF_TO_NFC: Map<String, Char> = PF_LATIN.associate { (pre, base, mark) -> "$base$mark" to pre }

/** Every letter of the table in its NFD spelling. */
private fun pfToNfd(s: String): String = buildString { for (c in s) append(PF_TO_NFD[c] ?: c.toString()) }

/** Every base + mark pair of the table in its NFC spelling. */
private fun pfToNfc(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        val pre = if (i + 1 < s.length) PF_TO_NFC[s.substring(i, i + 2)] else null
        if (pre != null) {
            append(pre)
            i += 2
        } else {
            append(s[i])
            i++
        }
    }
}

// ---- hostile text ----

/** The NBSP family, NEL, VT/FF and controls: the chars of isEdgeSpace that are not ASCII space. */
private val PF_INVISIBLE = listOf(
    " ", " ", " ", " ", " ", " ", " ", "　", "\u0085", "\u000B", "\u000C", "\u001F", "\u0001",
)

/** Invisibles that are not regex line terminators: inside a bank text they never cut the merchant. */
private val PF_SAFE_INVISIBLE = listOf(" ", " ", " ", " ", " ", " ", "　")

private fun pfAstral(rnd: Random): String {
    val v = rnd.nextInt(0x10000, 0x110000) - 0x10000
    return "" + Char(0xD800 + (v shr 10)) + Char(0xDC00 + (v and 0x3FF))
}

/** One hostile piece for a form field: lone surrogates, D28 chars, emoji, NFD, RTL, the table, ß İ ı, controls, breaks. */
private fun pfPiece(rnd: Random): String = when (rnd.nextInt(16)) {
    0 -> Char(rnd.nextInt(0xD800, 0xDC00)).toString()
    1 -> Char(rnd.nextInt(0xDC00, 0xE000)).toString()
    2 -> listOf("﻿", "￾", "￿", "�").random(rnd)
    3 -> listOf("😀", "👩‍👩‍👧", "🇧🇷", "❤️").random(rnd)
    4 -> "e" + Char(rnd.nextInt(0x300, 0x370)) + Char(rnd.nextInt(0x300, 0x370))
    5 -> listOf("‮", "‫", "‏", "⁦", "⁩", "שלום", "مرحبا").random(rnd)
    6 -> PF_LATIN.random(rnd).first.toString()
    7 -> listOf("ß", "ẞ", "İ", "ı", "Å", "K", "Æ", "Ø", "Ł").random(rnd)
    8 -> Char(rnd.nextInt(0, 0x20)).toString()
    9 -> PF_INVISIBLE.random(rnd)
    10 -> listOf("\n", "\r", "\r\n", " ", " ").random(rnd)
    11 -> listOf("'", "\"", "%", "_", "\\", "'; DROP TABLE planned;--", "\$x", "?").random(rnd)
    12 -> pfAstral(rnd)
    13 -> Char(rnd.nextInt(0x4E00, 0xA000)).toString()
    else -> Char(rnd.nextInt(0x21, 0x7F)).toString()
}

private fun pfHostile(rnd: Random, maxPieces: Int): String =
    buildString { repeat(rnd.nextInt(0, maxPieces + 1)) { append(pfPiece(rnd)) } }

/** Text for the fold: the table in both spellings, stacked marks, the special letters and the hostile mix. */
private fun pfFoldText(rnd: Random): String = buildString {
    repeat(rnd.nextInt(0, 21)) {
        append(
            when (rnd.nextInt(8)) {
                0 -> PF_LATIN.random(rnd).let { (pre, base, mark) -> if (rnd.nextBoolean()) pre.toString() else "$base$mark" }
                1 -> PF_LATIN.random(rnd).first.toString() + List(rnd.nextInt(1, 4)) { Char(rnd.nextInt(0x300, 0x370)) }.joinToString("")
                2 -> Char(rnd.nextInt(0x300, 0x370)).toString()
                3 -> PF_INVISIBLE.random(rnd)
                else -> pfPiece(rnd)
            },
        )
    }
}

/** A bank text piece: never a digit, '.', ';', '!', LF or ASCII space, so the generic extract keeps it in the merchant. */
private fun pfBankPiece(rnd: Random): String = when (rnd.nextInt(12)) {
    0 -> "abcdefghijklmnopqrstuvwxyz".random(rnd) + List(rnd.nextInt(1, 4)) { Char(rnd.nextInt(0x300, 0x370)) }.joinToString("")
    1 -> PF_LATIN.random(rnd).first.toString()
    2 -> listOf("ß", "ẞ", "İ", "ı", "I", "Æ", "Ø", "Đ", "Ł", "Œ", "Å", "K").random(rnd)
    3 -> listOf("😀", "👩‍👩‍👧", "🇧🇷", "❤️").random(rnd)
    4 -> listOf("‮", "‏", "⁦", "⁩", "שלום", "مرحبا").random(rnd)
    5 -> PF_SAFE_INVISIBLE.random(rnd)
    6 -> Char(if (rnd.nextBoolean()) rnd.nextInt(1, 9) else rnd.nextInt(0x0E, 0x20)).toString()
    7 -> "̧́"
    8 -> listOf("\uD800", "\uDC00").random(rnd)
    9 -> listOf("Ω", "Σ", "Ж", "ж").random(rnd)
    10 -> listOf("​", "﻿", "‍").random(rnd)
    else -> "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".random(rnd).toString()
}

private fun pfBankMerchant(rnd: Random): String = List(rnd.nextInt(1, 7)) { pfBankPiece(rnd) }.joinToString("")

/** A bank text meant to give an empty key: only invisibles and controls, or none at all (a Pix with no merchant). */
private fun pfEmptyKeyMerchant(rnd: Random): String =
    if (rnd.nextInt(4) == 0) "" else List(rnd.nextInt(1, 5)) { PF_INVISIBLE.random(rnd) }.joinToString("")

/** Letters only (a bank text takes no digit): unique per [i] < 676. */
private fun pfLetters(i: Int): String = "q" + ('a' + (i / 26) % 26) + ('a' + i % 26)

/** Spellings of the same bank text: case, NFD/NFC, invisibles in the middle, stacked marks. */
private fun pfVariant(rnd: Random, m: String): String = when (rnd.nextInt(6)) {
    0 -> m.uppercase()
    1 -> m.lowercase()
    2 -> pfToNfd(m)
    3 -> pfToNfc(pfToNfd(m))
    4 -> buildString {
        for (c in m) {
            append(c)
            if (!c.isHighSurrogate() && rnd.nextInt(3) == 0) append(PF_SAFE_INVISIBLE.random(rnd))
        }
    }
    else -> buildString {
        for (c in m) {
            append(c)
            if (c in 'a'..'z' || c in 'A'..'Z') append(Char(rnd.nextInt(0x300, 0x370)))
        }
    }
}

// ---- recados ----

private class PfRecado(val pkg: String, val key: String, val date: LocalDate, val minute: Int, val merchant: String)

/** A generic-bank text: the merchant after "em", or a Pix with no merchant at all. */
private fun pfText(merchant: String, reais: Int) = if (merchant.isEmpty()) "Pix de R$ $reais,00 enviado" else "Compra de R$ $reais,00 em $merchant"

/** Captures [recados] (posted at noon + minute, `when` set), then reads them back by key from pending(). */
private suspend fun pfCaptureAll(db: Db, what: String, recados: List<PfRecado>): Map<String, NoticeRow> {
    for ((i, r) in recados.withIndex()) {
        val at = r.date.atTime(12, 0).toInstant(PF_TZ).toEpochMilliseconds() + r.minute * 60_000L
        val raw = RawNotice(
            packageName = r.pkg, appLabel = PF_LABELS[r.pkg] ?: r.pkg, key = r.key, postedAt = at, whenMs = at,
            title = "Compra aprovada", text = pfText(r.merchant, 10 + i % 80),
        )
        assertTrue(pfNoThrow("$what: capture ${r.key}") { capture(db, raw) }, "$what: setup capture ${r.key} text=${pfShow(raw.text)}")
    }
    val rows = pfNoThrow("$what: pending()") { db.pending() }.associateBy { it.raw.key }
    return recados.associate { it.key to (rows[it.key] ?: fail("$what: setup recado ${it.key} missing from pending()")) }
}

private fun pfForm(
    budget: BudgetId,
    date: LocalDate,
    caption: String,
    repeats: Repeats = Repeats.ONCE,
    occurrence: OccurrenceId = OccurrenceId(0),
    planned: PlannedId = PlannedId(0),
    total: Long = 4590,
    merchant: String = "LOJA PREVISTO",
    tags: List<String> = listOf("casa"),
) = TransactionForm(
    budget = budget, tags = tags, total = Cents(total), installments = 1, merchant = merchant, date = date,
    time = LocalTime(12, 0), caption = caption, occurrence = occurrence, repeats = repeats, planned = planned,
)

// ---- sanity of what is read ----

private fun pfStrip(p: Planned) = p.copy(noticeInPauta = false)

/** One row of planned(today) / NoticeRow.planned against the KDoc of Planned. */
private fun pfSanePlanned(what: String, p: Planned, today: LocalDate, budgets: Map<BudgetId, String>) {
    val w = "$what: Planned(occurrence=${p.occurrence.v}, planned=${p.planned.v}, name=${pfShow(p.name)}, date=${p.date}, day=${p.day})"
    assertTrue(p.occurrence.v > 0, "$w: occurrence id")
    assertTrue(p.planned.v > 0, "$w: planned id")
    assertTrue(p.name.length <= PF_CAP, "$w: a name over 80 units (D62)")
    assertEquals(pfClean(p.name), p.name, "$w: the name is not clean like a legenda (D53, D62)")
    assertEquals(budgets[p.budget], p.budgetName, "$w: budgetName of budget ${p.budget.v}")
    assertTrue(p.tags.isNotEmpty(), "$w: a previsto keeps at least one tag")
    assertEquals(p.tags.distinct(), p.tags, "$w: repeated tags")
    if (p.tags.all { t -> t.all { it < '\uD800' } }) assertEquals(p.tags.sorted(), p.tags, "$w: tags not sorted")
    assertTrue(p.cents.v in 1..PF_MAX, "$w: cents ${p.cents.v}")
    assertTrue(p.day in 1..31, "$w: day")
    assertEquals(pfClamp(p.date.yearMonth, p.day), p.date, "$w: the date is the day in its month, clamped to the last day (P8)")
    val next = if (p.repeats == Repeats.MONTHLY) pfClamp(p.date.yearMonth.plusMonth(), p.day) else NO_DATE
    assertEquals(next, p.next, "$w: next (${p.repeats})")
    assertEquals(p.linked, p.linkText.isNotEmpty(), "$w: linkText is \"\" exactly when not linked")
    assertEquals(p.linked, p.linkSource.isNotEmpty(), "$w: linkSource is \"\" exactly when not linked")
    if (p.linked) assertTrue(MerchantKey.of(p.linkText).v.isNotEmpty(), "$w: linked from a bank text whose key is empty (R2)")
    assertEquals(p.date < today, p.late, "$w: late against $today")
}

/** The whole planned(today) list: every row sane, oldest date first (ties: occurrence id), one open row per previsto and month. */
private fun pfSaneList(what: String, list: List<Planned>, today: LocalDate, budgets: Map<BudgetId, String>) {
    for (p in list) pfSanePlanned(what, p, today, budgets)
    val order = list.sortedWith(compareBy<Planned>({ it.date }, { it.occurrence.v }))
    assertEquals(order.map { it.occurrence }, list.map { it.occurrence }, "$what: planned() is not oldest date first, ties by occurrence id")
    assertEquals(list.size, list.map { it.occurrence }.toSet().size, "$what: an occurrence listed twice")
    val months = list.map { it.planned to it.date.yearMonth }
    assertEquals(months.size, months.toSet().size, "$what: two open occurrences of one previsto in one calendar month (D59)")
}

/**
 * Recognition sanity on any pending list (D60, R2): an empty key never recognizes; a recognized recado is
 * recognized as an open occurrence of a previsto linked to its fonte and key, within 10 days, and that
 * occurrence says noticeInPauta.
 */
private fun pfSanePending(what: String, rows: List<NoticeRow>, open: List<Planned>) {
    val byOcc = open.associateBy { it.occurrence }
    for (r in rows) {
        val key = MerchantKey.of(r.parsed.merchant)
        val w = "$what: recado ${r.raw.key} (${r.parsed.date}) bank text ${pfShow(r.parsed.merchant)}"
        if (key.v.isEmpty() || r.planned.occurrence.v == 0L) {
            assertEquals(Planned(), r.planned, "$w: must not be recognized (empty key, R2) or must be all zero")
            continue
        }
        val p = byOcc[r.planned.occurrence] ?: fail("$w: recognized as occurrence ${r.planned.occurrence.v}, which planned() doesn't list as open")
        assertEquals(pfStrip(p).copy(late = false), pfStrip(r.planned).copy(late = false), "$w: NoticeRow.planned differs from the planned() row (D62)")
        assertTrue(p.linked, "$w: recognized as an unlinked previsto")
        assertEquals(key, MerchantKey.of(p.linkText), "$w: recognized by a previsto linked to another key")
        assertEquals(PF_LABELS[r.raw.packageName], p.linkSource, "$w: recognized by a previsto linked to another fonte")
        assertTrue(pfDays(r.parsed.date, p.date) <= 10, "$w: recognized as an occurrence on ${p.date}, over 10 days away")
    }
    val recognized = rows.map { it.planned.occurrence }.filter { it.v != 0L }.toSet()
    for (p in open) assertEquals(p.occurrence in recognized, p.noticeInPauta, "$what: noticeInPauta of ${pfShow(p.name)} on ${p.date}")
}

/**
 * The exact recognition of the KDoc of NoticeRow.planned, given the links the test made: same fonte and same
 * MerchantKey of the bank text as a linked previsto, its open occurrence within 10 days of the recado's date,
 * the closest (ties: earlier date, then lower occurrence id); zero when none and always zero for an empty key.
 * Returns how many recados were recognized.
 */
private suspend fun pfCheckRecognition(
    db: Db,
    what: String,
    today: LocalDate,
    links: Map<PlannedId, Pair<String, MerchantKey>>,
    budgets: Map<BudgetId, String>,
): Int {
    val rows = pfNoThrow("$what: pending($today)") { db.pending(today) }
    val open = pfNoThrow("$what: planned($today)") { db.planned(today) }
    pfSaneList(what, open, today, budgets)
    var count = 0
    val expectedOcc = HashSet<OccurrenceId>()
    for (r in rows) {
        val key = MerchantKey.of(r.parsed.merchant)
        val w = "$what: recado ${r.raw.key} (${r.raw.packageName}, ${r.parsed.date}) bank text ${pfShow(r.parsed.merchant)} key ${pfShow(key.v)}"
        if (key.v.isEmpty()) {
            assertEquals(Planned(), r.planned, "$w: a bank text with an empty key is never recognized (R2)")
            continue
        }
        val want = open
            .filter { p -> links[p.planned] == (r.raw.packageName to key) && pfDays(r.parsed.date, p.date) <= 10 }
            .minWithOrNull(compareBy<Planned>({ pfDays(r.parsed.date, it.date) }, { it.date }, { it.occurrence.v }))
        if (want == null) {
            assertEquals(Planned(), r.planned, "$w: recognized as occurrence ${r.planned.occurrence.v} ${pfShow(r.planned.name)} but no linked previsto matches")
        } else {
            assertEquals(pfStrip(want), pfStrip(r.planned), "$w: must be the closest open occurrence of the previsto linked to its fonte and key (D60)")
            count++
            expectedOcc += want.occurrence
        }
    }
    for (p in open) assertEquals(p.occurrence in expectedOcc, p.noticeInPauta, "$what: noticeInPauta of ${pfShow(p.name)} on ${p.date}")
    val plain = pfNoThrow("$what: pending()") { db.pending() }
    assertEquals(
        rows.map { it.id to it.planned.occurrence }, plain.map { it.id to it.planned.occurrence },
        "$what: pending() without today recognizes other occurrences than pending($today)",
    )
    return count
}

// ---- the form engine ----

private class PfWorld(
    val budgets: Map<BudgetId, String>,
    /** Recados still pending (the model). */
    val pending: MutableList<NoticeId>,
    /** Parsed.merchant of every recado: the bank text a link is made from. */
    val bankText: Map<NoticeId, String>,
    val noticeDate: Map<NoticeId, LocalDate>,
    /** Every lançamento and its recado (0 = manual). */
    val txs: LinkedHashMap<TransactionId, NoticeId> = LinkedHashMap(),
    val previstos: LinkedHashSet<PlannedId> = LinkedHashSet(),
    /** Occurrences closed by a lançamento or skipped. */
    val closed: MutableList<OccurrenceId> = ArrayList(),
    val accepted: MutableList<NoticeId> = ArrayList(),
)

/**
 * The name a previsto may take (TransactionForm.repeats / .planned, Db.addManual, D64), cleaned like the caption.
 * Caption when it cleans to non-empty. Else Editar previsto: the merchant. Else, with a non-blank alias: on
 * accept/edit, "the line's" name is the alias when the merchant has a key (the alias is then saved); addManual's
 * KDoc says "caption, else merchant" where TransactionForm.repeats says "caption, else alias, else bank text",
 * so both readings are allowed there.
 */
private fun pfNameReadings(path: String, kind: String, f: TransactionForm): Set<String> {
    val cap = pfClean(f.caption)
    if (cap.isNotEmpty()) return setOf(cap)
    val m = pfClean(f.merchant)
    if (path == "E" || f.alias.isBlank()) return setOf(m)
    val a = pfClean(f.alias)
    return if (kind != "addManual" && MerchantKey.of(f.merchant).v.isNotEmpty()) setOf(a) else setOf(a, m)
}

private fun pfGenForm(rnd: Random, i: Int, h: Int, kind: String, w: PfWorld, open: List<Planned>, noticeDate: LocalDate?): TransactionForm {
    fun bad() = rnd.nextInt(100) < h
    val monthly = rnd.nextInt(100) < 40
    val budget = if (bad()) {
        listOf(BudgetId(0), BudgetId(-1), BudgetId(999_999), BudgetId(Long.MAX_VALUE), BudgetId(Long.MIN_VALUE)).random(rnd)
    } else {
        w.budgets.keys.random(rnd)
    }
    val tags = when {
        rnd.nextInt(100) < h / 2 -> listOf(emptyList<String>(), listOf(""), listOf(" ", "\t", "  "), List(5) { " \t " }).random(rnd)
        rnd.nextInt(20) == 0 -> List(rnd.nextInt(200, 320)) { val n = rnd.nextInt(250); listOf("t$n", "T$n", " t$n ", "", " ").random(rnd) }
        rnd.nextInt(6) == 0 -> List(rnd.nextInt(1, 5)) { pfHostile(rnd, 3) }
        else -> listOf(listOf("casa"), listOf("mercado", "Casa"), listOf(" luz ", "LUZ", "conta")).random(rnd)
    }
    val total = if (bad()) {
        listOf(0L, -1L, -100L, Long.MIN_VALUE, PF_MAX + 1, Long.MAX_VALUE).random(rnd)
    } else {
        when (rnd.nextInt(10)) {
            0 -> 1L
            1 -> PF_MAX
            else -> rnd.nextLong(1, 1_000_000)
        }
    }
    val installments = if (bad()) {
        listOf(0, -1, 401, Int.MAX_VALUE, Int.MIN_VALUE).random(rnd)
    } else {
        when (rnd.nextInt(10)) {
            in 0..6 -> 1
            7 -> 400
            else -> rnd.nextInt(2, 13)
        }
    }
    val date = when {
        bad() && rnd.nextBoolean() -> PF_BEYOND.randomOrNull(rnd) ?: LocalDate(9999, 12, 31)
        bad() -> if (monthly) {
            // No MONTHLY in the far past here: a year-one previsto has 24 000 occurrences (own test below).
            listOf(LocalDate(9999, 12, 31), LocalDate(9999, 12, 1), LocalDate(9999, 11, 30)).random(rnd)
        } else {
            listOf(LocalDate(1, 1, 1), LocalDate(1, 1, 31), LocalDate(9999, 12, 31), LocalDate(9999, 12, 1), LocalDate(9999, 11, 30), LocalDate(9999, 6, 15)).random(rnd)
        }
        kind == "accept" && noticeDate != null && rnd.nextBoolean() -> noticeDate
        kind == "addManual" && rnd.nextInt(3) == 0 -> PF_TODAY.plus(rnd.nextInt(1, 151), DateTimeUnit.DAY)
        else -> PF_TODAY.plus(rnd.nextInt(-300, 91), DateTimeUnit.DAY)
    }
    val merchant = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> List(rnd.nextInt(1, 4)) { PF_INVISIBLE.random(rnd) }.joinToString("")
        2 -> "x".repeat(rnd.nextInt(75, 300)) + "~$i~"
        else -> pfHostile(rnd, 5) + "~$i~" // unique key per step: no alias from an earlier step
    }
    val alias = when (rnd.nextInt(20)) {
        in 0..13 -> ""
        in 14..16 -> pfHostile(rnd, 4)
        17, 18 -> "a".repeat(rnd.nextInt(75, 200))
        else -> PF_INVISIBLE.random(rnd)
    }
    val caption = when (rnd.nextInt(20)) {
        in 0..6 -> ""
        in 7..10 -> "Conta $i"
        in 11..13 -> pfHostile(rnd, 6)
        14, 15 -> List(rnd.nextInt(1, 5)) { (PF_INVISIBLE + listOf(" ", "\t", "\n", "\r\n", " ")).random(rnd) }.joinToString("")
        16, 17 -> "y".repeat(rnd.nextInt(81, 300)) + pfHostile(rnd, 2)
        else -> " " + "c".repeat(rnd.nextInt(78, 83)) + "\r\n"
    }
    val occurrence = when (rnd.nextInt(100)) {
        in 0..59 -> OccurrenceId(0)
        in 60..79 -> open.randomOrNull(rnd)?.occurrence ?: OccurrenceId(0)
        in 80..89 -> w.closed.randomOrNull(rnd) ?: OccurrenceId(-1)
        else -> listOf(OccurrenceId(-1), OccurrenceId(999_999_999), OccurrenceId(Long.MAX_VALUE), OccurrenceId(Long.MIN_VALUE)).random(rnd)
    }
    val planned = when (rnd.nextInt(100)) {
        in 0..(if (kind == "addManual") 14 else 9) -> w.previstos.randomOrNull(rnd) ?: PlannedId(0)
        in 85..89 -> listOf(PlannedId(-1), PlannedId(999_999_999), PlannedId(Long.MAX_VALUE), PlannedId(Long.MIN_VALUE)).random(rnd)
        else -> PlannedId(0)
    }
    return TransactionForm(
        budget = budget, tags = tags, total = Cents(total), installments = installments, merchant = merchant, date = date,
        time = LocalTime(rnd.nextInt(24), rnd.nextInt(60)), alias = alias, caption = caption, occurrence = occurrence,
        repeats = if (monthly) Repeats.MONTHLY else Repeats.ONCE, planned = planned,
    )
}

/** Every open row of one previsto against the form that made or edited it. [link] = (linked, linkText); null = not checked. */
private fun pfCheckRule(what: String, rows: List<Planned>, names: Set<String>, f: TransactionForm, repeats: Repeats, link: Pair<Boolean, String>?) {
    val tags = pfTags(f.tags).toSet()
    for (p in rows) {
        val w = "$what: previsto ${p.planned.v} occurrence ${p.occurrence.v} on ${p.date}"
        assertTrue(p.name in names, "$w: name ${pfShow(p.name)}, expected one of ${names.map(::pfShow)} (D64)")
        assertEquals(f.budget, p.budget, "$w: budget")
        assertEquals(tags, p.tags.toSet(), "$w: tags")
        assertEquals(f.total, p.cents, "$w: value")
        assertEquals(f.date.day, p.day, "$w: day of the form's date")
        assertEquals(repeats, p.repeats, "$w: repeats")
        if (link != null) {
            assertEquals(link.first, p.linked, "$w: linked")
            assertEquals(if (link.first) link.second else "", p.linkText, "$w: linkText (the recado's bank text, R4b)")
            assertEquals(if (link.first) PF_LABEL else "", p.linkSource, "$w: linkSource")
        }
    }
}

/**
 * One hostile write and the whole contract around it: nothing throws; what the KDoc refuses comes back 0 /
 * false / Saved() with every read as it was and version unmoved; what it doesn't refuse is accepted and reads
 * back sane; combinations the KDoc leaves open (see the report) may go either way but never throw.
 */
private suspend fun pfStep(db: Db, what0: String, w: PfWorld, kind: String, notice: NoticeId, editId: TransactionId, today: LocalDate, form: TransactionForm, before: List<Planned>) {
    val what = "$what0 $kind ${pfShowForm(form)}" + when (kind) {
        "accept" -> " notice=${notice.v}"
        "edit" -> " id=${editId.v}"
        else -> " today=$today"
    }
    val open = before.map { it.occurrence }.toSet()
    val path = when {
        kind == "addManual" && form.planned.v != 0L -> "E" // Editar previsto
        kind == "addManual" && today != NO_DATE && form.date > today -> "F" // Salvar previsto
        else -> "L" // a lançamento
    }
    val refuse = ArrayList<String>()
    val ambiguous = ArrayList<String>()
    if (kind == "accept" && notice !in w.pending) refuse += "the recado is not pending"
    if (kind == "edit" && editId !in w.txs) refuse += "unknown transaction"
    if (form.budget !in w.budgets) refuse += "unknown budget"
    if (pfTags(form.tags).isEmpty()) refuse += "no tag after cleaning"
    if (form.total.v !in 1..PF_MAX) refuse += "total out of 1..MAX_CENTS"
    if (form.installments !in 1..400) {
        refuse += "installments out of 1..400"
    } else if (!pfInRange(form.date, form.installments)) {
        if (path == "L" || form.date.year !in 1..9999) refuse += "an installment dated out of 0001..9999" else ambiguous += "later installments out of range on a previsto-only save"
    }
    if (pfClean(form.caption).length > PF_CAP) refuse += "caption over 80 after cleaning"
    if (path == "L" && kind != "edit" && form.occurrence.v != 0L && form.occurrence !in open) refuse += "occurrence not open"
    if (kind == "edit" && form.occurrence.v != 0L) ambiguous += "occurrence on edit"
    if (path != "L" && form.occurrence.v != 0L) ambiguous += "occurrence on a previsto-only save"
    if (path == "E" && form.planned !in w.previstos) refuse += "unknown previsto"
    val previsto = path != "L" || form.repeats == Repeats.MONTHLY
    val names = if (previsto) pfNameReadings(path, kind, form) else emptySet()
    if (previsto) {
        if (names.all { it.length > PF_CAP }) refuse += "previsto name over 80 after cleaning"
        else if (names.any { it.length > PF_CAP }) ambiguous += "a name reading over 80"
        if (names.any { it.isEmpty() }) ambiguous += "blank previsto name"
        if (path == "L" && form.repeats == Repeats.MONTHLY && form.date.yearMonth >= YearMonth(9999, 12)) ambiguous += "first occurrence after 9999-12"
    }
    val mustRefuse = refuse.isNotEmpty()
    val mustAccept = refuse.isEmpty() && ambiguous.isEmpty()
    val why = "path=$path refuse=$refuse ambiguous=$ambiguous names=${names.map(::pfShow)}"

    val rows0 = db.transactions()
    val pending0 = db.pending()
    val tags0 = if (mustAccept) null else db.tags()
    val detail0 = if (kind == "edit") db.transaction(editId) else null
    val v0 = db.version.value

    var tx = TransactionId(0)
    var saved = Saved()
    val wrote: Boolean
    when (kind) {
        "accept" -> {
            tx = pfNoThrow(what) { db.accept(notice, form) }
            wrote = tx.v != 0L
        }
        "addManual" -> {
            saved = pfNoThrow(what) { db.addManual(form, today) }
            tx = saved.transaction
            wrote = saved.transaction.v != 0L || saved.planned.v != 0L
        }
        else -> {
            wrote = pfNoThrow(what) { db.edit(editId, form) }
            if (wrote) tx = editId
        }
    }
    val after = pfNoThrow("$what: planned(HORIZON) after") { db.planned(PF_HORIZON) }
    pfSaneList("$what: after", after, PF_HORIZON, w.budgets)

    if (!wrote) {
        if (mustAccept) fail("$what: must be accepted ($why) but was refused")
        assertEquals(v0, db.version.value, "$what: a refused write bumped version ($why)")
        assertEquals(rows0, db.transactions(), "$what: a refused write changed transactions() ($why)")
        assertEquals(pending0, db.pending(), "$what: a refused write changed pending() ($why)")
        assertEquals(before, after, "$what: a refused write changed planned() ($why)")
        assertEquals(tags0, db.tags(), "$what: a refused write changed tags() ($why)")
        if (detail0 != null) assertEquals(detail0, db.transaction(editId), "$what: a refused edit changed the transaction ($why)")
        return
    }
    if (mustRefuse) fail("$what: must be refused ($why) but was written (transaction=${tx.v}, planned=${saved.planned.v})")
    if (kind != "edit") assertTrue(db.version.value > v0, "$what: an accepted write must bump version")

    // The lançamento, or none.
    when (path) {
        "L" -> {
            assertNotEquals(0L, tx.v, "$what: a lançamento must come back ($why)")
            if (kind == "addManual") assertEquals(form.repeats == Repeats.MONTHLY, saved.planned.v != 0L, "$what: Saved.planned with ${form.repeats} (D64)")
            val row = db.transactions().firstOrNull { it.id == tx } ?: fail("$what: transaction ${tx.v} missing from transactions()")
            assertEquals(form.budget, row.budget, "$what: budget read back")
            assertEquals(form.total, row.total, "$what: total read back")
            assertEquals(form.installments, row.installments, "$what: installments read back")
            assertEquals(pfTags(form.tags).toSet(), row.tags.toSet(), "$what: tags read back")
            assertEquals(pfClean(form.caption), row.caption, "$what: caption read back")
            if (kind != "edit") assertTrue(rows0.none { it.id == tx }, "$what: new id ${tx.v} was in use")
            when (kind) {
                "accept" -> {
                    w.pending.remove(notice)
                    w.accepted += notice
                    w.txs[tx] = notice
                }
                "addManual" -> w.txs[tx] = NoticeId(0)
            }
        }
        "F" -> {
            assertEquals(0L, saved.transaction.v, "$what: Salvar previsto writes no lançamento (D64)")
            assertNotEquals(0L, saved.planned.v, "$what: Salvar previsto returns the previsto")
            assertEquals(rows0, db.transactions(), "$what: Salvar previsto changed transactions()")
        }
        else -> {
            assertEquals(Saved(planned = form.planned), saved, "$what: Editar previsto returns Saved(planned) only")
            assertEquals(rows0, db.transactions(), "$what: Editar previsto changed transactions()")
        }
    }
    assertEquals(
        pending0.map { it.id }.filter { kind != "accept" || it != notice }, db.pending().map { it.id },
        "$what: pending() ids after the write",
    )

    // The occurrence it closed, or none.
    val closedNow = if (path == "L" && kind != "edit" && form.occurrence.v != 0L) form.occurrence else OccurrenceId(0)
    val closedRow = before.firstOrNull { it.occurrence == closedNow }
    if (closedNow.v != 0L) {
        assertTrue(after.none { it.occurrence == closedNow }, "$what: occurrence ${closedNow.v} still open after the lançamento closed it")
        w.closed += closedNow
    }
    val relinkText = if (kind == "accept") w.bankText[notice] ?: "" else ""
    val relinked = closedRow != null && MerchantKey.of(relinkText).v.isNotEmpty()
    if (closedRow != null) {
        val rest = after.filter { it.planned == closedRow.planned }
        if (relinked) {
            for (p in rest) {
                assertTrue(p.linked, "$what: previsto ${p.planned.v} must be (re)linked from the recado's bank text (D62)")
                assertEquals(pfStored(relinkText), p.linkText, "$what: previsto ${p.planned.v} linkText after the relink")
                assertEquals(PF_LABEL, p.linkSource, "$what: previsto ${p.planned.v} linkSource after the relink")
            }
        } else {
            val old = before.filter { it.planned == closedRow.planned && it.occurrence != closedNow }
            assertEquals(old.map(::pfStrip), rest.map(::pfStrip), "$what: closing without a key (or by hand) must not touch the previsto's link (R2, R6b)")
        }
    }

    // Previstos: the one it created or edited, and nobody else.
    val beforeIds = before.map { it.planned }.toSet()
    val edited = if (path == "E") form.planned else PlannedId(0)
    val created = after.map { it.planned }.toSet() - beforeIds - edited
    val touched = setOf(edited) + (if (relinked) setOf(closedRow.planned) else emptySet())
    assertEquals(
        before.filter { it.planned !in touched && it.occurrence != closedNow }.map(::pfStrip),
        after.filter { it.planned !in touched && it.planned !in created }.map(::pfStrip),
        "$what: previstos the write doesn't touch changed",
    )
    when (path) {
        "L" -> if (form.repeats == Repeats.ONCE) {
            assertEquals(emptySet(), created, "$what: Só uma vez creates no previsto")
        } else {
            val first = pfFirstOcc(form.date)
            // A first occurrence past today + 10 may or may not exist yet (lazy R1 vs. made at save): not asserted.
            if (first <= PF_HORIZON_REACH && closedNow.v == 0L) assertEquals(1, created.size, "$what: Todo mês creates exactly one previsto")
            else assertTrue(created.size <= 1, "$what: Todo mês creates at most one previsto")
            run {
                for (p in created) {
                    if (kind == "addManual") assertEquals(saved.planned, p, "$what: Saved.planned is the previsto that appeared")
                    val rows = after.filter { it.planned == p }
                    assertEquals(first, rows.first().date, "$what: first occurrence in the calendar month after the date's (D64)")
                    val linkNotice = when (kind) {
                        "accept" -> notice
                        "edit" -> w.txs[editId] ?: NoticeId(0)
                        else -> NoticeId(0)
                    }
                    val text = w.bankText[linkNotice] ?: ""
                    val linked = linkNotice.v != 0L && MerchantKey.of(text).v.isNotEmpty()
                    pfCheckRule("$what: Todo mês", rows, names, form, Repeats.MONTHLY, linked to pfStored(text))
                    w.previstos += p
                }
            }
            if (kind == "addManual") w.previstos += saved.planned
        }
        "F" -> {
            assertTrue(saved.planned !in beforeIds && saved.planned !in w.previstos, "$what: Salvar previsto reused previsto id ${saved.planned.v}")
            if (form.date <= PF_HORIZON_REACH) assertEquals(setOf(saved.planned), created, "$what: Salvar previsto shows exactly its previsto")
            else assertTrue(created.isEmpty() || created == setOf(saved.planned), "$what: Salvar previsto shows at most its previsto")
            if (created.isNotEmpty()) {
                val rows = after.filter { it.planned == saved.planned }
                assertEquals(form.date, rows.first().date, "$what: first occurrence on the chosen date (D64)")
                pfCheckRule("$what: Salvar previsto", rows, names, form, form.repeats, false to "")
            }
            w.previstos += saved.planned
        }
        else -> {
            assertEquals(emptySet(), created, "$what: Editar previsto creates no other previsto")
            val old = before.firstOrNull { it.planned == edited }
            pfCheckRule("$what: Editar previsto", after.filter { it.planned == edited }, names, form, form.repeats, old?.let { it.linked to it.linkText })
        }
    }
}

/** The whole state: reads that create occurrences are stable and don't bump version; recognition sane; the model. */
private suspend fun pfSweep(db: Db, what: String, w: PfWorld) {
    val v0 = db.version.value
    val a = pfNoThrow("$what: planned(HORIZON)") { db.planned(PF_HORIZON) }
    val b = pfNoThrow("$what: planned(HORIZON) again") { db.planned(PF_HORIZON) }
    assertEquals(a, b, "$what: planned(HORIZON) twice in a row differs")
    pfSaneList(what, a, PF_HORIZON, w.budgets)
    val p = db.pending()
    assertEquals(w.pending.toSet(), p.map { it.id }.toSet(), "$what: pending() ids against the model")
    pfSanePending(what, p, a)
    val pt = pfNoThrow("$what: pending(today)") { db.pending(PF_TODAY) }
    // Triage 2026-10-22 (user's decision, D59): with a today, an occurrence dated past today + 10 isn't shown, so
    // pending(today) can't recognize it; pending() (no today) still does. Same otherwise.
    val due = LocalDate.fromEpochDays(PF_TODAY.toEpochDays() + 10)
    assertEquals(
        p.map { it.id to if (it.planned.date > due) OccurrenceId(0) else it.planned.occurrence }, pt.map { it.id to it.planned.occurrence },
        "$what: pending(today) and pending() recognize differently",
    )
    assertEquals(w.txs.keys, db.transactions().map { it.id }.toSet(), "$what: transactions() ids against the model")
    for (n in w.accepted.takeLast(3) + p.take(3).map { it.id }) {
        assertEquals(Planned(), db.notice(n).planned, "$what: NoticeRow.planned of notice(${n.v}) must be zero (only pending() fills it)")
    }
    assertEquals(v0, db.version.value, "$what: planned(today) / pending(today) bumped version")
}

/** A whole fuzz run: hostile recados and three seed previstos, then [iterations] hostile writes, swept every 25. */
private suspend fun pfRun(seed: Long, iterations: Int, h: Int) = pfWithDb { db ->
    val rnd = Random(seed)
    assertTrue(db.addSource(PF_PKG, PF_LABEL, PF_SETUP_AT), "seed=$seed setup: addSource")
    val casa = db.createBudget("Casa", Cents(500_000), PF_TODAY)
    val mercado = db.createBudget("Mercado", Cents(300_000), PF_TODAY)
    assertTrue(casa.v != 0L && mercado.v != 0L, "seed=$seed setup: createBudget")
    val recados = ArrayList<PfRecado>()
    for (i in 0 until 110) {
        val family = rnd.nextInt(10)
        val m = when (family) {
            0, 1 -> pfEmptyKeyMerchant(rnd)
            2 -> ""
            else -> pfBankMerchant(rnd) + pfLetters(i)
        }
        val date = PF_TODAY.plus(rnd.nextInt(-20, 40), DateTimeUnit.DAY)
        recados += PfRecado(PF_PKG, "a|$i", date, i, m)
        // Same text a month later: recognized once the first one is lançado as Todo mês on its own date.
        if (family >= 3 && rnd.nextInt(3) == 0) recados += PfRecado(PF_PKG, "a|$i|twin", date.plus(1, DateTimeUnit.MONTH), i, m)
    }
    val captured = pfCaptureAll(db, "seed=$seed setup", recados)
    val w = PfWorld(
        budgets = mapOf(casa to "Casa", mercado to "Mercado"),
        pending = captured.values.map { it.id }.toMutableList(),
        bankText = captured.values.associate { it.id to it.parsed.merchant },
        noticeDate = captured.values.associate { it.id to it.parsed.date },
    )
    for ((date, caption, repeats) in listOf(
        Triple(PF_TODAY.minus(40, DateTimeUnit.DAY), "Aluguel", Repeats.MONTHLY),
        Triple(PF_TODAY.plus(5, DateTimeUnit.DAY), "Internet", Repeats.ONCE),
        Triple(PF_TODAY.plus(20, DateTimeUnit.DAY), "Luz", Repeats.MONTHLY),
    )) {
        val s = pfNoThrow("seed=$seed setup previsto $caption") { db.addManual(pfForm(casa, date, caption, repeats), PF_TODAY) }
        assertNotEquals(0L, s.planned.v, "seed=$seed setup previsto $caption: $s")
        if (s.transaction.v != 0L) w.txs[s.transaction] = NoticeId(0)
        w.previstos += s.planned
    }
    pfSweep(db, "seed=$seed setup sweep", w)

    repeat(iterations) { i ->
        val before = pfNoThrow("seed=$seed i=$i planned(HORIZON)") { db.planned(PF_HORIZON) }
        val roll = rnd.nextInt(100)
        if (roll < 4) {
            val o = before.randomOrNull(rnd)?.occurrence
            if (o != null) {
                assertTrue(pfNoThrow("seed=$seed i=$i skipOccurrence(${o.v})") { db.skipOccurrence(o) }, "seed=$seed i=$i: skipping open occurrence ${o.v}")
                assertTrue(db.planned(PF_HORIZON).none { it.occurrence == o }, "seed=$seed i=$i: skipped occurrence ${o.v} still listed")
                w.closed += o
            }
        } else {
            val kind = when {
                roll < 40 -> "accept"
                roll < 80 || w.txs.isEmpty() -> "addManual"
                else -> "edit"
            }
            val notice = if (kind != "accept") {
                NoticeId(0)
            } else if (rnd.nextInt(100) < 85 && w.pending.isNotEmpty()) {
                w.pending.random(rnd)
            } else {
                listOf(NoticeId(0), NoticeId(-1), NoticeId(999_999), w.accepted.randomOrNull(rnd) ?: NoticeId(Long.MAX_VALUE)).random(rnd)
            }
            val editId = when {
                kind != "edit" -> TransactionId(0)
                rnd.nextInt(100) < 85 -> w.txs.keys.random(rnd)
                else -> listOf(TransactionId(0), TransactionId(-1), TransactionId(Long.MAX_VALUE)).random(rnd)
            }
            val today = if (kind == "addManual" && rnd.nextInt(10) == 0) NO_DATE else PF_TODAY
            val form = pfGenForm(rnd, i, h, kind, w, before, w.noticeDate[notice])
            pfStep(db, "seed=$seed i=$i", w, kind, notice, editId, today, form, before)
        }
        if (i % 25 == 24) pfSweep(db, "seed=$seed i=$i sweep", w)
    }
    pfSweep(db, "seed=$seed end", w)
}

/** A write that must be refused: false/zero, and every read and version as they were. */
private suspend fun pfRefused(db: Db, what: String, write: suspend () -> Boolean) {
    val p0 = db.planned(PF_HORIZON)
    val t0 = db.transactions()
    val n0 = db.pending()
    val g0 = db.tags()
    val v0 = db.version.value
    assertTrue(!pfNoThrow(what) { write() }, "$what: must be refused")
    assertEquals(v0, db.version.value, "$what: a refused write bumped version")
    assertEquals(t0, db.transactions(), "$what: a refused write changed transactions()")
    assertEquals(n0, db.pending(), "$what: a refused write changed pending()")
    assertEquals(p0, db.planned(PF_HORIZON), "$what: a refused write changed planned()")
    assertEquals(g0, db.tags(), "$what: a refused write changed tags()")
}

class PrevistoFuzzTest {

    // ---- fold (D61) ----

    @Test
    fun `fold maps every letter of the Latin table and its NFD spelling to its base letter`() {
        assertEquals(161, PF_LATIN.size, "setup: the Latin table of the KDoc")
        assertEquals(161, PF_LATIN.map { it.first }.toSet().size, "setup: a letter twice in the table")
        assertTrue(PF_LATIN.all { it.first in 'À'..'ſ' }, "setup: a letter outside U+00C0–U+017F")
        val rnd = Random(0xF01DL)
        for ((pre, base, mark) in PF_LATIN) {
            val want = base.lowercaseChar().toString()
            val w = "U+${pre.code.toString(16).uppercase()} ${pfShow(pre.toString())}"
            assertEquals(want, pfPure(w) { fold(pre.toString()) }, "$w: fold of the precomposed letter (the KDoc table)")
            assertEquals(want, pfPure(w) { fold("$base$mark") }, "$w: fold of its NFD spelling")
            val extra = Char(rnd.nextInt(0x300, 0x370))
            assertEquals(want, fold("$pre$extra"), "$w: a stacked mark after the precomposed letter is dropped")
            assertEquals(want, fold("$base$mark$extra$extra"), "$w: stacked marks after the NFD spelling are dropped")
        }
        assertEquals("ss", fold("ß"), "ß folds to ss")
        assertEquals("i", fold("ı"), "dotless ı folds to i")
        assertEquals("strasse", fold("STRAßE"), "STRAßE")
        assertEquals("sao joao", fold("SÃO JOÃO"), "SÃO JOÃO, NFC")
        assertEquals("sao joao", fold("SÃO JOÃO"), "SÃO JOÃO, NFD")
        assertEquals("istanbul", fold("İSTANBUL"), "İSTANBUL")
        assertEquals("abc xyz 019 !~", fold("ABC xyz 019 !~"), "ASCII is only lowercased")
        // The documented limit: no decomposition (and every script outside Latin) is only lowercased.
        for ((from, to) in listOf("Æ" to "æ", "Ø" to "ø", "Đ" to "đ", "Ł" to "ł", "Œ" to "œ", "Þ" to "þ", "Ð" to "ð", "Ω" to "ω", "Ж" to "ж")) {
            assertEquals(to, fold(from), "$from is only lowercased (documented limit)")
        }
        assertEquals("\uD800", fold("\uD800"), "a lone high surrogate passes")
        assertEquals("a\uDC00b", fold("a\uDC00B"), "a lone low surrogate passes")
        assertEquals("😀", fold("😀"), "an astral pair passes")
        assertEquals("", fold(""), "empty")
        assertEquals("", fold("́̂ͯ"), "marks alone fold to nothing")
    }

    @Test
    fun `fold never throws on hostile text and is idempotent char by char`() {
        for (seed in listOf(0xF01D1L, 0xF01D2L)) {
            val rnd = Random(seed)
            repeat(3000) { i ->
                val s = if (i % 500 == 499) pfHostile(rnd, 40).repeat(50) else pfFoldText(rnd)
                val w = "seed=$seed i=$i s=${pfShow(s)}"
                val f = pfPure(w) { fold(s) }
                assertEquals(f, pfPure("$w: fold(fold)") { fold(f) }, "$w: fold(fold(x)) == fold(x)")
                assertTrue(f.none { it in '̀'..'ͯ' }, "$w: a combining mark U+0300–U+036F left in ${pfShow(f)}")
                assertEquals(f, fold(pfToNfd(s)), "$w: the NFD spelling of the table folds the same")
                assertEquals(f, fold(pfToNfc(s)), "$w: the NFC spelling of the table folds the same")
                val cut = rnd.nextInt(0, s.length + 1)
                val splitsPair = cut in 1 until s.length && s[cut - 1].isHighSurrogate() && s[cut].isLowSurrogate()
                if (!splitsPair) {
                    assertEquals(f, fold(s.substring(0, cut)) + fold(s.substring(cut)), "$w: one pass per char, cut at $cut")
                }
                val ascii = s.filter { it in ' '..'~' }
                assertEquals(ascii.lowercase(), fold(ascii), "$w: ASCII is only lowercased")
            }
        }
    }

    @Test
    fun `fold of every single UTF-16 unit never throws is idempotent and drops the combining marks`() {
        // Includes U+1E9E ẞ and U+212B Å (their lowercaseChar is ß and å, both folded further), İ, ı and every surrogate.
        for (code in 0..0xFFFF) {
            val s = Char(code).toString()
            val w = "U+${code.toString(16).uppercase().padStart(4, '0')}"
            val f = pfPure(w) { fold(s) }
            assertEquals(f, pfPure("$w: fold(fold)") { fold(f) }, "$w: fold(fold(x)) == fold(x), fold(x) = ${pfShow(f)}")
            if (code in 0x300..0x36F) {
                assertEquals("", f, "$w: a combining mark is dropped")
            } else {
                assertTrue(f.length in 1..2, "$w: only the marks are dropped and only ß grows, got ${pfShow(f)}")
                assertTrue(f.none { it in '̀'..'ͯ' }, "$w: a combining mark in ${pfShow(f)}")
            }
            assertEquals(f, fold("x${s}y").removePrefix("x").removeSuffix("y"), "$w: folds the same inside a word")
        }
    }

    // ---- MerchantKey (D61) ----

    @Test
    fun `MerchantKey of hostile text is the fold without any edge space and NFC and NFD share a key`() {
        val vivo = MerchantKey.of("VIVO  FIBRA")
        assertEquals("vivofibra", vivo.v, "VIVO  FIBRA: fold without the spaces")
        for (s in listOf("vivo fibra", "VÍVO FIBRA", "VÍVO FIBRA", "VIVO FIBRA", "　vivo fibra\u0085", "\tV I V O F I B R A\u000B")) {
            assertEquals(vivo, MerchantKey.of(s), "${pfShow(s)} shares the key of VIVO  FIBRA (D61)")
        }
        val cafe = MerchantKey.of("CAFÉ DO PONTO")
        assertEquals(cafe, MerchantKey.of("cafe do ponto"), "CAFÉ DO PONTO and cafe do ponto (D61)")
        assertEquals(cafe, MerchantKey.of("CAFÉ DO PONTO"), "CAFÉ DO PONTO in NFD")
        assertEquals("", MerchantKey.of("").v, "empty text, empty key")
        assertEquals("", MerchantKey.of(PF_INVISIBLE.joinToString("") + " \t\r\n\u0000").v, "only edge spaces: empty key")
        assertEquals("​", MerchantKey.of(" ​ ").v, "a zero-width space is not an edge space")

        for (seed in listOf(0x4E71L, 0x4E72L)) {
            val rnd = Random(seed)
            repeat(3000) { i ->
                val s = if (i % 500 == 499) pfHostile(rnd, 40).repeat(50) else pfFoldText(rnd)
                val w = "seed=$seed i=$i s=${pfShow(s)}"
                val k = pfPure(w) { MerchantKey.of(s) }
                assertTrue(k.v.none(::pfEdge), "$w: an edge-space char in the key ${pfShow(k.v)}")
                assertEquals(fold(s).filterNot(::pfEdge), k.v, "$w: the key is the fold without the edge spaces")
                assertEquals(k, pfPure("$w: of(of)") { MerchantKey.of(k.v) }, "$w: of(of(x).v) == of(x)")
                val noisy = buildString {
                    for (c in s) {
                        if (rnd.nextInt(4) == 0) append(PF_INVISIBLE.random(rnd))
                        append(c)
                    }
                    if (rnd.nextBoolean()) append(" 　")
                }
                assertEquals(k, MerchantKey.of(noisy), "$w: edge spaces anywhere don't change the key (noisy=${pfShow(noisy)})")
                assertEquals(k, MerchantKey.of(pfToNfd(s)), "$w: NFD spelling")
                assertEquals(k, MerchantKey.of(pfToNfc(s)), "$w: NFC spelling")
            }
            // Words of the table and ASCII: both spellings and both cases give the base letters, lowercase, no spaces.
            repeat(1000) { i ->
                val nfc = StringBuilder()
                val nfd = StringBuilder()
                val bases = StringBuilder()
                repeat(rnd.nextInt(1, 10)) {
                    when (rnd.nextInt(4)) {
                        0 -> {
                            val sp = listOf(" ", " ", "　", " ").random(rnd)
                            nfc.append(sp)
                            nfd.append(sp)
                            bases.append(sp)
                        }
                        1 -> {
                            val c = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".random(rnd)
                            nfc.append(c)
                            nfd.append(c)
                            bases.append(c)
                        }
                        else -> {
                            val (pre, base, mark) = PF_LATIN.random(rnd)
                            nfc.append(pre)
                            nfd.append(base).append(mark)
                            bases.append(base)
                        }
                    }
                }
                val w = "seed=$seed word i=$i nfc=${pfShow(nfc.toString())}"
                val want = bases.toString().lowercase().filterNot(::pfEdge)
                assertEquals(want, MerchantKey.of(nfc.toString()).v, "$w: the base letters, lowercase, without spaces")
                assertEquals(want, MerchantKey.of(nfd.toString()).v, "$w: NFD spelling ${pfShow(nfd.toString())}")
                assertEquals(want, MerchantKey.of(nfc.toString().uppercase()).v, "$w: uppercase")
                assertEquals(want, MerchantKey.of(nfd.toString().lowercase()).v, "$w: NFD lowercase")
            }
        }
    }

    // ---- hostile forms (D62, D64) ----

    /** Seed 0x9E715A1L: the general mix, about 12% of each field hostile. */
    @Test
    fun `hostile previsto forms through accept addManual and edit refuse exactly what the KDoc refuses`() = pfRealTime {
        pfRun(0x9E715A1L, iterations = 140, h = 12)
    }

    /** Seed 0xBAD5EEDL: about 35% of each field hostile, so most writes are refused and must write nothing. */
    @Test
    fun `mostly invalid previsto forms write nothing when refused`() = pfRealTime {
        pfRun(0xBAD5EEDL, iterations = 140, h = 35)
    }

    // ---- recognition with hostile bank texts (D60, R2) ----

    @Test
    fun `recados whose bank text gives an empty key are never recognized and the others follow the key`() = pfRealTime {
        pfWithDb { db ->
            val seed = 0x7EC0661L
            val rnd = Random(seed)
            assertTrue(db.addSource(PF_PKG, PF_LABEL, PF_SETUP_AT), "seed=$seed setup: addSource")
            assertTrue(db.addSource(PF_PKG2, PF_LABEL2, PF_SETUP_AT), "seed=$seed setup: addSource 2")
            val budget = db.createBudget("Casa", Cents(500_000), PF_TODAY)
            assertNotEquals(0L, budget.v, "seed=$seed setup: createBudget")
            val budgets = mapOf(budget to "Casa")

            // 1. One recado per previsto, lançado as Todo mês (every 4th through accept ONCE + edit MONTHLY).
            val k = 24
            val parts = List(k) { i -> if (i % 3 == 0) pfEmptyKeyMerchant(rnd) else pfBankMerchant(rnd) + pfLetters(i) }
            val link = pfCaptureAll(db, "seed=$seed link", List(k) { i -> PfRecado(PF_PKG, "link|$i", PF_TODAY, i, parts[i]) })
            val bank = List(k) { i -> link.getValue("link|$i").parsed.merchant }
            val keys = bank.map { MerchantKey.of(it) }
            val emptyIdx = (0 until k).filter { keys[it].v.isEmpty() }
            val fullIdx = (0 until k).filter { keys[it].v.isNotEmpty() }
            assertTrue(emptyIdx.size >= 3, "seed=$seed setup: fewer than 3 bank texts with an empty key: ${bank.map(::pfShow)}")
            assertTrue(fullIdx.size >= 3, "seed=$seed setup: fewer than 3 bank texts with a key: ${bank.map(::pfShow)}")
            for (i in 0 until k) {
                val n = link.getValue("link|$i").id
                // The form's merchant differs from the bank text: the link must come from the recado (D62).
                val form = pfForm(budget, PF_TODAY, "Conta ${pfLetters(i)}", Repeats.MONTHLY, total = 1200L + i, merchant = "EMENDADO ${pfLetters(i)}")
                val what = "seed=$seed link i=$i bank=${pfShow(bank[i])}"
                if (i % 4 == 1) {
                    val tx = pfNoThrow(what) { db.accept(n, form.copy(repeats = Repeats.ONCE)) }
                    assertNotEquals(0L, tx.v, "$what: accept Só uma vez")
                    assertTrue(pfNoThrow(what) { db.edit(tx, form) }, "$what: edit to Todo mês")
                } else {
                    assertNotEquals(0L, pfNoThrow(what) { db.accept(n, form) }.v, "$what: accept Todo mês")
                }
            }
            val nov5 = LocalDate(2026, 11, 5)
            val nov9 = LocalDate(2026, 11, 9)
            val novRows = pfNoThrow("seed=$seed planned(nov5)") { db.planned(nov5) }
            pfSaneList("seed=$seed nov5", novRows, nov5, budgets)
            val previsto = HashMap<Int, PlannedId>()
            val links = HashMap<PlannedId, Pair<String, MerchantKey>>()
            for (i in 0 until k) {
                val what = "seed=$seed link i=$i bank=${pfShow(bank[i])}"
                val rows = novRows.filter { it.name == "Conta ${pfLetters(i)}" }
                assertEquals(1, rows.size, "$what: one open occurrence by 5 nov")
                val p = rows.single()
                assertEquals(nov9, p.date, "$what: first occurrence in the month after the purchase (D64)")
                val linked = keys[i].v.isNotEmpty()
                assertEquals(linked, p.linked, "$what: linked exactly when the recado's bank text gives a key (R2)")
                assertEquals(if (linked) bank[i] else "", p.linkText, "$what: linkText is the recado's bank text, not the form's merchant")
                assertEquals(if (linked) PF_LABEL else "", p.linkSource, "$what: linkSource")
                previsto[i] = p.planned
                if (linked) links[p.planned] = PF_PKG to keys[i]
            }

            // 2. Next month's recados: same text, other spellings, another fonte, out of the window, on the day.
            val nov = ArrayList<PfRecado>()
            for (i in 0 until k) {
                val alt = if (i in emptyIdx) pfEmptyKeyMerchant(rnd) else pfVariant(rnd, parts[i])
                nov += PfRecado(PF_PKG, "same|$i", nov5, i, parts[i])
                nov += PfRecado(PF_PKG, "variant|$i", nov5, 100 + i, alt)
                nov += PfRecado(PF_PKG2, "source|$i", nov5, 200 + i, parts[i])
                nov += PfRecado(PF_PKG, "far|$i", LocalDate(2026, 11, 30), i, parts[i])
                if (i in emptyIdx) nov += PfRecado(PF_PKG, "onday|$i", nov9, 300 + i, pfEmptyKeyMerchant(rnd))
            }
            pfCaptureAll(db, "seed=$seed nov", nov)
            val recognized = pfCheckRecognition(db, "seed=$seed nov5", nov5, links, budgets)
            assertTrue(recognized >= fullIdx.size, "seed=$seed nov5: every same-text recado of a linked previsto is recognized; got $recognized")
            pfCheckRecognition(db, "seed=$seed nov1", LocalDate(2026, 11, 1), links, budgets)
            val someRecognized = db.pending().first { it.planned.occurrence.v != 0L }
            assertEquals(Planned(), db.notice(someRecognized.id).planned, "seed=$seed: notice(id) never carries NoticeRow.planned")

            // 3. Close occurrences: an empty key keeps the link (or the lack of one), a key (re)writes it, by hand writes none.
            val nov6 = LocalDate(2026, 11, 6)
            val r3 = pfBankMerchant(rnd) + "qzz"
            val r5 = "OUTRO" + pfBankMerchant(rnd) + "qzy"
            val closers = pfCaptureAll(
                db, "seed=$seed close",
                listOf(
                    PfRecado(PF_PKG, "close|1", nov6, 1, pfEmptyKeyMerchant(rnd)),
                    PfRecado(PF_PKG, "close|2", nov6, 2, pfEmptyKeyMerchant(rnd)),
                    PfRecado(PF_PKG, "close|3", nov6, 3, r3),
                    PfRecado(PF_PKG, "close|5", nov6, 5, r5),
                ),
            )
            for (c in listOf("close|1", "close|2")) {
                assertEquals("", MerchantKey.of(closers.getValue(c).parsed.merchant).v, "seed=$seed setup: $c must give an empty key")
            }
            for (c in listOf("close|3", "close|5")) {
                assertNotEquals("", MerchantKey.of(closers.getValue(c).parsed.merchant).v, "seed=$seed setup: $c must give a key")
            }
            val j1 = previsto.getValue(fullIdx[0])
            val j5 = previsto.getValue(fullIdx[1])
            val j2 = previsto.getValue(emptyIdx[0])
            val j3 = previsto.getValue(emptyIdx[1])
            val j4 = previsto.getValue(emptyIdx[2])
            val open6 = db.planned(nov6)
            fun occ(p: PlannedId) = open6.single { it.planned == p && it.date == nov9 }
            val closing = listOf("close|1" to j1, "close|2" to j2, "close|3" to j3, "close|5" to j5)
            for ((c, j) in closing) {
                val o = occ(j)
                val what = "seed=$seed $c closes previsto ${j.v} occurrence ${o.occurrence.v}"
                val tx = pfNoThrow(what) {
                    db.accept(closers.getValue(c).id, pfForm(budget, nov6, o.name, occurrence = o.occurrence, total = o.cents.v, tags = o.tags))
                }
                assertNotEquals(0L, tx.v, "$what: accept with an open occurrence")
            }
            val o4 = occ(j4)
            val manual = pfNoThrow("seed=$seed manual closes ${j4.v}") {
                db.addManual(pfForm(budget, nov6, o4.name, occurrence = o4.occurrence, merchant = "LOJA MANUAL"), nov6)
            }
            assertNotEquals(0L, manual.transaction.v, "seed=$seed: addManual with an open occurrence (R6b)")
            assertEquals(0L, manual.planned.v, "seed=$seed: addManual Só uma vez creates no previsto")
            val after6 = db.planned(nov6)
            for (j in listOf(j1, j2, j3, j4, j5)) {
                assertTrue(after6.none { it.planned == j && it.date == nov9 }, "seed=$seed: previsto ${j.v}'s November occurrence is still open after it was lançado")
            }
            links[j3] = PF_PKG to MerchantKey.of(closers.getValue("close|3").parsed.merchant)
            links[j5] = PF_PKG to MerchantKey.of(closers.getValue("close|5").parsed.merchant)

            val dec5 = LocalDate(2026, 12, 5)
            val dec9 = LocalDate(2026, 12, 9)
            val decRows = db.planned(dec5)
            fun dec(p: PlannedId) = decRows.singleOrNull { it.planned == p && it.date == dec9 } ?: fail("seed=$seed: previsto ${p.v} has no open occurrence on $dec9")
            assertEquals(true to bank[fullIdx[0]], dec(j1).let { it.linked to it.linkText }, "seed=$seed: an empty-key recado must not rewrite the link (R2)")
            assertEquals(false to "", dec(j2).let { it.linked to it.linkText }, "seed=$seed: an empty-key recado must not link (R2)")
            assertEquals(true to closers.getValue("close|3").parsed.merchant, dec(j3).let { it.linked to it.linkText }, "seed=$seed: a recado with a key links (D62)")
            assertEquals(false to "", dec(j4).let { it.linked to it.linkText }, "seed=$seed: a lançamento manual writes no link (R6b)")
            assertEquals(true to closers.getValue("close|5").parsed.merchant, dec(j5).let { it.linked to it.linkText }, "seed=$seed: a recado with a key rewrites the link (D62)")
            pfCaptureAll(
                db, "seed=$seed dec",
                listOf(
                    PfRecado(PF_PKG, "dec|1", dec5, 1, parts[fullIdx[0]]),
                    PfRecado(PF_PKG, "dec|3", dec5, 3, r3),
                    PfRecado(PF_PKG, "dec|5new", dec5, 5, r5),
                    PfRecado(PF_PKG, "dec|5old", dec5, 6, parts[fullIdx[1]]),
                    PfRecado(PF_PKG, "dec|e", dec5, 7, pfEmptyKeyMerchant(rnd)),
                ),
            )
            pfCheckRecognition(db, "seed=$seed dec5", dec5, links, budgets)
        }
    }

    // ---- dates at the storable edges ----

    @Test
    fun `previstos at the storable date edges never throw and read back sane`() = pfRealTime {
        pfWithDb { db ->
            assertTrue(db.addSource(PF_PKG, PF_LABEL, PF_SETUP_AT), "setup: addSource")
            val b = db.createBudget("Casa", Cents(500_000), PF_TODAY)
            assertNotEquals(0L, b.v, "setup: createBudget")
            val budgets = mapOf(b to "Casa")
            val end = LocalDate(9999, 12, 31)

            val s1 = pfNoThrow("Salvar previsto on $end") { db.addManual(pfForm(b, end, "Fim do calendário"), PF_TODAY) }
            val s2 = pfNoThrow("Salvar previsto monthly on 9999-12-05") { db.addManual(pfForm(b, LocalDate(9999, 12, 5), "Quase no fim", Repeats.MONTHLY), PF_TODAY) }
            val s3 = pfNoThrow("Salvar previsto monthly on 9999-11-30") { db.addManual(pfForm(b, LocalDate(9999, 11, 30), "Penúltimo mês", Repeats.MONTHLY), PF_TODAY) }
            for ((name, s) in listOf("s1" to s1, "s2" to s2, "s3" to s3)) {
                assertEquals(0L, s.transaction.v, "$name: a date ahead writes no lançamento (D64): $s")
                assertNotEquals(0L, s.planned.v, "$name: a date ahead in range is a previsto: $s")
            }
            val s4 = pfNoThrow("lançamento on 0001-01-01") { db.addManual(pfForm(b, LocalDate(1, 1, 1), "Começo"), PF_TODAY) }
            assertNotEquals(0L, s4.transaction.v, "0001-01-01 is in range: $s4")
            assertEquals(0L, s4.planned.v, "Só uma vez in the past: no previsto: $s4")

            for (d in PF_BEYOND) for (repeats in Repeats.entries) for (today in listOf(PF_TODAY, NO_DATE)) {
                pfRefused(db, "addManual date=$d repeats=$repeats today=$today") {
                    db.addManual(pfForm(b, d, "Fora do calendário", repeats), today).let { it.transaction.v != 0L || it.planned.v != 0L }
                }
                pfRefused(db, "Editar previsto ${s2.planned.v} to date=$d") {
                    db.addManual(pfForm(b, d, "Fora", repeats, planned = s2.planned), PF_TODAY).let { it.transaction.v != 0L || it.planned.v != 0L }
                }
            }
            // The KDoc leaves these open (first occurrence in 10000-01): any answer but a throw.
            val n = pfCaptureAll(db, "edge", listOf(PfRecado(PF_PKG, "edge|1", PF_TODAY, 1, "LOJA FIM")))
            pfNoThrow("accept Todo mês on 9999-12-15") { db.accept(n.getValue("edge|1").id, pfForm(b, LocalDate(9999, 12, 15), "Virada impossível", Repeats.MONTHLY)) }
            pfNoThrow("addManual Todo mês on 9999-12-20 without today") { db.addManual(pfForm(b, LocalDate(9999, 12, 20), "Sem hoje", Repeats.MONTHLY), NO_DATE) }

            for (today in listOf(PF_TODAY, LocalDate(1, 1, 1), LocalDate(9999, 12, 20), end)) {
                pfNoThrow("pending($today)") { db.pending(today) }
                val rows = pfNoThrow("planned($today)") { db.planned(today) }
                pfSaneList("planned($today)", rows, today, budgets)
            }
            val atEnd = db.planned(end)
            val r1 = atEnd.singleOrNull { it.planned == s1.planned } ?: fail("planned($end): the ONCE previsto on $end must be listed once: $atEnd")
            assertEquals(end to NO_DATE, r1.date to r1.next, "the ONCE previsto on $end")
            assertTrue(!r1.late, "on its own day the previsto is not late")
            val r2 = atEnd.firstOrNull { it.planned == s2.planned && it.date == LocalDate(9999, 12, 5) } ?: fail("planned($end): s2's occurrence on 9999-12-05 missing: $atEnd")
            assertEquals(LocalDate(10000, 1, 5), r2.next, "next of the December 9999 occurrence")
            assertTrue(r2.late, "9999-12-05 is late on $end")
            assertEquals(
                listOf(LocalDate(9999, 11, 30), LocalDate(9999, 12, 30)),
                atEnd.filter { it.planned == s3.planned && it.date.year == 9999 }.map { it.date },
                "s3 from 9999-11-30: November and December 9999",
            )
            for (today in PF_BEYOND) {
                pfNoThrow("pending($today)") { db.pending(today) }
                pfNoThrow("planned($today)") { db.planned(today) }
            }
            pfNoThrow("pending()") { db.pending() }

            // Editar previsto to day 31: the open occurrences follow the new day.
            val e = pfNoThrow("Editar previsto ${s2.planned.v} to day 31") {
                db.addManual(pfForm(b, LocalDate(1, 1, 31), "Último dia", Repeats.MONTHLY, planned = s2.planned), PF_TODAY)
            }
            assertEquals(Saved(planned = s2.planned), e, "Editar previsto at the edge")
            val edited = db.planned(end).filter { it.planned == s2.planned }
            assertTrue(edited.isNotEmpty(), "s2 still has open occurrences")
            for (p in edited) {
                assertEquals(31, p.day, "s2 occurrence ${p.occurrence.v}: the new day")
                assertEquals("Último dia", p.name, "s2 occurrence ${p.occurrence.v}: the new name")
            }
            pfSaneList("planned($end) after the edit", db.planned(end), end, budgets)
        }
    }

    @Test
    fun `a monthly previsto from year one reads back every month without throwing`() = pfRealTime {
        pfWithDb { db ->
            val b = db.createBudget("Casa", Cents(500_000), PF_TODAY)
            assertNotEquals(0L, b.v, "setup: createBudget")
            val s = pfNoThrow("Todo mês on 0001-01-31") { db.addManual(pfForm(b, LocalDate(1, 1, 31), "Desde o ano um", Repeats.MONTHLY), PF_TODAY) }
            assertNotEquals(0L, s.transaction.v, "0001-01-31 is in range: the lançamento: $s")
            assertNotEquals(0L, s.planned.v, "Todo mês in the past: the previsto too: $s")
            val rows = pfNoThrow("planned($PF_TODAY)") { db.planned(PF_TODAY) }.filter { it.planned == s.planned }
            // Every calendar month from 0001-02 whose occurrence (day 31, clamped) is dated up to today + 10 (R1).
            val reach = PF_TODAY.plus(10, DateTimeUnit.DAY)
            val want = ArrayList<LocalDate>()
            var ym = YearMonth(1, 2)
            while (pfClamp(ym, 31) <= reach) {
                want += pfClamp(ym, 31)
                ym = ym.plusMonth()
            }
            assertEquals(LocalDate(1, 2, 28), want.first(), "setup: the first occurrence")
            assertEquals(want.size, rows.size, "occurrences from 0001-02 to the last one dated up to $reach")
            assertEquals(want, rows.map { it.date }, "one occurrence per month, day 31 clamped")
            pfSaneList("year one", rows, PF_TODAY, mapOf(b to "Casa"))
            assertTrue(rows.all { it.late }, "every occurrence before today is late")
            pfNoThrow("pending($PF_TODAY)") { db.pending(PF_TODAY) }
            assertEquals(rows, db.planned(PF_TODAY).filter { it.planned == s.planned }, "a second read creates nothing new")
        }
    }

    // ---- hostile ids on skipOccurrence / deletePlanned ----

    @Test
    fun `skipOccurrence and deletePlanned with hostile ids change exactly what the KDoc says`() = pfRealTime {
        pfWithDb { db ->
            val seed = 0x5C1BDE1L
            val rnd = Random(seed)
            assertTrue(db.addSource(PF_PKG, PF_LABEL, PF_SETUP_AT), "seed=$seed setup: addSource")
            val b = db.createBudget("Casa", Cents(500_000), PF_TODAY)
            assertNotEquals(0L, b.v, "seed=$seed setup: createBudget")
            val budgets = mapOf(b to "Casa")
            val live = ArrayList<PlannedId>()
            for ((k, offset) in listOf(-40, 3, 25, -5, -70, 8, 60, -1).withIndex()) {
                val repeats = if (offset <= 0 || k % 2 == 0) Repeats.MONTHLY else Repeats.ONCE
                val s = pfNoThrow("seed=$seed setup previsto $k") { db.addManual(pfForm(b, PF_TODAY.plus(offset, DateTimeUnit.DAY), "Previsto $k", repeats), PF_TODAY) }
                assertNotEquals(0L, s.planned.v, "seed=$seed setup previsto $k: $s")
                live += s.planned
            }
            val recados = pfCaptureAll(db, "seed=$seed setup", List(12) { i -> PfRecado(PF_PKG, "d|$i", PF_TODAY, i, pfBankMerchant(rnd) + pfLetters(i)) })
            val free = recados.values.map { it.id }.toMutableList()
            val closed = ArrayList<OccurrenceId>()
            val deleted = ArrayList<PlannedId>()
            val deletedOcc = ArrayList<OccurrenceId>()
            val unknownOcc = listOf(OccurrenceId(0), OccurrenceId(-1), OccurrenceId(Long.MAX_VALUE), OccurrenceId(Long.MIN_VALUE), OccurrenceId(123_456_789))
            val unknownPlanned = listOf(PlannedId(0), PlannedId(-1), PlannedId(Long.MAX_VALUE), PlannedId(Long.MIN_VALUE), PlannedId(99_999))
            val month = db.monthOf(PF_TODAY)
            // One occurrence closed by accept, one by hand, one skipped.
            run {
                val open = db.planned(PF_HORIZON)
                val a = open[0]
                assertNotEquals(0L, db.accept(free.removeAt(0), pfForm(b, PF_TODAY, a.name, occurrence = a.occurrence)).v, "seed=$seed setup: accept closes ${a.occurrence.v}")
                val m = open[1]
                assertNotEquals(0L, db.addManual(pfForm(b, PF_TODAY, m.name, occurrence = m.occurrence), PF_TODAY).transaction.v, "seed=$seed setup: addManual closes ${m.occurrence.v}")
                val sk = open[2]
                assertTrue(db.skipOccurrence(sk.occurrence), "seed=$seed setup: skip ${sk.occurrence.v}")
                closed += listOf(a.occurrence, m.occurrence, sk.occurrence)
            }

            repeat(120) { i ->
                val what = "seed=$seed i=$i"
                val before = pfNoThrow("$what: planned(HORIZON)") { db.planned(PF_HORIZON) }
                val t0 = db.transactions()
                val n0 = db.pending()
                val v0 = db.version.value
                when (rnd.nextInt(10)) {
                    in 0..4 -> {
                        val id = when (rnd.nextInt(100)) {
                            in 0..39 -> before.randomOrNull(rnd)?.occurrence ?: unknownOcc.random(rnd)
                            in 40..59 -> closed.random(rnd)
                            in 60..74 -> deletedOcc.randomOrNull(rnd) ?: unknownOcc.random(rnd)
                            else -> unknownOcc.random(rnd)
                        }
                        val w = "$what skipOccurrence(${id.v})"
                        val want = before.any { it.occurrence == id }
                        assertEquals(want, pfNoThrow(w) { db.skipOccurrence(id) }, "$w: true exactly for an open occurrence (P10)")
                        val after = db.planned(PF_HORIZON)
                        assertEquals(before.filter { it.occurrence != id }.map(::pfStrip), after.map(::pfStrip), "$w: only that occurrence goes; it never comes back")
                        assertEquals(t0, db.transactions(), "$w: transactions() changed")
                        assertEquals(n0.map { it.id }, db.pending().map { it.id }, "$w: pending() changed")
                        if (want) closed += id else {
                            assertEquals(v0, db.version.value, "$w: a refused skip bumped version")
                            assertEquals(before, after, "$w: a refused skip changed planned()")
                        }
                    }
                    in 5..7 -> {
                        val id = when (rnd.nextInt(100)) {
                            in 0..49 -> live.randomOrNull(rnd) ?: unknownPlanned.random(rnd)
                            in 50..69 -> deleted.randomOrNull(rnd) ?: unknownPlanned.random(rnd)
                            else -> unknownPlanned.random(rnd)
                        }
                        val w = "$what deletePlanned(${id.v})"
                        val existed = id in live
                        val spent0 = db.budgetDetail(b, month, PF_TODAY).month.spent
                        pfNoThrow(w) { db.deletePlanned(id) }
                        val after = db.planned(PF_HORIZON)
                        assertEquals(before.filter { it.planned != id }.map(::pfStrip), after.map(::pfStrip), "$w: only its occurrences go")
                        assertEquals(t0, db.transactions(), "$w: its lançamentos stay")
                        assertEquals(spent0, db.budgetDetail(b, month, PF_TODAY).month.spent, "$w: its lançamentos keep counting")
                        assertEquals(n0.map { it.id }, db.pending().map { it.id }, "$w: pending() changed")
                        if (existed) {
                            live.remove(id)
                            deleted += id
                            deletedOcc += before.filter { it.planned == id }.map { it.occurrence }
                        } else {
                            assertEquals(v0, db.version.value, "$w: deleting an unknown previsto bumped version")
                            assertEquals(before, after, "$w: deleting an unknown previsto changed planned()")
                        }
                    }
                    8 -> {
                        val id = (deleted + unknownPlanned.drop(1)).random(rnd)
                        pfRefused(db, "$what Editar previsto ${id.v} (deleted or unknown)") {
                            db.addManual(pfForm(b, PF_TODAY, "Fantasma", planned = id), PF_TODAY).let { it.transaction.v != 0L || it.planned.v != 0L }
                        }
                    }
                    else -> {
                        val id = (closed + deletedOcc + unknownOcc.drop(1)).random(rnd)
                        val notice = free.randomOrNull(rnd)
                        if (notice != null && rnd.nextBoolean()) {
                            pfRefused(db, "$what accept with occurrence ${id.v} (not open)") { db.accept(notice, pfForm(b, PF_TODAY, "Fechado", occurrence = id)).v != 0L }
                        } else {
                            pfRefused(db, "$what addManual with occurrence ${id.v} (not open)") {
                                db.addManual(pfForm(b, PF_TODAY, "Fechado", occurrence = id), PF_TODAY).let { it.transaction.v != 0L || it.planned.v != 0L }
                            }
                        }
                    }
                }
                if (i % 20 == 19) pfSaneList("$what sweep", db.planned(PF_HORIZON), PF_HORIZON, budgets)
            }
        }
    }
}
