package com.kaiodelphino.margem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.DayOfWeek.MONDAY
import kotlinx.datetime.DayOfWeek.THURSDAY
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/** budgetMonth (architecture §6) against the spec's own numbers. */
class MonthTest {

    private fun bm(
        month: YearMonth = NOV,
        today: LocalDate = LocalDate(2026, 11, 15),
        limit: Long = 60000,
        startDay: Int = 1,
        weekStart: DayOfWeek = MONDAY,
        edited: Map<LocalDate, Long> = emptyMap(),
        spent: Map<LocalDate, Long> = emptyMap(),
    ) = budgetMonth(month, startDay, weekStart, today, Cents(limit), edited.mapValues { Cents(it.value) }, spent.mapValues { Cents(it.value) })

    private fun BudgetMonth.steps() = weeks.map { it.step.v }

    @Test
    fun `default steps follow the days of each week - spec example 600 in 30 days`() {
        // Sep 2026 starts on a Tuesday; weeks from Thursday: 1–2 (2 days), then four full weeks.
        val sep = bm(month = YearMonth(2026, 9), weekStart = THURSDAY, today = LocalDate(2026, 9, 1))
        assertEquals(listOf(4000L, 14000, 14000, 14000, 14000), sep.steps())
        assertEquals(LocalDate(2026, 9, 1) to LocalDate(2026, 9, 2), sep.weeks[0].start to sep.weeks[0].end)

        // Nov 2026 starts on a Sunday: 1-day weeks at both ends.
        val nov = bm()
        assertEquals(listOf(2000L, 14000, 14000, 14000, 14000, 2000), nov.steps())
        assertEquals(LocalDate(2026, 11, 30), nov.weeks.last().start)
        assertEquals(LocalDate(2026, 11, 30), nov.weeks.last().end)
    }

    @Test
    fun `cents round down and the last automatic week takes the rest - 3333 3333 3334`() {
        // Feb 2027: four full weeks from Monday. Week 1 edited to R$ 30, R$ 100 left for three equal weeks.
        val feb = bm(month = YearMonth(2027, 2), limit = 13000, edited = mapOf(LocalDate(2027, 2, 1) to 3000L), today = LocalDate(2027, 2, 1))
        assertEquals(listOf(3000L, 3333, 3333, 3334), feb.steps())
        assertEquals(listOf(true, false, false, false), feb.weeks.map { it.edited })
    }

    @Test
    fun `edits that match no week sum over the limit or leave no week automatic are ignored`() {
        val default = bm().steps()
        assertEquals(default, bm(edited = mapOf(LocalDate(2026, 11, 3) to 100L)).steps())
        assertEquals(default, bm(edited = mapOf(LocalDate(2026, 11, 2) to 40000L, LocalDate(2026, 11, 9) to 30000L)).steps())
        val all = listOf(1, 2, 9, 16, 23, 30).associate { LocalDate(2026, 11, it) to 10000L }
        assertEquals(default, bm(edited = all).steps())
    }

    @Test
    fun `month start day past the end of the month uses its last day`() {
        val feb31 = bm(month = YearMonth(2026, 2), startDay = 31)
        assertEquals(LocalDate(2026, 2, 28) to LocalDate(2026, 3, 30), feb31.start to feb31.end)
        val jan31 = bm(month = YearMonth(2026, 1), startDay = 31)
        assertEquals(LocalDate(2026, 1, 31) to LocalDate(2026, 2, 27), jan31.start to jan31.end)
        val feb29 = bm(month = YearMonth(2026, 2), startDay = 29)
        assertEquals(LocalDate(2026, 2, 28) to LocalDate(2026, 3, 28), feb29.start to feb29.end)
        val leap = bm(month = YearMonth(2024, 2), startDay = 30)
        assertEquals(LocalDate(2024, 2, 29) to LocalDate(2024, 3, 29), leap.start to leap.end)
        for (m in listOf(feb31, jan31, feb29, leap)) assertEquals(60000L, m.steps().sum())

        assertEquals(YearMonth(2026, 2), monthOf(LocalDate(2026, 3, 30), 31))
        assertEquals(YearMonth(2026, 3), monthOf(LocalDate(2026, 3, 31), 31))
        assertEquals(YearMonth(2025, 12), monthOf(LocalDate(2026, 1, 30), 31))
    }

