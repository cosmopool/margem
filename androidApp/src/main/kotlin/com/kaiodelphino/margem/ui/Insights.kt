package com.kaiodelphino.margem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.kaiodelphino.margem.BudgetMonth
import com.kaiodelphino.margem.BudgetOrder
import com.kaiodelphino.margem.Cents
import com.kaiodelphino.margem.Chip
import com.kaiodelphino.margem.ChipKind
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.LeituraQuery
import com.kaiodelphino.margem.LeituraRow
import com.kaiodelphino.margem.MerchantKey
import com.kaiodelphino.margem.NO_DATE
import com.kaiodelphino.margem.PaceStatus
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.Repeats
import com.kaiodelphino.margem.fold
import com.kaiodelphino.margem.widgets.BottomSheet
import com.kaiodelphino.margem.widgets.Button
import com.kaiodelphino.margem.widgets.ButtonKind
import com.kaiodelphino.margem.widgets.Check
import com.kaiodelphino.margem.widgets.DayHeader
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Icon
import com.kaiodelphino.margem.widgets.IconButton
import com.kaiodelphino.margem.widgets.ListRow
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.Meter
import com.kaiodelphino.margem.widgets.MonthPicker
import com.kaiodelphino.margem.widgets.NNBSP
import com.kaiodelphino.margem.widgets.OptionPicker
import com.kaiodelphino.margem.widgets.PageHeader
import com.kaiodelphino.margem.widgets.SearchLine
import com.kaiodelphino.margem.widgets.SectionLine
import com.kaiodelphino.margem.widgets.SeriesMark
import com.kaiodelphino.margem.widgets.TagChip
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.dashedBox
import com.kaiodelphino.margem.widgets.dateShort
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.moneyParts
import com.kaiodelphino.margem.widgets.moneyPlain
import com.kaiodelphino.margem.widgets.monthLong
import com.kaiodelphino.margem.widgets.monthShort
import com.kaiodelphino.margem.widgets.sans
import com.kaiodelphino.margem.widgets.serif
import com.kaiodelphino.margem.widgets.weekdayShort
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.minusMonth
import kotlinx.datetime.onDay
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import kotlin.math.abs

/**
 * Leitura's elements for UI tests (D35, spec §7.5 v2). Money: `money` ("R$ 231,10") and `moneyPlain` ("231,10") of
 * widgets/Theme.kt. "The interval" is the query's from..to; "the lines" are its installments (Db.leituraLines).
 */
object InsightsTags {
    /**
     * Instead of everything else when there are no budgets: "Nada para ler ainda.", then "A Leitura mostra
     * para onde o dinheiro vai: verbas mês a mês, o ritmo do mês, marcadores e estabelecimentos."
     */
    const val EMPTY = "insights.empty"

    // ---- The filter (R1–R5), on the grid and at the top of every page ----

    /** The interval ("1 a 30 nov"); click opens the sheet: "este mês", "mês passado", "3 meses", "6 meses" and the calendar. */
    const val RANGE = "insights.range"

    /** The one field: "Buscar verba, marcador, estabelecimento ou recado". */
    const val SEARCH = "insights.search"

    /**
     * Prefix: SECTION + ChipKind.name: the suggestions of that kind for the text typed, in the order Verbas ·
     * Marcadores · Estabelecimentos · Recados, each with "2 lançamentos" and moneyPlain of its sum in the interval.
     * Case and accents don't count; an estabelecimento matches by alias or bank text, never by legenda. Click: a chip.
     */
    const val SECTION = "insights.section."

    /** The chips, "ou" between them; a click on one takes it off. */
    const val CHIPS = "insights.chips"

    /** "R$ 130,00 · 5 lançamentos · até hoje" ("até hoje" while the interval holds today). */
    const val SUMMARY = "insights.summary"

    /** "Separar", with 2 chips or more: one series per chip, "a + b" for a line that matches both (R5). */
    const val SEPARATE = "insights.separate"

    // ---- The grid (R7–R10) ----

    /** The grid that scrolls. */
    const val GRID = "insights.grid"

    /** Prefix: CONTEXT + "quanto" | "para-onde" | "quando": the context titles, in that order. */
    const val CONTEXT = "insights.context."

    /** The bar under the TopBar with the name of the context whose title went off the top. */
    const val PINNED = "insights.pinned"

    /** The lupa in the TopBar once the filter collapsed (its badge: the number of chips); click reopens the filter. */
    const val LENS = "insights.lens"

    /**
     * Prefix: WIDGET + id: a widget of the grid, with its sentence; click opens its page. Ids: margem, total, ritmo,
     * previstos, historia, fora (only with something left out), retrato (only with one estabelecimento chip, which
     * takes out voltam, cresceu and primeira-vez), fluxo, para-onde-foi, cresceu, voltam, primeira-vez (only with
     * something new), tamanho, calendario, dia-da-semana, semanas, mes-a-mes, meses-a-frente, volta-todo-mes, dia-e-hora.
     */
    const val WIDGET = "insights.widget."

    // ---- The widget's page (R12–R14) ----

    /** Prefix: PAGE + id: the page of that widget (filter, sentence, options as OptionPicker, chart, piece, list). */
    const val PAGE = "insights.page."

    /** Prefix: LIST_ROW + index from 0: the page's list, Maiores (numbered, with each one's share) or Recentes (by day). */
    const val LIST_ROW = "insights.list."

    /** Prefix: DAY + ISO date: a day of the Calendário; click makes it the piece. */
    const val DAY = "insights.day."

    /** "Só X": the Leitura gets only the piece's chip, back on the grid, with Desfazer. */
    const val ONLY = "insights.only"

    /** "+ ou X": the piece's chip joins the Leitura's, back on the grid, with Desfazer. */
    const val OR = "insights.or"

    /** "Usar como intervalo": the piece's days become the Leitura's interval, back on the grid, with Desfazer. */
    const val AS_RANGE = "insights.asRange"

    /** On O que cresceu, "Mês a mês de X": opens the Mês a mês page with only that chip. */
    const val MONTHS_OF = "insights.monthsOf"

    /** The Ritmo's sentence and chart (the ritmo ideal, cota and "no passo atual" only with a whole month, R17). */
    const val PACE_SENTENCE = "insights.pace.sentence"
    const val PACE_CHART = "insights.pace.chart"
}

/** The grid (R7, §5): each context's id, name and question (R8), then its widgets' ids and titles, in order. */
private val CONTEXTS = listOf(
    Triple("quanto", "Quanto", "Cabe no que planejei?") to listOf(
        "margem" to "Margem", "total" to "Total e costume", "ritmo" to "Ritmo acumulado", "previstos" to "Previstos do mês",
        "historia" to "História", "fora" to "O que ficou fora",
    ),
    Triple("para-onde", "Para onde", "Verba, marcador, estabelecimento.") to listOf(
        "retrato" to "Retrato do estabelecimento", "fluxo" to "Fluxo verba → marcador", "para-onde-foi" to "Para onde foi",
        "cresceu" to "O que cresceu", "voltam" to "Estabelecimentos que voltam", "primeira-vez" to "Primeira vez", "tamanho" to "Tamanho",
    ),
    Triple("quando", "Quando", "Dias, semanas e meses.") to listOf(
        "calendario" to "Calendário", "dia-da-semana" to "Dia da semana", "semanas" to "Semanas", "mes-a-mes" to "Mês a mês",
        "meses-a-frente" to "Meses à frente", "volta-todo-mes" to "Volta todo mês", "dia-e-hora" to "Dia e hora",
    ),
)

/** Widgets of the whole width; the others are halves, in pairs (a half left alone takes the line, AC-36). */
private val WHOLE = setOf("ritmo", "fora", "retrato", "fluxo", "voltam", "calendario")

private fun titleOf(id: String) = CONTEXTS.flatMap { it.second }.firstOrNull { it.first == id }?.second ?: ""

/**
 * Leitura (spec §7.5 v2): the filter, then the grid in three contexts. [leitura] is the query of the whole Leitura,
 * kept by the App while the tab is open (D-5); [onLeitura] changes it.
 */
