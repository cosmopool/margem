package com.kaiodelphino.margem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kaiodelphino.margem.BudgetId
import com.kaiodelphino.margem.BudgetOrder
import com.kaiodelphino.margem.BudgetSummary
import com.kaiodelphino.margem.Cents
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.NoticeId
import com.kaiodelphino.margem.NoticeRow
import com.kaiodelphino.margem.NoticeStatus
import com.kaiodelphino.margem.NO_MONTH
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.Suggestion
import com.kaiodelphino.margem.TransactionDetail
import com.kaiodelphino.margem.TransactionForm
import com.kaiodelphino.margem.TransactionId
import com.kaiodelphino.margem.TransactionRow
import com.kaiodelphino.margem.widgets.AliasText
import com.kaiodelphino.margem.widgets.Amount
import com.kaiodelphino.margem.widgets.Button
import com.kaiodelphino.margem.widgets.ButtonKind
import com.kaiodelphino.margem.widgets.ChipState
import com.kaiodelphino.margem.widgets.DateTimeDialog
import com.kaiodelphino.margem.widgets.DayHeader
import com.kaiodelphino.margem.widgets.Emenda
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Field
import com.kaiodelphino.margem.widgets.Icon
import com.kaiodelphino.margem.widgets.MargemDialog
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.MoneyField
import com.kaiodelphino.margem.widgets.Snack
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.Stepper
import com.kaiodelphino.margem.widgets.TagChip
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.dateLong
import com.kaiodelphino.margem.widgets.dateShort
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.sans
import com.kaiodelphino.margem.widgets.serif
import com.kaiodelphino.margem.widgets.time
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Test tags of Lançamentos and of the one form of Lançar / editar / lançamento manual (D35). Same setup
 * and rules as [BudgetTags] and [InboxTags]; the date and time dialog is [com.kaiodelphino.margem.widgets.DateTimeTags].
 *
 * Margins in texts: `money(c)` when c ≥ 0, `"${money(-c)} além"` when negative ("R$ 12,00 além").
 *
 * The form opens three ways:
 * - **Lançar** (Screen.Post, from InboxTags.ACCEPT): prefilled from the notice — total = Parsed.cents,
 *   bank text = Parsed.merchant, date and time = Parsed.date/time, alias = NoticeRow.alias — plus the
 *   suggestion (Db.suggestion of the parsed merchant, spec §2.5): its budget selected and its tags selected
 *   and suggested. A notice that looks like an ad opens with zero total and blank merchant.
 * - **Editar** (Screen.Edit, from [ROW] or BudgetTags.LINE): the transaction as it is (Db.transaction):
 *   total, merchant, date, time, budget, installments, its tags selected (not suggested), alias of its
 *   merchant. A transaction from a notice shows the notice and the emendas against its Parsed.
 * - **Novo lançamento** (Screen.NewTransaction, from [NEW]): empty; today (LocalToday) and the current
 *   time of the system clock to the minute; no notice, no suggestion.
 *
 * Every input survives Activity recreation and process death (rememberSaveable): total, bank text,
 * alias, name, date, time, budget, installments, tags and their suggested/confirmed state.
 */
object TransactionTags {
    // ---- Lançamentos (spec §7.4, book 10.5) ----

    /** "Novo lançamento": opens the empty form. */
    const val NEW = "transactions.new"

    /** The scrollable list. Plain container. */
    const val LIST = "transactions.list"

    /** Instead of the rows when there are no transactions. */
    const val EMPTY = "transactions.empty"

    /** Prefix: DAY + date ("transactions.day.2026-10-08"): [dayTitle] of each purchase date with rows, newest first. */
    const val DAY = "transactions.day."

    /**
     * Prefix: ROW + transaction id, one per Db.transactions(today), in that order, under the day of its
     * purchase date. Headline: the legenda in italic (TransactionRow.caption) when set, else the name
     * (alias, else bank text); with a legenda, the name moves to an "em …" credit line under it (2 lines
     * for the headline, "…" when it passes — the alias underline is on the credit). "Lazer · bar, amigos"
     * (budget · tags joined by ", "), then "‸ emendado" when corrected, "Parcela 3 de 10" when in
     * installments with one in today's month (TransactionRow.now) or "10×" when it has none there, and
     * "estornada" when refunded (headline and credit in ink 3, unstruck; only the value struck). The
     * value is the installment of today's month (now.cents) for an installment purchase with one there,
     * else the total; struck through when refunded. Click opens the edit form.
     */
    const val ROW = "transactions.row."

