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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Legenda do lançamento (spec §2.12) fuzz: hostile caption text through the three writes
// (Db.accept with a pending recado, Db.addManual, Db.edit) and back through every read that carries
// it (transactions, transaction, the budgetDetail lines, refundCandidates). Fixed seeds, bounded
// iterations, a real SQLite file per test, a wall-clock timeout. Expected values come from the
// contract — spec §2.12 / §7.3, architecture D28 and D52–D55, and the KDoc of
// TransactionForm.caption, Db.accept / Db.edit and storable — never from the implementation. Every
// failure message carries the seed, the iteration and the input. Out of scope (normal tests,
// legenda-plan §6): the estorno search, the UI counter and the list cut.

private val LG_TODAY = LocalDate(2026, 10, 20)
private const val LG_PKG = "com.banco.legenda"

/** A whole minute (epoch ms): a time read from the notice's own time then carries no seconds. */
private const val LG_NOW = 1_760_000_040_000L

/** The legenda limit (spec §2.12, O2/D53/D55): 80 UTF-16 units after cleaning — `String.length`. */
private const val LG_CAP = 80

/** Real threads under a wall-clock timeout (withTimeout inside Dispatchers.Default), so a deadlock fails. */
private fun lgRealTime(block: suspend () -> Unit) = runTest(timeout = 120.seconds) {
    withContext(Dispatchers.Default) { withTimeout(90.seconds) { block() } }
}

/** A fresh Db on a temporary file, closed and deleted (with -wal/-shm) afterwards. */
private suspend fun lgWithDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-legenda-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path, {}, TimeZone.of("America/Sao_Paulo"))
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
private suspend fun <T> lgNoThrow(where: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fail("$where threw $e", e)
}

/** Escaped, truncated rendering of [s] for failure messages. */
private fun lgShow(s: String): String = buildString {
    append('"')
    for (c in s.take(60)) {
        if (c.code in 0x20..0x7E && c != '"' && c != '\\') append(c)
        else append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }
    append('"')
    if (s.length > 60) append("…(${s.length} units)")
}

// ---- the clean the KDoc of TransactionForm.caption describes ----

/** Every line break (LF, CRLF, CR, U+2028, U+2029) becomes one space (spec §2.12); CRLF is one break. */
private fun lgBreaks(s: String): String = buildString(s.length) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\r' && i + 1 < s.length && s[i + 1] == '\n' -> {
                append(' ')
                i += 2
            }
            c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' -> {
                append(' ')
                i++
            }
            else -> {
                append(c)
                i++
            }
        }
    }
}

