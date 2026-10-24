package com.kaiodelphino.margem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.text.input.ImeAction
import com.kaiodelphino.margem.fold
import com.kaiodelphino.margem.foldedMatch
import com.kaiodelphino.margem.widgets.dashedBorder
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiodelphino.margem.BudgetId
import com.kaiodelphino.margem.BudgetOrder
import com.kaiodelphino.margem.BudgetSummary
import com.kaiodelphino.margem.Cents
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.MAX_CAPTION
import com.kaiodelphino.margem.plannedName
import com.kaiodelphino.margem.NO_DATE
import com.kaiodelphino.margem.NO_MONTH
import com.kaiodelphino.margem.NO_TIME
import com.kaiodelphino.margem.NoticeId
import com.kaiodelphino.margem.NoticeRow
import com.kaiodelphino.margem.NoticeStatus
import com.kaiodelphino.margem.OccurrenceId
import com.kaiodelphino.margem.Planned
import com.kaiodelphino.margem.PlannedId
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.Repeats
import com.kaiodelphino.margem.Suggestion
import com.kaiodelphino.margem.TagRow
import com.kaiodelphino.margem.TransactionDetail
import com.kaiodelphino.margem.TransactionForm
import com.kaiodelphino.margem.TransactionId
import com.kaiodelphino.margem.TransactionRow
import com.kaiodelphino.margem.widgets.AliasText
import com.kaiodelphino.margem.widgets.Amount
import com.kaiodelphino.margem.widgets.BottomSheet
import com.kaiodelphino.margem.widgets.Button
import com.kaiodelphino.margem.widgets.ButtonKind
import com.kaiodelphino.margem.widgets.ChipState
import com.kaiodelphino.margem.widgets.CreditLine
import com.kaiodelphino.margem.widgets.DateTimeDialog
import com.kaiodelphino.margem.widgets.DayHeader
import com.kaiodelphino.margem.widgets.DayStamp
import com.kaiodelphino.margem.widgets.Emenda
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Field
import com.kaiodelphino.margem.widgets.Icon
import com.kaiodelphino.margem.widgets.IconButton
import com.kaiodelphino.margem.widgets.InfoBand
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.MargemDialog
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
import com.kaiodelphino.margem.widgets.monthLong
import com.kaiodelphino.margem.widgets.monthShort
import com.kaiodelphino.margem.widgets.plannedLine
import com.kaiodelphino.margem.widgets.sans
import com.kaiodelphino.margem.widgets.serif
import com.kaiodelphino.margem.widgets.time
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.onDay
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import kotlin.math.abs
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
 *   suggestion (Db.suggestion of the parsed merchant, spec §2.5): its budget chosen and "sugerida", its tags chosen
 *   and "sugerido". A notice that looks like an ad opens with zero total and blank merchant.
 * - **Editar** (Screen.Edit, from [ROW] or BudgetTags.LINE): the transaction as it is (Db.transaction):
 *   total, merchant, date, time, budget, installments, its tags selected (not suggested), alias of its
 *   merchant. A transaction from a notice shows the notice and the emendas against its Parsed.
 * - **Novo lançamento** (Screen.NewTransaction, from [NEW]): empty; today (LocalToday) and the current
 *   time of the system clock to the minute; no notice, no suggestion.
 *
 * Every input survives Activity recreation and process death (rememberSaveable): total, bank text,
 * alias, name, date, time, budget and whether it is "sugerida", installments, tags and which are
 * "sugerido", the open [TransactionTags.BUDGET_SHEET] and the text of both searches (D66).
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

    // ---- A caminho (spec §2.13, §7.4, book 8.11, 10.5; D59) ----
    // Rows come from Db.planned(today). Nothing here counts in any budget.

    /**
     * The "A caminho" line, first in [LIST], absent when there is no open previsto. "A caminho", "4 previstos ·
     * R$ 539,80" ("1 previsto"; Σ Planned.cents) and, only the parts that exist, "1 com recado na Pauta"
     * (Planned.noticeInPauta) and "1 atrasado" / "2 atrasados" (Planned.late) joined by " · ". Always opens
     * collapsed (P11); click toggles it. "$ON_THE_WAY.open" is present while it is open: then one [PLANNED_ROW]
     * per open occurrence follows it, before the day groups. Stays open while the screen is open.
     */
    const val ON_THE_WAY = "transactions.onTheWay"

    /**
     * Prefix: PLANNED_ROW + occurrence id, while [ON_THE_WAY] is open: late ones first, then by date. The
     * dashed day stamp ("out" / "5", day in ink 3 when late), the name, and under it "esperando desde 5 out"
     * when late, else "recado na Pauta" (anil) when Planned.noticeInPauta, else "Casa · contas, luz · todo mês"
     * (budget · tags · "todo mês" or "só uma vez"); the value in ink 2. Click opens [PLANNED_SHEET].
     */
    const val PLANNED_ROW = "transactions.planned."

    /**
     * The previsto sheet (book 8.11): stamp, name, "Lazer · academia · todo mês, dia 5", value; then
     * "Esperando o recado desde 5 de outubro." when late and "Ligado a “SMARTFIT” · Cartão Ipê." (Planned.linkText,
     * linkSource) when linked, in one line; then the actions. Back or a touch outside closes it.
     */
    const val PLANNED_SHEET = "transactions.plannedSheet"

    /**
     * "Lançar sem recado", "vira um lançamento de 5 out, sem ligação": only from the occurrence's day on
     * (date ≤ today, R6). Opens Novo lançamento filled from the previsto (budget, tags, caption = name, value,
     * the occurrence's date, occurrence): saving closes the occurrence and writes no link.
     */
    const val PLANNED_LAUNCH_NO_NOTICE = "transactions.plannedSheet.launch"

    /** "Pular este mês", "some sem lançar; volta em 5 de novembro" (Só uma vez: "some sem lançar"). Db.skipOccurrence; the sheet closes. */
    const val PLANNED_SKIP = "transactions.plannedSheet.skip"

    /** "Editar previsto": opens the manual form with the previsto (FORM_TITLE "Editar previsto"; TransactionForm.planned). */
    const val PLANNED_EDIT = "transactions.plannedSheet.edit"

    /** "Apagar previsto", "este e os próximos meses": opens [PLANNED_DELETE_DIALOG]. */
    const val PLANNED_DELETE = "transactions.plannedSheet.delete"

    /**
     * "Apagar o previsto Academia?", "Este e os próximos meses somem. O que já foi lançado continua." Confirm
     * deletes it (Db.deletePlanned) and closes dialog and sheet; cancel closes the dialog.
     */
    const val PLANNED_DELETE_DIALOG = "transactions.plannedDelete"
    const val PLANNED_DELETE_CONFIRM = "transactions.plannedDelete.confirm"
    const val PLANNED_DELETE_CANCEL = "transactions.plannedDelete.cancel"

    // ---- The form (spec §7.3, book 10.3) ----

    /** The top bar: "Lançar recado", "Editar lançamento", "Novo lançamento" or "Editar previsto". */
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

    // ---- Verba (spec §2.5, §7.3, book 8.12; D66) ----
    // Margins: "margem " + marginText of BudgetMonth.remaining of the budget in the budget month of the
    // form's date (Db.budgetsOverview(Db.monthOf(date), today, MANUAL)) — the month of [FORM_PREVIEW]. A
    // budget with no limit row in that month (Db.budgetsOverview leaves it out) reads "sem cota" instead.

    /**
     * The verba field, absent when there are no budgets ([BUDGETS_EMPTY]). Rubric "Verba"; with a budget
     * chosen, its name and its margin ("Lazer", "margem R$ 34,00"; "margem R$ 12,00 além"; without a limit
     * row in the month, "sem cota em setembro", the month by monthLong); with none,
     * "Escolher a verba". A budget deleted while the form is open stops being chosen. stateDescription
     * "sugerida" while the chosen budget is the suggestion's (Lançar, spec §2.5) and no budget was picked
     * in [BUDGET_SHEET] since: it counts all the same. Click opens [BUDGET_SHEET].
     */
    const val FORM_BUDGET = "txForm.budget"

    /**
     * The sheet "Em qual verba entra?" (rubric "Verba"): [BUDGET_SEARCH], then one [BUDGET] row per budget
     * (the form's budgets, manual order) whose name contains the search text, compared with `fold` (case
     * and accents ignored), then [BUDGET_SHEET_NEW]. Back or a touch outside closes it, nothing changes.
     */
    const val BUDGET_SHEET = "txForm.budgetSheet"

    /** "Nenhuma verba com “xyz”." (the trimmed search text): present while the search has text and no [BUDGET] row is shown. */
    const val BUDGET_NONE = "txForm.budgetSheet.none"

    /** "Buscar verba": the sheet's search input, always there, empty each time the sheet opens. */
    const val BUDGET_SEARCH = "txForm.budgetSheet.search"

    /**
     * Prefix: BUDGET + budget id, in [BUDGET_SHEET]: the name and its margin ("R$ 212,40", "R$ 23,50 além";
     * "sem cota" without a limit row in the month), `selected` for the chosen one (also when suggested). Click
     * chooses it (no longer "sugerida") and closes the sheet; exactly one or none is chosen.
     */
    const val BUDGET = "txForm.budgetSheet.budget."

    /**
     * "Nova verba", last in [BUDGET_SHEET]: closes it and opens Screen.NewBudget. Back in the form, a budget
     * created there is chosen (not "sugerida"): one with an id greater than every budget the form knew when
     * this was tapped (budget ids only grow, D66). Coming back without creating one changes nothing.
     */
    const val BUDGET_SHEET_NEW = "txForm.budgetSheet.new"

    /**
     * No budgets at all: "Crie uma verba antes de lançar." with "Nova verba" ([BUDGETS_NEW] opens it; back
     * in the form, the budget created there is chosen, as with [BUDGET_SHEET_NEW]).
     */
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

    // ---- Marcadores (spec §2.5, §7.3, book 8.12; D66) ----
    // Only the chosen tags are on the form; the others are reached through [TAG_SEARCH]. "Uses" is
    // TagRow.uses (Db.tags). Text is compared with `fold` (case and accents ignored); a name made here is
    // trim().lowercase(), like Db.createTag, and written only when the form is saved.

    /**
     * Prefix: TAG + tag name ("txForm.tag.padaria"): one removable chip per chosen tag, in the order they
     * were chosen (the suggestion's or the previsto's first, then the ones added here), all `selected`
     * (they count). A suggested one (spec §2.5) has stateDescription "sugerido" (dashed). Click on any
     * chip, suggested or not, removes it from the form.
     */
    const val TAG = "txForm.tag."

    /**
     * "Buscar ou criar marcador", under the chips. Focused with no text: the [TAG_SHORTCUT]s. With text:
     * the [TAG_RESULT]s, [TAG_NONE] and [TAG_CREATE], in a list that scrolls on its own and fits above
     * the keyboard (spec §7.3: nothing behind it). The keyboard's done: marks the tag
     * whose name is the clean text, else creates it like [TAG_CREATE]; blank does nothing; then the text
     * empties and the focus stays. A name already chosen is never added twice.
     */
    const val TAG_SEARCH = "txForm.tagSearch"

    /**
     * Prefix: TAG_SHORTCUT + tag name, under the rubric "Mais usados", while [TAG_SEARCH] is focused and
     * empty: the 8 most used tags (uses desc, then name) among the ones not chosen; none when no tag is
     * left. Click chooses it (not "sugerido"); focus stays.
     */
    const val TAG_SHORTCUT = "txForm.tagSearch.shortcut."

    /**
     * Prefix: TAG_RESULT + tag name, while [TAG_SEARCH] has non-blank text: every tag (Db.tags and the
     * names chosen here) whose name contains the trimmed text, the ones starting with it first, then uses
     * desc, then name; no limit (the list scrolls). Each reads the name and "33 lançamentos" ("1
     * lançamento"; nothing for a name made here and not saved yet). A chosen one is `selected`, with a ✓
     * and no other text, and a click on it does nothing; a click on another chooses it (not "sugerido"),
     * empties the text, focus stays.
     */
    const val TAG_RESULT = "txForm.tagSearch.result."

    /**
     * "Criar “festa junina”" (the clean text) and "novo, só é gravado ao lançar" ("ao salvar" when the button says Salvar), last under the results,
     * while the clean text is not blank and is neither the name of an existing tag nor of a chosen one.
     * Click adds it as a chosen chip (not "sugerido"), empties the text, focus stays.
     */
    const val TAG_CREATE = "txForm.tagSearch.create"

    /** "Nenhum marcador com “pizzaria”." (the clean text): present while the search has text and no [TAG_RESULT] is. */
    const val TAG_NONE = "txForm.tagSearch.none"

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
     * Absent, with [FORM_DISMISS], while the keyboard is open over [TAG_SEARCH] (spec §7.3).
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

    // ---- Previsto in the form (spec §2.13, §7.3, book 8.9, 8.11; D62, D64) ----

    /**
     * "Repete" with [FORM_REPEATS_ONCE] "Só uma vez" and [FORM_REPEATS_MONTHLY] "Todo mês, dia 12" (the
     * form date's day), one `selected`; Só uma vez on open (the previsto's own in Editar previsto, and in Editar
     * lançamento the one of its previsto, D65: switching that to Só uma vez and saving deletes it). Shown in
     * the three forms whatever the date (P4, P4b); absent while the form is linked ([FORM_LINKED]).
     */
    const val FORM_REPEATS = "txForm.repeats"
    const val FORM_REPEATS_ONCE = "txForm.repeats.once"
    const val FORM_REPEATS_MONTHLY = "txForm.repeats.monthly"

    /**
     * Lançar, with Todo mês and a non-empty bank text: "Em novembro, o recado “SPOTIFY” chega ligado." (the
     * month after the date's, the bank text). Absent otherwise.
     */
    const val FORM_REPEATS_HINT = "txForm.repeats.hint"

    /**
     * Novo lançamento only, while the date is after today: the anil band "Data à frente: vira previsto. Fica
     * em Lançamentos esperando o recado, sem contar na verba."; [FORM_SUBMIT] reads "Salvar previsto" and
     * saves only the previsto (Db.addManual with today), back to Lançamentos. [FORM_PREVIEW] is absent.
     */
    const val FORM_FUTURE = "txForm.future"

    /**
     * "É um previsto? Ligar": under the notice quote in Lançar, under [FORM_NAME] in Novo lançamento (R6b).
     * Absent in Editar, while linked, and when there is no open previsto. Opens [LINK_SHEET].
     */
    const val FORM_LINK_OPEN = "txForm.link"

    /**
     * The sheet "Qual previsto é este recado?" (Lançar; "Só desta vez: nos próximos meses, o recado “ENEL SP”
     * chega ligado." under it, the bank text) or "Qual previsto é este lançamento?" (manual). One
     * [LINK_CANDIDATE] per Db.planned(today), closest value to the form's first, then closest date; the first
     * reads "mais provável". [LINK_SUBMIT] "Ligar a “Conta de luz”" (disabled until one is chosen) links the form.
     */
    const val LINK_SHEET = "txForm.linkSheet"
    const val LINK_CANDIDATE = "txForm.linkSheet.candidate."
    const val LINK_SUBMIT = "txForm.linkSheet.submit"

    /**
     * The linked state: the anil band "Ligado a “Conta de luz”", "Todo mês, dia 12. Verba, marcadores e nome
     * vieram do previsto." with [FORM_UNLINK] "Desligar". Budget and tags of the previsto selected and
     * confirmed (not suggested), the caption field open with its name; total, bank text, date and time stay
     * the recado's (P7: no emenda, no previsto value). [FORM_REPEATS] is absent. Saving closes the occurrence
     * (TransactionForm.occurrence). Desligar puts the form back as it was before linking.
     */
    const val FORM_LINKED = "txForm.linked"
    const val FORM_UNLINK = "txForm.unlink"

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
    val planned = query(db, today) { it.planned(today) }
    // P11: always opens collapsed; open while this screen is.
    var open by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableLongStateOf(0L) }
    Column {
        TopBar("Lançamentos") {
            Button("Novo lançamento", { go(Screen.NewTransaction) }, Modifier.testTag(TransactionTags.NEW), kind = ButtonKind.TEXT, icon = R.drawable.i_mais)
        }
        LazyColumn(Modifier.testTag(TransactionTags.LIST), contentPadding = PaddingValues(start = Margem.space.page, end = Margem.space.page, bottom = 32.dp)) {
            if (rows == null) return@LazyColumn
            if (!planned.isNullOrEmpty()) item(key = "onTheWay") { OnTheWay(planned, today, open, { open = !open }) { sheet = it.occurrence.v } }
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
    // A skipped, launched or deleted occurrence leaves the list, and its sheet with it.
    planned?.firstOrNull { it.occurrence.v == sheet }?.let { PlannedSheet(db, it, today, go) { sheet = 0L } }
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
            if (t.caption.isEmpty()) AliasText(
                t.name, serif(17, 500, 22), alias = t.alias.isNotEmpty(), color = if (t.refunded) c.inkTertiary else c.ink,
            ) else {
                // §2.12: the legenda is the name, in italic; the merchant drops to an "em …" credit.
                Text(t.title, serif(17, 500, 22, italic = true), Modifier, if (t.refunded) c.inkTertiary else c.ink, maxLines = 2)
                if (t.name.isNotEmpty()) CreditLine(
                    t.name, t.alias.isNotEmpty(), if (t.refunded) c.inkTertiary else c.inkSecondary, Modifier.padding(top = 1.dp),
                )
            }
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

// ---- A caminho and the previsto sheet ----

/** "Todo mês, dia 12" / "Só uma vez, dia 12". */
private fun repeatsText(p: Planned) = (if (p.repeats == Repeats.MONTHLY) "Todo mês" else "Só uma vez") + ", dia ${p.day}"

/** "5 out" */
private fun dayMonth(d: LocalDate) = "${d.day}\u00A0${monthShort(d.month)}" // NBSP: "24 nov" never splits

/** The snack of a lançado previsto (book 7.3): "Lançado: Internet, em Casa. O próximo é 10 nov." */
fun launchedText(p: Planned, budgetName: String): String =
    "Lançado: ${p.name}, em $budgetName." + if (p.next != NO_DATE) " O próximo é ${dayMonth(p.next)}." else ""

/** Book 8.11: the "A caminho" line and, open, its rows. Tags: [TransactionTags.ON_THE_WAY], [TransactionTags.PLANNED_ROW]. */
@Composable
private fun OnTheWay(rows: List<Planned>, today: LocalDate, open: Boolean, toggle: () -> Unit, pick: (Planned) -> Unit) {
    val c = Margem.colors
    val inPauta = rows.count { it.noticeInPauta }
    val late = rows.count { it.late }
    val notes = listOfNotNull(
        if (inPauta > 0) "$inPauta com recado na Pauta" else null,
        if (late > 0) "$late ${if (late == 1) "atrasado" else "atrasados"}" else null,
    ).joinToString(" · ")
    Column {
        Row(
            Modifier.padding(top = 4.dp, bottom = 2.dp).fillMaxWidth().heightIn(min = 60.dp).testTag(TransactionTags.ON_THE_WAY)
                .background(c.surfaceSunken, RoundedCornerShape(6.dp)).semantics(mergeDescendants = true) { stateDescription = if (open) "aberto" else "recolhido" }
                .clickable(role = Role.Button, onClick = toggle).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(R.drawable.i_previsto, tint = c.inkSecondary)
            Column(Modifier.weight(1f)) {
                Text("A CAMINHO", sans(11f, 600, 14f, 0.12f), color = c.inkTertiary)
                Text(
                    "${rows.size} ${if (rows.size == 1) "previsto" else "previstos"} · ${money(Cents(rows.sumOf { it.cents.v }))}",
                    serif(17, 500, 22, tabular = true),
                )
                if (notes.isNotEmpty()) Text(notes, sans(13f, 400, 17f), color = c.inkSecondary)
            }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(R.drawable.i_avancar, Modifier.rotate(if (open) 90f else 0f), size = 20.dp)
            }
        }
        if (open) {
            Spacer(Modifier.size(0.dp).semantics { testTag = TransactionTags.ON_THE_WAY + ".open" })
            for (p in rows.sortedWith(compareByDescending<Planned> { it.late }.thenBy { it.date }.thenBy { it.occurrence.v })) {
                val sub = when {
                    p.late -> "esperando desde ${dayMonth(p.date)}"
                    p.noticeInPauta -> "recado na Pauta"
                    else -> listOf(plannedLine(p), if (p.repeats == Repeats.MONTHLY) "todo mês" else "só uma vez").filter(String::isNotEmpty).joinToString(" · ")
                }
                Row(
                    Modifier.fillMaxWidth().testTag(TransactionTags.PLANNED_ROW + p.occurrence.v).semantics(mergeDescendants = true) {}
                        .clickable { pick(p) }
                        .drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DayStamp(p.date, p.late)
                    Column(Modifier.weight(1f)) {
                        Text(p.name, serif(17, 500, 22), maxLines = 2)
                        Text(
                            sub, sans(13f, if (p.noticeInPauta && !p.late) 600 else 400, 17f),
                            color = when {
                                p.late -> c.inkSecondary
                                p.noticeInPauta -> c.info
                                else -> c.inkTertiary
                            },
                            maxLines = 1,
                        )
                    }
                    Amount(p.cents, size = 17, color = c.inkSecondary)
                }
            }
        }
    }
}

/** A line of the previsto sheet: icon, label and a small line under it. */
@Composable
private fun SheetAction(icon: Int, label: String, sub: String, tag: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val c = Margem.colors
    val ink = if (color == Color.Unspecified) c.ink else color
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag).semantics(mergeDescendants = true) {}.clickable(role = Role.Button, onClick = onClick)
            .drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, tint = if (color == Color.Unspecified) c.inkSecondary else color)
        Column {
            Text(label, sans(15.5f, 500, 20f), color = ink)
            if (sub.isNotEmpty()) Text(sub, sans(13f, 400, 17f), color = c.inkTertiary)
        }
    }
}

