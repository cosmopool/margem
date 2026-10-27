package com.kaiodelphino.margem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kaiodelphino.margem.BudgetDetail
import com.kaiodelphino.margem.BudgetId
import com.kaiodelphino.margem.BudgetOrder
import com.kaiodelphino.margem.BudgetSummary
import com.kaiodelphino.margem.Cents
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.HistoryRow
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.RankRow
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Icon
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.NNBSP
import com.kaiodelphino.margem.widgets.MonthPicker
import com.kaiodelphino.margem.widgets.OptionPicker
import com.kaiodelphino.margem.widgets.SectionLine
import com.kaiodelphino.margem.widgets.SeriesMark
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.hatch
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.moneyParts
import com.kaiodelphino.margem.widgets.moneyPlain
import com.kaiodelphino.margem.widgets.monthLong
import com.kaiodelphino.margem.widgets.monthShort
import com.kaiodelphino.margem.widgets.sans
import com.kaiodelphino.margem.widgets.serif
import com.kaiodelphino.margem.widgets.weekdayShort
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth

/**
 * Leitura's elements for UI tests (D35, spec §7.5, book 10.6). Money: `money` ("R$ 231,10") and
 * `moneyPlain` ("231,10") of widgets/Theme.kt; "R$ 796" = whole reais, rounded half up, thousands dot.
 * The month of the page is "the month" below; today comes from LocalToday.
 */
object InsightsTags {
    /**
     * Instead of everything else when there are no budgets: "Nada para ler ainda.", then "A Leitura mostra
     * para onde o dinheiro vai: verbas mês a mês, o ritmo do mês, marcadores e estabelecimentos."
     */
    const val EMPTY = "insights.empty"

    // ---- The month (spec §7.5: one month for the whole page) ----

    /**
     * "outubro de 2026", with "1 a 31 · até sex, 9" (uppercase) on the current month and "1 a 31" on a
     * past one (the month window, like the budget detail). Opens on the current month.
     */
    const val MONTH_LABEL = "insights.month.label"

    /** One month back; disabled on the earliest month with a limit row of any existing budget. */
    const val MONTH_PREV = "insights.month.prev"

    /** One month forward; disabled on the current month. */
    const val MONTH_NEXT = "insights.month.next"

    // ---- Ritmo do mês (book Fig. 9.2) ----
    // Ritmo and Verbas por mês are absent when no budget has a limit row in the month.

    /**
     * The chosen budget's name next to "Ritmo do mês", with its series mark; clickable, opens
     * [BUDGET_CHOICE]. Default: the riskiest budget of the month (spec §8.1 order). The choice stays
     * while the screen is open; on a month where that budget has no limit row, the default again.
     */
    const val PACE_BUDGET = "insights.pace.budget"

    /** Dialog listing the budgets with a limit row in the month, in the risk order. */
    const val BUDGET_CHOICE = "insights.budgetChoice"

    /** Prefix: BUDGET_OPTION + budget id ("insights.budgetChoice.3"): its name; click chooses it and closes. */
    const val BUDGET_OPTION = "insights.budgetChoice."

    /**
     * The conclusion (margem honesta, spec §7.5, D68). Current month, by the chosen budget's BudgetMonth:
     * - remaining < 0: "R$ 12,00 além da cota." (−remaining);
     * - else variable > ideal: "+ R$ 51,10 sobre o ritmo ideal." (variable − ideal);
     * - else variable < ideal: "R$ 20,00 abaixo do ritmo ideal." (ideal − variable);
     * - else: "No ritmo ideal.";
     * then, when ideal > 0 and spent > 0, " No passo atual, o mês fecharia em R$ 796." (projection, M6, whole reais).
     * Past month: "Fechou em R$ 1.180,00, R$ 60,00 dentro da cota." (spent, remaining),
     * "Fechou em R$ 477,00, R$ 12,00 além da cota." (spent, −remaining), or
     * "Fechou em R$ 620,00, exatamente a cota." when remaining == 0.
     */
    const val PACE_SENTENCE = "insights.pace.sentence"