    /**
     * Prefix: UNDO_REFUND + transaction id, on refunded rows only: "Desfazer estorno" (Db.undoRefund with
     * row.estorno). The row stops being refunded; the snack "Estorno desfeito: a compra volta a contar." appears.
     */
    const val UNDO_REFUND = "transactions.undoRefund."

    // ---- The form (spec §7.3, book 10.3) ----

    /** The top bar: "Lançar recado", "Editar lançamento" or "Novo lançamento". */
    const val FORM_TITLE = "txForm.title"

    /** Post and Edit of a transaction from a notice: "Cartão Ipê · qui, 8 out, 23:10" and the bank text in quotes. Absent otherwise. */
    const val FORM_NOTICE = "txForm.notice"

    /** Valor total: money input (digits as cents, like BudgetTags.FORM_LIMIT). */
    const val FORM_TOTAL = "txForm.total"

    /**
     * Present while the total differs from the notice's Parsed.cents (form from a notice): "‸ emendado",
     * "o recado dizia" and money of Parsed.cents. "$FORM_TOTAL_EMENDA.undo" ("Desfazer") puts it back.
     */
    const val FORM_TOTAL_EMENDA = "txForm.total.emenda"

    /** Form from a notice: the alias input (Apelido), prefilled with the merchant's alias ("" when none). */
    const val FORM_ALIAS = "txForm.alias"

    /**
     * Form from a notice: "No banco: BAR E REST PONTO CERTO" (the current bank text). Click turns it into
     * [FORM_BANK_INPUT], prefilled with that text; FORM_BANK is then absent.
     */
    const val FORM_BANK = "txForm.bank"
    const val FORM_BANK_INPUT = "txForm.bank.input"

    /** Present while the bank text differs from Parsed.merchant; "$FORM_BANK_EMENDA.undo" puts it back (and closes the input). */
    const val FORM_BANK_EMENDA = "txForm.bank.emenda"

    /** Manual (new, or editing one with no notice): the one name input, saved as the merchant. Absent in forms from a notice. */
    const val FORM_NAME = "txForm.name"

    /**
     * "+ Legenda · só deste lançamento" (spec §2.12, D57): the collapsed link under the merchant field —
     * under "No banco" in a form from a notice, under [FORM_NAME] when manual. Click opens [FORM_CAPTION]
     * in its place. Absent while the caption field is open or the caption is set.
     */
    const val FORM_CAPTION_OPEN = "txForm.caption.open"

    /**
     * The legenda input, italic, shown while open (D57). Hint "Aparece no lugar de Padaria do Zé, só neste
     * lançamento."; with a caption saved, the edit form opens with it visible. Accepts at most 80
     * characters (`String.length`; the input cuts, never splitting an astral emoji). Saves the caption;
     * blank (or [FORM_CAPTION_CLEAR]) and Salvar removes it, unlike the alias.
     */
    const val FORM_CAPTION = "txForm.caption"

    /** The ✕ of the caption field: empties it and folds the field back. In edit, [FORM_CAPTION_REMOVED] appears. */
    const val FORM_CAPTION_CLEAR = "txForm.caption.clear"

    /** The "72/80" counter at the right of the hint, shown from 70 characters (D57); at 80 the input stops accepting. */
    const val FORM_CAPTION_COUNT = "txForm.caption.count"

    /**
     * "legenda tirada: ~~Bolo do aniversário da Ana~~" (edit only, until Salvar; D57): present while the
     * saved caption was cleared in this edit. "$FORM_CAPTION_REMOVED.undo" ("Desfazer") puts the text back
     * and reopens the field. Typing new text also makes it go away.
     */
    const val FORM_CAPTION_REMOVED = "txForm.caption.removed"

    /** "quinta, 8 de outubro · 23:10" (dateLong · time). Click opens the date and time dialog (DateTimeTags). */
    const val FORM_DATE = "txForm.date"

