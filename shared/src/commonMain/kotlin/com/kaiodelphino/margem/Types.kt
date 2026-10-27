package com.kaiodelphino.margem

import kotlin.jvm.JvmInline
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth

// Zero is initialization: every type here has a valid empty value, and an id of 0 means "none" or "refused".

/** Centavos: R$ 31,50 = Cents(3150). */
@JvmInline value class Cents(val v: Long) {
    operator fun plus(o: Cents) = Cents(v + o.v)
    operator fun minus(o: Cents) = Cents(v - o.v)
    operator fun compareTo(o: Cents) = v.compareTo(o.v)
}

@JvmInline value class NoticeId(val v: Long)
@JvmInline value class TransactionId(val v: Long)
@JvmInline value class BudgetId(val v: Long)
@JvmInline value class TagId(val v: Long)
@JvmInline value class PlannedId(val v: Long)

/** One month of a previsto (D59): the row of `planned_dates`, never deleted. */
@JvmInline value class OccurrenceId(val v: Long)

/** The empty date and time of zero values. */
val NO_DATE = LocalDate(1970, 1, 1)
val NO_TIME = LocalTime(0, 0)
val NO_MONTH = YearMonth(1970, 1)

/**
 * Capture boundary (D25): the androidApp fills it from a StatusBarNotification, the cobudget import
 * from an old row. Absent text is "", absent time is 0. Texts arrive trimmed.
 */
data class RawNotice(
    val packageName: String = "",
    val appLabel: String = "",
    /** StatusBarNotification.key: stable across re-posts of the same notification. */
    val key: String = "",
    /** StatusBarNotification.postTime, epoch ms. Changes on every re-post. */
    val postedAt: Long = 0,
    /** Notification.when, epoch ms: the event time the app set; 0 = not set. */
    val whenMs: Long = 0,
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val subText: String = "",
    /** InboxStyle lines joined with '\n'. */
    val textLines: String = "",
    val category: String = "",
    val channelId: String = "",
    val extrasJson: String = "",
) {
    /** The most complete human-readable body: big text, else the lines, else the text. */
    val body: String get() = bigText.ifEmpty { textLines.ifEmpty { text } }
}

/**
 * What the app read from a notice's text, before any emenda (glossary: Valor sugerido).
 * cents 0 / merchant "" = not found; date and time fall back to the notice's own time.
 */
data class Parsed(
    val cents: Cents = Cents(0),
    val merchant: String = "",
    val date: LocalDate = NO_DATE,
    val time: LocalTime = NO_TIME,
    /** The text announces an estorno: the Pauta offers Registrar estorno instead of Lançar. */
    val isRefund: Boolean = false,
)

enum class BudgetOrder { RISK, MANUAL, AZ }

enum class Theme { SYSTEM, LIGHT, DARK }

enum class PaceStatus { ON_PACE, AHEAD, OVERRUN }

/** Derived, never stored (spec §2, architecture §5). */
enum class NoticeStatus { PENDING, DISMISSED, PROCESSED }

/** "Repete" (spec §2.13, P8, D64). Stored by ordinal: ONCE = 0 = zero. Never reorder. */
enum class Repeats { ONCE, MONTHLY }

/**
 * One open occurrence of a previsto (spec §2.13, D59, D62): the rule and the month together, everything
 * "A caminho", the previsto sheet, the Pauta band, the snackbar and "Qual previsto é este recado?" show.
 * Inside a [PlannedLine] it may be a closed (lançada) one. `Planned()` (zero, [occurrence] 0) = no previsto.
 */