@Composable
fun Insights(db: Db, leitura: LeituraQuery, onLeitura: (LeituraQuery) -> Unit, go: (Screen) -> Unit) {
    val c = Margem.colors
    val todayOf = LocalToday.current
    // Today is read with the lines, so a day that turns with the screen open is seen at the next write (AC-F2).
    val data = query(db, leitura) { d -> load(d, leitura, todayOf()) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Saved, so the way back from a page lays the grid out at once, the filter's place kept while it reloads.
    var filterHeight by rememberSaveable { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val tops = remember { mutableStateMapOf<String, Int>() }
    // R10: past the filter's height it collapses into the lupa; the lupa reopens it where it is, and scrolling 12 dp closes it.
    val collapsed = filterHeight > 0 && scroll.value > filterHeight
    // A new query, or the way back from a widget's page, reopens the filter at the top (R10).
    LaunchedEffect(leitura) { scroll.scrollTo(0) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        val at = scroll.value
        snapshotFlow { scroll.value }.first { abs(it - at) > with(density) { 12.dp.toPx() } }
        open = false
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Leitura") {
            val q = data?.first
            if (collapsed && q != null) {
                Text(span(q.from, q.to), sans(13f, 500, 16f), color = c.inkTertiary)
                Box {
                    IconButton(R.drawable.i_lupa, { open = true }, Modifier.testTag(InsightsTags.LENS))
                    if (q.chips.isNotEmpty()) Text(
                        q.chips.size.toString(), sans(11f, 700, 16f, tabular = true),
                        Modifier.align(Alignment.TopEnd).offset((-4).dp, 6.dp).widthIn(min = 16.dp).background(c.brand, CircleShape).padding(horizontal = 4.dp),
                        c.surface,
                    )
                }
            }
        }
        if (data?.first?.from == NO_DATE) {
            EmptyState(
                R.drawable.i_leitura, "Nada para ler ainda.",
                "A Leitura mostra para onde o dinheiro vai: verbas mês a mês, o ritmo do mês, marcadores e estabelecimentos.",
                Modifier.padding(horizontal = Margem.space.page).testTag(InsightsTags.EMPTY).semantics(mergeDescendants = true) {},
            )
            return@Column
        }
        // Widgets that come and go (§5): the grid knows before laying out its pairs.
        val extra = data?.let { (q, lines) -> query(db, q, lines) { d -> firstTimers(d, q, lines) to leftOut(d, q) } }
        val portrait = leitura.chips.singleOrNull()?.kind == ChipKind.MERCHANT
        val gone = buildSet {
            if (portrait) addAll(listOf("voltam", "cresceu", "primeira-vez")) else add("retrato")
            if (extra?.first.isNullOrEmpty()) add("primeira-vez")
            if (extra?.second.isNullOrEmpty()) add("fora")
        }
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().testTag(InsightsTags.GRID).verticalScroll(scroll).padding(start = Margem.space.page, end = Margem.space.page, bottom = 32.dp)) {
                if (collapsed || data == null) Spacer(Modifier.height(with(density) { filterHeight.toDp() }))
                else Box(Modifier.onSizeChanged { filterHeight = it.height }) { Filter(db, leitura, data.first, data.second, data.third, onLeitura) }
                for ((context, widgets) in CONTEXTS) {
                    PageHeader(
                        "Contexto", context.second,
                        Modifier.padding(top = 32.dp).testTag(InsightsTags.CONTEXT + context.first).onPlaced { tops[context.first] = it.positionInParent().y.toInt() },
                        context.third,
                    )
                    val ids = widgets.map { it.first }.filter { it !in gone }
                    val rows = mutableListOf<List<String>>()
                    for (id in ids) {
                        val last = rows.lastOrNull()
                        if (last != null && last.size == 1 && last[0] !in WHOLE && id !in WHOLE) rows[rows.lastIndex] = last + id else rows += listOf(id)
                    }
                    for (row in rows) Row(Modifier.fillMaxWidth().padding(top = 20.dp).height(IntrinsicSize.Min)) {
                        for (id in row) Frame(id, Modifier.weight(1f), { go(Screen.LeituraWidget(id, leitura)) }) {
                            if (data != null) Widget(db, id, data.first, data.second, data.third, false, {}, go, extra)
                        }
                    }
                }
            }
            // R8: the context whose title went off the top stays pinned until the next one's does.
            val pinned = CONTEXTS.lastOrNull { (tops[it.first.first] ?: Int.MAX_VALUE) < scroll.value }?.first
            if (pinned != null) Box(
                Modifier.fillMaxWidth().height(44.dp).background(c.paper).drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .testTag(InsightsTags.PINNED).padding(horizontal = Margem.space.page),
                contentAlignment = Alignment.CenterStart,
            ) { Text(pinned.second, serif(19, 500, 24)) }
            if (open && collapsed && data != null) {
                Box(Modifier.fillMaxSize().background(c.ink.copy(alpha = 0.25f)).clickable { open = false })
                Column(Modifier.fillMaxWidth().background(c.paper).padding(start = Margem.space.page, end = Margem.space.page, bottom = 12.dp)) {
                    Filter(db, leitura, data.first, data.second, data.third, onLeitura)
                }
            }
        }
    }
}

/**
 * A widget's whole page (R12): pushed on the stack, opened on [start]. Its filter changes only the page; the piece
 * touched offers Só, + ou and Usar como intervalo, which change the whole Leitura through [leituraTo] (R14).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeituraWidgetPage(db: Db, id: String, start: LeituraQuery, go: (Screen) -> Unit, back: () -> Unit, leituraTo: (LeituraQuery, String) -> Unit) {
    val todayOf = LocalToday.current
    var raw by rememberSaveable(stateSaver = QuerySaver) { mutableStateOf(start) }
    // The piece touched: one chip, or a from..to of days.
    var piece by rememberSaveable(stateSaver = QuerySaver) { mutableStateOf(LeituraQuery()) }
    val data = query(db, raw) { d -> load(d, raw, todayOf()) }
    Column(Modifier.fillMaxSize()) {
        TopBar(titleOf(id), onBack = back)
        val (q, lines, today) = data ?: return@Column
        if (q.from == NO_DATE) return@Column
        Column(Modifier.weight(1f).testTag(InsightsTags.PAGE + id).verticalScroll(rememberScrollState()).padding(start = Margem.space.page, end = Margem.space.page, bottom = 32.dp)) {
            Filter(db, raw, q, lines, today) { raw = it }
            Gap()
            Widget(db, id, q, lines, today, true, { piece = it }, go, null)
            val chip = piece.chips.firstOrNull()
            if (chip != null || piece.from != NO_DATE) SectionLine(chip?.label ?: span(piece.from, piece.to), aside = "o pedaço tocado")
            if (chip != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Só ${chip.label}", { leituraTo(raw.copy(chips = listOf(chip)), "Leitura só com ${chip.label}.") }, Modifier.testTag(InsightsTags.ONLY), ButtonKind.SECONDARY, small = true)
                Button("+ ou ${chip.label}", { leituraTo(raw.copy(chips = (raw.chips + chip).distinct()), "${chip.label} somado à Leitura.") }, Modifier.testTag(InsightsTags.OR), ButtonKind.SECONDARY, small = true)
                if (id == "cresceu") Button(
                    "Mês a mês de ${chip.label}", { go(Screen.LeituraWidget("mes-a-mes", raw.copy(chips = listOf(chip)))) },
                    Modifier.testTag(InsightsTags.MONTHS_OF), ButtonKind.SECONDARY, small = true,
                )
            }
            if (piece.from != NO_DATE) Button(
                "Usar como intervalo", { leituraTo(raw.copy(from = piece.from, to = piece.to), "Intervalo: ${span(piece.from, piece.to)}.") },
                Modifier.testTag(InsightsTags.AS_RANGE), ButtonKind.SECONDARY, small = true,
            )
            if (id !in setOf("historia", "fora", "retrato")) Lines(lines)
        }
    }
}

// ---- Reads ----

/** [raw] with its interval resolved (NO_DATE: today's budget month), its lines and today; from NO_DATE: no budgets yet. */
private suspend fun load(d: Db, raw: LeituraQuery, today: LocalDate): Triple<LeituraQuery, List<LeituraRow>, LocalDate> {
    val current = d.monthOf(today)
    d.ensureMonths(current)
    if (d.history(current, current).isEmpty()) return Triple(LeituraQuery(), emptyList(), today)
    val w = d.window(current)
    val q = if (raw.from == NO_DATE) raw.copy(from = w.start, to = w.endInclusive) else raw
    return Triple(q, d.leituraLines(q), today)
}

/**
 * The interval [n] steps before [q] (R18, AC-23): whole budget months go back as many months, cut at the same day
 * when [q] holds today; any other interval goes back its own length in days.
 */
private suspend fun earlier(d: Db, q: LeituraQuery, today: LocalDate, n: Int): LeituraQuery {
    val first = d.monthOf(q.from)
    val last = d.monthOf(q.to)
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

/**
 * The verbas' month behind the ritmo ideal, cota and projection (R17): the budgets of [q]'s chips (all without chips)
 * as one, when [q] is one whole budget month and every chip is a verba; zero otherwise.
 */
private suspend fun plan(d: Db, q: LeituraQuery, today: LocalDate): BudgetMonth {
    val m = d.monthOf(q.from)
    val w = d.window(m)
    if (w.start != q.from || w.endInclusive != q.to || q.chips.any { it.kind != ChipKind.BUDGET }) return BudgetMonth()
    d.ensureMonths(m)
    val keys = q.chips.map { it.key }
    return sum(d.budgetsOverview(m, today, BudgetOrder.MANUAL).filter { keys.isEmpty() || it.id.v.toString() in keys }.map { it.month })
}

/** Budgets of one month as one (the régua of the total, the Ritmo of several verbas): sums, idealByDay and weeks by index. */
private fun sum(months: List<BudgetMonth>): BudgetMonth = months.fold(BudgetMonth()) { a, m ->
    val remaining = a.remaining + m.remaining
    val variable = a.variable + m.variable
    val ideal = a.ideal + m.ideal
    BudgetMonth(
        m.month, m.start, m.end, a.limit + m.limit, a.spent + m.spent, remaining,
        if (a.weeks.isEmpty()) m.weeks else a.weeks.zip(m.weeks) { x, y -> x.copy(step = x.step + y.step, spent = x.spent + y.spent) },
        ideal, if (remaining.v < 0) PaceStatus.OVERRUN else if (variable > ideal) PaceStatus.AHEAD else PaceStatus.ON_PACE,
        a.projection + m.projection, if (a.idealByDay.isEmpty()) m.idealByDay else a.idealByDay.zip(m.idealByDay) { x, y -> x + y },
        a.open + m.open, a.fixed + m.fixed, a.freeLimit + m.freeLimit, variable,
    )
}

/** What the interval's counts leave out (R20, AC-25): estornos, recados riscados and recados still in the Pauta. */
private suspend fun leftOut(d: Db, q: LeituraQuery): List<String> {
    val refunded = d.transactions().filter { it.refunded && it.date in q.from..q.to }
    val dismissed = d.dismissed().filter { it.parsed.date in q.from..q.to }
    val pending = d.pending().filter { it.parsed.date in q.from..q.to }
    return listOfNotNull(
        if (refunded.isEmpty()) null else "${count(refunded.size, "estorno", "estornos")}: ${money(Cents(refunded.sumOf { it.total.v }))} estornados.",
        if (dismissed.isEmpty()) null else "${count(dismissed.size, "recado riscado", "recados riscados")}: ${money(Cents(dismissed.sumOf { it.parsed.cents.v }))}.",
        if (pending.isEmpty()) null else "${count(pending.size, "recado", "recados")} na Pauta: ${money(Cents(pending.sumOf { it.parsed.cents.v }))}.",
    )
}

/** Estabelecimentos and marcadores of [lines] whose first date in the whole history falls in [q]'s interval (AC-36). */
private suspend fun firstTimers(d: Db, q: LeituraQuery, lines: List<LeituraRow>): List<Chip> {
    val seen = (groups(lines, ChipKind.MERCHANT) + groups(lines, ChipKind.TAG)).map { it.first }
    if (seen.isEmpty()) return seen
    val old = d.chipsSeen(q.from.minus(1, DateTimeUnit.DAY), seen).toSet()
    return seen.filter { it !in old }
}

/** The chips a line matches in [kind] (key, label): one budget, each of its tags, its estabelecimento, its fonte. */
private fun keysOf(r: LeituraRow, kind: ChipKind): List<Pair<String, String>> = when (kind) {
    ChipKind.BUDGET -> listOf(r.budget.v.toString() to r.budgetName)
    ChipKind.TAG -> r.tags.map { it to it }
    ChipKind.MERCHANT -> listOf(r.merchantKey to r.merchant)
    ChipKind.NOTICE -> if (r.source.isEmpty()) emptyList() else listOf(r.source to r.sourceName)
}

private fun hits(r: LeituraRow, chip: Chip) = keysOf(r, chip.kind).any { it.first == chip.key }

/** [lines] by chip of [kind], largest sum first; a line with two tags is in both, whole (regra C, R16). */
private fun groups(lines: List<LeituraRow>, kind: ChipKind): List<Pair<Chip, List<LeituraRow>>> =
    lines.flatMap { r -> keysOf(r, kind).map { (k, label) -> Chip(kind, k, label) to r } }
        .groupBy { it.first.key }.values.map { g -> g[0].first to g.map { it.second } }
        .sortedByDescending { (_, rs) -> rs.sumOf { it.cents.v } }

private fun total(lines: List<LeituraRow>) = Cents(lines.sumOf { it.cents.v })

private fun transactions(lines: List<LeituraRow>) = lines.distinctBy { it.transaction }.size

private fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

private fun median(v: List<Long>) = v.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }

/** "2 nov", "1 a 30 nov", "1 out a 30 nov". */
private fun span(a: LocalDate, b: LocalDate) = when {
    a == b -> "${a.day} ${monthShort(a.month)}"
    a.yearMonth == b.yearMonth -> "${a.day} a ${b.day} ${monthShort(b.month)}"
    else -> "${a.day} ${monthShort(a.month)} a ${b.day} ${monthShort(b.month)}"
}

/** "R$ 796": whole reais, rounded half up. */
private fun reais(c: Cents) = "R$$NNBSP${moneyParts(Cents((c.v + 50) / 100 * 100)).first}"

// ---- The filter (R1–R5) ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filter(db: Db, raw: LeituraQuery, q: LeituraQuery, lines: List<LeituraRow>, today: LocalDate, onQuery: (LeituraQuery) -> Unit) {
    val c = Margem.colors
    var text by rememberSaveable { mutableStateOf("") }
    var ranging by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            Modifier.heightIn(min = 48.dp).testTag(InsightsTags.RANGE).clickable { ranging = true }.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(R.drawable.i_periodo, tint = c.inkSecondary, size = 20.dp)
            Text(span(q.from, q.to), serif(17, 500, 22))
        }
        SearchLine(text, { text = it }, "Buscar verba, marcador, estabelecimento ou recado", InsightsTags.SEARCH)
        if (text.isNotBlank()) Suggestions(db, q, text) {
            text = ""
            onQuery(raw.copy(chips = (raw.chips + it).distinct()))
        }
        if (raw.chips.isNotEmpty()) FlowRow(
            Modifier.padding(top = 6.dp).testTag(InsightsTags.CHIPS), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center,
        ) {
            for ((i, chip) in raw.chips.withIndex()) {
                if (i > 0) Text("ou", serif(15, 400, 20, italic = true), Modifier.align(Alignment.CenterVertically), c.inkTertiary)
                TagChip(chip.label, removable = true) { onQuery(raw.copy(chips = raw.chips - chip, separate = raw.separate && raw.chips.size > 2)) }
            }
        }
        val n = transactions(lines)
        Text(
            "${money(total(lines))} · ${count(n, "lançamento", "lançamentos")}" + if (today in q.from..q.to) " · até hoje" else "",
            sans(14f, 500, 20f, tabular = true), Modifier.padding(top = 8.dp).testTag(InsightsTags.SUMMARY), c.inkSecondary,
        )
        if (raw.chips.size >= 2) Row(
            Modifier.heightIn(min = 48.dp).testTag(InsightsTags.SEPARATE).clickable { onQuery(raw.copy(separate = !raw.separate)) }.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Check(raw.separate)
            Text("Separar", sans(15f, 500, 20f))
        }
        if (ranging) RangeSheet(db, today, { a, b ->
            ranging = false
            onQuery(raw.copy(from = a, to = b))
        }) { ranging = false }
    }
}

/** R2: the suggestions for [text] in fixed sections, with count and sum in the interval (chips not applied). */
@Composable
private fun Suggestions(db: Db, q: LeituraQuery, text: String, pick: (Chip) -> Unit) {
    val c = Margem.colors
    val all = query(db, q.from, q.to) { d -> d.leituraLines(LeituraQuery(q.from, q.to)) } ?: return
    val word = fold(text.trim())
    val key = MerchantKey.of(text).v
    for ((kind, title) in listOf(ChipKind.BUDGET to "Verbas", ChipKind.TAG to "Marcadores", ChipKind.MERCHANT to "Estabelecimentos", ChipKind.NOTICE to "Recados")) {
        val found = groups(all, kind).filter { (chip, rs) ->
            if (kind == ChipKind.MERCHANT) MerchantKey.of(chip.label).v.contains(key) || rs.any { MerchantKey.of(it.bank).v.contains(key) }
            else fold(chip.label).contains(word)
        }.take(5)
        if (found.isNotEmpty()) Column(Modifier.fillMaxWidth().testTag(InsightsTags.SECTION + kind.name)) {
            Text(title.uppercase(), sans(11f, 600, 14f, 0.12f), Modifier.padding(top = 10.dp), c.inkTertiary)
            for ((chip, rs) in found) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pick(chip) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(chip.label, sans(16f, 500, 20f), Modifier.weight(1f), maxLines = 1)
                Text(count(transactions(rs), "lançamento", "lançamentos"), sans(13f, 400, 16f), color = c.inkTertiary)
                Text(moneyPlain(total(rs)), sans(14f, 600, 18f, tabular = true))
            }
        }
    }
}

/** R1: the interval's one sheet — the shortcuts by budget month, then a calendar: the first touch the start, the second the end. */
@Composable
private fun RangeSheet(db: Db, today: LocalDate, onPick: (LocalDate, LocalDate) -> Unit, close: () -> Unit) {
    val c = Margem.colors
    val shortcuts = query(db, today) { d ->
        val cur = d.monthOf(today)
        listOf("este mês" to 0, "mês passado" to 1, "3 meses" to 2, "6 meses" to 5).map { (label, back) ->
            Triple(label, d.window(cur.minus(back, DateTimeUnit.MONTH)).start, d.window(if (back == 1) cur.minusMonth() else cur).endInclusive)
        }
    }
    var month by remember { mutableStateOf(today.yearMonth) }
    var first by remember { mutableStateOf(NO_DATE) }
    BottomSheet(close) {
        Text("INTERVALO", sans(11f, 600, 14f, 0.12f), color = c.inkTertiary)
        for ((label, a, b) in shortcuts.orEmpty()) ListRow(label, sub = span(a, b), onClick = { onPick(a, b) })
        Text(
            if (first == NO_DATE) "Personalizado: toque no início e no fim." else "Do dia ${first.day} ${monthShort(first.month)} até…",
            sans(14f, 400, 20f), Modifier.padding(top = 12.dp), c.inkSecondary,
        )
        MonthPicker(month, "", { month = month.minusMonth() }, { month = month.plusMonth() })
        val lead = month.onDay(1).dayOfWeek.ordinal
        val cells = List(lead) { NO_DATE } + (1..month.numberOfDays).map { month.onDay(it) }
        for (week in cells.chunked(7)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until 7) {
                val day = week.getOrElse(i) { NO_DATE }
                if (day == NO_DATE) Box(Modifier.weight(1f)) else Box(
                    Modifier.weight(1f).aspectRatio(1f).background(if (day == first) c.ink else c.surfaceSunken)
                        .clickable { if (first == NO_DATE) first = day else onPick(minOf(first, day), maxOf(first, day)) },
                    contentAlignment = Alignment.Center,
                ) { Text(day.day.toString(), sans(13f, 500, 16f, tabular = true), color = if (day == first) c.paper else c.ink) }
            }
        }
    }
}

// ---- The grid's pieces ----

/** R9: a widget hangs from a 2 dp vertical rule, no card; the Fluxo from the argila double rule. Pressed, the rule turns ink. */
@Composable
private fun Frame(id: String, modifier: Modifier, onOpen: () -> Unit, content: @Composable () -> Unit) {
    val c = Margem.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier.fillMaxHeight().testTag(InsightsTags.WIDGET + id).clickable(interaction, null, onClick = onOpen)
            .background(if (pressed) c.surfaceSunken else Color.Transparent)
            .drawBehind {
                if (id == "fluxo") for (x in listOf(0.5f, 3.5f)) drawLine(c.brand, Offset(x.dp.toPx(), 0f), Offset(x.dp.toPx(), size.height), 1.dp.toPx())
                else drawLine(if (pressed) c.ink else c.rule, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx())
            }
            .padding(start = 14.dp, end = 8.dp, bottom = 4.dp),
    ) {
        Text(titleOf(id).uppercase(), sans(11f, 600, 14f, 0.12f), color = c.inkTertiary)
        content()
    }
}

/**
 * One widget, small on the grid or [big] on its page with its options; a touched piece goes to [onPiece]. On the grid,
 * [extra] holds the grid's own firstTimers and leftOut, so fora, voltam and primeira-vez do not read them again.
 */