    /** Present while date or time differ from the notice's Parsed; "$FORM_DATE_EMENDA.undo" puts both back. */
    const val FORM_DATE_EMENDA = "txForm.date.emenda"

    /**
     * Prefix: BUDGET + budget id: one option per budget (manual order), `selected` when chosen. Click
     * selects it; exactly one or none is selected.
     */
    const val BUDGET = "txForm.budget."

    /** No budgets at all: "Crie uma verba antes de lançar." with "Nova verba" ([BUDGETS_NEW] opens it). */
    const val BUDGETS_EMPTY = "txForm.budgets.empty"
    const val BUDGETS_NEW = "txForm.budgets.new"

    /** Parcelas: the number (1..400) as text; [INSTALLMENTS_MINUS] / [INSTALLMENTS_PLUS] change it by one and are disabled at the ends. */
    const val INSTALLMENTS = "txForm.installments"
    const val INSTALLMENTS_MINUS = "txForm.installments.minus"
    const val INSTALLMENTS_PLUS = "txForm.installments.plus"

    /**
     * With 2 or more installments and total > 0: "10 × R$ 120,00" (n × money(total / n rounded down)),
     * plus " · última R$ 333,34" when the last one absorbs cents (spec §6). Absent otherwise.
     */
    const val INSTALLMENTS_PREVIEW = "txForm.installments.preview"

    /**
     * Prefix: TAG + tag name ("txForm.tag.padaria"): one chip per existing tag (Db.tags) and per name
     * suggested or added here, by name. `selected` when it counts. A suggested one (spec §2.5) is selected
     * with stateDescription "sugerido" (dashed); click confirms it (selected, no stateDescription); click
     * on a confirmed one unselects it; click on an unselected one selects it.
     */
    const val TAG = "txForm.tag."

    /**
     * "+ novo": shows [TAG_INPUT]. Text typed there and confirmed with [TAG_ADD] (or the keyboard's done)
     * becomes a selected chip, trimmed and lowercased; an existing name selects that chip; blank does
     * nothing. The tag is written only when the form is saved. The input closes after adding.
     */
    const val TAG_NEW = "txForm.tag.new"
    const val TAG_INPUT = "txForm.tag.input"
    const val TAG_ADD = "txForm.tag.add"

    /**
     * "Lazer: margem R$ 34,00 → R$ 12,00 além" (Db.previewAccept for the chosen budget, date, total and
     * installments; in Editar, replacing the transaction). Present while a budget is chosen and total > 0.
     */
    const val FORM_PREVIEW = "txForm.preview"

    /**
     * What is missing, while something is: "Falta " + the missing ones among "o valor" (total 0), "a
     * verba", "um marcador", joined with ", " and a final " e ", + "." — e.g. "Falta a verba e um
     * marcador.". Absent when nothing is missing.
     */
    const val FORM_MISSING = "txForm.missing"

    /**
     * Lançar: "Lançar R$ 46,00" (money of the total); Novo lançamento the same; Editar: "Salvar".
     * Disabled while [FORM_MISSING] is shown; a second click while saving does nothing.
     * - Lançar: Db.accept; back to the Pauta with the snack "Lançado em Lazer. Margem: R$ 12,00 além." (the
     *   preview's after) and "Desfazer" (deletes it: the notice is pending again). Refused: InboxTags.GONE_TEXT, back.
     * - Novo lançamento: Db.addManual; back to Lançamentos with the same snack and "Desfazer".
     * - Editar: Db.edit; back. Refused (the transaction is gone): "Este lançamento não existe mais.", back.
     * A non-blank alias is saved with it (TransactionForm.alias); blank leaves the alias as it is.
     */
    const val FORM_SUBMIT = "txForm.submit"

    /** Lançar only: "Riscar" — dismisses the notice; back to the Pauta with "Recado riscado." and "Desfazer". */
    const val FORM_DISMISS = "txForm.dismiss"

    /** Lançar only: "É um estorno? Registrar estorno" — replaces the form with Registrar estorno of the same notice. */
    const val FORM_AS_REFUND = "txForm.asRefund"