    /**
     * The chart: cumulative gasto variável by day (installments on their date, refunds and TransactionRow.fixed
     * lines out, D68), BudgetMonth.idealByDay (of the cota livre)
     * dashed, projection dotted (current month, when there is a projection), the rest of the month shaded (current month only).
     * Content description, current month (the gasto variável up to today): "Ritmo do mês de Restaurantes: R$ 231,10 até o dia 9, contra
     * ritmo ideal de R$ 180,00; no passo atual, R$ 796 no fim do mês, para uma cota de R$ 620,00"
     * (the "; no passo atual, … no fim do mês" part only when ideal > 0 and spent > 0). Past month: "Ritmo do mês de
     * Restaurantes: R$ 1.180,00 no mês, para uma cota de R$ 1.240,00".
     */
    const val PACE_CHART = "insights.pace.chart"

    // ---- Verbas por mês (book Fig. 9.6) ----

    /**
     * Months of the 6 ending at the month in which the chosen budget spent more than that month's
     * limit: "Nenhum mês fora da margem.", "Um mês fora da margem.", "Dois meses fora da margem.",
     * "Três …", "Quatro …", "Cinco …", "Seis meses fora da margem."
     */
    const val HISTORY_SENTENCE = "insights.history.sentence"

    /**
     * The bars of the chosen budget, 6 slots, oldest first, ending at the month. Content description:
     * "Verbas por mês de Lazer: mai sem cota; jun 431,00 de 400,00; …; out 477,00 de 465,00" — one
     * "<monthShort> <moneyPlain spent> de <moneyPlain limit>" per month with a limit row, "<monthShort>
     * sem cota" for a month without one (before the budget's first month), joined by "; ".
     */
    const val HISTORY_CHART = "insights.history.chart"

    // ---- Marcadores e estabelecimentos (book Fig. 9.4) ----

    /** Options "Marcadores" (default) · "Estabelecimentos"; the chosen one is `selected`. Kept while the screen is open. */
    const val RANK_TAGS = "insights.rank.tags"
    const val RANK_MERCHANTS = "insights.rank.merchants"

    /** "feira puxa a fila." — the first row's name. Absent when the ranking is empty. */
    const val RANK_SENTENCE = "insights.rank.sentence"

    /**
     * Prefix: RANK_ROW + index from 0 ("insights.rank.row.0"), in Db.tagRanking / Db.merchantRanking
     * order for the month window: name, "3 lançamentos" ("1 lançamento") and moneyPlain of the total.
     * Rows 0–5 only until [RANK_MORE] is clicked; then all.
     */
    const val RANK_ROW = "insights.rank.row."

    /** "Ver todos (9)" (row count). Present only with more than 6 rows and until clicked. */
    const val RANK_MORE = "insights.rank.more"

    /**
     * Under Marcadores only, when there are rows: "Com vários marcadores, o lançamento conta inteiro
     * em cada um: a soma pode passar do total."
     */
    const val RANK_NOTE = "insights.rank.note"

    /** Instead of the rows when the ranking is empty: "Nada lançado em outubro." (monthLong of the month). */
    const val RANK_EMPTY = "insights.rank.empty"

    // ---- Calendário de gastos (book Fig. 9.5) ----

    /**
     * The weekday with the most spending in the month window (ties: the first in week order from the
     * Ajustes week start): "Os sábados pesam." / "As sextas pesam." (plural, lowercase weekday;
     * article "As" for segunda–sexta, "Os" for sábado and domingo). Absent when nothing was spent.
     */
    const val CAL_SENTENCE = "insights.cal.sentence"

    /**
     * "31% do mês em quatro sábados": that weekday's share of the month's spending (rounded half up)
     * and how many of that weekday the month window has, in words ("um sábado", "uma sexta",
     * "dois sábados", "duas sextas", "três", "quatro", "cinco"). Absent when nothing was spent.
     */
    const val CAL_SHARE = "insights.cal.share"

    /**
     * Prefix: CAL_DAY + ISO date ("insights.cal.day.2026-10-04"), one per day of the month window,
     * in a grid whose columns start at the Ajustes week start. Content description "4: R$ 286,40"
     * or "4: sem gasto"; state description "degrau 0".."degrau 4" (spec §7.5: step = highest day ÷ 4
     * rounded up to R$ 10; level = ⌈spent ÷ step⌉; 0 = no spending).
     */
    const val CAL_DAY = "insights.cal.day."

