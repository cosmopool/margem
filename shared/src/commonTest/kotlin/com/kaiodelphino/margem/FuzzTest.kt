package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Fuzz with hostile input (architecture §10): fixed seeds, bounded iterations, a real SQLite file per test.
// Every failure message carries the seed, the iteration and the offending input, escaped by show().

private val TODAY = LocalDate(2026, 10, 14)
private const val PKG = "com.banco.fuzz"
private const val NOW = 1_760_000_000_000L

/** Totals at or above this (R$ 100 bilhões) are absurd: refusing them is allowed, crashing is not. */
private const val ABSURD = 10_000_000_000_000L

private fun dbTest(body: suspend (Db) -> Unit) = runTest(timeout = 60.seconds) {
    val path = Path(SystemTemporaryDirectory, "margem-fuzz-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))
        try {
            body(db)
        } finally {
            db.close()
        }
    } finally {
        for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
    }
}

// ---- hostile text ----

private val ATOMS = listOf(
    "a", "Z", "0", "7", "42", " ", "  ", "\t", "\n", "\r\n",
    " ", " ", " ", "​", "﻿", "‎", "‏", "‮", "⁦", "⁩",
    "\u0000", "\u0001", "\u001B", "\u007F", "\u0085", " ",
    "R$", "R$ ", "R$ ", "R$ ", ",", ".", "-", "+", "*", "%", "_", "'", "\"", "\\", ";", "--", "/*", "*/", "`",
    "'; DROP TABLE notices;--", "\" OR \"1\"=\"1", "' OR '1'='1", "%_%", "NULL", "\\0", "?", "?1", ":name",
    "😀", "👨‍👩‍👧", "🇧🇷",
    "é", "É", "ß", "İ", "ﬁ", "مرحبا", "שלום", "中文", "١٢٣", "１２３", "�", "￿",
    "Compra aprovada", "no valor de", " em ", "Estorno", "Pix recebido", "PAG*JOSE", "1.234,56", "0,01", "31,50",
)

private fun digitRun(rnd: Random): String {
    val n = listOf(1, 2, 3, 5, 10, 18, 19, 20, 25, 100, 1000).random(rnd)
    val digits = buildString { repeat(n) { append(Char('0'.code + rnd.nextInt(10))) } }
    return "R$ " + digits + (if (rnd.nextBoolean()) "," + rnd.nextInt(100).toString().padStart(2, '0') else "")
}

private fun supplementary(rnd: Random): String {
    val v = rnd.nextInt(0x10000, 0x110000) - 0x10000
    return "" + Char(0xD800 + (v shr 10)) + Char(0xDC00 + (v and 0x3FF))
}

/** Random hostile text: valid UTF-16 (surrogates only in pairs). */
private fun hostile(rnd: Random, maxAtoms: Int = 12): String = buildString {
    repeat(rnd.nextInt(0, maxAtoms + 1)) {
        when (rnd.nextInt(12)) {
            0 -> append(digitRun(rnd))
            1 -> append(Char(rnd.nextInt(0x20, 0xD800)))
            2 -> append(supplementary(rnd))
            else -> append(ATOMS.random(rnd))
        }
    }
}

/** ~100 KB of hostile text. */
private fun huge(rnd: Random): String =
    if (rnd.nextBoolean()) "R$ " + "9".repeat(100_000)
    else buildString { while (length < 100_000) append(hostile(rnd, 40)) }

/** Escaped, truncated rendering of [s] for failure messages. */
private fun show(s: String): String = buildString {
    append('"')
    for (c in s.take(200)) {
        if (c.code in 0x20..0x7E && c != '"' && c != '\\') append(c)
        else append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }
    append('"')
    if (s.length > 200) append("…(${s.length} chars)")
}

private fun showRaw(r: RawNotice): String =
    "RawNotice(pkg=${show(r.packageName)}, label=${show(r.appLabel)}, key=${show(r.key)}, postedAt=${r.postedAt}, " +
        "when=${r.whenMs}, title=${show(r.title)}, text=${show(r.text)}, bigText=${show(r.bigText)}, " +
        "subText=${show(r.subText)}, lines=${show(r.textLines)}, category=${show(r.category)}, " +
        "channel=${show(r.channelId)}, extras=${show(r.extrasJson)})"

private fun firstDiff(e: String, a: String): String {
    var i = 0
    while (i < e.length && i < a.length && e[i] == a[i]) i++
    return "at char $i expected ${show(e.drop(i).take(20))} got ${show(a.drop(i).take(20))} (lengths ${e.length}/${a.length})"
}

/** Which fields of a read-back RawNotice differ from what was sent; "" when equal. */
/**
 * A text as the Db stores and reads it back (architecture D28, decided after this suite's first run):
 * U+FEFF, U+FFFE, U+FFFF and lone surrogates become U+FFFD; everything else is exact.
 */