    @Test
    fun `ideal pace states and projection`() {
        // Nov 15 closes the third week: 2000 + 14000 + 14000.
        val onPace = bm(spent = mapOf(LocalDate(2026, 11, 10) to 30000L))
        assertEquals(Cents(30000), onPace.ideal)
        assertEquals(PaceStatus.ON_PACE, onPace.status)
        assertEquals(Cents(60000), onPace.projection) // exactly on pace projects the limit
        assertEquals(Cents(30000), onPace.remaining)

        val ahead = bm(spent = mapOf(LocalDate(2026, 11, 10) to 30001L))
        assertEquals(PaceStatus.AHEAD, ahead.status) // a cent above, no tolerance
        assertEquals(Cents(60002), ahead.projection)

        val over = bm(spent = mapOf(LocalDate(2026, 11, 10) to 60001L))
        assertEquals(PaceStatus.OVERRUN, over.status)
        assertEquals(Cents(-1), over.remaining)

        // Mid-week: Wed 18 is day 3 of 7 of a 14000 week.
        assertEquals(Cents(36000), bm(today = LocalDate(2026, 11, 18)).ideal)
        assertEquals(Cents(60000), bm(today = LocalDate(2026, 12, 5)).ideal)

        val notStarted = bm(today = LocalDate(2026, 10, 31), spent = mapOf(LocalDate(2026, 11, 20) to 100L))
        assertEquals(Cents(0), notStarted.ideal)
        assertEquals(PaceStatus.AHEAD, notStarted.status)
        assertEquals(Cents(0), notStarted.projection) // no projection with ideal 0
    }

    @Test
    fun `projection on the last day is what was spent - also where spent times limit leaves 64 bits`() {
        val last = LocalDate(2026, 11, 30)
        fun projection(limit: Long, spent: Long) = bm(today = last, limit = limit, spent = mapOf(LocalDate(2026, 11, 10) to spent)).projection.v
        // Σ steps = ideal on the last day: spent × limit ÷ limit.
        assertEquals(10_000_000_000_000_001L, projection(3, 10_000_000_000_000_001L)) // above 2^53: no rounding
        assertEquals(Long.MAX_VALUE / 3, projection(3, Long.MAX_VALUE / 3)) // spent × limit is just Long.MAX_VALUE - 1
        assertEquals(100L, projection(99_999_999_999_999_999L, 100)) // spent × limit overflows a Long
    }

    @Test
    fun `an edited plan moves the ideal pace`() {
        // Week 2 lowered to R$ 70: the other 23 days share 53000, so week 1 (1 day) is 53000 / 23 = 2304.
        val m = bm(today = LocalDate(2026, 11, 8), edited = mapOf(LocalDate(2026, 11, 2) to 7000L))
        assertEquals(Cents(2304), m.weeks[0].step)
        assertEquals(Cents(9304), m.ideal)
        assertEquals(60000L, m.steps().sum())
    }

    @Test
    fun `spending counts only inside the month per week`() {
        val m = bm(
            spent = mapOf(
                LocalDate(2026, 10, 31) to 999L, LocalDate(2026, 11, 1) to 500L, LocalDate(2026, 11, 3) to 700L,
                LocalDate(2026, 11, 30) to 100L, LocalDate(2026, 12, 1) to 999L,
            ),
        )
        assertEquals(Cents(1300), m.spent)
        assertEquals(listOf(500L, 700, 0, 0, 0, 100), m.weeks.map { it.spent.v })
    }

    private companion object {
        val NOV = YearMonth(2026, 11)
    }
    @Test
    fun `ideal by day follows the steps - R$ 310 in October is R$ 10 a day`() {
        // Book 10.7: Oct 2026 from Monday, R$ 310 → steps 40 · 70 · 70 · 70 · 60, R$ 10 a day (spec §4, §5).
        val oct = bm(month = YearMonth(2026, 10), limit = 31000, today = LocalDate(2026, 10, 9))
        assertEquals((1..31).map { Cents(it * 1000L) }, oct.idealByDay)
        assertEquals(oct.ideal, oct.idealByDay[8])
    }

    @Test
    fun `ideal by day keeps an edited week and rounds down inside each week`() {
        // Week 1–4 edited to R$ 20: R$ 290 left over 27 days → 7518 per full week, the last (26–31) 6446 (D11).
        val oct = bm(month = YearMonth(2026, 10), limit = 31000, today = LocalDate(2026, 10, 9), edited = mapOf(LocalDate(2026, 10, 1) to 2000L))
        assertEquals(Cents(1000), oct.idealByDay[1]) // day 2: 2000 × 2 ÷ 4
        assertEquals(Cents(4148), oct.idealByDay[5]) // day 6: 2000 + 7518 × 2 ÷ 7 = 2000 + 2148
        assertEquals(Cents(2000 + 7518 * 3), oct.idealByDay[24]) // day 25 closes the 4th week
        assertEquals(Cents(31000), oct.idealByDay.last())
    }
}
