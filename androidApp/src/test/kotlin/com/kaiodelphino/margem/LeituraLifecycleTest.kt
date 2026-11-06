package com.kaiodelphino.margem

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Leitura v2 (new-leitura-page), ciclo de vida pela UI, em fumaça (API exploratória): sair da aba zera
 * (D-5), a página do widget sobrevive à recriação (D18), o Desfazer depois da saída não quebra, e a virada
 * do dia com a tela aberta relê com o `today` atual. Tags: o contrato descrito em LeituraUiTest.
 *
 * Hoje fixo (LocalToday, lido a cada chamada): sexta, 6/11/2026; mês da verba de 1 a 30.
 * Novembro: Delivery pizza R$ 40 (3/11), Mercado padaria R$ 30 (2/11) = R$ 70,00.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class LeituraLifecycleTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    private var show by mutableStateOf(true)

    @Volatile private var today: LocalDate = NOV_6

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
    }

    @After
    fun tearDown() {
        runCatching {
            show = false
            composeRule.waitForIdle()
        }
        db.close()
    }

    @Composable
    private fun Content() {
        if (!show) return
        MargemTheme(dark = false) {
            CompositionLocalProvider(LocalToday provides { today }) { App(db, null) {} }
        }
    }

    @Test
    fun `AC-14, AC-F4 Desfazer depois de sair da aba não quebra e a Leitura volta no mês atual sem chips`() {
        seed()
        composeRule.setContent { Content() }
        openInsights()
        await("resumo", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 70,00") }
        pick("delivery", ChipKind.BUDGET, "Delivery")
        await("só Delivery", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 40,00") }

        openPage("para-onde-foi")
        option("marcador")
        tapIn("para-onde-foi", "pizza")
        tap(InsightsTags.ONLY)
        await("Desfazer na tela", SnackTags.ACTION) { shows(SnackTags.ACTION, "Desfazer") }

        tapTab("Verbas")
        await("Verbas aberta") { exists(BudgetTags.LIST) && !exists(InsightsTags.SUMMARY) }
        tap(SnackTags.ACTION)
        settle()

        openInsights()
        await("mês atual sem chips", InsightsTags.SUMMARY) {
            shows(InsightsTags.SUMMARY, "R$ 70,00") && !shows(InsightsTags.CHIPS, "Delivery") && !shows(InsightsTags.CHIPS, "pizza")
        }
    }

    @Test
    fun `AC-F3 a página do widget, o filtro e as opções sobrevivem à recriação`() {
        seed()
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openInsights()
        pick("delivery", ChipKind.BUDGET, "Delivery")
        openPage("para-onde-foi")
        option("marcador")
        await("marcador escolhido") { selectedRadio("marcador") }

        restore.emulateSavedInstanceStateRestore()
        await("página, chip e opção de volta", InsightsTags.PAGE + "para-onde-foi", InsightsTags.CHIPS) {
            exists(InsightsTags.PAGE + "para-onde-foi") && shows(InsightsTags.CHIPS, "Delivery") && selectedRadio("marcador")
        }
    }

    @Test
    fun `AC-F2 a virada do mês com a Leitura aberta relê com o today atual quando o Db muda`() {
        today = LocalDate(2026, 10, 31)
        val casa = fora { db.createBudget("Casa", Cents(100_000), LocalDate(2026, 10, 1)) }
        tx(casa, 10_000, LocalDate(2026, 10, 30), listOf("feira"), "Sacolao")
        composeRule.setContent { Content() }
        openInsights()
        await("outubro", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 100,00") }

        today = LocalDate(2026, 11, 1)
        tx(casa, 700, LocalDate(2026, 11, 1), listOf("padaria"), "Padoca")
        await("novembro depois da virada", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 7,00") && !shows(InsightsTags.SUMMARY, "R$ 100,00") }
    }

    // ---- Cenário ----

    private fun seed() {
        val mercado = fora { db.createBudget("Mercado", Cents(100_000), LocalDate(2026, 10, 1)) }
        val delivery = fora { db.createBudget("Delivery", Cents(40_000), LocalDate(2026, 10, 1)) }
        tx(mercado, 3_000, LocalDate(2026, 11, 2), listOf("padaria"), "Padaria")
        tx(delivery, 4_000, LocalDate(2026, 11, 3), listOf("pizza"), "Pizzaria")
    }

    private fun tx(budget: BudgetId, cents: Long, date: LocalDate, tags: List<String>, merchant: String): TransactionId =
        fora {
            db.addManual(TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = 1, merchant = merchant, date = date, time = LocalTime(10, 0))).transaction
        }.also { assertNotEquals("addManual $merchant", 0L, it.v) }

    private fun <T> fora(block: suspend () -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { block() } }

    // ---- Navegação e filtro ----

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun tapTab(label: String) {
        composeRule.onAllNodes(hasText(label) and tabRole).onFirst().performClick()
        composeRule.waitForIdle()
    }

    private fun openInsights() {
        await("barra de abas") { composeRule.onAllNodes(hasText("Leitura") and tabRole).fetchSemanticsNodes().isNotEmpty() }
        tapTab("Leitura")
        await("Leitura aberta") { exists(InsightsTags.EMPTY) || exists(InsightsTags.SUMMARY) }
    }

    private fun sectionTag(kind: ChipKind) = InsightsTags.SECTION + kind.name

    private fun pick(text: String, kind: ChipKind, label: String) {
        if (!exists(InsightsTags.SEARCH) && exists(InsightsTags.LENS)) tap(InsightsTags.LENS)
        await("campo de busca", InsightsTags.SEARCH) { exists(InsightsTags.SEARCH) }
        composeRule.onAllNodesWithTag(InsightsTags.SEARCH, useUnmergedTree = true).onFirst().performTextReplacement(text)
        await("sugestão $label", sectionTag(kind)) { shows(sectionTag(kind), label) }
        composeRule.onAllNodes(hasText(label, substring = true) and hasAnyAncestor(hasTestTag(sectionTag(kind))), useUnmergedTree = true).onFirst().performClick()
        await("chip $label", InsightsTags.CHIPS) { shows(InsightsTags.CHIPS, label) }
    }

    private fun openPage(id: String) {
        if (exists(InsightsTags.GRID)) runCatching { composeRule.onAllNodesWithTag(InsightsTags.GRID).onFirst().performScrollToNode(hasTestTag(InsightsTags.WIDGET + id)) }
        tap(InsightsTags.WIDGET + id)
        await("página $id", InsightsTags.PAGE + id) { exists(InsightsTags.PAGE + id) }
    }

    private fun radio(label: String): SemanticsNode? =
        composeRule.onAllNodes(hasText(label) and radioRole).fetchSemanticsNodes().firstOrNull()

    private fun selectedRadio(label: String) = radio(label)?.config?.getOrNull(SemanticsProperties.Selected) == true

    private fun option(label: String) {
        await("opção $label") { radio(label) != null }
        composeRule.onAllNodes(hasText(label) and radioRole).onFirst().performClick()
        composeRule.waitForIdle()
    }

    private fun tapIn(id: String, text: String) {
        val m = hasText(text) and hasAnyAncestor(hasTestTag(InsightsTags.PAGE + id)) and !hasAnyAncestor(hasTestTag(InsightsTags.CHIPS))
        await("pedaço $text em $id", InsightsTags.PAGE + id) { composeRule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val n = composeRule.onAllNodes(m, useUnmergedTree = true).onFirst()
        runCatching { n.performScrollTo() }
        n.performClick()
        composeRule.waitForIdle()
    }

    // ---- Semântica ----

    private fun nodes(tag: String): List<SemanticsNode> = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    private fun exists(tag: String) = nodes(tag).isNotEmpty()

    private fun norm(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    private fun collect(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text) +
            n.children.flatMap(::collect)

    private fun texts(tag: String): List<String> = nodes(tag).flatMap(::collect).map(::norm)

    private fun shows(tag: String, s: String): Boolean {
        val t = texts(tag)
        val want = norm(s)
        return t.any { it.contains(want) } || t.joinToString(" ").contains(want)
    }

    private fun tap(tag: String) {
        val n = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst()
        runCatching { n.performScrollTo() }
        n.performClick()
        composeRule.waitForIdle()
    }

    private fun await(what: String, vararg show: String, cond: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) { cond() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what; na tela: " + show.joinToString { "$it=${texts(it)}" }, e)
        }
    }

    /** Deixa as consultas de IO voltarem; uma exceção na composição sai aqui. */
    private fun settle() = repeat(10) {
        Thread.sleep(20)
        composeRule.waitForIdle()
    }

    private companion object {
        val NOV_6 = LocalDate(2026, 11, 6)
        const val OP_TIMEOUT_MS = 10_000L
        const val UI_TIMEOUT_MS = 10_000L
    }
}