data class Planned(
    val occurrence: OccurrenceId = OccurrenceId(0),
    val planned: PlannedId = PlannedId(0),
    /** Clean like a legenda (D53, D62): becomes the lançamento's caption; at most [MAX_CAPTION]. */
    val name: String = "",
    val budget: BudgetId = BudgetId(0),
    val budgetName: String = "",
    /** Sorted. */
    val tags: List<String> = emptyList(),
    /** The previsto's own value (P7: never replaced by a recado's). */
    val cents: Cents = Cents(0),
    /** 1..31: the day of every occurrence; a month without it uses its last day (P8). */
    val day: Int = 0,
    val repeats: Repeats = Repeats.ONCE,
    /** This occurrence's date: [day] in its calendar month, clamped to the month's last day. */
    val date: LocalDate = NO_DATE,
    /** [MONTHLY][Repeats.MONTHLY]: the date of the occurrence of the month after [date] ("O próximo é 10 nov."); NO_DATE for ONCE. */
    val next: LocalDate = NO_DATE,
    /** Has a link (fonte + texto do banco, D59): recados that match are recognized (D60). */
    val linked: Boolean = false,
    /** The bank text the link was made from, raw, for display only (R4b): "SMARTFIT". "" when not linked. */
    val linkText: String = "",
    /** The label of the linked fonte (Source.label; the package when the source was removed). "" when not linked. */
    val linkSource: String = "",
    /** [date] < the `today` of the read: waiting past its day (P10). Always false in reads without today. */
    val late: Boolean = false,
    /** A pending recado is recognized as this occurrence (D60): "recado na Pauta". */
    val noticeInPauta: Boolean = false,
)

data class Settings(
    /** 1..31; a day missing from a month means that month's last day (spec §3). */
    val monthStartDay: Int = 1,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val theme: Theme = Theme.SYSTEM,
    /** Order of the Verbas tab. */
    val budgetOrder: BudgetOrder = BudgetOrder.RISK,
    /** Order of the Verbas widget. */
    val widgetOrder: BudgetOrder = BudgetOrder.RISK,
    /** Epoch ms of the last time the Pauta was opened; notices posted after it are new. */
    val pautaSeenAt: Long = 0,
)

/**
 * What [importCobudget] did (spec §7.6, D3, D40). [opened] = false: the file was refused (not a cobudget
 * backup of schema version 7) and nothing was written; every count is then 0.
 */
data class CobudgetImport(
    val opened: Boolean = false,
    /** Notices that entered as pending (not dismissed in the cobudget). */
    val pending: Int = 0,
    /** Notices that entered as dismissed (Riscados): dismissed in the cobudget. */
    val dismissed: Int = 0,
    /** Notices left out: re-posts by the capture dedupe (D4), or without any text. */
    val skipped: Int = 0,
)

/** An app whose notifications become notices. */
data class Source(val packageName: String = "", val label: String = "", val addedAt: Long = 0)

/** A notice as the Pauta, Riscados and the accept screen show it. */
data class NoticeRow(
    val id: NoticeId = NoticeId(0),
    val raw: RawNotice = RawNotice(),
    val parsed: Parsed = Parsed(),
    /** Alias of the parsed merchant; "" = none. */
    val alias: String = "",
    val status: NoticeStatus = NoticeStatus.PENDING,
    /** Posted after Settings.pautaSeenAt. */
    val isNew: Boolean = false,
    /** Neither a value nor a merchant was read: the button becomes "Lançar mesmo assim". */
    val looksLikeAd: Boolean = false,
    /**
     * The open occurrence this pending recado is recognized as (D60, D62): same fonte and same
     * [MerchantKey] of its bank text as a linked previsto, its date within 10 days of the recado's date,
     * the closest one (ties: earlier date, then lower occurrence id). Zero when none, always zero for a
     * recado whose bank text gives an empty key (R2), and in every read but [Db.pending].
     */
    val planned: Planned = Planned(),
)

/** The most a legenda takes (spec §2.12, D53, D55): `String.length` units — the field cuts here, the Db refuses past it. */
const val MAX_CAPTION = 80

