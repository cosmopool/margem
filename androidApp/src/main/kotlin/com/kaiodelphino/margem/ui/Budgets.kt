package com.kaiodelphino.margem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.kaiodelphino.margem.BudgetDetail
import com.kaiodelphino.margem.BudgetId
import com.kaiodelphino.margem.BudgetMonth
import com.kaiodelphino.margem.BudgetOrder
import com.kaiodelphino.margem.BudgetSummary
import com.kaiodelphino.margem.Ceiling
import com.kaiodelphino.margem.Cents
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.MonthLine
import com.kaiodelphino.margem.PaceStatus
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.Week
import com.kaiodelphino.margem.widgets.AliasText
import com.kaiodelphino.margem.widgets.Amount
import com.kaiodelphino.margem.widgets.Button
import com.kaiodelphino.margem.widgets.ButtonKind
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Field
import com.kaiodelphino.margem.widgets.Gap
import com.kaiodelphino.margem.widgets.HomeBar
import com.kaiodelphino.margem.widgets.Icon
import com.kaiodelphino.margem.widgets.IconButton
import com.kaiodelphino.margem.widgets.MargemDialog
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.Meter
import com.kaiodelphino.margem.widgets.MoneyField
import com.kaiodelphino.margem.widgets.MonthPicker
import com.kaiodelphino.margem.widgets.OptionPicker
import com.kaiodelphino.margem.widgets.PageHeader
import com.kaiodelphino.margem.widgets.SectionLine
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.Space
import com.kaiodelphino.margem.widgets.StatusLabel
import com.kaiodelphino.margem.widgets.StepsEditor
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.moneyPlain
import com.kaiodelphino.margem.widgets.monthLong
import com.kaiodelphino.margem.widgets.monthShort
import com.kaiodelphino.margem.widgets.sans
import com.kaiodelphino.margem.widgets.serif
import com.kaiodelphino.margem.widgets.stepValue
import com.kaiodelphino.margem.widgets.weekRange
import com.kaiodelphino.margem.widgets.weekdayShort
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth

/**
 * Test tags of the Verbas screens (D35): the contract of their UI tests. Tests run
 * `App(db, tabRequest = null, onTabOpened = {})` inside `MargemTheme(dark = false)` and
 * `CompositionLocalProvider(LocalToday provides { day })`, on a Db with AndroidSQLiteDriver (D28),
 * and find elements only by these tags. The app opens on Verbas.
 *
 * - A tagged node's text (SemanticsProperties.Text, assertTextContains) is everything it shows,
 *   except other tagged nodes inside it, which are nodes of their own. [LIST] and [STEP_DIALOG] are
 *   plain containers: only their presence and children matter.
 * - Inputs ([FORM_NAME], [FORM_LIMIT], [STEP_FIELD]) are text fields: performTextInput /
 *   performTextReplacement on them. Money inputs take digits as cents ("31000" = R$ 310,00); how they
 *   display the value is not part of the contract.
 * - Money in texts is formatted by `money()` ("R$ 1.234,56", U+202F after "R$") or `moneyPlain()`
 *   ("1.234,56") from widgets/Theme.kt; a week's step by `stepValue()` ("105", "33,33").
 * - "Absent" = no node has the tag. "Disabled" = the node is not enabled (assertIsNotEnabled).
 */
object BudgetTags {
    // ---- Verbas, the home tab (book 10.1) ----

    /**
     * The month of today: "Mês de 1 a 31 de outubro" (or "Mês de 5 de outubro a 4 de novembro"),
     * "Dia 9 de 31." and "Faltam 23 dias, contando hoje." ("Falta 1 dia, contando hoje." on the last day).
     */
    const val HEADER = "budgets.header"

    // ---- Teto in the header (spec §3, §7.1; book 8.10; D46) ----

    /**
     * The ceiling line under the header's dek, for the current month (after ensureMonths). Absent when the
     * month has no budget. With a ceiling: "cotas R$ 3.720,00" and "teto R$ 4.100,00" ("teto R$ 4.400,00 ·
     * só outubro" when Ceiling.cents != Ceiling.standard), plus "+ 300" (whole reais; "+ 300,50" with
     * cents) when the limits pass it; content description "Teto de outubro: R$ 4.100,00. Cotas somam
     * R$ 3.720,00, a R$ 380,00 do teto. Tocar para mudar." ("…, fecham no teto. …" when equal; "…, R$ 300,00
     * além do teto. …" when above). Without a ceiling: "As cotas somam R$ 3.720,00." and "Pôr um teto";
     * content description "As cotas de outubro somam R$ 3.720,00. Sem teto. Tocar para pôr um teto."
     * Clicking it, or [HEADER], opens [CEILING_DIALOG] (one touch target from the rubric to the legend);
     * with no budget, [HEADER] opens nothing.
     */
    const val CEILING = "budgets.ceiling"

    /**
     * The ceiling dialog: "Teto de outubro" (the current month's name). Two steps: the value ([CEILING_FIELD],
     * [CEILING_HINT], [CEILING_SAVE], [CEILING_REMOVE], [CEILING_CANCEL]) and then the months question
     * ([CEILING_SCOPE]). Closing it any other way writes nothing.
     */
    const val CEILING_DIALOG = "budgets.ceilingDialog"

