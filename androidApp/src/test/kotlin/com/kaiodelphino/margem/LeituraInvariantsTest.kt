package com.kaiodelphino.margem

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.NNBSP
import com.kaiodelphino.margem.widgets.dateShort
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.moneyParts
import com.kaiodelphino.margem.widgets.moneyPlain
import com.kaiodelphino.margem.widgets.monthLong
import com.kaiodelphino.margem.widgets.monthShort
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Leitura v2 (spec §7.5 v2, new-leitura-page), invariantes entre a tela e as leituras públicas do Db (InsightsTags é o
 * contrato). Sequências aleatórias com semente fixa de ações no Db (verbas, cotas, passos, lançamentos parcelados,
 * estornos, apagar, marcadores, apelidos) com a tela aberta; depois de cada lote, para o intervalo mostrado:
 *
 * - resumo (SUMMARY) = soma e número, à mão, das parcelas não estornadas do intervalo, "até hoje" sse o intervalo tem hoje;
 * - compras estornadas não contam: `spentByDay` bate com as parcelas não estornadas, e Σ `history` do mês com o total;
 * - Calendário: um DAY por dia do intervalo, descrição = `spentByDay`; frase do dia mais pesado (o primeiro, num empate);
 * - Dia da semana: média por dia da semana nos dias até hoje, frase só com um maior sem empate;
 * - Mês a mês: o mês do fim do intervalo e a média dos 5 anteriores, somados à mão;
 * - Para onde foi: a frase nomeia a verba de maior soma; na página, por marcador = `tagRanking`, por estabelecimento =
 *   `merchantRanking`, e a lista Maiores tem uma linha por parcela, a maior primeiro;
 * - Ritmo e Margem, com mês inteiro e sem chip: o BudgetMonth somado de todas as verbas de `budgetsOverview`; com um chip
 *   de verba: o BudgetMonth dela (a "verba escolhida" da v1); a página da Margem lista as verbas na ordem de risco;
 * - "mês passado" na folha de intervalo: tudo acima para a janela anterior, com "Fechou em" no Ritmo.
 *
 * Saíram com a v1 (spec §7.5 v2): navegação mês a mês, a escolha de verba do Ritmo, o "Verbas por mês" de uma verba,
 * as abas Marcadores/Estabelecimentos com "Ver todos (n)", e o degrau, a legenda e a fatia do dia do calendário.
 *
 * Hoje fixo: sex, 9 out 2026. Datas de compra com dia ≤ 9 quando alguma parcela pode cair no mês atual, para que o gasto
 * do mês atual seja todo "até hoje".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h3000dp")
class LeituraInvariantsTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db
    private lateinit var rnd: Random
    private var seed = 0
    private var startDay = 1
    private var weekStart = DayOfWeek.MONDAY
    private var batch = 0
    private val log = mutableListOf<String>()
    private val estornos = mutableListOf<NoticeId>()
    private var noticeClock = 1_790_000_000_000L

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
        runBlocking { assertTrue(db.addSource(BANK, "Cartão Ipê", 1L)) }
    }

    /** Set by the teardown: the screen leaves before the Db closes, so no query in flight meets a closed Db. */
    private var gone by mutableStateOf(false)

    @After
    fun tearDown() {
        runCatching {
            gone = true
            compose.waitForIdle()
        }
        db.close()
    }

    @Test
    fun `with no budget only the empty state shows`() {
        open()
        eventually("sem verba") {
            // InsightsTags.EMPTY: the title "Nada para ler ainda." comes first, then the sentence about the Leitura.
            check(text(InsightsTags.EMPTY).startsWith("Nada para ler ainda.")) { "EMPTY: ${text(InsightsTags.EMPTY)}" }
            for (t in listOf(InsightsTags.GRID, InsightsTags.SUMMARY, InsightsTags.RANGE, InsightsTags.PACE_SENTENCE)) check(!exists(t)) { "$t presente sem verba" }
            check(countPrefix(InsightsTags.WIDGET) == 0) { "WIDGET sem verba" }
            check(countPrefix(InsightsTags.DAY) == 0) { "DAY sem verba" }
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
        checkBatch(null)
        for (b in 1..BATCHES) {
            batch = b
            log.clear()
            runBlocking { repeat(ACTIONS) { randomAction() } }
            // Each batch checks the grid, then one of the four ways in, in turn, so every run goes through all of them.
            checkBatch(EXTRAS[(b - 1) % EXTRAS.size])
        }
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
    private val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun checkBatch(extra: String?) {
        if (overview(OCT).isEmpty()) {
            eventually("sem verba") { check(exists(InsightsTags.EMPTY)) { "EMPTY ausente sem verba" } }
            return
        }
        val e = expect(OCT, null)
        eventually("mês $OCT, grade") { checkGrid(e) }
        when (extra) {
            "chip" -> checkBudgetChip()
            "pages" -> checkPages(e)
            "margem" -> checkMargemPage()
            "past" -> checkLastMonth()
        }
    }

    /** A verba chip (the v1's "verba escolhida"): the Ritmo and the Margem become that verba's; taking it off comes back. */
    private fun checkBudgetChip() {
        val risk = overview(OCT)
        val b = risk[rnd.nextInt(risk.size)]
        pickBudget(b.name)
        val e = expect(OCT, b.id)
        eventually("chip ${b.name}") { checkSummary(e); checkPace(e); checkMargem(e) }
        val chip = hasText(b.name) and hasAnyAncestor(hasTestTag(InsightsTags.CHIPS))
        compose.onAllNodes(chip, useUnmergedTree = true).onFirst().performClick()
        eventually("chip ${b.name} tirado") { check(!exists(InsightsTags.CHIPS)) { "CHIPS ainda na tela" } }
        eventually("de volta sem chip") { checkGrid(expect(OCT, null)) }
    }

    /** Para onde foi's page: by marcador = tagRanking, by estabelecimento = merchantRanking, the list one row per parcela. */
    private fun checkPages(e: Expect) {
        openPage("para-onde-foi")
        val page = InsightsTags.PAGE + "para-onde-foi"
        option("marcador")
        eventually("Para onde foi por marcador") { checkRanks(page, e.tags) { shown, row -> norm(shown) == norm(row.name) } }
        option("estabelecimento")
        eventually("Para onde foi por estabelecimento") { checkRanks(page, e.merchants) { shown, row -> MerchantKey.of(shown) == MerchantKey.of(row.name) } }
        eventually("lista Maiores") {
            // LIST_ROW + index only: the Maiores/Recentes picker's tags share the prefix ("insights.list.recent.").
            val n = nodesWithPrefix(InsightsTags.LIST_ROW).count { it.config[SemanticsProperties.TestTag].removePrefix(InsightsTags.LIST_ROW).all(Char::isDigit) }
            check(n == e.installments.size) { "LIST_ROW: $n linhas, ${e.installments.size} parcelas no intervalo" }
            if (n > 0) {
                val top = e.installments.maxOf { it }
                check(texts(InsightsTags.LIST_ROW + 0).any { norm(it) == norm(moneyPlain(Cents(top))) }) { "LIST_ROW.0 ${texts(InsightsTags.LIST_ROW + 0)}, esperado a maior parcela ${moneyPlain(Cents(top))}" }
            }
        }
        back()
        eventually("de volta à grade") { checkGrid(e) }
    }

    /** Margem's page: one régua per verba, in the order of risk of budgetsOverview (the default order). */
    private fun checkMargemPage() {
        val risk = overview(OCT)
        openPage("margem")
        eventually("Margem por risco") {
            val t = texts(InsightsTags.PAGE + "margem").map(::norm)
            val at = risk.map { b -> t.indexOf(norm(b.name)) }
            check(at.none { it < 0 } && at.zipWithNext().all { (a, b) -> a < b }) { "verbas na página $t, esperado na ordem de risco ${risk.map { it.name }}" }
        }
        back()
    }

    /** "mês passado" from the interval sheet: every check for the window before, then "este mês" back. */
    private fun checkLastMonth() {
        val m = OCT.minusMonth()
        runBlocking { db.ensureMonths(m) }
        val past = overview(m)
        // Without a verba with cota in September the Ritmo compares with the interval before instead (R17).
        if (past.isEmpty()) return
        pickRange("mês passado")
        val e = expect(m, null)
        eventually("mês passado") { checkGrid(e) }
        pickRange("este mês")
        eventually("este mês de volta") { checkGrid(expect(OCT, null)) }
    }

    // ---- Expectations, from public Db reads and the spec's formulas ----

    /** The BudgetMonths of the verbas behind the Ritmo and the Margem, summed. */
    private data class Plan(val limit: Long, val spent: Long, val remaining: Long, val variable: Long, val ideal: Long, val projection: Long)

    private class Expect(
        val month: YearMonth,
        val window: Pair<LocalDate, LocalDate>,
        val current: Boolean,
        val plan: Plan,
        val total: Long,
        val count: Int,
        val installments: List<Long>,
        val byDay: Map<LocalDate, Cents>,
        val months: List<Long>,
        val budgets: Map<String, Long>,
        val tags: List<RankRow>,
        val merchants: List<RankRow>,
    )

    private fun overview(m: YearMonth): List<BudgetSummary> = runBlocking {
        db.ensureMonths(OCT)
        db.budgetsOverview(m, OCT_9, BudgetOrder.RISK)
    }

    private fun expect(m: YearMonth, only: BudgetId?): Expect = runBlocking {
        db.ensureMonths(OCT)
        val w = window(m)
        val ctx = ctx(m)

        // Refunded purchases never count: by hand from transactions() and each one's installments.
        val mine = mutableMapOf<LocalDate, Long>()
        val parcels = mutableListOf<Long>()
        var count = 0
        val byMonth = LongArray(6)
        val wins = (5 downTo 0).map { window(m.minusMonths(it)) }
        for (t in db.transactions()) {
            if (t.refunded || (only != null && t.budget != only)) continue
            var counted = false
            for (i in db.transaction(t.id).installments) {
                for ((k, mw) in wins.withIndex()) if (i.date >= mw.first && i.date <= mw.second) byMonth[k] += i.cents.v
                if (i.date < w.first || i.date > w.second) continue
                mine[i.date] = (mine[i.date] ?: 0L) + i.cents.v
                parcels += i.cents.v
                counted = true
            }
            if (counted) count++
        }
        val total = parcels.sum()
        val budgets = db.history(m, m).filter { only == null || it.budget == only }.groupBy { it.name }.mapValues { (_, rs) -> rs.sumOf { it.spent.v } }
        if (only == null) {
            val byDay = db.spentByDay(w.first, w.second)
            assertEquals("$ctx spentByDay × parcelas não estornadas", mine.filterValues { it > 0 }.mapValues { Cents(it.value) }, byDay)
            assertEquals("$ctx Σ history do mês = Σ parcelas", total, budgets.values.sum())
        }
        val tags = db.tagRanking(w.first, w.second)
        val merchants = db.merchantRanking(w.first, w.second)
        if (only == null) {
            assertEquals("$ctx Σ merchantRanking = Σ parcelas", total, merchants.sumOf { it.total.v })
            assertTrue("$ctx Σ tagRanking ≥ Σ parcelas", tags.sumOf { it.total.v } >= total)
        }

        // D44 on every verba of the month; the plan is their sum (or the one verba of the chip).
        db.ensureMonths(m)
        val months = db.budgetsOverview(m, OCT_9, BudgetOrder.MANUAL).filter { only == null || it.id == only }.map { it.month }
        for (bm in months) {
            val days = bm.start.daysUntil(bm.end) + 1
            assertEquals("$ctx janela do BudgetMonth", w, bm.start to bm.end)
            assertEquals("$ctx idealByDay.size", days, bm.idealByDay.size)
            assertTrue("$ctx idealByDay não decresce: ${bm.idealByDay}", bm.idealByDay.zipWithNext().all { (a, b) -> a <= b })
            if (m == OCT) assertEquals("$ctx idealByDay[hoje] = ideal", bm.ideal, bm.idealByDay[bm.start.daysUntil(OCT_9)])
        }
        val plan = Plan(
            months.sumOf { it.limit.v }, months.sumOf { it.spent.v }, months.sumOf { it.remaining.v }, months.sumOf { it.variable.v },
            months.sumOf { it.ideal.v }, months.sumOf { it.projection.v },
        )
        Expect(m, w, m == OCT, plan, total, count, parcels, mine.filterValues { it > 0 }.mapValues { Cents(it.value) }, byMonth.toList(), budgets, tags, merchants)
    }

    // ---- Checks of the screen (throw IllegalStateException via check; eventually retries) ----

    private fun checkGrid(e: Expect) {
        check(!exists(InsightsTags.EMPTY)) { "EMPTY com verbas" }
        checkSummary(e)
        checkPace(e)
        checkMargem(e)
        checkCalendar(e)
        checkWeekday(e)
        checkMonths(e)
        checkWhere(e)
    }

    private fun checkSummary(e: Expect) {
        val range = span(e.window.first, e.window.second)
        check(sameText(InsightsTags.RANGE, range)) { "RANGE '${text(InsightsTags.RANGE)}', esperado '$range'" }
        val summary = "${money(Cents(e.total))} · ${e.count} ${if (e.count == 1) "lançamento" else "lançamentos"}" + if (e.current) " · até hoje" else ""
        check(sameText(InsightsTags.SUMMARY, summary)) { "SUMMARY '${text(InsightsTags.SUMMARY)}', esperado '$summary'" }
    }

    /** The Ritmo with a whole month and no chip but verbas (R17): the summed BudgetMonth, sentence and chart by the KDoc. */
    private fun checkPace(e: Expect) {
        val p = e.plan
        val projects = p.ideal > 0 && p.spent > 0
        val sentence = if (e.current) {
            val head = when {
                p.remaining < 0 -> "${money(Cents(-p.remaining))} além da cota."
                p.variable > p.ideal -> "+ ${money(Cents(p.variable - p.ideal))} sobre o ritmo ideal."
                p.variable < p.ideal -> "${money(Cents(p.ideal - p.variable))} abaixo do ritmo ideal."
                else -> "No ritmo ideal."
            }
            head + if (projects) " No passo atual, o mês fecharia em ${whole(Cents(p.projection))}." else ""
        } else when {
            p.remaining > 0 -> "Fechou em ${money(Cents(p.spent))}, ${money(Cents(p.remaining))} dentro da cota."
            p.remaining < 0 -> "Fechou em ${money(Cents(p.spent))}, ${money(Cents(-p.remaining))} além da cota."
            else -> "Fechou em ${money(Cents(p.spent))}, exatamente a cota."
        }
        check(sameText(InsightsTags.PACE_SENTENCE, sentence)) { "PACE_SENTENCE '${text(InsightsTags.PACE_SENTENCE)}', esperado '$sentence' ($p)" }
        val chart = if (e.current) {
            "Ritmo do mês: ${money(Cents(p.variable))} até o dia ${OCT_9.day}, contra ritmo ideal de ${money(Cents(p.ideal))}" +
                (if (projects) "; no passo atual, ${whole(Cents(p.projection))} no fim do mês" else "") + ", para uma cota de ${money(Cents(p.limit))}"
        } else "Ritmo do mês: ${money(Cents(p.spent))} no mês, para uma cota de ${money(Cents(p.limit))}"
        val d = norm(desc(InsightsTags.PACE_CHART))
        check(d.startsWith(norm(chart))) { "PACE_CHART '$d', esperado '$chart'" }
    }

    /** Margem of the month the interval ends in (D-10), with the month in the sentence. */
    private fun checkMargem(e: Expect) {
        val month = monthLong(e.month.month)
        val r = e.plan.remaining
        val sentence = if (r >= 0) "Em $month, cabem ${money(Cents(r))}." else "Em $month, ${money(Cents(-r))} além da cota."
        check(shows(InsightsTags.WIDGET + "margem", sentence)) { "Margem ${texts(InsightsTags.WIDGET + "margem")}, esperado '$sentence'" }
    }

    private fun checkCalendar(e: Expect) {
        val days = days(e.window)
        check(countPrefix(InsightsTags.DAY) == days.size) { "DAY: ${countPrefix(InsightsTags.DAY)} nós, janela de ${days.size} dias" }
        for (d in days) {
            val spent = e.byDay[d]?.v ?: 0L
            val expDesc = if (spent == 0L) "${d.day}: sem gasto" else "${d.day}: ${money(Cents(spent))}"
            check(norm(desc(InsightsTags.DAY + d)) == norm(expDesc)) { "DAY.$d descrição '${desc(InsightsTags.DAY + d)}', esperado '$expDesc'" }
        }
        val max = e.byDay.values.maxOfOrNull { it.v }
        val sentence = if (max == null) "Nada lançado no intervalo." else {
            val day = days.first { (e.byDay[it]?.v ?: 0L) == max }
            "O dia mais pesado foi ${dateShort(day)}: ${money(Cents(max))}."
        }
        check(shows(InsightsTags.WIDGET + "calendario", sentence)) { "Calendário ${texts(InsightsTags.WIDGET + "calendario")}, esperado '$sentence'" }
    }

    /** Dia da semana (AC-40): the mean per occurrence over the days so far; the heaviest only when > 0 and alone. */
    private fun checkWeekday(e: Expect) {
        val sofar = days(e.window).filter { !e.current || it <= OCT_9 }
        val order = (0 until 7).map { DayOfWeek((weekStart.isoDayNumber - 1 + it) % 7 + 1) }
        val means = order.map { wd ->
            val ds = sofar.filter { it.dayOfWeek == wd }
            wd to if (ds.isEmpty()) 0L else ds.sumOf { e.byDay[it]?.v ?: 0L } / ds.size
        }
        val top = means.maxOf { it.second }
        val heavy = means.filter { it.second == top }.singleOrNull()?.takeIf { top > 0 }
        val sentence = if (heavy == null) "Nenhum dia da semana pesa." else
            "${if (heavy.first.isoDayNumber <= 5) "As" else "Os"} ${PLURAL.getValue(heavy.first)} pesam: ${money(Cents(heavy.second))} por dia."
        check(shows(InsightsTags.WIDGET + "dia-da-semana", sentence)) { "Dia da semana ${texts(InsightsTags.WIDGET + "dia-da-semana")}, esperado '$sentence' ($means)" }
    }

    /** Mês a mês (AC-42), 6 months by default: the last one and the mean of the 5 before. */
    private fun checkMonths(e: Expect) {
        val mean = e.months.dropLast(1).sum() / 5
        val sentence = "Em ${monthLong(e.month.month)}, ${money(Cents(e.months.last()))}; a média dos anteriores é ${money(Cents(mean))}."
        check(shows(InsightsTags.WIDGET + "mes-a-mes", sentence)) { "Mês a mês ${texts(InsightsTags.WIDGET + "mes-a-mes")}, esperado '$sentence' (${e.months})" }
    }

    /** Para onde foi on the grid groups by verba: "<the heaviest> puxa a fila." (any of a tie). */
    private fun checkWhere(e: Expect) {
        val t = texts(InsightsTags.WIDGET + "para-onde-foi").map(::norm)
        val max = e.budgets.values.filter { it > 0 }.maxOrNull()
        if (max == null) {
            check(t.contains("Nada lançado no intervalo.")) { "Para onde foi $t, esperado o vazio" }
            return
        }
        val heavy = e.budgets.filterValues { it == max }.keys
        check(heavy.any { t.contains(norm("$it puxa a fila.")) }) { "Para onde foi $t, esperado uma de $heavy puxando a fila (${e.budgets})" }
    }

    /** Each ranking row shows as its name, then its value within the next two texts; and nothing else ranks. */
    private fun checkRanks(page: String, rows: List<RankRow>, same: (String, RankRow) -> Boolean) {
        val t = texts(page).map(::norm)
        for (r in rows) {
            val value = norm(moneyPlain(r.total))
            check(t.indices.any { i -> same(t[i], r) && (t.getOrNull(i + 1) == value || t.getOrNull(i + 2) == value) }) { "$page sem ${r.name} · $value: $t" }
        }
        if (rows.isEmpty()) check(t.contains("Nada lançado no intervalo.")) { "$page sem o vazio: $t" }
    }

    // ---- Interaction ----

    /** The filter, reopened from the lupa when the grid scrolled past it. */
    private fun filter() {
        if (!exists(InsightsTags.SEARCH) && exists(InsightsTags.LENS)) click(InsightsTags.LENS)
        eventually("filtro aberto") { check(exists(InsightsTags.SEARCH)) { "SEARCH ausente" } }
    }

    private fun pickBudget(name: String) {
        filter()
        compose.onNodeWithTag(InsightsTags.SEARCH, useUnmergedTree = true).performTextReplacement(name)
        val section = InsightsTags.SECTION + ChipKind.BUDGET.name
        val row = hasText(name) and hasAnyAncestor(hasTestTag(section))
        eventually("sugestão $name") { check(compose.onAllNodes(row, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) { "$section: ${texts(section)}" } }
        compose.onAllNodes(row, useUnmergedTree = true).onFirst().performClick()
        eventually("chip $name") { check(shows(InsightsTags.CHIPS, name)) { "CHIPS: ${texts(InsightsTags.CHIPS)}" } }
    }

    private fun pickRange(label: String) {
        filter()
        click(InsightsTags.RANGE)
        eventually("folha de intervalo") { check(compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty()) { "'$label' ausente" } }
        compose.onAllNodes(hasText(label)).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun openPage(id: String) {
        click(InsightsTags.WIDGET + id)
        eventually("página $id") { check(exists(InsightsTags.PAGE + id)) { "PAGE.$id ausente" } }
    }

    private fun option(label: String) {
        eventually("opção $label") { check(compose.onAllNodes(hasText(label) and radioRole).fetchSemanticsNodes().isNotEmpty()) { "opção $label ausente" } }
        compose.onAllNodes(hasText(label) and radioRole).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        eventually("grade de volta") { check(exists(InsightsTags.GRID)) { "GRID ausente" } }
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

    private suspend fun form(): TransactionForm? {
        val b = budgets().randomOrNull(rnd) ?: return null
        val n = if (rnd.nextInt(10) < 7) 1 else rnd.nextInt(2, 7)
        val total = Cents(if (rnd.nextInt(15) == 0) rnd.nextLong(n * 100L, 500_000) else rnd.nextLong(n * 100L, 60_000))
        val month = months(maxOf(b.firstMonth, MAY), OCT).random(rnd)
        // Any installment that can reach the current month must be dated by day 9 (today), so the month's spending is all "até hoje".
        val maxDay = if (n > 1 || month == OCT) 9 else 28
        val date = LocalDate(month.year, month.month, rnd.nextInt(startDay, maxDay + 1))
        val tags = TAG_POOL.shuffled(rnd).take(rnd.nextInt(1, 4))
        val merchant = MERCHANTS.random(rnd).let { if (rnd.nextInt(4) == 0) it.lowercase().replace(" ", "  ") else it }
        val alias = if (rnd.nextInt(8) == 0) ALIASES.random(rnd) else ""
        return TransactionForm(b.id, tags, total, n, merchant, date, LocalTime(rnd.nextInt(24), rnd.nextInt(60)), alias)
    }

    private suspend fun addTransaction() {
        val f = form() ?: return
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

    /** "1 a 30 nov", "5 out a 4 nov" (the KDoc of InsightsTags.RANGE). */
    private fun span(a: LocalDate, b: LocalDate) =
        if (a.year == b.year && a.month == b.month) "${a.day} a ${b.day} ${monthShort(b.month)}" else "${a.day} ${monthShort(a.month)} a ${b.day} ${monthShort(b.month)}"

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

    /** One of the texts under [tag] is exactly [expected]. */
    private fun shows(tag: String, expected: String) = texts(tag).any { norm(it) == norm(expected) }

    private fun desc(tag: String): String = node(tag)?.let { n ->
        subtree(n).firstNotNullOfOrNull { s -> s.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("")?.takeIf { it.isNotEmpty() } }
    }.orEmpty()

    private fun click(tag: String) {
        eventually("tocar $tag") { check(exists(tag)) { "$tag ausente" } }
        runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo() }
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun ctx(m: YearMonth) = "seed=$seed startDay=$startDay weekStart=$weekStart lote=$batch mês=$m\nações do lote:\n  " +
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
        val EXTRAS = listOf("chip", "pages", "margem", "past")

        val BUDGET_NAMES = listOf("Mercado", "Restaurantes", "Lazer", "Casa", "Transporte", "Saúde", "Presentes")
        val TAG_POOL = listOf("feira", "padaria", "cafe", "uber", "farmacia", "cinema", "livros", "pet", "presente", "bar")
        val RENAMES = listOf("mercado", "viagem", "saude")
        val MERCHANTS = listOf(
            "PADARIA DO ZE", "SUPERMERCADO AURORA", "POSTO IPE", "FARMA VIDA", "LIVRARIA SOL", "CINE ARTE", "UBER TRIP", "PET LAR", "BAR DO TONI",
        )
        val ALIASES = listOf("Padoca", "Quitanda", "Boteco", "Sebo")

        val PLURAL = mapOf(
            DayOfWeek.MONDAY to "segundas", DayOfWeek.TUESDAY to "terças", DayOfWeek.WEDNESDAY to "quartas", DayOfWeek.THURSDAY to "quintas",
            DayOfWeek.FRIDAY to "sextas", DayOfWeek.SATURDAY to "sábados", DayOfWeek.SUNDAY to "domingos",
        )

        /** "R$ 796": whole reais, rounded half up, thousands dot. */
        fun whole(c: Cents) = "R$$NNBSP" + moneyParts(Cents((c.v + 50) / 100 * 100)).first

        /** NNBSP and NBSP read as a plain space; runs of spaces collapse. */
        fun norm(s: String) = s.replace(' ', ' ').replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
    }
}