/** The input of accept, addManual and edit (spec §7.3). The Db parses it: see [Db.accept]. */
data class TransactionForm(
    val budget: BudgetId = BudgetId(0),
    /** Tag names; trimmed, lowercased, blanks and repeats dropped. New names create tags. */
    val tags: List<String> = emptyList(),
    /** Total of the purchase, split into [installments]. */
    val total: Cents = Cents(0),
    /** 1..400; 1 = à vista. */
    val installments: Int = 1,
    /** Bank text of the merchant, trimmed; "" is allowed. */
    val merchant: String = "",
    val date: LocalDate = NO_DATE,
    val time: LocalTime = NO_TIME,
    /** Non-blank sets the merchant's alias; blank leaves the alias as it is. */
    val alias: String = "",
    /**
     * The legenda of this one lançamento (spec §2.12, D52–D55); "" = none. The Db cleans it on write:
     * every line break (LF, CRLF, CR, U+2028, U+2029) becomes one space, the edges are trimmed (whitespace
     * and the NBSP family) and the text goes through `storable`; whitespace-only becomes "" (no legenda).
     * Over 80 characters after cleaning (`String.length` — an astral emoji counts 2) refuses the whole form
     * like an out-of-range installment (D53): nothing is written and a refused edit keeps the previous state.
     * Blank removes the legenda on edit, unlike [alias] (§2.10). Never comes from a [Suggestion] and never
     * sets `corrected` (not an emenda).
     */
    val caption: String = "",
    /**
     * The open occurrence this lançamento closes (D62); 0 = none. Insert only (accept, addManual): stored
     * as the lançamento's origin, which edit never changes. Refused when the occurrence is not open
     * (skipped, or closed by another lançamento). With a recado whose bank text gives a non-empty key,
     * accept also (re)writes the previsto's link from that recado's fonte and bank text (not from
     * [merchant]); a manual one writes no link (R2, R6b).
     */
    val occurrence: OccurrenceId = OccurrenceId(0),
    /**
     * "Repete" (D64). MONTHLY, in the same write: a new previsto named after the line (caption, else alias,
     * else bank text, cleaned like the caption), with this budget, tags, total and the day of [date], first
     * occurrence in the calendar month after [date]'s, linked like [occurrence] says when there is a recado.
     * With [planned] ≠ 0, the previsto's own repetition.
     */
    val repeats: Repeats = Repeats.ONCE,
    /**
     * Editar previsto (D64): ≠ 0 makes [Db.addManual] update this previsto's rule — name ([caption], else
     * [merchant], cleaned like the caption), budget, tags, value ([total]), day (of [date]) and [repeats] —
     * and nothing else: no lançamento. Its open occurrences follow the new day. Ignored by accept and edit.
     */
    val planned: PlannedId = PlannedId(0),
)

/**
 * What [Db.addManual] wrote: a lançamento, a previsto, or both ("Todo mês" from today or the past).
 * Both zero = refused, nothing written.
 */
data class Saved(val transaction: TransactionId = TransactionId(0), val planned: PlannedId = PlannedId(0))

/** One line of Lançamentos and of the budget detail. */
data class TransactionRow(
    val id: TransactionId = TransactionId(0),
    /** 0 = manual. */
    val notice: NoticeId = NoticeId(0),
    val budget: BudgetId = BudgetId(0),
    val budgetName: String = "",
    /** Bank text (glossary: Texto do banco). */
    val merchant: String = "",
    /** "" = none. */
    val alias: String = "",
    val total: Cents = Cents(0),
    val installments: Int = 1,
    val date: LocalDate = NO_DATE,
    val time: LocalTime = NO_TIME,
    /** Sorted. */
    val tags: List<String> = emptyList(),
    /** The estorno notice that cancels this purchase; 0 = not refunded. */
    val estorno: NoticeId = NoticeId(0),
    /** Differs from the notice's [Parsed] (glossary: emendado). Manual ones never are. */
    val corrected: Boolean = false,
    /**
     * The installment dated in the budget month of the `today` given to [Db.transactions] (by the
     * current month start day): "Parcela 3 de 10 · R$ 120,00" in Lançamentos. Zero (k = 0) when none
     * falls in that month (a purchase from a later month, or one whose installments ended) and in every
     * other read. At most one installment falls in a month (D7). Refunded or not, it's the same.
     */
    val now: Installment = Installment(),
    /** The legenda (spec §2.12); "" = none. Lists show [title] instead of [name] when set. */
    val caption: String = "",
    /**
     * Closed an occurrence of a previsto (D69): out of the ritmo, at its real value, in every month of its
     * installments ([BudgetMonth.fixed]). Written by Lançar previsto, Lançar recado ligado, "É um previsto?
     * Ligar" and Lançar sem recado; kept by edit; stays when its previsto is deleted.
     */
    val fixed: Boolean = false,
) {
    val name: String get() = alias.ifEmpty { merchant }
    /** The row's headline (spec §2.12, D54): [caption] when set, else [name]. The credit "em …" shows [name]. */
    val title: String get() = caption.ifEmpty { name }
    val refunded: Boolean get() = estorno.v != 0L
}