    /** "cada degrau ≈ R$ 60" (the step, whole reais). Absent when nothing was spent. */
    const val CAL_LEGEND = "insights.cal.legend"

    /** Instead of the grid when nothing was spent in the month: "Nada lançado em outubro." */
    const val CAL_EMPTY = "insights.cal.empty"
}

/** Everything the page shows for one month, read in one go. */
private class InsightsData(
    val month: YearMonth,
    val current: YearMonth,
    val first: YearMonth,
    val weekStart: DayOfWeek,
    /** Budgets with a limit row in the month, riskiest first (spec §8.1). */
    val budgets: List<BudgetSummary>,
    /** Every existing budget's id, oldest first: the series color is the index (spec §7.5). */
    val ids: List<Long>,
    /** The chosen budget, or zero when [budgets] is empty. */
    val detail: BudgetDetail,
    /** The chosen budget's rows of the 6 months ending at [month]. */
    val history: List<HistoryRow>,
    val start: LocalDate,
    val end: LocalDate,
    val tags: List<RankRow>,
    val merchants: List<RankRow>,
    val byDay: Map<LocalDate, Cents>,
)

/** The query's result: [data] null = no budgets (null from query() itself = still loading). */
private class Page(val data: InsightsData?)

private enum class Rank { TAGS, MERCHANTS }

private const val RANK_ROWS = 6

/** Leitura (spec §7.5, book 10.6): one month for the whole page; Ritmo, Verbas por mês, ranking, calendar. */
@Composable
fun Insights(db: Db) {
    val today = LocalToday.current()
    // "" = the current month; the chosen budget 0 = the riskiest. Both kept while the screen is open.
    var picked by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableLongStateOf(0L) }
    var rank by rememberSaveable { mutableStateOf(Rank.TAGS) }
    var all by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val page = query(db, picked, chosen, today) { d ->
        val current = d.monthOf(today)
        d.ensureMonths(current)
        val rows = d.history(YearMonth(1, 1), current)
        if (rows.isEmpty()) return@query Page(null)
        val first = rows.minOf { it.month }
        val month = (if (picked.isEmpty()) current else YearMonth.parse(picked)).coerceIn(first, current)
        d.ensureMonths(month)
        val budgets = d.budgetsOverview(month, today, BudgetOrder.RISK)
        val pick = budgets.firstOrNull { it.id.v == chosen } ?: budgets.firstOrNull()
        val detail = if (pick == null) BudgetDetail() else d.budgetDetail(pick.id, month, today)
        val from = month.minusMonth().minusMonth().minusMonth().minusMonth().minusMonth()
        val start = budgets.firstOrNull()?.month?.start ?: LocalDate(month.year, month.month, 1)
        val end = budgets.firstOrNull()?.month?.end ?: start
        Page(InsightsData(
            month, current, first, d.settings().weekStart, budgets,
            rows.map { it.budget.v }.distinct().sorted(), detail,
            rows.filter { it.budget == detail.id && it.month in from..month },
            start, end, d.tagRanking(start, end), d.merchantRanking(start, end), d.spentByDay(start, end),
        ))
    }
    val data = page?.data
    Column(Modifier.fillMaxSize()) {
        TopBar("Leitura")
        if (data == null) {
            if (page != null) EmptyState(
                R.drawable.i_leitura, "Nada para ler ainda.",
                "A Leitura mostra para onde o dinheiro vai: verbas mês a mês, o ritmo do mês, marcadores e estabelecimentos.",
                Modifier.padding(horizontal = Margem.space.page).testTag(InsightsTags.EMPTY).semantics(mergeDescendants = true) {},
            )
            return@Column
        }
        val isCurrent = data.month == data.current
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Margem.space.page, end = Margem.space.page, bottom = 32.dp)) {
            val span = if (data.start.month == data.end.month) "${data.start.day} a ${data.end.day}"
            else "${data.start.day} ${monthShort(data.start.month)} a ${data.end.day} ${monthShort(data.end.month)}"
            // The arrows move from the month asked for, not the one last read: quick taps don't repeat a month.
            val asked = (if (picked.isEmpty()) data.current else YearMonth.parse(picked)).coerceIn(data.first, data.current)
            MonthPicker(
                data.month, if (isCurrent) "$span · até ${weekdayShort(today.dayOfWeek)}, ${today.day}" else span,
                { picked = asked.minusMonth().toString() }, { picked = asked.plusMonth().toString() },
                canNext = asked < data.current, canPrev = asked > data.first, tag = "insights.month",
            )
            if (data.detail.id.v != 0L) {
                val color = seriesColor(data.ids, data.detail.id.v)
                Pace(data, today, isCurrent, color) { choosing = true }
                History(data, color)
            }
            Ranking(data, rank, all, { rank = it }, { all = true })
            Calendar(data)
        }
    }
    if (choosing && data != null) BudgetChoice(data, { chosen = it; choosing = false }) { choosing = false }
}