@Composable
private fun Widget(db: Db, id: String, q: LeituraQuery, lines: List<LeituraRow>, today: LocalDate, big: Boolean, onPiece: (LeituraQuery) -> Unit, go: (Screen) -> Unit, extra: Pair<List<Chip>, List<String>>?) {
    val chip: (Chip) -> Unit = { onPiece(LeituraQuery(chips = listOf(it))) }
    val days: (LocalDate, LocalDate) -> Unit = { a, b -> onPiece(LeituraQuery(a, b)) }
    when (id) {
        "margem" -> MargemWidget(db, q, lines, today, big)
        "total" -> TotalWidget(db, q, lines, today, big)
        "ritmo" -> RitmoWidget(db, q, lines, today, big)
        "previstos" -> PlannedWidget(db, q, today, big)
        "historia" -> HistoryWidget(q, lines, big, go)
        "fora" -> LeftOutWidget(db, q, big, extra)
        "retrato" -> PortraitWidget(lines)
        "fluxo" -> FlowWidget(lines, big, chip)
        "para-onde-foi" -> WhereWidget(db, q, lines, big, chip)
        "cresceu" -> GrewWidget(db, q, lines, today, big, chip)
        "voltam" -> ReturnWidget(db, q, lines, big, chip, extra)
        "primeira-vez" -> FirstWidget(db, q, lines, big, chip, extra)
        "tamanho" -> SizeWidget(lines, big)
        "calendario" -> CalendarWidget(q, lines, big, days)
        "dia-da-semana" -> WeekdayWidget(db, q, lines, today, big)
        "semanas" -> WeeksWidget(db, q, lines, today, big, days)
        "mes-a-mes" -> MonthsWidget(db, q, big, days)
        "meses-a-frente" -> AheadWidget(db, q, today, big)
        "volta-todo-mes" -> MonthlyWidget(db, q, today, big)
        "dia-e-hora" -> HourWidget(q, lines, big)
    }
}

@Composable
private fun Sentence(text: String, modifier: Modifier = Modifier) =
    Text(text, serif(15, 400, 21, italic = true), modifier.padding(top = 2.dp), Margem.colors.inkSecondary)

@Composable
private fun Note(text: String) = Text(text, sans(13f, 400, 18f), Modifier.padding(top = 8.dp), Margem.colors.inkTertiary)

@Composable
private fun Gap() = Spacer(Modifier.height(12.dp))

/** R5: with Separar, one series per chip with the chip's color (not its place), "a + b" for a line that matches both. */
@Composable
private fun Series(q: LeituraQuery, lines: List<LeituraRow>) {
    if (!q.separate || q.chips.size < 2) return
    val palette = Margem.colors.series
    val series = lines.groupBy { r -> q.chips.filter { hits(r, it) } }.map { (chips, rs) -> chips to total(rs) }.sortedByDescending { it.second.v }
    for ((chips, cents) in series) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SeriesMark(if (chips.size == 1) palette[(chips[0].kind.ordinal * 31 + chips[0].key.hashCode()).mod(6)] else palette[6])
        Text(chips.joinToString(" + ") { it.label }, sans(13f, 500, 17f), Modifier.weight(1f))
        Text(moneyPlain(cents), sans(13f, 600, 17f, tabular = true))
    }
}

/**
 * Columns with straight ends (R11), labels under: each bar's parts stack in [colors]' order; [mark] a dashed rule (the
 * passo semanal); a click on a column goes to [onBar].
 */
@Composable
private fun Columns(bars: List<Pair<String, List<Long>>>, colors: List<Color>, onBar: ((Int) -> Unit)? = null, mark: Long = 0) {
    val c = Margem.colors
    val measurer = rememberTextMeasurer()
    val label = Margem.type.axis.copy(color = c.inkTertiary)
    val top = maxOf(bars.maxOfOrNull { it.second.sum() } ?: 0L, mark, 1L)
    Box(Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val bottom = size.height - 16.dp.toPx()
            val slot = size.width / maxOf(bars.size, 1)
            val y = { v: Long -> bottom - (bottom - 4.dp.toPx()) * v / top }
            for ((i, bar) in bars.withIndex()) {
                var base = 0L
                for ((p, v) in bar.second.withIndex()) {
                    drawRect(colors[p % colors.size], Offset(slot * i + slot * 0.18f, y(base + v)), Size(slot * 0.64f, y(base) - y(base + v)))
                    base += v
                }
                val t = measurer.measure(bar.first, label)
                if (t.size.width < slot || i % 2 == 0) drawText(t, topLeft = Offset(slot * i + slot / 2 - t.size.width / 2, bottom + 3.dp.toPx()))
            }
            if (mark > 0) drawLine(c.brand, Offset(0f, y(mark)), Offset(size.width, y(mark)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            drawLine(label.color.copy(alpha = 0.9f), Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        }
        if (onBar != null) Row(Modifier.fillMaxSize()) { for (i in bars.indices) Box(Modifier.weight(1f).fillMaxHeight().clickable { onBar(i) }) }
    }
}

/** Ranking rows (book Fig. 9.4): name, a note, the value, a thin bar from the largest (both ways when some are negative). */
@Composable
private fun Ranks(rows: List<Triple<Chip, Long, String>>, onRow: ((Chip) -> Unit)?, note: (Chip) -> String = { "" }) {
    val c = Margem.colors
    val top = maxOf(rows.maxOfOrNull { abs(it.second) } ?: 0L, 1L)
    val twoWays = rows.any { it.second < 0 }
    for ((chip, value, text) in rows) Column(
        Modifier.fillMaxWidth().then(if (onRow == null) Modifier else Modifier.heightIn(min = 48.dp).clickable { onRow(chip) }).padding(top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(chip.label, serif(16, 500, 21), Modifier.weight(1f, fill = false), maxLines = 1)
            Text(note(chip), sans(12f, 500, 16f), Modifier.weight(1f).padding(horizontal = 8.dp), c.inkTertiary, maxLines = 1)
            Text(text, sans(14f, 600, 18f, tabular = true))
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 3.dp)) {
            val w = (if (twoWays) size.width / 2 else size.width) * abs(value) / top
            val x = if (!twoWays) 0f else if (value < 0) size.width / 2 - w else size.width / 2
            drawRect(if (value < 0) c.onPace else c.inkTertiary, Offset(x, 0f), Size(w, size.height))
        }
    }
}

/** R13: the page's list — Maiores, numbered with each one's share, or Recentes, by day with the day's total. */
@Composable
private fun Lines(lines: List<LeituraRow>) {
    val c = Margem.colors
    var recent by rememberSaveable { mutableStateOf(false) }
    SectionLine("Lançamentos", aside = count(lines.size, "parcela", "parcelas").takeIf { lines.any { it.k > 1 } } ?: "")
    OptionPicker(listOf(false to "Maiores", true to "Recentes"), recent, { recent = it }, tag = "insights.list.recent.")
    val sum = maxOf(total(lines).v, 1L)
    val shown = if (recent) lines.sortedWith(compareByDescending<LeituraRow> { it.date }.thenByDescending { it.time }) else lines.sortedByDescending { it.cents.v }
    for ((i, r) in shown.withIndex()) {
        if (recent && (i == 0 || shown[i - 1].date != r.date)) DayHeader("${dateShort(r.date)} · ${money(total(shown.filter { it.date == r.date }))}")
        Row(
            Modifier.fillMaxWidth().testTag(InsightsTags.LIST_ROW + i).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!recent) Text("${i + 1}", sans(13f, 600, 17f, tabular = true), Modifier.widthIn(min = 20.dp), c.inkTertiary)
            ListRow(
                r.merchant, Modifier.weight(1f), (listOf(r.budgetName, r.tags.joinToString(", ")) + if (r.k > 1) listOf("parcela ${r.k}") else emptyList())
                    .filter(String::isNotEmpty).joinToString(" · "),
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(moneyPlain(r.cents), sans(15f, 600, 20f, tabular = true))
                    if (!recent) Text("${(200 * r.cents.v + sum) / (2 * sum)}%", sans(12f, 500, 16f), color = c.inkTertiary)
                }
            }
        }
    }
}

// ---- Quanto ----

/** Margem of the month the interval ends in (D-10): the régua of the total; on the page one per verba, ordered. */
@Composable
private fun MargemWidget(db: Db, q: LeituraQuery, lines: List<LeituraRow>, today: LocalDate, big: Boolean) {
    var order by rememberSaveable { mutableStateOf("risco") }
    val read = query(db, q.to, today) { d ->
        val m = d.monthOf(q.to)
        d.ensureMonths(m)
        m to d.budgetsOverview(m, today, BudgetOrder.RISK)
    } ?: return
    val keys = q.chips.filter { it.kind == ChipKind.BUDGET }.map { it.key }
    val budgets = read.second.filter { keys.isEmpty() || it.id.v.toString() in keys }
    val all = sum(budgets.map { it.month })
    val month = monthLong(read.first.month)
    Sentence(if (all.remaining.v >= 0) "Em $month, cabem ${money(all.remaining)}." else "Em $month, ${money(Cents(-all.remaining.v))} além da cota.")
    Meter(all, Modifier.padding(top = 10.dp))
    val places = q.chips.filter { it.kind == ChipKind.TAG || it.kind == ChipKind.MERCHANT }
    if (places.isNotEmpty()) Note("${places.joinToString(" ou ") { it.label }} cai em ${lines.map { it.budgetName }.distinct().joinToString(", ").ifEmpty { "nenhuma verba" }}.")
    if (!big) return
    OptionPicker(listOf("risco" to "risco", "margem" to "margem", "az" to "A–Z"), order, { order = it })
    val sorted = when (order) {
        "margem" -> budgets.sortedBy { it.month.remaining.v }
        "az" -> budgets.sortedBy { it.name.lowercase() }
        else -> budgets
    }
    for (b in sorted) Column(Modifier.padding(top = 12.dp)) {
        Row {
            Text(b.name, serif(16, 500, 21), Modifier.weight(1f))
            Text(if (b.month.remaining.v >= 0) money(b.month.remaining) else "${money(Cents(-b.month.remaining.v))} além", sans(14f, 600, 18f, tabular = true))
        }
        Meter(b.month, Modifier.padding(top = 4.dp))
    }
}