data class Installment(val k: Int = 0, val date: LocalDate = NO_DATE, val cents: Cents = Cents(0))

/** The edit screen: the transaction, its installments and the notice it came from (zero for manual). */
data class TransactionDetail(
    val row: TransactionRow = TransactionRow(),
    val installments: List<Installment> = emptyList(),
    val notice: NoticeRow = NoticeRow(),
    /**
     * Its previsto (D65): the one its "Repete" created, else the one whose occurrence it closed; 0 = none
     * (or deleted). The edit's "Repete" opens as [repeats] says, and [Db.edit] acts on this previsto.
     */
    val planned: PlannedId = PlannedId(0),
    /** That previsto's repetition; ONCE when there is none. */
    val repeats: Repeats = Repeats.ONCE,
    /** That previsto's day (1..31): the edit's "Todo mês, dia N"; 0 when there is none. */
    val day: Int = 0,
)

/** Budget and tags of the merchant's last transaction (spec §2.5); zero = no history. */
data class Suggestion(val budget: BudgetId = BudgetId(0), val tags: List<String> = emptyList())

/** A purchase an estorno can cancel: a transaction or a pending notice (exactly one id is non-zero). */
data class RefundCandidate(
    val transaction: TransactionId = TransactionId(0),
    val notice: NoticeId = NoticeId(0),
    /** Alias, else merchant text. */
    val name: String = "",
    val total: Cents = Cents(0),
    val date: LocalDate = NO_DATE,
    /** Same source, same value, same merchant (when the estorno names one) and not after the estorno. */
    val likely: Boolean = false,
    /** The legenda (spec §2.12); "" = none; always "" for a pending notice (recados have none). */
    val caption: String = "",
    /** Alias of the merchant; "" = none. For the estorno search (spec §7.3). */
    val alias: String = "",
    /** Bank text of the merchant; "" = none. For the estorno search (spec §7.3). */
    val merchant: String = "",
) {
    /** The candidate's headline (spec §7.3, D54): [caption] when set (one line), else [name]. The credit "em …" shows [name]. */
    val title: String get() = caption.ifEmpty { name }
}

data class Week(
    /** Cut to the month: the first and last weeks may be partial. */
    val start: LocalDate = NO_DATE,
    val end: LocalDate = NO_DATE,
    /** Share of [BudgetMonth.freeLimit]: Σ steps of the month = freeLimit (spec §4, D68). */
    val step: Cents = Cents(0),
    val edited: Boolean = false,
    /** Gasto variável of the week: installments dated in it, refunded and [TransactionRow.fixed] ones left out (D68). */
    val spent: Cents = Cents(0),
    /**
     * The most [Db.setStep] accepts for this week: [BudgetMonth.freeLimit] − Σ steps of the other edited
     * weeks (D70). Any value in 0..max is accepted, max + 1 is refused. Cents(0) when [locked].
     */
    val max: Cents = Cents(0),
    /**
     * The only week of the month not edited (spec §4: one week always stays automatic). [Db.setStep]
     * refuses it with any value; the steps editor shows it in ink 3, without touch. With two or more
     * weeks not edited, none is locked. In a month without its limit row (before the budget's first
     * month), every week is locked.
     */
    val locked: Boolean = false,
)