@Composable
private fun seriesColor(ids: List<Long>, id: Long): Color {
    val series = Margem.colors.series
    val i = ids.indexOf(id)
    return if (i in 0..5) series[i] else series[6]
}

/** "R$ 796": whole reais, rounded half up. */
private fun reais(c: Cents) = "R$$NNBSP${moneyParts(Cents((c.v + 50) / 100 * 100)).first}"

// ---- Ritmo do mês ----

@Composable
private fun Pace(data: InsightsData, today: LocalDate, isCurrent: Boolean, color: Color, onChoose: () -> Unit) {
    val c = Margem.colors
    val d = data.detail
    val bm = d.month
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("RITMO DO MÊS", sans(11f, 600, 14f, 0.12f))
        Row(
            Modifier.heightIn(min = 48.dp).testTag(InsightsTags.PACE_BUDGET).clickable(onClick = onChoose).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SeriesMark(color)
            Text(d.name, serif(15, 500, 20))
            Icon(R.drawable.i_avancar, tint = c.inkTertiary, size = 16.dp)
        }
    }
    // No projection without ideal (spec §5) or without spending: "fecharia em R$ 0" says nothing (spec §7.5).
    val projects = bm.ideal.v > 0 && bm.spent.v > 0
    val sentence = if (isCurrent) {
        val first = when {
            bm.spent > bm.limit -> "${money(bm.spent - bm.limit)} além da cota."
            bm.spent > bm.ideal -> "+ ${money(bm.spent - bm.ideal)} sobre o ritmo ideal."
            bm.spent < bm.ideal -> "${money(bm.ideal - bm.spent)} abaixo do ritmo ideal."
            else -> "No ritmo ideal."
        }
        if (projects) "$first No passo atual, o mês fecharia em ${reais(bm.projection)}." else first
    } else when {
        bm.spent < bm.limit -> "Fechou em ${money(bm.spent)}, ${money(bm.limit - bm.spent)} dentro da cota."
        bm.spent > bm.limit -> "Fechou em ${money(bm.spent)}, ${money(bm.spent - bm.limit)} além da cota."
        else -> "Fechou em ${money(bm.spent)}, exatamente a cota."
    }
    Text(sentence, serif(15, 400, 21, italic = true), Modifier.padding(top = 2.dp).testTag(InsightsTags.PACE_SENTENCE), c.inkSecondary)
    val description = if (isCurrent) {
        "Ritmo do mês de ${d.name}: ${money(bm.spent)} até o dia ${today.day}, contra ritmo ideal de ${money(bm.ideal)}" +
            (if (projects) "; no passo atual, ${reais(bm.projection)} no fim do mês" else "") +
            ", para uma cota de ${money(bm.limit)}"
    } else "Ritmo do mês de ${d.name}: ${money(bm.spent)} no mês, para uma cota de ${money(bm.limit)}"

    // Cumulative spent at the end of each day of the window (refunds out, installments on their date).
    val days = bm.start.daysUntil(bm.end) + 1
    val cumulative = LongArray(days)
    for (line in d.lines) if (!line.row.refunded) {
        val i = bm.start.daysUntil(line.date)
        if (i in 0 until days) cumulative[i] += line.cents.v
    }
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
        fun x(i: Int) = left + w * (i + 0.5f) / days
        fun y(v: Long) = bottom - bottom * 0.94f * v / ceil
        grid(measurer, label, left, bottom, axis, ceil, ::y)
        if (isCurrent && last < days - 1) {
            val from = x(last.coerceAtLeast(0)) + w / days / 2
            drawRect(c.surfaceSunken.copy(alpha = 0.6f), Offset(from, 0f), Size(size.width - from, bottom))
        }
        // Cota: the argila double rule.
        for (dy in listOf(-1.5f, 1.5f)) drawLine(c.brand, Offset(left, y(bm.limit.v) + dy.dp.toPx()), Offset(size.width, y(bm.limit.v) + dy.dp.toPx()), 0.75f.dp.toPx())
        // Ritmo ideal, dashed, the whole month.
        val ideal = Path()
        bm.idealByDay.forEachIndexed { i, v -> if (i == 0) ideal.moveTo(x(i), y(v.v)) else ideal.lineTo(x(i), y(v.v)) }
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
            val t = measurer.measure(bm.start.plusDays(i).day.toString(), label)
            drawText(t, topLeft = Offset(x(i) - t.size.width / 2, bottom + 4.dp.toPx()))
        }
    }
}