/** Total e costume (AC-28): the interval's total against the mean of the same stretch before; Somar tudo · variável · previstos. */
@Composable
private fun TotalWidget(db: Db, q: LeituraQuery, lines: List<LeituraRow>, today: LocalDate, big: Boolean) {
    var back by rememberSaveable { mutableIntStateOf(6) }
    var add by rememberSaveable { mutableStateOf("tudo") }
    val keep = { r: LeituraRow -> add == "tudo" || r.fixed == (add == "previstos") }
    val before = query(db, q, back, add) { d -> (1..back).map { total(d.leituraLines(earlier(d, q, today, it)).filter(keep)).v } } ?: return
    val now = total(lines.filter { keep(it) && (today !in q.from..q.to || it.date <= today) })
    val usual = Cents(before.sum() / back)
    Sentence(
        when {
            usual.v == 0L -> "${money(now)}; sem costume para comparar."
            now > usual -> "${money(now)}, ${money(now - usual)} acima do costume."
            now < usual -> "${money(now)}, ${money(usual - now)} abaixo do costume."
            else -> "${money(now)}, igual ao costume."
        },
    )
    val c = Margem.colors
    Columns(before.reversed().mapIndexed { i, v -> "−${before.size - i}" to listOf(v) } + ("agora" to listOf(now.v)), listOf(c.inkTertiary))
    if (!big) return
    OptionPicker(listOf(6 to "média de 6", 3 to "média de 3", 1 to "o anterior"), back, { back = it })
    OptionPicker(listOf("tudo" to "tudo", "variavel" to "só variável", "previstos" to "só previstos"), add, { add = it })
}

/**
 * Ritmo acumulado (AC-22, AC-29): with one whole month and no chip but verbas, the ritmo ideal, the cota and "no passo
 * atual"; otherwise against the interval before or the costume, with the ritmo option dimmed and why.
 */
@Composable
private fun RitmoWidget(db: Db, q: LeituraQuery, lines: List<LeituraRow>, today: LocalDate, big: Boolean) {
    var compare by rememberSaveable { mutableStateOf("") }
    val bm = query(db, q, today) { d -> plan(d, q, today) } ?: return
    val whole = bm.limit.v > 0
    val mode = if (compare == "" || (compare == "ritmo" && !whole)) (if (whole) "ritmo" else "anterior") else compare
    if (mode == "ritmo") Pace(bm, lines, today)
    else {
        val n = if (mode == "costume") 3 else 1
        val before = query(db, q, n, today) { d -> (1..n).map { earlier(d, q, today, it) }.map { it.from to d.leituraLines(it) } } ?: return
        Versus(q, lines, before, today, if (n == 1) "no intervalo anterior" else "de costume")
    }
    if (!big) return
    OptionPicker(
        listOf("ritmo" to "ritmo", "anterior" to "intervalo anterior", "costume" to "costume"), mode, { compare = it },
        disabled = if (whole) emptySet() else setOf("ritmo"),
    )
    if (!whole) Note("Ritmo ideal, cota e passo atual só com um mês inteiro e sem chip além de verbas: a cota é da verba.")
}

/** The interval's cumulative spending against [before] (each from its own first day; several: their mean). */
@Composable
private fun Versus(q: LeituraQuery, lines: List<LeituraRow>, before: List<Pair<LocalDate, List<LeituraRow>>>, today: LocalDate, against: String) {
    val c = Margem.colors
    val days = q.from.daysUntil(q.to) + 1
    val now = LongArray(days)
    for (r in lines) now[q.from.daysUntil(r.date)] += r.cents.v
    val was = LongArray(days)
    for ((from, rs) in before) for (r in rs) from.daysUntil(r.date).takeIf { it in 0 until days }?.let { was[it] += r.cents.v / before.size }
    for (i in 1 until days) {
        now[i] += now[i - 1]
        was[i] += was[i - 1]
    }
    val last = if (today in q.from..q.to) q.from.daysUntil(today) else days - 1
    Sentence("${money(Cents(now[last]))} neste intervalo, contra ${money(Cents(was[last]))} $against.", Modifier.testTag(InsightsTags.PACE_SENTENCE))
    val top = maxOf(now.max(), was.max(), 1L)
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp).testTag(InsightsTags.PACE_CHART)) {
        val bottom = size.height
        val x = { i: Int -> size.width * (i + 0.5f) / days }
        val y = { v: Long -> bottom - bottom * 0.94f * v / top }
        val path = { a: LongArray, upTo: Int -> Path().apply { for (i in 0..upTo) if (i == 0) moveTo(x(i), y(a[i])) else lineTo(x(i), y(a[i])) } }
        drawPath(path(was, days - 1), c.inkTertiary, style = Stroke(1.25f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        drawPath(path(now, last), c.inkSecondary, style = Stroke(2.dp.toPx()))
        drawLine(c.inkTertiary, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
    }
}

/** Ritmo do mês of [bm] (book Fig. 9.2): previstos band at the base, cota double rule, dashed ritmo ideal, dotted projection. */
@Composable
private fun Pace(bm: BudgetMonth, lines: List<LeituraRow>, today: LocalDate) {
    val c = Margem.colors
    val isCurrent = today in bm.start..bm.end
    // No projection without ideal (spec §5) or without spending: "fecharia em R$ 0" says nothing (spec §7.5).
    val projects = bm.ideal.v > 0 && bm.spent.v > 0
    // Margem honesta (D68): the ritmo is the gasto variável against the ideal of the cota livre; "além" and "dentro"
    // are the margem, open previstos reserved (M1).
    val sentence = if (isCurrent) {
        val first = when {
            bm.remaining.v < 0 -> "${money(Cents(-bm.remaining.v))} além da cota."
            bm.variable > bm.ideal -> "+ ${money(bm.variable - bm.ideal)} sobre o ritmo ideal."
            bm.variable < bm.ideal -> "${money(bm.ideal - bm.variable)} abaixo do ritmo ideal."
            else -> "No ritmo ideal."
        }
        if (projects) "$first No passo atual, o mês fecharia em ${reais(bm.projection)}." else first
    } else when {
        bm.remaining.v > 0 -> "Fechou em ${money(bm.spent)}, ${money(bm.remaining)} dentro da cota."
        bm.remaining.v < 0 -> "Fechou em ${money(bm.spent)}, ${money(Cents(-bm.remaining.v))} além da cota."
        else -> "Fechou em ${money(bm.spent)}, exatamente a cota."
    }
    Sentence(sentence, Modifier.testTag(InsightsTags.PACE_SENTENCE))
    val description = if (isCurrent) {
        "Ritmo do mês: ${money(bm.variable)} até o dia ${today.day}, contra ritmo ideal de ${money(bm.ideal)}" +
            (if (projects) "; no passo atual, ${reais(bm.projection)} no fim do mês" else "") +
            ", para uma cota de ${money(bm.limit)}" + (if (bm.planned.v > 0) ", com ${money(bm.planned)} em previstos" else "")
    } else "Ritmo do mês: ${money(bm.spent)} no mês, para uma cota de ${money(bm.limit)}" +
        (if (bm.open.v > 0) ", com ${money(bm.open)} em previstos em aberto" else "")

    // Spec §7.5 (2026-11-04): the previstos do mês (lançados at their value, open at the previsto's) are a band at the
    // base; the gasto variável and the ritmo ideal of the cota livre stand on it, so the line meets the cota's rule
    // exactly when the margem reaches zero, and the projection (M6, previstos included) starts from the same base.
    val base = bm.planned.v
    val days = bm.start.daysUntil(bm.end) + 1
    val cumulative = LongArray(days)
    for (line in lines) if (!line.fixed) {
        val i = bm.start.daysUntil(line.date)
        if (i in 0 until days) cumulative[i] += line.cents.v
    }
    cumulative[0] += base
    for (i in 1 until days) cumulative[i] += cumulative[i - 1]
    val last = if (isCurrent) bm.start.daysUntil(today).coerceIn(-1, days - 1) else days - 1
    val showProjection = isCurrent && projects
    val top = maxOf(bm.limit.v, if (last >= 0) cumulative[last] else 0L, if (showProjection) bm.projection.v else 0L, 1L)
    val axis = axisStep(top)
    val measurer = rememberTextMeasurer()
    val label = Margem.type.axis.copy(color = c.inkTertiary)
    Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp).testTag(InsightsTags.PACE_CHART).semantics { contentDescription = description }) {
        val left = 40.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val ceil = axis * ((top + axis - 1) / axis).coerceAtLeast(1)
        val w = size.width - left
        val x = { i: Int -> left + w * (i + 0.5f) / days }
        val y = { v: Long -> bottom - bottom * 0.94f * v / ceil }
        grid(measurer, label, left, bottom, axis, ceil, y)
        if (isCurrent && last < days - 1) {
            val from = x(last.coerceAtLeast(0)) + w / days / 2
            drawRect(c.surfaceSunken.copy(alpha = 0.6f), Offset(from, 0f), Size(size.width - from, bottom))
        }
        // Previstos at the base, as the régua (M11): lançados in ink 3, open ones in the dashed outline.
        if (bm.fixed.v > 0) drawRect(c.inkTertiary.copy(alpha = 0.3f), Offset(left, y(bm.fixed.v)), Size(w, bottom - y(bm.fixed.v)))
        if (bm.open.v > 0) dashedBox(c.ruleStrong, Offset(left, y(base)), Size(w, y(bm.fixed.v) - y(base)))
        if (base > 0 && bottom - y(base) > 16.dp.toPx()) {
            val t = measurer.measure("previstos", label)
            drawText(t, topLeft = Offset(left + 4.dp.toPx(), bottom - t.size.height - 3.dp.toPx()))
        }
        // Cota: the argila double rule.
        for (dy in listOf(-1.5f, 1.5f)) drawLine(c.brand, Offset(left, y(bm.limit.v) + dy.dp.toPx()), Offset(size.width, y(bm.limit.v) + dy.dp.toPx()), 0.75f.dp.toPx())
        // Ritmo ideal of the cota livre, dashed, the whole month, on the previstos.
        val ideal = Path()
        bm.idealByDay.forEachIndexed { i, v -> if (i == 0) ideal.moveTo(x(i), y(base + v.v)) else ideal.lineTo(x(i), y(base + v.v)) }
        drawPath(ideal, c.inkTertiary, style = Stroke(1.25f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        if (showProjection && last >= 0) drawLine(
            c.ahead, Offset(x(last), y(cumulative[last])), Offset(x(days - 1), y(bm.projection.v)), 1.5f.dp.toPx(),
            cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.5f.dp.toPx())),
        )
        if (last >= 0) {
            val real = Path()
            for (i in 0..last) if (i == 0) real.moveTo(x(i), y(cumulative[i])) else real.lineTo(x(i), y(cumulative[i]))
            drawPath(real, c.inkSecondary, style = Stroke(2.dp.toPx()))
            drawCircle(c.surface, 5.dp.toPx(), Offset(x(last), y(cumulative[last])))
            drawCircle(c.ink, 3.5f.dp.toPx(), Offset(x(last), y(cumulative[last])))
        }
        // Days: the first, each week start and the last.
        val marks = buildList {
            add(0)
            bm.weeks.drop(1).forEach { add(bm.start.daysUntil(it.start)) }
            if (days - 1 !in this) add(days - 1)
        }
        for (i in marks) {
            val t = measurer.measure(bm.start.plus(i, DateTimeUnit.DAY).day.toString(), label)
            drawText(t, topLeft = Offset(x(i) - t.size.width / 2, bottom + 4.dp.toPx()))
        }
    }
}

