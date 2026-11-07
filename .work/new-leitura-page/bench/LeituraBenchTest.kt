package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/** new-leitura-page stage 3 bench (scratch, not committed): VolumeTest's five years, then the reads one Leitura grid open makes. */
class LeituraBenchTest {
    private val path = Path(SystemTemporaryDirectory, "margem-lbench-${Random.nextLong().toULong()}.db").toString()
    private val tz = TimeZone.of("America/Sao_Paulo")
    private val db = Db(BundledSQLiteDriver(), path, timeZone = tz)

    @AfterTest
    fun tearDown() {
        db.close()
        for (p in listOf(path, "$path-wal", "$path-shm")) SystemFileSystem.delete(Path(p), mustExist = false)
    }

    private fun cents(c: Long) = "${c / 100},${(c % 100).toString().padStart(2, '0')}"

    // Copies of Insights.kt's private earlier() / plan() / firstTimers() / leftOut() reads (same Db calls).
    private suspend fun earlier(d: Db, q: LeituraQuery, today: LocalDate, n: Int): LeituraQuery {
        val first = d.monthOf(q.from); val last = d.monthOf(q.to)
        if (d.window(first).start != q.from || d.window(last).endInclusive != q.to) {
            val days = q.from.daysUntil(q.to) + 1
            return q.copy(from = q.from.minus(days * n, DateTimeUnit.DAY), to = q.to.minus(days * n, DateTimeUnit.DAY))
        }
        val months = (last.year * 12 + last.month.ordinal) - (first.year * 12 + first.month.ordinal) + 1
        val from = d.window(first.minus(months * n, DateTimeUnit.MONTH)).start
        val end = d.window(last.minus(months * n, DateTimeUnit.MONTH)).endInclusive
        val to = if (today in q.from..q.to) minOf(from.plus(q.from.daysUntil(today), DateTimeUnit.DAY), end) else end
        return q.copy(from = from, to = to)
    }
    private suspend fun plan(d: Db, q: LeituraQuery, today: LocalDate) {
        val m = d.monthOf(q.from); val w = d.window(m)
        if (w.start != q.from || w.endInclusive != q.to || q.chips.any { it.kind != ChipKind.BUDGET }) return
        d.ensureMonths(m); d.budgetsOverview(m, today, BudgetOrder.MANUAL)
    }
    private fun seen(lines: List<LeituraRow>) =
        (lines.map { Chip(ChipKind.MERCHANT, it.merchantKey, it.merchant) } + lines.flatMap { r -> r.tags.map { Chip(ChipKind.TAG, it, it) } }).distinctBy { it.kind to it.key }
    private suspend fun firstTimers(d: Db, q: LeituraQuery, lines: List<LeituraRow>): Int {
        val s = seen(lines); if (s.isEmpty()) return 0
        return d.leituraLines(LeituraQuery(LocalDate(1, 1, 1), q.from.minus(1, DateTimeUnit.DAY), s)).size
    }
    private suspend fun leftOut(d: Db, q: LeituraQuery): Int =
        d.transactions().count { it.refunded && it.date in q.from..q.to } + d.dismissed().count { it.parsed.date in q.from..q.to } + d.pending().count { it.parsed.date in q.from..q.to }

    /** Every query(db) block the grid starts for [q] with default options (Insights.kt:237, :278, Widget bodies). After P1, fora, voltam and primeira-vez reuse :278 and start no block of their own. */
    private fun gridReads(q: LeituraQuery, lines: List<LeituraRow>, today: LocalDate): List<Pair<String, suspend (Db) -> Unit>> = listOf(
        "load" to { d -> val cur = d.monthOf(today); d.ensureMonths(cur); d.history(cur, cur); d.window(cur); d.leituraLines(q) },
        "extra(firstTimers+leftOut)" to { d -> firstTimers(d, q, lines); leftOut(d, q) },
        "margem" to { d -> val m = d.monthOf(q.to); d.ensureMonths(m); d.budgetsOverview(m, today, BudgetOrder.RISK) },
        "total(6 earlier)" to { d -> (1..6).map { d.leituraLines(earlier(d, q, today, it)) } },
        "ritmo(plan)" to { d -> plan(d, q, today) },
        "previstos" to { d -> val m = d.monthOf(q.to); d.ensureMonths(m); d.budgetsOverview(m, today, BudgetOrder.MANUAL).forEach { d.budgetDetail(it.id, m, today) } },
        "cresceu(3 earlier)" to { d -> (1..3).flatMap { d.leituraLines(earlier(d, q, today, it)) } },
        "dia-da-semana" to { d -> d.settings() },
        "semanas" to { d -> d.settings(); plan(d, q, today) },
        "mes-a-mes(6)" to { d -> val end = d.monthOf(q.to); val w = (5 downTo 0).map { d.window(end.minus(it, DateTimeUnit.MONTH)) }; d.leituraLines(q.copy(from = w.first().start, to = w.last().endInclusive)) },
        "meses-a-frente(3)" to { d -> val cur = d.monthOf(today); d.ensureMonths(cur); val w = (1..3).map { d.window(cur.plus(it, DateTimeUnit.MONTH)) }
            d.budgetsOverview(cur, today, BudgetOrder.MANUAL).forEach { d.budgetDetail(it.id, cur, today) }; d.leituraLines(LeituraQuery(w.first().start, w.last().endInclusive, q.chips)) },
        "volta-todo-mes(6)" to { d -> val cur = d.monthOf(today); val w = (6 downTo 1).map { d.window(cur.minus(it, DateTimeUnit.MONTH)) }; d.leituraLines(LeituraQuery(w.first().start, w.last().endInclusive, q.chips)) },
    )

