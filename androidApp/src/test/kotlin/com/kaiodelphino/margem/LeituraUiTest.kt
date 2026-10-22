package com.kaiodelphino.margem

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.Screen
import com.kaiodelphino.margem.widgets.MargemTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Leitura end to end (spec §7.5, book 10.6): App opened on the Leitura tab over a real Db, today fixed on
 * Friday, Oct 9 2026; the month runs 1–31 and the week starts on Monday (defaults).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp")
class LeituraUiTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: Db

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
    }

    @After
    fun tearDown() = db.close()

    private fun open(today: LocalDate = OCT_9) = compose.setContent {
        MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { today }) { App(db, Screen.Insights) {} } }
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = false)

    private fun description(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    private fun add(budget: BudgetId, cents: Long, day: Int, tag: String, merchant: String = "Loja") = runBlocking {
        db.addManual(TransactionForm(budget, listOf(tag), Cents(cents), 1, merchant, LocalDate(2026, 10, day), LocalTime(12, 0))).transaction
    }

    /** Book 10.6: Restaurantes R$ 620 with R$ 231,10 by the 9th; Lazer R$ 465 with R$ 477,00. */
    private fun seed(): Pair<BudgetId, BudgetId> {
        val (rest, lazer) = runBlocking { db.createBudget("Restaurantes", Cents(62000), OCT_1) to db.createBudget("Lazer", Cents(46500), OCT_1) }
        add(rest, 12000, 3, "feira", "Feira")
        add(rest, 6110, 4, "padaria", "Padaria")
        add(rest, 5000, 8, "feira", "Feira")
        add(lazer, 47700, 2, "show", "Teatro")
        return rest to lazer
    }

    @Test
    fun `no budget shows the empty page`() {
        open()
        waitFor(InsightsTags.EMPTY)
        node(InsightsTags.EMPTY).assertTextContains("Nada para ler ainda.")
    }

    @Test
    fun `ritmo opens on the riskiest budget and switches to another`() {
        val (rest, _) = seed()
        open()
        waitFor(InsightsTags.PACE_SENTENCE)
        node(InsightsTags.MONTH_LABEL).assertTextContains("outubro de 2026").assertTextContains("1 A 31 · ATÉ SEX, 9")
        node(InsightsTags.MONTH_PREV).assertIsNotEnabled()
        node(InsightsTags.MONTH_NEXT).assertIsNotEnabled()
        // Lazer is Fora da margem, the riskiest (§8.1). Its steps: 6000 · 10500 …; ideal on the 9th = 6000 + 10500 × 5 ÷ 7 = 13500;
        // projection 47700 × 46500 ÷ 13500 = 164300 → R$ 1.643.
        node(InsightsTags.PACE_BUDGET).assertTextContains("Lazer")
        node(InsightsTags.PACE_SENTENCE).assertTextEquals(r("R$ 12,00 além da cota. No passo atual, o mês fecharia em R$ 1.643."))
        node(InsightsTags.HISTORY_SENTENCE).assertTextEquals("Um mês fora da margem.")
        node(InsightsTags.HISTORY_CHART).assert(description("Verbas por mês de Lazer: mai sem cota; jun sem cota; jul sem cota; ago sem cota; set sem cota; out 477,00 de 465,00"))

        node(InsightsTags.PACE_BUDGET).performClick()
        waitFor(InsightsTags.BUDGET_CHOICE)
        node(InsightsTags.BUDGET_OPTION + rest.v).performClick()
        // Restaurantes: R$ 20 a day, ideal on the 9th = 8000 + 14000 × 5 ÷ 7 = 18000; 23110 − 18000 = 5110;
        // projection 23110 × 62000 ÷ 18000 = 79601 → R$ 796 (book 10.6).
        compose.waitUntil(5_000) { runCatching { node(InsightsTags.PACE_BUDGET).assertTextContains("Restaurantes") }.isSuccess }
        node(InsightsTags.PACE_SENTENCE).assertTextEquals(r("+ R$ 51,10 sobre o ritmo ideal. No passo atual, o mês fecharia em R$ 796."))
        node(InsightsTags.PACE_CHART).assert(description(r("Ritmo do mês de Restaurantes: R$ 231,10 até o dia 9, contra ritmo ideal de R$ 180,00; no passo atual, R$ 796 no fim do mês, para uma cota de R$ 620,00"),
        ))
        node(InsightsTags.HISTORY_SENTENCE).assertTextEquals("Nenhum mês fora da margem.")
    }

    @Test
    fun `ranking and calendar count the month by value and by day`() {
        seed()
        open()
        waitFor(InsightsTags.RANK_SENTENCE)
        // Tags: show 477,00 (1) · feira 170,00 (2) · padaria 61,10 (1).
        node(InsightsTags.RANK_SENTENCE).assertTextEquals("show puxa a fila.")
        node(InsightsTags.RANK_TAGS).assertIsSelected()
        node(InsightsTags.RANK_ROW + 1).assertTextContains("feira").assertTextContains("2 lançamentos").assertTextContains("170,00")
        node(InsightsTags.RANK_ROW + 2).assertTextContains("1 lançamento").assertTextContains("61,10")
        node(InsightsTags.RANK_NOTE).assertExists()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, InsightsTags.RANK_MORE)).assertCountEquals(0)

        node(InsightsTags.RANK_MERCHANTS).performClick()
        compose.waitUntil(5_000) { runCatching { node(InsightsTags.RANK_SENTENCE).assertTextEquals("Teatro puxa a fila.") }.isSuccess }
        node(InsightsTags.RANK_ROW + 1).assertTextContains("Feira").assertTextContains("170,00")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, InsightsTags.RANK_NOTE)).assertCountEquals(0)

        // Days 2: 477,00 · 3: 120,00 · 4: 61,10 · 8: 50,00; step = ⌈47700 ÷ 4 to R$ 10⌉ = R$ 120.
        node(InsightsTags.CAL_LEGEND).performScrollTo().assertTextEquals(r("cada degrau ≈ R$ 120"))
        node(InsightsTags.CAL_DAY + "2026-10-02").assert(description(r("2: R$ 477,00"))).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "degrau 4"))
        node(InsightsTags.CAL_DAY + "2026-10-04").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "degrau 1"))
        node(InsightsTags.CAL_DAY + "2026-10-05").assert(description("5: sem gasto")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "degrau 0"))
        // Fridays: 47700 of 70810 = 67.4% → 67%; Oct 2026 has five (2, 9, 16, 23, 30).
        node(InsightsTags.CAL_SENTENCE).assertTextEquals("As sextas pesam.")
        node(InsightsTags.CAL_SHARE).assertTextEquals("67% do mês em cinco sextas")
    }

    @Test
    fun `ver todos opens the rest of the ranking`() {
        val id = runBlocking { db.createBudget("Casa", Cents(100000), OCT_1) }
        for (i in 1..8) add(id, 1000L * (10 - i), i, "m$i")
        open()
        waitFor(InsightsTags.RANK_MORE)
        node(InsightsTags.RANK_MORE).assertTextEquals("Ver todos (8)")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, InsightsTags.RANK_ROW + 6)).assertCountEquals(0)
        node(InsightsTags.RANK_MORE).performClick()
        waitFor(InsightsTags.RANK_ROW + 7)
        node(InsightsTags.RANK_ROW + 7).assertTextContains("m8")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, InsightsTags.RANK_MORE)).assertCountEquals(0)
    }

    @Test
    fun `a past month shows how it closed and an empty month says so`() {
        val id = runBlocking { db.createBudget("Mercado", Cents(124000), LocalDate(2026, 9, 1)) }
        runBlocking { db.addManual(TransactionForm(id, listOf("feira"), Cents(118000), 1, "Feira", LocalDate(2026, 9, 12), LocalTime(9, 0))).transaction }
        open()
        waitFor(InsightsTags.RANK_EMPTY)
        node(InsightsTags.RANK_EMPTY).assertTextEquals("Nada lançado em outubro.")
        node(InsightsTags.CAL_EMPTY).assertTextEquals("Nada lançado em outubro.")
        node(InsightsTags.MONTH_PREV).performClick()
        compose.waitUntil(5_000) { runCatching { node(InsightsTags.PACE_SENTENCE).assertTextEquals(r("Fechou em R$ 1.180,00, R$ 60,00 dentro da cota.")) }.isSuccess }
        node(InsightsTags.MONTH_LABEL).assertTextContains("setembro de 2026").assertTextContains("1 A 30")
        node(InsightsTags.PACE_CHART).assert(description(r("Ritmo do mês de Mercado: R$ 1.180,00 no mês, para uma cota de R$ 1.240,00")))
        node(InsightsTags.MONTH_PREV).assertIsNotEnabled()
        // Saturday Sep 12 holds the whole month; Sep 2026 has four Saturdays (5, 12, 19, 26).
        node(InsightsTags.CAL_SHARE).performScrollTo().assertTextEquals("100% do mês em quatro sábados")
    }

    /** money() puts U+202F after "R$" (widgets/Theme.kt). */
    private fun r(s: String) = s.replace("R$ ", "R$\u202F")

    private companion object {
        val OCT_1 = LocalDate(2026, 10, 1)
        val OCT_9 = LocalDate(2026, 10, 9)
    }
}