    /** Editar of a refunded transaction: "estornada" and [FORM_UNDO_REFUND] "Desfazer estorno" (Db.undoRefund; the line goes away). */
    const val FORM_REFUNDED = "txForm.refunded"
    const val FORM_UNDO_REFUND = "txForm.undoRefund"

    /** Editar only: "Apagar lançamento", opens [DELETE_DIALOG]. */
    const val FORM_DELETE = "txForm.delete"

    /**
     * "Apagar o lançamento?" saying what happens (spec §2.8): from a notice, "O recado volta à Pauta." (and,
     * when refunded, that the estorno notice goes back too); manual, that it simply goes away. No undo.
     * Confirm deletes it (Db.deleteTransaction) and goes back; cancel closes the dialog.
     */
    const val DELETE_DIALOG = "txForm.deleteDialog"
    const val DELETE_DIALOG_CONFIRM = "txForm.deleteDialog.confirm"
    const val DELETE_DIALOG_CANCEL = "txForm.deleteDialog.cancel"
}

/** Lançamentos (spec §7.4, book 10.5): every transaction grouped by day, with Desfazer estorno. Tags: [TransactionTags]. */
@Composable
fun Transactions(db: Db, snack: SnackHost, go: (Screen) -> Unit) {
    val today = LocalToday.current()
    val scope = rememberCoroutineScope()
    val rows = query(db, today) { it.transactions(today) }
    Column {
        TopBar("Lançamentos") {
            Button("Novo lançamento", { go(Screen.NewTransaction) }, Modifier.testTag(TransactionTags.NEW), kind = ButtonKind.TEXT, icon = R.drawable.i_mais)
        }
        LazyColumn(Modifier.testTag(TransactionTags.LIST), contentPadding = PaddingValues(start = Margem.space.page, end = Margem.space.page, bottom = 32.dp)) {
            if (rows == null) return@LazyColumn
            if (rows.isEmpty()) item {
                EmptyState(
                    R.drawable.i_lancamento, "Nenhum lançamento ainda.",
                    "Cada recado que você lança da Pauta vira uma linha aqui. Compras sem recado entram como lançamento manual.",
                    Modifier.testTag(TransactionTags.EMPTY).semantics(mergeDescendants = true) {},
                )
            }
            for ((day, list) in rows.groupBy { it.date }) {
                item(key = "day-$day") { DayHeader(dayTitle(day, today), Modifier.testTag(TransactionTags.DAY + day).semantics(mergeDescendants = true) {}) }
                items(list, key = { it.id.v }) { t ->
                    TransactionLine(t, { go(Screen.Edit(t.id)) }) {
                        scope.launch { if (db.undoRefund(t.estorno)) snack.show(Snack("Estorno desfeito: a compra volta a contar.")) }
                    }
                }
            }
        }
    }
}

/** Book 10.5 `.lrow`: name, "Lazer · bar, amigos · ‸ emendado · Parcela 1 de 10", the value; refunded: struck, with Desfazer estorno. */
@Composable
private fun TransactionLine(t: TransactionRow, onClick: () -> Unit, onUndoRefund: () -> Unit) {
    val c = Margem.colors
    val split = t.installments > 1
    val sub = buildList {
        add(t.budgetName)
        if (t.tags.isNotEmpty()) add(t.tags.joinToString(", "))
        if (t.corrected) add("‸ emendado")
        if (split) add(if (t.now.k > 0) "Parcela ${t.now.k} de ${t.installments}" else "${t.installments}×")
        if (t.refunded) add("estornada")
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().testTag(TransactionTags.ROW + t.id.v).clickable(onClick = onClick)
            .drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            AliasText(t.name, serif(17, 500, 22), alias = t.alias.isNotEmpty(), color = if (t.refunded) c.inkTertiary else c.ink)
            Text(sub, sans(13f, 400, 17f), Modifier.padding(top = 2.dp), c.inkTertiary, maxLines = 2)
            if (t.refunded) Button(
                "Desfazer estorno", onUndoRefund, Modifier.testTag(TransactionTags.UNDO_REFUND + t.id.v), kind = ButtonKind.TEXT, small = true,
                icon = R.drawable.i_desfazer,
            )
        }
        val value = if (split && t.now.k > 0) t.now.cents else t.total
        Amount(value, size = 17, color = if (t.refunded) c.inkTertiary else c.ink, struck = t.refunded)
    }
}

