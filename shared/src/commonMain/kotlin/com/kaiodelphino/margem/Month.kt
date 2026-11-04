package com.kaiodelphino.margem

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateRange
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.minusMonth
import kotlinx.datetime.onDay
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.previousOrSame
import kotlinx.datetime.yearMonth

/** The budget month [month]: from [startDay] (or the month's last day) to the eve of the next one (spec §3). */
internal fun monthWindow(month: YearMonth, startDay: Int): LocalDateRange {
    val next = month.plusMonth()
    val nextStart = next.onDay(minOf(startDay, next.numberOfDays))
    return month.onDay(minOf(startDay, month.numberOfDays))..nextStart.minus(1, DateTimeUnit.DAY)
}

internal fun monthOf(date: LocalDate, startDay: Int): YearMonth {
    val ym = date.yearMonth
    return if (date >= monthWindow(ym, startDay).start) ym else ym.minusMonth()
}

/**
 * Installment k (1-based) of a purchase on [date]: date + (k−1) months, moved to the nearest day
 * inside the k-th budget month when it falls outside it (start days 29–31, D7).
 */
internal fun installmentDate(date: LocalDate, k: Int, startDay: Int): LocalDate {
    val d = date.plus(k - 1, DateTimeUnit.MONTH)
    val window = monthWindow(monthOf(date, startDay).plus(k - 1, DateTimeUnit.MONTH), startDay)
    return d.coerceIn(window.start, window.endInclusive)
}

/**
 * Everything about one budget in one month (architecture §6), with the margem honesta (D68, margem-plan
 * M1–M6). [spentByDay] is the whole gasto (fixed included), [fixedByDay] the installments of the
 * [TransactionRow.fixed] lançamentos (refunded ones left out), [open] the reserve of the open occurrences
 * dated in the month; days outside the month are ignored. Then fixed = Σ fixedByDay, planned = open + fixed,
 * freeLimit = max(0, limit − planned), variable = spent − fixed, remaining = limit − spent − open. The steps
 * share freeLimit and Week.max is against it; [BudgetMonth.ideal], idealByDay and Week.spent are of the free
 * part (Week.spent = variable of the week). Status: remaining < 0 → OVERRUN, variable > ideal → AHEAD, else
 * ON_PACE. Projection = variable × freeLimit ÷ ideal + planned (0 when ideal is 0). Edits that match no week
 * are ignored; so are all of them when they leave no week automatic or sum more than freeLimit (D10, D70:
 * they count again when freeLimit grows back). With empty [fixedByDay] and [open] 0, the rules of before.
 */
