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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InboxTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.Screen
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
import com.kaiodelphino.margem.widgets.money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Escolha de verba e marcadores no formulário enxuto (spec §2.5, §7.3 "Verba" e "Marcadores"; D66), concorrência
 * e ciclo de vida. Testes cegos, escritos contra o contrato (TransactionTags e o KDoc da classe: o que sobrevive à
 * recriação e à morte do processo) antes da implementação:
 * - recriação (StateRestorationTester) com a folha "Em qual verba entra?" aberta e texto na busca dela, com texto
 *   na busca de marcadores, com verba "sugerida" e chips "sugerido" (continuam sugeridos), com marcadores criados
 *   e ainda não gravados (continuam chips, continuam fora do Db);
 * - escritas por fora com o formulário aberto (Db direto, outra coroutine): marcador criado, renomeado, fundido,
 *   apagado; verba criada, renomeada, apagada; lançamento gravado que muda usos e margens — resultados, atalhos e
 *   linhas da folha seguem o Db, chips escolhidos ficam, verba escolhida apagada deixa de estar escolhida;
 * - "Nova verba" ida e volta (Screen.NewBudget), com recriação no meio;
 * - Ligar a um previsto e Desligar, que volta "sugerida"/"sugerido";
 * - toque duplo num resultado, em Criar e em Nova verba; Lançar logo depois de escolher.
 *
 * Calendário (spec §3; Ajustes padrão: mês começa no dia 1): hoje fixo (LocalToday) é quarta, 14/10/2026. As
 * margens do campo e da folha são as do mês da data do formulário (TransactionTags, "Margins"): outubro.
 *
 * Recados entram por `capture` de uma fonte cadastrada, no formato genérico ("Compra de R$ 46,00 aprovada em BAR
 * PONTO CERTO em 13/10 às 21h03."); cada captura confere o que foi lido antes de o teste depender disso.
 *
 * Navegação só por tags e pelo `tabRequest` público do App (o mesmo caminho dos widgets, §7). Plain Application:
 * MargemApp abriria o Db de produção. Tela alta para o formulário inteiro caber sem rolagem preguiçosa.
 *
 * Robolectric não abre teclado: `WindowInsets.isImeVisible` fica falso, então o rodapé (Lançar) continua na
 * árvore mesmo com foco na busca de marcadores (TransactionTags.FORM_SUBMIT só some com o teclado aberto).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h1800dp")
class EscolhaLifecycleTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    /** Tira o App da composição antes de fechar o Db, para nenhuma consulta rodar num Db fechado. */
    private var show by mutableStateOf(true)

    /** App.tabRequest: abre uma aba como os widgets fazem. */
    private var tab by mutableStateOf<Screen?>(null)

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem-escolha.db").also { it.delete() }.path)
        assertTrue("addSource da fonte de teste", fora { db.addSource(BANK, BANK_LABEL, at(1, 8, 0)) })
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

    // ---- Recriação: folha da verba aberta com texto na busca (TransactionTags, KDoc da classe; BUDGET_SHEET) ----

    @Test
    fun `folha da verba aberta com texto na busca sobrevive à recriação e segue filtrando`() {
        val lazer = budget("Lazer")
        val casa = budget("Casa")
        val mercado = budget("Mercado")
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openNew()

        openSheet()
        replaceText(TransactionTags.BUDGET_SEARCH, "merc")
        // BUDGET_SHEET: uma linha por verba cujo nome contém o texto (fold).
        await("só Mercado com 'merc' na busca", TransactionTags.BUDGET_SEARCH) {
            rowIds(TransactionTags.BUDGET) == setOf(mercado.v)
        }

        restore.emulateSavedInstanceStateRestore()

        // KDoc da classe: "the open BUDGET_SHEET and the text of both searches" sobrevivem.
        await("folha aberta, busca 'merc' e só Mercado depois da recriação", TransactionTags.BUDGET_SEARCH) {
            exists(TransactionTags.BUDGET_SHEET) && editable(TransactionTags.BUDGET_SEARCH) == "merc" &&
                rowIds(TransactionTags.BUDGET) == setOf(mercado.v) && exists(TransactionTags.BUDGET_SHEET_NEW)
        }
        // A busca continua viva: trocar o texto refiltra.
        replaceText(TransactionTags.BUDGET_SEARCH, "a")
        await("'a' acha Lazer, Casa e Mercado") { rowIds(TransactionTags.BUDGET) == setOf(lazer.v, casa.v, mercado.v) }
        replaceText(TransactionTags.BUDGET_SEARCH, "CAS")
        await("'CAS' acha só Casa (sem diferença de maiúsculas)") { rowIds(TransactionTags.BUDGET) == setOf(casa.v) }

        tap(TransactionTags.BUDGET + casa.v)
        await("Casa escolhida, folha fechada", TransactionTags.FORM_BUDGET) {
            !exists(TransactionTags.BUDGET_SHEET) && shows(TransactionTags.FORM_BUDGET, "Casa") &&
                state(TransactionTags.FORM_BUDGET) != "sugerida"
        }

        // BUDGET_SEARCH: "empty each time the sheet opens" — reabrir não traz o texto de antes.
        openSheet()
        await("busca vazia ao reabrir, todas as verbas, Casa selecionada", TransactionTags.BUDGET_SEARCH) {
            editable(TransactionTags.BUDGET_SEARCH) == "" &&
                rowIds(TransactionTags.BUDGET) == setOf(lazer.v, casa.v, mercado.v) && selected(TransactionTags.BUDGET + casa.v)
        }
    }

    // ---- Recriação: texto na busca de marcadores (KDoc da classe; TAG_SEARCH, TAG_RESULT) ----

    @Test
    fun `busca de marcadores com texto sobrevive à recriação com os resultados`() {
        val lazer = budget("Lazer")
        manual(lazer, 1_000, listOf("cafe"), "Cafe Central", LocalDate(2026, 10, 2))
        manual(lazer, 1_000, listOf("cafe"), "Cafe Central", LocalDate(2026, 10, 3))
        manual(lazer, 1_000, listOf("cafeteria"), "Cafeteria Sol", LocalDate(2026, 10, 4))
        manual(lazer, 1_000, listOf("bar"), "Bar Um", LocalDate(2026, 10, 5))
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openNew()

        replaceText(TransactionTags.TAG_SEARCH, "caf")
        // TAG_RESULT: os que contêm o texto, começando com ele primeiro, depois usos desc: cafe (2), cafeteria (1).
        await("resultados cafe, cafeteria", TransactionTags.TAG_SEARCH) {
            names(TransactionTags.TAG_RESULT) == listOf("cafe", "cafeteria")
        }

        restore.emulateSavedInstanceStateRestore()

        await("busca 'caf' e os mesmos resultados depois da recriação", TransactionTags.TAG_SEARCH, TransactionTags.TAG_CREATE) {
            editable(TransactionTags.TAG_SEARCH) == "caf" && names(TransactionTags.TAG_RESULT) == listOf("cafe", "cafeteria") &&
                // TAG_CREATE: "caf" não é nome de marcador nem de escolhido.
                exists(TransactionTags.TAG_CREATE) && !exists(TransactionTags.TAG_NONE)
        }
        assertFalse("nenhum chip escolhido ainda", names(TransactionTags.TAG).isNotEmpty())

        tap(TransactionTags.TAG_RESULT + "cafe")
        // TAG_RESULT: "Click chooses it (not sugerido), empties the text".
        await("cafe escolhido e a busca vazia", TransactionTags.TAG_SEARCH) {
            confirmed("cafe") && editable(TransactionTags.TAG_SEARCH) == ""
        }
        restore.emulateSavedInstanceStateRestore()
        await("cafe continua escolhido e a busca vazia depois de outra recriação", TransactionTags.TAG_SEARCH) {
            confirmed("cafe") && editable(TransactionTags.TAG_SEARCH) == "" && !exists(TransactionTags.TAG_RESULT + "cafeteria")
        }
    }

    // ---- Recriação: verba sugerida e chips sugeridos (spec §2.5; KDoc da classe) ----

    @Test
    fun `verba sugerida e marcadores sugeridos continuam sugeridos depois da recriação com a folha aberta`() {
        val lazer = budget("Lazer")
        budget("Casa")
        manual(lazer, 6_000, listOf("bar", "amigos"), "BAR PONTO CERTO", LocalDate(2026, 10, 10))
        assertEquals("pré-condição: sugestão de BAR PONTO CERTO", Suggestion(lazer, listOf("amigos", "bar")), fora { db.suggestion("BAR PONTO CERTO") }.let { it.copy(tags = it.tags.sorted()) })
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)

        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openPost(n)
        await("Lazer sugerida, bar e amigos sugeridos", TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.FORM_BUDGET, "Lazer") && state(TransactionTags.FORM_BUDGET) == "sugerida" && suggested("bar") && suggested("amigos")
        }
        createTag("petisco")

        openSheet()
        replaceText(TransactionTags.BUDGET_SEARCH, "laz")
        // BUDGET: `selected` para a escolhida, também quando sugerida.
        await("só Lazer na folha, selecionada") { rowIds(TransactionTags.BUDGET) == setOf(lazer.v) && selected(TransactionTags.BUDGET + lazer.v) }

        restore.emulateSavedInstanceStateRestore()

        await("depois da recriação: folha aberta com 'laz', Lazer selecionada e ainda sugerida; chips como antes", TransactionTags.FORM_BUDGET, TransactionTags.BUDGET_SEARCH) {
            exists(TransactionTags.BUDGET_SHEET) && editable(TransactionTags.BUDGET_SEARCH) == "laz" &&
                rowIds(TransactionTags.BUDGET) == setOf(lazer.v) && selected(TransactionTags.BUDGET + lazer.v) &&
                state(TransactionTags.FORM_BUDGET) == "sugerida" &&
                suggested("bar") && suggested("amigos") && confirmed("petisco")
        }

        // §2.5: "A verba tracejada fica cheia quando a pessoa escolhe uma verba na folha, mesmo a sugerida."
        tap(TransactionTags.BUDGET + lazer.v)
        await("Lazer escolhida na folha deixa de ser sugerida", TransactionTags.FORM_BUDGET) {
            !exists(TransactionTags.BUDGET_SHEET) && shows(TransactionTags.FORM_BUDGET, "Lazer") && state(TransactionTags.FORM_BUDGET) != "sugerida"
        }
        restore.emulateSavedInstanceStateRestore()
        await("depois de outra recriação: Lazer cheia, chips sugeridos continuam sugeridos", TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.FORM_BUDGET, "Lazer") && state(TransactionTags.FORM_BUDGET) != "sugerida" &&
                suggested("bar") && suggested("amigos") && confirmed("petisco") && !exists(TransactionTags.BUDGET_SHEET)
        }

        submitAndWait()
        val row = fora { db.transactions() }.single { it.notice == n }
        assertEquals("verba", lazer, row.budget)
        // §2.5: "Lançar grava o que estiver escolhido, tracejado ou não."
        assertEquals("marcadores", listOf("amigos", "bar", "petisco"), row.tags.sorted())
    }

    @Test
    fun `verba sugerida sem tocar na folha continua sugerida depois da recriação e é a gravada`() {
        val lazer = budget("Lazer")
        budget("Casa")
        manual(lazer, 6_000, listOf("bar"), "BAR PONTO CERTO", LocalDate(2026, 10, 10))
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openPost(n)
        await("Lazer sugerida e bar sugerido", TransactionTags.FORM_BUDGET) {
            state(TransactionTags.FORM_BUDGET) == "sugerida" && suggested("bar")
        }
        restore.emulateSavedInstanceStateRestore()
        await("ainda sugeridos depois da recriação", TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.FORM_BUDGET, "Lazer") && state(TransactionTags.FORM_BUDGET) == "sugerida" && suggested("bar") &&
                !exists(TransactionTags.FORM_MISSING) && enabled(TransactionTags.FORM_SUBMIT)
        }
        // §2.5: sugeridos já contam.
        submitAndWait()
        val row = fora { db.transactions() }.single { it.notice == n }
        assertEquals("verba sugerida gravada", lazer, row.budget)
        assertEquals("marcador sugerido gravado", listOf("bar"), row.tags)
    }

    // ---- Recriação: marcador criado e ainda não gravado (TAG_CREATE: "novo, só é gravado ao lançar") ----

    @Test
    fun `marcador criado e não gravado sobrevive à recriação e só vai ao Db ao lançar`() {
        val lazer = budget("Lazer")
        manual(lazer, 1_000, listOf("bar"), "Bar Um", LocalDate(2026, 10, 5))
        val n = compra("n1", 4_600, "PADARIA SAO JOSE", 13, 9, 15)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openPost(n)
        chooseBudget(lazer)

        replaceText(TransactionTags.TAG_SEARCH, "  Festa Junina ")
        // TAG_CREATE: o texto limpo (trim + lowercase, como createTag).
        await("Criar “festa junina”", TransactionTags.TAG_CREATE) { shows(TransactionTags.TAG_CREATE, "festa junina") }
        tap(TransactionTags.TAG_CREATE)
        await("chip festa junina, busca vazia", TransactionTags.TAG_SEARCH) { confirmed("festa junina") && editable(TransactionTags.TAG_SEARCH) == "" }
        assertTrue("o marcador novo ainda não está no Db", fora { db.tags() }.none { it.name == "festa junina" })

        restore.emulateSavedInstanceStateRestore()

        await("chip festa junina continua escolhido depois da recriação", TransactionTags.TAG_SEARCH) {
            confirmed("festa junina") && countTag(TransactionTags.TAG + "festa junina") == 1 && confirmedOnly(listOf("festa junina"))
        }
        settle()
        assertTrue("continua fora do Db depois da recriação", fora { db.tags() }.none { it.name == "festa junina" })

        // TAG_CREATE: não aparece quando o texto é o nome de um escolhido; TAG_NONE só com TAG_CREATE.
        replaceText(TransactionTags.TAG_SEARCH, "FESTA JUNINA")
        settle()
        assertFalse("sem Criar para um nome já escolhido", exists(TransactionTags.TAG_CREATE))
        assertFalse("há resultado: sem 'Nenhum marcador'", exists(TransactionTags.TAG_NONE))
        // Spec §7.3 (2026-10-23): a chosen name is a result with its ✓, also one made here and not saved.
        assertTrue("o escolhido aparece como resultado", exists(TransactionTags.TAG_RESULT + "festa junina"))
        replaceText(TransactionTags.TAG_SEARCH, "")

        submitAndWait()
        assertTrue("gravado ao lançar", fora { db.tags() }.any { it.name == "festa junina" && it.uses == 1 })
        assertEquals("o lançamento leva o marcador novo", listOf("festa junina"), fora { db.transactions() }.single { it.notice == n }.tags)
    }

    // ---- Escritas por fora: marcadores (TAG_RESULT, TAG_SHORTCUT seguem Db.tags; os chips ficam) ----

    @Test
    fun `marcadores criados renomeados fundidos e apagados por fora - os resultados seguem o Db e os escolhidos ficam`() {
        val lazer = budget("Lazer")
        manual(lazer, 1_000, listOf("amigos"), "Bar Um", LocalDate(2026, 10, 1))
        manual(lazer, 1_000, listOf("bar"), "Bar Um", LocalDate(2026, 10, 2))
        manual(lazer, 1_000, listOf("chope"), "Bar Um", LocalDate(2026, 10, 3))
        manual(lazer, 1_000, listOf("chope"), "Bar Dois", LocalDate(2026, 10, 4))
        manual(lazer, 1_000, listOf("cerveja"), "Bar Tres", LocalDate(2026, 10, 5))
        manual(lazer, 1_000, listOf("cerveja", "chope"), "Bar Tres", LocalDate(2026, 10, 6))
        manual(lazer, 1_000, listOf("caipirinha", "amigos"), "Bar Tres", LocalDate(2026, 10, 7))
        composeRule.setContent { Content() }
        openNew()
        addTag("amigos")

        // Criado por fora: aparece nos resultados.
        replaceText(TransactionTags.TAG_SEARCH, "pi")
        await("'pi' acha caipirinha e oferece Criar", TransactionTags.TAG_SEARCH) {
            names(TransactionTags.TAG_RESULT) == listOf("caipirinha") && exists(TransactionTags.TAG_CREATE)
        }
        assertNotEquals("createTag por fora", 0L, fora { db.createTag("Pizza") }.v)
        // TAG_RESULT: começa com o texto primeiro: pizza antes de caipirinha.
        await("pizza criada por fora aparece primeiro", TransactionTags.TAG_SEARCH) {
            names(TransactionTags.TAG_RESULT) == listOf("pizza", "caipirinha")
        }

        // Renomeado por fora: o nome velho some, o novo aparece com os usos dele.
        replaceText(TransactionTags.TAG_SEARCH, "bo")
        await("'bo' não acha nada ainda", TransactionTags.TAG_SEARCH) { !exists(TransactionTags.TAG_RESULT + "bar") && exists(TransactionTags.TAG_NONE) }
        val bar = tagId("bar")
        assertTrue("renameTag por fora", fora { db.renameTag(bar, "Boteco") })
        await("boteco aparece com 1 lançamento", TransactionTags.TAG_RESULT + "boteco") {
            names(TransactionTags.TAG_RESULT) == listOf("boteco") && shows(TransactionTags.TAG_RESULT + "boteco", "1 lançamento") &&
                !exists(TransactionTags.TAG_NONE)
        }
        replaceText(TransactionTags.TAG_SEARCH, "bar")
        await("bar não existe mais: nenhum resultado", TransactionTags.TAG_SEARCH) {
            names(TransactionTags.TAG_RESULT).isEmpty() && exists(TransactionTags.TAG_NONE)
        }

        // Fundido por fora: cerveja some, chope ganha os lançamentos dela (sem par repetido: 3 + 2 − 1 = 4).
        replaceText(TransactionTags.TAG_SEARCH, "c")
        // Começam com "c": chope (3), cerveja (2), caipirinha (1); depois os que só contêm: boteco.
        await("'c' acha chope, cerveja, caipirinha, boteco", TransactionTags.TAG_SEARCH) {
            names(TransactionTags.TAG_RESULT) == listOf("chope", "cerveja", "caipirinha", "boteco") &&
                shows(TransactionTags.TAG_RESULT + "chope", "3 lançamentos")
        }
        assertTrue("mergeTags por fora", fora { db.mergeTags(tagId("cerveja"), tagId("chope")) })
        await("cerveja some e chope tem 4 lançamentos", TransactionTags.TAG_RESULT + "chope") {
            !exists(TransactionTags.TAG_RESULT + "cerveja") && shows(TransactionTags.TAG_RESULT + "chope", "4 lançamentos") &&
                // começam com "c": chope (4) antes de caipirinha (1); depois boteco, que só contém.
                names(TransactionTags.TAG_RESULT) == listOf("chope", "caipirinha", "boteco")
        }

        // Apagado por fora: some dos resultados.
        fora { db.deleteTag(tagId("caipirinha")) }
        await("caipirinha some", TransactionTags.TAG_SEARCH) { names(TransactionTags.TAG_RESULT) == listOf("chope", "boteco") }

        // O escolhido ficou o tempo todo.
        assertTrue("amigos continua escolhido", confirmed("amigos"))
        assertEquals("só amigos escolhido", listOf("amigos"), names(TransactionTags.TAG))
    }

    @Test
    fun `marcador escolhido apagado por fora continua chip e volta ao Db ao lançar`() {
        val lazer = budget("Lazer")
        manual(lazer, 1_000, listOf("padaria", "cafe"), "Padaria Um", LocalDate(2026, 10, 1))
        composeRule.setContent { Content() }
        openNew()
        replaceText(TransactionTags.FORM_NAME, "Padaria Dois")
        replaceText(TransactionTags.FORM_TOTAL, "1250")
        chooseBudget(lazer)
        addTag("padaria")

        fora { db.deleteTag(tagId("padaria")) }
        settle()
        // TAG: só um toque no chip o tira; a lista de chips é de nomes, e um nome novo só é gravado ao lançar
        // (TransactionTags, "Marcadores"). Leitura escolhida: apagar o marcador por fora não tira o chip.
        await("padaria continua escolhido depois de apagado por fora", TransactionTags.FORM_MISSING) {
            confirmed("padaria") && !exists(TransactionTags.FORM_MISSING) && enabled(TransactionTags.FORM_SUBMIT)
        }
        submitAndWait(expected = 2)
        val row = fora { db.transactions() }.single { it.merchant == "Padaria Dois" }
        assertEquals("lançado com o marcador (recriado)", listOf("padaria"), row.tags)
        assertTrue("padaria existe de novo no Db", fora { db.tags() }.any { it.name == "padaria" && it.uses == 1 })
    }

    @Test
    fun `lançamentos gravados por fora mudam a ordem e as contagens dos resultados e dos atalhos`() {
        val lazer = budget("Lazer")
        manual(lazer, 1_000, listOf("feira"), "Feira Um", LocalDate(2026, 10, 1))
        manual(lazer, 1_000, listOf("feira"), "Feira Dois", LocalDate(2026, 10, 2))
        manual(lazer, 1_000, listOf("mercado"), "Mercado Um", LocalDate(2026, 10, 3))
        composeRule.setContent { Content() }
        openNew()

        replaceText(TransactionTags.TAG_SEARCH, "e")
        // Nenhum começa com "e": usos desc — feira (2), mercado (1).
        await("feira antes de mercado", TransactionTags.TAG_SEARCH) {
            names(TransactionTags.TAG_RESULT) == listOf("feira", "mercado") &&
                shows(TransactionTags.TAG_RESULT + "feira", "2 lançamentos") && shows(TransactionTags.TAG_RESULT + "mercado", "1 lançamento")
        }
        manual(lazer, 1_000, listOf("mercado"), "Mercado Dois", LocalDate(2026, 10, 4))
        manual(lazer, 1_000, listOf("mercado"), "Mercado Tres", LocalDate(2026, 10, 5))
        await("mercado (3) passa feira (2)", TransactionTags.TAG_SEARCH) {
            names(TransactionTags.TAG_RESULT) == listOf("mercado", "feira") && shows(TransactionTags.TAG_RESULT + "mercado", "3 lançamentos")
        }

        // TAG_SHORTCUT: com foco e sem texto, os mais usados (usos desc).
        replaceText(TransactionTags.TAG_SEARCH, "")
        runCatching { composeRule.onNodeWithTag(TransactionTags.TAG_SEARCH).performClick() }
        await("atalhos mercado, feira", TransactionTags.TAG_SEARCH) { names(TransactionTags.TAG_SHORTCUT) == listOf("mercado", "feira") }
        repeat(3) { manual(lazer, 1_000, listOf("feira"), "Feira ${it + 3}", LocalDate(2026, 10, 6 + it)) }
        await("feira (5) volta para a frente nos atalhos", TransactionTags.TAG_SEARCH) { names(TransactionTags.TAG_SHORTCUT) == listOf("feira", "mercado") }
        assertNotEquals("createTag por fora", 0L, fora { db.createTag("acougue") }.v)
        // Sem usos, depois dos outros (usos desc, depois nome).
        await("acougue entra nos atalhos por último", TransactionTags.TAG_SEARCH) {
            names(TransactionTags.TAG_SHORTCUT) == listOf("feira", "mercado", "acougue")
        }
        tap(TransactionTags.TAG_SHORTCUT + "mercado")
        // TAG_SHORTCUT: "among the ones not chosen"; escolhe sem "sugerido".
        await("mercado escolhido sai dos atalhos", TransactionTags.TAG_SEARCH) {
            confirmed("mercado") && !exists(TransactionTags.TAG_SHORTCUT + "mercado")
        }
        manual(lazer, 1_000, listOf("mercado"), "Mercado Quatro", LocalDate(2026, 10, 10))
        settle()
        assertFalse("um escolhido não volta aos atalhos com novos usos", exists(TransactionTags.TAG_SHORTCUT + "mercado"))
        assertTrue("e continua escolhido", confirmed("mercado"))
    }

    // ---- Escritas por fora: verbas (BUDGET_SHEET, FORM_BUDGET) ----

    @Test
    fun `verbas criadas renomeadas e apagadas por fora com a folha aberta - as linhas seguem o Db`() {
        val lazer = budget("Lazer")
        val casa = budget("Casa")
        composeRule.setContent { Content() }
        openNew()
        openSheet()
        await("Lazer e Casa na folha") { rowIds(TransactionTags.BUDGET) == setOf(lazer.v, casa.v) }

        val viagem = budget("Viagem", 30_000)
        await("Viagem criada por fora aparece na folha", TransactionTags.BUDGET + viagem.v) {
            rowIds(TransactionTags.BUDGET) == setOf(lazer.v, casa.v, viagem.v) && shows(TransactionTags.BUDGET + viagem.v, "Viagem") &&
                shows(TransactionTags.BUDGET + viagem.v, money(Cents(30_000)))
        }
        // Ordem manual (a da aba Verbas): a nova vai para o fim.
        assertEquals("ordem manual", listOf(lazer.v, casa.v, viagem.v), orderedIds(TransactionTags.BUDGET))

        assertTrue("renameBudget por fora", fora { db.renameBudget(casa, "Moradia") })
        await("Casa vira Moradia na folha", TransactionTags.BUDGET + casa.v) { shows(TransactionTags.BUDGET + casa.v, "Moradia") }

        fora { db.deleteBudget(lazer) }
        await("Lazer apagada some da folha") { rowIds(TransactionTags.BUDGET) == setOf(casa.v, viagem.v) }
        assertTrue("a folha continua aberta", exists(TransactionTags.BUDGET_SHEET) && exists(TransactionTags.BUDGET_SHEET_NEW))

        // Com texto na busca, uma verba renomeada por fora passa a casar.
        replaceText(TransactionTags.BUDGET_SEARCH, "fér")
        await("'fér' não acha nada") { rowIds(TransactionTags.BUDGET).isEmpty() && exists(TransactionTags.BUDGET_SHEET_NEW) }
        assertTrue("renameBudget por fora", fora { db.renameBudget(viagem, "Férias") })
        await("Férias casa com 'fér'") { rowIds(TransactionTags.BUDGET) == setOf(viagem.v) }
        tap(TransactionTags.BUDGET + viagem.v)
        await("Férias escolhida", TransactionTags.FORM_BUDGET) { shows(TransactionTags.FORM_BUDGET, "Férias") && !exists(TransactionTags.BUDGET_SHEET) }
    }

    @Test
    fun `verba escolhida renomeada por fora segue no campo e apagada por fora deixa de ser escolhida`() {
        val lazer = budget("Lazer")
        val casa = budget("Casa", 80_000)
        composeRule.setContent { Content() }
        openNew()
        replaceText(TransactionTags.FORM_NAME, "Cinema")
        replaceText(TransactionTags.FORM_TOTAL, "4600")
        chooseBudget(lazer)
        createTag("filme") // triage: "filme" is not in the Db, so the search offers Criar, not a result
        await("nada falta", TransactionTags.FORM_MISSING) { !exists(TransactionTags.FORM_MISSING) && enabled(TransactionTags.FORM_SUBMIT) }

        assertTrue("renameBudget por fora", fora { db.renameBudget(lazer, "Cultura") })
        await("o campo mostra Cultura", TransactionTags.FORM_BUDGET) { shows(TransactionTags.FORM_BUDGET, "Cultura") }

        fora { db.deleteBudget(lazer) }
        // FORM_BUDGET: "A budget deleted while the form is open stops being chosen"; vazio, "Escolher a verba".
        await("verba apagada deixa de ser escolhida e Lançar desabilita", TransactionTags.FORM_BUDGET, TransactionTags.FORM_MISSING) {
            shows(TransactionTags.FORM_BUDGET, "Escolher a verba") && !shows(TransactionTags.FORM_BUDGET, "Cultura") &&
                shows(TransactionTags.FORM_MISSING, "Falta a verba.") && !enabled(TransactionTags.FORM_SUBMIT)
        }
        assertTrue("o marcador continua escolhido", confirmed("filme"))
        assertFalse("sem prévia sem verba", exists(TransactionTags.FORM_PREVIEW))

        openSheet()
        await("a folha só tem Casa, nenhuma selecionada") { rowIds(TransactionTags.BUDGET) == setOf(casa.v) && !selected(TransactionTags.BUDGET + casa.v) }
        tap(TransactionTags.BUDGET + casa.v)
        await("Casa escolhida, nada falta", TransactionTags.FORM_MISSING) { shows(TransactionTags.FORM_BUDGET, "Casa") && !exists(TransactionTags.FORM_MISSING) }
        submitAndWait(expected = 1)
        val row = fora { db.transactions() }.single()
        assertEquals("lançado em Casa", casa, row.budget)
        assertEquals(listOf("filme"), row.tags)
    }

    @Test
    fun `verba sugerida renomeada por fora continua sugerida e apagada por fora deixa de ser escolhida e sugerida`() {
        val lazer = budget("Lazer")
        budget("Casa")
        manual(lazer, 6_000, listOf("bar", "amigos"), "BAR PONTO CERTO", LocalDate(2026, 10, 10))
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        composeRule.setContent { Content() }
        openPost(n)
        await("Lazer sugerida", TransactionTags.FORM_BUDGET) { state(TransactionTags.FORM_BUDGET) == "sugerida" && suggested("bar") }

        assertTrue("renameBudget por fora", fora { db.renameBudget(lazer, "Diversão") })
        await("renomeada, ainda sugerida", TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.FORM_BUDGET, "Diversão") && state(TransactionTags.FORM_BUDGET) == "sugerida"
        }

        fora { db.deleteBudget(lazer) }
        await("apagada: sem verba, sem 'sugerida', falta a verba", TransactionTags.FORM_BUDGET, TransactionTags.FORM_MISSING) {
            shows(TransactionTags.FORM_BUDGET, "Escolher a verba") && state(TransactionTags.FORM_BUDGET) != "sugerida" &&
                shows(TransactionTags.FORM_MISSING, "Falta a verba.") && !enabled(TransactionTags.FORM_SUBMIT)
        }
        // Nada no contrato tira os chips com a verba: continuam escolhidos e sugeridos.
        assertTrue("bar e amigos continuam sugeridos", suggested("bar") && suggested("amigos"))
    }

    @Test
    fun `margem da verba no campo e na folha segue lançamentos gravados por fora`() {
        val lazer = budget("Lazer", 50_000)
        composeRule.setContent { Content() }
        openNew()
        chooseBudget(lazer)
        // Margins: "margem " + marginText de BudgetMonth.remaining no mês da data do formulário (outubro).
        await("margem R$ 500,00 no campo", TransactionTags.FORM_BUDGET) { shows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(50_000))}") }

        manual(lazer, 12_000, listOf("bar"), "Bar Um", DIA)
        await("margem R$ 380,00 no campo depois do lançamento de fora", TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(38_000))}")
        }
        openSheet()
        await("a linha da folha mostra R$ 380,00", TransactionTags.BUDGET + lazer.v) { shows(TransactionTags.BUDGET + lazer.v, money(Cents(38_000))) }

        manual(lazer, 50_000, listOf("bar"), "Bar Dois", DIA)
        // Margem negativa: "R$ 120,00 além" (TransactionTags: money(−c) + " além").
        await("folha e campo dizem R$ 120,00 além", TransactionTags.BUDGET + lazer.v, TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.BUDGET + lazer.v, "${money(Cents(12_000))} além") &&
                shows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(12_000))} além")
        }
    }

    // ---- Nova verba, ida e volta (BUDGET_SHEET_NEW; D66) ----

    @Test
    fun `Nova verba criada pela folha volta escolhida mesmo com recriação no meio e o resto do formulário fica`() {
        val lazer = budget("Lazer")
        budget("Casa")
        manual(lazer, 6_000, listOf("bar", "amigos"), "BAR PONTO CERTO", LocalDate(2026, 10, 10))
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openPost(n)
        await("sugestão aplicada", TransactionTags.FORM_BUDGET) { state(TransactionTags.FORM_BUDGET) == "sugerida" && suggested("bar") && suggested("amigos") }
        replaceText(TransactionTags.FORM_TOTAL, "5290")
        createTag("petisco")

        openSheet()
        tap(TransactionTags.BUDGET_SHEET_NEW)
        awaitNewBudget()

        restore.emulateSavedInstanceStateRestore()
        awaitNewBudget()

        replaceText(BudgetTags.FORM_NAME, "Viagem")
        replaceText(BudgetTags.FORM_LIMIT, "30000")
        tap(BudgetTags.FORM_SUBMIT)
        await("de volta ao formulário com Viagem escolhida, não sugerida", TransactionTags.FORM_BUDGET) {
            exists(TransactionTags.FORM_TITLE) && !exists(BudgetTags.FORM_NAME) &&
                shows(TransactionTags.FORM_BUDGET, "Viagem") && state(TransactionTags.FORM_BUDGET) != "sugerida"
        }
        val viagem = budgetId("Viagem")
        // "Coming back ... changes nothing" além da verba: o resto do formulário fica.
        await("valor e marcadores como estavam", TransactionTags.FORM_SUBMIT) {
            shows(TransactionTags.FORM_SUBMIT, money(Cents(5_290))) && suggested("bar") && suggested("amigos") && confirmed("petisco") &&
                shows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(30_000))}")
        }

        restore.emulateSavedInstanceStateRestore()
        await("Viagem continua escolhida depois de outra recriação", TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.FORM_BUDGET, "Viagem") && state(TransactionTags.FORM_BUDGET) != "sugerida" && confirmed("petisco")
        }

        submitAndWait()
        val row = fora { db.transactions() }.single { it.notice == n }
        assertEquals("lançado na verba criada", viagem, row.budget)
        assertEquals("valor", Cents(5_290), row.total)
        assertEquals("marcadores", listOf("amigos", "bar", "petisco"), row.tags.sorted())
    }

    @Test
    fun `Nova verba a partir do Novo lançamento sem verba volta escolhida e Lançar habilita`() {
        budget("Lazer")
        composeRule.setContent { Content() }
        openNew()
        replaceText(TransactionTags.FORM_NAME, "Hotel")
        replaceText(TransactionTags.FORM_TOTAL, "20000")
        createTag("hotel")
        await("falta só a verba", TransactionTags.FORM_MISSING) { shows(TransactionTags.FORM_MISSING, "Falta a verba.") }

        openSheet()
        tap(TransactionTags.BUDGET_SHEET_NEW)
        awaitNewBudget()
        replaceText(BudgetTags.FORM_NAME, "Viagem")
        replaceText(BudgetTags.FORM_LIMIT, "100000")
        tap(BudgetTags.FORM_SUBMIT)
        await("Viagem escolhida, nada falta", TransactionTags.FORM_BUDGET, TransactionTags.FORM_MISSING) {
            shows(TransactionTags.FORM_BUDGET, "Viagem") && !exists(TransactionTags.FORM_MISSING) && enabled(TransactionTags.FORM_SUBMIT)
        }
        submitAndWait(expected = 1)
        assertEquals("lançado em Viagem", budgetId("Viagem"), fora { db.transactions() }.single().budget)
    }

    @Test
    fun `voltar da Nova verba sem criar não muda nada`() {
        val lazer = budget("Lazer")
        budget("Casa")
        manual(lazer, 6_000, listOf("bar"), "BAR PONTO CERTO", LocalDate(2026, 10, 10))
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openPost(n)
        await("Lazer sugerida", TransactionTags.FORM_BUDGET) { state(TransactionTags.FORM_BUDGET) == "sugerida" && suggested("bar") }
        createTag("petisco")

        openSheet()
        tap(TransactionTags.BUDGET_SHEET_NEW)
        awaitNewBudget()
        restore.emulateSavedInstanceStateRestore()
        awaitNewBudget()
        pressBack()

        await("de volta ao formulário, Lazer ainda sugerida", TransactionTags.FORM_BUDGET) {
            exists(TransactionTags.FORM_TITLE) && shows(TransactionTags.FORM_BUDGET, "Lazer") && state(TransactionTags.FORM_BUDGET) == "sugerida" &&
                suggested("bar") && confirmed("petisco") && !exists(TransactionTags.BUDGET_SHEET)
        }
        assertEquals("nenhuma verba nova", 2, fora { db.budgetsOverview(db.monthOf(DIA), DIA, BudgetOrder.MANUAL) }.size)
    }

    @Test
    fun `toque duplo em Nova verba abre uma tela só`() {
        budget("Lazer")
        composeRule.setContent { Content() }
        openNew()
        openSheet()
        doubleTap(TransactionTags.BUDGET_SHEET_NEW)
        awaitNewBudget()
        replaceText(BudgetTags.FORM_NAME, "Viagem")
        replaceText(BudgetTags.FORM_LIMIT, "30000")
        tap(BudgetTags.FORM_SUBMIT)
        // Uma volta só leva ao formulário: não havia uma segunda Nova verba empilhada.
        await("de volta ao formulário com Viagem, sem outra Nova verba", TransactionTags.FORM_BUDGET) {
            exists(TransactionTags.FORM_TITLE) && !exists(BudgetTags.FORM_NAME) && shows(TransactionTags.FORM_BUDGET, "Viagem")
        }
        settle()
        assertFalse("nenhuma segunda Nova verba", exists(BudgetTags.FORM_NAME))
    }

    // ---- Ligar a um previsto e Desligar (FORM_LINKED: "Desligar puts the form back as it was before linking") ----

    @Test
    fun `ligar um previsto e Desligar devolve a verba sugerida e os marcadores sugeridos`() {
        val (n, luz, lazer, casa) = recadoComSugestaoEPrevisto()
        composeRule.setContent { Content() }
        openPost(n)
        await("Lazer sugerida, energia sugerido", TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.FORM_BUDGET, "Lazer") && state(TransactionTags.FORM_BUDGET) == "sugerida" && suggested("energia")
        }
        createTag("extra")

        link(luz)
        // FORM_LINKED: verba e marcadores do previsto escolhidos e confirmados (não sugeridos).
        await("ligado: Casa cheia, contas e luz cheios", TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.FORM_BUDGET, "Casa") && state(TransactionTags.FORM_BUDGET) != "sugerida" && confirmed("contas") && confirmed("luz")
        }
        assertNotEquals(lazer, casa)

        tap(TransactionTags.FORM_UNLINK)
        await("desligado: Lazer sugerida, energia sugerido, extra cheio, sem contas e luz", TransactionTags.FORM_BUDGET) {
            !exists(TransactionTags.FORM_LINKED) && exists(TransactionTags.FORM_LINK_OPEN) &&
                shows(TransactionTags.FORM_BUDGET, "Lazer") && state(TransactionTags.FORM_BUDGET) == "sugerida" &&
                suggested("energia") && confirmed("extra") && !exists(TransactionTags.TAG + "contas") && !exists(TransactionTags.TAG + "luz")
        }

        submitAndWait()
        val row = fora { db.transactions() }.single { it.notice == n }
        assertEquals("lançado na verba sugerida", lazer, row.budget)
        assertEquals("marcadores de antes de ligar", listOf("energia", "extra"), row.tags.sorted())
        assertTrue("a ocorrência continua aberta (não ligado)", fora { db.planned(DIA) }.any { it.occurrence == luz })
    }

    @Test
    fun `recriação com o formulário ligado mantém a ligação e Desligar ainda devolve as sugestões`() {
        val (n, luz, _, _) = recadoComSugestaoEPrevisto()
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openPost(n)
        await("Lazer sugerida", TransactionTags.FORM_BUDGET) { state(TransactionTags.FORM_BUDGET) == "sugerida" && suggested("energia") }
        link(luz)

        restore.emulateSavedInstanceStateRestore()

        await("ainda ligado depois da recriação: Casa, contas, luz", TransactionTags.FORM_LINKED, TransactionTags.FORM_BUDGET) {
            exists(TransactionTags.FORM_LINKED) && shows(TransactionTags.FORM_LINKED, "Conta de luz") &&
                shows(TransactionTags.FORM_BUDGET, "Casa") && state(TransactionTags.FORM_BUDGET) != "sugerida" && confirmed("contas") && confirmed("luz")
        }
        tap(TransactionTags.FORM_UNLINK)
        await("desligado depois da recriação: Lazer sugerida e energia sugerido de novo", TransactionTags.FORM_BUDGET) {
            !exists(TransactionTags.FORM_LINKED) && shows(TransactionTags.FORM_BUDGET, "Lazer") && state(TransactionTags.FORM_BUDGET) == "sugerida" &&
                suggested("energia") && !exists(TransactionTags.TAG + "contas") && !exists(TransactionTags.TAG + "luz")
        }
    }

    @Test
    fun `escolher na folha depois de desligar deixa a verba cheia e a escolha sobrevive à recriação`() {
        val (n, luz, _, casa) = recadoComSugestaoEPrevisto()
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openPost(n)
        await("Lazer sugerida", TransactionTags.FORM_BUDGET) { state(TransactionTags.FORM_BUDGET) == "sugerida" }
        link(luz)
        tap(TransactionTags.FORM_UNLINK)
        await("desligado, Lazer sugerida", TransactionTags.FORM_BUDGET) { !exists(TransactionTags.FORM_LINKED) && state(TransactionTags.FORM_BUDGET) == "sugerida" }
        chooseBudget(casa)
        restore.emulateSavedInstanceStateRestore()
        await("Casa cheia depois da recriação, não ligado", TransactionTags.FORM_BUDGET) {
            shows(TransactionTags.FORM_BUDGET, "Casa") && state(TransactionTags.FORM_BUDGET) != "sugerida" &&
                !exists(TransactionTags.FORM_LINKED) && suggested("energia")
        }
    }

    // ---- Toque duplo (TAG_SEARCH: "A name already chosen is never added twice") ----

    @Test
    fun `toque duplo num resultado ou em Criar põe o marcador uma vez só`() {
        val lazer = budget("Lazer")
        manual(lazer, 1_000, listOf("bar"), "Bar Um", LocalDate(2026, 10, 1))
        manual(lazer, 1_000, listOf("barzinho"), "Bar Um", LocalDate(2026, 10, 2))
        composeRule.setContent { Content() }
        openNew()
        replaceText(TransactionTags.FORM_NAME, "Bar Dois")
        replaceText(TransactionTags.FORM_TOTAL, "3000")
        chooseBudget(lazer)

        replaceText(TransactionTags.TAG_SEARCH, "bar")
        await("resultado bar") { exists(TransactionTags.TAG_RESULT + "bar") }
        doubleTap(TransactionTags.TAG_RESULT + "bar")
        await("bar escolhido", TransactionTags.TAG_SEARCH) { confirmed("bar") }
        settle()
        assertEquals("um chip bar", 1, countTag(TransactionTags.TAG + "bar"))
        assertFalse("barzinho não foi escolhido pelo segundo toque", exists(TransactionTags.TAG + "barzinho"))
        assertEquals("só bar escolhido", listOf("bar"), names(TransactionTags.TAG))

        replaceText(TransactionTags.TAG_SEARCH, "boteco")
        await("Criar boteco") { exists(TransactionTags.TAG_CREATE) }
        doubleTap(TransactionTags.TAG_CREATE)
        await("boteco escolhido", TransactionTags.TAG_SEARCH) { confirmed("boteco") }
        settle()
        assertEquals("um chip boteco", 1, countTag(TransactionTags.TAG + "boteco"))
        assertEquals("bar e boteco, na ordem em que foram escolhidos", listOf("bar", "boteco"), names(TransactionTags.TAG))

        // "Pronto" com o nome de um escolhido (outra caixa, espaços nas pontas): não repete.
        replaceText(TransactionTags.TAG_SEARCH, " BAR ")
        composeRule.onNodeWithTag(TransactionTags.TAG_SEARCH).performImeAction()
        await("Pronto esvazia a busca", TransactionTags.TAG_SEARCH) { editable(TransactionTags.TAG_SEARCH) == "" }
        // Pronto em branco não faz nada.
        composeRule.onNodeWithTag(TransactionTags.TAG_SEARCH).performImeAction()
        settle()
        assertEquals("bar continua uma vez só", listOf("bar", "boteco"), names(TransactionTags.TAG))

        submitAndWait(expected = 3)
        val row = fora { db.transactions() }.single { it.merchant == "Bar Dois" }
        assertEquals(listOf("bar", "boteco"), row.tags.sorted())
        assertEquals("boteco gravado uma vez, com 1 uso", 1, fora { db.tags() }.single { it.name == "boteco" }.uses)
    }

    @Test
    fun `toque duplo numa linha da folha escolhe a verba e fecha a folha`() {
        val lazer = budget("Lazer")
        val casa = budget("Casa")
        composeRule.setContent { Content() }
        openNew()
        openSheet()
        doubleTap(TransactionTags.BUDGET + casa.v)
        await("Casa escolhida e folha fechada", TransactionTags.FORM_BUDGET) {
            !exists(TransactionTags.BUDGET_SHEET) && shows(TransactionTags.FORM_BUDGET, "Casa")
        }
        settle()
        assertFalse("a folha não reabriu", exists(TransactionTags.BUDGET_SHEET))
        assertTrue("ainda no formulário", exists(TransactionTags.FORM_TITLE))
        openSheet()
        await("só Casa selecionada") { selected(TransactionTags.BUDGET + casa.v) && !selected(TransactionTags.BUDGET + lazer.v) }
    }

    // ---- Lançar logo depois de escolher ----

    @Test
    fun `Lançar logo depois de escolher na folha e na busca grava a verba e os marcadores escolhidos`() {
        val lazer = budget("Lazer")
        val casa = budget("Casa")
        manual(lazer, 6_000, listOf("bar"), "BAR PONTO CERTO", LocalDate(2026, 10, 10))
        manual(casa, 1_000, listOf("amigos"), "Outro", LocalDate(2026, 10, 9))
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        composeRule.setContent { Content() }
        openPost(n)
        await("Lazer sugerida", TransactionTags.FORM_BUDGET) { state(TransactionTags.FORM_BUDGET) == "sugerida" && suggested("bar") }

        replaceText(TransactionTags.TAG_SEARCH, "ami")
        await("resultado amigos") { exists(TransactionTags.TAG_RESULT + "amigos") }
        tap(TransactionTags.TAG_RESULT + "amigos")
        tap(TransactionTags.FORM_BUDGET)
        await("folha aberta") { exists(TransactionTags.BUDGET + casa.v) }
        tap(TransactionTags.BUDGET + casa.v)
        // Sem esperar consulta nenhuma (margem, prévia): Lançar já.
        await("Lançar na árvore", TransactionTags.FORM_SUBMIT) { exists(TransactionTags.FORM_SUBMIT) && enabled(TransactionTags.FORM_SUBMIT) }
        tap(TransactionTags.FORM_SUBMIT)

        await("lançado", SnackTags.TEXT) { fora { db.transactions() }.any { it.notice == n } }
        val row = fora { db.transactions() }.single { it.notice == n }
        assertEquals("a verba escolhida na folha, não a sugerida", casa, row.budget)
        assertEquals("o sugerido conta e o escolhido entra", listOf("amigos", "bar"), row.tags.sorted())
    }

    @Test
    fun `Lançar logo depois de Pronto grava o marcador criado`() {
        val lazer = budget("Lazer")
        composeRule.setContent { Content() }
        openNew()
        replaceText(TransactionTags.FORM_NAME, "Quermesse")
        replaceText(TransactionTags.FORM_TOTAL, "2500")
        tap(TransactionTags.FORM_BUDGET)
        await("folha aberta") { exists(TransactionTags.BUDGET + lazer.v) }
        tap(TransactionTags.BUDGET + lazer.v)
        replaceText(TransactionTags.TAG_SEARCH, "Festa Junina")
        // TAG_SEARCH: "Pronto" cria quando não há marcador com o nome limpo.
        composeRule.onNodeWithTag(TransactionTags.TAG_SEARCH).performImeAction()
        await("Lançar na árvore", TransactionTags.FORM_SUBMIT) { exists(TransactionTags.FORM_SUBMIT) && enabled(TransactionTags.FORM_SUBMIT) }
        tap(TransactionTags.FORM_SUBMIT)

        await("lançado") { fora { db.transactions() }.size == 1 }
        val row = fora { db.transactions() }.single()
        assertEquals(lazer, row.budget)
        assertEquals("o marcador do Pronto, limpo", listOf("festa junina"), row.tags)
        assertEquals("gravado uma vez", 1, fora { db.tags() }.count { it.name == "festa junina" })
    }

    // ---- Helpers: estado no Db ----

    private data class LinkSetup(val notice: NoticeId, val occurrence: OccurrenceId, val lazer: BudgetId, val casa: BudgetId)

    /**
     * Lazer e Casa; um lançamento de ENEL SP em Lazer com "energia" (a sugestão do recado, §2.5); o previsto
     * "Conta de luz" em Casa com contas e luz, Todo mês, dia 20 (sem ligação: o recado não é reconhecido, só
     * ligado à mão); e o recado de R$ 187,40 de ENEL SP.
     */
    private fun recadoComSugestaoEPrevisto(): LinkSetup {
        val lazer = budget("Lazer")
        val casa = budget("Casa", 150_000)
        manual(lazer, 9_000, listOf("energia"), "ENEL SP", LocalDate(2026, 10, 5))
        val saved = fora {
            db.addManual(
                TransactionForm(
                    budget = casa, tags = listOf("contas", "luz"), total = Cents(18_000), installments = 1, merchant = "Conta de luz",
                    date = LocalDate(2026, 10, 20), time = LocalTime(9, 0), repeats = Repeats.MONTHLY,
                ),
                DIA,
            )
        }
        assertNotEquals("previsto salvo", 0L, saved.planned.v)
        assertEquals("só o previsto", 0L, saved.transaction.v)
        val occ = fora { db.planned(DIA) }.single { it.name == "Conta de luz" }
        assertFalse("pré-condição: previsto sem ligação", occ.linked)
        val n = compra("n1", 18_740, "ENEL SP", 13, 18, 20)
        assertEquals("pré-condição: recado não reconhecido", OccurrenceId(0), fora { db.pending(DIA) }.single { it.id == n }.planned.occurrence)
        return LinkSetup(n, occ.occurrence, lazer, casa)
    }

    private fun budget(name: String, limit: Long = 50_000): BudgetId =
        fora { db.createBudget(name, Cents(limit), DIA) }.also { assertNotEquals("createBudget $name", 0L, it.v) }

    private fun budgetId(name: String): BudgetId {
        val b = fora { db.budgetsOverview(db.monthOf(DIA), DIA, BudgetOrder.MANUAL) }.firstOrNull { it.name == name }
        assertNotNull("verba $name no Db", b)
        return b!!.id
    }

    private fun tagId(name: String): TagId {
        val t = fora { db.tags() }.firstOrNull { it.name == name }
        assertNotNull("marcador $name no Db", t)
        return t!!.id
    }

    /** Lançamento manual por fora (Db direto, outra coroutine). */
    private fun manual(budget: BudgetId, cents: Long, tags: List<String>, merchant: String, date: LocalDate): TransactionId {
        val form = TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = 1, merchant = merchant, date = date, time = LocalTime(10, 0))
        return fora { db.addManual(form, DIA) }.transaction.also { assertNotEquals("addManual $merchant", 0L, it.v) }
    }

    /** Epoch ms de [day]/10/2026 [h]:[m] no fuso do sistema (o mesmo em que o app lê recados). */
    private fun at(day: Int, h: Int, m: Int): Long =
        LocalDateTime(2026, 10, day, h, m).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private fun capturar(key: String, text: String, postedAt: Long): NoticeId {
        val raw = RawNotice(
            packageName = BANK, appLabel = BANK_LABEL, key = key, postedAt = postedAt, whenMs = postedAt,
            title = "Compra aprovada", text = text, bigText = text,
        )
        assertTrue("capture de $key", fora { capture(db, raw) })
        val row = fora { db.pending() }.firstOrNull { it.raw.key == key }
        assertNotNull("recado $key pendente depois da captura", row)
        return row!!.id
    }

    /** Compra no formato genérico; confere o valor e o estabelecimento lidos. */
    private fun compra(key: String, cents: Long, merchant: String, day: Int, h: Int, m: Int): NoticeId {
        val reais = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
        val text = "Compra de R$ $reais aprovada em $merchant em ${day.toString().padStart(2, '0')}/10 às ${h}h${m.toString().padStart(2, '0')}."
        val id = capturar(key, text, at(day, h, m))
        val parsed = fora { db.notice(id) }.parsed
        assertEquals("pré-condição: leitura de '$text'", Cents(cents) to merchant, parsed.cents to parsed.merchant)
        assertFalse("pré-condição: compra não é estorno", parsed.isRefund)
        return id
    }

    /** Uma escrita ou leitura "por fora": outra coroutine, fora da thread da UI, com timeout. */
    private fun <T> fora(block: suspend () -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { block() } }

    // ---- Helpers: navegação ----

    private fun openPauta() {
        await("App aberto") { exists(BudgetTags.HEADER) || exists(InboxTags.LIST) || exists(InboxTags.EMPTY) }
        if (!exists(InboxTags.LIST) && !exists(InboxTags.EMPTY)) tab = Screen.Inbox
        await("Pauta aberta") { exists(InboxTags.LIST) || exists(InboxTags.EMPTY) }
    }

    private fun openPost(n: NoticeId) {
        openPauta()
        await("Lançar do recado ${n.v}") { exists(InboxTags.ACCEPT + n.v) }
        tap(InboxTags.ACCEPT + n.v)
        await("formulário de Lançar do recado ${n.v}") { exists(TransactionTags.FORM_SUBMIT) && exists(TransactionTags.FORM_TOTAL) }
    }

    /** Lançamentos › Novo lançamento. */
    private fun openNew() {
        await("App aberto em Verbas") { exists(BudgetTags.HEADER) }
        tab = Screen.Transactions
        await("Lançamentos aberta") { exists(TransactionTags.NEW) }
        tap(TransactionTags.NEW)
        await("Novo lançamento aberto", TransactionTags.FORM_TITLE) {
            shows(TransactionTags.FORM_TITLE, "Novo lançamento") && exists(TransactionTags.FORM_TOTAL)
        }
    }

    private fun awaitNewBudget() = await("tela Nova verba", TransactionTags.FORM_TITLE) {
        exists(BudgetTags.FORM_NAME) && exists(BudgetTags.FORM_LIMIT) && exists(BudgetTags.FORM_SUBMIT) && !exists(TransactionTags.FORM_TITLE)
    }

    private fun pressBack() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    // ---- Helpers: formulário ----

    private fun openSheet() {
        await("campo da verba") { exists(TransactionTags.FORM_BUDGET) }
        tap(TransactionTags.FORM_BUDGET)
        await("folha da verba aberta") { exists(TransactionTags.BUDGET_SHEET) && exists(TransactionTags.BUDGET_SEARCH) && exists(TransactionTags.BUDGET_SHEET_NEW) }
    }

    private fun chooseBudget(b: BudgetId) {
        openSheet()
        await("linha da verba ${b.v}") { exists(TransactionTags.BUDGET + b.v) }
        tap(TransactionTags.BUDGET + b.v)
        await("verba ${b.v} escolhida, folha fechada", TransactionTags.FORM_BUDGET) {
            !exists(TransactionTags.BUDGET_SHEET) && state(TransactionTags.FORM_BUDGET) != "sugerida" && !shows(TransactionTags.FORM_BUDGET, "Escolher a verba")
        }
    }

    /** Toca o resultado [name] (deve existir no Db). */
    private fun addTag(name: String) {
        replaceText(TransactionTags.TAG_SEARCH, name)
        await("resultado $name") { exists(TransactionTags.TAG_RESULT + name) }
        tap(TransactionTags.TAG_RESULT + name)
        await("marcador $name escolhido") { confirmed(name) }
    }

    /** Toca "Criar" para [name] (não deve existir no Db). */
    private fun createTag(name: String) {
        replaceText(TransactionTags.TAG_SEARCH, name)
        await("Criar $name") { exists(TransactionTags.TAG_CREATE) }
        tap(TransactionTags.TAG_CREATE)
        await("marcador $name escolhido") { confirmed(name) }
    }

    private fun link(occ: OccurrenceId) {
        await("É um previsto? Ligar") { exists(TransactionTags.FORM_LINK_OPEN) }
        tap(TransactionTags.FORM_LINK_OPEN)
        await("candidato ${occ.v}") { exists(TransactionTags.LINK_CANDIDATE + occ.v) }
        tap(TransactionTags.LINK_CANDIDATE + occ.v)
        await("Ligar habilitado") { enabled(TransactionTags.LINK_SUBMIT) }
        tap(TransactionTags.LINK_SUBMIT)
        await("formulário ligado", TransactionTags.FORM_LINKED) { exists(TransactionTags.FORM_LINKED) && !exists(TransactionTags.LINK_SHEET) }
    }

    /** Toca Lançar e espera o lançamento no Db ([expected] lançamentos ao todo, ou um a mais que antes). */
    private fun submitAndWait(expected: Int = -1) {
        val before = fora { db.transactions() }.size
        val want = if (expected >= 0) expected else before + 1
        await("Lançar habilitado", TransactionTags.FORM_SUBMIT, TransactionTags.FORM_MISSING) { enabled(TransactionTags.FORM_SUBMIT) }
        tap(TransactionTags.FORM_SUBMIT)
        await("lançamento gravado ($want ao todo)", SnackTags.TEXT) { fora { db.transactions() }.size == want }
    }

    private fun suggested(name: String) = selected(TransactionTags.TAG + name) && state(TransactionTags.TAG + name) == "sugerido"

    private fun confirmed(name: String) = selected(TransactionTags.TAG + name) && state(TransactionTags.TAG + name) != "sugerido"

    private fun confirmedOnly(list: List<String>) = names(TransactionTags.TAG) == list && list.all { confirmed(it) }

    // ---- Helpers: semântica ----

    private fun node(tag: String): SemanticsNode? = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()

    private fun countTag(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun exists(tag: String) = node(tag) != null

    private fun selected(tag: String) = node(tag)?.config?.getOrNull(SemanticsProperties.Selected) == true

    private fun enabled(tag: String) = node(tag)?.let { !it.config.contains(SemanticsProperties.Disabled) } == true

    private fun state(tag: String): String? = node(tag)?.config?.getOrNull(SemanticsProperties.StateDescription)

    /** The input's own text (not the hint); null when absent. */
    private fun editable(tag: String): String? = node(tag)?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    /** Text + EditableText of the (merged) node with [tag]; empty when absent. */
    private fun texts(tag: String): List<String> {
        val n = node(tag) ?: return emptyList()
        return n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    private fun shows(tag: String, s: String): Boolean {
        val t = texts(tag)
        return t.any { it.contains(s) } || t.joinToString("").contains(s)
    }

    private fun prefixed(prefix: String) = composeRule
        .onAllNodes(SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true })
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix(prefix) }

    /** Suffixes of the tags with [prefix], in tree order (the order on screen), each once. */
    private fun names(prefix: String): List<String> = prefixed(prefix).distinct()

    private fun rowIds(prefix: String): Set<Long> = prefixed(prefix).mapNotNull { it.toLongOrNull() }.toSet()

    private fun orderedIds(prefix: String): List<Long> = prefixed(prefix).mapNotNull { it.toLongOrNull() }.distinct()

    private fun tap(tag: String) {
        val n = composeRule.onNodeWithTag(tag)
        runCatching { n.performScrollTo() }
        n.performClick()
    }

    /** Dois toques seguidos no mesmo ponto, antes de qualquer recomposição. */
    private fun doubleTap(tag: String) {
        val n = composeRule.onNodeWithTag(tag)
        runCatching { n.performScrollTo() }
        n.performTouchInput {
            click()
            advanceEventTime(40)
            click()
        }
    }

    private fun replaceText(tag: String, text: String) {
        val n = composeRule.onNodeWithTag(tag)
        runCatching { n.performScrollTo() }
        n.performTextReplacement(text)
    }

    /** Waits for [cond]; on timeout, fails with [what] and the texts of [show]. */
    private fun await(what: String, vararg show: String, timeout: Long = UI_TIMEOUT_MS, cond: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeout) { cond() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what; na tela: " + show.joinToString { "$it=${texts(it)}" }, e)
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
        const val BANK = "com.bank"
        const val BANK_LABEL = "Banco Aurora"
        const val OP_TIMEOUT_MS = 10_000L
        const val UI_TIMEOUT_MS = 10_000L
    }
}