    /** "Máximo para a soma das cotas": money input, starting with the month's ceiling (zero when none). */
    const val CEILING_FIELD = "budgets.ceilingDialog.field"

    /**
     * While the field is > 0: "As cotas de outubro somam R$ 3.720,00: ficam a R$ 680,00 do teto." /
     * "…: fecham no teto." (equal) / "…: ficariam R$ 220,00 além do teto. Dá para salvar assim." Absent at zero.
     */
    const val CEILING_HINT = "budgets.ceilingDialog.hint"

    /** "Salvar": disabled while the field is zero; opens [CEILING_SCOPE] for that value. */
    const val CEILING_SAVE = "budgets.ceilingDialog.save"

    /** "Tirar teto": only when the month has a ceiling (absent otherwise); opens [CEILING_SCOPE] for removing. */
    const val CEILING_REMOVE = "budgets.ceilingDialog.remove"

    /** "Cancelar": closes the dialog; nothing is written. */
    const val CEILING_CANCEL = "budgets.ceilingDialog.cancel"

    /**
     * The months question, inside [CEILING_DIALOG]: "Vale para quais meses?" after Salvar, "Tirar o teto de
     * quais meses?" after Tirar teto.
     */
    const val CEILING_SCOPE = "budgets.ceilingDialog.scope"

    /**
     * "De outubro em diante", with "Vira o teto dos próximos meses." ("Os próximos meses ficam sem teto." when
     * removing). Click writes setCeiling(value or 0, onward = true), closes the dialog and shows the snack
     * "Teto de R$ 4.400,00 de outubro em diante." / "Outubro em diante sem teto.", whose Desfazer puts the
     * default and the month's ceiling back as they were (D46).
     */
    const val CEILING_ONWARD = "budgets.ceilingDialog.onward"

    /**
     * "Só em outubro", with "Em novembro, volta a R$ 4.100,00." (the default) or "Em novembro, volta a não ter
     * teto." (no default). Click writes setCeiling(value or 0, onward = false), closes the dialog and shows the
     * snack "Teto de R$ 4.400,00 só em outubro." / "Outubro sem teto.", with the same Desfazer.
     */
    const val CEILING_ONLY = "budgets.ceilingDialog.only"

    /** "Voltar": back from [CEILING_SCOPE] to the value step, with the typed value kept; nothing is written. */
    const val CEILING_BACK = "budgets.ceilingDialog.back"

    /**
     * "4 recados na pauta" ("1 recado na pauta"), plus "3 novos desde a última vez" ("1 novo desde a
     * última vez") when some are new (spec §2.11). Click opens the Pauta. Absent when the Pauta is empty.
     */
    const val PAUTA_STRIP = "budgets.pauta"

    /**
     * "Ordenar" options: risco · manual · A–Z (spec §7.1). Each is clickable and `selected` when it is
     * the chosen order; the choice is saved in Settings.budgetOrder and the list follows it.
     */
    const val ORDER_RISK = "budgets.order.risk"
    const val ORDER_MANUAL = "budgets.order.manual"
    const val ORDER_AZ = "budgets.order.az"

    /** "+": opens Nova verba. */
    const val ADD = "budgets.add"

    /** The scrollable list: one [ROW] per budget with a limit in the current month, in the chosen order. */
    const val LIST = "budgets.list"

    /**
     * Prefix: ROW + budget id ("budgets.row.3"). Name; spent (money) and "de 465,00" (moneyPlain of the
     * limit); the meter; the state ("No ritmo", "Acima do ritmo", "Fora da margem"; "Sem gasto" when
     * nothing was spent); and "margem R$ 388,90", or "R$ 12,00 além" when Fora da margem.
     * Click opens the detail on the current month. In manual order, long press and drag moves the row;
     * the new order is saved (reorderBudgets) on release.
     */
    const val ROW = "budgets.row."

    /** Instead of the list when there are no budgets: what a verba is and how to create the first. */
    const val EMPTY = "budgets.empty"

    // ---- Nova verba (book 10.7) and Editar verba: the same form ----

    /** Name input. Empty in Nova verba; the budget's name in Editar verba. */
    const val FORM_NAME = "budgetForm.name"

    /**
     * After a refused submit: "Dê um nome à verba." (blank) or "Já existe uma verba com esse nome."
     * (used by another budget, ignoring case). Absent otherwise.
     */
    const val FORM_NAME_ERROR = "budgetForm.name.error"

    /** Cota mensal input. Zero in Nova verba; the current month's limit in Editar verba. */
    const val FORM_LIMIT = "budgetForm.limit"

    /** After a refused submit: "A cota precisa ser maior que zero." Absent otherwise. */
    const val FORM_LIMIT_ERROR = "budgetForm.limit.error"

    /**
     * Nova verba only, while the limit is > 0: the steps of previewBudget(limit, today) — "Passos
     * semanais de outubro", "R$ 10,00 por dia · semana cheia R$ 70,00" (limit ÷ days of the month and
     * limit × 7 ÷ days, rounded down to the cent), and each week's range ("1–4") and step. Absent while
     * the limit is zero.
     */
    const val FORM_PREVIEW = "budgetForm.preview"