/** Gridline step: 1, 2 or 5 × 10ⁿ reais, at most 4 lines up to [top]. */
private fun axisStep(top: Long): Long {
    var unit = 100L
    while (true) {
        for (m in longArrayOf(1, 2, 5)) if (top / (unit * m) < 4) return unit * m
        unit *= 10
    }
}

/** Book 9.3: horizontal grid in fio, ink baseline, labels in ink 3, "R$" only on the top one. */
private fun DrawScope.grid(measurer: TextMeasurer, label: TextStyle, left: Float, bottom: Float, step: Long, ceil: Long, y: (Long) -> Float) {
    val lines = generateSequence(step) { it + step }.takeWhile { it <= ceil }.toList()
    for ((i, g) in lines.withIndex()) {
        drawLine(label.color.copy(alpha = 0.25f), Offset(left, y(g)), Offset(size.width, y(g)), 1.dp.toPx())
        val text = (if (i == lines.lastIndex) "R$$NNBSP" else "") + moneyParts(Cents(g)).first
        val t = measurer.measure(text, label)
        drawText(t, topLeft = Offset(left - 5.dp.toPx() - t.size.width, y(g) - t.size.height / 2))
    }
    drawLine(label.color.copy(alpha = 0.9f), Offset(left, bottom), Offset(size.width, bottom), 1.dp.toPx())
}

/** Previstos do mês the interval ends in (D-10, AC-30): Vieram, with the difference from the previsto, and A caminho. */
@Composable
private fun PlannedWidget(db: Db, q: LeituraQuery, today: LocalDate, big: Boolean) {
    val c = Margem.colors
    val read = query(db, q.to, today) { d ->
        val m = d.monthOf(q.to)
        d.ensureMonths(m)
        m to d.budgetsOverview(m, today, BudgetOrder.MANUAL).flatMap { d.budgetDetail(it.id, m, today).plannedLines }.sortedBy { it.planned.date }
    } ?: return
    // R19: a previsto has no estabelecimento nor fonte; it enters by its verba or its marcadores.
    val lines = read.second.filter { p ->
        q.chips.isEmpty() || q.chips.any { (it.kind == ChipKind.BUDGET && it.key == p.planned.budget.v.toString()) || (it.kind == ChipKind.TAG && it.key in p.planned.tags) }
    }
    val came = lines.filter { !it.open }
    val coming = lines.filter { it.open }
    val month = monthLong(read.first.month)
    Sentence(if (lines.isEmpty()) "Nenhum previsto em $month." else "Em $month, ${count(came.size, "veio", "vieram")} e ${coming.size} a caminho.")
    for ((title, group) in listOf("Vieram" to came, "A caminho" to coming)) {
        if (group.isEmpty() || (!big && title == "Vieram")) continue
        if (big) SectionLine(title)
        for (p in if (big) group else group.take(3)) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(p.planned.name, sans(14f, 500, 18f), maxLines = 1)
                Text(
                    when {
                        !p.open -> "lançado em ${p.launched.day} ${monthShort(p.launched.month)}" +
                            if (p.cents != p.planned.cents) ", ${money(Cents(abs((p.cents - p.planned.cents).v)))} ${if (p.cents > p.planned.cents) "acima" else "abaixo"} do previsto" else ""
                        p.planned.late -> "esperando desde ${p.planned.date.day} ${monthShort(p.planned.date.month)}"
                        else -> "dia ${p.planned.date.day}"
                    },
                    sans(12f, 400, 16f), color = c.inkTertiary,
                )
            }
            Text(moneyPlain(p.cents), sans(14f, 600, 18f, tabular = true), color = if (p.open) c.inkTertiary else c.ink)
        }
    }
}

/** História (AC-31): up to 3 sentences on the grid; on its page all of them, each opening its widget's page. */
@Composable
private fun HistoryWidget(q: LeituraQuery, lines: List<LeituraRow>, big: Boolean, go: (Screen) -> Unit) {
    val days = lines.groupBy { it.date }.mapValues { total(it.value) }
    val stories = buildList {
        if (lines.isNotEmpty()) add("total" to "${money(total(lines))} em ${count(transactions(lines), "lançamento", "lançamentos")}.")
        groups(lines, ChipKind.TAG).firstOrNull()?.let { add("para-onde-foi" to "${it.first.label} puxa a fila: ${money(total(it.second))}.") }
        days.maxByOrNull { it.value.v }?.let { add("calendario" to "O dia mais pesado foi ${dateShort(it.key)}: ${money(it.value)}.") }
        groups(lines, ChipKind.MERCHANT).maxByOrNull { transactions(it.second) }?.takeIf { transactions(it.second) > 1 }
            ?.let { add("voltam" to "${it.first.label} voltou ${transactions(it.second)} vezes.") }
    }
    if (stories.isEmpty()) Sentence("Nada lançado no intervalo.")
    for ((id, text) in if (big) stories else stories.take(3)) Sentence(
        text, if (big) Modifier.heightIn(min = 48.dp).clickable { go(Screen.LeituraWidget(id, q)) }.padding(vertical = 8.dp) else Modifier,
    )
}

/** O que ficou fora (AC-25): what no widget counts, when the interval has any. */
@Composable
private fun LeftOutWidget(db: Db, q: LeituraQuery, big: Boolean, extra: Pair<List<Chip>, List<String>>?) {
    val out = (if (big) query(db, q) { d -> leftOut(d, q) } else extra?.second) ?: return
    for (line in out) Sentence(line)
}

// ---- Para onde ----

/** Retrato do estabelecimento (AC-38), with one estabelecimento chip: its visits, total and typical value. */
@Composable
private fun PortraitWidget(lines: List<LeituraRow>) {
    val n = transactions(lines)
    Sentence("${lines.firstOrNull()?.merchant ?: "O estabelecimento"}: ${count(n, "ida", "idas")}, ${money(total(lines))}; típico ${money(Cents(median(lines.map { it.cents.v })))}.")
    lines.lastOrNull()?.let { Note("Última vez em ${dateShort(it.date)}, em ${it.budgetName}.") }
}

/**
 * Fluxo (AC-20, AC-21, AC-32): from verba or fonte to the set of marcadores ("padaria + café": a line goes whole to one
 * destination, so the Fluxo adds up to the total), the estabelecimento or both. A marcador touched lights its paths.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowWidget(lines: List<LeituraRow>, big: Boolean, onPiece: (Chip) -> Unit) {
    val c = Margem.colors
    var from by rememberSaveable { mutableStateOf("verba") }
    var to by rememberSaveable { mutableStateOf("marcador") }
    var lit by rememberSaveable { mutableStateOf("") }
    // A set of marcadores is named heaviest first, as Para onde foi orders them: "padaria + café".
    val rank = groups(lines, ChipKind.TAG).mapIndexed { i, g -> g.first.key to i }.toMap()
    val tagset = { r: LeituraRow -> r.tags.sortedBy { rank[it] }.joinToString(" + ").ifEmpty { "sem marcador" } }
    val flows = lines.groupBy { r ->
        (if (from == "fonte") r.sourceName.ifEmpty { "sem recado" } else r.budgetName) to when (to) {
            "estabelecimento" -> r.merchant
            "os dois" -> "${tagset(r)} · ${r.merchant}"
            else -> tagset(r)
        }
    }.map { it.key to it.value }.sortedByDescending { total(it.second).v }
    val top = flows.firstOrNull()
    Sentence(if (top == null) "Nada lançado no intervalo." else "${top.first.first} → ${top.first.second} puxa: ${money(total(top.second))}.")
    if (big) {
        OptionPicker(listOf("verba" to "verba", "fonte" to "fonte"), from, { from = it })
        OptionPicker(listOf("marcador" to "marcador", "estabelecimento" to "estabelecimento", "os dois" to "os dois"), to, { to = it })
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((chip, _) in groups(lines, ChipKind.TAG)) TagChip(chip.label) {
                lit = chip.key
                onPiece(chip)
            }
        }
    }
    for ((path, rs) in if (big) flows else flows.take(4)) {
        val on = lit.isEmpty() || rs.any { lit in it.tags }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(path.first, sans(13f, 500, 17f), Modifier.weight(0.4f), if (on) c.inkSecondary else c.inkTertiary, maxLines = 1)
            Text("→", sans(13f, 500, 17f), color = c.inkTertiary)
            Text(path.second, sans(13f, if (on && lit.isNotEmpty()) 700 else 500, 17f), Modifier.weight(0.6f), if (on) c.ink else c.inkTertiary)
            Text(moneyPlain(total(rs)), sans(13f, 600, 17f, tabular = true), color = if (on) c.ink else c.inkTertiary)
        }
    }
    // The ficha: the marcador's number of Para onde foi, divided by destination.
    val with = lines.filter { lit in it.tags }
    if (big && with.isNotEmpty()) Note(
        "$lit: ${money(total(with))} = " + with.groupBy(tagset).entries.joinToString(" + ") { "${it.key} ${money(total(it.value))}" },
    )
}

/**
 * Para onde foi (AC-20, AC-33): by verba, marcador or estabelecimento; soma, vezes or média. The chips of the kind grouped
 * don't filter it: their rows stay among the others, to swap (Só) or add (+ ou) one.
 */
