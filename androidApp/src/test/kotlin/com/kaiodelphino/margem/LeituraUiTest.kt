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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Leitura v2 (new-leitura-page), testes de fumaça pela UI: App sobre um Db real, Leitura aberta pela barra
 * de abas. API exploratória (manifest): poucos testes grossos, que pegam a tela quebrada de vez; a suíte
 * completa vem quando a API assentar.
 *
 * Contrato de tags (InsightsTags, D35) que estes testes usam:
 * - EMPTY: o vazio de hoje ("Nada para ler ainda.").
 * - SUMMARY: a linha de resumo do filtro (total, número de lançamentos, "até hoje").
 * - RANGE: abre a folha de intervalo; as opções são tocadas pelo texto ("mês passado", "3 meses"…).
 * - SEARCH: o campo único; SECTION + ChipKind.name: cada seção de sugestões; CHIPS: a fileira de chips.
 * - SEPARATE: o interruptor Separar.
 * - GRID: a grade que rola; CONTEXT + "quanto" | "para-onde" | "quando": títulos de contexto; PINNED: barra presa.
 * - LENS: a lupa da TopBar com o filtro recolhido (o selo é o texto do número de chips).
 * - WIDGET + id: cada widget na grade, tocável. Ids: margem, total, ritmo, previstos, historia, fora, fluxo,
 *   para-onde-foi, cresceu, voltam, primeira-vez, tamanho, retrato, calendario, dia-da-semana, semanas,
 *   mes-a-mes, meses-a-frente, volta-todo-mes, dia-e-hora.
 * - PAGE + id: a página do widget; LIST_ROW + i: linha i da lista da página (0 = primeira).
 * - ONLY, OR, AS_RANGE, MONTHS_OF: as ações sobre o pedaço tocado; DAY + data ISO: um dia tocável.
 * As opções da página são OptionPicker (Role.RadioButton) tocadas pelo rótulo.
 *
 * Hoje fixo: sexta, 6/11/2026; mês da verba de 1 a 30 (padrão). Dados de [seed]:
 * - outubro: parcela 3/3 de R$ 90 (R$ 30, 20/10), feira R$ 100 (10/10), lanche R$ 50 (20/10) = R$ 180,00;
 * - novembro: padaria R$ 15 (Delivery, 1/11), padaria + café R$ 30 (Mercado, 2/11), pizza R$ 40 (Delivery, 3/11),
 *   feira R$ 20 com legenda "padaria do lado" (4/11), recado da LOJA CENTRO R$ 25 (Mercado, 5/11 19h30)
 *   = R$ 130,00 em 5 lançamentos. Fora da conta: presente R$ 60 estornado e um recado riscado de R$ 99.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class LeituraUiTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    private var show by mutableStateOf(true)

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
            CompositionLocalProvider(LocalToday provides { NOV_6 }) { App(db, null) {} }
        }
    }

    @Test
    fun `AC-46, AC-F1 sem verba a Leitura mostra o vazio de hoje`() {
        // AC-F1: sem tratamento novo de falha de leitura (A-1); não há teste de injeção enquanto A-1 valer.
        composeRule.setContent { Content() }
        openInsights()
        await("vazio", InsightsTags.EMPTY) { shows(InsightsTags.EMPTY, "Nada para ler ainda.") }
    }

    @Test
    fun `AC-1, AC-2, AC-8, AC-9, AC-10, AC-11, AC-25, AC-26, AC-27, AC-28, AC-29, AC-30, AC-31, AC-32, AC-33, AC-34, AC-35, AC-36, AC-37, AC-38, AC-39, AC-40, AC-41, AC-42, AC-43, AC-44, AC-45 a grade abre no mês atual em três contextos e o intervalo muda a conta`() {
        seed()
        composeRule.setContent { Content() }
        openInsights()

        // AC-1: mês da verba atual, só o que conta (estorno e riscado fora, AC-25).
        await("resumo de novembro", InsightsTags.SUMMARY) {
            shows(InsightsTags.SUMMARY, "R$ 130,00") && shows(InsightsTags.SUMMARY, "5 lançamentos") && shows(InsightsTags.SUMMARY, "até hoje")
        }

        // AC-8: Quanto, Para onde, Quando, nessa ordem; os widgets da §5 na grade.
        for ((a, b) in CONTEXTS.zipWithNext()) {
            scrollTo(InsightsTags.CONTEXT + b)
            assertTrue("contexto $b na grade", exists(InsightsTags.CONTEXT + b))
            assertTrue("$a antes de $b", !exists(InsightsTags.CONTEXT + a) || positionOf(InsightsTags.CONTEXT + a).first < positionOf(InsightsTags.CONTEXT + b).first)
        }
        assertFalse("sem abas no topo", composeRule.onAllNodes(hasText("Ritmo") and tabRole).fetchSemanticsNodes().isNotEmpty())
        for (id in GRID_WIDGETS) {
            scrollTo(InsightsTags.WIDGET + id)
            assertTrue("widget $id na grade", exists(InsightsTags.WIDGET + id))
            assertTrue("widget $id tem frase", texts(InsightsTags.WIDGET + id).any { it.isNotBlank() })
        }
        assertFalse("Retrato só com um chip de estabelecimento (AC-38)", exists(InsightsTags.WIDGET + "retrato"))
        // AC-25: O que ficou fora aparece quando há estorno ou riscado no intervalo.
        scrollTo(InsightsTags.WIDGET + "fora")
        assertTrue("O que ficou fora mostra o estorno", shows(InsightsTags.WIDGET + "fora", "estorn"))
        // AC-26: Dia e hora conta só o recado e diz quantos ficaram de fora (4 manuais).
        scrollTo(InsightsTags.WIDGET + "dia-e-hora")
        assertTrue("Dia e hora diz o que ficou de fora", shows(InsightsTags.WIDGET + "dia-e-hora", "4"))
        // AC-43: Meses à frente sempre de hoje em diante.
        scrollTo(InsightsTags.WIDGET + "meses-a-frente")
        assertTrue("Meses à frente diz de hoje em diante", shows(InsightsTags.WIDGET + "meses-a-frente", "de hoje em diante"))

        // AC-9, AC-11: rolando até Quando, o filtro recolhe na lupa e a barra presa diz o contexto.
        scrollTo(InsightsTags.WIDGET + "semanas")
        await("filtro recolhido", InsightsTags.LENS, InsightsTags.PINNED) {
            exists(InsightsTags.LENS) && !exists(InsightsTags.SEARCH) && shows(InsightsTags.PINNED, "Quando")
        }
        // AC-10: a lupa reabre o filtro onde está.
        tap(InsightsTags.LENS)
        await("filtro reaberto", InsightsTags.SEARCH) { exists(InsightsTags.SEARCH) }

        // AC-2: mês passado conta outubro, com a parcela 3/3 pela data dela.
        scrollTo(InsightsTags.RANGE)
        tap(InsightsTags.RANGE)
        tapText("mês passado")
        await("resumo de outubro", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 180,00") }

        // AC-38: com um só chip de estabelecimento, o Retrato entra e saem Estabelecimentos que voltam, O que cresceu e Primeira vez.
        pick("sacolao", ChipKind.MERCHANT, "Sacolao")
        scrollTo(InsightsTags.WIDGET + "retrato")
        await("Retrato do estabelecimento", InsightsTags.WIDGET + "retrato") { exists(InsightsTags.WIDGET + "retrato") }
        for (gone in listOf("voltam", "cresceu", "primeira-vez")) {
            scrollTo(InsightsTags.WIDGET + gone)
            assertFalse("$gone sai com o Retrato", exists(InsightsTags.WIDGET + gone))
        }
    }

    @Test
    fun `AC-3, AC-4, AC-5, AC-6, AC-7 o campo único sugere por seção e os chips somam com ou`() {
        seed()
        composeRule.setContent { Content() }
        openInsights()
        await("resumo", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 130,00") }

        // AC-3: "pa" sugere nas seções fixas, com contagem e soma; AC-7: a legenda "padaria do lado" não conta.
        type("pa")
        await("sugestões de pa", sectionTag(ChipKind.TAG), sectionTag(ChipKind.MERCHANT)) {
            shows(sectionTag(ChipKind.TAG), "padaria") && shows(sectionTag(ChipKind.TAG), "2 lançamentos") &&
                shows(sectionTag(ChipKind.TAG), "45,00") && shows(sectionTag(ChipKind.MERCHANT), "Padaria Pão Quente")
        }
        val order = listOf(ChipKind.BUDGET, ChipKind.TAG, ChipKind.MERCHANT, ChipKind.NOTICE).filter { exists(sectionTag(it)) }.map { positionOf(sectionTag(it)).second }
        assertEquals("seções Verbas · Marcadores · Estabelecimentos · Recados", order.sorted(), order)
        // Sem diferença de maiúsculas e acentos.
        type("PAO")
        await("estabelecimento sem acento") { shows(sectionTag(ChipKind.MERCHANT), "Padaria Pão Quente") }

        // AC-4: Delivery ou padaria: R$ 15 (os dois, uma vez só) + R$ 30 + R$ 40 = R$ 85,00.
        pick("delivery", ChipKind.BUDGET, "Delivery")
        pick("padaria", ChipKind.TAG, "padaria")
        await("Delivery ou padaria", InsightsTags.CHIPS, InsightsTags.SUMMARY) {
            shows(InsightsTags.CHIPS, "Delivery") && shows(InsightsTags.CHIPS, "ou") && shows(InsightsTags.CHIPS, "padaria") &&
                shows(InsightsTags.SUMMARY, "R$ 85,00") && shows(InsightsTags.SUMMARY, "3 lançamentos")
        }

        // AC-5: o chip de recado pela fonte traz o lançamento do recado, não os manuais.
        pick("aurora", ChipKind.NOTICE, BANK_LABEL)
        await("mais o recado", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 110,00") && shows(InsightsTags.SUMMARY, "4 lançamentos") }

        // AC-6: Separar com ≥ 2 chips; o lançamento que bate em dois vai para "a + b".
        tap(InsightsTags.SEPARATE)
        scrollTo(InsightsTags.WIDGET + "calendario")
        await("série a + b") { composeRule.onAllNodes(hasText("Delivery + padaria", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `AC-12, AC-15, AC-16, AC-17, AC-18, AC-19, AC-20, AC-21 a página do widget mostra a regra C e muda a consulta da Leitura com Desfazer`() {
        seed()
        composeRule.setContent { Content() }
        openInsights()
        await("resumo", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 130,00") }

        // AC-12, AC-20: Para onde foi por marcador conta R$ 30 inteiro em padaria e em café.
        openPage("para-onde-foi")
        option("marcador")
        await("regra C", InsightsTags.PAGE + "para-onde-foi") {
            shows(InsightsTags.PAGE + "para-onde-foi", "padaria") && shows(InsightsTags.PAGE + "para-onde-foi", "45,00") &&
                shows(InsightsTags.PAGE + "para-onde-foi", "café") && shows(InsightsTags.PAGE + "para-onde-foi", "30,00")
        }
        // AC-19: Maiores, do maior para o menor, numerada.
        await("lista em Maiores", InsightsTags.LIST_ROW + 0) { shows(InsightsTags.LIST_ROW + 0, "Pizzaria") && shows(InsightsTags.LIST_ROW + 0, "1") }

        // AC-15: Só padaria troca os chips, volta à grade, e Desfazer restaura.
        tapIn("para-onde-foi", "padaria")
        tap(InsightsTags.ONLY)
        await("só padaria na grade", InsightsTags.CHIPS, InsightsTags.SUMMARY) {
            !exists(InsightsTags.PAGE + "para-onde-foi") && shows(InsightsTags.CHIPS, "padaria") && shows(InsightsTags.SUMMARY, "R$ 45,00")
        }
        await("Desfazer", SnackTags.ACTION) { shows(SnackTags.ACTION, "Desfazer") }
        tap(SnackTags.ACTION)
        await("sem chips de novo", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 130,00") && !shows(InsightsTags.CHIPS, "padaria") }

        // AC-16: + ou padaria soma aos chips que já estão.
        pick("pizza", ChipKind.TAG, "pizza")
        openPage("para-onde-foi")
        option("marcador")
        tapIn("para-onde-foi", "padaria")
        tap(InsightsTags.OR)
        await("pizza ou padaria", InsightsTags.CHIPS, InsightsTags.SUMMARY) {
            shows(InsightsTags.CHIPS, "pizza") && shows(InsightsTags.CHIPS, "padaria") && shows(InsightsTags.SUMMARY, "R$ 85,00")
        }

        // AC-21: no Fluxo, tocar em padaria acende "padaria + café".
        openPage("fluxo")
        tapIn("fluxo", "padaria")
        await("caminho combinado", InsightsTags.PAGE + "fluxo") { shows(InsightsTags.PAGE + "fluxo", "padaria + café") }
        back()

        // AC-17: um dia do Calendário vira o intervalo, com Desfazer.
        openPage("calendario")
        tap(InsightsTags.DAY + "2026-11-02")
        tap(InsightsTags.AS_RANGE)
        await("intervalo de um dia", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 30,00") && shows(InsightsTags.SUMMARY, "1 lançamento") }
        await("Desfazer do intervalo", SnackTags.ACTION) { shows(SnackTags.ACTION, "Desfazer") }

        // AC-18: O que cresceu → Mês a mês de feira abre o Mês a mês já filtrado.
        tap(SnackTags.ACTION)
        await("intervalo de volta", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 85,00") }
        openPage("cresceu")
        tapIn("cresceu", "pizza")
        tap(InsightsTags.MONTHS_OF)
        await("Mês a mês filtrado", InsightsTags.PAGE + "mes-a-mes") { exists(InsightsTags.PAGE + "mes-a-mes") && shows(InsightsTags.PAGE + "mes-a-mes", "pizza") }
    }

    @Test
    fun `AC-22, AC-23, AC-24 o Ritmo só mostra ritmo ideal e cota com mês inteiro e filtro de verba`() {
        seed()
        composeRule.setContent { Content() }
        openInsights()
        await("Ritmo com cota", InsightsTags.WIDGET + "ritmo") { shows(InsightsTags.WIDGET + "ritmo", "no passo atual") }

        // AC-22: chip de marcador tira o ritmo ideal; a opção fica apagada.
        pick("feira", ChipKind.TAG, "feira")
        await("Ritmo compara com o intervalo anterior", InsightsTags.WIDGET + "ritmo") { !shows(InsightsTags.WIDGET + "ritmo", "no passo atual") }
        openPage("ritmo")
        await("opção ritmo apagada") { radio("ritmo")?.let { !it.enabledNode() } == true }
        // AC-23: este mês no dia 6 compara com outubro até o dia 6 (nada) — R$ 20 de feira contra R$ 0.
        assertTrue("comparação do mesmo trecho", shows(InsightsTags.PAGE + "ritmo", "20,00"))
        back()

        // AC-24: com previstos é uma opção do Calendário; sem previsto em aberto o gasto não muda.
        openPage("calendario")
        tapText("Com previstos")
        await("gasto igual", InsightsTags.SUMMARY) { shows(InsightsTags.SUMMARY, "R$ 20,00") }
    }

    // ---- Cenário ----

    private fun seed() {
        ensureSource()
        val mercado = budget("Mercado", 100_000)
        val delivery = budget("Delivery", 40_000)
        tx(mercado, 9_000, LocalDate(2026, 8, 20), listOf("moveis"), "Loja Movel", installments = 3)
        tx(mercado, 10_000, LocalDate(2026, 10, 10), listOf("feira"), "Sacolao")
        tx(delivery, 5_000, LocalDate(2026, 10, 20), listOf("lanche"), "iFood")
        tx(delivery, 1_500, LocalDate(2026, 11, 1), listOf("padaria"), "Padaria Delivery")
        tx(mercado, 3_000, LocalDate(2026, 11, 2), listOf("padaria", "café"), "Padaria Pão Quente")
        tx(delivery, 4_000, LocalDate(2026, 11, 3), listOf("pizza"), "Pizzaria")
        tx(mercado, 2_000, LocalDate(2026, 11, 4), listOf("feira"), "Sacolao", caption = "padaria do lado")
        val c1 = compra("c1", 2_500, "LOJA CENTRO", 5, 19, 30)
        assertNotEquals("accept", 0L, fora { db.accept(c1, form(mercado, 2_500, LocalDate(2026, 11, 5), listOf("casa"), "LOJA CENTRO", time = LocalTime(19, 30))) }.v)
        val presente = tx(mercado, 6_000, LocalDate(2026, 11, 5), listOf("presente"), "Loja Presente")
        val e = capturar("e1", "Estorno de R$ 60,00 efetuado", at(5, 20, 0), title = "Estorno")
        assertTrue("estorno", fora { db.registerRefund(e, presente) })
        val riscado = compra("c2", 9_900, "LOJA RISCADA", 4, 10, 0)
        assertTrue("riscar", fora { db.dismiss(riscado) })
    }

    private fun ensureSource() {
        if (fora { db.sources() }.none { it.packageName == BANK }) assertTrue("addSource", fora { db.addSource(BANK, BANK_LABEL, at(1, 8, 0)) })
    }

    private fun budget(name: String, limit: Long): BudgetId =
        fora { db.createBudget(name, Cents(limit), LocalDate(2026, 8, 1)) }.also { assertNotEquals("createBudget $name", 0L, it.v) }

    private fun form(budget: BudgetId, cents: Long, date: LocalDate, tags: List<String>, merchant: String, installments: Int = 1, caption: String = "", time: LocalTime = LocalTime(10, 0)) =
        TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = installments, merchant = merchant, date = date, time = time, caption = caption)

    private fun tx(budget: BudgetId, cents: Long, date: LocalDate, tags: List<String>, merchant: String, installments: Int = 1, caption: String = ""): TransactionId =
        fora { db.addManual(form(budget, cents, date, tags, merchant, installments, caption)).transaction }.also { assertNotEquals("addManual $merchant", 0L, it.v) }

    /** Epoch ms de [day]/11/2026 [h]:[m] no fuso do sistema. */
    private fun at(day: Int, h: Int, m: Int): Long =
        LocalDateTime(2026, 11, day, h, m).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private fun capturar(key: String, text: String, postedAt: Long, title: String = "Compra aprovada"): NoticeId {
        val raw = RawNotice(packageName = BANK, appLabel = BANK_LABEL, key = key, postedAt = postedAt, whenMs = postedAt, title = title, text = text, bigText = text)
        assertTrue("capture de $key", fora { capture(db, raw) })
        return fora { db.pending() }.first { it.raw.key == key }.id
    }

    /** Compra no formato do ExtractTest, em novembro. */
    private fun compra(key: String, cents: Long, merchant: String, day: Int, h: Int, m: Int): NoticeId {
        val reais = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
        val text = "Compra de R$ $reais aprovada em $merchant em ${day.toString().padStart(2, '0')}/11 às ${h}h${m.toString().padStart(2, '0')}."
        return capturar(key, text, at(day, h, m))
    }

    private fun <T> fora(block: suspend () -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { block() } }

    // ---- Navegação e filtro ----

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun openInsights() {
        await("barra de abas") { composeRule.onAllNodes(hasText("Leitura") and tabRole).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(hasText("Leitura") and tabRole).onFirst().performClick()
        await("Leitura aberta") { exists(InsightsTags.EMPTY) || exists(InsightsTags.SUMMARY) }
    }

    private fun sectionTag(kind: ChipKind) = InsightsTags.SECTION + kind.name

    private fun type(text: String) {
        if (!exists(InsightsTags.SEARCH)) {
            if (exists(InsightsTags.LENS)) tap(InsightsTags.LENS) else scrollTo(InsightsTags.SEARCH)
        }
        await("campo de busca", InsightsTags.SEARCH) { exists(InsightsTags.SEARCH) }
        composeRule.onAllNodesWithTag(InsightsTags.SEARCH, useUnmergedTree = true).onFirst().performTextReplacement(text)
    }

    /** Digita [text] e toca a sugestão [label] da seção [kind]. */
    private fun pick(text: String, kind: ChipKind, label: String) {
        type(text)
        await("sugestão $label", sectionTag(kind)) { shows(sectionTag(kind), label) }
        composeRule.onAllNodes(hasText(label, substring = true) and hasAnyAncestor(hasTestTag(sectionTag(kind))), useUnmergedTree = true).onFirst().performClick()
        await("chip $label", InsightsTags.CHIPS) { shows(InsightsTags.CHIPS, label) }
    }

    private fun openPage(id: String) {
        scrollTo(InsightsTags.WIDGET + id)
        tap(InsightsTags.WIDGET + id)
        await("página $id", InsightsTags.PAGE + id) { exists(InsightsTags.PAGE + id) }
    }

    private fun back() {
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun radio(label: String): SemanticsNode? =
        composeRule.onAllNodes(hasText(label) and radioRole, useUnmergedTree = false).fetchSemanticsNodes().firstOrNull()

    private fun SemanticsNode.enabledNode() = !config.contains(SemanticsProperties.Disabled)

    private fun option(label: String) {
        await("opção $label") { radio(label) != null }
        composeRule.onAllNodes(hasText(label) and radioRole).onFirst().performClick()
        composeRule.waitForIdle()
    }

    private fun tapText(text: String) {
        await("texto $text") { composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val n = composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).onFirst()
        runCatching { n.performScrollTo() }
        n.performClick()
        composeRule.waitForIdle()
    }

    /** Toca o pedaço [text] (texto exato) na página [id], fora da fileira de chips do filtro. */
    private fun tapIn(id: String, text: String) {
        val m = hasText(text) and hasAnyAncestor(hasTestTag(InsightsTags.PAGE + id)) and !hasAnyAncestor(hasTestTag(InsightsTags.CHIPS))
        await("pedaço $text em $id", InsightsTags.PAGE + id) { composeRule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val n = composeRule.onAllNodes(m, useUnmergedTree = true).onFirst()
        runCatching { n.performScrollTo() }
        n.performClick()
        composeRule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        if (exists(InsightsTags.GRID)) runCatching { composeRule.onAllNodesWithTag(InsightsTags.GRID).onFirst().performScrollToNode(hasTestTag(tag)) }
        composeRule.waitForIdle()
    }

    // ---- Semântica ----

    private fun nodes(tag: String): List<SemanticsNode> = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    private fun exists(tag: String) = nodes(tag).isNotEmpty()

    private fun positionOf(tag: String): Pair<Float, Float> =
        nodes(tag).first().positionInRoot.let { it.y to it.x }

    private fun norm(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    private fun collect(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text) +
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
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

    private companion object {
        val NOV_6 = LocalDate(2026, 11, 6)
        val CONTEXTS = listOf("quanto", "para-onde", "quando")
        val GRID_WIDGETS = listOf(
            "margem", "total", "ritmo", "previstos", "historia",
            "fluxo", "para-onde-foi", "cresceu", "voltam", "primeira-vez", "tamanho",
            "calendario", "dia-da-semana", "semanas", "mes-a-mes", "meses-a-frente", "volta-todo-mes", "dia-e-hora",
        )
        const val BANK = "com.bank"
        const val BANK_LABEL = "Banco Aurora"
        const val OP_TIMEOUT_MS = 10_000L
        const val UI_TIMEOUT_MS = 10_000L
    }
}