    /**
     * "Criar verba" in Nova verba, "Salvar" in Editar verba. A blank or used name, or a zero limit, shows
     * the errors above and stays. Nova verba creates the budget (createBudget, today's month) and goes
     * back to Verbas. Editar verba renames it and, when the limit changed, sets it from today's month on
     * (setLimit) — first asking with [LIMIT_DIALOG] when limitDropsEdits — then goes back to the detail.
     * When [FORM_CEILING] applies, [CEILING_WARN] asks first, before anything else is written.
     */
    const val FORM_SUBMIT = "budgetForm.submit"

    /**
     * Under the limit, while the typed limit raises the current month's sum of limits above its ceiling
     * (previewCeiling over > 0 and limits above the month's; spec §3): "Com esta cota, as cotas de outubro
     * somam R$ 4.400,00: R$ 300,00 além do teto." Absent otherwise (no ceiling, not above, or the sum
     * doesn't go up).
     */
    const val FORM_CEILING = "budgetForm.ceiling"

    /**
     * On a valid submit while [FORM_CEILING] applies: "Passar do teto?", "As cotas de outubro somariam
     * R$ 4.400,00, R$ 300,00 além do teto de R$ 4.100,00. O teto é só um limite de planejamento." Confirm
     * ("Criar mesmo assim" / "Salvar mesmo assim") goes on with the submit (in Editar verba, [LIMIT_DIALOG]
     * comes next when it also applies); cancel ("Voltar") closes it, writes nothing and stays on the form.
     */
    const val CEILING_WARN = "budgetForm.ceilingDialog"
    const val CEILING_WARN_CONFIRM = "budgetForm.ceilingDialog.confirm"
    const val CEILING_WARN_CANCEL = "budgetForm.ceilingDialog.cancel"

    /** Editar verba only: "Apagar verba", opens [DELETE_DIALOG]. */
    const val FORM_DELETE = "budgetForm.delete"

    /**
     * The new limit deletes step edits (spec §4): says so. Confirm saves (rename + setLimit) and goes back
     * to the detail; cancel closes the dialog, saves nothing and stays on the form.
     */
    const val LIMIT_DIALOG = "budgetForm.limitDialog"
    const val LIMIT_DIALOG_CONFIRM = "budgetForm.limitDialog.confirm"
    const val LIMIT_DIALOG_CANCEL = "budgetForm.limitDialog.cancel"

    /**
     * "Apagar Lazer?": says the budget's N lançamentos are deleted too and their recados go back to the
     * Pauta (the number in digits). Confirm deletes (deleteBudget) and returns to Verbas; cancel closes it.
     */
    const val DELETE_DIALOG = "budgetForm.deleteDialog"
    const val DELETE_DIALOG_CONFIRM = "budgetForm.deleteDialog.confirm"
    const val DELETE_DIALOG_CANCEL = "budgetForm.deleteDialog.cancel"

    // ---- Detalhe da verba (book 10.8) ----

    /** The top bar: the budget's name. */
    const val DETAIL_TITLE = "budget.title"

    /** "Editar": opens Editar verba. */
    const val DETAIL_EDIT = "budget.edit"

    /** "outubro de 2026", with "1 a 31 · mês atual" (uppercase) on the current month. */
    const val MONTH_LABEL = "budget.month.label"

    /** One month back; disabled on the budget's first month (BudgetDetail.firstMonth). */
    const val MONTH_PREV = "budget.month.prev"

    /** One month forward; disabled on the current month. */
    const val MONTH_NEXT = "budget.month.next"

    /**
     * The headline: "R$ 12,00 além da cota." when Fora da margem; otherwise it contains the margin
     * (money of BudgetMonth.remaining). Followed by a sentence about the month.
     */
    const val DETAIL_HEADLINE = "budget.headline"

    /** The tall meter; content description "R$ 477,00 de R$ 465,00" (spent de limit). */
    const val DETAIL_METER = "budget.meter"

    /**
     * Prefix: WEEK + Week.start ("budget.week.2026-10-05"): range ("5–11") and step. Clickable and
     * enabled unless Week.locked; click opens [STEP_DIALOG] for that week.
     */
    const val WEEK = "budget.week."

    /** The step dialog of the tapped week. */
    const val STEP_DIALOG = "budget.step"

    /** Step input, starting with the week's step. */
    const val STEP_FIELD = "budget.step.field"

    /** "no máximo R$ 465,00": money of Week.max. */
    const val STEP_MAX = "budget.step.max"

    /** "Salvar": a value in 0..Week.max is saved (setStep) and the dialog closes; above it the dialog stays and nothing changes. */
    const val STEP_SAVE = "budget.step.save"

    /** Edited weeks only: "Voltar ao automático" (clearStep), then the dialog closes. Absent on an automatic week. */
    const val STEP_AUTO = "budget.step.auto"

    /** "Cancelar": closes the dialog; nothing changes. */
    const val STEP_CANCEL = "budget.step.cancel"