@Composable
private fun WhereWidget(db: Db, q: LeituraQuery, lines: List<LeituraRow>, big: Boolean, onPiece: (Chip) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(ChipKind.BUDGET) }
    var metric by rememberSaveable { mutableStateOf("soma") }
    val source = if (q.chips.none { it.kind == kind }) lines else query(db, q, kind) { d -> d.leituraLines(q.copy(chips = q.chips.filter { it.kind != kind })) } ?: return
    val rows = groups(source, kind).map { (chip, rs) ->
        val n = transactions(rs)
        when (metric) {
            "vezes" -> Triple(chip, n.toLong(), count(n, "vez", "vezes"))
            "media" -> Triple(chip, total(rs).v / n, moneyPlain(Cents(total(rs).v / n)))
            else -> Triple(chip, total(rs).v, moneyPlain(total(rs)))
        }
    }.sortedByDescending { it.second }
    Sentence(rows.firstOrNull()?.let { "${it.first.label} puxa a fila." } ?: "Nada lançado no intervalo.")
    if (big) {
        OptionPicker(listOf(ChipKind.BUDGET to "verba", ChipKind.TAG to "marcador", ChipKind.MERCHANT to "estabelecimento"), kind, { kind = it })
        OptionPicker(listOf("soma" to "soma", "vezes" to "vezes", "media" to "média"), metric, { metric = it })
    }
    val chosen = q.chips.map { it.key }
    Ranks(if (big) rows else rows.take(4), if (big) onPiece else null) { if (it.key in chosen) "no filtro" else "" }
    if (kind == ChipKind.TAG && rows.isNotEmpty()) Note("Com vários marcadores, o lançamento conta inteiro em cada um: a soma pode passar do total.")
}

/** O que cresceu (AC-34): each marcador against the mean of 3 months or the interval before, both ways. */
@Composable
private fun GrewWidget(db: Db, q: LeituraQuery, lines: List<LeituraRow>, today: LocalDate, big: Boolean, onPiece: (Chip) -> Unit) {
    var against by rememberSaveable { mutableIntStateOf(3) }
    var often by rememberSaveable { mutableStateOf(false) }
    val before = query(db, q, against, today) { d -> (1..against).flatMap { d.leituraLines(earlier(d, q, today, it)) } } ?: return
    val now = groups(lines.filter { today !in q.from..q.to || it.date <= today }, ChipKind.TAG).associateBy { it.first.key }
    val was = groups(before, ChipKind.TAG).associateBy { it.first.key }
    val rows = (now.keys + was.keys).map { k ->
        val chip = (now[k] ?: was.getValue(k)).first
        chip to total(now[k]?.second.orEmpty()).v - total(was[k]?.second.orEmpty()).v / against
    }.filter { (chip, delta) -> delta != 0L && (!often || transactions(now[chip.key]?.second.orEmpty()) >= 3) }
        .sortedByDescending { abs(it.second) }
        .map { (chip, delta) -> Triple(chip, delta, (if (delta > 0) "+" else "−") + moneyPlain(Cents(abs(delta)))) }
    val top = rows.firstOrNull { it.second > 0 }
    Sentence(if (top == null) "Nada cresceu." else "${top.first.label} cresceu ${money(Cents(top.second))}.")
    if (big) {
        OptionPicker(listOf(3 to "média de 3 meses", 1 to "intervalo anterior"), against, { against = it })
        Row(Modifier.heightIn(min = 48.dp).clickable { often = !often }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Check(often)
            Text("só o que voltou 3 ou mais vezes", sans(14f, 400, 18f))
        }
    }
    Ranks(if (big) rows else rows.take(3), if (big) onPiece else null)
}

/** Estabelecimentos que voltam (AC-35): visits in the interval, typical value, "novo" when first seen in it. */
@Composable
private fun ReturnWidget(db: Db, q: LeituraQuery, lines: List<LeituraRow>, big: Boolean, onPiece: (Chip) -> Unit, extra: Pair<List<Chip>, List<String>>?) {
    var byValue by rememberSaveable { mutableStateOf(false) }
    val fresh = (if (big) query(db, q, lines) { d -> firstTimers(d, q, lines) } else extra?.first)?.map { it.key }?.toSet() ?: return
    val rows = groups(lines, ChipKind.MERCHANT).filter { transactions(it.second) > 1 }
        .map { (chip, rs) -> Triple(chip, if (byValue) total(rs).v else transactions(rs).toLong(), "${transactions(rs)} idas") to median(rs.map { it.cents.v }) }
        .sortedByDescending { it.first.second }
    Sentence(rows.firstOrNull()?.let { "${it.first.first.label} volta mais: ${it.first.third}." } ?: "Nenhum estabelecimento voltou no intervalo.")
    if (big) OptionPicker(listOf(false to "vezes", true to "valor"), byValue, { byValue = it })
    val typical = rows.associate { it.first.first.key to it.second }
    Ranks(rows.map { it.first }.let { if (big) it else it.take(4) }, if (big) onPiece else null) {
        "típico ${moneyPlain(Cents(typical.getValue(it.key)))}" + if (it.key in fresh) " · novo" else ""
    }
}

/** Primeira vez (AC-36): estabelecimentos and marcadores first seen in the interval. */
@Composable
private fun FirstWidget(db: Db, q: LeituraQuery, lines: List<LeituraRow>, big: Boolean, onPiece: (Chip) -> Unit, extra: Pair<List<Chip>, List<String>>?) {
    val fresh = (if (big) query(db, q, lines) { d -> firstTimers(d, q, lines) } else extra?.first) ?: return
    Sentence(if (fresh.isEmpty()) "Nada novo no intervalo." else "${count(fresh.size, "novidade", "novidades")}: ${fresh.take(3).joinToString(", ") { it.label }}.")
    if (big) Ranks(
        fresh.map { chip -> Triple(chip, total(lines.filter { hits(it, chip) }).v, moneyPlain(total(lines.filter { hits(it, chip) }))) }, onPiece,
    ) { if (it.kind == ChipKind.TAG) "marcador" else "estabelecimento" }
}

/** Tamanho (AC-37): how many lançamentos of each size, the median; on the page, the median by estabelecimento. */
@Composable
private fun SizeWidget(lines: List<LeituraRow>, big: Boolean) {
    val edges = listOf(1_000L, 2_500L, 5_000L, 10_000L, 25_000L)
    val names = listOf("até 10", "10–25", "25–50", "50–100", "100–250", "250+")
    val bins = LongArray(names.size)
    for (r in lines) bins[edges.count { r.cents.v > it }]++
    Sentence(if (lines.isEmpty()) "Nada lançado no intervalo." else "Metade fica até ${money(Cents(median(lines.map { it.cents.v })))}.")
    Columns(names.mapIndexed { i, n -> n to listOf(bins[i]) }, listOf(Margem.colors.inkTertiary))
    if (big) Ranks(groups(lines, ChipKind.MERCHANT).map { (chip, rs) -> median(rs.map { it.cents.v }).let { Triple(chip, it, moneyPlain(Cents(it))) } }.take(8), null) { "mediana" }
}

// ---- Quando ----

private val weekdayPlural = listOf("segundas", "terças", "quartas", "quintas", "sextas", "sábados", "domingos")

/** Calendário (AC-39): heat by day, soma or contagem, com previstos or only the variável; the heaviest days on the page. */
@Composable
private fun CalendarWidget(q: LeituraQuery, lines: List<LeituraRow>, big: Boolean, onDays: (LocalDate, LocalDate) -> Unit) {
    val c = Margem.colors
    var counting by rememberSaveable { mutableStateOf(false) }
    var planned by rememberSaveable { mutableStateOf(false) }
    // R19: the variável by default; "com previstos" adds the lançamentos of previstos.
    val kept = if (planned) lines else lines.filter { !it.fixed }
    val byDay = kept.groupBy { it.date }.mapValues { (_, rs) -> if (counting) transactions(rs).toLong() else total(rs).v }
    val heaviest = byDay.maxByOrNull { it.value }
    Sentence(heaviest?.let { "O dia mais pesado foi ${dateShort(it.key)}: ${if (counting) count(it.value.toInt(), "lançamento", "lançamentos") else money(Cents(it.value))}." } ?: "Nada lançado no intervalo.")
    if (big) {
        OptionPicker(listOf(false to "soma", true to "contagem"), counting, { counting = it })
        Row(Modifier.heightIn(min = 48.dp).clickable { planned = !planned }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Check(planned)
            Text("Com previstos", sans(14f, 400, 18f))
        }
    }
    val max = maxOf(heaviest?.value ?: 0L, 1L)
    val days = (0..q.from.daysUntil(q.to)).map { q.from.plus(it, DateTimeUnit.DAY) }
    val cells: List<LocalDate> = List(q.from.dayOfWeek.ordinal) { NO_DATE } + days
    for (week in cells.chunked(7)) Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until 7) {
            val day = week.getOrElse(i) { NO_DATE }
            if (day == NO_DATE) {
                Box(Modifier.weight(1f))
                continue
            }
            val v = byDay[day] ?: 0L
            val level = if (v == 0L) 0 else ((4 * v + max - 1) / max).toInt().coerceIn(1, 4)
            Box(
                Modifier.weight(1f).aspectRatio(if (big) 1f else 1.6f).background(c.heat[level])
                    .then(if (big) Modifier.clickable { onDays(day, day) } else Modifier).clearAndSetSemantics {
                    testTag = InsightsTags.DAY + day
                    contentDescription = "${day.day}: ${if (v == 0L) "sem gasto" else if (counting) count(v.toInt(), "lançamento", "lançamentos") else money(Cents(v))}"
                }.padding(2.dp),
            ) { if (big) Text(day.day.toString(), sans(10.5f, 600, 13f, tabular = true), color = if (level >= 3) c.paper else c.inkSecondary) }
        }
    }
    Series(q, lines)
    if (big) {
        SectionLine("Dias mais pesados")
        for ((day, v) in byDay.entries.sortedByDescending { it.value }.take(5)) ListRow(dateShort(day), onClick = { onDays(day, day) }) {
            Text(if (counting) v.toString() else moneyPlain(Cents(v)), sans(14f, 600, 18f, tabular = true))
        }
    }
}

