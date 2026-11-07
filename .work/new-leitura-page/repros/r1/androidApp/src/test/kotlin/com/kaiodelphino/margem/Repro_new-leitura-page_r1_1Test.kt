package com.kaiodelphino.margem

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.widgets.MargemTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repro: Total e costume compares the whole interval (future-dated parcelas included) against the earlier
 * interval cut at today's day (AC-23, AC-28).
 *
 * Today 6/11/2026, budget month 1–30. Spending in the same stretch: R$ 50 on 3/10 and R$ 50 on 3/11, so
 * "este mês" against "o anterior" (1–6 Oct) is equal. A purchase of R$ 300 in 3 parcelas on 20/10 puts a
 * R$ 100 parcela on 20/11, which is after today and has no counterpart in 1–6 Oct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class `Repro_new-leitura-page_r1_1Test` {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `AC-23, AC-28 Total e costume compares the same stretch, not the future parcela of this month`() {
        val mercado = fora { db.createBudget("Mercado", Cents(100_000), LocalDate(2026, 8, 1)) }.also { assertNotEquals(0L, it.v) }
        tx(mercado, 5_000, LocalDate(2026, 10, 3), "feira", "Sacolao")
        tx(mercado, 5_000, LocalDate(2026, 11, 3), "feira", "Sacolao")
        tx(mercado, 30_000, LocalDate(2026, 10, 20), "moveis", "Loja Movel", installments = 3)

        composeRule.setContent {
            MargemTheme(dark = false) {
                CompositionLocalProvider(LocalToday provides { NOV_6 }) { App(db, null) {} }
            }
        }
        await("barra de abas") { composeRule.onAllNodes(hasText("Leitura") and tabRole).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(hasText("Leitura") and tabRole).onFirst().performClick()
        await("Leitura aberta", InsightsTags.SUMMARY) { exists(InsightsTags.SUMMARY) }

        val page = InsightsTags.PAGE + "total"
        runCatching { composeRule.onAllNodesWithTag(InsightsTags.GRID).onFirst().performScrollToNode(hasTestTag(InsightsTags.WIDGET + "total")) }
        tap(InsightsTags.WIDGET + "total")
        await("página total", page) { exists(page) }
        await("opção o anterior") { composeRule.onAllNodes(hasText("o anterior") and radioRole).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(hasText("o anterior") and radioRole).onFirst().performClick()
        composeRule.waitForIdle()

        // 1–6 Nov: R$ 50; 1–6 Oct: R$ 50. Same stretch, same spending.
        await("frase do Total e costume contra o anterior", page) { shows(page, "igual ao costume") || shows(page, "R$ 100,00 acima do costume") }
        assertTrue(
            "1–6 nov (R$ 50) contra 1–6 out (R$ 50) é igual ao costume; na tela: ${texts(page)}",
            shows(page, "igual ao costume") && !shows(page, "acima do costume"),
        )
    }

    private fun tx(budget: BudgetId, cents: Long, date: LocalDate, tag: String, merchant: String, installments: Int = 1) {
        val form = TransactionForm(budget = budget, tags = listOf(tag), total = Cents(cents), installments = installments, merchant = merchant, date = date, time = LocalTime(10, 0), caption = "")
        assertNotEquals("addManual $merchant", 0L, fora { db.addManual(form).transaction }.v)
    }

    private fun <T> fora(block: suspend () -> T): T = runBlocking(Dispatchers.Default) { withTimeout(10_000L) { block() } }

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun nodes(tag: String): List<SemanticsNode> = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    private fun exists(tag: String) = nodes(tag).isNotEmpty()

    private fun norm(s: String) = s.replace('\u202F', ' ').replace('\u00A0', ' ')

    private fun collect(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            n.children.flatMap(::collect)

    private fun texts(tag: String): List<String> = nodes(tag).flatMap(::collect).map(::norm)

    private fun shows(tag: String, s: String): Boolean = texts(tag).let { t -> t.any { it.contains(norm(s)) } || t.joinToString(" ").contains(norm(s)) }

    private fun tap(tag: String) {
        val n = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst()
        runCatching { n.performScrollTo() }
        n.performClick()
        composeRule.waitForIdle()
    }

    private fun await(what: String, vararg show: String, cond: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = 10_000L) { cond() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what; na tela: " + show.joinToString { "$it=${texts(it)}" }, e)
        }
    }

    private companion object {
        val NOV_6 = LocalDate(2026, 11, 6)
    }
}