    /**
     * Prefix: LINE + transaction id, one per BudgetDetail.lines, oldest first. Name (alias, else bank
     * text); "qui, 1" (weekday and day of MonthLine.date) and the tags; "Parcela 3 de 10" when in
     * installments; "estornada" when refunded; the installment's value (money). Click opens the edit form (TransactionTags).
     */
    const val LINE = "budget.line."

    /** Prefix: LINE_SUM + transaction id: the running sum up to that line (moneyPlain); refunded lines don't add. */
    const val LINE_SUM = "budget.lineSum."

    /** "cota atingida aqui": right after the first line whose running sum reaches the limit (≥). Absent if it never does. */
    const val CUT = "budget.cut"

    /** Instead of the lines when the month has none. */
    const val LINES_EMPTY = "budget.lines.empty"
}

/** "texts" of a node whose visible text is transformed (uppercase rubrics): what the contract reads (D35). */
private fun Modifier.texts(tag: String, vararg lines: String) =
    clearAndSetSemantics { testTag = tag; this[SemanticsProperties.Text] = lines.map(::AnnotatedString) }

/** The budget's identity dot (book `i.id`): a categorical series color by id. */
@Composable
private fun budgetColor(id: BudgetId) = Margem.colors.series[((id.v - 1).mod(6L)).toInt()]

/** "Mês de 1 a 31 de outubro" / "Mês de 5 de outubro a 4 de novembro". */
private fun monthSpan(start: LocalDate, end: LocalDate) =
    if (start.month == end.month) "Mês de ${start.day} a ${end.day} de ${monthLong(start.month)}"
    else "Mês de ${start.day} de ${monthLong(start.month)} a ${end.day} de ${monthLong(end.month)}"

private class Overview(val budgets: List<BudgetSummary>, val order: BudgetOrder, val window: BudgetMonth, val pending: Int, val fresh: Int)

/**
 * Verbas (spec §7.1, book 10.1): header of today's month, the Pauta strip, "Ordenar", the budgets of
 * the current month (after ensureMonths, D30) and "+". Tags: [BudgetTags].
 */
@Composable
fun Budgets(db: Db, go: (Screen) -> Unit) {
    val today = LocalToday.current()
    val scope = rememberCoroutineScope()
    val data = query(db, today) {
        val month = it.monthOf(today)
        it.ensureMonths(month)
        val pending = it.pending()
        // The month's window and day count, with or without budgets: a preview with any valid limit.
        Overview(it.budgetsOverview(month, today, it.settings().budgetOrder), it.settings().budgetOrder, it.previewBudget(Cents(1), today), pending.size, pending.count { n -> n.isNew })
    }
    Column {
        HomeBar { IconButton(R.drawable.i_mais, { go(Screen.NewBudget) }, Modifier.testTag(BudgetTags.ADD)) }
        if (data == null) return@Column
        val w = data.window
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(BudgetTags.LIST).padding(start = Margem.space.page, end = Margem.space.page, bottom = 32.dp)) {
            val over = monthSpan(w.start, w.end)
            val headline = "Dia ${w.start.daysUntil(today) + 1} de ${w.start.daysUntil(w.end) + 1}."
            val left = today.daysUntil(w.end) + 1
            val dek = if (left == 1) "Falta 1 dia, contando hoje." else "Faltam $left dias, contando hoje."
            PageHeader(over, headline, Modifier.texts(BudgetTags.HEADER, over, headline, dek), dek)
            if (data.pending > 0) PautaStrip(data.pending, data.fresh) { go(Screen.Inbox) }
            val ink = Margem.colors.ink
            Row(
                Modifier.padding(top = 10.dp).drawBehind { drawLine(ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("ORDENAR", sans(11f, 600, 14f, 0.12f))
                OptionPicker(
                    listOf(BudgetOrder.RISK to "risco", BudgetOrder.MANUAL to "manual", BudgetOrder.AZ to "A–Z"), data.order,
                    { scope.launch { db.setBudgetOrder(it) } }, Modifier.weight(1f), tag = "budgets.order.",
                )
            }
            if (data.budgets.isEmpty()) {
                EmptyState(
                    R.drawable.i_verba, "Nenhuma verba ainda.",
                    "Verba é um limite que você escolhe: um nome e uma cota por mês. Cada lançamento entra em uma verba. " +
                        "Comece por onde o dinheiro mais escorre — mercado, por exemplo.",
                    Modifier.testTag(BudgetTags.EMPTY).semantics(mergeDescendants = true) {},
                ) { Button("Nova verba", { go(Screen.NewBudget) }) }
                return@Column
            }
            val month = w.month
            val manual = data.order == BudgetOrder.MANUAL
            // Manual order: long press and drag swaps rows locally; the order is saved on release.
            var local by remember(data.budgets) { mutableStateOf(data.budgets) }
            var dragged by remember { mutableStateOf(BudgetId(0)) }
            var dragY by remember { mutableFloatStateOf(0f) }
            val heights = remember { HashMap<Long, Int>() }
            for (b in local) {
                val id = b.id
                Box(
                    Modifier.onSizeChanged { heights[id.v] = it.height }
                        .zIndex(if (dragged == id) 1f else 0f)
                        .graphicsLayer { translationY = if (dragged == id) dragY else 0f }
                        .then(
                            if (!manual) Modifier else Modifier.pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragged = id; dragY = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragY += amount.y
                                        val i = local.indexOfFirst { it.id == id }
                                        if (dragY > 0 && i < local.size - 1) {
                                            val h = heights[local[i + 1].id.v] ?: 0
                                            if (dragY > h / 2f) { local = local.toMutableList().apply { add(i + 1, removeAt(i)) }; dragY -= h }
                                        } else if (dragY < 0 && i > 0) {
                                            val h = heights[local[i - 1].id.v] ?: 0
                                            if (-dragY > h / 2f) { local = local.toMutableList().apply { add(i - 1, removeAt(i)) }; dragY += h }
                                        }
                                    },
                                    onDragEnd = {
                                        dragged = BudgetId(0); dragY = 0f
                                        val ids = local.map { it.id }
                                        scope.launch { db.reorderBudgets(ids) }
                                    },
                                    onDragCancel = { dragged = BudgetId(0); dragY = 0f; local = data.budgets },
                                )
                            },
                        ),
                ) { BudgetLine(b) { go(Screen.Budget(id, month)) } }
            }
        }
    }
}