private fun stored(s: String): String = buildString {
    for (i in s.indices) {
        val c = s[i]
        val lone = (c.isHighSurrogate() && (i + 1 >= s.length || !s[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !s[i - 1].isHighSurrogate()))
        append(if (c == '\uFEFF' || c == '\uFFFE' || c == '\uFFFF' || lone) '\uFFFD' else c)
    }
}

private fun rawDiff(e: RawNotice, a: RawNotice): String {
    val texts = listOf(
        "packageName" to (stored(e.packageName) to a.packageName), "appLabel" to (stored(e.appLabel) to a.appLabel),
        "key" to (stored(e.key) to a.key), "title" to (stored(e.title) to a.title), "text" to (stored(e.text) to a.text),
        "bigText" to (stored(e.bigText) to a.bigText), "subText" to (stored(e.subText) to a.subText),
        "textLines" to (stored(e.textLines) to a.textLines), "category" to (stored(e.category) to a.category),
        "channelId" to (stored(e.channelId) to a.channelId), "extrasJson" to (stored(e.extrasJson) to a.extrasJson),
    ).filter { it.second.first != it.second.second }.map { "${it.first} ${firstDiff(it.second.first, it.second.second)}" }
    val longs = listOfNotNull(
        if (e.postedAt != a.postedAt) "postedAt ${e.postedAt}/${a.postedAt}" else null,
        if (e.whenMs != a.whenMs) "whenMs ${e.whenMs}/${a.whenMs}" else null,
    )
    return (texts + longs).joinToString("; ")
}

private fun isNoise(r: RawNotice) = listOf(r.title, r.text, r.bigText, r.subText, r.textLines).all { it.isBlank() }

/** TransactionForm.tags cleaning, as the KDoc says: trimmed, lowercased, blanks and repeats dropped. */
private fun cleanTags(tags: List<String>) = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

// ---- money formatting for the parseBrl round trip ----

private val AROUND = listOf("", " ", "  ", "\t", " ", " ", "  ")
private val PREFIX = listOf("", "R$", "R$ ", "R$ ", "R$ ", "R$  ")

private fun groupThousands(digits: String): String {
    val sb = StringBuilder()
    for ((i, c) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append('.')
        sb.append(c)
    }
    return sb.toString()
}

/** Brazilian money: optional R$, optional thousand dots, ",dd" (omittable when whole), spaces/NBSP around. */
private fun formatBrl(cents: Long, rnd: Random): String {
    val reais = (cents / 100).toString()
    val c = cents % 100
    val int = if (rnd.nextBoolean()) groupThousands(reais) else reais
    val dec = if (c == 0L && rnd.nextBoolean()) "" else "," + c.toString().padStart(2, '0')
    return AROUND.random(rnd) + PREFIX.random(rnd) + int + dec + AROUND.random(rnd)
}

class FuzzTest {

    // ---- parseBrl ----

    @Test
    fun parseBrlRoundTripsBrazilianMoney() {
        val seed = 0xB121L
        val rnd = Random(seed)
        repeat(4000) { i ->
            val digits = rnd.nextInt(1, 12) // up to R$ 999.999.999,99
            var max = 1L
            repeat(digits) { max *= 10 }
            val cents = rnd.nextLong(0, max)
            val text = formatBrl(cents, rnd)
            val got = try {
                parseBrl(text)
            } catch (e: Throwable) {
                fail("seed=$seed i=$i parseBrl threw $e for ${show(text)}", e)
            }
            assertEquals(Cents(cents), got, "seed=$seed i=$i parseBrl(${show(text)})")
        }
    }

    @Test
    fun parseBrlNeverThrowsNorGoesNegative() {
        val seed = 0xB122L
        val rnd = Random(seed)
        val extra = listOf("\uD800", "\uDC00", "\uDBFF\uD800", "-", "−", "+", "e", "E", "x", "0", "00", ",", ".", "..", ",,")
        repeat(6000) { i ->
            val text = when (rnd.nextInt(4)) {
                // a valid amount with one hostile atom injected somewhere
                0 -> formatBrl(rnd.nextLong(0, 100_000_000), rnd).let {
                    val at = rnd.nextInt(0, it.length + 1)
                    it.substring(0, at) + (ATOMS + extra).random(rnd) + it.substring(at)
                }
                1 -> buildString { repeat(rnd.nextInt(0, 30)) { append(listOf("1", "9", "0", ",", ".", "-", " ", "R$").random(rnd)) } }
                2 -> buildString { repeat(rnd.nextInt(0, 8)) { append((ATOMS + extra).random(rnd)) } }
                else -> hostile(rnd)
            }
            val got = try {
                parseBrl(text)
            } catch (e: Throwable) {
                fail("seed=$seed i=$i parseBrl threw $e for ${show(text)}", e)
            }
            assertTrue(got.v >= 0, "seed=$seed i=$i parseBrl(${show(text)}) = ${got.v} < 0")
        }
        // Absurdly long inputs: no throw, no negative, bounded time.
        for (text in listOf("9".repeat(100_000), "R$ " + "1.000".repeat(20_000) + ",00", "0".repeat(100_000) + "1", ",".repeat(100_000), huge(rnd))) {
            val got = try {
                parseBrl(text)
            } catch (e: Throwable) {
                fail("seed=$seed long parseBrl threw $e for ${show(text)}", e)
            }
            assertTrue(got.v >= 0, "seed=$seed long parseBrl(${show(text)}) = ${got.v} < 0")
        }
    }

    @Test
    fun parseBrlCraftedValues() {
        val valid = listOf(
            "31,50" to 3150L, "1.234,56" to 123456L, "R$ 12" to 1200L, "R$ 0,05" to 5L, "R$ 1.200" to 120000L,
            "R$ 600,00" to 60000L, "0,00" to 0L, "0" to 0L, " R$ 31,50 " to 3150L,
            " 1.234,56 " to 123456L, "R$ 1.000.000,00" to 100000000L, "\tR$ 46,00\n" to 4600L,
        )
        for ((text, cents) in valid) assertEquals(Cents(cents), parseBrl(text), "parseBrl(${show(text)})")

        val invalid = listOf(
            "", " ", " ", " ", "R$", "R$ ", "R$ ", ",", ".", ",,", "..", "-", "--",
            // negative: a spending value is never negative
            "-5", "-5,00", "R$ -5,00", "-R$ 5,00", "R$ - 5", "−5,00",
            // letters, emoji, control chars
            "abc", "R$ abc", "12a", "a12", "12,3a", "😀", "R$ 😀", "12,00\u0000", "NaN", "Infinity", "1e5", "0x10",
            // mixed or malformed separators
            "1,234.56", "1.234.56", "1,234,56", "12,345,67", "1.23,45", "12.3456,00", "12,345", "R$ R$ 12", "R$ 12,00 e R$ 3,00",
            // overflow: more than Long.MAX_VALUE centavos
            "9".repeat(30), "R$ 99999999999999999999,99", "92.233.720.368.547.758,08", "R$ " + "9".repeat(10_000),
        )
        for (text in invalid) assertEquals(Cents(0), parseBrl(text), "parseBrl(${show(text)}) should be Cents(0)")
    }

    // ---- capture + reads ----

    @Test
    fun captureStoresHostileTextsExactly() = dbTest { db ->
        val seed = 0xCA97L
        val rnd = Random(seed)
        assertTrue(db.addSource(PKG, "Banco Fuzz", NOW))
        val sent = HashMap<String, Pair<Int, RawNotice>>() // key -> (iteration, raw)
        repeat(200) { i ->
            val long = i % 40 == 7
            fun field(): String = (if (long && rnd.nextInt(3) == 0) huge(rnd) else hostile(rnd)).trim()
            val raw = RawNotice(
                packageName = PKG,
                appLabel = hostile(rnd, 3).trim(),
                key = i.toString().padStart(4, '0') + "|" + hostile(rnd, 4),
                postedAt = NOW + i * 1000L,
                whenMs = if (rnd.nextBoolean()) 0 else NOW - rnd.nextLong(1, 10_000_000),
                title = field(), text = field(), bigText = if (rnd.nextInt(3) == 0) field() else "",
                subText = if (rnd.nextInt(3) == 0) field() else "", textLines = if (rnd.nextInt(4) == 0) field() else "",
                category = hostile(rnd, 2).trim(), channelId = hostile(rnd, 2).trim(),
                extrasJson = "{\"android.title\":\"" + hostile(rnd, 4) + "\"}",
            )
            val saved = try {
                capture(db, raw)
            } catch (e: Throwable) {
                fail("seed=$seed i=$i capture threw $e for ${showRaw(raw)}", e)
            }
            assertEquals(!isNoise(raw), saved, "seed=$seed i=$i capture returned $saved for ${showRaw(raw)}")
            if (saved) sent[stored(raw.key)] = i to raw
        }

        val pending = db.pending()
        assertEquals(sent.size, pending.size, "seed=$seed pending() size")
        assertEquals(sent.size, db.pendingCount(), "seed=$seed pendingCount()")
        assertEquals(pending.size, pending.map { it.id }.toSet().size, "seed=$seed notice ids are unique")
        for (k in 1 until pending.size) {
            assertTrue(pending[k - 1].raw.postedAt >= pending[k].raw.postedAt, "seed=$seed pending() not newest first at $k")
        }
        for (row in pending) {
            val (i, raw) = sent[row.raw.key] ?: fail("seed=$seed pending() has an unknown key ${show(row.raw.key)}")
            assertTrue(row.id.v != 0L, "seed=$seed i=$i zero id")
            assertEquals("", rawDiff(raw, row.raw), "seed=$seed i=$i pending() raw differs for ${showRaw(raw)}")
            assertEquals(NoticeStatus.PENDING, row.status, "seed=$seed i=$i status")
            assertTrue(row.parsed.cents.v >= 0, "seed=$seed i=$i parsed.cents ${row.parsed.cents.v} < 0 for ${showRaw(raw)}")
            val one = db.notice(row.id)
            assertEquals(row.id, one.id, "seed=$seed i=$i notice(id).id")
            assertEquals("", rawDiff(raw, one.raw), "seed=$seed i=$i notice(id) raw differs for ${showRaw(raw)}")
            assertTrue(one.parsed.cents.v >= 0, "seed=$seed i=$i notice(id) parsed.cents ${one.parsed.cents.v} < 0")
        }

        // Riscados read back the same text.
        val some = pending.take(20)
        for (row in some) assertTrue(db.dismiss(row.id), "seed=$seed dismiss ${row.id}")
        val dismissed = db.dismissed().associateBy { it.id }
        for (row in some) {
            val d = dismissed[row.id] ?: fail("seed=$seed dismissed() misses ${row.id}")
            assertEquals("", rawDiff(row.raw, d.raw), "seed=$seed dismissed() raw differs for ${showRaw(row.raw)}")
            assertTrue(db.restore(row.id), "seed=$seed restore ${row.id}")
        }
        assertEquals(sent.size, db.pendingCount(), "seed=$seed pendingCount() after dismiss + restore")

        // Hostile ids: zero values, refusals, no crash.
        for (id in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(NoticeRow(), db.notice(NoticeId(id)), "notice($id)")
            assertEquals(false, db.dismiss(NoticeId(id)), "dismiss($id)")
            assertEquals(false, db.restore(NoticeId(id)), "restore($id)")
        }
    }

    @Test
    fun captureDropsNoiseAndForeignPackages() = dbTest { db ->
        val seed = 0xCA98L
        val rnd = Random(seed)
        assertTrue(db.addSource(PKG, "Banco Fuzz", NOW))
        var i = 0
        // All-blank content, whatever else is set.
        val blanks = listOf("", " ", "\t", "\n", " ", "    ", "\r\n")
        repeat(100) {
            val raw = RawNotice(
                packageName = PKG, appLabel = hostile(rnd, 3), key = "blank|$i", postedAt = NOW + i * 1000L, whenMs = NOW,
                title = blanks.random(rnd), text = blanks.random(rnd), bigText = blanks.random(rnd),
                subText = blanks.random(rnd), textLines = blanks.random(rnd),
                category = hostile(rnd, 3), channelId = hostile(rnd, 3), extrasJson = hostile(rnd, 3),
            )
            val saved = try {
                capture(db, raw)
            } catch (e: Throwable) {
                fail("seed=$seed i=$i capture threw $e for ${showRaw(raw)}", e)
            }
            assertEquals(false, saved, "seed=$seed i=$i blank notice saved: ${showRaw(raw)}")
            i++
        }
        // Packages that are not a source, including LIKE wildcards and SQL around the real one.
        val foreign = listOf(
            "", " ", "com.banco.fuz_", "com.banco.fu%", "%", "_", "com.banco.%", "COM.BANCO.FUZZ",
            "$PKG\u0000", "$PKG'; DROP TABLE sources;--", "' OR '1'='1", "com.banco.fuzz.extra", "com‮.banco.fuzz",
        )
        repeat(100) {
            val pkg = if (rnd.nextInt(3) == 0) hostile(rnd, 4) else foreign.random(rnd)
            if (pkg == PKG) return@repeat
            val raw = RawNotice(
                packageName = pkg, appLabel = "X", key = "foreign|$i", postedAt = NOW + i * 1000L,
                title = "Compra aprovada", text = "R$ 12,34 em " + hostile(rnd).trim().ifEmpty { "LOJA" },
            )
            val saved = try {
                capture(db, raw)
            } catch (e: Throwable) {
                fail("seed=$seed i=$i capture threw $e for ${showRaw(raw)}", e)
            }
            assertEquals(false, saved, "seed=$seed i=$i notice of a non-source saved: ${showRaw(raw)}")
            i++
        }
        assertEquals(0, db.pendingCount(), "seed=$seed pendingCount()")
        assertEquals(emptyList(), db.pending(), "seed=$seed pending()")
        assertEquals(emptyList(), db.dismissed(), "seed=$seed dismissed()")
    }

    @Test
    fun captureDropsExactRepostsOfHostileContent() = dbTest { db ->
        val seed = 0xCA99L
        val rnd = Random(seed)
        assertTrue(db.addSource(PKG, "Banco Fuzz", NOW))
        var expected = 0
        repeat(40) { i ->
            var text = hostile(rnd).trim()
            if (text.isEmpty()) text = "R$ 1,00"
            val base = RawNotice(
                packageName = PKG, appLabel = "Banco", key = "repost|$i|" + hostile(rnd, 3),
                postedAt = NOW + i * 10_000_000L, whenMs = NOW + i,
                title = hostile(rnd, 4).trim(), text = text, bigText = if (rnd.nextBoolean()) hostile(rnd).trim() else "",
            )
            suspend fun cap(r: RawNotice, what: String): Boolean = try {
                capture(db, r)
            } catch (e: Throwable) {
                fail("seed=$seed i=$i $what: capture threw $e for ${showRaw(r)}", e)
            }
            assertTrue(cap(base, "first"), "seed=$seed i=$i first post not saved: ${showRaw(base)}")
            expected++
            // Same key, content and when ≠ 0, a day later: re-post.
            val later = base.copy(postedAt = base.postedAt + 86_400_000L)
            assertEquals(false, cap(later, "re-post a day later"), "seed=$seed i=$i re-post a day later saved: ${showRaw(later)}")
            // Same key and content, no when, 30 s after the latest: re-post.
            val soon = base.copy(postedAt = base.postedAt + 30_000L, whenMs = 0)
            assertEquals(false, cap(soon, "re-post 30 s later"), "seed=$seed i=$i re-post 30 s later saved: ${showRaw(soon)}")
            // One invisible character more is different content: saved.
            val zwsp = base.copy(text = base.text + "​", postedAt = base.postedAt + 40_000L)
            assertTrue(cap(zwsp, "content + ZWSP"), "seed=$seed i=$i content + ZWSP not saved: ${showRaw(zwsp)}")
            expected++
            // Same characters split differently across fields is different content: saved.
            val cut = if (base.text[0].isHighSurrogate()) 2 else 1
            val shifted = base.copy(
                title = base.title + base.text.take(cut), text = base.text.drop(cut).trim(), postedAt = base.postedAt + 50_000L,
            )
            if (shifted.title != base.title && !isNoise(shifted)) {
                assertTrue(cap(shifted, "shifted fields"), "seed=$seed i=$i shifted fields not saved: ${showRaw(shifted)}")
                expected++
            }
            // Same content and when under another key: another notification, saved.
            val other = base.copy(key = base.key + "#2", postedAt = base.postedAt + 60_000L)
            assertTrue(cap(other, "other key"), "seed=$seed i=$i same content under another key not saved: ${showRaw(other)}")
            expected++
        }
        assertEquals(expected, db.pendingCount(), "seed=$seed pendingCount()")
    }

    // ---- accept / addManual / edit with absurd values ----

    @Test
    fun acceptRefusesAbsurdFormsAndKeepsAcceptedOnesConsistent() = dbTest { db ->
        val seed = 0xACCL
        val rnd = Random(seed)
        assertTrue(db.addSource(PKG, "Banco Fuzz", NOW))
        repeat(150) { i ->
            val raw = RawNotice(
                packageName = PKG, appLabel = "Banco", key = "acc|$i", postedAt = NOW + i * 1000L,
                title = "Compra aprovada", text = "R$ 10,00 em LOJA $i",
            )
            assertTrue(capture(db, raw), "seed=$seed i=$i setup capture")
        }
        val notices = db.pending().associate { it.raw.key to it.id }
        val budget = db.createBudget("Mercado", Cents(100_000), TODAY)
        assertNotEquals(0L, budget.v, "createBudget")

        val budgets = listOf(budget, budget, budget, budget, BudgetId(0), BudgetId(-1), BudgetId(budget.v + 1000), BudgetId(Long.MAX_VALUE))
        val randomTotal = Long.MIN_VALUE + 1 // marker: a random ordinary total
        val totals = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 99L, randomTotal, randomTotal, ABSURD, Long.MAX_VALUE / 400, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val counts = listOf(Int.MIN_VALUE, -1, 0, 1, 1, 2, 3, 12, 399, 400, 401, Int.MAX_VALUE)
        val tagLists = listOf(
            emptyList(), listOf(""), listOf("  ", "\t\n", " ", " "), listOf("CAFÉ"), listOf("café", " Café ", "CAFÉ"),
            listOf("'; DROP TABLE tags;--"), listOf("100%", "a_b", "%", "_"), listOf("😀"), listOf("İSTANBUL", "ß"),
            listOf("x".repeat(5000)), listOf("mercado", "MERCADO", "Mercado "), listOf("a\u0000b", "‮evil"),
        )
        val merchants = listOf(
            "", "Bar \"do\" Zé", "O'Hara's", "linha1\nlinha2", "'; DROP TABLE transactions;--", "PAG*JOSE 0231", "‮evil", "100%_",
        )
        val refusing = listOf<(TransactionForm) -> TransactionForm>(
            { it.copy(total = Cents(0)) }, { it.copy(total = Cents(-5)) }, { it.copy(installments = 0) },
            { it.copy(installments = 401) }, { it.copy(tags = listOf(" ", " ")) }, { it.copy(budget = BudgetId(0)) },
        )

        repeat(150) { i ->
            val manual = i % 3 == 0
            val notice = notices["acc|$i"] ?: fail("seed=$seed i=$i setup notice missing")
            val totalPick = totals.random(rnd)
            val form = TransactionForm(
                budget = budgets.random(rnd),
                tags = if (rnd.nextInt(5) == 0) listOf(hostile(rnd, 4), hostile(rnd, 2)) else tagLists.random(rnd),
                total = Cents(if (totalPick == randomTotal) rnd.nextLong(1, 1_000_000_000) else totalPick),
                installments = counts.random(rnd),
                merchant = if (rnd.nextInt(4) == 0) hostile(rnd).trim() else merchants.random(rnd),
                date = if (rnd.nextBoolean()) TODAY else LocalDate(rnd.nextInt(2000, 2041), rnd.nextInt(1, 13), rnd.nextInt(1, 29)),
                time = LocalTime(rnd.nextInt(24), rnd.nextInt(60)),
            )
            val what = "seed=$seed i=$i ${if (manual) "addManual" else "accept"} budget=${form.budget.v} " +
                "total=${form.total.v} installments=${form.installments} tags=${form.tags.map { show(it) }} merchant=${show(form.merchant)} date=${form.date}"
            val valid = form.budget == budget && cleanTags(form.tags).isNotEmpty() && form.total.v > 0 && form.installments in 1..400
            val absurd = form.total.v >= ABSURD

            val txBefore = db.transactions()
            val tagsBefore = db.tags()
            val id = try {
                if (manual) db.addManual(form).transaction else db.accept(notice, form)
            } catch (e: Throwable) {
                fail("$what threw $e", e)
            }

            if (!valid || (absurd && id.v == 0L)) {
                assertEquals(0L, id.v, "$what: should be refused")
                assertEquals(txBefore, db.transactions(), "$what: refused but transactions() changed")
                assertEquals(tagsBefore, db.tags(), "$what: refused but tags() changed")
                if (!manual) assertEquals(NoticeStatus.PENDING, db.notice(notice).status, "$what: refused but notice not pending")
                return@repeat
            }

            assertNotEquals(0L, id.v, "$what: should be accepted")
            val d = db.transaction(id)
            assertEquals(id, d.row.id, "$what: transaction(id).row.id")
            assertEquals(form.total, d.row.total, "$what: total")
            assertEquals(form.installments, d.row.installments, "$what: installments")
            assertEquals(form.budget, d.row.budget, "$what: budget")
            assertEquals(stored(form.merchant), d.row.merchant, "$what: merchant")
            assertEquals(form.date, d.row.date, "$what: date")
            assertEquals(form.time, d.row.time, "$what: time")
            assertEquals(if (manual) NoticeId(0) else notice, d.row.notice, "$what: notice")
            if (manual) assertEquals(false, d.row.corrected, "$what: manual is never corrected")
            assertEquals(cleanTags(form.tags).sorted(), d.row.tags.sorted(), "$what: tags")

            val n = form.installments
            val parts = d.installments.sortedBy { it.k }
            assertEquals((1..n).toList(), parts.map { it.k }, "$what: installment ks")
            val each = form.total.v / n
            for (p in parts) {
                val expectedCents = if (p.k < n) each else form.total.v - each * (n - 1)
                assertEquals(expectedCents, p.cents.v, "$what: installment ${p.k} cents")
                assertEquals(form.date.plus(p.k - 1, DateTimeUnit.MONTH), p.date, "$what: installment ${p.k} date")
            }
            var sum = 0L
            for (p in parts) sum += p.cents.v
            assertEquals(form.total.v, sum, "$what: installments sum to the total")

            val listed = db.transactions().firstOrNull { it.id == id } ?: fail("$what: missing from transactions()")
            assertEquals(d.row, listed, "$what: transactions() row differs from transaction(id).row")
            if (!manual) {
                assertEquals(NoticeStatus.PROCESSED, db.notice(notice).status, "$what: notice status after accept")
                assertTrue(db.pending().none { it.id == notice }, "$what: accepted notice still in pending()")
            }

            // A refused edit changes nothing.
            val bad = refusing.random(rnd)(form)
            val tagsBeforeEdit = db.tags()
            val edited = try {
                db.edit(id, bad)
            } catch (e: Throwable) {
                fail("$what: edit threw $e", e)
            }
            assertEquals(false, edited, "$what: refused edit returned true (total=${bad.total.v} n=${bad.installments} tags=${bad.tags.map { show(it) }} budget=${bad.budget.v})")
            assertEquals(d, db.transaction(id), "$what: refused edit changed the transaction")
            assertEquals(tagsBeforeEdit, db.tags(), "$what: refused edit changed tags()")
        }

        // Unknown targets.
        val ok = TransactionForm(budget = budget, tags = listOf("t"), total = Cents(100), date = TODAY)
        for (id in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertEquals(TransactionId(0), db.accept(NoticeId(id), ok), "accept(NoticeId($id))")
            assertEquals(false, db.edit(TransactionId(id), ok), "edit(TransactionId($id))")
            assertEquals(TransactionDetail(), db.transaction(TransactionId(id)), "transaction($id)")
        }

        // Absurd previews: zero for invalid inputs, never a crash.
        for ((total, n) in listOf(-1L to 1, 0L to 1, Long.MIN_VALUE to 1, 100L to 0, 100L to 401, 100L to Int.MAX_VALUE, 100L to -1)) {
            assertEquals(Preview(), db.previewAccept(budget, TODAY, Cents(total), n, TODAY), "previewAccept(total=$total, n=$n)")
        }
        assertEquals(Preview(), db.previewAccept(BudgetId(-1), TODAY, Cents(100), 1, TODAY), "previewAccept(unknown budget)")
        try {
            db.previewAccept(budget, TODAY, Cents(Long.MAX_VALUE), 1, TODAY)
            db.previewAccept(budget, TODAY, Cents(Long.MAX_VALUE), 400, TODAY)
        } catch (e: Throwable) {
            fail("seed=$seed previewAccept(Long.MAX_VALUE) threw $e", e)
        }

        // Whatever was accepted, every read survives it and never shows negative spending.
        val month = db.monthOf(TODAY)
        try {
            db.ensureMonths(month)
            for (s in db.budgetsOverview(month, TODAY, BudgetOrder.RISK)) {
                assertTrue(s.month.spent.v >= 0, "seed=$seed budgetsOverview spent ${s.month.spent.v} < 0")
            }
            db.budgetDetail(budget, month, TODAY)
            db.history(YearMonth(2000, 1), YearMonth(2080, 12))
            db.tagRanking(LocalDate(2000, 1, 1), LocalDate(2080, 12, 31))
            db.merchantRanking(LocalDate(2000, 1, 1), LocalDate(2080, 12, 31))
            for ((day, cents) in db.spentByDay(LocalDate(2000, 1, 1), LocalDate(2080, 12, 31))) {
                assertTrue(cents.v > 0, "seed=$seed spentByDay $day = ${cents.v}")
            }
            db.suggestion("'; DROP TABLE transactions;--")
            db.suggestion("100%_")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            fail("seed=$seed a read threw after absurd accepts: $e", e)
        }
    }

    // ---- hostile names ----

    @Test
    fun budgetsKeepHostileNamesExactly() = dbTest { db ->
        val seed = 0xB0D6L
        val rnd = Random(seed)
        val month = db.monthOf(TODAY)
        val fixed = listOf(
            "%", "_", "a_b", "axb", "100%", "1000", "%_%", "'; DROP TABLE budgets;--", "\"; DROP TABLE budgets;--",
            "Mercado 'do' Zé", "\\", "Ünïcödé 中文 😀", "‮evil", "שלום", "a\u0000b", "x".repeat(10_000),
            "é", "linha1\nlinha2", "Café",
        )
        val names = (fixed + List(30) { hostile(rnd, 6).trim() })
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val ids = HashMap<String, BudgetId>()
        for ((i, name) in names.withIndex()) {
            val id = try {
                db.createBudget(name, Cents(10_000L + i), TODAY)
            } catch (e: Throwable) {
                fail("seed=$seed i=$i createBudget(${show(name)}) threw $e", e)
            }
            assertNotEquals(0L, id.v, "seed=$seed i=$i createBudget(${show(name)}) refused")
            ids[name] = id
            val detail = db.budgetDetail(id, month, TODAY)
            assertEquals(stored(name), detail.name, "seed=$seed i=$i budgetDetail name of ${show(name)}")
            assertEquals(Cents(10_000L + i), detail.month.limit, "seed=$seed i=$i limit of ${show(name)}")
        }
        val overview = db.budgetsOverview(month, TODAY, BudgetOrder.AZ)
        assertEquals(names.map(::stored).toSet(), overview.map { it.name }.toSet(), "seed=$seed budgetsOverview names")
        assertEquals(ids.values.toSet(), overview.map { it.id }.toSet(), "seed=$seed budgetsOverview ids")

        // Same name in another case (Unicode too): refused.
        for (name in names) {
            val upper = name.uppercase()
            if (upper == name || upper.lowercase() != name.lowercase()) continue
            assertEquals(BudgetId(0), db.createBudget(upper, Cents(100), TODAY), "seed=$seed createBudget(${show(upper)}) duplicates ${show(name)}")
        }
        assertEquals(BudgetId(0), db.createBudget("CAFÉ", Cents(100), TODAY), "createBudget(\"CAFÉ\") duplicates \"Café\"")
        for (blank in listOf("", " ", "\t\n", " ", " ")) {
            assertEquals(BudgetId(0), db.createBudget(blank, Cents(100), TODAY), "createBudget(${show(blank)})")
        }
        for (limit in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertEquals(BudgetId(0), db.createBudget("limite $limit", Cents(limit), TODAY), "createBudget(limit=$limit)")
        }
        assertEquals(names.size, db.budgetsOverview(month, TODAY, BudgetOrder.AZ).size, "seed=$seed refusals created budgets")

        // Renames read back exactly; renaming onto another budget's name is refused.
        for ((i, name) in names.withIndex()) {
            val id = ids.getValue(name)
            val renamed = "R$i:$name"
            assertTrue(db.renameBudget(id, renamed), "seed=$seed i=$i renameBudget(${show(renamed)})")
            assertEquals(stored(renamed), db.budgetDetail(id, month, TODAY).name, "seed=$seed i=$i renamed name")
        }
        val (a, b) = names.take(2).map { ids.getValue(it) }
        assertEquals(false, db.renameBudget(a, "R1:${names[1]}".uppercase()), "renameBudget onto another budget's name")
        assertEquals(false, db.renameBudget(a, " "), "renameBudget to blank")
        assertEquals(false, db.renameBudget(BudgetId(-1), "nova"), "renameBudget(unknown)")
        assertNotEquals(a, b)

        // An absurd limit: refused, or its steps are non-negative and still sum to the limit (spec §4).
        val huge = db.createBudget("Absurda", Cents(Long.MAX_VALUE), TODAY)
        if (huge.v != 0L) {
            val m = db.budgetDetail(huge, month, TODAY).month
            assertEquals(Cents(Long.MAX_VALUE), m.limit, "absurd limit read back")
            var sum = 0L
            for (w in m.weeks) {
                assertTrue(w.step.v >= 0, "absurd limit: week ${w.start} step ${w.step.v} < 0")
                sum += w.step.v
            }
            assertEquals(Long.MAX_VALUE, sum, "absurd limit: steps sum to the limit")
        }
    }

    @Test
    fun tagsKeepHostileNamesLowercased() = dbTest { db ->
        val seed = 0x7A65L
        val rnd = Random(seed)
        val fixed = listOf(
            "%", "_", "a_b", "axb", "100%", "'; DROP TABLE tags;--", "\" OR \"1\"=\"1", " Padaria ", "CAFÉ", "İSTANBUL",
            "ẞ", "😀", "‮evil", "a\u0000b", "y".repeat(10_000), "linha1\nlinha2",
        )
        val names = (fixed + List(30) { hostile(rnd, 6) }).filter { it.isNotBlank() }.distinctBy { it.trim().lowercase() }
        val ids = HashMap<String, TagId>() // cleaned name -> id
        for ((i, name) in names.withIndex()) {
            val clean = name.trim().lowercase()
            val id = try {
                db.createTag(name)
            } catch (e: Throwable) {
                fail("seed=$seed i=$i createTag(${show(name)}) threw $e", e)
            }
            assertNotEquals(0L, id.v, "seed=$seed i=$i createTag(${show(name)})")
            ids[stored(clean)] = id
        }
        val rows = db.tags()
        assertEquals(ids, rows.associate { it.name to it.id }, "seed=$seed tags() names and ids")
        assertTrue(rows.all { it.uses == 0 && it.soleUses == 0 }, "seed=$seed unused tags have counts")

        // The same name in another case or spacing is the same tag.
        for (name in names) {
            val clean = name.trim().lowercase()
            val variant = " " + name.trim().uppercase() + " "
            if (variant.trim().lowercase() != clean) continue
            assertEquals(ids.getValue(stored(clean)), db.createTag(variant), "seed=$seed createTag(${show(variant)}) is ${show(clean)}")
        }
        assertEquals(ids.getValue("café"), db.createTag("café"), "createTag(\"café\") after \"CAFÉ\"")
        for (blank in listOf("", " ", "\t\n", " ", " ")) assertEquals(TagId(0), db.createTag(blank), "createTag(${show(blank)})")
        assertEquals(ids.size, db.tags().size, "seed=$seed tag count after duplicates and blanks")

        // Renames are trimmed and lowercased; another tag's name, a blank or an unknown tag is refused.
        val first = ids.getValue("%")
        assertTrue(db.renameTag(first, "  Novo 'Nome' ÇÃO "), "renameTag hostile")
        assertTrue(db.tags().any { it.id == first && it.name == "novo 'nome' ção" }, "renamed tag read back")
        assertEquals(false, db.renameTag(first, "A_B"), "renameTag onto another tag's name")
        assertEquals(false, db.renameTag(first, " "), "renameTag to blank")
        assertEquals(false, db.renameTag(TagId(-1), "zzz"), "renameTag(unknown)")
        assertEquals(false, db.mergeTags(first, first), "mergeTags(same)")
        assertEquals(false, db.mergeTags(first, TagId(Long.MAX_VALUE)), "mergeTags(unknown)")
    }

    @Test
    fun aliasesKeepHostileTextAndMatchMerchantsExactly() = dbTest { db ->
        val budget = db.createBudget("Apelidos", Cents(1_000_000), TODAY)
        assertNotEquals(0L, budget.v)
        val pairs = listOf(
            "PAG*JOSE_100%" to "Zé 'do' \"Bar\"",
            "PAG*JOSEX1000" to "100%",
            "Bar \"do\" Zé" to "_",
            "O'Hara" to "'; DROP TABLE merchant_aliases;--",
            "'; DROP TABLE merchant_aliases;--" to "‮evil",
            "linha1\nlinha2" to "a\u0000b",
            "😀 Café" to "z".repeat(10_000),
        )
        fun form(merchant: String, alias: String = "") =
            TransactionForm(budget = budget, tags = listOf("t"), total = Cents(100), merchant = merchant, date = TODAY, time = LocalTime(12, 0), alias = alias)
        val txs = HashMap<String, TransactionId>()
        for ((m, _) in pairs) {
            val id = db.addManual(form(m)).transaction
            assertNotEquals(0L, id.v, "addManual(merchant=${show(m)})")
            txs[m] = id
        }
        val empty = db.addManual(form("")).transaction
        assertNotEquals(0L, empty.v, "addManual(merchant=\"\")")

        for ((m, alias) in pairs) db.setAlias(m, alias)
        db.setAlias("", "Fantasma")
        db.setAlias("  ", "Fantasma")
        suspend fun aliasOf(id: TransactionId) = db.transactions().first { it.id == id }.alias
        for ((m, alias) in pairs) {
            assertEquals(alias, aliasOf(txs.getValue(m)), "alias of ${show(m)}")
            assertEquals(alias, db.transaction(txs.getValue(m)).row.alias, "alias of ${show(m)} via transaction(id)")
            assertEquals(stored(m), db.transaction(txs.getValue(m)).row.merchant, "merchant text of ${show(m)}")
        }
        assertEquals("", aliasOf(empty), "blank merchant takes no alias")

        // Case is ignored in the key; LIKE wildcards are not wildcards.
        db.setAlias("pag*jose_100%", "Zé novo")
        assertEquals("Zé novo", aliasOf(txs.getValue("PAG*JOSE_100%")), "alias by key ignoring case")
        assertEquals("100%", aliasOf(txs.getValue("PAG*JOSEX1000")), "a wildcard-like key renamed another merchant")
        // A blank alias removes it.
        db.setAlias("PAG*JOSEX1000", "  ")
        assertEquals("", aliasOf(txs.getValue("PAG*JOSEX1000")), "blank alias removes it")
        // A form alias names the merchant.
        val named = db.addManual(form("Loja \"X\" ; --", "Apelido 'Y' %")).transaction
        assertNotEquals(0L, named.v)
        assertEquals("Apelido 'Y' %", aliasOf(named), "form alias read back")
    }

    @Test
    fun sourcesKeepHostileNamesExactly() = dbTest { db ->
        val seed = 0x5005L
        val rnd = Random(seed)
        val fixed = listOf(
            "com.a.b%" to "100% Banco", "com.a._" to "_", "com.x'y\"z" to "O'Hara \"Bank\"",
            "com.drop'; DROP TABLE sources;--" to "'; DROP TABLE sources;--", "com.uni.‮evil" to "Ünïcödé 中文 😀",
            "com.nul\u0000x" to "a\u0000b", "com.long" to "L".repeat(10_000), "com.nl" to "linha1\nlinha2",
        )
        val all = (fixed + List(20) { ("r.$it." + hostile(rnd, 4)).trim() to hostile(rnd, 6).trim() })
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .distinctBy { it.first }
        for ((i, p) in all.withIndex()) {
            val ok = try {
                db.addSource(p.first, p.second, NOW + i)
            } catch (e: Throwable) {
                fail("seed=$seed i=$i addSource(${show(p.first)}, ${show(p.second)}) threw $e", e)
            }
            assertTrue(ok, "seed=$seed i=$i addSource(${show(p.first)}, ${show(p.second)})")
        }
        val expected = all.withIndex().map { (i, p) -> Source(stored(p.first), stored(p.second), NOW + i) }.toSet()
        assertEquals(expected, db.sources().toSet(), "seed=$seed sources()")

        // Refusals and wildcard-looking removals change nothing.
        for ((pkg, label) in listOf("" to "x", "  " to "x", " " to "x", "com.ok" to "", "com.ok" to "\t", "com.ok" to " ")) {
            assertEquals(false, db.addSource(pkg, label, NOW), "addSource(${show(pkg)}, ${show(label)})")
        }
        for (pkg in listOf("%", "_%", "com.a.%", "' OR '1'='1", "", "COM.A.B%", "com.a.b")) db.removeSource(pkg)
        assertEquals(expected, db.sources().toSet(), "seed=$seed sources() after refusals and wildcard removals")

        // Relabel keeps one row; exact removal removes only that one.
        assertTrue(db.addSource("com.a.b%", "Novo 'rótulo'", NOW + 999), "relabel")
        val relabeled = db.sources().filter { it.packageName == "com.a.b%" }
        assertEquals(listOf("Novo 'rótulo'"), relabeled.map { it.label }, "relabel read back")
        assertEquals(all.size, db.sources().size, "relabel adds no row")
        db.removeSource("com.a._")
        assertEquals(all.map { stored(it.first) }.toSet() - "com.a._", db.sources().map { it.packageName }.toSet(), "removeSource exact")

        // A hostile package that is a source captures; a wildcard-alike that isn't does not.
        val raw = RawNotice(packageName = "com.x'y\"z", appLabel = "O'Hara", key = "k1", postedAt = NOW, title = "Compra", text = "R$ 1,00 em 'LOJA'")
        assertTrue(capture(db, raw), "capture from ${show(raw.packageName)}")
        assertEquals(false, capture(db, raw.copy(packageName = "com.a.bX", key = "k2")), "capture from com.a.bX (source com.a.b%)")
        assertEquals(false, capture(db, raw.copy(packageName = "com.a._", key = "k3")), "capture from a removed source")
        assertEquals(1, db.pendingCount(), "pendingCount()")
    }
}