/** Book 8.11: the previsto sheet with its actions. Tags: [TransactionTags.PLANNED_SHEET]. */
@Composable
private fun PlannedSheet(db: Db, p: Planned, today: LocalDate, go: (Screen) -> Unit, close: () -> Unit) {
    val c = Margem.colors
    val scope = rememberCoroutineScope()
    var ask by rememberSaveable { mutableStateOf(false) }
    BottomSheet(close, Modifier.testTag(TransactionTags.PLANNED_SHEET)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DayStamp(p.date, p.late)
            Column(Modifier.weight(1f)) {
                Text(p.name, serif(20, 500, 26), maxLines = 2)
                Text(
                    listOf(plannedLine(p), repeatsText(p).replaceFirstChar { it.lowercase() }).filter(String::isNotEmpty).joinToString(" · "),
                    sans(13f, 400, 17f), color = c.inkTertiary,
                )
            }
            Amount(p.cents, size = 17, color = c.inkSecondary)
        }
        val about = listOfNotNull(
            if (p.late) "Esperando o recado desde ${p.date.day} de ${monthLong(p.date.month)}." else null,
            if (p.linked) "Ligado a “${p.linkText}” · ${p.linkSource}." else null,
        ).joinToString(" ")
        if (about.isNotEmpty()) Text(about, sans(14f, 400, 20f), Modifier.padding(top = 2.dp, bottom = 8.dp), c.inkSecondary)
        // R6: lançar sem recado only from the occurrence's day on.
        if (p.date <= today) SheetAction(
            R.drawable.i_lancamento, "Lançar sem recado", "vira um lançamento de ${dayMonth(p.date)}, sem ligação", TransactionTags.PLANNED_LAUNCH_NO_NOTICE,
        ) {
            close()
            go(Screen.FromPlanned(p.occurrence))
        }
        SheetAction(
            R.drawable.i_avancar, "Pular este mês",
            if (p.next != NO_DATE) "some sem lançar; volta em ${p.next.day} de ${monthLong(p.next.month)}" else "some sem lançar",
            TransactionTags.PLANNED_SKIP,
        ) { scope.launch { db.skipOccurrence(p.occurrence); close() } }
        SheetAction(R.drawable.i_emenda, "Editar previsto", "", TransactionTags.PLANNED_EDIT) {
            close()
            go(Screen.EditPlanned(p.occurrence))
        }
        SheetAction(R.drawable.i_fechar, "Apagar previsto", "este e os próximos meses", TransactionTags.PLANNED_DELETE, c.overrun) { ask = true }
    }
    if (ask) MargemDialog(
        "Apagar o previsto ${p.name}?", "Este e os próximos meses somem. O que já foi lançado continua.", "Apagar", {
            ask = false
            scope.launch { db.deletePlanned(p.planned); close() }
        }, { ask = false }, tag = TransactionTags.PLANNED_DELETE_DIALOG,
    )
}

