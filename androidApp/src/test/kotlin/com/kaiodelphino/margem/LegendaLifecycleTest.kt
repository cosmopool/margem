package com.kaiodelphino.margem

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.Screen
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.money
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Legenda do lançamento (spec §2.12, §7.3; D57): o ciclo de vida do campo sob restauração de estado.
 * O estado saveable é o texto da legenda e o aberto/fechado (TransactionTags: rememberSaveable); a
 * linha "legenda tirada: ~~…~~ Desfazer" ([TransactionTags.FORM_CAPTION_REMOVED]) é derivada
 * (`captionOriginal.isNotEmpty() && caption.isEmpty()`), nunca um estado que sobrevive a Salvar; o
 * contador ([TransactionTags.FORM_CAPTION_COUNT]) acompanha o texto restaurado. Tags: [TransactionTags].
 *
 * A UI da legenda ainda não existe (rodada de contrato fechado): os testes são o contrato do que ela
 * tem que fazer sob `StateRestorationTester.emulateSavedInstanceStateRestore()`.
 *
 * Convenções: Robolectric + Compose UI test + AndroidSQLiteDriver (D28); sqlite temporário por teste
 * com prefixo "margem-legenda-"; Db com TimeZone "America/Sao_Paulo"; datas fixas — hoje (LocalToday)
 * é quarta, 14/10/2026. Plain Application: MargemApp abriria o Db de produção com o driver bundled.
 * Tela alta para o formulário inteiro caber sem rolagem preguiçosa.
 *
 * Sem recados: a legenda nasce no formulário e vive na edição, então os lançamentos vêm de `addManual`
 * e a edição abre por Lançamentos ([TransactionTags.ROW]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h1800dp")
class LegendaLifecycleTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    /** Tira o App da composição antes de fechar o Db, para nenhuma consulta rodar num Db fechado. */
    private var show by mutableStateOf(true)

    /** App.tabRequest: abre uma aba como os widgets fazem (a barra de abas não tem tags). */
    private var tab by mutableStateOf<Screen?>(null)

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem-legenda-${Random.nextLong().toULong()}.db").also { it.delete() }.path, timeZone = ZONA)
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
            CompositionLocalProvider(LocalToday provides { DIA }) { App(db, tab) { tab = null } }
        }
    }

    // ---- O texto e o aberto/fechado sobrevivem à restauração (D57) ----

    @Test
    fun `legenda digitada sem salvar sobrevive à restauração com o campo aberto`() {
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openNew()
        abrirLegenda()
        type(TransactionTags.FORM_CAPTION, TEXTO)
        await("legenda digitada no campo aberto", TransactionTags.FORM_CAPTION) {
            shows(TransactionTags.FORM_CAPTION, TEXTO) && !exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }

        restore.emulateSavedInstanceStateRestore()

        await("o texto e o campo aberto sobrevivem à restauração", TransactionTags.FORM_CAPTION) {
            shows(TransactionTags.FORM_CAPTION, TEXTO) && !exists(TransactionTags.FORM_CAPTION_OPEN)
        }
    }

    @Test
    fun `campo de legenda recolhido sobrevive à restauração recolhido`() {
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openNew()
        await("link recolhido no lançamento novo") {
            exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION) && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }

        restore.emulateSavedInstanceStateRestore()

        await("continua recolhido depois da restauração") {
            exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION) && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }
    }

    @Test
    fun `legenda salva sobrevive à restauração com o campo à vista na edição`() {
        val t = comLegenda(TEXTO)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openEdit(t)
        await("a edição abre com o campo à vista e a legenda salva") {
            exists(TransactionTags.FORM_CAPTION) && shows(TransactionTags.FORM_CAPTION, TEXTO) &&
                !exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }

        restore.emulateSavedInstanceStateRestore()

        await("a legenda e o campo à vista sobrevivem à restauração", TransactionTags.FORM_CAPTION) {
            shows(TransactionTags.FORM_CAPTION, TEXTO) && !exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }
    }

    // ---- Navegação: sair da edição sem salvar e voltar ----

    /**
     * Nem a spec (§2.12, §7.3) nem o contrato (TransactionTags: "survives Activity recreation and
     * process death") mandam o rascunho sobreviver à navegação; o contrato de fala só cobre recriação
     * e morte do processo. O resto do formulário descarta o rascunho ao sair da tela (D18: uma pilha e
     * um `when`; "the first load fills them once") e a legenda segue o resto do formulário — então o
     * que existe e é exigido aqui é: voltar à edição sem salvar mostra de novo a legenda salva à vista,
     * sem a linha "legenda tirada" vazando, e nada foi gravado. Se o produto decidir que rascunho
     * sobrevive à navegação, este teste muda com essa decisão.
     */
    @Test
    fun `sair e voltar à edição sem salvar mostra a legenda salva de novo e não grava nada`() {
        val t = comLegenda(TEXTO)
        composeRule.setContent { Content() }
        openEdit(t)
        await("campo com a legenda salva") { shows(TransactionTags.FORM_CAPTION, TEXTO) }
        replaceText(TransactionTags.FORM_CAPTION, "$TEXTO grande") // rascunho, sem salvar
        await("rascunho no campo") { shows(TransactionTags.FORM_CAPTION, "$TEXTO grande") }

        pressBack()
        await("de volta a Lançamentos") { exists(TransactionTags.NEW) && !exists(TransactionTags.FORM_TITLE) }

        openEdit(t)
        await("a edição reabre com a legenda salva à vista", TransactionTags.FORM_CAPTION) {
            shows(TransactionTags.FORM_CAPTION, TEXTO) && !shows(TransactionTags.FORM_CAPTION, "grande") &&
                !exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION_REMOVED) &&
                // The money field's semantics read the formatted pieces ("R$", "46,00" + a zero-width tail).
                shows(TransactionTags.FORM_TOTAL, "46,00")
        }
        assertEquals("sair sem salvar não gravou nada", TEXTO, fora { db.transaction(t) }.row.caption)
    }

    // ---- A linha "legenda tirada: ~~…~~ Desfazer" (derivada, edit only) ----

    @Test
    fun `✕ mostra a linha "legenda tirada" que sobrevive à restauração - Desfazer devolve o texto, texto novo some com a linha`() {
        val t = comLegenda(TEXTO)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openEdit(t)
        await("campo com a legenda salva") { shows(TransactionTags.FORM_CAPTION, TEXTO) }
        tap(TransactionTags.FORM_CAPTION_CLEAR)
        await("✕ esvazia, recolhe e mostra a linha", TransactionTags.FORM_CAPTION_REMOVED) {
            !exists(TransactionTags.FORM_CAPTION) && exists(TransactionTags.FORM_CAPTION_OPEN) &&
                shows(TransactionTags.FORM_CAPTION_REMOVED, TEXTO) && exists(TransactionTags.FORM_CAPTION_REMOVED + ".undo")
        }

        restore.emulateSavedInstanceStateRestore()

        await("a linha e o campo recolhido sobrevivem à restauração", TransactionTags.FORM_CAPTION_REMOVED) {
            shows(TransactionTags.FORM_CAPTION_REMOVED, TEXTO) && exists(TransactionTags.FORM_CAPTION_REMOVED + ".undo") &&
                !exists(TransactionTags.FORM_CAPTION) && exists(TransactionTags.FORM_CAPTION_OPEN)
        }

        tap(TransactionTags.FORM_CAPTION_REMOVED + ".undo")
        await("Desfazer devolve o texto e reabre o campo") {
            shows(TransactionTags.FORM_CAPTION, TEXTO) && !exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }

        tap(TransactionTags.FORM_CAPTION_CLEAR)
        await("a linha volta ao tirar de novo") {
            shows(TransactionTags.FORM_CAPTION_REMOVED, TEXTO) && exists(TransactionTags.FORM_CAPTION_OPEN)
        }
        tap(TransactionTags.FORM_CAPTION_OPEN)
        await("campo reaberto ainda sem legenda: a linha fica") {
            exists(TransactionTags.FORM_CAPTION) && shows(TransactionTags.FORM_CAPTION_REMOVED, TEXTO)
        }
        type(TransactionTags.FORM_CAPTION, "Bolo novo")
        await("texto novo some com a linha") {
            shows(TransactionTags.FORM_CAPTION, "Bolo novo") && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }
    }

    @Test
    fun `a linha "legenda tirada" não passa do Salvar e nunca aparece em lançamento novo`() {
        val t = comLegenda(TEXTO)
        composeRule.setContent { Content() }
        openEdit(t)
        tap(TransactionTags.FORM_CAPTION_CLEAR)
        await("linha do ✕") { shows(TransactionTags.FORM_CAPTION_REMOVED, TEXTO) }

        tap(TransactionTags.FORM_SUBMIT)
        await("de volta a Lançamentos") { exists(TransactionTags.NEW) && !exists(TransactionTags.FORM_TITLE) }
        settle()
        assertEquals("Salvar remove a legenda (§2.12: apagar o texto tira a legenda)", "", fora { db.transaction(t) }.row.caption)

        openEdit(t)
        await("edição reaberta sem a linha: o original está vazio") {
            exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION) && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }
        pressBack()

        openNew()
        await("lançamento novo sem a linha") {
            exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION) && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }
        tap(TransactionTags.FORM_CAPTION_OPEN)
        type(TransactionTags.FORM_CAPTION, "Bolo novo")
        await("campo com texto no lançamento novo") { shows(TransactionTags.FORM_CAPTION, "Bolo novo") }
        tap(TransactionTags.FORM_CAPTION_CLEAR)
        await("✕ no lançamento novo nunca mostra a linha (original vazio)") {
            exists(TransactionTags.FORM_CAPTION_OPEN) && !exists(TransactionTags.FORM_CAPTION) && !exists(TransactionTags.FORM_CAPTION_REMOVED)
        }
    }

    // ---- O contador acompanha o texto restaurado (a partir de 70) ----

    @Test
    fun `contador 72 por 80 acompanha o texto restaurado e acende aos 70`() {
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openNew()
        abrirLegenda()

        replaceText(TransactionTags.FORM_CAPTION, TEXTO69)
        await("69 sem contador", TransactionTags.FORM_CAPTION) {
            shows(TransactionTags.FORM_CAPTION, TEXTO69) && !exists(TransactionTags.FORM_CAPTION_COUNT)
        }
        replaceText(TransactionTags.FORM_CAPTION, TEXTO70)
        await("70 acende o contador", TransactionTags.FORM_CAPTION_COUNT) {
            shows(TransactionTags.FORM_CAPTION_COUNT, "70/80") && shows(TransactionTags.FORM_CAPTION, TEXTO70)
        }
        replaceText(TransactionTags.FORM_CAPTION, TEXTO72)
        await("72/80", TransactionTags.FORM_CAPTION_COUNT) {
            shows(TransactionTags.FORM_CAPTION_COUNT, "72/80") && shows(TransactionTags.FORM_CAPTION, TEXTO72)
        }

        restore.emulateSavedInstanceStateRestore()

        await("texto e contador restaurados", TransactionTags.FORM_CAPTION_COUNT) {
            shows(TransactionTags.FORM_CAPTION_COUNT, "72/80") && shows(TransactionTags.FORM_CAPTION, TEXTO72)
        }
    }

    // ---- Helpers: estado no Db ----

    private fun budget(name: String, limit: Long = 50_000): BudgetId =
        fora { db.createBudget(name, Cents(limit), DIA) }.also { assertNotEquals("createBudget $name", 0L, it.v) }

    /** Um lançamento manual já salvo com legenda; a edição abre com o campo à vista (TransactionTags.FORM_CAPTION). */
    private fun comLegenda(caption: String): TransactionId {
        val t = fora { db.addManual(form(budget("Lazer"), 4_600, caption = caption)) }
        assertNotEquals("addManual", 0L, t.v)
        return t
    }

    private fun form(
        budget: BudgetId, cents: Long, tags: List<String> = listOf("teste"), merchant: String = "Sacolao",
        caption: String = "", date: LocalDate = DIA, time: LocalTime = LocalTime(10, 0),
    ) = TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = 1, merchant = merchant, date = date, time = time, caption = caption)

    /** Uma escrita ou leitura "por fora": outra coroutine, fora da thread da UI, com timeout. */
    private fun <T> fora(block: suspend () -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { block() } }

    // ---- Helpers: navegação ----

    private fun openTransactions() {
        tab = Screen.Transactions
        await("Lançamentos aberta") { exists(TransactionTags.NEW) }
    }

    private fun openNew() {
        openTransactions()
        tap(TransactionTags.NEW)
        await("formulário de Novo lançamento") { exists(TransactionTags.FORM_NAME) && exists(TransactionTags.FORM_SUBMIT) }
    }

    /** Lançamentos › linha do lançamento › edição. */
    private fun openEdit(t: TransactionId) {
        openTransactions()
        await("linha do lançamento ${t.v}") { exists(TransactionTags.ROW + t.v) }
        tap(TransactionTags.ROW + t.v)
        await("edição aberta") { exists(TransactionTags.FORM_TITLE) && exists(TransactionTags.FORM_SUBMIT) }
    }

    private fun pressBack() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    // ---- Helpers: formulário ----

    /** "+ Legenda · só deste lançamento" abre o campo no lugar (TransactionTags.FORM_CAPTION_OPEN). */
    private fun abrirLegenda() {
        tap(TransactionTags.FORM_CAPTION_OPEN)
        await("campo de legenda aberto") { exists(TransactionTags.FORM_CAPTION) && !exists(TransactionTags.FORM_CAPTION_OPEN) }
    }

    // ---- Helpers: semântica ----

    private fun node(tag: String): SemanticsNode? = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()

    private fun exists(tag: String) = node(tag) != null

    /** Text + EditableText of the (merged) node with [tag]; empty when absent. */
    private fun texts(tag: String): List<String> {
        val n = node(tag) ?: return emptyList()
        return n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    /** Like assertTextContains(substring = true), also accepting a value split across pieces of text. */
    private fun shows(tag: String, s: String): Boolean {
        val t = texts(tag)
        return t.any { it.contains(s) } || t.joinToString("").contains(s)
    }

    private fun tap(tag: String) {
        val n = composeRule.onNodeWithTag(tag)
        runCatching { n.performScrollTo() }
        n.performClick()
    }

    private fun replaceText(tag: String, text: String) {
        val n = composeRule.onNodeWithTag(tag)
        runCatching { n.performScrollTo() }
        n.performTextReplacement(text)
    }

    /** Digita [text] no campo (acrescenta ao que já está lá, como um teclado). */
    private fun type(tag: String, text: String) {
        val n = composeRule.onNodeWithTag(tag)
        runCatching { n.performScrollTo() }
        n.performTextInput(text)
    }

    /** Waits for [cond]; on timeout, fails with [what] and the texts of [show]. */
    private fun await(what: String, vararg show: String, timeout: Long = UI_TIMEOUT_MS, cond: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeout) { cond() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "$what; na tela: " + show.joinToString {
                    "$it=${texts(it)}(n=${composeRule.onAllNodesWithTag(it).fetchSemanticsNodes().size})"
                },
                e,
            )
        }
    }

    /** Lets the IO queries come back and the UI settle; an exception in the composition comes out here. */
    private fun settle() = repeat(10) {
        Thread.sleep(20)
        composeRule.waitForIdle()
    }

    private companion object {
        /** Quarta, 14 de outubro de 2026. */
        val DIA = LocalDate(2026, 10, 14)
        val ZONA = TimeZone.of("America/Sao_Paulo")
        const val OP_TIMEOUT_MS = 10_000L
        const val UI_TIMEOUT_MS = 10_000L

        /** "Bolo do aniversário da Ana": 26 caracteres. */
        const val TEXTO = "Bolo do aniversário da Ana"

        /** O mesmo texto + um espaço (27) e x até o tamanho exato do contador (O4: String.length). */
        val TEXTO69 = "$TEXTO " + "x".repeat(42)
        val TEXTO70 = "$TEXTO " + "x".repeat(43)
        val TEXTO72 = "$TEXTO " + "x".repeat(45)
    }
}
