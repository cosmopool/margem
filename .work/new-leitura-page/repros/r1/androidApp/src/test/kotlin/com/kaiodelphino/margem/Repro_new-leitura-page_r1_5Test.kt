package com.kaiodelphino.margem

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.widgets.MargemTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
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
 * Repro: na página Mês a mês, cada coluna é um alvo tocável (AC-17, "Usar como intervalo"), mas o TalkBack não tem
 * o que ler nela — nem o mês nem o valor. Hoje fixo: 6/11/2026; gastos em set, out e nov.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class `Repro_new-leitura-page_r1_5Test` {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `AC-17 cada coluna tocável de Mês a mês tem um rótulo para o TalkBack`() {
        runBlocking(Dispatchers.Default) {
            val b = db.createBudget("Mercado", Cents(100_000), LocalDate(2026, 8, 1))
            for ((d, v) in listOf(LocalDate(2026, 9, 10) to 5_000L, LocalDate(2026, 10, 10) to 7_000L, LocalDate(2026, 11, 3) to 3_000L))
                db.addManual(TransactionForm(budget = b, tags = listOf("feira"), total = Cents(v), installments = 1, merchant = "Feira", date = d, time = LocalTime(10, 0)))
        }
        composeRule.setContent {
            MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { LocalDate(2026, 11, 6) }) { App(db, null) {} } }
        }
        val tab = hasText("Leitura") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(tab).onFirst().performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(InsightsTags.GRID).fetchSemanticsNodes().isNotEmpty() }
        val widget = InsightsTags.WIDGET + "mes-a-mes"
        composeRule.onAllNodesWithTag(InsightsTags.GRID).onFirst().performScrollToNode(hasTestTag(widget))
        composeRule.onAllNodesWithTag(widget).onFirst().performClick()
        val page = InsightsTags.PAGE + "mes-a-mes"
        val clickable = hasClickAction() and hasAnyAncestor(hasTestTag(page))
        // espera a consulta de IO desenhar o gráfico (as opções 3/6/12 já aparecem junto com ele)
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("12") and hasAnyAncestor(hasTestTag(page))).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()

        val targets = composeRule.onAllNodes(clickable).fetchSemanticsNodes()
        val unnamed = targets.filter { label(it).isEmpty() }
        assertTrue(
            "${unnamed.size} de ${targets.size} alvos tocáveis da página Mês a mês não têm texto nem contentDescription " +
                "(o TalkBack só diz \"tocável\"); rótulos lidos: ${targets.map(::label)}",
            unnamed.isEmpty(),
        )
    }

    private fun label(n: SemanticsNode): String =
        (n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.config.getOrNull(SemanticsActions.OnClick)?.label.let(::listOfNotNull)).joinToString(" ").trim()
}
