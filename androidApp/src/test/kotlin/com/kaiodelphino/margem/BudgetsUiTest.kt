package com.kaiodelphino.margem

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
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
import com.kaiodelphino.margem.widgets.money
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Verbas screens end to end (D23, D35): App on a real Db (AndroidSQLiteDriver, D28), today fixed on
 * Friday, Oct 9 2026 (book §10), elements found by BudgetTags. Screens query on IO, so each step waits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BudgetsUiTest {

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

    private fun shown(tag: String) = compose.onAllNodesWithTagCount(tag) > 0

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { shown(tag) }

    private fun waitGone(tag: String) = compose.waitUntil(5_000) { !shown(tag) }

    private fun weeks(id: BudgetId) = runBlocking { db.budgetDetail(id, OCT, OCT_9).month.weeks }

    @Test
    fun `a new budget starts with its whole limit and a used name is refused`() {
        open()
        waitFor(BudgetTags.EMPTY)
        compose.onNodeWithTag(BudgetTags.ADD).performClick()
        waitFor(BudgetTags.FORM_NAME)
        compose.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()
        compose.onNodeWithTag(BudgetTags.FORM_NAME_ERROR).assertTextContains("Dê um nome à verba.")
        compose.onNodeWithTag(BudgetTags.FORM_LIMIT_ERROR).assertTextContains("A cota precisa ser maior que zero.")

        compose.onNodeWithTag(BudgetTags.FORM_NAME).performTextInput("Presentes")
        compose.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextInput("31000")
        // Book 10.7: R$ 310 in October → R$ 10,00 a day, a full week R$ 70,00; steps 40 · 70 · 70 · 70 · 60.
        waitFor(BudgetTags.FORM_PREVIEW)
        compose.onNodeWithTag(BudgetTags.FORM_PREVIEW)
            .assertTextContains("${money(Cents(1000))} por dia · semana cheia ${money(Cents(7000))}")
            .assertTextContains("26–31").assertTextContains("60")
        compose.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()

        compose.waitUntil(5_000) { runBlocking { db.budgetsOverview(OCT, OCT_9, BudgetOrder.RISK) }.isNotEmpty() }
        val budget = runBlocking { db.budgetsOverview(OCT, OCT_9, BudgetOrder.RISK) }.single()
        assertEquals("Presentes" to Cents(31000), budget.name to budget.month.remaining)
        waitFor(BudgetTags.ROW + budget.id.v)
        compose.onNodeWithTag(BudgetTags.ROW + budget.id.v).assertTextContains("margem ${money(Cents(31000))}")
        compose.onNodeWithTag(BudgetTags.HEADER).assertTextContains("Mês de 1 a 31 de outubro").assertTextContains("Dia 9 de 31.")
            .assertTextContains("Faltam 23 dias, contando hoje.")

        compose.onNodeWithTag(BudgetTags.ADD).performClick()
        waitFor(BudgetTags.FORM_NAME)
        compose.onNodeWithTag(BudgetTags.FORM_NAME).performTextInput("PRESENTES")
        compose.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextInput("100")
        compose.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()
        waitFor(BudgetTags.FORM_NAME_ERROR)
        compose.onNodeWithTag(BudgetTags.FORM_NAME_ERROR).assertTextContains("Já existe uma verba com esse nome.")
        assertEquals(1, runBlocking { db.budgetsOverview(OCT, OCT_9, BudgetOrder.RISK) }.size)
    }

    @Test
    @Config(shadows = [ActiveKeyboard::class])
    fun `a keyboard that composes the whole number loses no digit in the cota and the step`() {
        open()
        waitFor(BudgetTags.EMPTY)
        compose.onNodeWithTag(BudgetTags.ADD).performClick()
        waitFor(BudgetTags.FORM_NAME)
        compose.onNodeWithTag(BudgetTags.FORM_NAME).performTextInput("Mercado")
        compose.typeComposing(BudgetTags.FORM_LIMIT, "123456")
        waitFor(BudgetTags.FORM_PREVIEW)
        compose.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()
        compose.waitUntil(5_000) { runBlocking { db.budgetsOverview(OCT, OCT_9, BudgetOrder.RISK) }.isNotEmpty() }
        val id = runBlocking { db.budgetsOverview(OCT, OCT_9, BudgetOrder.RISK) }.single().id
        assertEquals(Cents(123456), runBlocking { db.budgetDetail(id, OCT, OCT_9) }.month.limit)

        // Editar verba: the keyboard picks up the loaded cota as its word and goes on from it.
        waitFor(BudgetTags.ROW + id.v)
        compose.onNodeWithTag(BudgetTags.ROW + id.v).performClick()
        waitFor(BudgetTags.DETAIL_EDIT)
        compose.onNodeWithTag(BudgetTags.DETAIL_EDIT).performClick()
        waitFor(BudgetTags.FORM_LIMIT)
        compose.typeComposing(BudgetTags.FORM_LIMIT, "78")
        compose.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()
        waitFor(BudgetTags.DETAIL_HEADLINE)
        assertEquals(Cents(12345678), runBlocking { db.budgetDetail(id, OCT, OCT_9) }.month.limit)

        compose.onNodeWithTag(BudgetTags.WEEK + "2026-10-05").performClick()
        waitFor(BudgetTags.STEP_DIALOG)
        compose.onNodeWithTag(BudgetTags.STEP_FIELD).performTextReplacement("")
        compose.typeComposing(BudgetTags.STEP_FIELD, "250000")
        compose.onNodeWithTag(BudgetTags.STEP_SAVE).performClick()
        waitGone(BudgetTags.STEP_DIALOG)
        assertEquals(Cents(250000), weeks(id)[1].step)
    }

    @Test
    fun `editing a step moves the others and the last automatic week stays locked`() {
        val id = runBlocking { db.createBudget("Mercado", Cents(60000), OCT_9) }
        open()
        waitFor(BudgetTags.ROW + id.v)
        compose.onNodeWithTag(BudgetTags.ROW + id.v).performClick()
        waitFor(BudgetTags.WEEK + "2026-10-05")

        compose.onNodeWithTag(BudgetTags.WEEK + "2026-10-05").performClick()
        waitFor(BudgetTags.STEP_DIALOG)
        compose.onNodeWithTag(BudgetTags.STEP_MAX).assertTextContains("no máximo ${money(Cents(60000))}")
        compose.onNodeWithTag(BudgetTags.STEP_FIELD).performTextReplacement("20000")
        compose.onNodeWithTag(BudgetTags.STEP_SAVE).performClick()
        waitGone(BudgetTags.STEP_DIALOG)
        assertEquals(60000L, weeks(id).sumOf { it.step.v }) // spec §4: the others adjust, the sum stays the limit
        assertEquals(Cents(20000), weeks(id)[1].step)

        // Above the max (600 − 200) the dialog stays and nothing changes.
        compose.onNodeWithTag(BudgetTags.WEEK + "2026-10-12").performClick()
        waitFor(BudgetTags.STEP_DIALOG)
        compose.onNodeWithTag(BudgetTags.STEP_MAX).assertTextContains("no máximo ${money(Cents(40000))}")
        compose.onNodeWithTag(BudgetTags.STEP_FIELD).performTextReplacement("40001")
        compose.onNodeWithTag(BudgetTags.STEP_SAVE).performClick()
        compose.waitForIdle()
        assertTrue(shown(BudgetTags.STEP_DIALOG))
        assertFalse(weeks(id)[2].edited)
        compose.onNodeWithTag(BudgetTags.STEP_CANCEL).performClick()
        waitGone(BudgetTags.STEP_DIALOG)

        runBlocking {
            for (d in listOf(1, 12, 19)) assertTrue(db.setStep(id, OCT, LocalDate(2026, 10, d), Cents(5000)))
        }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(BudgetTags.WEEK + "2026-10-26").assertIsNotEnabled() }.isSuccess }

        compose.onNodeWithTag(BudgetTags.WEEK + "2026-10-05").performClick()
        waitFor(BudgetTags.STEP_AUTO)
        compose.onNodeWithTag(BudgetTags.STEP_AUTO).performClick()
        waitGone(BudgetTags.STEP_DIALOG)
        assertFalse(weeks(id)[1].edited)
        assertTrue(weeks(id).none { it.locked })
    }

    @Test
    fun `a limit that drops step edits asks first`() {
        val id = runBlocking {
            db.createBudget("Mercado", Cents(60000), OCT_9).also {
                assertTrue(db.setStep(it, OCT, LocalDate(2026, 10, 5), Cents(20000)))
                assertTrue(db.setStep(it, OCT, LocalDate(2026, 10, 12), Cents(10000)))
            }
        }
        open()
        waitFor(BudgetTags.ROW + id.v)
        compose.onNodeWithTag(BudgetTags.ROW + id.v).performClick()
        waitFor(BudgetTags.DETAIL_EDIT)
        compose.onNodeWithTag(BudgetTags.DETAIL_EDIT).performClick()
        waitFor(BudgetTags.FORM_LIMIT)

        compose.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextReplacement("29999") // edits sum 300,00
        compose.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()
        waitFor(BudgetTags.LIMIT_DIALOG)
        compose.onNodeWithTag(BudgetTags.LIMIT_DIALOG_CANCEL).performClick()
        waitGone(BudgetTags.LIMIT_DIALOG)
        assertEquals(Cents(60000), runBlocking { db.budgetDetail(id, OCT, OCT_9) }.month.limit)
        assertEquals(2, weeks(id).count { it.edited })

        compose.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()
        waitFor(BudgetTags.LIMIT_DIALOG_CONFIRM)
        compose.onNodeWithTag(BudgetTags.LIMIT_DIALOG_CONFIRM).performClick()
        waitFor(BudgetTags.DETAIL_HEADLINE)
        assertEquals(Cents(29999), runBlocking { db.budgetDetail(id, OCT, OCT_9) }.month.limit)
        assertTrue(weeks(id).none { it.edited })
    }

    @Test
    fun `the detail sums the month line by line and marks where the limit was reached`() {
        val (id, lines) = runBlocking {
            val id = db.createBudget("Lazer", Cents(46500), LocalDate(2026, 10, 1))
            // Book 10.8: 315 + 48 + 56 + 46 = 465 reaches the limit; 12 more goes beyond it.
            val lines = listOf(1 to 31500L, 3 to 4800L, 7 to 5600L, 8 to 4600L, 9 to 1200L).map { (day, cents) ->
                db.addManual(TransactionForm(id, listOf("lazer"), Cents(cents), 1, "Loja $day", LocalDate(2026, 10, day), LocalTime(12, 0)))
            }
            id to lines
        }
        open()
        waitFor(BudgetTags.ROW + id.v)
        compose.onNodeWithTag(BudgetTags.ROW + id.v).assertTextContains("${money(Cents(1200))} além")
        compose.onNodeWithTag(BudgetTags.ROW + id.v).performClick()
        waitFor(BudgetTags.CUT)
        compose.onNodeWithTag(BudgetTags.DETAIL_HEADLINE).assertTextContains("${money(Cents(1200))} além da cota.")
        compose.onNodeWithTag(BudgetTags.LINE_SUM + lines[3].v).assertTextContains("465,00")
        compose.onNodeWithTag(BudgetTags.LINE_SUM + lines[4].v).assertTextContains("477,00")
        val cut = compose.onNodeWithTag(BudgetTags.CUT).fetchSemanticsNode().boundsInRoot
        assertTrue(cut.top >= compose.onNodeWithTag(BudgetTags.LINE + lines[3].v).fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(cut.bottom <= compose.onNodeWithTag(BudgetTags.LINE + lines[4].v).fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag(BudgetTags.MONTH_NEXT).assertIsNotEnabled()
        compose.onNodeWithTag(BudgetTags.MONTH_PREV).assertIsNotEnabled() // created in October
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp") // a phone: below the teto line, the rows must be on screen to be tapped
    fun `the order is remembered and deleting a budget returns to Verbas`() {
        val (a, b) = runBlocking { db.createBudget("Zebra", Cents(1000), OCT_9) to db.createBudget("Abacate", Cents(1000), OCT_9) }
        open()
        waitFor(BudgetTags.ORDER_AZ)
        compose.onNodeWithTag(BudgetTags.ORDER_AZ).performClick()
        compose.waitUntil(5_000) { runBlocking { db.settings() }.budgetOrder == BudgetOrder.AZ }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(BudgetTags.ORDER_AZ).assertIsSelected() }.isSuccess }
        val top = { id: BudgetId -> compose.onNodeWithTag(BudgetTags.ROW + id.v).fetchSemanticsNode().boundsInRoot.top }
        compose.waitUntil(5_000) { top(b) < top(a) }

        compose.onNodeWithTag(BudgetTags.ROW + a.v).performClick()
        waitFor(BudgetTags.DETAIL_EDIT)
        compose.onNodeWithTag(BudgetTags.DETAIL_EDIT).performClick()
        waitFor(BudgetTags.FORM_DELETE)
        compose.onNodeWithTag(BudgetTags.FORM_DELETE).performClick()
        waitFor(BudgetTags.DELETE_DIALOG)
        compose.onNodeWithTag(BudgetTags.DELETE_DIALOG).assertTextContains("Apagar Zebra?").assertTextContains("0 lançamentos", substring = true)
        compose.onNodeWithTag(BudgetTags.DELETE_DIALOG_CONFIRM).performClick()
        waitFor(BudgetTags.ORDER_AZ)
        waitGone(BudgetTags.ROW + a.v)
        assertEquals(listOf(b), runBlocking { db.budgetsOverview(OCT, OCT_9, BudgetOrder.AZ) }.map { it.id })
    }

    private companion object {
        val OCT_9 = LocalDate(2026, 10, 9)
        val OCT = YearMonth(2026, 10)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