/** Dia da semana (AC-40): the mean per weekday over the days so far of the interval; seg a dom or the heaviest first. */
@Composable
private fun WeekdayWidget(db: Db, q: LeituraQuery, lines: List<LeituraRow>, today: LocalDate, big: Boolean) {
    var heavyFirst by rememberSaveable { mutableStateOf(false) }
    val start = query(db) { d -> d.settings().weekStart } ?: return
    val sofar = (0..q.from.daysUntil(minOf(q.to, maxOf(today, q.from)))).map { q.from.plus(it, DateTimeUnit.DAY) }
    val order = (0 until 7).map { DayOfWeek.entries[(start.ordinal + it) % 7] }
    val means = order.map { wd ->
        val n = sofar.count { it.dayOfWeek == wd }
        wd to if (n == 0) 0L else total(lines.filter { it.date.dayOfWeek == wd && it.date in sofar }).v / n
    }
    val heavy = means.maxByOrNull { it.second }?.takeIf { it.second > 0 && means.count { m -> m.second == it.second } == 1 }
    Sentence(heavy?.let { "${if (it.first.ordinal <= 4) "As" else "Os"} ${weekdayPlural[it.first.ordinal]} pesam: ${money(Cents(it.second))} por dia." } ?: "Nenhum dia da semana pesa.")
    if (big) OptionPicker(listOf(false to "seg a dom", true to "maior primeiro"), heavyFirst, { heavyFirst = it })
    Columns((if (heavyFirst) means.sortedByDescending { it.second } else means).map { weekdayShort(it.first) to listOf(it.second) }, listOf(Margem.colors.inkSecondary))
    Series(q, lines)
}

/** Semanas (AC-41): a column per week from the Ajustes week start, with the passo semanal's mark when the month allows it. */
@Composable
private fun WeeksWidget(db: Db, q: LeituraQuery, lines: List<LeituraRow>, today: LocalDate, big: Boolean, onDays: (LocalDate, LocalDate) -> Unit) {
    val read = query(db, q, today) { d -> d.settings().weekStart to plan(d, q, today) } ?: return
    val (start, bm) = read
    val first = q.from.minus((q.from.dayOfWeek.ordinal - start.ordinal + 7) % 7, DateTimeUnit.DAY)
    val weeks = generateSequence(first) { it.plus(7, DateTimeUnit.DAY) }.takeWhile { it <= q.to }
        .map { maxOf(it, q.from) to minOf(it.plus(6, DateTimeUnit.DAY), q.to) }.toList()
    val sums = weeks.map { (a, b) -> total(lines.filter { it.date in a..b }).v }
    val heavy = sums.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }
    Sentence(heavy?.let { "A semana de ${span(weeks[it.index].first, weeks[it.index].second)} pesou mais: ${money(Cents(it.value))}." } ?: "Nada lançado no intervalo.")
    val mark = bm.weeks.maxOfOrNull { it.step.v } ?: 0L
    Columns(weeks.mapIndexed { i, (a, _) -> "${a.day}" to listOf(sums[i]) }, listOf(Margem.colors.inkSecondary), if (big) { i -> onDays(weeks[i].first, weeks[i].second) } else null, mark)
    if (mark > 0) Note("A marca é o maior passo semanal, ${money(Cents(mark))}.")
    Series(q, lines)
}

/** Mês a mês (AC-42): 3, 6 or 12 budget months ending in the interval's; divided or not into previstos and variável or by verba. */
@Composable
private fun MonthsWidget(db: Db, q: LeituraQuery, big: Boolean, onDays: (LocalDate, LocalDate) -> Unit) {
    val c = Margem.colors
    var n by rememberSaveable { mutableIntStateOf(6) }
    var split by rememberSaveable { mutableStateOf("nao") }
    val read = query(db, q, n) { d ->
        val end = d.monthOf(q.to)
        val windows = (n - 1 downTo 0).map { end.minus(it, DateTimeUnit.MONTH).let { m -> m to d.window(m) } }
        windows to d.leituraLines(q.copy(from = windows.first().second.start, to = windows.last().second.endInclusive))
    } ?: return
    val (windows, lines) = read
    val budgets = lines.map { it.budget }.distinct()
    val bars = windows.map { (m, w) ->
        val rs = lines.filter { it.date in w }
        monthShort(m.month) to when (split) {
            "previstos" -> listOf(total(rs.filter { it.fixed }).v, total(rs.filter { !it.fixed }).v)
            "verba" -> budgets.map { b -> total(rs.filter { it.budget == b }).v }
            else -> listOf(total(rs).v)
        }
    }
    val last = bars.last().second.sum()
    val mean = bars.dropLast(1).map { it.second.sum() }.let { if (it.isEmpty()) 0L else it.sum() / it.size }
    Sentence("Em ${monthLong(windows.last().first.month)}, ${money(Cents(last))}; a média dos anteriores é ${money(Cents(mean))}.")
    if (big) {
        OptionPicker(listOf(3 to "3", 6 to "6", 12 to "12"), n, { n = it })
        OptionPicker(listOf("nao" to "não", "previstos" to "previstos e variável", "verba" to "por verba"), split, { split = it })
    }
    Columns(bars, if (split == "nao") listOf(c.inkSecondary) else if (split == "previstos") listOf(c.inkTertiary.copy(alpha = 0.3f), c.inkSecondary) else c.series, if (big) { i ->
        onDays(windows[i].second.start, windows[i].second.endInclusive)
    } else null)
    Series(q, lines)
}

/** Meses à frente (AC-43): parcelas and previstos "Todo mês" of the next months, from today on, whatever the interval. */
@Composable
private fun AheadWidget(db: Db, q: LeituraQuery, today: LocalDate, big: Boolean) {
    val c = Margem.colors
    var n by rememberSaveable { mutableIntStateOf(3) }
    val read = query(db, q.chips, n, today) { d ->
        val cur = d.monthOf(today)
        d.ensureMonths(cur)
        val windows = (1..n).map { d.window(cur.plus(it, DateTimeUnit.MONTH)) }
        // planned_dates reaches only today's month (D71): its "Todo mês" previstos are projected, nothing written.
        val monthly = d.budgetsOverview(cur, today, BudgetOrder.MANUAL).flatMap { d.budgetDetail(it.id, cur, today).plannedLines }
            .map { it.planned }.filter { p ->
                p.repeats == Repeats.MONTHLY && (q.chips.isEmpty() ||
                    q.chips.any { (it.kind == ChipKind.BUDGET && it.key == p.budget.v.toString()) || (it.kind == ChipKind.TAG && it.key in p.tags) })
            }
        Triple(windows, d.leituraLines(LeituraQuery(windows.first().start, windows.last().endInclusive, q.chips)), Cents(monthly.sumOf { it.cents.v }))
    } ?: return
    val (windows, lines, monthly) = read
    val bars = windows.map { w -> monthShort(w.start.month) to listOf(total(lines.filter { it.date in w }).v, monthly.v) }
    Sentence("${money(total(lines))} em parcelas e ${money(Cents(monthly.v * n))} em previstos nos próximos $n meses, de hoje em diante.")
    if (big) OptionPicker(listOf(3 to "3", 6 to "6", 12 to "12"), n, { n = it })
    Columns(bars, listOf(c.inkSecondary, c.inkTertiary.copy(alpha = 0.3f)))
}

/** Volta todo mês (AC-44): estabelecimentos in every month of the last 6 or 12, at a similar value, not from a previsto. */
@Composable
private fun MonthlyWidget(db: Db, q: LeituraQuery, today: LocalDate, big: Boolean) {
    var n by rememberSaveable { mutableIntStateOf(6) }
    val read = query(db, q.chips, n, today) { d ->
        val cur = d.monthOf(today)
        val windows = (n downTo 1).map { d.window(cur.minus(it, DateTimeUnit.MONTH)) }
        windows to d.leituraLines(LeituraQuery(windows.first().start, windows.last().endInclusive, q.chips))
    } ?: return
    val (windows, lines) = read
    val rows = groups(lines, ChipKind.MERCHANT).mapNotNull { (chip, rs) ->
        val months = windows.map { w -> total(rs.filter { it.date in w }).v }
        // Similar: the largest month at most half again the smallest.
        if (rs.any { it.fixed } || months.any { it == 0L } || months.max() * 2 > months.min() * 3) null
        else Triple(chip, months.sum() / n, "${moneyPlain(Cents(months.sum() / n))} por mês")
    }
    Sentence(if (rows.isEmpty()) "Nenhum estabelecimento voltou todo mês nos últimos $n meses." else "${count(rows.size, "estabelecimento volta", "estabelecimentos voltam")} todo mês sem previsto.")
    if (big) OptionPicker(listOf(6 to "6 meses", 12 to "12 meses"), n, { n = it })
    Ranks(if (big) rows else rows.take(3), null)
}

/**
 * Dia e hora (AC-26, AC-45): 7 days × 6 bands of 4 hours, only lançamentos of recados, parcela 1, not of a previsto
 * (R21); says how many stayed out. Contagem by default, "Só até R$ 50" on.
 */
@Composable
private fun HourWidget(q: LeituraQuery, lines: List<LeituraRow>, big: Boolean) {
    val c = Margem.colors
    var summing by rememberSaveable { mutableStateOf(false) }
    var small by rememberSaveable { mutableStateOf(true) }
    val timed = lines.filter { it.source.isNotEmpty() && it.k == 1 && !it.fixed }
    val shown = timed.filter { !small || it.cents.v <= 5_000 }
    val grid = Array(7) { LongArray(6) }
    for (r in shown) grid[r.date.dayOfWeek.ordinal][r.time.hour / 4] += if (summing) r.cents.v else 1
    val max = maxOf(grid.maxOf { it.max() }, 1L)
    Sentence("${count(timed.size, "lançamento", "lançamentos")} com hora de recado; ${lines.size - timed.size} ficaram de fora.")
    if (big) {
        OptionPicker(listOf(false to "contagem", true to "soma"), summing, { summing = it })
        Row(Modifier.heightIn(min = 48.dp).clickable { small = !small }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Check(small)
            Text("Só até R$ 50", sans(14f, 400, 18f))
        }
    }
    for (band in 0 until 6) Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${band * 4}h", sans(10f, 500, 12f, tabular = true), Modifier.widthIn(min = 24.dp), c.inkTertiary)
        for (wd in 0 until 7) {
            val v = grid[wd][band]
            Box(Modifier.weight(1f).height(if (big) 22.dp else 12.dp).background(c.heat[if (v == 0L) 0 else ((4 * v + max - 1) / max).toInt().coerceIn(1, 4)]))
        }
    }
    Series(q, shown)
}