/** Book 8.11: "Qual previsto é este recado?". Tags: [TransactionTags.LINK_SHEET]. */
@Composable
private fun LinkSheet(rows: List<Planned>, notice: NoticeRow, date: LocalDate, total: Cents, link: (Planned) -> Unit, close: () -> Unit) {
    val c = Margem.colors
    // Closest value first, then closest day (decided 2026-10-23: a bill's value says more than its day).
    val sorted = rows.sortedWith(compareBy<Planned> { abs(it.cents.v - total.v) }.thenBy { abs(it.date.toEpochDays() - date.toEpochDays()) })
    var chosen by rememberSaveable { mutableLongStateOf(0L) }
    BottomSheet(close, Modifier.testTag(TransactionTags.LINK_SHEET)) {
        val isPost = notice.id.v != 0L
        Text(if (isPost) "Qual previsto é este recado?" else "Qual previsto é este lançamento?", serif(22, 500, 28))
        if (isPost && notice.parsed.merchant.isNotEmpty()) Text(
            "Só desta vez: nos próximos meses, o recado “${notice.parsed.merchant}” chega ligado.", sans(14f, 400, 20f),
            Modifier.padding(top = 4.dp, bottom = 6.dp), c.inkSecondary,
        )
        sorted.forEachIndexed { i, p ->
            val on = p.occurrence.v == chosen
            Row(
                Modifier.fillMaxWidth().testTag(TransactionTags.LINK_CANDIDATE + p.occurrence.v).semantics(mergeDescendants = true) { selected = on }
                    .clickable(role = Role.RadioButton) { chosen = p.occurrence.v }.background(if (on) c.surfaceSunken else Color.Transparent)
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(20.dp).background(if (on) c.ink else Color.Transparent, RoundedCornerShape(3.dp))
                        .border(1.dp, if (on) c.ink else c.ruleStrong, RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(R.drawable.i_lancar, tint = c.paper, size = 16.dp) }
                Column(Modifier.weight(1f)) {
                    Text(p.name, serif(17, 500, 22), maxLines = 1)
                    Text(
                        buildAnnotatedString {
                            append(listOfNotNull("dia ${p.day}", p.budgetName, if (p.late) "atrasado" else null).joinToString(" · "))
                            if (p.linked) append(" · ligado a “${p.linkText}”")
                            if (i == 0) {
                                append(" · ")
                                withStyle(SpanStyle(color = c.info, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)) { append("MAIS PROVÁVEL") }
                            }
                        },
                        sans(13f, 400, 17f), color = c.inkTertiary,
                    )
                }
                Amount(p.cents, size = 17)
            }
        }
        val pick = sorted.firstOrNull { it.occurrence.v == chosen }
        Button(
            if (pick != null) "Ligar a “${pick.name}”" else "Ligar", { pick?.let(link) },
            Modifier.padding(top = 12.dp).fillMaxWidth().testTag(TransactionTags.LINK_SUBMIT), icon = R.drawable.i_ligar, enabled = pick != null,
        )
    }
}