/** Lançar recado (book 10.3) for the pending notice [notice]. [replace] swaps this screen (Registrar estorno). */
@Composable
fun PostScreen(db: Db, notice: NoticeId, snack: SnackHost, go: (Screen) -> Unit, replace: (Screen) -> Unit, back: () -> Unit) =
    TxForm(db, notice, TransactionId(0), snack, go, replace, back)

/** Editar lançamento: the same form, with Apagar and Desfazer estorno. */
@Composable
fun EditTransaction(db: Db, id: TransactionId, snack: SnackHost, go: (Screen) -> Unit, back: () -> Unit) =
    TxForm(db, NoticeId(0), id, snack, go, {}, back)

/** Novo lançamento: the manual form. */
@Composable
fun NewTransaction(db: Db, snack: SnackHost, go: (Screen) -> Unit, back: () -> Unit) =
    TxForm(db, NoticeId(0), TransactionId(0), snack, go, {}, back)

private const val GONE_TX = "Este lançamento não existe mais."

/** What the form reads once and again on every write: the notice or transaction, budgets, tags, week start. */
private class FormData(
    val notice: NoticeRow, val detail: TransactionDetail, val budgets: List<BudgetSummary>, val tags: List<String>,
    val weekStart: DayOfWeek, val suggestion: Suggestion,
)

/** A chip's state in the form: S = suggested (counts, dashed), C = confirmed (counts), O = off. Saved as "S:name". */
private fun chipsOf(list: List<String>): Map<String, Char> = list.associate { it.drop(2) to it[0] }

private fun chipList(map: Map<String, Char>): ArrayList<String> = ArrayList(map.map { (name, st) -> "$st:$name" })

/** "Falta a verba e um marcador." — [TransactionTags.FORM_MISSING]. */
private fun missingText(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> "Falta ${items[0]}."
    else -> "Falta ${items.dropLast(1).joinToString(", ")} e ${items.last()}."
}