/** Book `.strip`: "4 recados na pauta / 3 novos desde a última vez", the only call to action of Verbas. */
@Composable
private fun PautaStrip(count: Int, fresh: Int, onClick: () -> Unit) {
    val c = Margem.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().testTag(BudgetTags.PAUTA_STRIP).clickable(role = Role.Button, onClick = onClick)
            .background(c.surface, shape).border(1.dp, c.rule, shape).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(R.drawable.i_pauta, tint = c.brand)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(if (count == 1) "1 recado na pauta" else "$count recados na pauta", serif(17, 500, 22))
            if (fresh > 0) Text(if (fresh == 1) "1 novo desde a última vez" else "$fresh novos desde a última vez", sans(13f, 400, 17f), color = c.inkSecondary)
        }
        Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) { Icon(R.drawable.i_avancar) }
    }
}

/** Book `.vr`: name and "R$ 477,00 de 465,00", the ruler, the state and the margin. */
@Composable
private fun BudgetLine(b: BudgetSummary, onClick: () -> Unit) {
    val c = Margem.colors
    val m = b.month
    Column(
        Modifier.fillMaxWidth().testTag(BudgetTags.ROW + b.id.v).clickable(onClick = onClick)
            .drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(top = 10.dp, bottom = 9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.padding(end = 8.dp, bottom = 7.dp).size(8.dp).background(budgetColor(b.id), RoundedCornerShape(4.dp)))
            Text(b.name, serif(19, 500, 24), Modifier.weight(1f), maxLines = 1)
            Text(money(m.spent), serif(17, 500, 22, tabular = true))
            Text(" de ${moneyPlain(m.limit)}", sans(12.5f, 400, 18f, tabular = true), color = c.inkTertiary)
        }
        Meter(m, Modifier.padding(top = 9.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusLabel(m.status, empty = m.spent.v == 0L)
            if (m.status == PaceStatus.OVERRUN) Text("${money(m.spent - m.limit)} além", sans(13f, 600, 16f, tabular = true), color = c.overrun)
            else Text("margem ${money(m.remaining)}", sans(13f, 400, 16f, tabular = true), color = c.inkSecondary)
        }
    }
}

// ---- Nova verba and Editar verba ----

/** Nova verba (book 10.7): name, cota mensal and the preview of the weekly steps; [back] after creating. */
@Composable
fun NewBudget(db: Db, back: () -> Unit) = BudgetForm(db, BudgetId(0), back, back)

/** Editar verba: name, cota mensal and "Apagar verba"; [back] after saving, [home] after deleting. */
@Composable
fun EditBudget(db: Db, id: BudgetId, back: () -> Unit, home: () -> Unit) = BudgetForm(db, id, back, home)

private class Loaded(val name: String, val limit: Cents, val count: Int)