private fun LocalDate.plusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)

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

/** The budget choice: budgets with a limit row in the month, riskiest first. */
@Composable
private fun BudgetChoice(data: InsightsData, onPick: (Long) -> Unit, close: () -> Unit) {
    val c = Margem.colors
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.testTag(InsightsTags.BUDGET_CHOICE).padding(horizontal = 32.dp).widthIn(max = 340.dp).fillMaxWidth()
                .shadow(14.dp, shape, ambientColor = c.shadow, spotColor = c.shadow).background(c.surface, shape)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
        ) {
            Text("Qual verba?", serif(22, 500, 28), Modifier.padding(bottom = 8.dp))
            for (b in data.budgets) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(InsightsTags.BUDGET_OPTION + b.id.v)
                    .clickable { onPick(b.id.v) }.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SeriesMark(seriesColor(data.ids, b.id.v))
                Text(b.name, serif(17, if (b.id == data.detail.id) 600 else 400, 22))
            }
        }
    }
}

// ---- Verbas por mês ----

private val countWords = listOf("Nenhum mês", "Um mês", "Dois meses", "Três meses", "Quatro meses", "Cinco meses", "Seis meses")

@Composable
private fun History(data: InsightsData, color: Color) {
    val c = Margem.colors
    val months = (5 downTo 0).map { back ->
        var m = data.month
        repeat(back) { m = m.minusMonth() }
        m
    }
    val rows = months.map { m -> data.history.firstOrNull { it.month == m } }
    SectionLine("Verbas por mês", aside = "gasto · cota")
    val over = rows.count { it != null && it.spent > it.limit }
    Text("${countWords[over]} fora da margem.", serif(15, 400, 21, italic = true), Modifier.padding(top = 8.dp).testTag(InsightsTags.HISTORY_SENTENCE), c.inkSecondary)
    val description = "Verbas por mês de ${data.detail.name}: " + months.zip(rows).joinToString("; ") { (m, r) ->
        if (r == null) "${monthShort(m.month)} sem cota" else "${monthShort(m.month)} ${moneyPlain(r.spent)} de ${moneyPlain(r.limit)}"
    }
    val top = maxOf(rows.maxOf { maxOf(it?.spent?.v ?: 0L, it?.limit?.v ?: 0L) }, 1L)
    val measurer = rememberTextMeasurer()
    val label = Margem.type.axis.copy(color = c.inkTertiary)
    val value = Margem.type.axis.copy(color = c.overrun)
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp).testTag(InsightsTags.HISTORY_CHART).semantics { contentDescription = description }) {
        val bottom = size.height - 18.dp.toPx()
        val slot = size.width / 6
        fun y(v: Long) = bottom - (bottom - 14.dp.toPx()) * v / top
        for ((i, r) in rows.withIndex()) {
            val x0 = slot * i + slot * 0.2f
            val bw = slot * 0.6f
            if (r != null) {
                val within = minOf(r.spent.v, r.limit.v)
                drawRect(color, Offset(x0, y(within)), Size(bw, bottom - y(within)))
                if (r.spent > r.limit) {
                    hatch(c.overrun, Offset(x0, y(r.spent.v)), Size(bw, y(r.limit.v) - y(r.spent.v) - 2.dp.toPx()), 1.6f.dp.toPx(), 4.dp.toPx())
                    val t = measurer.measure(moneyParts(r.spent).first, value)
                    drawText(t, topLeft = Offset(x0 + bw / 2 - t.size.width / 2, y(r.spent.v) - t.size.height - 2.dp.toPx()))
                }
                for (dy in listOf(-1.5f, 1.5f)) drawLine(c.brand, Offset(x0 - 3.dp.toPx(), y(r.limit.v) + dy.dp.toPx()), Offset(x0 + bw + 3.dp.toPx(), y(r.limit.v) + dy.dp.toPx()), 0.75f.dp.toPx())
            }
            val t = measurer.measure(monthShort(months[i].month), label)
            drawText(t, topLeft = Offset(slot * i + slot / 2 - t.size.width / 2, bottom + 4.dp.toPx()))
        }
        drawLine(label.color.copy(alpha = 0.9f), Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
    }
}

