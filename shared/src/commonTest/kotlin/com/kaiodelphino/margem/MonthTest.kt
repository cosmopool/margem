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
        fixed: Map<LocalDate, Long> = emptyMap(),
        open: Long = 0,
    ) = budgetMonth(
        month, startDay, weekStart, today, Cents(limit), edited.mapValues { Cents(it.value) }, spent.mapValues { Cents(it.value) },
        fixed.mapValues { Cents(it.value) }, Cents(open),
    )

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
    fun `ideal by day follows the steps - R 310 in October is R 10 a day`() {
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

    // Margem honesta (D68, margem-plan M1–M6). Nov 2026 from Monday: weeks 1 · 2–8 · 9–15 · 16–22 · 23–29 · 30.

    @Test
    fun `an open aluguel reserves - cota 3000 aluguel 2000 leaves 1000 free and margem 960 with 40 spent`() {
        // M1: margem = 3000 − 40 − 2000 = 960. M3: free 1000 over 30 days, 3333 a day rounded down (D11):
        // week 1 = 3333, full weeks 23331 + 70 ÷ 30 = 23333, last 3333 + the 2 left = 3335.
        val m = bm(today = LocalDate(2026, 11, 2), limit = 300000, spent = mapOf(LocalDate(2026, 11, 1) to 4000L), open = 200000)
        assertEquals(Cents(200000), m.open)
        assertEquals(Cents(0), m.fixed)
        assertEquals(Cents(200000), m.planned)
        assertEquals(Cents(100000), m.freeLimit)
        assertEquals(Cents(4000), m.spent)
        assertEquals(Cents(4000), m.variable)
        assertEquals(Cents(96000), m.remaining)
        assertEquals(listOf(3333L, 23333, 23333, 23333, 23333, 3335), m.steps())
        // Ideal of Nov 2: 3333 + 23333 × 1 ÷ 7 = 3333 + 3333.
        assertEquals(Cents(6666), m.ideal)
        assertEquals(PaceStatus.ON_PACE, m.status)
        assertEquals(Cents(100000), m.idealByDay.last())
        // M6: 4000 × 100000 ÷ 6666 = 60006, + previstos 200000.
        assertEquals(Cents(260006), m.projection)
        // Every week max is against the free part (D70).
        assertEquals(Cents(100000), m.weeks[1].max)
    }

    @Test
    fun `aluguel lancado at 2100 is gasto out of the ritmo and leaves 900 free`() {
        // M4: the whole 2100 is fixed; free = 3000 − 2100 = 900, 3000 a day; Week.spent is the variable part.
        val m = bm(
            today = LocalDate(2026, 11, 2), limit = 300000,
            spent = mapOf(LocalDate(2026, 11, 1) to 4000L, LocalDate(2026, 11, 2) to 214000L),
            fixed = mapOf(LocalDate(2026, 11, 2) to 210000L),
        )
        assertEquals(Cents(0), m.open)
        assertEquals(Cents(210000), m.fixed)
        assertEquals(Cents(210000), m.planned)
        assertEquals(Cents(90000), m.freeLimit)
        assertEquals(Cents(218000), m.spent)
        assertEquals(Cents(8000), m.variable)
        assertEquals(Cents(82000), m.remaining) // 3000 − 2180 − 0 open
        assertEquals(listOf(3000L, 21000, 21000, 21000, 21000, 3000), m.steps())
        assertEquals(listOf(4000L, 4000, 0, 0, 0, 0), m.weeks.map { it.spent.v })
        // Ideal of Nov 2 = 3000 + 21000 ÷ 7 = 6000; 80 variable > 60 → Acima do ritmo, the 2100 never counts in it.
        assertEquals(Cents(6000), m.ideal)
        assertEquals(PaceStatus.AHEAD, m.status)
    }

    @Test
    fun `variable over the ideal is Acima do ritmo and spent plus open over the cota is Fora da margem`() {
        val nov2 = LocalDate(2026, 11, 2)
        // 70 variable > ideal 66,66 of the free 1000; margem 3000 − 70 − 2000 = 930 ≥ 0.
        val ahead = bm(today = nov2, limit = 300000, spent = mapOf(LocalDate(2026, 11, 1) to 7000L), open = 200000)
        assertEquals(PaceStatus.AHEAD, ahead.status)
        assertEquals(Cents(93000), ahead.remaining)
        // 1010 spent + 2000 open > 3000: margem −10 (M1, M3).
        val over = bm(today = nov2, limit = 300000, spent = mapOf(LocalDate(2026, 11, 1) to 101000L), open = 200000)
        assertEquals(Cents(-1000), over.remaining)
        assertEquals(PaceStatus.OVERRUN, over.status)
        // The same gasto without the previsto is far from the cota but way over the ritmo.
        assertEquals(PaceStatus.AHEAD, bm(today = nov2, limit = 300000, spent = mapOf(LocalDate(2026, 11, 1) to 101000L)).status)
    }

    @Test
    fun `previstos over the cota leave no free part - steps 0 and no projection`() {
        // limit 1000, open 1500: free = max(0, −500) = 0, every step 0, ideal 0 → no projection (M6); margem −500.
        val m = bm(limit = 100000, spent = mapOf(LocalDate(2026, 11, 3) to 2000L), open = 150000)
        assertEquals(Cents(0), m.freeLimit)
        assertEquals(listOf(0L, 0, 0, 0, 0, 0), m.steps())
        assertEquals(Cents(0), m.ideal)
        assertEquals(Cents(0), m.projection)
        assertEquals(Cents(-52000), m.remaining)
        assertEquals(PaceStatus.OVERRUN, m.status)
        assertEquals(Cents(0), m.idealByDay.last())
    }

    @Test
    fun `exactly on the ritmo of the free part projects the cota`() {
        // free 900, 3000 a day; Nov 15 closes the 3rd week: ideal 3000 + 21000 × 2 = 45000.
        // M6: 45000 × 90000 ÷ 45000 + 210000 = 300000 = cota.
        val m = bm(
            limit = 300000,
            spent = mapOf(LocalDate(2026, 11, 2) to 210000L, LocalDate(2026, 11, 10) to 45000L),
            fixed = mapOf(LocalDate(2026, 11, 2) to 210000L),
        )
        assertEquals(Cents(45000), m.ideal)
        assertEquals(PaceStatus.ON_PACE, m.status)
        assertEquals(Cents(300000), m.projection)
    }

    @Test
    fun `step edits over the free part are ignored and count again when it grows back`() {
        // D70: weeks 2–8 and 9–15 edited to 500 each (1000). Free 1000 fits them, free 500 doesn't.
        val edits = mapOf(LocalDate(2026, 11, 2) to 50000L, LocalDate(2026, 11, 9) to 50000L)
        val fits = bm(limit = 300000, edited = edits, open = 200000)
        assertEquals(listOf(false, true, true, false, false, false), fits.weeks.map { it.edited })
        assertEquals(Cents(50000), fits.weeks[1].step)
        assertEquals(100000L, fits.steps().sum())
        // The edited week's max: free − the other edit = 1000 − 500.
        assertEquals(Cents(50000), fits.weeks[1].max)
        assertEquals(Cents(0), fits.weeks[0].max) // 1000 − 1000 edited

        val over = bm(limit = 300000, edited = edits, open = 250000)
        assertEquals(List(6) { false }, over.weeks.map { it.edited })
        assertEquals(50000L, over.steps().sum())
        assertEquals(Cents(50000), over.weeks[1].max)

        val back = bm(limit = 300000, edited = edits, open = 0)
        assertEquals(Cents(50000), back.weeks[1].step)
        assertEquals(300000L, back.steps().sum())
    }

    @Test
    fun `fixed and open outside the month are ignored and no previsto gives the rules of before`() {
        val spent = mapOf(LocalDate(2026, 11, 3) to 7000L)
        val plain = bm(spent = spent)
        assertEquals(plain, bm(spent = spent, fixed = mapOf(LocalDate(2026, 10, 31) to 5000L, LocalDate(2026, 12, 1) to 5000L)))
        assertEquals(Cents(60000), plain.freeLimit)
        assertEquals(plain.spent, plain.variable)
        assertEquals(Cents(53000), plain.remaining)
        assertEquals(Cents(0), plain.planned)
    }
}