/** The one form of Nova verba ([id] 0) and Editar verba. Inputs survive recreation (D19). */
@Composable
private fun BudgetForm(db: Db, id: BudgetId, back: () -> Unit, home: () -> Unit) {
    val today = LocalToday.current()
    val scope = rememberCoroutineScope()
    val isNew = id.v == 0L
    var name by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableLongStateOf(0L) }
    var loaded by rememberSaveable { mutableStateOf(isNew) }
    var nameError by rememberSaveable { mutableStateOf("") }
    var limitError by rememberSaveable { mutableStateOf("") }
    var askLimit by rememberSaveable { mutableStateOf(false) }
    var askDelete by rememberSaveable { mutableStateOf(false) }
    val current = if (isNew) null else query(db, id, today) {
        val month = it.monthOf(today)
        it.ensureMonths(month)
        val d = it.budgetDetail(id, month, today)
        Loaded(d.name, d.month.limit, it.transactions().count { t -> t.budget == id })
    }
    LaunchedEffect(current) {
        if (current == null || loaded) return@LaunchedEffect
        name = current.name
        limit = current.limit.v
        loaded = true
    }
    val preview = if (isNew && limit > 0) query(db, limit, today) { it.previewBudget(Cents(limit), today) } else null

    suspend fun save() {
        if (!db.renameBudget(id, name)) {
            nameError = "Já existe uma verba com esse nome."
            return
        }
        if (Cents(limit) != current?.limit) db.setLimit(id, Cents(limit), today)
        back()
    }

    fun submit() {
        nameError = if (name.isBlank()) "Dê um nome à verba." else ""
        limitError = if (limit <= 0) "A cota precisa ser maior que zero." else ""
        if (nameError.isNotEmpty() || limitError.isNotEmpty()) return
        scope.launch {
            if (isNew) {
                if (db.createBudget(name, Cents(limit), today).v == 0L) nameError = "Já existe uma verba com esse nome." else back()
            } else if (Cents(limit) != current?.limit && db.limitDropsEdits(id, Cents(limit), today)) {
                askLimit = true
            } else {
                save()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (isNew) "Nova verba" else "Editar verba", onBack = back, close = true)
        if (!loaded) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Margem.space.page)) {
            Field(name, { name = it; nameError = "" }, "Nome", placeholder = "Mercado, por exemplo", error = nameError, tag = BudgetTags.FORM_NAME)
            MoneyField(
                Cents(limit), { limit = it.v; limitError = "" }, "Cota mensal", size = 40, error = limitError, tag = BudgetTags.FORM_LIMIT,
                hint = "Quanto a verba permite gastar por mês. A cota recomeça inteira na virada; sobra e excesso não passam adiante.",
            )
            if (preview != null && preview.weeks.isNotEmpty()) StepsPreview(preview, today)
            if (!isNew) Button(
                "Apagar verba", { askDelete = true }, Modifier.padding(top = 24.dp).testTag(BudgetTags.FORM_DELETE),
                kind = ButtonKind.DISMISS, color = Margem.colors.overrun,
            )
        }
        Button(
            if (isNew) "Criar verba" else "Salvar", ::submit,
            Modifier.fillMaxWidth().padding(horizontal = Margem.space.page, vertical = 16.dp).testTag(BudgetTags.FORM_SUBMIT),
        )
    }
    if (askLimit) MargemDialog(
        "Apagar os passos editados?",
        "Com a cota de ${money(Cents(limit))}, os passos que você editou não cabem mais em algum mês. As semanas desses meses voltam ao automático.",
        "Mudar a cota", { askLimit = false; scope.launch { save() } }, { askLimit = false }, tag = BudgetTags.LIMIT_DIALOG,
    )
    if (askDelete && current != null) MargemDialog(
        "Apagar ${current.name}?",
        "A verba tem ${current.count} ${if (current.count == 1) "lançamento" else "lançamentos"}. " +
            "Eles também serão apagados, e os recados deles voltam à Pauta. Não dá para desfazer.",
        "Apagar", { askDelete = false; scope.launch { db.deleteBudget(id); home() } }, { askDelete = false }, tag = BudgetTags.DELETE_DIALOG,
    )
}

/** Book 10.7: the default steps of the month, a rule with today's point in argila, the week ranges. */
@Composable
private fun StepsPreview(bm: BudgetMonth, today: LocalDate) {
    val c = Margem.colors
    val days = bm.start.daysUntil(bm.end) + 1
    val title = "Passos semanais de ${monthLong(bm.start.month)}"
    val rate = "${money(Cents(bm.limit.v / days))} por dia · semana cheia ${money(Cents(bm.limit.v * 7 / days))}"
    val cells = bm.weeks.flatMap { listOf(weekRange(it), stepValue(it.step)) }
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().texts(BudgetTags.FORM_PREVIEW, title, rate, *cells.toTypedArray())) {
        Text(title.uppercase(), sans(11f, 600, 14f, 0.12f), color = c.inkTertiary)
        Text(rate, serif(17, 500, 22, tabular = true), Modifier.padding(top = 4.dp))
        val todayAt = (bm.start.daysUntil(today) + 0.5f) / days
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().drawBehind {
                val y = 22.dp.toPx()
                drawLine(c.ink, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                if (today in bm.start..bm.end) drawCircle(c.brand, 4.dp.toPx(), Offset(todayAt * size.width, y))
            },
        ) {
            for (w in bm.weeks) Column(
                Modifier.weight((w.start.daysUntil(w.end) + 1).toFloat()).drawBehind {
                    drawLine(c.ink, Offset(0f, 18.dp.toPx()), Offset(0f, 26.dp.toPx()), 1.dp.toPx())
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stepValue(w.step), sans(11f, 600, 14f, tabular = true), maxLines = 1)
                Gap(18.dp)
                Text(weekRange(w), sans(10.5f, 600, 14f), color = c.inkSecondary, maxLines = 1)
            }
        }
        Text(
            "Cota × dias da semana no mês ÷ dias do mês. Só mostram quanto cabe; dá para editar cada semana no detalhe da verba.",
            sans(13.5f, 400, 18f), Modifier.padding(top = 8.dp), c.inkTertiary,
        )
        if (today > bm.start) Text(
            "Criada hoje, a verba já tem a cota cheia de ${monthLong(bm.start.month)}: o ritmo ideal de hoje conta as semanas passadas (${money(bm.ideal)}).",
            sans(13.5f, 400, 18f), Modifier.padding(top = 12.dp), c.inkTertiary,
        )
    }
}