// ---- Marcadores e estabelecimentos ----

@Composable
private fun Ranking(data: InsightsData, rank: Rank, all: Boolean, onRank: (Rank) -> Unit, onAll: () -> Unit) {
    val c = Margem.colors
    SectionLine(if (rank == Rank.TAGS) "Marcadores" else "Estabelecimentos", aside = "por valor · R$")
    OptionPicker(listOf(Rank.TAGS to "Marcadores", Rank.MERCHANTS to "Estabelecimentos"), rank, onRank, tag = "insights.rank.")
    val rows = if (rank == Rank.TAGS) data.tags else data.merchants
    if (rows.isEmpty()) {
        Text("Nada lançado em ${monthLong(data.month.month)}.", Margem.type.body, Modifier.padding(top = 12.dp).testTag(InsightsTags.RANK_EMPTY), c.inkSecondary)
        return
    }
    Text("${rows[0].name} puxa a fila.", serif(15, 400, 21, italic = true), Modifier.padding(top = 10.dp).testTag(InsightsTags.RANK_SENTENCE), c.inkSecondary)
    val shown = if (all) rows else rows.take(RANK_ROWS)
    val top = maxOf(rows[0].total.v, 1L)
    for ((i, r) in shown.withIndex()) Column(
        Modifier.fillMaxWidth().testTag(InsightsTags.RANK_ROW + i).semantics(mergeDescendants = true) {}.padding(top = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(r.name, serif(16, 500, 21), Modifier.weight(1f), maxLines = 1)
            Text(if (r.count == 1) "1 lançamento" else "${r.count} lançamentos", sans(12f, 500, 16f), Modifier.padding(horizontal = 10.dp), c.inkTertiary)
            Text(moneyPlain(r.total), sans(14f, 600, 18f, tabular = true))
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 3.dp)) {
            drawRect(c.inkTertiary, Offset.Zero, Size(size.width * r.total.v / top, size.height))
        }
    }
    if (!all && rows.size > RANK_ROWS) Text(
        "Ver todos (${rows.size})", sans(15f, 600, 20f),
        Modifier.padding(top = 6.dp).heightIn(min = 48.dp).testTag(InsightsTags.RANK_MORE).clickable(onClick = onAll).padding(vertical = 14.dp),
        c.action,
    )
    if (rank == Rank.TAGS) Text(
        "Com vários marcadores, o lançamento conta inteiro em cada um: a soma pode passar do total.",
        sans(13f, 400, 18f), Modifier.padding(top = 10.dp).testTag(InsightsTags.RANK_NOTE), c.inkTertiary,
    )
}

// ---- Calendário de gastos ----

private val weekdayPlural = listOf("segundas", "terças", "quartas", "quintas", "sextas", "sábados", "domingos")
private val weekdaySingular = listOf("segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo")