/**
 * D62: the one form of a previsto with a recado (Lançar previsto, Lançar já ligado, "É um previsto? Ligar")
 * or without one ([notice] zero: "Lançar sem recado", R6b): budget and tags of the previsto, caption = its
 * name, total / bank text / date / time of the recado (else the occurrence's value and date at 00:00), occurrence.
 */
fun formFrom(planned: Planned, notice: NoticeRow): TransactionForm =
    if (notice.id.v == 0L) TransactionForm(
        planned.budget, planned.tags, planned.cents, 1, "", planned.date, NO_TIME, caption = planned.name, occurrence = planned.occurrence,
    ) else TransactionForm(
        planned.budget, planned.tags, notice.parsed.cents, 1, notice.parsed.merchant, notice.parsed.date, notice.parsed.time,
        caption = planned.name, occurrence = planned.occurrence,
    )

/** D55: the caption input takes at most [MAX_CAPTION] UTF-16 units and never leaves a lone high surrogate at the cut. */
private fun cutCaption(s: String): String =
    if (s.length <= MAX_CAPTION) s
    else s.take(MAX_CAPTION).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }

/**
 * Lançar recado (book 10.3) for the pending notice [notice]. [replace] swaps this screen (Registrar estorno).
 * [linked] ≠ 0 opens it already linked to that open occurrence (D62).
 */
@Composable
fun PostScreen(
    db: Db, notice: NoticeId, snack: SnackHost, go: (Screen) -> Unit, replace: (Screen) -> Unit, back: () -> Unit, linked: OccurrenceId = OccurrenceId(0),
) = TxForm(db, notice, TransactionId(0), snack, go, replace, back, from = linked)

/** Editar lançamento: the same form, with Apagar and Desfazer estorno. */
@Composable
fun EditTransaction(db: Db, id: TransactionId, snack: SnackHost, go: (Screen) -> Unit, back: () -> Unit) =
    TxForm(db, NoticeId(0), id, snack, go, {}, back)