/**
 * The one form of Lançar ([notice] ≠ 0), Editar ([edit] ≠ 0) and Novo lançamento (both 0), book 10.3.
 * Inputs survive recreation (D19); the first load fills them once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TxForm(
    db: Db, notice: NoticeId, edit: TransactionId, snack: SnackHost, go: (Screen) -> Unit, replace: (Screen) -> Unit, back: () -> Unit,
) {
    val c = Margem.colors
    val today = LocalToday.current()
    val scope = rememberCoroutineScope()
    val isPost = notice.v != 0L
    val isEdit = edit.v != 0L
    val data = query(db, notice, edit, today) {
        val month = it.monthOf(today)
        it.ensureMonths(month)
        val n = if (isPost) it.notice(notice) else NoticeRow()
        val d = if (isEdit) it.transaction(edit) else TransactionDetail()
        FormData(
            n, d, it.budgetsOverview(month, today, BudgetOrder.MANUAL), it.tags().map { t -> t.name }, it.settings().weekStart,
            if (isPost) it.suggestion(n.parsed.merchant) else Suggestion(),
        )
    }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var total by rememberSaveable { mutableLongStateOf(0L) }
    var merchant by rememberSaveable { mutableStateOf("") }
    var editingBank by rememberSaveable { mutableStateOf(false) }
    var alias by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var hour by rememberSaveable { mutableStateOf("00:00") }
    var budget by rememberSaveable { mutableLongStateOf(0L) }
    var installments by rememberSaveable { mutableIntStateOf(1) }
    var chips by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var addingTag by rememberSaveable { mutableStateOf(false) }
    var newTag by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }
    var askDelete by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(data) {
        if (data == null || loaded) return@LaunchedEffect
        when {
            isPost -> {
                val n = data.notice
                if (n.id.v == 0L || n.status != NoticeStatus.PENDING) {
                    snack.show(Snack(InboxTags.GONE_TEXT))
                    back()
                    return@LaunchedEffect
                }
                total = n.parsed.cents.v
                merchant = n.parsed.merchant
                alias = n.alias
                date = n.parsed.date.toString()
                hour = time(n.parsed.time)
                val sug = data.suggestion
                if (data.budgets.any { it.id == sug.budget }) budget = sug.budget.v
                chips = chipList(sug.tags.associateWith { 'S' })
            }
            isEdit -> {
                val r = data.detail.row
                if (r.id.v == 0L) {
                    snack.show(Snack(GONE_TX))
                    back()
                    return@LaunchedEffect
                }
                total = r.total.v
                merchant = r.merchant
                alias = r.alias
                date = r.date.toString()
                hour = time(r.time)
                budget = r.budget.v
                installments = r.installments
                chips = chipList(r.tags.associateWith { 'C' })
            }
            else -> {
                val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
                hour = time(LocalTime(now.hour, now.minute))
            }
        }
        loaded = true
    }

    val day = LocalDate.parse(date)
    val at = LocalTime.parse(hour)
    val states = chipsOf(chips)
    val selectedTags = states.filterValues { it != 'O' }.keys.toList()
    // The notice this form is about (Lançar, or Editar of a transaction from a notice): emendas compare with it.
    val source = if (isPost) data?.notice else data?.detail?.notice?.takeIf { it.id.v != 0L }
    val parsed = source?.parsed
    val budgetName = data?.budgets?.firstOrNull { it.id.v == budget }?.name.orEmpty()
    val preview = if (budget != 0L && total > 0 && loaded) query(db, budget, date, total, installments, edit, today) {
        it.previewAccept(BudgetId(budget), day, Cents(total), installments, today, edit)
    } else null
    val missing = buildList {
        if (total <= 0) add("o valor")
        // A budget deleted while the form is open stops counting as chosen.
        if (data?.budgets?.none { it.id.v == budget } != false) add("a verba")
        if (selectedTags.isEmpty()) add("um marcador")
    }

    fun form() = TransactionForm(BudgetId(budget), selectedTags, Cents(total), installments, merchant, day, at, alias)

    fun submit() {
        if (busy || missing.isNotEmpty()) return
        busy = true
        scope.launch {
            val f = form()
            val margin = db.previewAccept(f.budget, f.date, f.total, f.installments, today, edit).after
            when {
                isEdit -> {
                    if (!db.edit(edit, f)) snack.show(Snack(GONE_TX))
                }
                else -> {
                    val id = if (isPost) db.accept(notice, f) else db.addManual(f)
                    if (id.v == 0L) snack.show(Snack(InboxTags.GONE_TEXT))
                    else snack.show(Snack("Lançado em $budgetName. Margem: ${marginText(margin)}.", "Desfazer") { db.deleteTransaction(id) })
                }
            }
            back()
        }
    }

    fun tapChip(name: String) {
        val st = states[name] ?: 'O'
        // Suggested → confirmed, off → confirmed, confirmed → off (spec §2.5).
        chips = chipList(states + (name to if (st == 'C') 'O' else 'C'))
    }

    fun addTag() {
        val name = newTag.trim().lowercase()
        if (name.isEmpty()) return
        chips = chipList(states + (name to 'C'))
        newTag = ""
        addingTag = false
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            if (isPost) "Lançar recado" else if (isEdit) "Editar lançamento" else "Novo lançamento",
            Modifier.testTag(TransactionTags.FORM_TITLE).semantics(mergeDescendants = true) {}, onBack = back, close = true,
        )
        if (data == null || !loaded) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Margem.space.page, end = Margem.space.page, bottom = 24.dp)) {
            if (source != null) NoticeQuote(source, Modifier.testTag(TransactionTags.FORM_NOTICE).semantics(mergeDescendants = true) {})
            MoneyField(Cents(total), { total = it.v }, "Valor total", size = 40, tag = TransactionTags.FORM_TOTAL)
            if (parsed != null && Cents(total) != parsed.cents) Emenda(parsed.cents, { total = parsed.cents.v }, TransactionTags.FORM_TOTAL_EMENDA)

            if (source != null) {
                Field(
                    alias, { alias = it }, "Estabelecimento", placeholder = merchant.ifEmpty { "Apelido" }, alias = alias.isNotBlank(),
                    hint = "O apelido vale para este e os próximos lançamentos.", tag = TransactionTags.FORM_ALIAS,
                ) { Icon(R.drawable.i_apelido, tint = c.brand, size = 20.dp) }
                if (editingBank) Field(merchant, { merchant = it }, "No banco", tag = TransactionTags.FORM_BANK_INPUT)
                else Text(
                    "No banco: ${merchant.ifEmpty { "—" }}", sans(13.5f, 400, 18f),
                    Modifier.testTag(TransactionTags.FORM_BANK).clickable(role = Role.Button) { editingBank = true }.padding(vertical = 8.dp),
                    c.inkSecondary,
                )
                if (parsed != null && merchant.trim() != parsed.merchant) Emenda(parsed.merchant.ifEmpty { "—" }, {
                    merchant = parsed.merchant
                    editingBank = false
                }, TransactionTags.FORM_BANK_EMENDA)
            } else {
                Field(merchant, { merchant = it }, "Estabelecimento", placeholder = "Feira, por exemplo", tag = TransactionTags.FORM_NAME)
            }

            Column(Modifier.padding(top = 8.dp).fillMaxWidth().testTag(TransactionTags.FORM_DATE).clickable(role = Role.Button) { picking = true }) {
                Text("DATA", sans(11f, 600, 14f, 0.12f), color = c.inkTertiary)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .drawBehind { drawLine(c.ruleStrong, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${dateLong(day)} · $hour", serif(19, 400, 24, tabular = true), Modifier.weight(1f))
                    Icon(R.drawable.i_periodo, tint = c.inkTertiary, size = 20.dp)
                }
            }
            if (parsed != null && (parsed.date != day || parsed.time != at)) Emenda("${dateShort(parsed.date)}, ${time(parsed.time)}", {
                date = parsed.date.toString()
                hour = time(parsed.time)
            }, TransactionTags.FORM_DATE_EMENDA)

            Text("VERBA", sans(11f, 600, 14f, 0.12f), Modifier.padding(top = 18.dp), c.inkTertiary)
            if (data.budgets.isEmpty()) Column(Modifier.testTag(TransactionTags.BUDGETS_EMPTY).padding(top = 6.dp)) {
                Text("Crie uma verba antes de lançar.", Margem.type.body, color = c.inkSecondary)
                Button("Nova verba", { go(Screen.NewBudget) }, Modifier.testTag(TransactionTags.BUDGETS_NEW), kind = ButtonKind.TEXT)
            } else FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in data.budgets) BudgetOption(b.name, b.id.v == budget, Modifier.testTag(TransactionTags.BUDGET + b.id.v)) { budget = b.id.v }
            }

            Text("PARCELAS", sans(11f, 600, 14f, 0.12f), Modifier.padding(top = 18.dp), c.inkTertiary)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Stepper(installments, { installments = it }, 1..400, tag = TransactionTags.INSTALLMENTS)
                if (installments > 1 && total > 0) {
                    val each = total / installments
                    val last = total - each * (installments - 1)
                    Text(
                        "$installments × ${money(Cents(each))}" + if (last != each) " · última ${money(Cents(last))}" else "",
                        sans(15f, 500, 20f, tabular = true), Modifier.testTag(TransactionTags.INSTALLMENTS_PREVIEW),
                    )
                }
            }

            Text("MARCADORES", sans(11f, 600, 14f, 0.12f), Modifier.padding(top = 18.dp), c.inkTertiary)
            FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in (data.tags + states.keys).distinct().sorted()) {
                    val st = states[name] ?: 'O'
                    TagChip(
                        name,
                        Modifier.testTag(TransactionTags.TAG + name).semantics {
                            selected = st != 'O'
                            if (st == 'S') stateDescription = "sugerido"
                        },
                        state = when (st) { 'S' -> ChipState.SUGGESTED; 'C' -> ChipState.SELECTED; else -> ChipState.NORMAL },
                    ) { tapChip(name) }
                }
                if (!addingTag) TagChip("novo", Modifier.testTag(TransactionTags.TAG_NEW), state = ChipState.ADD) { addingTag = true }
            }
            if (addingTag) Row(verticalAlignment = Alignment.CenterVertically) {
                Field(newTag, { newTag = it }, "Novo marcador", Modifier.weight(1f), placeholder = "em minúsculas", tag = TransactionTags.TAG_INPUT, onDone = ::addTag)
                Button("Adicionar", ::addTag, Modifier.testTag(TransactionTags.TAG_ADD), kind = ButtonKind.TEXT)
            }

            if (preview != null && preview.month != NO_MONTH) Text(
                "$budgetName: margem ${marginText(preview.before)} → ${marginText(preview.after)}", sans(15f, 500, 20f, tabular = true),
                Modifier.padding(top = 18.dp).testTag(TransactionTags.FORM_PREVIEW), if (preview.after.v < 0) c.overrun else c.ink,
            )
            if (missing.isNotEmpty()) Text(
                missingText(missing), sans(13.5f, 400, 18f), Modifier.padding(top = 10.dp).testTag(TransactionTags.FORM_MISSING), c.inkTertiary,
            )

            if (isEdit) {
                val r = data.detail.row
                if (r.refunded) Row(Modifier.padding(top = 18.dp).testTag(TransactionTags.FORM_REFUNDED).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Text("estornada", serif(16, 500, 20, italic = true), Modifier.weight(1f), c.inkTertiary)
                    Button("Desfazer estorno", {
                        scope.launch { if (db.undoRefund(r.estorno)) snack.show(Snack("Estorno desfeito: a compra volta a contar.")) }
                    }, Modifier.testTag(TransactionTags.FORM_UNDO_REFUND), kind = ButtonKind.TEXT, icon = R.drawable.i_desfazer)
                }
                Button(
                    "Apagar lançamento", { askDelete = true }, Modifier.padding(top = 24.dp).testTag(TransactionTags.FORM_DELETE),
                    kind = ButtonKind.DISMISS, color = c.overrun,
                )
            }
            if (isPost) Button(
                "É um estorno? Registrar estorno", { replace(Screen.Refund(notice)) },
                Modifier.padding(top = 16.dp).testTag(TransactionTags.FORM_AS_REFUND), kind = ButtonKind.TEXT,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Margem.space.page, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isPost) Button("Riscar", {
                if (!busy) {
                    busy = true
                    scope.launch {
                        if (!riscar(db, notice, snack)) snack.show(Snack(InboxTags.GONE_TEXT))
                        back()
                    }
                }
            }, Modifier.weight(1f).testTag(TransactionTags.FORM_DISMISS), kind = ButtonKind.DISMISS, icon = R.drawable.i_riscar)
            Button(
                if (isEdit) "Salvar" else "Lançar ${money(Cents(total))}", ::submit,
                Modifier.weight(1.4f).testTag(TransactionTags.FORM_SUBMIT), icon = if (isEdit) 0 else R.drawable.i_lancar, enabled = missing.isEmpty(),
            )
        }
    }
    if (picking && data != null) DateTimeDialog(day, at, today, data.weekStart, { d, t ->
        date = d.toString()
        hour = time(t)
    }, { picking = false })
    if (askDelete && data != null) {
        val r = data.detail.row
        val text = when {
            r.notice.v != 0L && r.refunded -> "O recado volta à Pauta, e o recado de estorno também. Não dá para desfazer."
            r.notice.v != 0L -> "O recado volta à Pauta. Não dá para desfazer."
            r.refunded -> "O lançamento some, e o recado de estorno volta à Pauta. Não dá para desfazer."
            else -> "O lançamento some. Não dá para desfazer."
        }
        MargemDialog("Apagar o lançamento?", text, "Apagar", {
            askDelete = false
            scope.launch {
                db.deleteTransaction(edit)
                back()
            }
        }, { askDelete = false }, tag = TransactionTags.DELETE_DIALOG)
    }
}

/** A budget to choose in the form: its name in a bordered box, filled in ink when chosen. */
@Composable
private fun BudgetOption(name: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Margem.colors
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.heightIn(min = 48.dp).semantics { selected = on }.clickable(role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            name, sans(14.5f, 600, 18f), Modifier.background(if (on) c.ink else c.surface, shape).border(1.dp, if (on) c.ink else c.ruleStrong, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            if (on) c.paper else c.ink, maxLines = 1,
        )
    }
}