/** "um sábado", "duas sextas", "quatro sábados". */
private fun countOf(day: DayOfWeek, n: Int): String {
    val feminine = day.ordinal <= 4
    val word = when (n) {
        1 -> if (feminine) "uma" else "um"
        2 -> if (feminine) "duas" else "dois"
        3 -> "três"
        4 -> "quatro"
        else -> "cinco"
    }
    return "$word ${if (n == 1) weekdaySingular[day.ordinal] else weekdayPlural[day.ordinal]}"
}

@Composable
private fun Calendar(data: InsightsData) {
    val c = Margem.colors
    SectionLine("Gasto por dia", aside = monthLong(data.month.month))
    val total = data.byDay.values.sumOf { it.v }
    if (total == 0L) {
        Text("Nada lançado em ${monthLong(data.month.month)}.", Margem.type.body, Modifier.padding(top = 12.dp).testTag(InsightsTags.CAL_EMPTY), c.inkSecondary)
        return
    }
    val days = (0..data.start.daysUntil(data.end)).map { data.start.plusDays(it) }
    // Weekday with the most spending; ties go to the first in week order.
    val order = (0 until 7).map { DayOfWeek.entries[(data.weekStart.ordinal + it) % 7] }
    val byWeekday = order.associateWith { wd -> days.filter { it.dayOfWeek == wd }.sumOf { data.byDay[it]?.v ?: 0L } }
    val heavy = order.maxBy { byWeekday.getValue(it) }
    val article = if (heavy.ordinal <= 4) "As" else "Os"
    Text("$article ${weekdayPlural[heavy.ordinal]} pesam.", serif(15, 400, 21, italic = true), Modifier.padding(top = 8.dp).testTag(InsightsTags.CAL_SENTENCE), c.inkSecondary)
    val share = (200 * byWeekday.getValue(heavy) + total) / (2 * total)
    Text(
        "$share% do mês em ${countOf(heavy, days.count { it.dayOfWeek == heavy })}", sans(13f, 500, 17f),
        Modifier.padding(top = 2.dp).testTag(InsightsTags.CAL_SHARE), c.inkTertiary,
    )
    // Spec §7.5: step = highest day ÷ 4, rounded up to R$ 10.
    val max = data.byDay.values.maxOf { it.v }
    val step = (max + 3999) / 4000 * 1000
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (wd in order) Text(weekdayShort(wd).uppercase(), sans(10.5f, 600, 14f, 0.06f), Modifier.weight(1f), c.inkTertiary)
    }
    val lead = (data.start.dayOfWeek.ordinal - data.weekStart.ordinal + 7) % 7
    val cells: List<LocalDate?> = List(lead) { null } + days
    for (week in cells.chunked(7)) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 7) {
            val day = week.getOrNull(i)
            if (day == null) {
                Box(Modifier.weight(1f).aspectRatio(1f))
                continue
            }
            val spent = data.byDay[day]?.v ?: 0L
            val level = if (spent == 0L) 0 else ((spent + step - 1) / step).toInt().coerceIn(1, 4)
            val description = "${day.day}: ${if (spent == 0L) "sem gasto" else money(Cents(spent))}"
            Box(
                Modifier.weight(1f).aspectRatio(1f).background(c.heat[level]).clearAndSetSemantics {
                    testTag = InsightsTags.CAL_DAY + day
                    contentDescription = description
                    stateDescription = "degrau $level"
                }.padding(3.dp),
            ) {
                Text(day.day.toString(), sans(10.5f, 600, 13f, tabular = true), color = if (level >= 3) c.paper else c.inkSecondary)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("menos", sans(11f, 500, 14f), color = c.inkTertiary)
        for (l in 0..4) Box(Modifier.weight(1f, fill = false).padding(horizontal = 1.dp).background(c.heat[l]).height(10.dp).widthIn(min = 14.dp))
        Text("mais", sans(11f, 500, 14f), color = c.inkTertiary)
        Text(
            "cada degrau ≈ ${reais(Cents(step))}", sans(11f, 500, 14f),
            Modifier.weight(1f).padding(start = 8.dp).testTag(InsightsTags.CAL_LEGEND), c.inkTertiary,
        )
    }
}