    private suspend fun median(read: suspend () -> Unit): Double {
        val clock = TimeSource.Monotonic
        repeat(2) { read() }
        return List(5) { val m = clock.markNow(); read(); m.elapsedNow().inWholeMicroseconds / 1000.0 }.sorted()[2]
    }

    @Test
    fun `leitura grid reads at five years`() = runTest(timeout = 10.minutes) {
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
        println("LBENCH generated in ${generation.inWholeMilliseconds} ms")
        println("VOLUME data: $notices notices ($accepted accepted, $dismissed dismissed, ${db.pendingCount()} pending), $refunds refunds, ${budgets.size} budgets, ${db.tags().size} tags, $steps step edits, $installmentRows installment rows; generated in ${generation.inWholeMilliseconds} ms")

        val w = db.window(db.monthOf(today))
        val monthQ = LeituraQuery(w.start, w.endInclusive)
        val w6 = db.window(db.monthOf(today).minus(5, DateTimeUnit.MONTH))
        val six = LeituraQuery(w6.start, w.endInclusive)
        val all = LeituraQuery(LocalDate(2021, 10, 1), today)
        for ((name, q) in listOf("month" to monthQ, "6 months" to six, "all (5y)" to all)) {
            val rows = db.leituraLines(q).size
            println("LBENCH leituraLines $name: $rows rows, median ${median { db.leituraLines(q) }} ms")
        }
        val before = db.leituraLines(LeituraQuery(LocalDate(1, 1, 1), monthQ.from.minus(1, DateTimeUnit.DAY), seen(db.leituraLines(monthQ))))
        println("LBENCH firstTimers history read (month, ${seen(db.leituraLines(monthQ)).size} chips OR'd): ${before.size} rows, median ${median { firstTimers(db, monthQ, db.leituraLines(monthQ)) }} ms")
        println("LBENCH leftOut (transactions+dismissed+pending, full tables): median ${median { leftOut(db, monthQ) }} ms")
        // Base (a5980d6) Leitura: its one query block per open (old Insights.kt:272-291), default month, first budget picked.
        val base = median {
            val cur = db.monthOf(today); db.ensureMonths(cur); db.history(YearMonth(1, 1), cur); db.ensureMonths(cur)
            val bs = db.budgetsOverview(cur, today, BudgetOrder.RISK); db.budgetDetail(bs.first().id, cur, today); db.settings()
            db.tagRanking(w.start, w.endInclusive); db.merchantRanking(w.start, w.endInclusive); db.spentByDay(w.start, w.endInclusive); db.spentByDay(w.start, w.endInclusive, fixedOnly = true)
        }
        println("LBENCH base Leitura open (one block): median $base ms")
        val detail = median { val cur = db.monthOf(today); db.budgetDetail(db.budgetsOverview(cur, today, BudgetOrder.MANUAL).first().id, cur, today) }
        println("LBENCH one budgetDetail (+overview): median $detail ms")
        for ((name, q) in listOf("month" to monthQ, "6 months" to six)) {
            val lines = db.leituraLines(q)
            val reads = gridReads(q, lines, today)
            var sum = 0.0
            for ((r, block) in reads) { val ms = median { block(db) }; sum += ms; println("LBENCH grid[$name] ${r.padEnd(28)} median $ms ms") }
            println("LBENCH grid[$name] sequential sum of ${reads.size} query blocks: ${(sum * 10).toLong() / 10.0} ms")
            val wall = median { withContext(Dispatchers.IO) { coroutineScope { reads.map { (_, b) -> async { b(db) } }.awaitAll() } } }
            println("LBENCH grid[$name] concurrent wall (all blocks at once, 2 readers): $wall ms")
        }
    }

    private companion object {
        const val NU = "com.nu.production"
    }
}
