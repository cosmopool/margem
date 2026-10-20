package com.kaiodelphino.margem

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
import com.kaiodelphino.margem.widgets.money
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The teto end to end (spec §3, §7.1, book 8.10): the header line, the dialog with "Vale para quais meses?",
 * Desfazer, and the warning in Nova verba. Today is Friday, Oct 9 2026 (book §10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TetoUiTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: Db

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
    }

    @After
    fun tearDown() = db.close()

    private fun open() = compose.setContent {
        MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { OCT_9 }) { App(db, null) {} } }
    }

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { shown(tag) }

    private fun waitGone(tag: String) = compose.waitUntil(5_000) { !shown(tag) }

    private fun described(text: String) = SemanticsMatcher("description $text") {
        it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { d -> d == text }
    }

    private fun budgets() = runBlocking {
        db.createBudget("Mercado", Cents(124_000), OCT_9)
        db.createBudget("Casa", Cents(248_000), OCT_9)
    }

    @Test
    fun `no budget, no line, without a ceiling the dotted line offers one`() {
        open()
        waitFor(BudgetTags.EMPTY)
        assertEquals(false, shown(BudgetTags.CEILING))
        budgets()
        waitFor(BudgetTags.CEILING)
        compose.onNodeWithTag(BudgetTags.CEILING).assertTextContains("As cotas somam ${money(Cents(372_000))}.").assertTextContains("Pôr um teto")
            .assert(described("As cotas de outubro somam ${money(Cents(372_000))}. Sem teto. Tocar para pôr um teto."))
    }

    @Test
    fun `setting a ceiling only for October, then undoing it`() {
        budgets()
        runBlocking { db.ensureMonths(OCT); db.setCeiling(Cents(410_000), onward = true, OCT_9) }
        open()
        waitFor(BudgetTags.CEILING)
        compose.onNodeWithTag(BudgetTags.CEILING).assertTextContains("cotas ${money(Cents(372_000))}").assertTextContains("teto ${money(Cents(410_000))}")
            .assert(described("Teto de outubro: ${money(Cents(410_000))}. Cotas somam ${money(Cents(372_000))}, a ${money(Cents(38_000))} do teto. Tocar para mudar."))

        compose.onNodeWithTag(BudgetTags.HEADER).performClick()
        waitFor(BudgetTags.CEILING_DIALOG)
        compose.onNodeWithTag(BudgetTags.CEILING_FIELD).performTextReplacement("")
        compose.onNodeWithTag(BudgetTags.CEILING_SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(BudgetTags.CEILING_FIELD).performTextInput("350000")
        waitFor(BudgetTags.CEILING_HINT)
        compose.onNodeWithTag(BudgetTags.CEILING_HINT)
            .assertTextContains("As cotas de outubro somam ${money(Cents(372_000))}: ficariam ${money(Cents(22_000))} além do teto. Dá para salvar assim.")
        compose.onNodeWithTag(BudgetTags.CEILING_SAVE).performClick()
        waitFor(BudgetTags.CEILING_SCOPE)
        compose.onNodeWithTag(BudgetTags.CEILING_ONLY).assertTextContains("Em novembro, volta a ${money(Cents(410_000))}.").performClick()
        waitGone(BudgetTags.CEILING_DIALOG)
        compose.onNodeWithTag(SnackTags.TEXT).assertTextContains("Teto de ${money(Cents(350_000))} só em outubro.")
        assertEquals(Ceiling(OCT, Cents(350_000), Cents(410_000), Cents(372_000)), runBlocking { db.ceiling(OCT) })
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(BudgetTags.CEILING).assertTextContains("+ 220") }.isSuccess }
        compose.onNodeWithTag(BudgetTags.CEILING).assertTextContains("teto ${money(Cents(350_000))} · só outubro")

        compose.onNodeWithTag(SnackTags.ACTION).performClick()
        compose.waitUntil(5_000) { runBlocking { db.ceiling(OCT) }.cents == Cents(410_000) }
        assertEquals(Cents(410_000), runBlocking { db.ceiling(OCT) }.standard)
    }

    @Test
    fun `removing from October on`() {
        budgets()
        runBlocking { db.ensureMonths(OCT); db.setCeiling(Cents(410_000), onward = true, OCT_9) }
        open()
        waitFor(BudgetTags.CEILING)
        compose.onNodeWithTag(BudgetTags.CEILING).performClick()
        waitFor(BudgetTags.CEILING_REMOVE)
        compose.onNodeWithTag(BudgetTags.CEILING_REMOVE).performClick()
        compose.onNodeWithTag(BudgetTags.CEILING_SCOPE).assertTextContains("Tirar o teto de quais meses?")
        compose.onNodeWithTag(BudgetTags.CEILING_ONWARD).assertTextContains("Os próximos meses ficam sem teto.").performClick()
        waitGone(BudgetTags.CEILING_DIALOG)
        compose.onNodeWithTag(SnackTags.TEXT).assertTextContains("Outubro em diante sem teto.")
        assertEquals(Ceiling(OCT, Cents(0), Cents(0), Cents(372_000)), runBlocking { db.ceiling(OCT) })
    }

    @Test
    fun `a new budget past the ceiling warns, Voltar writes nothing, confirming creates it`() {
        budgets()
        runBlocking { db.ensureMonths(OCT); db.setCeiling(Cents(410_000), onward = true, OCT_9) }
        open()
        waitFor(BudgetTags.ADD)
        compose.onNodeWithTag(BudgetTags.ADD).performClick()
        waitFor(BudgetTags.FORM_NAME)
        compose.onNodeWithTag(BudgetTags.FORM_NAME).performTextInput("Lazer")
        compose.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextInput("30000")
        assertEquals(false, shown(BudgetTags.FORM_CEILING)) // 4.020,00: under the ceiling
        compose.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextInput("0")
        waitFor(BudgetTags.FORM_CEILING)
        compose.onNodeWithTag(BudgetTags.FORM_CEILING)
            .assertTextContains("Com esta cota, as cotas de outubro somam ${money(Cents(672_000))}: ${money(Cents(262_000))} além do teto.")
        compose.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()
        waitFor(BudgetTags.CEILING_WARN)
        compose.onNodeWithTag(BudgetTags.CEILING_WARN_CANCEL).performClick()
        waitGone(BudgetTags.CEILING_WARN)
        assertEquals(2, runBlocking { db.budgetsOverview(OCT, OCT_9, BudgetOrder.RISK) }.size)
        compose.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()
        waitFor(BudgetTags.CEILING_WARN)
        compose.onNodeWithTag(BudgetTags.CEILING_WARN_CONFIRM).performClick()
        compose.waitUntil(5_000) { runBlocking { db.budgetsOverview(OCT, OCT_9, BudgetOrder.RISK) }.size == 3 }
    }

    private companion object {
        val OCT_9 = LocalDate(2026, 10, 9)
        val OCT = YearMonth(2026, 10)
    }
}
