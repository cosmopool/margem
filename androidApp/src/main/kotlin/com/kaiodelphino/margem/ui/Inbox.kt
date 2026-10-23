package com.kaiodelphino.margem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kaiodelphino.margem.Cents
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.fold
import com.kaiodelphino.margem.NO_MONTH
import com.kaiodelphino.margem.NO_TIME
import com.kaiodelphino.margem.NoticeId
import com.kaiodelphino.margem.NoticeRow
import com.kaiodelphino.margem.Planned
import com.kaiodelphino.margem.NoticeStatus
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.RefundCandidate
import com.kaiodelphino.margem.accessSettingsIntent
import com.kaiodelphino.margem.widgets.Amount
import com.kaiodelphino.margem.widgets.Button
import com.kaiodelphino.margem.widgets.ButtonKind
import com.kaiodelphino.margem.widgets.CapturePaused
import com.kaiodelphino.margem.widgets.Check
import com.kaiodelphino.margem.widgets.CreditLine
import com.kaiodelphino.margem.widgets.DayHeader
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Field
import com.kaiodelphino.margem.widgets.Icon
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.NoticeCard
import com.kaiodelphino.margem.widgets.PageHeader
import com.kaiodelphino.margem.widgets.SectionLine
import com.kaiodelphino.margem.widgets.Snack
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.dateLong
import com.kaiodelphino.margem.widgets.dateShort
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.moneyPlain
import com.kaiodelphino.margem.widgets.monthShort
import com.kaiodelphino.margem.widgets.sans
import com.kaiodelphino.margem.widgets.serif
import com.kaiodelphino.margem.widgets.time
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Test tags of the Pauta and of Registrar estorno (D35): the contract of their UI tests. Same setup and
 * rules as [BudgetTags]: `App(db, null) {}` in `MargemTheme(dark = false)` with `LocalToday` fixed, a Db on
 * AndroidSQLiteDriver (D28), elements found only by tags. Notices enter by `capture(db, raw)` from a
 * registered source. Dates and times of notices are read in the device's time zone
 * (TimeZone.currentSystemDefault()).
 *
 * The Pauta tab is reached by the tab bar or by BudgetTags.PAUTA_STRIP. The app-wide snackbar is
 * [com.kaiodelphino.margem.widgets.SnackTags]: a snack's action works even after the screen that showed
 * it is gone (it runs on the App's scope).
 *
 * "Este recado não está mais na Pauta." ([GONE_TEXT]) is the one answer whenever a notice stops being
 * pending under an open screen (launched, dismissed or refunded by another path): opening, submitting or
 * dismissing from Lançar or Registrar estorno shows it as a snack and goes back to the Pauta, writing nothing.
 */
object InboxTags {
    /** The snack text when the notice is no longer pending (see the object's KDoc). */
    const val GONE_TEXT = "Este recado não está mais na Pauta."

    // ---- Pauta (spec §7.2, book 10.2) ----

    /** The scrollable list: the paused notice, day headers and cards. Plain container. */
    const val LIST = "inbox.list"

    /** "A anotação automática está pausada.", first in the list, only while notification access is off. */
    const val PAUSED = "inbox.paused"

    /** "Pauta limpa.", instead of the cards when nothing is pending. */
    const val EMPTY = "inbox.empty"

    /**
     * Prefix: DAY + the date ("inbox.day.2026-10-09"), one per day with pending notices, newest first:
     * "Hoje · sexta, 9 de outubro", "Ontem · quinta, 8 de outubro", "Quarta, 7 de outubro" ([dayTitle]).
     */
    const val DAY = "inbox.day."

    /**
     * Prefix: CARD + notice id, one per pending notice (Db.pending order: newest posted first), under its
     * day. Shows the source (app label), the time ("07:42"), the bank text in quotes, and — unless it
     * looks like an ad — the merchant (alias when there is one, else the parsed merchant) and the value
     * read (money), with "ESTORNO" on an estorno notice. "▲ Parece propaganda" when NoticeRow.looksLikeAd.
     */
    const val CARD = "inbox.card."

    /**
     * Prefix: NEW + notice id: present exactly while the card is shown as new (NoticeRow.isNew, spec
     * §2.11 — the argila double rule). Notices stay new while the Pauta is open; everything posted up to
     * when it was opened is no longer new on the next visit (Db.markPautaSeen, written on opening).
     */
    const val NEW = "inbox.new."

    /**
     * Prefix: ACCEPT + notice id: "Lançar"; "Registrar estorno" on an estorno notice (Parsed.isRefund);
     * "Lançar mesmo assim" when it looks like an ad (an ad that reads as estorno is still an ad). Click opens
     * Registrar estorno for an estorno notice, the Lançar form ([TransactionTags]) for any other.
     */
    const val ACCEPT = "inbox.accept."

    /**
     * Prefix: DISMISS + notice id: "Riscar". Click dismisses it (Db.dismiss): the card leaves and the snack
     * "Recado riscado." with "Desfazer" (Db.restore) appears.
     */
    const val DISMISS = "inbox.dismiss."

    // ---- Recado reconhecido de um previsto (spec §2.13, §7.2, book 8.4; D60, D62) ----
    // While NoticeRow.planned is non-zero, the card's ACCEPT is [PLANNED_LAUNCH], DISMISS is the Riscar icon
    // (content description "Riscar") and [PLANNED_NOT_IT] sits before them. Clicking the card body opens the
    // Lançar form already linked (TransactionTags.FORM_LINKED).

    /**
     * Prefix: PLANNED_BAND + notice id: the anil band "É o previsto Internet" (Planned.name) and
     * "Casa · contas, internet · dia 10" (budget · tags joined by ", " · "dia " + Planned.day). Present while
     * the recado is recognized and not set aside by [PLANNED_NOT_IT].
     */
    const val PLANNED_BAND = "inbox.planned."

    /**
     * Prefix: PLANNED_NOT_IT + notice id: "Não é". Takes the band away while the Pauta stays open, writing
     * nothing (D60): the card turns into an ordinary one ("Lançar", "Riscar"); reopening the Pauta shows
     * the band again.
     */
    const val PLANNED_NOT_IT = "inbox.plannedNotIt."

    /**
     * Prefix: PLANNED_LAUNCH + notice id: "Lançar previsto". Lança with no form (P5): Db.accept with the form of
     * the previsto and the recado (budget and tags of the previsto, caption = its name, total, bank text,
     * date and time of the recado, occurrence). The card leaves and the snack "Lançado: Internet, em Casa.
     * O próximo é 10 nov." ("Lançado: Internet, em Casa." for Só uma vez; day and monthShort of Planned.next)
     * with "Desfazer" (Db.deleteTransaction: recado and occurrence come back, S1). Refused: [GONE_TEXT].
     */
    const val PLANNED_LAUNCH = "inbox.plannedLaunch."

    // ---- Registrar estorno (spec §6, §7.3, book 10.4), Screen.Refund ----

    /** The top bar: "Registrar estorno". */
    const val REFUND_TITLE = "refund.title"

    /** The scrollable (lazy) list under the top bar, with everything below but the buttons. Plain container. */
    const val REFUND_LIST = "refund.list"

    /** The estorno notice: "Cartão Ipê · qui, 8 out, 21:03" (source · dateShort · time) and the bank text in quotes. */
    const val REFUND_NOTICE = "refund.notice"

    /**
     * Search input (lupa), placeholder "nome, legenda ou valor". Filters the candidates as the person
     * types: a candidate stays when its legenda, alias, bank text or name contains the query ignoring case
     * and accents ("estação" finds LOJA ESTACAO; the shared `fold`, D61), or
     * when moneyPlain of its total contains it ("59,9" keeps R$ 59,90). Blank = no filter. The selection
     * stays even when the selected candidate is filtered out.
     */
    const val REFUND_SEARCH = "refund.search"

    /**
     * "Sugerida a mais provável": present when the first candidate of Db.refundCandidates is likely; it
     * heads that candidate. Absent when no candidate is likely or the search hides the first one.
     */
    const val REFUND_LIKELY = "refund.likely"

    /**
     * Prefix: CANDIDATE_TX + transaction id, or CANDIDATE_NOTICE + notice id: one per Db.refundCandidates
     * entry that passes the search, in that order. Headline: the legenda in italic, one line ("…" when it
     * passes), else the name; with a legenda, "em" + the name under it (no credit line without a name).
     * A transaction also shows "Casa · sex, 2 out · lançamento" (budget · dateShort · "lançamento") and its
     * total (money); a notice shows "hoje, 07:15 · recado na pauta" ("hoje", "ontem" or dateShort of its
     * date, then time) and its value. Click selects it (`selected`); exactly one or none is selected. On
     * open the first candidate is selected when it is likely; otherwise none.
     */
    const val CANDIDATE_TX = "refund.candidate.t."
    const val CANDIDATE_NOTICE = "refund.candidate.n."

    /**
     * Instead of the candidates: "Nenhuma compra para estornar." when there are none, "Nada encontrado."
     * when the search hides all of them.
     */
    const val REFUND_EMPTY = "refund.empty"

    /**
     * "Casa: margem R$ 738,70 → R$ 798,60" (Db.previewRefund of the selected transaction; a negative margin
     * is written "R$ 12,00 além"). Present only while a transaction is selected.
     */
    const val REFUND_PREVIEW = "refund.preview"

    /**
     * "Registrar estorno": disabled until a candidate is selected. Click registers it (Db.registerRefund
     * with the transaction or the notice) and goes back to the Pauta with the snack "Estorno registrado: a
     * compra de 2 out não conta mais." (day and monthShort of the candidate's date) and "Desfazer"
     * (Db.undoRefund). Refused: [GONE_TEXT], back to the Pauta.
     */
    const val REFUND_SUBMIT = "refund.submit"

    /** "Riscar": dismisses the estorno notice; back to the Pauta with "Recado riscado." and "Desfazer". */
    const val REFUND_DISMISS = "refund.dismiss"

    /** "Não é estorno? Lançar como compra": replaces this screen with the Lançar form of the same notice. */
    const val REFUND_AS_PURCHASE = "refund.asPurchase"
}

/**
 * Pauta (spec §7.2, book 10.2): the anotação pausada notice when access is off, the pending notices
 * grouped by day, newest first, or "Pauta limpa.". Tags: [InboxTags].
 */
@Composable
fun Inbox(db: Db, granted: Boolean, snack: SnackHost, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalToday.current()
    val pending = query(db, today) { it.pending(today) }
    // "Não é" (D60): sets the band aside while the Pauta is open, across recreation and a Lançar and back; nothing is written.
    var notIt by rememberSaveable { mutableStateOf(longArrayOf()) }
    // Lançar previsto writing (no second tap while it runs, so its Desfazer isn't replaced by a refusal).
    var launching by remember { mutableStateOf(setOf<Long>()) }
    // Notices posted since the last visit stay "new" while the person is here: the visit is marked seen
    // on opening (no write on leaving), and the new marks use the previous visit, kept across recreation.
    val openedAt = rememberSaveable { Clock.System.now().toEpochMilliseconds() }
    var seenBefore by rememberSaveable { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        if (seenBefore < 0) seenBefore = db.settings().pautaSeenAt
        db.markPautaSeen(openedAt)
    }
    val zone = TimeZone.currentSystemDefault()
    Column {
        TopBar("Pauta")
        LazyColumn(
            Modifier.testTag(InboxTags.LIST),
            contentPadding = PaddingValues(start = Margem.space.page, end = Margem.space.page, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!granted) item {
                CapturePaused(
                    { context.startActivity(accessSettingsIntent(context)) },
                    Modifier.padding(top = 8.dp).testTag(InboxTags.PAUSED).semantics(mergeDescendants = true) {}, onManual = { go(Screen.NewTransaction) },
                )
            }
            if (pending == null || seenBefore < 0) return@LazyColumn
            if (pending.isEmpty()) item {
                EmptyState(
                    R.drawable.i_pauta, "Pauta limpa.", "Nenhum recado esperando. Os próximos chegam sozinhos.",
                    Modifier.testTag(InboxTags.EMPTY).semantics(mergeDescendants = true) {},
                )
            }
            val byDay = pending.groupBy { Instant.fromEpochMilliseconds(it.raw.postedAt).toLocalDateTime(zone).date }
            for ((day, notices) in byDay) {
                item(key = "day-$day") { DayHeader(dayTitle(day, today), Modifier.testTag(InboxTags.DAY + day).semantics(mergeDescendants = true) {}) }
                items(notices, key = { it.id.v }) { row ->
                    val n = row.copy(isNew = row.raw.postedAt > seenBefore)
                    val p = if (n.id.v in notIt) Planned() else n.planned
                    val recognized = p.occurrence.v != 0L
                    NoticeCard(
                        n,
                        onAccept = {
                            when {
                                // Lançar previsto (P5): no form, the previsto's budget, tags and name with the recado's values.
                                // In the snack's scope: leaving the Pauta while it writes still shows Desfazer.
                                recognized -> if (n.id.v !in launching) {
                                    launching = launching + n.id.v
                                    snack.scope.launch {
                                        val id = db.accept(n.id, formFrom(p, n))
                                        if (id.v == 0L) snack.show(Snack(InboxTags.GONE_TEXT))
                                        else snack.show(Snack(launchedText(p, p.budgetName), "Desfazer") { db.deleteTransaction(id) })
                                        launching = launching - n.id.v
                                    }
                                }
                                n.parsed.isRefund && !n.looksLikeAd -> go(Screen.Refund(n.id))
                                else -> go(Screen.Post(n.id))
                            }
                        },
                        onDismiss = { scope.launch { riscar(db, n.id, snack) } },
                        modifier = Modifier.testTag(InboxTags.CARD + n.id.v).semantics(mergeDescendants = true) {},
                        acceptTag = (if (recognized) InboxTags.PLANNED_LAUNCH else InboxTags.ACCEPT) + n.id.v,
                        dismissTag = InboxTags.DISMISS + n.id.v, newTag = InboxTags.NEW + n.id.v,
                        planned = p, onNotIt = { notIt = notIt + n.id.v }, acceptEnabled = n.id.v !in launching,
                        bandTag = InboxTags.PLANNED_BAND + n.id.v, notItTag = InboxTags.PLANNED_NOT_IT + n.id.v,
                        onBody = if (recognized) ({ go(Screen.Post(n.id, p.occurrence)) }) else null,
                    )
                }
            }
        }
    }
}

/** Riscar with "Desfazer" (spec §2.3); false when the notice was no longer pending. */
suspend fun riscar(db: Db, id: NoticeId, snack: SnackHost): Boolean {
    if (!db.dismiss(id)) return false
    snack.show(Snack("Recado riscado.", "Desfazer") { db.restore(id) })
    return true
}

/** "Cartão Ipê · qui, 8 out, 21:03": source, day and time the notice arrived, in the device's zone. */
fun noticeLine(n: NoticeRow): String {
    val at = Instant.fromEpochMilliseconds(n.raw.postedAt).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${n.raw.appLabel} · ${dateShort(at.date)}, ${time(at.time)}"
}

/** Book `.quoteblk`: where the notice came from and its bank text, quoted — a citation, not data. */
@Composable
fun NoticeQuote(n: NoticeRow, modifier: Modifier = Modifier) {
    val c = Margem.colors
    Column(modifier.fillMaxWidth().background(c.surfaceSunken, RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(noticeLine(n), sans(12f, 600, 16f, 0.04f), color = c.inkTertiary)
        Text("“${n.raw.body}”", serif(14, 400, 20, italic = true), Modifier.padding(top = 4.dp), c.inkSecondary)
    }
}

/** A margin in a sentence: "R$ 34,00", or "R$ 12,00 além" when negative. */
fun marginText(c: Cents) = if (c.v < 0) "${money(Cents(-c.v))} além" else money(c)

private class RefundData(
    val notice: NoticeRow, val candidates: List<RefundCandidate>,
    val budgets: Map<Long, String>, val pending: Map<Long, NoticeRow>,
)

private fun RefundCandidate.key() = if (transaction.v != 0L) "t:${transaction.v}" else "n:${notice.v}"

/**
 * Registrar estorno (book 10.4) for the pending estorno notice [estorno]: pick the cancelled purchase
 * among Db.refundCandidates. [replace] swaps this screen for another (Lançar como compra); [back] returns
 * to the Pauta. Tags: [InboxTags].
 */
@Composable
fun RefundScreen(db: Db, estorno: NoticeId, snack: SnackHost, replace: (Screen) -> Unit, back: () -> Unit) {
    val c = Margem.colors
    val scope = rememberCoroutineScope()
    val today = LocalToday.current()
    val data = query(db, estorno) {
        RefundData(
            it.notice(estorno), it.refundCandidates(estorno),
            it.transactions().associate { t -> t.id.v to t.budgetName }, it.pending().associateBy { n -> n.id.v },
        )
    }
    var search by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf("") }
    var opened by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(data) {
        if (data == null || opened) return@LaunchedEffect
        if (data.notice.id.v == 0L || data.notice.status != NoticeStatus.PENDING) {
            snack.show(Snack(InboxTags.GONE_TEXT))
            back()
            return@LaunchedEffect
        }
        picked = data.candidates.firstOrNull()?.takeIf { it.likely }?.key() ?: ""
        opened = true
    }
    val chosen = data?.candidates?.firstOrNull { it.key() == picked }
    val preview = if (chosen != null && chosen.transaction.v != 0L) query(db, chosen.transaction, today) { it.previewRefund(chosen.transaction, today) } else null

    fun submit() {
        if (busy || chosen == null) return
        busy = true
        scope.launch {
            val ok = if (chosen.transaction.v != 0L) db.registerRefund(estorno, chosen.transaction) else db.registerRefund(estorno, chosen.notice)
            if (ok) snack.show(Snack("Estorno registrado: a compra de ${chosen.date.day} ${monthShort(chosen.date.month)} não conta mais.", "Desfazer") { db.undoRefund(estorno) })
            else snack.show(Snack(InboxTags.GONE_TEXT))
            back()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Registrar estorno", Modifier.testTag(InboxTags.REFUND_TITLE).semantics(mergeDescendants = true) {}, onBack = back, close = true)
        if (data == null || !opened) return@Column
        val q = fold(search.trim())
        val shown = data.candidates.filter {
            q.isEmpty() || fold(it.caption).contains(q) || fold(it.alias).contains(q) || fold(it.merchant).contains(q) ||
                fold(it.name).contains(q) || moneyPlain(it.total).contains(search.trim())
        }
        LazyColumn(Modifier.weight(1f).testTag(InboxTags.REFUND_LIST), contentPadding = PaddingValues(start = Margem.space.page, end = Margem.space.page, bottom = 24.dp)) {
            item { NoticeQuote(data.notice, Modifier.testTag(InboxTags.REFUND_NOTICE).semantics(mergeDescendants = true) {}) }
            item {
                PageHeader(
                    "Estorno", "Qual compra foi estornada?", Modifier.padding(top = 16.dp),
                    "Estorno é só um registro: não pede verba nem marcador. A compra escolhida deixa de contar.",
                )
            }
            item {
                Field(search, { search = it }, "Buscar", placeholder = "nome, legenda ou valor", tag = InboxTags.REFUND_SEARCH) {
                    Icon(R.drawable.i_lupa, tint = c.inkTertiary, size = 20.dp)
                }
            }
            if (shown.isEmpty()) item {
                Text(
                    if (data.candidates.isEmpty()) "Nenhuma compra para estornar." else "Nada encontrado.", Margem.type.body,
                    Modifier.padding(top = 12.dp).testTag(InboxTags.REFUND_EMPTY), c.inkSecondary,
                )
            }
            val likely = shown.firstOrNull()?.takeIf { it.likely && it == data.candidates.first() }
            if (likely != null) {
                item { SectionLine("Sugerida a mais provável", Modifier.testTag(InboxTags.REFUND_LIKELY).semantics(mergeDescendants = true) {}) }
                item(key = likely.key()) { CandidateRow(likely, likely.key() == picked, data, today) { picked = likely.key() } }
            }
            val rest = if (likely == null) shown else shown.drop(1)
            if (rest.isNotEmpty() && likely != null) item { SectionLine("Ou escolha outra") }
            items(rest, key = { it.key() }) { cand -> CandidateRow(cand, cand.key() == picked, data, today) { picked = cand.key() } }
            if (preview != null && chosen != null && preview.month != NO_MONTH) item {
                val name = data.budgets[chosen.transaction.v].orEmpty()
                Text(
                    "$name: margem ${marginText(preview.before)} → ${marginText(preview.after)}", sans(15f, 500, 20f, tabular = true),
                    Modifier.padding(top = 16.dp).testTag(InboxTags.REFUND_PREVIEW),
                )
            }
            item {
                Button(
                    "Não é estorno? Lançar como compra", { replace(Screen.Post(estorno)) },
                    Modifier.padding(top = 12.dp).testTag(InboxTags.REFUND_AS_PURCHASE), kind = ButtonKind.TEXT,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Margem.space.page, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Riscar", {
                if (!busy) {
                    busy = true
                    scope.launch {
                        if (!riscar(db, estorno, snack)) snack.show(Snack(InboxTags.GONE_TEXT))
                        back()
                    }
                }
            }, Modifier.weight(1f).testTag(InboxTags.REFUND_DISMISS), kind = ButtonKind.DISMISS, icon = R.drawable.i_riscar)
            Button(
                "Registrar estorno", ::submit, Modifier.weight(1.4f).testTag(InboxTags.REFUND_SUBMIT),
                icon = R.drawable.i_estorno, enabled = chosen != null,
            )
        }
    }
}

/** One purchase the estorno may cancel: name, where it is, its value; a radio mark when chosen. */
@Composable
private fun CandidateRow(cand: RefundCandidate, on: Boolean, data: RefundData, today: LocalDate, onClick: () -> Unit) {
    val c = Margem.colors
    val sub = if (cand.transaction.v != 0L) "${data.budgets[cand.transaction.v].orEmpty()} · ${dateShort(cand.date)} · lançamento"
    else {
        val n = data.pending[cand.notice.v]
        val day = when (cand.date) {
            today -> "hoje"
            today.minus(DatePeriod(days = 1)) -> "ontem"
            else -> dateShort(cand.date)
        }
        "$day, ${time(n?.parsed?.time ?: NO_TIME)} · recado na pauta"
    }
    val tag = if (cand.transaction.v != 0L) InboxTags.CANDIDATE_TX + cand.transaction.v else InboxTags.CANDIDATE_NOTICE + cand.notice.v
    Row(
        Modifier.fillMaxWidth().testTag(tag).semantics { selected = on }.clickable(role = Role.RadioButton, onClick = onClick)
            .then(if (on) Modifier.background(c.surfaceSunken) else Modifier)
            .drawBehind { drawLine(c.rule, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Check(on)
        Column(Modifier.weight(1f)) {
            if (cand.caption.isEmpty()) Text(cand.name, serif(17, 500, 22), maxLines = 1)
            else {
                // §2.12: the legenda is the name (one line), the merchant drops to the "em …" credit.
                Text(cand.title, serif(17, 500, 22, italic = true), maxLines = 1)
                if (cand.name.isNotEmpty()) CreditLine(cand.name, cand.alias.isNotEmpty(), modifier = Modifier.padding(top = 1.dp))
            }
            Text(sub, sans(13f, 400, 17f), Modifier.padding(top = 2.dp), c.inkTertiary, maxLines = 2)
        }
        Amount(cand.total, size = 17)
    }
}

/** "Hoje · sexta, 9 de outubro", "Ontem · quinta, 8 de outubro", "Quarta, 7 de outubro". */
fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Hoje · ${dateLong(day)}"
    today.minus(DatePeriod(days = 1)) -> "Ontem · ${dateLong(day)}"
    else -> dateLong(day).replaceFirstChar { it.uppercase() }
}