/**
 * Everything the screens show about one budget in one month (architecture §6). Margem honesta (spec §3,
 * §5; margem-plan M1–M6, D68): the previstos do mês ([planned] = [open] + [fixed]) reserve in the margem
 * and stay out of the ritmo, which is only the [freeLimit] against the [variable] spending. Without a
 * previsto in the month, open = fixed = 0, freeLimit = limit and variable = spent: the rules of before.
 */
data class BudgetMonth(
    val month: YearMonth = NO_MONTH,
    val start: LocalDate = NO_DATE,
    val end: LocalDate = NO_DATE,
    val limit: Cents = Cents(0),
    /** Gasto do mês: every installment dated in the month, refunded ones left out, [fixed] included. */
    val spent: Cents = Cents(0),
    /** Margem (M1) = limit − spent − [open]; negative when OVERRUN. */
    val remaining: Cents = Cents(0),
    val weeks: List<Week> = emptyList(),
    /** Ritmo ideal of today, of the [freeLimit] (M3). */
    val ideal: Cents = Cents(0),
    /** OVERRUN when remaining < 0; else AHEAD when [variable] > [ideal]; else ON_PACE (M3). */
    val status: PaceStatus = PaceStatus.ON_PACE,
    /**
     * No passo atual (M6) = variable × freeLimit ÷ ideal + [planned], rounded down: exactly on pace projects
     * the limit. 0 when ideal is 0 (no projection).
     */
    val projection: Cents = Cents(0),
    /**
     * Ritmo ideal at the end of each day of the month (D44): one value per day from [start] to [end],
     * index 0 = [start]. Day d = the rule of [ideal] with today = d (spec §5): steps of the weeks ended
     * before d, plus the step of d's week × days elapsed in it (with d) ÷ its days, rounded down per
     * week. Never decreases; the last day = Σ steps = [freeLimit] (D68). For
     * today inside the month, idealByDay[today − start] == [ideal]. Empty when [start] is NO_DATE.
     */
    val idealByDay: List<Cents> = emptyList(),
    /**
     * Reserve of the open previstos (M1, M2): Σ of the previsto's value of every open occurrence of this
     * budget (not skipped, no lançamento) dated in the month, late ones included, whatever the R1 window.
     */
    val open: Cents = Cents(0),
    /** Previstos lançados (M2, D69): Σ installments dated in the month of the [TransactionRow.fixed] lançamentos, refunded ones left out. Part of [spent]. */
    val fixed: Cents = Cents(0),
    /** Cota livre (M3, M10) = max(0, limit − [planned]): what the steps share and the ritmo measures. */
    val freeLimit: Cents = Cents(0),
    /** Gasto variável (M3, M4) = spent − [fixed]: what is compared with [ideal]. */
    val variable: Cents = Cents(0),
) {
    /** Previstos do mês (M2) = [open] + [fixed]. The régua's ritmo mark sits at (planned + ideal) ÷ limit (M11). */
    val planned: Cents get() = open + fixed
}

data class BudgetSummary(val id: BudgetId = BudgetId(0), val name: String = "", val month: BudgetMonth = BudgetMonth())

/** One line of the budget detail: a transaction and its installment dated in the month (book 10.8). */
data class MonthLine(
    val row: TransactionRow = TransactionRow(),
    /** Which installment (1..row.installments) is dated in the month: "Parcela 3 de 10". 1 for à vista. */
    val k: Int = 0,
    /** That installment's date (the purchase date for à vista and for k = 1). */
    val date: LocalDate = NO_DATE,
    /** That installment's value: what the line adds to the running sum, unless row.refunded. */
    val cents: Cents = Cents(0),
)