// ---- Teto ----

/** Book 8.10: the ceiling line and its legend, or the dotted line with "Pôr um teto". Tags: [BudgetTags.CEILING]. */
@Composable
private fun CeilingLine(ceiling: Ceiling): Unit = TODO()

/** The ceiling dialog: value, then "Vale para quais meses?"; saving shows a snack with Desfazer. Tags: [BudgetTags.CEILING_DIALOG]. */
@Composable
private fun CeilingDialog(db: Db, ceiling: Ceiling, snack: SnackHost, close: () -> Unit): Unit = TODO()

// ---- Detalhe da verba ----

private class DetailData(val detail: BudgetDetail, val current: YearMonth)

/**
 * Detalhe da verba (spec §7.1, book 10.8) in [month]: month picker ([onMonth] switches the month),
 * headline, tall meter, weekly steps with their edit dialog, the month's lines with the running sum,
 * and "Editar".
 */
@Composable
fun BudgetDetailScreen(db: Db, id: BudgetId, month: YearMonth, go: (Screen) -> Unit, onMonth: (YearMonth) -> Unit, back: () -> Unit) {
    val c = Margem.colors
    val today = LocalToday.current()
    val data = query(db, id, month, today) {
        it.ensureMonths(month)
        DetailData(it.budgetDetail(id, month, today), it.monthOf(today))
    }
    // The tapped week, by its start; survives recreation.
    var editing by rememberSaveable(month) { mutableStateOf("") }
    val d = data?.detail
    Column(Modifier.fillMaxSize()) {
        TopBar(d?.name ?: "", Modifier.testTag(BudgetTags.DETAIL_TITLE).semantics(mergeDescendants = true) {}, onBack = back) {
            if (d != null && d.id.v != 0L) Button("Editar", { go(Screen.EditBudget(id)) }, Modifier.testTag(BudgetTags.DETAIL_EDIT), kind = ButtonKind.TEXT)
        }
        if (data == null || d == null || d.id.v == 0L) return@Column
        val bm = d.month
        val isCurrent = month == data.current
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Margem.space.page, end = Margem.space.page, bottom = 32.dp)) {
            val span = if (bm.start.month == bm.end.month) "${bm.start.day} a ${bm.end.day}"
            else "${bm.start.day} ${monthShort(bm.start.month)} a ${bm.end.day} ${monthShort(bm.end.month)}"
            MonthPicker(
                month, if (isCurrent) "$span · mês atual" else span, { onMonth(month.minusMonth()) }, { onMonth(month.plusMonth()) },
                canNext = month < data.current, canPrev = month > d.firstMonth, tag = "budget.month",
            )
            Headline(bm, today)
            Meter(bm, Modifier.padding(top = 8.dp).testTag(BudgetTags.DETAIL_METER), tall = true)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("R$ 0", sans(12f, 600, 16f, tabular = true), Modifier.weight(1f), c.inkTertiary)
                Text("cota ${money(bm.limit)}", sans(12f, 600, 16f, tabular = true), Modifier.padding(end = 10.dp), c.inkTertiary)
                if (bm.spent > bm.limit) Text("+ ${moneyPlain(bm.spent - bm.limit)}", sans(12f, 600, 16f, tabular = true), color = c.overrun)
            }
            SectionLine("Passos semanais", aside = "toque para editar")
            StepsEditor(bm.weeks, today, { editing = it.start.toString() }, tag = BudgetTags.WEEK)
            SectionLine("Lançamentos do mês", aside = "valor · soma")
            if (d.lines.isEmpty()) Text(
                "Nenhum lançamento neste mês.", Margem.type.body,
                Modifier.padding(top = 12.dp).testTag(BudgetTags.LINES_EMPTY), c.inkSecondary,
            )
            var sum = 0L
            var cut = false
            for (line in d.lines) {
                if (!line.row.refunded) sum += line.cents.v
                MonthLineRow(line, sum, sum > bm.limit.v) { go(Screen.Edit(line.row.id)) }
                if (!cut && bm.limit.v > 0 && sum >= bm.limit.v) {
                    cut = true
                    CutLine()
                }
            }
        }
    }
    val week = d?.month?.weeks?.firstOrNull { it.start.toString() == editing }
    if (week != null && !week.locked) StepDialog(db, id, month, week) { editing = "" }
}

