package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.minusMonth
import kotlinx.datetime.onDay
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toInstant
import kotlinx.datetime.yearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Volume (D49): about five years of use, built only through the public API with a fixed seed, then the reads
 * the screens make timed against generous ceilings (~10x what a laptop measures). A failure here means a
 * read stopped scaling (a missing index, an N+1), not that the machine is slow: look at the query first.
 */
class VolumeTest {

    private val path = Path(SystemTemporaryDirectory, "margem-volume-${Random.nextLong().toULong()}.db").toString()
    private val tz = TimeZone.of("America/Sao_Paulo")
    private val db = Db(BundledSQLiteDriver(), path, timeZone = tz)

    @AfterTest
    fun tearDown() {
        db.close()
        for (p in listOf(path, "$path-wal", "$path-shm")) SystemFileSystem.delete(Path(p), mustExist = false)
    }

    private fun cents(c: Long) = "${c / 100},${(c % 100).toString().padStart(2, '0')}"

    @Test
    fun `five years of use still read fast`() = runTest(timeout = 5.minutes) {
        val rnd = Random(49)
        val start = LocalDate(2021, 10, 1)
        val today = LocalDate(2026, 10, 16)
        val current = YearMonth(2026, 10)
        val clock = TimeSource.Monotonic

        // ---- Generation ----
        val t0 = clock.markNow()
        db.addSource(NU, "Nubank", 0)
        val limits = listOf("Mercado" to 120000L, "Restaurantes" to 60000L, "Transporte" to 45000L, "Lazer" to 40000L, "Saúde" to 30000L, "Casa" to 50000L, "Assinaturas" to 15000L, "Roupas" to 25000L)
        val budgets = limits.map { (name, limit) -> db.createBudget(name, Cents(limit), start) }
        assertTrue(budgets.none { it.v == 0L })
        db.ensureMonths(current)
        val tagNames = listOf("feira", "carne", "padaria", "almoço", "jantar", "delivery", "uber", "ônibus", "combustível", "cinema", "viagem", "farmácia", "consulta", "luz", "internet", "streaming", "presente", "esporte", "pet", "filhos")
        val merchants = (1..150).map { "LOJA ${it.toString().padStart(3, '0')} ${listOf("COMERCIO", "ALIMENTOS", "SERVICOS", "LTDA", "SA")[it % 5]}" }
        for (m in merchants.take(40)) db.setAlias(m, "Apelido ${m.substring(5, 8)}")

        var notices = 0
        var accepted = 0
        var dismissed = 0
        var refunds = 0
        var installmentRows = 0L
        var steps = 0
        val recent = ArrayDeque<Triple<TransactionId, String, Long>>()
        var date = start
        var n = 0
        while (date <= today) {
            if (date == LocalDate(2023, 3, 10)) db.setLimit(budgets[0], Cents(150000), date)
            if (date == LocalDate(2025, 1, 10)) db.setLimit(budgets[0], Cents(180000), date)
            val nearEnd = date >= today.plus(-20, DateTimeUnit.DAY)
            repeat(2 + rnd.nextInt(3)) { slot ->
                n++
                val at = date.atTime(7 + slot * 4 + rnd.nextInt(3), rnd.nextInt(60)).toInstant(tz).toEpochMilliseconds()
                val merchant = merchants[rnd.nextInt(merchants.size)]
                val value = 500L + rnd.nextLong(30000)
                val text = "Compra de R$ ${cents(value)} APROVADA em $merchant para o cartão com final 1234."
                assertTrue(capture(db, RawNotice(NU, "Nubank", "k$n", at, at, "Nubank", text, text)))
                notices++
                val id = db.pending().first().id
                val roll = rnd.nextInt(100)
                when {
                    nearEnd && roll < 18 -> Unit // still waiting in the Pauta
                    roll < 12 -> { db.dismiss(id); dismissed++ }
                    else -> {
                        val p = db.notice(id).parsed
                        val parts = if (rnd.nextInt(100) < 10) 2 + rnd.nextInt(11) else 1
                        val total = value * parts
                        val b = budgets[rnd.nextInt(budgets.size)]
                        val tags = List(1 + rnd.nextInt(3)) { tagNames[rnd.nextInt(tagNames.size)] }
                        val tx = db.accept(id, TransactionForm(b, tags, Cents(total), parts, p.merchant, p.date, p.time))
                        assertTrue(tx.v != 0L)
                        accepted++
                        installmentRows += parts
                        recent.addLast(Triple(tx, merchant, total))
                        if (recent.size > 8) recent.removeFirst()
                    }
                }
                if (recent.isNotEmpty() && rnd.nextInt(100) < 2) {
                    val (tx, m, v) = recent.removeLast()
                    val rt = at + 600_000
                    val rtext = "A compra em $m no valor de R$ ${cents(v)} foi estornada."
                    if (capture(db, RawNotice(NU, "Nubank", "r$n", rt, rt, "Nubank", rtext, rtext))) {
                        notices++
                        if (db.registerRefund(db.pending().first().id, tx)) refunds++
                    }
                }
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        // Week step edits in past months: every third month of every budget, one week raised.
        var month = YearMonth(2021, 10)
        while (month < current) {
            if (month.month.ordinal % 3 == 0) {
                for (b in budgets) {
                    val w = db.budgetDetail(b, month, today).month.weeks.getOrNull(1) ?: continue
                    if (db.setStep(b, month, w.start, Cents(minOf(w.step.v + 5000, w.max.v)))) steps++
                }
            }
            month = month.plus(1, DateTimeUnit.MONTH)
        }
        // Previstos (D59–D64), outside the seeded sequence: 12 linked "Todo mês" from recados of September, each with its
        // October recado waiting in the Pauta; 6 "Salvar previsto" ahead; one "Todo mês" left unlaunched since 2021
        // (every month open and late: the worst A caminho).
        for (i in 0 until 12) {
            val merchant = merchants[i * 7]
            val on = LocalDate(2026, 9, 1 + i * 2)
            val at = on.atTime(9, 0).toInstant(tz).toEpochMilliseconds()
            val text = "Compra de R$ 99,90 APROVADA em $merchant para o cartão com final 1234."
            assertTrue(capture(db, RawNotice(NU, "Nubank", "p$i", at, at, "Nubank", text, text)))
            val id = db.pending().first { it.raw.key == "p$i" }.id
            val p = db.notice(id).parsed
            val tx = db.accept(id, TransactionForm(budgets[i % budgets.size], listOf("assinatura"), p.cents, 1, p.merchant, p.date, p.time, caption = "Previsto $i", repeats = Repeats.MONTHLY))
            assertTrue(tx.v != 0L)
            val oct = LocalDate(2026, 10, 1 + i).atTime(9, 0).toInstant(tz).toEpochMilliseconds()
            assertTrue(capture(db, RawNotice(NU, "Nubank", "q$i", oct, oct, "Nubank", text, text)))
        }
        for (i in 0 until 6) {
            val ahead = TransactionForm(budgets[i], listOf("conta"), Cents(15000), 1, "Conta $i", LocalDate(2026, 10, 18 + i), LocalTime(9, 0), repeats = Repeats.MONTHLY)
            assertTrue(db.addManual(ahead, today).planned.v != 0L)
        }
        val old = TransactionForm(budgets[0], listOf("conta"), Cents(5000), 1, "Desde 2021", LocalDate(2021, 10, 20), LocalTime(9, 0), repeats = Repeats.MONTHLY)
        assertTrue(db.addManual(old, today).planned.v != 0L)
        db.setWeekStart(DayOfWeek.SUNDAY, today)
        db.setMonthStartDay(5, today)
        val generation = t0.elapsedNow()
        println("VOLUME data: $notices notices ($accepted accepted, $dismissed dismissed, ${db.pendingCount()} pending), $refunds refunds, ${budgets.size} budgets, ${db.tags().size} tags, $steps step edits, $installmentRows installment rows; generated in ${generation.inWholeMilliseconds} ms")

        // ---- Reads ----
        val from = YearMonth(2021, 10)
        val fromDay = LocalDate(2021, 10, 1)
        val monthStart = LocalDate(2026, 10, 5)
        val yearStart = LocalDate(2025, 10, 16)
        val reads = listOf<Triple<String, Long, suspend () -> Unit>>(
            Triple("budgetsOverview", CEIL_OVERVIEW) { db.budgetsOverview(current, today, BudgetOrder.RISK) },
            Triple("budgetDetail (every budget)", CEIL_DETAIL) { for (b in budgets) db.budgetDetail(b, current, today) },
            Triple("history (all months)", CEIL_HISTORY) { db.history(from, current) },
            Triple("pending", CEIL_PENDING) { db.pending() },
            // D60: the recognition loop over the Pauta, "medido pelo teste de volume".
            Triple("pending(today)", CEIL_PENDING_TODAY) { db.pending(today) },
            Triple("planned(today)", CEIL_PLANNED) { db.planned(today) },
            Triple("dismissed", CEIL_DISMISSED) { db.dismissed() },
            Triple("transactions", CEIL_TRANSACTIONS) { db.transactions(today) },
            Triple("tagRanking (month)", CEIL_RANK_MONTH) { db.tagRanking(monthStart, today) },
            Triple("tagRanking (all)", CEIL_RANK_ALL) { db.tagRanking(fromDay, today) },
            Triple("merchantRanking (month)", CEIL_RANK_MONTH) { db.merchantRanking(monthStart, today) },
            Triple("merchantRanking (all)", CEIL_RANK_ALL) { db.merchantRanking(fromDay, today) },
            Triple("spentByDay (year)", CEIL_BYDAY) { db.spentByDay(yearStart, today) },
            Triple("spentByDay (all)", CEIL_BYDAY) { db.spentByDay(fromDay, today) },
        )
        val failures = mutableListOf<String>()
        for ((name, ceiling, read) in reads) {
            repeat(2) { read() }
            val runs = List(5) { val m = clock.markNow(); read(); m.elapsedNow().inWholeMicroseconds / 1000.0 }.sorted()
            println("VOLUME read ${name.padEnd(30)} min ${runs.first()}  median ${runs[2]}  max ${runs.last()} ms  (ceiling $ceiling ms)")
            if (runs.last() > ceiling) failures += "$name: ${runs.last()} ms > $ceiling ms"
        }
        println("VOLUME total ${t0.elapsedNow().inWholeMilliseconds} ms")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    /**
     * D60: planned(today) reads the Pauta only to mark the occurrences whose recado is waiting, which only a linked
     * previsto can have. With none linked, a big Pauta must not cost it anything: it stays well under pending(), which
     * reads and parses every waiting recado.
     */
    @Test
    fun `planned without a linked previsto does not read the pauta`() = runTest(timeout = 5.minutes) {
        val today = LocalDate(2026, 10, 16)
        val clock = TimeSource.Monotonic
        db.addSource(NU, "Nubank", 0)
        val casa = db.createBudget("Casa", Cents(500000), today)
        for (i in 0 until 3000) {
            val at = LocalDate(2026, 10, 1 + i % 15).atTime(8, 0).toInstant(tz).toEpochMilliseconds() + i
            val text = "Compra de R$ ${cents(500L + i)} APROVADA em LOJA $i para o cartão com final 1234."
            assertTrue(capture(db, RawNotice(NU, "Nubank", "v$i", at, at, "Nubank", text, text)))
        }
        val ahead = TransactionForm(casa, listOf("luz"), Cents(18000), 1, "Conta de luz", LocalDate(2026, 10, 20), LocalTime(9, 0), repeats = Repeats.MONTHLY)
        assertTrue(db.addManual(ahead, today).planned.v != 0L)

        suspend fun median(read: suspend () -> Unit): Double {
            repeat(2) { read() }
            return List(5) { val m = clock.markNow(); read(); m.elapsedNow().inWholeMicroseconds / 1000.0 }.sorted()[2]
        }
        val planned = median { db.planned(today) }
        val pending = median { db.pending() }
        println("VOLUME planned(today) without a link: median $planned ms; pending() of 3000: median $pending ms")
        assertTrue(planned < pending / 4, "planned(today) $planned ms vs pending() $pending ms: it reads the Pauta with no linked previsto")
    }

    /** Median of 5 runs of [read], after 2 warm-up runs, in ms. */
    private suspend fun median(read: suspend () -> Unit): Double {
        val clock = TimeSource.Monotonic
        repeat(2) { read() }
        return List(5) { val m = clock.markNow(); read(); m.elapsedNow().inWholeMicroseconds / 1000.0 }.sorted()[2]
    }

    @Test
    fun `planned with a linked previsto does not read the pauta`() = runTest(timeout = 5.minutes) {
        // Architecture testing "Volume" (2026-10-24): planned(today) doesn't read the Pauta, with or without a linked previsto.
        val today = LocalDate(2026, 10, 16)
        db.addSource(NU, "Nubank", 0)
        val casa = db.createBudget("Casa", Cents(500000), LocalDate(2026, 9, 1))
        for (i in 0 until 3000) {
            val at = LocalDate(2026, 10, 1 + i % 15).atTime(8, 0).toInstant(tz).toEpochMilliseconds() + i
            val text = "Compra de R$ ${cents(500L + i)} APROVADA em LOJA $i para o cartão com final 1234."
            assertTrue(capture(db, RawNotice(NU, "Nubank", "v$i", at, at, "Nubank", text, text)))
        }
        // One previsto linked to "ENEL SP": its September recado launched with Todo mês, first occurrence 20 out.
        val sep = LocalDate(2026, 9, 20).atTime(9, 0).toInstant(tz).toEpochMilliseconds()
        val luz = "Compra de R$ 180,00 APROVADA em ENEL SP para o cartão com final 1234."
        assertTrue(capture(db, RawNotice(NU, "Nubank", "luz", sep, sep, "Nubank", luz, luz)))
        val id = db.pending().first { it.raw.key == "luz" }.id
        val p = db.notice(id).parsed
        assertTrue(db.accept(id, TransactionForm(casa, listOf("luz"), p.cents, 1, p.merchant, p.date, p.time, caption = "Conta de luz", repeats = Repeats.MONTHLY)).v != 0L)
        assertTrue(db.planned(today).single().linked)

        val planned = median { db.planned(today) }
        val pending = median { db.pending() }
        println("VOLUME planned(today) with a link: median $planned ms; pending() of 3000: median $pending ms")
        assertTrue(planned < pending / 4, "planned(today) $planned ms vs pending() $pending ms: it reads the Pauta with a linked previsto")
    }

    @Test
    fun `two years of launched previstos keep pending and planned under twice the time`() = runTest(timeout = 10.minutes) {
        // Architecture testing "Volume" (2026-10-24): 200 previstos and 24 months launched, pending() and planned(today) at most 2× the same Db without that history (median of 5).
        val today = LocalDate(2026, 10, 16)
        val freshPath = Path(SystemTemporaryDirectory, "margem-volume-fresh-${Random.nextLong().toULong()}.db").toString()
        val fresh = Db(BundledSQLiteDriver(), freshPath, timeZone = tz)
        var key = 0

        /** Captures a Nubank purchase of [merchant] on [on] and returns its id; [only] = it is the one pending recado. */
        suspend fun Db.purchase(on: LocalDate, merchant: String, value: Long, only: Boolean): NoticeId {
            val at = on.atTime(9, 0).toInstant(tz).toEpochMilliseconds() + key
            val k = "h${key++}"
            val text = "Compra de R$ ${cents(value)} APROVADA em $merchant para o cartão com final 1234."
            assertTrue(capture(this, RawNotice(NU, "Nubank", k, at, at, "Nubank", text, text)))
            return if (only) pending().single().id else pending().first { it.raw.key == k }.id
        }

        /** 200 Todo mês previstos, each linked to SERVICO i, day 1 + i % 28, from the recado of the month before [first]. */
        suspend fun Db.previstos(first: YearMonth): BudgetId {
            addSource(NU, "Nubank", 0)
            val casa = createBudget("Casa", Cents(5_000_000), first.minusMonth().onDay(1))
            for (i in 0 until 200) {
                val r = purchase(first.minusMonth().onDay(1 + i % 28), "SERVICO $i", 5000L + i, only = true)
                val p = notice(r).parsed
                assertTrue(accept(r, TransactionForm(casa, listOf("conta"), p.cents, 1, p.merchant, p.date, p.time, caption = "Conta $i", repeats = Repeats.MONTHLY)).v != 0L)
            }
            return casa
        }

        /** The same pending recados in both: 1000 of stores and one October recado of each of 50 previstos. */
        suspend fun Db.pauta() {
            for (i in 0 until 1000) purchase(LocalDate(2026, 10, 1 + i % 15), "LOJA $i", 500L + i, only = false)
            for (i in 0 until 50) purchase(LocalDate(2026, 10, 1 + i % 28).let { if (it > today) today else it }, "SERVICO $i", 5000L + i, only = false)
        }

        try {
            // Without the history: the previstos start in October 2026.
            fresh.previstos(YearMonth(2026, 10))
            fresh.pauta()

            // With it: they start in October 2024 and every occurrence up to September 2026 is launched from its recado.
            val casa = db.previstos(YearMonth(2024, 10))
            var month = YearMonth(2024, 10)
            var launched = 0
            while (month <= YearMonth(2026, 9)) {
                val open = db.planned(month.onDay(month.numberOfDays)).filter { it.date.yearMonth == month }
                assertTrue(open.size == 200, "$month: ${open.size} open")
                for (o in open) {
                    val i = o.name.removePrefix("Conta ").toInt()
                    val r = db.purchase(o.date, "SERVICO $i", 5000L + i, only = true)
                    val p = db.notice(r).parsed
                    val form = TransactionForm(casa, o.tags, p.cents, 1, p.merchant, p.date, p.time, caption = o.name, occurrence = o.occurrence)
                    assertTrue(db.accept(r, form).v != 0L)
                    launched++
                }
                month = month.plusMonth()
            }
            assertTrue(launched == 200 * 24, "launched $launched")
            db.pauta()
            assertTrue(db.planned(today).size == fresh.planned(today).size, "the same open occurrences today")
            assertTrue(db.pending().size == fresh.pending().size, "the same Pauta")

            val freshPending = median { fresh.pending() }
            val freshPlanned = median { fresh.planned(today) }
            val freshPendingToday = median { fresh.pending(today) }
            val pending = median { db.pending() }
            val planned = median { db.planned(today) }
            val pendingToday = median { db.pending(today) }
            println(
                "VOLUME 200 previstos, 24 months launched vs none: pending() $pending vs $freshPending ms; " +
                    "planned(today) $planned vs $freshPlanned ms; pending(today) $pendingToday vs $freshPendingToday ms (not asserted)",
            )
            assertTrue(pending <= 2 * freshPending, "pending() $pending ms with the history vs $freshPending ms without")
            assertTrue(planned <= 2 * freshPlanned, "planned(today) $planned ms with the history vs $freshPlanned ms without")
        } finally {
            fresh.close()
            for (p in listOf(freshPath, "$freshPath-wal", "$freshPath-shm")) SystemFileSystem.delete(Path(p), mustExist = false)
        }
    }

    private companion object {
        const val NU = "com.nu.production"

        // Ceilings in ms: ~10x measured on the author's laptop, rounded up, never below 10.
        const val CEIL_OVERVIEW = 30L
        const val CEIL_DETAIL = 350L
        const val CEIL_HISTORY = 1_200L
        const val CEIL_PENDING = 15L
        const val CEIL_PENDING_TODAY = 45L
        const val CEIL_PLANNED = 45L
        const val CEIL_DISMISSED = 50L
        const val CEIL_TRANSACTIONS = 200L
        const val CEIL_RANK_MONTH = 10L
        const val CEIL_RANK_ALL = 80L
        const val CEIL_BYDAY = 40L
    }
}