internal fun budgetMonth(
    month: YearMonth,
    monthStartDay: Int,
    weekStart: DayOfWeek,
    today: LocalDate,
    limit: Cents,
    editedSteps: Map<LocalDate, Cents>,
    spentByDay: Map<LocalDate, Cents>,
    fixedByDay: Map<LocalDate, Cents>,
    open: Cents,
): BudgetMonth {
    val window = monthWindow(month, monthStartDay)
    var fixed = 0L
    for ((date, cents) in fixedByDay) if (date in window) fixed += cents.v
    val free = maxOf(0L, limit.v - open.v - fixed)

    // Calendar weeks cut to the window.
    val starts = ArrayList<LocalDate>()
    val ends = ArrayList<LocalDate>()
    var s = window.start
    while (s <= window.endInclusive) {
        val e = minOf(s.previousOrSame(weekStart).plus(6, DateTimeUnit.DAY), window.endInclusive)
        starts.add(s)
        ends.add(e)
        s = e.plus(1, DateTimeUnit.DAY)
    }
    val n = starts.size
    val days = IntArray(n) { starts[it].daysUntil(ends[it]) + 1 }

    var edited = BooleanArray(n) { starts[it] in editedSteps }
    var editedSum = 0L
    for (i in 0 until n) if (edited[i]) editedSum += editedSteps.getValue(starts[i]).v
    if (edited.all { it } || editedSum > free) {
        edited = BooleanArray(n)
        editedSum = 0
    }

    // Automatic weeks share what the edits leave, by days, rounded down; the last one takes the rest (D11).
    val steps = LongArray(n)
    val rest = free - editedSum
    val autoDays = (0 until n).filter { !edited[it] }.sumOf { days[it] }
    val autoCount = edited.count { !it }
    var lastAuto = -1
    var given = 0L
    for (i in 0 until n) {
        if (edited[i]) {
            steps[i] = editedSteps.getValue(starts[i]).v
        } else {
            steps[i] = rest / autoDays * days[i] + rest % autoDays * days[i] / autoDays
            given += steps[i]
            lastAuto = i
        }
    }
    if (lastAuto >= 0) steps[lastAuto] += rest - given

    // Week.spent is the gasto variável: the whole gasto of the week less its fixed installments (D68).
    val weekSpent = LongArray(n)
    var spent = 0L
    for ((date, cents) in spentByDay) {
        if (date !in window) continue
        spent += cents.v
        for (i in 0 until n) if (date <= ends[i]) { weekSpent[i] += cents.v; break }
    }
    for ((date, cents) in fixedByDay) {
        if (date !in window) continue
        for (i in 0 until n) if (date <= ends[i]) { weekSpent[i] -= cents.v; break }
    }
    val variable = spent - fixed
    val remaining = limit.v - spent - open.v

    // Ritmo ideal of a day: closed weeks whole, that day's week by days elapsed (with the day).
    fun idealOn(day: LocalDate): Long {
        var sum = 0L
        for (i in 0 until n) {
            when {
                ends[i] < day -> sum += steps[i]
                starts[i] <= day -> sum += steps[i] * (starts[i].daysUntil(day) + 1) / days[i]
            }
        }
        return sum
    }
    val ideal = idealOn(today)
    val idealByDay = ArrayList<Cents>(window.start.daysUntil(window.endInclusive) + 1)
    for (d in window) idealByDay.add(Cents(idealOn(d)))

    val status = when {
        remaining < 0 -> PaceStatus.OVERRUN
        variable > ideal -> PaceStatus.AHEAD
        else -> PaceStatus.ON_PACE
    }
    // M6: the free part at today's pace, plus the previstos do mês.
    val projection = when {
        ideal == 0L -> 0L
        free == 0L || variable <= Long.MAX_VALUE / free -> variable * free / ideal + open.v + fixed
        else -> (variable.toDouble() * free / ideal).toLong() + open.v + fixed
    }

    return BudgetMonth(
        month = month,
        start = window.start,
        end = window.endInclusive,
        limit = limit,
        spent = Cents(spent),
        remaining = Cents(remaining),
        weeks = List(n) {
            // Max of a step edit: what the other edits leave; the only automatic week left is locked (spec §4).
            val locked = !edited[it] && autoCount == 1
            val max = if (locked) 0L else free - editedSum + (if (edited[it]) steps[it] else 0L)
            Week(starts[it], ends[it], Cents(steps[it]), edited[it], Cents(weekSpent[it]), Cents(max), locked)
        },
        ideal = Cents(ideal),
        status = status,
        projection = Cents(projection),
        idealByDay = idealByDay,
        open = open,
        fixed = Cents(fixed),
        freeLimit = Cents(free),
        variable = Cents(variable),
    )
}

/** Week.max of the week starting [weekStart]; Cents(-1) when it can't be edited: not a week of the month, or locked. */
internal fun maxStep(bm: BudgetMonth, weekStart: LocalDate): Cents {
    val week = bm.weeks.firstOrNull { it.start == weekStart } ?: return Cents(-1)
    return if (week.locked) Cents(-1) else week.max
}

/**
 * Riskiest first (spec §8.1): Fora da margem by excess (−remaining), Acima do ritmo by distance to the
 * ideal (variable − ideal, D68), No ritmo by smallest margin as a share of the limit. Ties by name.
 */
internal fun riskOrder(budgets: List<BudgetSummary>): List<BudgetSummary> =
    budgets.sortedWith(
        compareByDescending<BudgetSummary> { it.month.status.ordinal }
            .thenByDescending {
                when (it.month.status) {
                    PaceStatus.OVERRUN -> -it.month.remaining.v.toDouble()
                    PaceStatus.AHEAD -> (it.month.variable - it.month.ideal).v.toDouble()
                    PaceStatus.ON_PACE -> if (it.month.limit.v == 0L) 0.0 else -it.month.remaining.v.toDouble() / it.month.limit.v
                }
            }
            .thenBy { it.name.lowercase() }
    )