/** Book 10.8 headline: the fact and the number, in the tone of a newspaper. */
@Composable
private fun Headline(bm: BudgetMonth, today: LocalDate) {
    val past = bm.end < today
    val next = bm.end.plus(1, DateTimeUnit.DAY)
    val (headline, dek) = when {
        bm.status == PaceStatus.OVERRUN -> "${money(bm.spent - bm.limit)} além da cota." to
            "Você lançou ${money(bm.spent)} de ${money(bm.limit)}. Nada foi bloqueado." +
            (if (past) "" else " O mês recomeça em ${next.day} de ${monthLong(next.month)}, com a cota inteira.")
        past -> "Sobraram ${money(bm.remaining)}." to "Você lançou ${money(bm.spent)} de ${money(bm.limit)}."
        bm.status == PaceStatus.AHEAD -> "Sobram ${money(bm.remaining)}." to
            "Você lançou ${money(bm.spent)} de ${money(bm.limit)}, ${money(bm.spent - bm.ideal)} acima do ritmo ideal de hoje."
        else -> "Sobram ${money(bm.remaining)}." to "Você lançou ${money(bm.spent)} de ${money(bm.limit)}. No ritmo: o ideal até hoje é ${money(bm.ideal)}."
    }
    Column(Modifier.padding(top = 2.dp).testTag(BudgetTags.DETAIL_HEADLINE).semantics(mergeDescendants = true) {}) {
        Text(headline, serif(29, 400, 33, tracking = -0.01f), Modifier.padding(bottom = 6.dp))
        Text(dek, sans(15f, 400, 22f), color = Margem.colors.inkSecondary)
    }
}

/** Book 10.8 `.lrow`: name, "qui, 1 · tags", the installment's value and the running sum. */
@Composable
private fun MonthLineRow(line: MonthLine, sum: Long, over: Boolean, onClick: () -> Unit) {
    val c = Margem.colors
    val r = line.row
    val sub = buildList {
        add("${weekdayShort(line.date.dayOfWeek)}, ${line.date.day}")
        if (r.tags.isNotEmpty()) add(r.tags.joinToString(", "))
        if (r.installments > 1) add("Parcela ${line.k} de ${r.installments}")
        if (r.refunded) add("estornada")
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().testTag(BudgetTags.LINE + r.id.v).clickable(onClick = onClick)
            .drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            AliasText(r.name, serif(17, 500, 22), alias = r.alias.isNotEmpty(), color = if (r.refunded) c.inkTertiary else c.ink)
            Text(sub, sans(13f, 400, 17f), Modifier.padding(top = 2.dp), c.inkTertiary, maxLines = 2)
        }
        Column(horizontalAlignment = Alignment.End) {
            Amount(line.cents, size = 17, color = if (r.refunded) c.inkTertiary else c.ink, struck = r.refunded)
            Text(
                moneyPlain(Cents(sum)), sans(12f, 500, 16f, tabular = true),
                Modifier.testTag(BudgetTags.LINE_SUM + r.id.v).semantics(mergeDescendants = true) {},
                if (over) c.overrun else c.inkTertiary,
            )
        }
    }
}

/** Book `.cutline`: a dashed rule, "cota atingida aqui". */
@Composable
private fun CutLine() {
    val c = Margem.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag(BudgetTags.CUT).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("COTA ATINGIDA AQUI", sans(10.5f, 600, 14f, 0.12f), color = c.overrun, modifier = Modifier.clearAndSetSemantics { text = AnnotatedString("cota atingida aqui") })
        Canvas(Modifier.weight(1f).height(1.dp)) {
            drawLine(c.overrun, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        }
    }
}

/** The step of one week: value, "no máximo R$ X", Salvar, and Voltar ao automático on an edited week. */
@Composable
private fun StepDialog(db: Db, id: BudgetId, month: YearMonth, week: Week, close: () -> Unit) {
    val c = Margem.colors
    val scope = rememberCoroutineScope()
    var value by rememberSaveable(week.start.toString()) { mutableLongStateOf(week.step.v) }
    val tooMuch = value > week.max.v
    var refused by remember { mutableStateOf(false) }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.testTag(BudgetTags.STEP_DIALOG).padding(horizontal = 32.dp).widthIn(max = 340.dp).fillMaxWidth()
                .shadow(14.dp, shape, ambientColor = c.shadow, spotColor = c.shadow).background(c.surface, shape)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
        ) {
            val range = if (week.start == week.end) "${week.start.day} de ${monthLong(week.start.month)}"
            else "${week.start.day} a ${week.end.day} de ${monthLong(week.end.month)}"
            Text("Passo de $range", serif(22, 500, 28))
            MoneyField(Cents(value), { value = it.v; refused = false }, "Passo da semana", size = 30, tag = BudgetTags.STEP_FIELD)
            Text(
                "no máximo ${money(week.max)}", sans(13.5f, if (refused && tooMuch) 600 else 400, 18f),
                Modifier.testTag(BudgetTags.STEP_MAX), if (refused && tooMuch) c.overrun else c.inkTertiary,
            )
            if (week.edited) Button(
                "Voltar ao automático", { scope.launch { db.clearStep(id, month, week.start); close() } },
                Modifier.padding(top = 12.dp).testTag(BudgetTags.STEP_AUTO), kind = ButtonKind.TEXT,
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                Button("Cancelar", close, Modifier.testTag(BudgetTags.STEP_CANCEL), kind = ButtonKind.TEXT)
                Button("Salvar", {
                    if (tooMuch) refused = true
                    else scope.launch { if (db.setStep(id, month, week.start, Cents(value))) close() else refused = true }
                }, Modifier.testTag(BudgetTags.STEP_SAVE), kind = ButtonKind.TEXT)
            }
        }
    }
}
