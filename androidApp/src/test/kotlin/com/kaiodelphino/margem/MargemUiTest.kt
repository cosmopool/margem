package com.kaiodelphino.margem

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.Screen
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.MargemTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The margem honesta on screen (margem-plan M9, M12, D73): Casa, cota R$ 3.000,00, R$ 40,00 spent on 1 out and
 * the aluguel of R$ 2.000,00 expected on 20 out, today 9 out. Month from day 1, weeks from Monday: the cota livre
 * of R$ 1.000,00 gives steps 129,03 · 225,80 · …, and the ideal of 9 out is 129,03 + 225,80 × 5 ÷ 7 = R$ 290,31.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MargemUiTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: Db
    private var gone by mutableStateOf(false)

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
    }

    @After
    fun tearDown() {
        gone = true
        compose.waitForIdle()
        db.close()
    }

    private fun open(screen: Screen? = null) = compose.setContent {
        if (gone) return@setContent
        MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { OCT_9 }) { App(db, screen) {} } }
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun gone(tag: String) = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().isEmpty()

    private fun said(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(r(text)))

    private fun r(s: String) = s.replace("R$ ", "R$ ")

    /** Casa with its aluguel; returns the budget and the aluguel's October occurrence. */
    private fun seed(): Pair<BudgetId, OccurrenceId> = runBlocking {
        val casa = db.createBudget("Casa", Cents(300_000), OCT_9)
        db.addManual(TransactionForm(casa, listOf("padaria"), Cents(4_000), 1, "Padaria", LocalDate(2026, 10, 1), LocalTime(8, 0)))
        db.addManual(TransactionForm(casa, listOf("casa"), Cents(200_000), 1, "", LocalDate(2026, 10, 20), LocalTime(9, 0), caption = "Aluguel", repeats = Repeats.MONTHLY), OCT_9)
        casa to db.budgetDetail(casa, OCT, OCT_9).plannedLines.single().planned.occurrence
    }

    @Test
    fun `the header sums the month with the previstos and the row says them`() {
        val (casa, _) = seed()
        open()
        waitFor(BudgetTags.CEILING)
        // M9, M12: margem 3.000 − 40 − 2.000; no teto in this month.
        compose.onNodeWithTag(BudgetTags.CEILING)
            .assertTextEquals(r("Margem R$ 960,00 de R$ 3.000,00"), "gasto 40 · previstos 2.000", "Pôr um teto")
            .assert(said("Outubro: margem de R$ 960,00 de R$ 3.000,00. Gasto R$ 40,00, previstos R$ 2.000,00. Sem teto. Tocar para pôr um teto."))
        compose.onNodeWithTag(BudgetTags.ROW + casa.v)
            .assertTextContains(r("margem R$ 960,00"))
            .assert(said("Casa, R$ 40,00 de R$ 3.000,00, R$ 2.000,00 em previstos, no ritmo, margem de R$ 960,00."))

        runBlocking { db.setCeiling(Cents(400_000), onward = true, OCT_9) }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag(BudgetTags.CEILING).assertTextContains("gasto 40 · previstos 2.000 · teto 4.000") }.isSuccess
        }
        compose.onNodeWithTag(BudgetTags.CEILING).assert(
            said("Outubro: margem de R$ 960,00 de R$ 3.000,00. Gasto R$ 40,00, previstos R$ 2.000,00. Teto R$ 4.000,00, cotas a R$ 1.000,00 do teto. Tocar para mudar o teto."),
        )
    }

    @Test
    fun `the detail says the previstos and the steps share the cota livre`() {
        val (casa, occurrence) = seed()
        open(Screen.Budget(casa, OCT))
        waitFor(BudgetTags.DETAIL_HEADLINE)
        compose.onNodeWithTag(BudgetTags.DETAIL_HEADLINE).assertTextEquals(
            r("Sobram R$ 960,00."),
            r("Você lançou R$ 40,00 de R$ 3.000,00 e tem R$ 2.000,00 em previstos. No ritmo: o ideal da cota livre até hoje é R$ 290,31."),
        )
        compose.onNodeWithTag(BudgetTags.PLANNED_LINES).assertTextEquals("Previstos de outubro", "valor")
        compose.onNodeWithTag(BudgetTags.PLANNED_LINE + occurrence.v)
            .assertTextContains("Aluguel").assertTextContains("esperando · todo mês").assertTextContains(r("R$ 2.000,00"))
        compose.onNodeWithTag(BudgetTags.STEPS_SUM)
            .assertTextEquals("Soma = cota livre", r("cota R$ 3.000,00 − previstos R$ 2.000,00"), r("R$ 1.000,00"))

        compose.onNodeWithTag(BudgetTags.PLANNED_LINE + occurrence.v).performClick()
        waitFor(TransactionTags.PLANNED_SHEET)
        waitFor(TransactionTags.PLANNED_SKIP)
    }

    @Test
    fun `a lancada line opens the sheet with only edit and delete and the edit finds the previsto`() {
        val (casa, occurrence) = seed()
        runBlocking {
            db.addManual(TransactionForm(casa, listOf("casa"), Cents(210_000), 1, "", OCT_9, LocalTime(10, 0), caption = "Aluguel", occurrence = occurrence))
        }
        open(Screen.Budget(casa, OCT))
        waitFor(BudgetTags.PLANNED_LINE + occurrence.v)
        compose.onNodeWithTag(BudgetTags.PLANNED_LINE + occurrence.v)
            .assertTextContains("lançado em 9 out").assertTextContains(r("R$ 2.100,00"))
        compose.onNodeWithTag(BudgetTags.STEPS_SUM).assertTextContains(r("cota R$ 3.000,00 − previstos R$ 2.100,00"))

        compose.onNodeWithTag(BudgetTags.PLANNED_LINE + occurrence.v).performClick()
        waitFor(TransactionTags.PLANNED_SHEET)
        compose.onAllNodes(hasText("Lançado em 9\u00A0out.") and hasAnyAncestor(hasTestTag(TransactionTags.PLANNED_SHEET)), useUnmergedTree = true).assertCountEquals(1)
        assertTrue(gone(TransactionTags.PLANNED_SKIP) && gone(TransactionTags.PLANNED_LAUNCH_NO_NOTICE))
        compose.onNodeWithTag(TransactionTags.PLANNED_EDIT).performClick()
        waitFor(TransactionTags.FORM_TITLE)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(TransactionTags.FORM_TITLE).assertTextContains("Editar previsto") }.isSuccess }
        // The form found the lançada occurrence: no "Este previsto não existe mais." and still open.
        waitFor(TransactionTags.FORM_SUBMIT)
        assertTrue(gone(TransactionTags.PLANNED_SHEET))
    }

    @Test
    fun `leitura compares the gasto variavel with the ideal of the cota livre`() {
        seed()
        open(Screen.Insights)
        // Leitura v2: the Ritmo sits inside its widget (WIDGET + "ritmo", clickable, so merged): read the unmerged tree.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(InsightsTags.PACE_SENTENCE) and hasAnyAncestor(hasTestTag(InsightsTags.WIDGET + "ritmo")), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // 290,31 − 40,00 below; M6: 4.000 × 100.000 ÷ 29.031 = 13.778 + 200.000 = R$ 2.137,78 → R$ 2.138.
        compose.onNodeWithTag(InsightsTags.PACE_SENTENCE, useUnmergedTree = true)
            .assertTextEquals(r("R$ 250,31 abaixo do ritmo ideal. No passo atual, o mês fecharia em R$ 2.138."))
    }

    private companion object {
        val OCT_9 = LocalDate(2026, 10, 9)
        val OCT = YearMonth(2026, 10)
    }
}