/** Novo lançamento: the manual form; [from] ≠ 0 fills it from that occurrence (Lançar sem recado, R6). */
@Composable
fun NewTransaction(db: Db, snack: SnackHost, go: (Screen) -> Unit, back: () -> Unit, from: OccurrenceId = OccurrenceId(0)) =
    TxForm(db, NoticeId(0), TransactionId(0), snack, go, {}, back, from = from)

/** Editar previsto (D64): the manual form of the previsto of [occurrence]. */
@Composable
fun EditPlanned(db: Db, occurrence: OccurrenceId, snack: SnackHost, go: (Screen) -> Unit, back: () -> Unit) =
    TxForm(db, NoticeId(0), TransactionId(0), snack, go, {}, back, editPlanned = occurrence)

private const val GONE_PLANNED = "Este previsto não existe mais."

private const val GONE_TX = "Este lançamento não existe mais."

/** What the form reads once and again on every write: the notice or transaction, budgets, tags, week start. */
private class FormData(
    val notice: NoticeRow, val detail: TransactionDetail, val budgets: List<BudgetSummary>, val tags: List<TagRow>,
    val weekStart: DayOfWeek, val suggestion: Suggestion, val planned: List<Planned>,
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
    from: OccurrenceId = OccurrenceId(0), editPlanned: OccurrenceId = OccurrenceId(0),
) {
    val c = Margem.colors
    val today = LocalToday.current()
    val scope = rememberCoroutineScope()
    val isPost = notice.v != 0L
    val isEdit = edit.v != 0L
    val isEditPlanned = editPlanned.v != 0L
    val isManual = !isPost && !isEdit
    val data = query(db, notice, edit, today) {
        val month = it.monthOf(today)
        it.ensureMonths(month)
        val n = if (isPost) it.notice(notice) else NoticeRow()
        val d = if (isEdit) it.transaction(edit) else TransactionDetail()
        FormData(
            n, d, it.budgetsOverview(month, today, BudgetOrder.MANUAL), it.tags(), it.settings().weekStart,
            if (isPost) it.suggestion(n.parsed.merchant) else Suggestion(), if (isEdit) emptyList() else it.planned(today),
        )
    }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var total by rememberSaveable { mutableLongStateOf(0L) }
    var merchant by rememberSaveable { mutableStateOf("") }
    var editingBank by rememberSaveable { mutableStateOf(false) }
    var alias by rememberSaveable { mutableStateOf("") }
    var caption by rememberSaveable { mutableStateOf("") }
    var captionOpen by rememberSaveable { mutableStateOf(false) }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var hour by rememberSaveable { mutableStateOf("00:00") }
    var budget by rememberSaveable { mutableLongStateOf(0L) }
    var installments by rememberSaveable { mutableIntStateOf(1) }
    var chips by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var askDelete by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Previsto (D62, D64): the linked occurrence, Repete, and what Desligar puts back.
    var linked by rememberSaveable { mutableLongStateOf(0L) }
    var monthly by rememberSaveable { mutableStateOf(false) }
    var linking by rememberSaveable { mutableStateOf(false) }
    var preBudget by rememberSaveable { mutableLongStateOf(0L) }
    var preChips by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var preCaption by rememberSaveable { mutableStateOf("") }
    var preCaptionOpen by rememberSaveable { mutableStateOf(false) }
    var preSuggested by rememberSaveable { mutableStateOf(false) }
    var preDate by rememberSaveable { mutableStateOf("") }
    // Book 8.12 (D66): the suggestion's budget until one is picked; the sheet and both searches.
    var suggested by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var budgetQuery by rememberSaveable { mutableStateOf("") }
    var tagQuery by rememberSaveable { mutableStateOf("") }
    var tagFocused by remember { mutableStateOf(false) }
    // Nova verba (D66): the greatest budget id known when it was tapped; -1 = not waiting for one.
    var knownBudget by rememberSaveable { mutableLongStateOf(-1L) }
    // Editar previsto: the previsto and its day, fixed on load (its occurrence may leave the list while the form is open).
    var editId by rememberSaveable { mutableLongStateOf(0L) }
    var editDay by rememberSaveable { mutableIntStateOf(0) }

    fun link(p: Planned) {
        // D62: what comes from the previsto is formFrom's, the one owner of that rule.
        val f = formFrom(p, data?.notice ?: NoticeRow())
        preBudget = budget
        preChips = chips
        preCaption = caption
        preCaptionOpen = captionOpen
        preSuggested = suggested
        preDate = date
        suggested = false
        if (data?.budgets?.any { it.id == f.budget } == true) budget = f.budget.v
        chips = chipList(f.tags.associateWith { 'C' })
        caption = cutCaption(f.caption)
        captionOpen = true
        linked = p.occurrence.v
        linking = false
        // Linked takes no day ahead (spec §7.3): a lançamento paid before the day is dated today.
        if (!isPost && LocalDate.parse(date) > today) date = today.toString()
    }

    fun unlink() {
        budget = preBudget
        chips = preChips
        caption = preCaption
        captionOpen = preCaptionOpen
        suggested = preSuggested
        // Spec §7.3 (2026-10-24): the date ahead that link() brought to today comes back.
        date = preDate
        linked = 0L
    }

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
                if (data.budgets.any { it.id == sug.budget }) {
                    budget = sug.budget.v
                    suggested = true
                }
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
                caption = r.caption
                // §2.12: editing a launch with legenda opens with the field in sight.
                captionOpen = r.caption.isNotEmpty()
                date = r.date.toString()
                hour = time(r.time)
                budget = r.budget.v
                installments = r.installments
                chips = chipList(r.tags.associateWith { 'C' })
                // D65: "Repete" opens as the transaction's own previsto is.
                monthly = data.detail.repeats == Repeats.MONTHLY
            }
            isEditPlanned -> {
                val p = data.planned.firstOrNull { it.occurrence == editPlanned }
                if (p == null) {
                    snack.show(Snack(GONE_PLANNED))
                    back()
                    return@LaunchedEffect
                }
                total = p.cents.v
                merchant = p.name
                date = p.date.toString()
                budget = p.budget.v
                chips = chipList(p.tags.associateWith { 'C' })
                monthly = p.repeats == Repeats.MONTHLY
                editId = p.planned.v
                editDay = p.day
            }
            else -> {
                val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
                hour = time(LocalTime(now.hour, now.minute))
            }
        }
        // Opened from a previsto: the card's body (linked Lançar) or Lançar sem recado (the occurrence's value and date).
        data.planned.firstOrNull { it.occurrence == from && from.v != 0L }?.let { p ->
            if (!isPost) {
                val f = formFrom(p, NoticeRow())
                total = f.total.v
                date = f.date.toString()
                hour = time(f.time)
            }
            link(p)
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
    // Margins of the month of the form's date, the preview's month (D66); a budget without a limit row there has none.
    val monthMargins = query(db, date, today) { d ->
        val m = d.monthOf(day)
        // D30: a month screen fills the months first; a date ahead may be in one not filled yet.
        d.ensureMonths(m)
        m to d.budgetsOverview(m, today, BudgetOrder.MANUAL).associate { it.id.v to it.month.remaining }
    }
    val margins = monthMargins?.second.orEmpty()
    // "sem cota em setembro" (E1.1): the name of the form's budget month.
    val marginMonth = monthMargins?.first?.let { monthLong(it.month) }.orEmpty()
    // The height the form's scroll shows, so the marcador results fit above the keyboard (E1.6).
    var room by remember { mutableIntStateOf(0) }

    // Back from Nova verba: the budget created there is chosen (ids only grow).
    LaunchedEffect(data, knownBudget) {
        val created = data?.budgets?.filter { it.id.v > knownBudget }?.maxByOrNull { it.id.v }
        if (knownBudget < 0L || created == null) return@LaunchedEffect
        budget = created.id.v
        suggested = false
        knownBudget = -1L
    }
    val preview = if (budget != 0L && total > 0 && loaded) query(db, budget, date, total, installments, edit, today) {
        it.previewAccept(BudgetId(budget), day, Cents(total), installments, today, edit)
    } else null
    val missing = buildList {
        if (total <= 0) add("o valor")
        // A budget deleted while the form is open stops counting as chosen.
        if (data?.budgets?.none { it.id.v == budget } != false) add("a verba")
        if (selectedTags.isEmpty()) add("um marcador")
    }

    val linkedPlanned = data?.planned?.firstOrNull { it.occurrence.v == linked }
    // Data à frente (spec §7.3): only a new manual one becomes a previsto.
    val ahead = isManual && !isEditPlanned && day > today
    val plannedId = PlannedId(if (isEditPlanned) editId else 0L)
    // Spec §7.3 (2026-10-23): a previsto this form makes or edits can't be named over 80; the form says so first.
    val makesPlanned = isEditPlanned || ahead || (monthly && linked == 0L && !(isEdit && data?.detail?.planned?.v != 0L))
    val longName = makesPlanned && plannedName(caption, if (ahead || isEditPlanned) "" else alias, merchant).length > MAX_CAPTION

    fun form() = TransactionForm(
        BudgetId(budget), selectedTags, Cents(total), installments, merchant, day, at, alias, caption,
        occurrence = if (isEditPlanned) editPlanned else OccurrenceId(linked),
        repeats = if (monthly && linked == 0L) Repeats.MONTHLY else Repeats.ONCE, planned = plannedId,
    )

    fun submit() {
        if (busy || missing.isNotEmpty() || longName) return
        busy = true
        scope.launch {
            val f = form()
            val margin = db.previewAccept(f.budget, f.date, f.total, f.installments, today, edit).after
            val p = linkedPlanned
            // A refusal names what is gone: the recado (Lançar), else the previsto the form is linked to.
            val gone = if (isPost) InboxTags.GONE_TEXT else GONE_PLANNED
            var stay = false
            when {
                isEdit -> {
                    if (!db.edit(edit, f)) snack.show(Snack(GONE_TX))
                }
                isEditPlanned -> {
                    if (db.addManual(f, today).planned.v == 0L) snack.show(Snack(GONE_PLANNED))
                }
                ahead -> {
                    // Spec §2.13 (2026-10-23): a refusal the form didn't foresee keeps what was typed.
                    if (db.addManual(f, today).planned.v == 0L) {
                        snack.show(Snack("Não deu para salvar o previsto."))
                        stay = true
                    }
                }
                p != null -> {
                    val id = if (isPost) db.accept(notice, f) else db.addManual(f, today).transaction
                    if (id.v == 0L) snack.show(Snack(gone))
                    else snack.show(Snack(launchedText(p, budgetName), "Desfazer") { db.deleteTransaction(id) })
                }
                else -> {
                    val id = if (isPost) db.accept(notice, f) else db.addManual(f, today).transaction
                    if (id.v == 0L) snack.show(Snack(if (linked != 0L) gone else InboxTags.GONE_TEXT))
                    else snack.show(Snack("Lançado em $budgetName. Margem: ${marginText(margin)}.", "Desfazer") { db.deleteTransaction(id) })
                }
            }
            if (stay) busy = false else back()
        }
    }

    fun newBudget() {
        knownBudget = data?.budgets?.maxOfOrNull { it.id.v } ?: 0L
        sheet = false
        go(Screen.NewBudget)
    }

    // Spec §2.5, §7.3: any chosen chip, suggested or not, goes with one touch; a name is never chosen twice.
    // They read `chips`, not `states`: TagPicker may skip recomposing and keep an older reference to them.
    fun removeTag(name: String) {
        chips = chipList(chipsOf(chips) - name)
    }

    fun chooseTag(name: String) {
        val now = chipsOf(chips)
        if (name.isNotEmpty() && name !in now) chips = chipList(now + (name to 'C'))
        tagQuery = ""
    }


    Column(Modifier.fillMaxSize()) {
        TopBar(
            when {
                isPost -> "Lançar recado"
                isEdit -> "Editar lançamento"
                isEditPlanned -> "Editar previsto"
                else -> "Novo lançamento"
            },
            Modifier.testTag(TransactionTags.FORM_TITLE).semantics(mergeDescendants = true) {}, onBack = back, close = true,
        )
        if (data == null || !loaded) return@Column
        Column(Modifier.weight(1f).onSizeChanged { room = it.height }.verticalScroll(rememberScrollState()).padding(start = Margem.space.page, end = Margem.space.page, bottom = 24.dp)) {
            if (source != null) NoticeQuote(source, Modifier.testTag(TransactionTags.FORM_NOTICE).semantics(mergeDescendants = true) {})

            @Composable
            fun LinkLine() {
                if (linkedPlanned != null) InfoBand(
                    R.drawable.i_ligar, "Ligado a “${linkedPlanned.name}”", "${repeatsText(linkedPlanned)}. Verba, marcadores e nome vieram do previsto.",
                    Modifier.padding(top = 10.dp).testTag(TransactionTags.FORM_LINKED), "Desligar", ::unlink, TransactionTags.FORM_UNLINK,
                ) else if (data.planned.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(TransactionTags.FORM_LINK_OPEN).semantics(mergeDescendants = true) {}
                        .clickable(role = Role.Button) { linking = true },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(R.drawable.i_ligar, tint = c.info, size = 18.dp)
                    Text("É um previsto? Ligar", sans(14.5f, 600, 20f), color = c.info)
                }
            }
            if (isPost) LinkLine()
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
                Field(
                    merchant, { merchant = it }, "Estabelecimento", placeholder = "Feira, por exemplo", tag = TransactionTags.FORM_NAME,
                    error = if (longName) "O nome do previsto tem até 80 letras." else "",
                )
                if (!isEditPlanned) LinkLine()
            }

            // Legenda (spec §2.12, D57): behind the link while folded, the field while open or set.
            val displayName = alias.trim().ifEmpty { merchant.trim() }
            if (captionOpen || caption.isNotEmpty()) Field(
                caption, { caption = cutCaption(it) }, "Legenda",
                hint = if (displayName.isEmpty()) "Só deste lançamento." else "Aparece no lugar de $displayName, só neste lançamento.",
                italic = true, tag = TransactionTags.FORM_CAPTION,
                counter = if (caption.length >= 70) "${caption.length}/$MAX_CAPTION" else "",
                counterTag = TransactionTags.FORM_CAPTION_COUNT,
            ) {
                IconButton(R.drawable.i_fechar, { caption = ""; captionOpen = false }, Modifier.testTag(TransactionTags.FORM_CAPTION_CLEAR))
            } else Row(
                Modifier.fillMaxWidth().testTag(TransactionTags.FORM_CAPTION_OPEN).clickable(role = Role.Button) { captionOpen = true }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.info)) { append("+ Legenda · ") }
                        withStyle(SpanStyle(color = c.inkTertiary)) { append("só deste lançamento") }
                    },
                    sans(14.5f, 600, 20f),
                )
            }
            // "legenda tirada" is derived (D57): the saved one was cleared in this edit. Gone on Salvar or new text.
            val removed = isEdit && data.detail.row.caption.isNotEmpty() && caption.isEmpty()
            if (removed) Row(
                Modifier.testTag(TransactionTags.FORM_CAPTION_REMOVED).semantics(mergeDescendants = true) {}
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("legenda tirada:", sans(13.5f, 400, 18f), color = c.inkSecondary)
                Text(
                    data.detail.row.caption, serif(14, 500, 18, italic = true), Modifier.padding(start = 8.dp), c.inkTertiary,
                    decoration = TextDecoration.LineThrough,
                )
                Button("Desfazer", {
                    caption = data.detail.row.caption
                    captionOpen = true
                }, Modifier.testTag(TransactionTags.FORM_CAPTION_REMOVED + ".undo").padding(start = 8.dp), kind = ButtonKind.TEXT, small = true)
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
            if (ahead) InfoBand(
                R.drawable.i_previsto, "", "Data à frente: vira previsto. Fica em Lançamentos esperando o recado, sem contar na verba.",
                Modifier.padding(top = 8.dp).testTag(TransactionTags.FORM_FUTURE),
            )

            // Repete (spec §2.13, P4, P4b): always there, but for a linked form (the previsto's own repetition).
            if (linked == 0L) {
                Text("REPETE", sans(11f, 600, 14f, 0.12f), Modifier.padding(top = 18.dp), c.inkTertiary)
                FlowRow(Modifier.padding(top = 6.dp).testTag(TransactionTags.FORM_REPEATS), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BudgetOption("Só uma vez", !monthly, Modifier.testTag(TransactionTags.FORM_REPEATS_ONCE)) { monthly = false }
                    // The previsto's own day: in Editar previsto, the occurrence's date may be cut to the month's last day (P8).
                    val repeatDay = when {
                        isEdit && data.detail.day != 0 -> data.detail.day
                        isEditPlanned && editDay != 0 && day == day.yearMonth.onDay(minOf(editDay, day.yearMonth.numberOfDays)) -> editDay
                        else -> day.day
                    }
                    BudgetOption("Todo mês, dia $repeatDay", monthly, Modifier.testTag(TransactionTags.FORM_REPEATS_MONTHLY)) { monthly = true }
                }
                // D62: the link comes from the recado's own bank text, never the amended "No banco".
                if (isPost && monthly && parsed != null && parsed.merchant.isNotBlank()) Text(
                    "Em ${monthLong(day.yearMonth.plusMonth().month)}, o recado “${parsed.merchant}” chega ligado.", sans(13.5f, 400, 18f),
                    Modifier.padding(top = 6.dp).testTag(TransactionTags.FORM_REPEATS_HINT), c.inkTertiary,
                )
                if (longName && source != null) Text(
                    "O nome do previsto tem até 80 letras. Escreva uma legenda.", sans(13.5f, 400, 18f), Modifier.padding(top = 6.dp), c.overrun,
                )
            }

            Text("VERBA", sans(11f, 600, 14f, 0.12f), Modifier.padding(top = 18.dp), c.inkTertiary)
            if (data.budgets.isEmpty()) Column(Modifier.testTag(TransactionTags.BUDGETS_EMPTY).padding(top = 6.dp)) {
                Text("Crie uma verba antes de lançar.", Margem.type.body, color = c.inkSecondary)
                Button("Nova verba", ::newBudget, Modifier.testTag(TransactionTags.BUDGETS_NEW), kind = ButtonKind.TEXT)
            } else BudgetField(budgetName, margins[budget], marginMonth, suggested) {
                budgetQuery = ""
                knownBudget = -1L
                sheet = true
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
            TagPicker(
                states, data.tags, tagQuery, { tagQuery = it }, { tagFocused = it }, ::chooseTag, ::removeTag, with(LocalDensity.current) { room.toDp() },
                if (isEdit || isEditPlanned || ahead) "salvar" else "lançar",
            )

            if (preview != null && preview.month != NO_MONTH && !ahead && !isEditPlanned) Text(
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
        // Spec §7.3: with the keyboard open over the marcador search, the footer stays behind it.
        if (!(tagFocused && WindowInsets.isImeVisible)) Row(Modifier.fillMaxWidth().padding(horizontal = Margem.space.page, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                when {
                    isEdit || isEditPlanned -> "Salvar"
                    ahead -> "Salvar previsto"
                    else -> "Lançar ${money(Cents(total))}"
                },
                ::submit, Modifier.weight(1.4f).testTag(TransactionTags.FORM_SUBMIT),
                icon = if (isEdit || isEditPlanned || ahead) 0 else R.drawable.i_lancar, enabled = missing.isEmpty() && !longName,
            )
        }
    }
    if (picking && data != null) DateTimeDialog(day, at, today, data.weekStart, { d, t ->
        date = d.toString()
        hour = time(t)
    }, { picking = false }, future = isManual && linked == 0L, oneMonth = isEditPlanned)
    if (sheet && data != null) BudgetSheet(data.budgets, margins, budget, budgetQuery, { budgetQuery = it }, {
        budget = it.v
        suggested = false
        sheet = false
    }, ::newBudget) { sheet = false }
    if (linking && data != null) LinkSheet(data.planned, data.notice, day, Cents(total), ::link) { linking = false }
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

/** "margem R$ 34,00" / "margem R$ 12,00 além"; "sem cota em setembro" without a limit row in the month (E1.1). */
private fun marginLine(m: Cents?, month: String): String = if (m == null) "sem cota em $month" else "margem ${marginText(m)}"

/** Book 8.12: the verba field — name and margin, or "Escolher a verba"; dashed while suggested. [TransactionTags.FORM_BUDGET]. */
@Composable
private fun BudgetField(name: String, margin: Cents?, month: String, suggested: Boolean, onClick: () -> Unit) {
    val c = Margem.colors
    Row(
        Modifier.padding(top = 2.dp).fillMaxWidth().heightIn(min = 48.dp).testTag(TransactionTags.FORM_BUDGET)
            .semantics(mergeDescendants = true) { if (suggested && name.isNotEmpty()) stateDescription = "sugerida" }
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind { drawLine(c.ruleStrong, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (name.isEmpty()) Text("Escolher a verba", serif(19, 400, 24, italic = true), Modifier.weight(1f), c.inkTertiary)
        else {
            Text(
                name, serif(19, 400, 24), (if (suggested) Modifier.drawBehind { dashedBorder(c.ruleStrong, 3.dp) }.padding(horizontal = 8.dp, vertical = 1.dp) else Modifier),
                if (suggested) c.inkSecondary else c.ink, maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(marginLine(margin, month), sans(13.5f, 400, 18f, tabular = true), color = if ((margin?.v ?: 0L) < 0L) c.overrun else c.inkTertiary)
        }
        Icon(R.drawable.i_avancar, tint = c.inkTertiary, size = 20.dp)
    }
}

/** Book 8.12: a search line — lupa, the text, the rule under it; [onDone] on the keyboard's done keeps the focus. */
@Composable
private fun SearchLine(
    value: String, onChange: (String) -> Unit, placeholder: String, tag: String, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null,
) {
    val c = Margem.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value, onChange, modifier.fillMaxWidth().testTag(tag), singleLine = true, interactionSource = interaction,
        textStyle = sans(16f, 400, 20f).copy(color = c.ink), cursorBrush = SolidColor(c.info),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = if (onDone == null) KeyboardActions.Default else KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).drawBehind {
                    val w = (if (focused) 2 else 1).dp.toPx()
                    drawLine(if (focused) c.ink else c.ruleStrong, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(R.drawable.i_lupa, tint = c.inkTertiary, size = 20.dp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, sans(16f, 400, 20f), color = c.inkTertiary)
                    inner()
                }
            }
        },
    )
}

/** Book 8.12: "Em qual verba entra?" [TransactionTags.BUDGET_SHEET]. */
@Composable
private fun BudgetSheet(
    budgets: List<BudgetSummary>, margins: Map<Long, Cents>, chosen: Long, query: String, onQuery: (String) -> Unit,
    pick: (BudgetId) -> Unit, new: () -> Unit, close: () -> Unit,
) {
    val c = Margem.colors
    val q = fold(query.trim())
    BottomSheet(close, Modifier.testTag(TransactionTags.BUDGET_SHEET)) {
        Text("VERBA", sans(11f, 600, 14f, 0.12f), color = c.inkTertiary)
        Text("Em qual verba entra?", Margem.type.title, Modifier.padding(top = 2.dp, bottom = 6.dp))
        SearchLine(query, onQuery, "Buscar verba", TransactionTags.BUDGET_SEARCH)
        val shown = budgets.filter { fold(it.name).contains(q) }
        if (shown.isEmpty() && q.isNotEmpty()) Text(
            "Nenhuma verba com “${query.trim()}”.", sans(13.5f, 400, 18f),
            Modifier.padding(vertical = 14.dp).testTag(TransactionTags.BUDGET_NONE), c.inkTertiary,
        )
        for ((i, b) in shown.withIndex()) {
            val on = b.id.v == chosen
            val m = margins[b.id.v]
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TransactionTags.BUDGET + b.id.v)
                    .semantics(mergeDescendants = true) {
                        selected = on
                        collectionItemInfo = CollectionItemInfo(i, 1, 0, 1)
                    }
                    .clickable(role = Role.RadioButton) { pick(b.id) }
                    .background(if (on) c.surfaceSunken else Color.Transparent)
                    .drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (on) Icon(R.drawable.i_lancar, tint = c.ink, size = 20.dp) else Spacer(Modifier.size(20.dp))
                Text(b.name, serif(19, 500, 24), Modifier.weight(1f), maxLines = 1)
                Text(if (m == null) "sem cota" else marginText(m), sans(14f, 400, 18f, tabular = true), color = if ((m?.v ?: 0L) < 0L) c.overrun else c.inkSecondary)
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TransactionTags.BUDGET_SHEET_NEW).semantics(mergeDescendants = true) {}
                .clickable(role = Role.Button, onClick = new).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(R.drawable.i_mais, tint = c.info, size = 20.dp)
            Text("Nova verba", sans(15f, 600, 20f), color = c.info)
        }
    }
}