data class BudgetDetail(
    val id: BudgetId = BudgetId(0),
    val name: String = "",
    val month: BudgetMonth = BudgetMonth(),
    /**
     * One line per transaction of this budget with an installment dated in the month, oldest first
     * (installment date, then time, then id), for the running sum. Refunded ones are included and
     * don't add: Σ cents of the lines not refunded = month.spent.
     */
    val lines: List<MonthLine> = emptyList(),
    /**
     * The budget's first month with a limit (the month it was created in, by the month start day of
     * that time): the month picker goes from here to the current month. NO_MONTH when unknown.
     */
    val firstMonth: YearMonth = NO_MONTH,
    /** Its previstos, due or not: deleted with it (D59, spec §7.1's "Apagar verba"). */
    val planned: Int = 0,
    /**
     * "Previstos de outubro" (spec §7.1, M12, D73): one line per occurrence of this budget's previstos dated
     * in the month, open or lançada, skipped ones left out; by date, then occurrence id. Empty when none.
     * Σ cents of the open lines = month.open.
     */
    val plannedLines: List<PlannedLine> = emptyList(),
)

/**
 * One line of "Previstos de outubro" in the budget detail (spec §7.1, M12, D73): the folhinha with the day,
 * the name, "esperando · todo mês" (open, value in ink 2) or "lançado em 4 out" (lançada, the lançado value).
 * Tap opens the previsto sheet with [planned].
 */
data class PlannedLine(
    /**
     * The occurrence with its previsto: name, date, repeats, budget, tags, link. Here it may be a closed one
     * (lançada); [Planned.late] against the read's today, false when lançada; [Planned.noticeInPauta] false.
     */
    val planned: Planned = Planned(),
    /** Open: the previsto's value (Planned.cents). Lançada: what its lançamento adds to [BudgetMonth.fixed] (fixedByDay, D73). */
    val cents: Cents = Cents(0),
    /** Lançada: the date of that installment ("lançado em 4 out"). NO_DATE while open. */
    val launched: LocalDate = NO_DATE,
) {
    val open: Boolean get() = launched == NO_DATE
}

/**
 * Teto of one budget month (spec §3, §7.1; D42, D46): the most the month's limits may sum, as a planning
 * limit. Nothing is ever refused because of it.
 */
data class Ceiling(
    val month: YearMonth = NO_MONTH,
    /** This month's ceiling; 0 = no ceiling (also for a month without its row). */
    val cents: Cents = Cents(0),
    /**
     * The default (Settings-level "deste mês em diante" value) that months without a row get from
     * [Db.ensureMonths]; 0 = none. [cents] != [standard] is the "· só outubro" of the legend.
     */
    val standard: Cents = Cents(0),
    /** Σ of the limits of the budgets with a limit row in [month]. */
    val limits: Cents = Cents(0),
) {
    /** How much [limits] passes [cents]: limits − cents when there is a ceiling and limits > cents, else 0. */
    val over: Cents get() = if (cents.v > 0 && limits > cents) limits - cents else Cents(0)
}

/** "Mercado: margem R$ 300,00 → R$ 254,00", for the month of the first installment. */
data class Preview(val month: YearMonth = NO_MONTH, val before: Cents = Cents(0), val after: Cents = Cents(0))

data class TagRow(
    val id: TagId = TagId(0),
    val name: String = "",
    /** Transactions with this tag. */
    val uses: Int = 0,
    /** Transactions with only this tag: deleted with it (spec §2.9). */
    val soleUses: Int = 0,
    /** Previstos with only this tag: deleted with it (D59). */
    val solePlanned: Int = 0,
)

/** Leitura, Verbas por mês: one budget in one month, with that month's own limit. */
data class HistoryRow(
    val budget: BudgetId = BudgetId(0),
    val name: String = "",
    val month: YearMonth = NO_MONTH,
    val limit: Cents = Cents(0),
    val spent: Cents = Cents(0),
)

/** Leitura ranking: a tag or a merchant (by alias), by value, with how many transactions. */
data class RankRow(val name: String = "", val total: Cents = Cents(0), val count: Int = 0)