/** The text as the Db stores and reads it back (D28): U+FEFF, U+FFFE, U+FFFF and lone surrogates become U+FFFD. */
private fun lgStored(s: String): String = buildString(s.length) {
    for (i in s.indices) {
        val c = s[i]
        val lone = (c.isHighSurrogate() && (i + 1 >= s.length || !s[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !s[i - 1].isHighSurrogate()))
        append(if (c == '\uFEFF' || c == '\uFFFE' || c == '\uFFFF' || lone) '\uFFFD' else c)
    }
}

/**
 * What an accepted caption may read back as: `storable(trim(breaks → spaces))`, every invisible trimmed
 * at the edges. Exact since 2026-10-20 (the user decided the edge set): ASCII controls, VT/FF/NEL and
 * the NBSP family go at the edges and stay in the middle. Before that the set had two readings for the
 * NBSP family (see the plan §6); the table cases now pin the single one.
 */
private fun lgReadings(s: String): Set<String> {
    val broken = lgBreaks(s)
    return setOf(lgStored(broken.trim(::lgEdgeSpace)))
}

/** The decided edge set (spec §2.12, 2026-10-20): every invisible that makes a line look empty. Explicit, like Db's. */
private fun lgEdgeSpace(c: Char): Boolean =
    c <= ' ' || c == '\u0085' || c == '\u00A0' || c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u202F' || c == '\u205F' || c == '\u3000'

// ---- hostile caption text ----

/** One hostile piece: lone surrogates, the D28 characters, emoji, combining marks, RTL, controls, breaks, spaces. */
private fun lgPiece(rnd: Random): String = when (rnd.nextInt(18)) {
    0 -> Char(rnd.nextInt(0xD800, 0xDC00)).toString() // lone high surrogate (unless a low one follows)
    1 -> Char(rnd.nextInt(0xDC00, 0xE000)).toString() // lone low surrogate
    2 -> listOf("\uFEFF", "\uFFFE", "\uFFFF", "\uFFFD").random(rnd)
    3 -> listOf("😀", "👩‍👩‍👧", "🇧🇷", "❤️").random(rnd) // astral, ZWJ family, flag, variation selector
    4 -> "e" + Char(rnd.nextInt(0x300, 0x370)) + Char(rnd.nextInt(0x300, 0x370)) // combining marks (NFD)
    5 -> listOf("\u202E", "\u202B", "\u202C", "\u200F", "\u200E", "\u2066", "\u2069", "שלום", "مرحبا").random(rnd) // RTL and bidi
    6 -> Char(rnd.nextInt(0xC0, 0x250)).toString() // é, É, ç, ø, ß…
    7 -> Char(rnd.nextInt(0x4E00, 0xA000)).toString() // CJK
    8 -> Char(rnd.nextInt(0, 0x20)).toString() // controls, \u0000 and the ASCII breaks included
    9 -> listOf("\u0085", "\u00A0", "\u2007", "\u202F", "\u205F", "\u3000", "\u1680", "\u200B").random(rnd) // exotic spaces
    10 -> listOf(" ", "\t", "  ", "\u3000", "\t\n", "\u00A0").random(rnd) // plain whitespace
    11 -> listOf("\n", "\r", "\r\n", "\n\r", "\u2028", "\u2029", "\n\n", "\r\r\n").random(rnd) // line breaks
    12 -> listOf("'", "\"", "%", "_", "\\", "'; DROP TABLE transactions;--", "\$x", "?").random(rnd) // SQL-ish
    13 -> {
        val v = rnd.nextInt(0x10000, 0x110000) - 0x10000
        "" + Char(0xD800 + (v shr 10)) + Char(0xDC00 + (v and 0x3FF))
    }
    14 -> "x".repeat(rnd.nextInt(2000, 5000)) // far beyond 80
    15 -> "x".repeat(rnd.nextInt(70, 85)) // around the limit
    16 -> "e\u0301" + Char(0x300 + rnd.nextInt(0x70)) // one NFD letter
    else -> Char(rnd.nextInt(0x21, 0x7F)).toString()
}

private fun lgHostile(rnd: Random, maxPieces: Int): String =
    buildString { repeat(rnd.nextInt(0, maxPieces + 1)) { append(lgPiece(rnd)) } }

/**
 * Exactly [units] UTF-16 units out of [pool], appended until it overflows and then cut — the cut may
 * split an astral pair and leave a lone surrogate (D55: the UI never does, the API can).
 */
private fun lgFill(rnd: Random, units: Int, pool: List<String>): String {
    var s = ""
    while (s.length < units) s += pool.random(rnd)
    return s.take(units)
}

/** The general mix: blanks, edges that trim, whitespace and breaks only, huge text, boundaries. */
private fun lgCaption(rnd: Random): String = when (rnd.nextInt(12)) {
    0 -> ""
    1 -> lgFill(rnd, listOf(79, 80, 81, 82, 120, 300).random(rnd), listOf("a", "😀", "\r\n", "\uFFFF", "\uD800", " ", "e\u0301"))
    2 -> List(rnd.nextInt(1, 6)) { listOf(" ", "\t", "\n", "\r\n", "\u00A0", "\u3000", "\u2028", "\u2029", "\u0085", "\u200B").random(rnd) }.joinToString("")
    3 -> " ".repeat(rnd.nextInt(0, 4)) + lgHostile(rnd, 6) + " ".repeat(rnd.nextInt(0, 4))
    4 -> "x".repeat(rnd.nextInt(2000, 5000))
    5 -> lgHostile(rnd, 2) + "😀".repeat(rnd.nextInt(37, 43)) + lgHostile(rnd, 2)
    6 -> listOf("\r\n", "\n", "\r", "\u2028", "\u2029", " ").random(rnd).repeat(rnd.nextInt(1, 40))
    else -> lgHostile(rnd, rnd.nextInt(1, 10))
}

/** Text at the length boundary: exact pre-clean unit counts out of pieces that shrink, double or replace. */
private fun lgBoundary(rnd: Random): String {
    val units = listOf(78, 79, 80, 81, 82, 83, 84, 100).random(rnd)
    return when (rnd.nextInt(6)) {
        0 -> lgFill(rnd, units, listOf("a"))
        1 -> lgFill(rnd, units, listOf("😀"))
        2 -> lgFill(rnd, units, listOf("ab", "\r\n", " "))
        3 -> lgFill(rnd, units, listOf("\uFFFF", "\uD800", "\uDC00", "e\u0301"))
        4 -> " ".repeat(rnd.nextInt(1, 5)) + lgFill(rnd, units, listOf("a", "😀")) + " ".repeat(rnd.nextInt(1, 5))
        else -> lgFill(rnd, units, listOf("a", "😀", " ", "\t", "\n", "\r\n", "\u2028", "\uFEFF", "\uD800"))
    }
}

/** Breaks and whitespace at the edges and in the middle, plus short text. */
private fun lgBreakPiece(rnd: Random): String = when (rnd.nextInt(8)) {
    0, 1 -> listOf("\n", "\r", "\r\n", "\n\r", "\u2028", "\u2029", "\n\n", "\r\r\n", "\r\u2028").random(rnd)
    2 -> listOf(" ", "  ", "\t", "\t\t", "\u00A0", "\u3000", "\u0085", "\u000B", "\u000C", "\u200B").random(rnd)
    3 -> "e" + Char(rnd.nextInt(0x300, 0x370))
    else -> listOf("a", "b", "Z", "é", "ç", "0", "😀", "x").random(rnd)
}

private fun lgCleanGen(rnd: Random): String = when (rnd.nextInt(8)) {
    0, 1 -> List(rnd.nextInt(1, 10)) { lgBreakPiece(rnd) }.joinToString("")
    2 -> lgFill(rnd, listOf(78, 79, 80, 81, 82, 83).random(rnd), listOf("a", "\r\n", " ", "\n", "\u2028", "\t", "\r"))
    3 -> List(rnd.nextInt(1, 8)) {
        listOf(" ", "\t", "\n", "\r", "\r\n", "\u2028", "\u2029", "\u00A0", "\u3000", "\u0085", "\u000B", "\u000C").random(rnd)
    }.joinToString("")
    else -> lgHostile(rnd, 5)
}

/** D28 characters, lone surrogates, combining marks, bidi and controls. */
private fun lgUnicodePiece(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> Char(rnd.nextInt(0xD800, 0xDC00)).toString()
    1 -> Char(rnd.nextInt(0xDC00, 0xE000)).toString()
    2 -> listOf("\uFEFF", "\uFFFE", "\uFFFF", "\uFFFD").random(rnd)
    3 -> {
        val v = rnd.nextInt(0x10000, 0x110000) - 0x10000
        "" + Char(0xD800 + (v shr 10)) + Char(0xDC00 + (v and 0x3FF))
    }
    4 -> "e" + Char(rnd.nextInt(0x300, 0x370)) + Char(rnd.nextInt(0x300, 0x370))
    5 -> listOf("\u00E9", "e\u0301", "\u212B", "\u2126").random(rnd) // NFC and NFD spellings
    6 -> listOf("\u202E", "\u202B", "\u202C", "\u200F", "\u200E", "\u2066", "\u2069", "\u2028", "\u2029").random(rnd)
    7 -> Char(rnd.nextInt(0, 0x20)).toString()
    8 -> listOf("שלום", "مرحبا").random(rnd)
    else -> Char(rnd.nextInt(0x21, 0x7F)).toString()
}

private fun lgUnicodeGen(rnd: Random): String = when (rnd.nextInt(6)) {
    0, 1 -> List(rnd.nextInt(1, 12)) { lgUnicodePiece(rnd) }.joinToString("")
    2 -> lgFill(rnd, listOf(78, 79, 80, 81, 82).random(rnd), listOf("\uFFFF", "\uD800", "\uDC00", "e\u0301", "\u202E", "\uFEFF", "a"))
    3 -> "e\u0301".repeat(rnd.nextInt(38, 43))
    else -> lgUnicodePiece(rnd).repeat(rnd.nextInt(1, 20))
}

// ---- hand-computed cases ----

/** A caption and the clean text the KDoc describes for it: its length decides the refusal and pins the model. */
private class LgCase(val note: String, val caption: String, val expected: String?)

private val LG_LIMIT_TABLE = listOf(
    LgCase("79 ASCII units", "a".repeat(79), "a".repeat(79)),
    LgCase("80 ASCII units: the limit is accepted", "a".repeat(80), "a".repeat(80)),
    LgCase("81 ASCII units: refused", "a".repeat(81), "a".repeat(81)),
    LgCase("84 raw units with edges that trim to 80", "  " + "a".repeat(80) + "  ", "a".repeat(80)),
    LgCase("81 raw units: the CRLF collapses to one space", "a".repeat(78) + "\r\n" + "b", "a".repeat(78) + " b"),
    LgCase("82 raw units refused after the CRLF collapse", "a".repeat(79) + "\r\n" + "b", "a".repeat(79) + " b"),
    LgCase("81 raw units of text and a trailing CRLF trim to 79", "a".repeat(79) + "\r\n", "a".repeat(79)),
    LgCase("40 astral emoji = 80 units", "😀".repeat(40), "😀".repeat(40)),
    LgCase("41 astral emoji = 82 units refused", "😀".repeat(41), "😀".repeat(41)),
    LgCase("39 emoji and 2 ASCII = 80 units", "😀".repeat(39) + "ab", "😀".repeat(39) + "ab"),
    LgCase("80 units ending in an astral cut in half", "😀".repeat(39) + "a" + "\uD83D", "😀".repeat(39) + "a\uFFFD"),
    LgCase("81 units with an astral cut refused", "😀".repeat(40) + "\uD83D", "😀".repeat(40) + "\uFFFD"),
    LgCase("80 U+FFFF read back as U+FFFD", "\uFFFF".repeat(80), "\uFFFD".repeat(80)),
    LgCase("81 U+FEFF refused", "\uFEFF".repeat(81), "\uFFFD".repeat(81)),
    LgCase("U+FEFF and 79 ASCII = 80 units", "\uFEFF" + "a".repeat(79), "\uFFFD" + "a".repeat(79)),
    LgCase("80 units of NFD accepted unchanged", "e\u0301".repeat(40), "e\u0301".repeat(40)),
    LgCase("81 units of NFD refused", "e\u0301".repeat(40) + "e", "e\u0301".repeat(40) + "e"),
    LgCase("5000 chars refused", "x".repeat(5000), "x".repeat(5000)),
    LgCase("9000 chars with edges refused", " " + "x".repeat(9000) + " ", "x".repeat(9000)),
    LgCase("10000 units of CRLF clean to nothing", "\r\n".repeat(5000), ""),
    LgCase("80 spaces are no legenda", " ".repeat(80), ""),
    LgCase("81 spaces count as nothing after cleaning", " ".repeat(81), ""),
    LgCase("80 units with a NUL in the middle", "a\u0000b" + "c".repeat(77), "a\u0000b" + "c".repeat(77)),
)

private val LG_CLEAN_TABLE = listOf(
    LgCase("CRLF is one break", "a\r\nb", "a b"),
    LgCase("LF and CR are breaks", "a\nb\rc", "a b c"),
    LgCase("two breaks are two spaces", "a\n\nb", "a  b"),
    LgCase("CRLF and CR are two breaks", "a\r\n\rb", "a  b"),
    LgCase("U+2028 and U+2029 are breaks", "a\u2028b\u2029c", "a b c"),
    LgCase("a U+2028 is one space", "a\u2028b", "a b"),
    LgCase("breaks at the edges trim away", "\n\r\nfoo\n", "foo"),
    LgCase("spaces between the breaks survive", "a \r\n b", "a   b"),
    LgCase("only breaks and spaces means no legenda", "\n \r\n \u2028 ", ""),
    LgCase("only spaces and tabs means no legenda", " \t \t ", ""),
    LgCase("edges trimmed", "  bolo  ", "bolo"),
    LgCase("a zero-width space is neither a break nor trimmed", "a\u200Bb", "a\u200Bb"),
    LgCase("vertical tab and form feed are not in the break list", "a\u000B\u000Cb", "a\u000B\u000Cb"),
    LgCase("NEL is not in the break list", "a\u0085b", "a\u0085b"),
    LgCase("NEL at the edges is an invisible for the trim", "\u0085a\u0085", "a"),
    LgCase("NFC stays NFC", "\u00E9lan", "\u00E9lan"),
    LgCase("NFD stays NFD", "e\u0301lan", "e\u0301lan"),
    // 2026-10-20: the NBSP family counts as space at the edges (user decision) — exact readings now.
    LgCase("NBSP only means no legenda", "\u00A0", ""),
    LgCase("NBSP at the edges trims", "\u00A0bolo\u00A0", "bolo"),
)

private val LG_UNICODE_TABLE = listOf(
    LgCase("U+FEFF at the start", "\uFEFFabc", "\uFFFDabc"),
    LgCase("U+FEFF in the middle", "ab\uFEFFcd", "ab\uFFFDcd"),
    LgCase("U+FFFE anywhere", "a\uFFFEb", "a\uFFFDb"),
    LgCase("U+FFFF anywhere", "a\uFFFFb", "a\uFFFDb"),
    LgCase("lone high surrogate at the end", "abc\uD800", "abc\uFFFD"),
    LgCase("lone low surrogate at the start", "\uDC00abc", "\uFFFDabc"),
    LgCase("a valid astral pair is kept", "a\uD83D\uDE00b", "a\uD83D\uDE00b"),
    LgCase("two highs are two lone surrogates", "\uD800\uD800x", "\uFFFD\uFFFDx"),
    LgCase("low then high are two lone surrogates", "\uDC00\uD800x", "\uFFFD\uFFFDx"),
    LgCase("a pair split by a space is two lone surrogates", "\uD83D \uDE00", "\uFFFD \uFFFD"),
    LgCase("combining marks are kept", "e\u0301\u0327", "e\u0301\u0327"),
    LgCase("NFD is not normalized", "e\u0301", "e\u0301"),
    LgCase("NFC is not normalized", "\u00E9", "\u00E9"),
    LgCase("bidi overrides are kept", "a\u202Eb\u202Cc", "a\u202Eb\u202Cc"),
    LgCase("Hebrew and Arabic are kept", "שלום مرحبا", "שלום مرحبا"),
    LgCase("bidi isolates are kept", "\u2066a\u2069", "\u2066a\u2069"),
    LgCase("control characters are kept", "a\u0000\u0001\u0002b", "a\u0000\u0001\u0002b"),
    LgCase("everything at once", "\uD800e\u0301\uFEFF😀\uFFFFx", "\uFFFDe\u0301\uFFFD😀\uFFFDx"),
)

// ---- the engine ----

/** The live model: what each transaction's legenda may read back as, and the recados not yet accepted. */
private class LgEnv(
    val budget: BudgetId,
    val month: YearMonth,
    val estorno: NoticeId,
    val free: MutableList<NoticeId>,
    val txs: LinkedHashMap<TransactionId, Set<String>>,
)

/** Captures [count] notices keyed "<prefix>|i", one minute apart from minute [from]; their ids in order. */
private suspend fun lgCapture(db: Db, prefix: String, count: Int, from: Int): List<NoticeId> {
    repeat(count) { i ->
        val at = LG_NOW + (from + i) * 60_000L
        val raw = RawNotice(
            packageName = LG_PKG, appLabel = "Banco Legenda", key = "$prefix|$i", postedAt = at, whenMs = at,
            title = if (prefix == "estorno") "Estorno" else "Compra aprovada",
            text = if (prefix == "estorno") "Estorno de R$ ${1 + i},00 efetuado" else "Compra de R$ ${1 + i},00 em LOJA $i",
        )
        assertTrue(capture(db, raw), "setup: capture $prefix|$i")
    }
    val byKey = db.pending().associate { it.raw.key to it.id }
    return List(count) { i -> byKey["$prefix|$i"] ?: fail("setup: notice $prefix|$i missing from pending()") }
}

/** Every read that carries the legenda must show the same cleaned text, one of [readings], at most 80 units. */
private suspend fun lgCheckReads(db: Db, what: String, env: LgEnv, id: TransactionId, readings: Set<String>) {
    val detail = lgNoThrow("$what: transaction(${id.v})") { db.transaction(id) }
    val row = detail.row
    assertEquals(id, row.id, "$what: transaction(${id.v}).row.id")
    assertTrue(row.caption in readings, "$what: caption read back ${lgShow(row.caption)}, expected one of ${readings.map(::lgShow)}")
    assertTrue(row.caption.length <= LG_CAP, "$what: caption read back with ${row.caption.length} units, over the 80 of the contract")
    assertEquals(row.caption.ifEmpty { row.name }, row.title, "$what: TransactionRow.title (D54)")

    val listed = lgNoThrow("$what: transactions()") { db.transactions() }.firstOrNull { it.id == id }
        ?: fail("$what: transaction ${id.v} is missing from transactions()")
    assertEquals(row, listed, "$what: transactions() row differs from transaction(id).row")

    val lines = lgNoThrow("$what: budgetDetail") { db.budgetDetail(env.budget, env.month, LG_TODAY) }.lines.filter { it.row.id == id }
    assertEquals(1, lines.size, "$what: transaction ${id.v} must appear exactly once in budgetDetail(...).lines")
    assertEquals(row, lines[0].row, "$what: the budgetDetail line differs from transaction(id).row")

    val candidates = lgNoThrow("$what: refundCandidates") { db.refundCandidates(env.estorno) }
    val cs = candidates.filter { it.transaction == id }
    assertEquals(1, cs.size, "$what: transaction ${id.v} must appear exactly once in refundCandidates")
    assertEquals(row.caption, cs[0].caption, "$what: RefundCandidate.caption differs from the row")
    assertEquals(row.name, cs[0].name, "$what: RefundCandidate.name (alias else bank text) differs from the row")
    assertEquals(cs[0].caption.ifEmpty { cs[0].name }, cs[0].title, "$what: RefundCandidate.title (D54)")
    for (n in candidates.filter { it.notice.v != 0L }) {
        assertEquals("", n.caption, "$what: a pending recado has no legenda (RefundCandidate of NoticeId(${n.notice.v}))")
    }
}

/** The whole state against the model: ids, every read, no legenda on the pending recados. */
private suspend fun lgSweep(db: Db, what: String, env: LgEnv) {
    val rows = lgNoThrow("$what: transactions()") { db.transactions() }
    assertEquals(env.txs.keys, rows.map { it.id }.toSet(), "$what: transactions() ids")
    assertEquals(env.free.toSet() + setOf(env.estorno), db.pending().map { it.id }.toSet(), "$what: pending()")
    for (r in rows) {
        val want = env.txs.getValue(r.id)
        assertTrue(r.caption in want, "$what: transaction ${r.id.v} caption ${lgShow(r.caption)}, expected one of ${want.map(::lgShow)}")
        lgCheckReads(db, what, env, r.id, want)
    }
}

/**
 * One write of [caption] through [kind] and the whole contract around it: nothing throws; what the
 * KDoc refuses (every reading of the cleaned legenda over 80 units) returns 0/false with nothing
 * written and the previous state kept; what is accepted reads back one of [readings] — the cleaned
 * text — in every read, with no partial write. [exact] is the hand-computed clean text of a table
 * case: it must agree with the model and its length decides.
 */
private suspend fun lgStep(
    db: Db,
    what: String,
    env: LgEnv,
    kind: String,
    notice: NoticeId,
    editId: TransactionId,
    caption: String,
    exact: String? = null,
) {
    val readings = if (exact == null) {
        lgReadings(caption)
    } else {
        val model = lgReadings(caption)
        assertTrue(exact in model, "$what: the model readings ${model.map(::lgShow)} do not include the hand-computed clean text ${lgShow(exact)}")
        setOf(exact)
    }
    val lens = readings.map { it.length }
    val mustRefuse = lens.all { it > LG_CAP }
    val mustAccept = lens.all { it <= LG_CAP }
    val form = TransactionForm(
        budget = env.budget, tags = listOf("mercado"), total = Cents(1234), installments = 1,
        merchant = "LOJA LEGENDA", date = LG_TODAY, time = LocalTime(12, 34), caption = caption,
    )

    val v0 = db.version.value
    val rows0 = db.transactions()
    val pending0 = db.pending()
    val detail0 = if (kind == "edit") db.transaction(editId) else null

    val id: TransactionId
    val wrote: Boolean
    when (kind) {
        "accept" -> {
            id = lgNoThrow(what) { db.accept(notice, form) }
            wrote = id.v != 0L
        }
        "addManual" -> {
            id = lgNoThrow(what) { db.addManual(form).transaction }
            wrote = id.v != 0L
        }
        else -> {
            id = editId
            wrote = lgNoThrow(what) { db.edit(editId, form) }
        }
    }

    if (!wrote) {
        if (mustAccept) fail("$what: must be accepted (the cleaned legenda is ${lens.map { "$it units" }}) but was refused")
        assertEquals(v0, db.version.value, "$what: a refused write must not bump version (nothing is written)")
        assertEquals(rows0, db.transactions(), "$what: a refused write changed transactions() (nothing may be written)")
        assertEquals(pending0, db.pending(), "$what: a refused write changed pending()")
        if (detail0 != null) assertEquals(detail0, db.transaction(editId), "$what: a refused edit must keep the transaction as it was")
    } else {
        if (mustRefuse) fail("$what: must be refused (the cleaned legenda is ${lens.map { "$it units" }}, over 80) but was written (id=${id.v})")
        if (kind != "edit") assertTrue(rows0.none { it.id == id }, "$what: the new id ${id.v} was already in use")
        if (kind == "accept") assertTrue(db.pending().none { it.id == notice }, "$what: the accepted recado is still in pending()")
        env.txs[id] = readings
        if (kind == "accept") env.free.remove(notice)
        lgCheckReads(db, what, env, id, readings)
    }
}

/**
 * The whole run of one case: a real Db, one seed, [table] through the three writes first, then
 * [iterations] hostile captions, the full state checked every 30 steps and at the end.
 */
private suspend fun lgRun(seed: Long, iterations: Int, table: List<LgCase>, gen: (Random) -> String) {
    lgWithDb { db ->
        val rnd = Random(seed)
        assertTrue(db.addSource(LG_PKG, "Banco Legenda", LG_NOW), "seed=$seed setup: addSource")
        val budget = db.createBudget("Mercado", Cents(100_000), LG_TODAY)
        assertNotEquals(0L, budget.v, "seed=$seed setup: createBudget")
        val env = LgEnv(
            budget = budget,
            month = db.monthOf(LG_TODAY),
            estorno = lgCapture(db, "estorno", 1, 1000).first(),
            free = lgCapture(db, "compra", 260, 0).toMutableList(),
            txs = LinkedHashMap(),
        )
        val seedTx = db.addManual(
            TransactionForm(
                budget = budget, tags = listOf("mercado"), total = Cents(100), installments = 1,
                merchant = "LOJA SEED", date = LG_TODAY, time = LocalTime(9, 0),
            ),
        ).transaction
        assertNotEquals(0L, seedTx.v, "seed=$seed setup: seed transaction")
        env.txs[seedTx] = setOf("")

        for ((index, case) in table.withIndex()) {
            for (kind in listOf("accept", "addManual", "edit")) {
                if (kind == "accept" && env.free.isEmpty()) continue
                val notice = env.free.firstOrNull() ?: NoticeId(0)
                val editId = if (kind == "edit") env.txs.keys.random(rnd) else TransactionId(0)
                lgStep(
                    db, "seed=$seed table[$index ${case.note}] $kind caption=${lgShow(case.caption)}", env,
                    kind, notice, editId, case.caption, case.expected,
                )
            }
        }

        repeat(iterations) { i ->
            val caption = gen(rnd)
            val roll = rnd.nextInt(10)
            val kind = when {
                roll < 4 && env.free.isNotEmpty() -> "accept"
                roll < 7 -> "addManual"
                else -> "edit"
            }
            val notice = env.free.firstOrNull() ?: NoticeId(0)
            val editId = if (kind == "edit") env.txs.keys.random(rnd) else TransactionId(0)
            lgStep(db, "seed=$seed i=$i $kind caption=${lgShow(caption)}", env, kind, notice, editId, caption)
            if (i % 30 == 29) lgSweep(db, "seed=$seed i=$i sweep", env)
        }
        lgSweep(db, "seed=$seed end", env)
    }
}

class LegendaFuzzTest {

    /** Seed 0x1E6E11DL: the general hostile mix through the three writes and the four reads. */
    @Test
    fun `accept addManual and edit with hostile captions never throw and read back the cleaned legenda`() = lgRealTime {
        lgRun(0x1E6E11DL, iterations = 150, table = emptyList()) { rnd -> lgCaption(rnd) }
    }

    /** Seed 0xB0DA81L: lengths 78..100 before cleaning, astral units of 2, CRLF that shrinks, edges that trim. */
    @Test
    fun `captions at the 80-unit boundary are accepted or refused by the length after cleaning`() = lgRealTime {
        lgRun(0xB0DA81L, iterations = 120, table = LG_LIMIT_TABLE) { rnd -> lgBoundary(rnd) }
    }

    /** Seed 0x1B0EA5EL: line breaks to one space, trim at the edges, whitespace-only to no legenda. */
    @Test
    fun `line breaks become one space the edges are trimmed and whitespace-only means no legenda`() = lgRealTime {
        lgRun(0x1B0EA5EL, iterations = 150, table = LG_CLEAN_TABLE) { rnd -> lgCleanGen(rnd) }
    }

    /** Seed 0xD28F012L: U+FEFF/U+FFFE/U+FFFF and lone surrogates to U+FFFD (D28); everything else exact, no normalization. */
    @Test
    fun `D28 characters lone surrogates combining marks and RTL read back as the storable text`() = lgRealTime {
        lgRun(0xD28F012L, iterations = 150, table = LG_UNICODE_TABLE) { rnd -> lgUnicodeGen(rnd) }
    }
}