/** "33 lançamentos" / "1 lançamento". */
private fun usesText(n: Int): String = if (n == 1) "1 lançamento" else "$n lançamentos"

/**
 * Book 8.12: the chosen chips and "Buscar ou criar marcador" with the shortcuts, results and Criar
 * under it (spec §7.3). [TransactionTags.TAG_SEARCH].
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TagPicker(
    chips: Map<String, Char>, tags: List<TagRow>, query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit,
    choose: (String) -> Unit, remove: (String) -> Unit, room: Dp, verb: String,
) {
    val c = Margem.colors
    var focused by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    val clean = query.trim().lowercase()
    val q = fold(query.trim())
    val byUse = compareByDescending<TagRow> { it.uses }.thenBy { it.name }
    // E1.2: the chosen ones are results too (with a ✓); a name made here and not saved has uses −1 (no count).
    val known = tags + chips.keys.filter { n -> tags.none { it.name == n } }.map { TagRow(name = it, uses = -1) }
    val matches = if (clean.isEmpty()) emptyList() else known.filter { fold(it.name).contains(q) }
        .sortedWith(compareBy<TagRow> { if (fold(it.name).startsWith(q)) 0 else 1 }.then(byUse))
    val create = clean.isNotEmpty() && tags.none { it.name == clean } && clean !in chips
    LaunchedEffect(query, focused) { if (focused) bring.bringIntoView() }

    if (chips.isNotEmpty()) FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((name, st) in chips) TagChip(
            name,
            Modifier.testTag(TransactionTags.TAG + name).semantics {
                selected = true
                if (st == 'S') stateDescription = "sugerido"
            },
            state = if (st == 'S') ChipState.SUGGESTED else ChipState.SELECTED, removable = true,
        ) { remove(name) }
    }
    // The search and what hangs from it are brought into view together, sized to the room above the keyboard.
    Column(Modifier.bringIntoViewRequester(bring)) {
        SearchLine(
            query, onQuery, "Buscar ou criar marcador", TransactionTags.TAG_SEARCH,
            Modifier.onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            },
        ) { choose(tags.firstOrNull { it.name == clean }?.name ?: clean) }

        if (focused && clean.isEmpty()) {
            val shortcuts = tags.filter { it.name !in chips }.sortedWith(byUse).take(8)
            if (shortcuts.isNotEmpty()) {
                Text("MAIS USADOS", sans(11f, 600, 14f, 0.12f), Modifier.padding(top = 10.dp), c.inkTertiary)
                FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in shortcuts) TagChip(t.name, Modifier.testTag(TransactionTags.TAG_SHORTCUT + t.name)) { choose(t.name) }
                }
            }
        }
        if (clean.isNotEmpty()) Column(
            Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(max = (room - 62.dp).coerceAtLeast(96.dp))
                .background(c.surface, RoundedCornerShape(6.dp)).border(1.dp, c.rule, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState()),
        ) {
            if (matches.isEmpty()) Text(
                "Nenhum marcador com “$clean”.", sans(13.5f, 400, 18f),
                Modifier.testTag(TransactionTags.TAG_NONE).padding(horizontal = 14.dp, vertical = 11.dp), c.inkTertiary,
            )
            for (t in matches) {
                val on = t.name in chips
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TransactionTags.TAG_RESULT + t.name)
                        .semantics(mergeDescendants = true) { selected = on }
                        .clickable(role = Role.Button) { if (!on) choose(t.name) }
                        .drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (on) Icon(R.drawable.i_lancar, tint = c.ink, size = 18.dp)
                    // Book 8.12: the part found, folded like the search, in bold on the name's own letters (spec §7.3, 2026-10-24).
                    val found = foldedMatch(t.name, q)
                    Text(
                        buildAnnotatedString {
                            append(t.name)
                            if (found != null) addStyle(SpanStyle(fontWeight = FontWeight.Bold), found.first, found.last + 1)
                        },
                        sans(16f, 500, 20f), Modifier.weight(1f), if (on) c.ink else c.inkSecondary, maxLines = 1,
                    )
                    // Spec §7.3: a chosen one shows its ✓ and nothing more.
                    if (!on && t.uses >= 0) Text(usesText(t.uses), sans(13f, 400, 16f, tabular = true), color = c.inkTertiary)
                }
            }
            if (create) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TransactionTags.TAG_CREATE).semantics(mergeDescendants = true) {}
                    .clickable(role = Role.Button) { choose(clean) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(R.drawable.i_mais, tint = c.info, size = 20.dp)
                Text("Criar “$clean”", sans(16f, 600, 20f), Modifier.weight(1f), c.info, maxLines = 1)
                Text("novo, só é gravado ao $verb", sans(13f, 400, 16f), color = c.inkTertiary)
            }
        }
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
