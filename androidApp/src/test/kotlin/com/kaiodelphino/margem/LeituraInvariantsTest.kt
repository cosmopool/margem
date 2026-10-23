package com.kaiodelphino.margem

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.NNBSP
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.moneyParts
import com.kaiodelphino.margem.widgets.moneyPlain
import com.kaiodelphino.margem.widgets.monthLong
import com.kaiodelphino.margem.widgets.monthShort
import com.kaiodelphino.margem.widgets.monthTitle
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.minusMonth
import kotlinx.datetime.onDay
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Leitura (bloco 6), invariantes entre a tela e as leituras públicas do Db (spec §7.5, D44, D45; InsightsTags é o
 * contrato). Sequências aleatórias com semente fixa de ações no Db (verbas, cotas, passos, lançamentos parcelados,
 * estornos, apagar, marcadores, apelidos) com a tela aberta; depois de cada lote, para o mês mostrado:
 *
 * - ranking = `tagRanking` / `merchantRanking` da janela do mês (ordem, nome, contagem, valor), "Ver todos (n)" sse n > 6,
 *   RANK_NOTE só em Marcadores, frase "<1º> puxa a fila.";
 * - calendário: um CAL_DAY por dia da janela, descrição = `spentByDay`, degrau pela regra do §7.5 (à mão), legenda = o degrau,
 *   frase e fatia do dia da semana mais pesado;
 * - Ritmo do mês e Verbas por mês da verba escolhida: frase e descrição pelos modelos do KDoc com o BudgetMonth e o `history`;
 * - verba padrão = a primeira de `budgetsOverview(mês, hoje, RISK)`; a escolha lista as verbas nessa ordem;
 * - compras estornadas não contam: o gasto por dia da tela bate com a soma, à mão, das parcelas dos lançamentos não estornados.
 *
 * Hoje fixo: sex, 9 out 2026. Datas de compra com dia ≤ 9 quando alguma parcela pode cair no mês atual, para que o gasto
 * do mês atual seja todo "até hoje" (a descrição do gráfico diz "até o dia 9").
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h3000dp")
class LeituraInvariantsTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: Db
    private lateinit var rnd: Random
    private var seed = 0
    private var startDay = 1
    private var weekStart = DayOfWeek.MONDAY
    private var batch = 0
    private val log = mutableListOf<String>()
    private val estornos = mutableListOf<NoticeId>()
    private var noticeClock = 1_790_000_000_000L
    private var chosen: BudgetId? = null

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
        runBlocking { assertTrue(db.addSource(BANK, "Cartão Ipê", 1L)) }
    }

    /** Set by the teardown: the screen leaves before the Db closes, so no query in flight meets a closed Db. */
    private var gone by mutableStateOf(false)

    @After
    fun tearDown() {
        gone = true
        compose.waitForIdle()
        db.close()
    }

    @Test
    fun `with no budget only the empty state shows`() {
        open()
        eventually("sem verba") {
            // InsightsTags.EMPTY: the title "Nada para ler ainda." comes first, then the sentence about the Leitura.
            check(text(InsightsTags.EMPTY).startsWith("Nada para ler ainda.")) { "EMPTY: ${text(InsightsTags.EMPTY)}" }
            for (t in listOf(InsightsTags.PACE_BUDGET, InsightsTags.HISTORY_CHART, InsightsTags.RANK_TAGS, InsightsTags.CAL_EMPTY)) {
                check(!exists(t)) { "$t presente sem verba" }
            }
            check(countPrefix(InsightsTags.CAL_DAY) == 0) { "CAL_DAY sem verba" }
        }
    }

    @Test
    fun `seed 11 start day 1 week from monday`() = run(11, 1, DayOfWeek.MONDAY)

    @Test
    fun `seed 23 start day 5 week from sunday`() = run(23, 5, DayOfWeek.SUNDAY)

    @Test
    fun `seed 37 start day 1 week from saturday`() = run(37, 1, DayOfWeek.SATURDAY)

    // ---- The run ----

    private fun run(seed: Int, startDay: Int, weekStart: DayOfWeek) {
        this.seed = seed
        this.startDay = startDay
        this.weekStart = weekStart
        rnd = Random(seed)
        runBlocking {
            assertTrue(db.setMonthStartDay(startDay, OCT_9))
            db.setWeekStart(weekStart, OCT_9)
            // Lote 0, antes de abrir: três verbas em meses diferentes e lançamentos espalhados.
            repeat(3) { createBudget() }
            repeat(14) { addTransaction() }
        }
        open()
        checkBatch(first = true)
        for (b in 1..BATCHES) {
            batch = b
            log.clear()
            runBlocking { repeat(ACTIONS) { randomAction() } }
            checkBatch(first = false)
        }
        checkExpandedRanking()
    }

    private fun open() {
        compose.setContent {
        if (gone) return@setContent
            MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { OCT_9 }) { App(db, null) {} } }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(leituraTab).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(leituraTab).performClick()
    }

    private val leituraTab = hasText("Leitura") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun checkBatch(first: Boolean) {
        val risk = overview(OCT)
        if (risk.isEmpty()) {
            eventually("sem verba") { check(exists(InsightsTags.EMPTY)) { "EMPTY ausente sem verba" } }
            chosen = null
            return
        }
        // Wait until the screen shows this batch's data (ranking and calendar don't depend on the budget).
        val loading = expect(OCT, risk.first().id)
        eventually("mês $OCT, dados do lote") { checkRanking(loading, tags = true); checkCalendar(loading) }
        if (first) {
            chosen = null
        } else {
            click(InsightsTags.PACE_BUDGET)
            eventually("escolha de verba em $OCT") {
                check(exists(InsightsTags.BUDGET_CHOICE)) { "BUDGET_CHOICE não abriu" }
                val shown = nodesWithPrefix(InsightsTags.BUDGET_OPTION).sortedBy { it.boundsInRoot.top }
                    .map { BudgetId(it.config[SemanticsProperties.TestTag].removePrefix(InsightsTags.BUDGET_OPTION).toLong()) }
                check(shown == risk.map { it.id }) { "opções $shown, esperado na ordem de risco ${risk.map { it.id to it.name }}" }
                for (b in risk) check(texts(InsightsTags.BUDGET_OPTION + b.id.v).any { norm(it) == norm(b.name) }) { "opção ${b.id.v} sem o nome ${b.name}" }
            }
            val pick = risk[rnd.nextInt(risk.size)].id
            click(InsightsTags.BUDGET_OPTION + pick.v)
            eventually("escolha fecha") { check(!exists(InsightsTags.BUDGET_CHOICE)) { "BUDGET_CHOICE ainda aberto" } }
            chosen = pick
        }
        val e = expect(OCT, chosen ?: risk.first().id)
        checkMonth(e)

        // A past month, only after an explicit choice (the default without a choice isn't carried between months).
        val earliest = earliestMonth()
        val back = months(earliest, OCT).size - 1
        if (!first && back > 0 && rnd.nextBoolean()) {
            val k = 1 + rnd.nextInt(minOf(back, 3))
            var m = OCT
            repeat(k) {
                m = m.minusMonth()
                click(InsightsTags.MONTH_PREV)
                val target = m
                eventually("navegar para $target") { check(text(InsightsTags.MONTH_LABEL).contains(monthTitle(target))) { "MONTH_LABEL: ${text(InsightsTags.MONTH_LABEL)}" } }
            }
            val pastRisk = overview(m)
            if (pastRisk.isEmpty()) fail(ctx(m) + "mês $m sem verba com cota, entre o primeiro mês ($earliest) e o atual")
            val c = chosen
            val shownBudget = if (c != null && pastRisk.any { it.id == c }) c else pastRisk.first().id
            checkMonth(expect(m, shownBudget))
            repeat(k) {
                m = m.plusMonth()
                click(InsightsTags.MONTH_NEXT)
                val target = m
                eventually("voltar para $target") { check(text(InsightsTags.MONTH_LABEL).contains(monthTitle(target))) { "MONTH_LABEL: ${text(InsightsTags.MONTH_LABEL)}" } }
            }
        }
    }

    private fun checkMonth(e: Expect) {
        eventually("mês ${e.month}, verba ${e.budget.name}") {
            checkHeader(e)
            checkPace(e)
            checkHistory(e)
            checkRanking(e, tags = true)
            checkCalendar(e)
        }
        click(InsightsTags.RANK_MERCHANTS)
        eventually("mês ${e.month}, Estabelecimentos") { checkRanking(e, tags = false) }
        click(InsightsTags.RANK_TAGS)
        eventually("mês ${e.month}, Marcadores de novo") { checkRanking(e, tags = true) }
    }

    /** Last step: with more than 6 tags in the month, "Ver todos (n)" opens all the rows in place. */
    private fun checkExpandedRanking() {
        batch = BATCHES + 1
        log.clear()
        runBlocking {
            var extra = 0
            while (db.tagRanking(window(OCT).first, window(OCT).second).size <= 6 && extra < 8) {
                addTransaction(forceOctTag = "extra$extra")
                extra++
            }
        }
        val risk = overview(OCT)
        val e = expect(OCT, risk.first().id)
        eventually("dados do último lote") { checkRanking(e, tags = true); checkCalendar(e) }
        if (e.tags.size <= 6) fail(ctx(OCT) + "não chegou a 7 marcadores: ${e.tags}")
        click(InsightsTags.RANK_MORE)
        eventually("Ver todos aberto") {
            check(!exists(InsightsTags.RANK_MORE)) { "RANK_MORE ainda presente depois do clique" }
            for ((i, r) in e.tags.withIndex()) checkRow(i, r)
            check(!exists(InsightsTags.RANK_ROW + e.tags.size)) { "linha ${e.tags.size} além do ranking" }
        }
    }

    // ---- Expectations, from public Db reads and the spec's formulas ----

    private class Expect(
        val month: YearMonth,
        val window: Pair<LocalDate, LocalDate>,
        val current: Boolean,
        val earliest: YearMonth,
        val budget: BudgetDetail,
        val history: List<Pair<YearMonth, HistoryRow?>>,
        val tags: List<RankRow>,
        val merchants: List<RankRow>,
        val byDay: Map<LocalDate, Cents>,
    )

    private fun overview(m: YearMonth): List<BudgetSummary> = runBlocking {
        db.ensureMonths(OCT)
        db.budgetsOverview(m, OCT_9, BudgetOrder.RISK)
    }

    private fun earliestMonth(): YearMonth = runBlocking { db.history(YearMonth(2000, 1), OCT).minOf { it.month } }

    private fun expect(m: YearMonth, budget: BudgetId): Expect = runBlocking {
        db.ensureMonths(OCT)
        val w = window(m)
        val detail = db.budgetDetail(budget, m, OCT_9)
        val from = m.minusMonths(5)
        val hist = db.history(from, m).filter { it.budget == budget }
        val tags = db.tagRanking(w.first, w.second)
        val merchants = db.merchantRanking(w.first, w.second)
        val byDay = db.spentByDay(w.first, w.second)
        val ctx = ctx(m)

        // Refunded purchases never count: by hand from transactions() and each one's installments.
        val mine = mutableMapOf<LocalDate, Long>()
        for (t in db.transactions()) {
            if (t.refunded) continue
            for (i in db.transaction(t.id).installments) if (i.date >= w.first && i.date <= w.second) mine[i.date] = (mine[i.date] ?: 0L) + i.cents.v
        }
        assertEquals("$ctx spentByDay × parcelas não estornadas", mine.filterValues { it > 0 }.mapValues { Cents(it.value) }, byDay)
        val total = byDay.values.sumOf { it.v }
        assertEquals("$ctx Σ merchantRanking = Σ spentByDay", total, merchants.sumOf { it.total.v })
        assertTrue("$ctx Σ tagRanking ≥ Σ spentByDay", tags.sumOf { it.total.v } >= total)
        assertEquals("$ctx Σ history do mês = Σ spentByDay", total, db.history(m, m).sumOf { it.spent.v })

        // D44 on the chosen budget's month.
        val bm = detail.month
        val days = bm.start.daysUntil(bm.end) + 1
        assertEquals("$ctx janela do BudgetMonth", w, bm.start to bm.end)
        assertEquals("$ctx idealByDay.size", days, bm.idealByDay.size)
        assertTrue("$ctx idealByDay não decresce: ${bm.idealByDay}", bm.idealByDay.zipWithNext().all { (a, b) -> a <= b })
        assertEquals("$ctx idealByDay último = cota", bm.limit, bm.idealByDay.last())
        if (m == OCT) assertEquals("$ctx idealByDay[hoje] = ideal", bm.ideal, bm.idealByDay[bm.start.daysUntil(OCT_9)])

        Expect(
            m, w, m == OCT, db.history(YearMonth(2000, 1), OCT).minOf { it.month }, detail,
            months(from, m).map { mo -> mo to hist.firstOrNull { it.month == mo } }, tags, merchants, byDay,
        )
    }

    // ---- Checks of the screen (throw IllegalStateException via check; eventually retries) ----

    private fun checkHeader(e: Expect) {
        check(text(InsightsTags.MONTH_LABEL).contains(monthTitle(e.month))) { "MONTH_LABEL '${text(InsightsTags.MONTH_LABEL)}' sem ${monthTitle(e.month)}" }
        check(enabled(InsightsTags.MONTH_PREV) == (e.month > e.earliest)) { "MONTH_PREV habilitado=${enabled(InsightsTags.MONTH_PREV)}, mês ${e.month}, primeiro ${e.earliest}" }
        check(enabled(InsightsTags.MONTH_NEXT) == (e.month < OCT)) { "MONTH_NEXT habilitado=${enabled(InsightsTags.MONTH_NEXT)}, mês ${e.month}" }
        check(!exists(InsightsTags.EMPTY)) { "EMPTY com verbas" }
    }

    private fun checkPace(e: Expect) {
        val b = e.budget
        val bm = b.month
        check(texts(InsightsTags.PACE_BUDGET).any { norm(it) == norm(b.name) } || sameText(InsightsTags.PACE_BUDGET, b.name)) { "PACE_BUDGET '${text(InsightsTags.PACE_BUDGET)}', esperado ${b.name}" }
        val sentence: String
        val chart: List<String>
        if (e.current) {
            // The branch agrees with the status (spec §5) before it becomes text.
            val branch = when {
                bm.spent > bm.limit -> PaceStatus.OVERRUN
                bm.spent > bm.ideal -> PaceStatus.AHEAD
                else -> PaceStatus.ON_PACE
            }
            check(branch == bm.status) { "status ${bm.status} × gasto ${bm.spent} ideal ${bm.ideal} cota ${bm.limit}" }
            val head = when {
                bm.spent > bm.limit -> "${money(bm.spent - bm.limit)} além da cota."
                bm.spent > bm.ideal -> "+ ${money(bm.spent - bm.ideal)} sobre o ritmo ideal."
                bm.spent < bm.ideal -> "${money(bm.ideal - bm.spent)} abaixo do ritmo ideal."
                else -> "No ritmo ideal."
            }
            sentence = head + if (bm.ideal.v > 0 && bm.spent.v > 0) " No passo atual, o mês fecharia em ${whole(bm.projection)}." else ""
            val proj = if (bm.ideal.v > 0 && bm.spent.v > 0) "; no passo atual, ${whole(bm.projection)} no fim do mês" else ""
            // "até o dia 9": the date's day; with a start day other than 1, also accept the day of the window.
            val dayNumbers = listOf(OCT_9.day, bm.start.daysUntil(OCT_9) + 1).distinct()
            chart = dayNumbers.map {
                "Ritmo do mês de ${b.name}: ${money(bm.spent)} até o dia $it, contra ritmo ideal de ${money(bm.ideal)}$proj, para uma cota de ${money(bm.limit)}"
            }
        } else {
            sentence = when {
                bm.spent < bm.limit -> "Fechou em ${money(bm.spent)}, ${money(bm.limit - bm.spent)} dentro da cota."
                bm.spent > bm.limit -> "Fechou em ${money(bm.spent)}, ${money(bm.spent - bm.limit)} além da cota."
                else -> "Fechou em ${money(bm.spent)}, exatamente a cota."
            }
            chart = listOf("Ritmo do mês de ${b.name}: ${money(bm.spent)} no mês, para uma cota de ${money(bm.limit)}")
        }
        check(sameText(InsightsTags.PACE_SENTENCE, sentence)) { "PACE_SENTENCE '${text(InsightsTags.PACE_SENTENCE)}', esperado '$sentence' (${bm.status}, gasto ${bm.spent}, ideal ${bm.ideal}, cota ${bm.limit}, projeção ${bm.projection})" }
        val d = norm(desc(InsightsTags.PACE_CHART))
        check(chart.any { norm(it) == d }) { "PACE_CHART '$d', esperado '${chart.first()}'" }
    }

    private fun checkHistory(e: Expect) {
        val name = e.budget.name
        val parts = e.history.map { (mo, row) ->
            if (row == null) "${monthShort(mo.month)} sem cota" else "${monthShort(mo.month)} ${moneyPlain(row.spent)} de ${moneyPlain(row.limit)}"
        }
        val expected = "Verbas por mês de $name: " + parts.joinToString("; ")
        val d = desc(InsightsTags.HISTORY_CHART)
        check(norm(d) == norm(expected)) { "HISTORY_CHART '$d', esperado '$expected'" }
        val over = e.history.count { (_, row) -> row != null && row.spent > row.limit }
        val sentence = OUT_OF_MARGIN[over] + " fora da margem."
        check(sameText(InsightsTags.HISTORY_SENTENCE, sentence)) { "HISTORY_SENTENCE '${text(InsightsTags.HISTORY_SENTENCE)}', esperado '$sentence'" }
    }

    private fun checkRanking(e: Expect, tags: Boolean) {
        val rows = if (tags) e.tags else e.merchants
        val which = if (tags) "Marcadores" else "Estabelecimentos"
        check(selected(InsightsTags.RANK_TAGS) == tags) { "RANK_TAGS selected=${selected(InsightsTags.RANK_TAGS)} em $which" }
        check(selected(InsightsTags.RANK_MERCHANTS) == !tags) { "RANK_MERCHANTS selected=${selected(InsightsTags.RANK_MERCHANTS)} em $which" }
        if (rows.isEmpty()) {
            val empty = "Nada lançado em ${monthLong(e.month.month)}."
            check(sameText(InsightsTags.RANK_EMPTY, empty)) { "RANK_EMPTY '${text(InsightsTags.RANK_EMPTY)}' em $which, esperado '$empty'" }
            for (t in listOf(InsightsTags.RANK_ROW + 0, InsightsTags.RANK_SENTENCE, InsightsTags.RANK_NOTE, InsightsTags.RANK_MORE)) {
                check(!exists(t)) { "$t presente com ranking vazio em $which" }
            }
            return
        }
        check(!exists(InsightsTags.RANK_EMPTY)) { "RANK_EMPTY com ${rows.size} linhas em $which" }
        val first = "${rows[0].name} puxa a fila."
        check(sameText(InsightsTags.RANK_SENTENCE, first)) { "RANK_SENTENCE '${text(InsightsTags.RANK_SENTENCE)}', esperado '$first' em $which" }
        val shown = minOf(rows.size, 6)
        for (i in 0 until shown) checkRow(i, rows[i])
        check(!exists(InsightsTags.RANK_ROW + shown)) { "linha $shown visível antes de Ver todos em $which (ranking ${rows.size})" }
        if (rows.size > 6) {
            check(sameText(InsightsTags.RANK_MORE, "Ver todos (${rows.size})")) { "RANK_MORE '${text(InsightsTags.RANK_MORE)}', esperado 'Ver todos (${rows.size})' em $which" }
        } else {
            check(!exists(InsightsTags.RANK_MORE)) { "RANK_MORE com ${rows.size} linhas em $which" }
        }
        if (tags) {
            val note = "Com vários marcadores, o lançamento conta inteiro em cada um: a soma pode passar do total."
            check(sameText(InsightsTags.RANK_NOTE, note)) { "RANK_NOTE '${text(InsightsTags.RANK_NOTE)}' em Marcadores" }
        } else {
            check(!exists(InsightsTags.RANK_NOTE)) { "RANK_NOTE em Estabelecimentos" }
        }
    }

    private fun checkRow(i: Int, r: RankRow) {
        val t = norm(texts(InsightsTags.RANK_ROW + i).joinToString(" "))
        val count = if (r.count == 1) "(?<!\\d)1 lançamento(?!s)" else "(?<!\\d)${r.count} lançamentos"
        val value = "(?<![\\d.,])${Regex.escape(moneyPlain(r.total))}(?!\\d)"
        check(t.contains(norm(r.name)) && Regex(count).containsMatchIn(t) && Regex(value).containsMatchIn(t)) {
            "RANK_ROW.$i '$t', esperado ${r.name} · ${r.count} · ${moneyPlain(r.total)}"
        }
    }

    private fun checkCalendar(e: Expect) {
        val days = days(e.window)
        if (e.byDay.isEmpty()) {
            val empty = "Nada lançado em ${monthLong(e.month.month)}."
            check(sameText(InsightsTags.CAL_EMPTY, empty)) { "CAL_EMPTY '${text(InsightsTags.CAL_EMPTY)}', esperado '$empty'" }
            check(countPrefix(InsightsTags.CAL_DAY) == 0) { "CAL_DAY com nada gasto: ${countPrefix(InsightsTags.CAL_DAY)}" }
            for (t in listOf(InsightsTags.CAL_LEGEND, InsightsTags.CAL_SENTENCE, InsightsTags.CAL_SHARE)) check(!exists(t)) { "$t com nada gasto" }
            return
        }
        check(!exists(InsightsTags.CAL_EMPTY)) { "CAL_EMPTY com gasto ${e.byDay}" }
        check(countPrefix(InsightsTags.CAL_DAY) == days.size) { "CAL_DAY: ${countPrefix(InsightsTags.CAL_DAY)} nós, janela de ${days.size} dias" }
        // §7.5: step = highest day ÷ 4 rounded up to R$ 10; level = ⌈spent ÷ step⌉; 0 = no spending.
        val max = e.byDay.values.maxOf { it.v }
        val step = (max + 3_999) / 4_000 * 1_000
        for (d in days) {
            val spent = e.byDay[d]?.v ?: 0L
            val tag = InsightsTags.CAL_DAY + d
            val expDesc = if (spent == 0L) "${d.day}: sem gasto" else "${d.day}: ${money(Cents(spent))}"
            check(norm(desc(tag)) == norm(expDesc)) { "$tag descrição '${desc(tag)}', esperado '$expDesc'" }
            val level = if (spent == 0L) 0 else ((spent + step - 1) / step).toInt()
            check(level in 0..4) { "degrau $level fora de 0..4 (gasto $spent, degrau $step)" }
            check(state(tag) == "degrau $level") { "$tag estado '${state(tag)}', esperado 'degrau $level' (gasto $spent, maior $max, degrau $step)" }
        }
        val legend = "cada degrau ≈ R$$NNBSP${moneyParts(Cents(step)).first}"
        check(sameText(InsightsTags.CAL_LEGEND, legend)) { "CAL_LEGEND '${text(InsightsTags.CAL_LEGEND)}', esperado '$legend'" }

        // The heaviest weekday; ties: first in week order from the Ajustes week start.
        val order = (0 until 7).map { DayOfWeek((weekStart.isoDayNumber - 1 + it) % 7 + 1) }
        val byWeekday = order.associateWith { wd -> e.byDay.filterKeys { it.dayOfWeek == wd }.values.sumOf { it.v } }
        val top = order.maxBy { byWeekday.getValue(it) }
        val total = e.byDay.values.sumOf { it.v }
        val pct = (200 * byWeekday.getValue(top) + total) / (2 * total)
        val n = days.count { it.dayOfWeek == top }
        val fem = top.isoDayNumber <= 5
        val sentence = "${if (fem) "As" else "Os"} ${PLURAL.getValue(top)} pesam."
        val share = "$pct% do mês em ${countWord(n, fem)} ${if (n == 1) SINGULAR.getValue(top) else PLURAL.getValue(top)}"
        check(sameText(InsightsTags.CAL_SENTENCE, sentence)) { "CAL_SENTENCE '${text(InsightsTags.CAL_SENTENCE)}', esperado '$sentence' ($byWeekday)" }
        check(sameText(InsightsTags.CAL_SHARE, share)) { "CAL_SHARE '${text(InsightsTags.CAL_SHARE)}', esperado '$share' ($byWeekday)" }
    }

    // ---- Random actions on the Db ----

    private suspend fun randomAction() {
        val r = rnd.nextInt(100)
        when {
            r < 30 -> addTransaction()
            r < 38 -> editTransaction()
            r < 44 -> deleteTransaction()
            r < 52 -> refund()
            r < 56 -> undoRefund()
            r < 64 -> setStep()
            r < 67 -> clearStep()
            r < 74 -> setLimit()
            r < 79 -> createBudget()
            r < 82 -> renameBudget()
            r < 84 -> deleteBudget()
            r < 89 -> setAlias()
            r < 94 -> renameTag()
            r < 98 -> mergeTags()
            else -> deleteTag()
        }
    }

    private suspend fun budgets(): List<BudgetDetail> {
        db.ensureMonths(OCT)
        return db.budgetsOverview(OCT, OCT_9, BudgetOrder.MANUAL).map { db.budgetDetail(it.id, OCT, OCT_9) }
    }

    private suspend fun createBudget() {
        if (budgets().size >= 5) return
        val name = BUDGET_NAMES.filter { n -> budgets().none { it.name == n } }.randomOrNull(rnd) ?: return
        val month = months(MAY, OCT)[rnd.nextInt(6)]
        val day = if (month == OCT) rnd.nextInt(startDay, 10) else rnd.nextInt(startDay, 29)
        val today = LocalDate(month.year, month.month, day)
        val limit = Cents(rnd.nextLong(100_00, 2_000_01))
        val id = db.createBudget(name, limit, today)
        log += "createBudget($name, $limit, $today) = ${id.v}"
    }

    private suspend fun renameBudget() {
        val b = budgets().randomOrNull(rnd) ?: return
        val name = BUDGET_NAMES.random(rnd) + " " + rnd.nextInt(10)
        log += "renameBudget(${b.id.v}, $name) = ${db.renameBudget(b.id, name)}"
    }

    private suspend fun deleteBudget() {
        val all = budgets()
        if (all.size < 3) return
        val b = all.random(rnd)
        db.deleteBudget(b.id)
        log += "deleteBudget(${b.id.v})"
    }

    private suspend fun setLimit() {
        val b = budgets().randomOrNull(rnd) ?: return
        val from = months(b.firstMonth, OCT).random(rnd)
        val today = if (from == OCT) OCT_9 else LocalDate(from.year, from.month, rnd.nextInt(startDay, 29))
        val limit = Cents(rnd.nextLong(50_00, 1_500_01))
        log += "setLimit(${b.id.v}, $limit, $today) = ${db.setLimit(b.id, limit, today)}"
    }

    private suspend fun setStep() {
        val b = budgets().randomOrNull(rnd) ?: return
        val w = b.month.weeks.filter { !it.locked }.randomOrNull(rnd) ?: return
        val step = Cents(if (rnd.nextInt(5) == 0) 0 else rnd.nextLong(0, w.max.v + 1))
        log += "setStep(${b.id.v}, $OCT, ${w.start}, $step) = ${db.setStep(b.id, OCT, w.start, step)}"
    }

    private suspend fun clearStep() {
        val b = budgets().randomOrNull(rnd) ?: return
        val w = b.month.weeks.filter { it.edited }.randomOrNull(rnd) ?: return
        log += "clearStep(${b.id.v}, ${w.start}) = ${db.clearStep(b.id, OCT, w.start)}"
    }

    private suspend fun form(forceOctTag: String? = null): TransactionForm? {
        val b = budgets().randomOrNull(rnd) ?: return null
        val n = if (forceOctTag != null || rnd.nextInt(10) < 7) 1 else rnd.nextInt(2, 7)
        val total = Cents(if (rnd.nextInt(15) == 0) rnd.nextLong(n * 100L, 500_000) else rnd.nextLong(n * 100L, 60_000))
        val month = if (forceOctTag != null) OCT else months(maxOf(b.firstMonth, MAY), OCT).random(rnd)
        // Any installment that can reach the current month must be dated by day 9 (today), so the month's spending is all "até hoje".
        val maxDay = if (n > 1 || month == OCT) 9 else 28
        val date = LocalDate(month.year, month.month, rnd.nextInt(startDay, maxDay + 1))
        val tags = forceOctTag?.let { listOf(it) } ?: TAG_POOL.shuffled(rnd).take(rnd.nextInt(1, 4))
        val merchant = MERCHANTS.random(rnd).let { if (rnd.nextInt(4) == 0) it.lowercase().replace(" ", "  ") else it }
        val alias = if (rnd.nextInt(8) == 0) ALIASES.random(rnd) else ""
        return TransactionForm(b.id, tags, total, n, merchant, date, LocalTime(rnd.nextInt(24), rnd.nextInt(60)), alias)
    }

    private suspend fun addTransaction(forceOctTag: String? = null) {
        val f = form(forceOctTag) ?: return
        log += "addManual($f) = ${db.addManual(f).transaction.v}"
    }

    private suspend fun editTransaction() {
        val t = db.transactions().randomOrNull(rnd) ?: return
        val f = form() ?: return
        log += "edit(${t.id.v}, $f) = ${db.edit(t.id, f)}"
    }

    private suspend fun deleteTransaction() {
        val t = db.transactions().randomOrNull(rnd) ?: return
        db.deleteTransaction(t.id)
        log += "deleteTransaction(${t.id.v})"
    }

    private suspend fun refund() {
        val t = db.transactions().filter { !it.refunded }.randomOrNull(rnd) ?: return
        val at = noticeClock
        noticeClock += 600_000
        assertTrue(capture(db, RawNotice(packageName = BANK, appLabel = "Cartão Ipê", key = "e$at", postedAt = at, title = "Cartão Ipê", text = "Estorno de R$ 10,00 em LOJA")))
        val estorno = db.pending().first { it.raw.postedAt == at }.id
        val ok = db.registerRefund(estorno, t.id)
        if (ok) estornos += estorno
        log += "registerRefund(${estorno.v}, ${t.id.v}) = $ok"
    }

    private suspend fun undoRefund() {
        val e = estornos.randomOrNull(rnd) ?: return
        estornos -= e
        log += "undoRefund(${e.v}) = ${db.undoRefund(e)}"
    }

    private suspend fun setAlias() {
        val m = MERCHANTS.random(rnd)
        val alias = if (rnd.nextInt(3) == 0) "" else ALIASES.random(rnd)
        db.setAlias(m, alias)
        log += "setAlias($m, '$alias')"
    }

    private suspend fun renameTag() {
        val t = db.tags().randomOrNull(rnd) ?: return
        val name = (TAG_POOL + RENAMES).random(rnd)
        log += "renameTag(${t.name}, $name) = ${db.renameTag(t.id, name)}"
    }

    private suspend fun mergeTags() {
        val all = db.tags()
        if (all.size < 2) return
        val (a, b) = all.shuffled(rnd).take(2)
        log += "mergeTags(${a.name}, ${b.name}) = ${db.mergeTags(a.id, b.id)}"
    }

    private suspend fun deleteTag() {
        val t = db.tags().randomOrNull(rnd) ?: return
        db.deleteTag(t.id)
        log += "deleteTag(${t.name})"
    }

    // ---- Months and windows (spec §3, by hand) ----

    private fun window(m: YearMonth): Pair<LocalDate, LocalDate> {
        val next = m.plusMonth()
        return m.onDay(minOf(startDay, m.numberOfDays)) to next.onDay(minOf(startDay, next.numberOfDays)).minus(1, DateTimeUnit.DAY)
    }

    private fun days(w: Pair<LocalDate, LocalDate>): List<LocalDate> =
        generateSequence(w.first) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= w.second }.toList()

    private fun months(from: YearMonth, to: YearMonth): List<YearMonth> =
        generateSequence(from) { it.plusMonth() }.takeWhile { it <= to }.toList()

    private fun YearMonth.minusMonths(n: Int): YearMonth = (1..n).fold(this) { m, _ -> m.minusMonth() }

    // ---- Semantics helpers (unmerged tree: a tag may sit inside a merged parent) ----

    private fun nodes(tag: String): List<SemanticsNode> = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun node(tag: String): SemanticsNode? = nodes(tag).firstOrNull()

    private fun exists(tag: String) = node(tag) != null

    private fun nodesWithPrefix(prefix: String): List<SemanticsNode> = compose.onAllNodes(
        SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun countPrefix(prefix: String) = nodesWithPrefix(prefix).size

    private fun subtree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { subtree(it) }

    /** Every Text and EditableText in the node with [tag] and below it, in tree order. */
    private fun texts(tag: String): List<String> = node(tag)?.let { n ->
        subtree(n).flatMap { s ->
            s.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + listOfNotNull(s.config.getOrNull(SemanticsProperties.EditableText)?.text)
        }
    }.orEmpty()

    private fun text(tag: String) = texts(tag).joinToString("")

    /** Exact text, ignoring how it is split into pieces and the kind of space. */
    private fun sameText(tag: String, expected: String): Boolean {
        val t = texts(tag)
        return norm(t.joinToString("")) == norm(expected) || norm(t.joinToString(" ")) == norm(expected)
    }

    private fun desc(tag: String): String = node(tag)?.let { n ->
        subtree(n).firstNotNullOfOrNull { s -> s.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("")?.takeIf { it.isNotEmpty() } }
    }.orEmpty()

    private fun state(tag: String): String? = node(tag)?.let { n -> subtree(n).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.StateDescription) } }

    private fun selected(tag: String) = node(tag)?.let { n -> subtree(n).any { it.config.getOrNull(SemanticsProperties.Selected) == true } } == true

    private fun enabled(tag: String) = node(tag)?.let { n -> subtree(n).none { it.config.contains(SemanticsProperties.Disabled) } } == true

    private fun click(tag: String) {
        eventually("tocar $tag") { check(exists(tag)) { "$tag ausente" } }
        runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo() }
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun ctx(m: YearMonth) = "seed=$seed startDay=$startDay weekStart=$weekStart lote=$batch mês=$m escolhida=${chosen?.v}\nações do lote:\n  " +
        log.joinToString("\n  ") + "\n"

    /** Waits for [block] to pass (the screen queries on IO), then runs it once more and reports with the run's context. */
    private fun eventually(what: String, block: () -> Unit) {
        try {
            compose.waitUntil(8_000) { runCatching(block).isSuccess }
        } catch (_: ComposeTimeoutException) {
        }
        try {
            block()
        } catch (e: IllegalStateException) {
            throw AssertionError(ctx(OCT) + "$what: ${e.message}", e)
        }
    }

    private companion object {
        val OCT_9 = LocalDate(2026, 10, 9)
        val OCT = YearMonth(2026, 10)
        val MAY = YearMonth(2026, 5)
        const val BANK = "com.bank"
        const val BATCHES = 5
        const val ACTIONS = 6

        val BUDGET_NAMES = listOf("Mercado", "Restaurantes", "Lazer", "Casa", "Transporte", "Saúde", "Presentes")
        val TAG_POOL = listOf("feira", "padaria", "cafe", "uber", "farmacia", "cinema", "livros", "pet", "presente", "bar")
        val RENAMES = listOf("mercado", "viagem", "saude")
        val MERCHANTS = listOf(
            "PADARIA DO ZE", "SUPERMERCADO AURORA", "POSTO IPE", "FARMA VIDA", "LIVRARIA SOL", "CINE ARTE", "UBER TRIP", "PET LAR", "BAR DO TONI",
        )
        val ALIASES = listOf("Padoca", "Quitanda", "Boteco", "Sebo")

        val OUT_OF_MARGIN = listOf("Nenhum mês", "Um mês", "Dois meses", "Três meses", "Quatro meses", "Cinco meses", "Seis meses")
        val PLURAL = mapOf(
            DayOfWeek.MONDAY to "segundas", DayOfWeek.TUESDAY to "terças", DayOfWeek.WEDNESDAY to "quartas", DayOfWeek.THURSDAY to "quintas",
            DayOfWeek.FRIDAY to "sextas", DayOfWeek.SATURDAY to "sábados", DayOfWeek.SUNDAY to "domingos",
        )
        val SINGULAR = mapOf(
            DayOfWeek.MONDAY to "segunda", DayOfWeek.TUESDAY to "terça", DayOfWeek.WEDNESDAY to "quarta", DayOfWeek.THURSDAY to "quinta",
            DayOfWeek.FRIDAY to "sexta", DayOfWeek.SATURDAY to "sábado", DayOfWeek.SUNDAY to "domingo",
        )

        fun countWord(n: Int, fem: Boolean) = when (n) {
            1 -> if (fem) "uma" else "um"
            2 -> if (fem) "duas" else "dois"
            3 -> "três"
            4 -> "quatro"
            5 -> "cinco"
            else -> n.toString()
        }

        /** "R$ 796": whole reais, rounded half up, thousands dot. */
        fun whole(c: Cents) = "R$$NNBSP" + moneyParts(Cents((c.v + 50) / 100 * 100)).first

        /** NNBSP and NBSP read as a plain space; runs of spaces collapse. */
        fun norm(s: String) = s.replace('\u202F', ' ').replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }
}
