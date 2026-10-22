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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.widgets.MargemTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
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
 * Leitura (bloco 6), concorrência e ciclo de vida, pela UI (InsightsTags é o contrato, D35).
 *
 * - A tela segue o Db escrito por fora enquanto está aberta (outra coroutine escrevendo direto no Db: D6, D19).
 * - O mês do topo e a verba escolhida ficam enquanto a tela está aberta, inclusive na recriação
 *   (rememberSaveable, architecture §7); sair da aba e voltar abre de novo no mês atual com o padrão.
 * - Vazio ↔ cheio: EMPTY, RANK_EMPTY, CAL_EMPTY (spec §7.5).
 *
 * Calendário (spec §3, §4; padrão: mês começa no dia 1, semana na segunda): hoje fixo (LocalToday) é
 * sexta, 9/10/2026. Outubro de 2026 tem 31 dias e começa numa quinta; semanas de outubro:
 * 1–4 (4 dias), 5–11, 12–18, 19–25 (7), 26–31 (6). Segundas: 5, 12, 19, 26; sábados: 3, 10, 17, 24, 31.
 *
 * Cota R$ 310,00 em outubro (spec §4: passo = cota × dias da semana ÷ dias do mês): 310 × 4/31 = 40,00;
 * 310 × 7/31 = 70,00; 310 × 6/31 = 60,00 (tudo exato, sem centavos a arredondar). Ritmo ideal de 9/10
 * (spec §5): semana fechada 1–4 = 40,00 + 70,00 × 5/7 (5 a 9 = 5 dias corridos com hoje) = 50,00 → R$ 90,00.
 * Projeção (§5) = gasto × (310 ÷ 90).
 *
 * Os textos vêm do KDoc de InsightsTags. Espaços: o app usa U+202F depois de "R$" (widgets/Theme.kt);
 * as comparações trocam U+202F e U+00A0 por espaço. O "1 a 31 · até sex, 9" do MONTH_LABEL é "uppercase"
 * no KDoc: a comparação dessa parte ignora caixa.
 *
 * Plain Application: MargemApp abriria o Db de produção com o driver bundled (D28). Tela alta para a
 * página caber sem rolagem preguiçosa.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp")
class LeituraLifecycleTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    /** Tira o App da composição antes de fechar o Db. */
    private var show by mutableStateOf(true)

    /** Hoje, lido pelo LocalToday a cada chamada. */
    @Volatile private var today: LocalDate = DIA

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
            CompositionLocalProvider(LocalToday provides { today }) {
                App(db, null) {}
            }
        }
    }

    // =====================================================================================
    // Vazio ↔ cheio (EMPTY)
    // =====================================================================================

    @Test
    fun `sem verba mostra Nada para ler ainda e a verba criada e apagada por fora troca a página`() {
        composeRule.setContent { Content() }
        openInsights()
        await("vazio", InsightsTags.EMPTY) { textIs(InsightsTags.EMPTY, "Nada para ler ainda.") }
        // KDoc EMPTY: no lugar de tudo o mais.
        assertFalse("sem seletor de mês no vazio", exists(InsightsTags.MONTH_LABEL))
        assertFalse("sem Ritmo no vazio", exists(InsightsTags.PACE_BUDGET))
        assertFalse("sem ranking no vazio", exists(InsightsTags.RANK_TAGS))
        assertFalse("sem calendário no vazio", exists(InsightsTags.CAL_EMPTY))

        val casa = budget("Casa", 31_000)
        await("a verba criada por fora abre a página", InsightsTags.EMPTY, InsightsTags.PACE_BUDGET) {
            !exists(InsightsTags.EMPTY) && shows(InsightsTags.PACE_BUDGET, "Casa")
        }
        await("mês atual", InsightsTags.MONTH_LABEL) { shows(InsightsTags.MONTH_LABEL, "outubro de 2026") }
        // spec §7.5: com verba mas nada lançado, ranking e calendário dizem "Nada lançado em outubro.".
        await("ranking vazio", InsightsTags.RANK_EMPTY) { textIs(InsightsTags.RANK_EMPTY, "Nada lançado em outubro.") }
        await("calendário vazio", InsightsTags.CAL_EMPTY) { textIs(InsightsTags.CAL_EMPTY, "Nada lançado em outubro.") }
        // Gasto 0 < ideal 90,00: "R$ 90,00 abaixo do ritmo ideal."; sem gasto, sem projeção (decidido na triagem do bloco 6).
        await("frase do Ritmo em zero", InsightsTags.PACE_SENTENCE) {
            textIs(InsightsTags.PACE_SENTENCE, "R$ 90,00 abaixo do ritmo ideal.")
        }
        // Verbas por mês: 0 > 0 nunca.
        await("histórico", InsightsTags.HISTORY_SENTENCE) { textIs(InsightsTags.HISTORY_SENTENCE, "Nenhum mês fora da margem.") }

        fora { db.deleteBudget(casa) }
        await("a última verba apagada volta ao vazio", InsightsTags.EMPTY) {
            textIs(InsightsTags.EMPTY, "Nada para ler ainda.") && !exists(InsightsTags.PACE_BUDGET) && !exists(InsightsTags.RANK_TAGS)
        }
    }

    // =====================================================================================
    // Ritmo do mês e Verbas por mês seguem o Db
    // =====================================================================================

    @Test
    fun `Ritmo do mês segue lançamento novo estorno e lançamento apagado por fora`() {
        ensureSource()
        val mercado = budget("Mercado", 31_000)
        composeRule.setContent { Content() }
        openInsights()
        await("Mercado no Ritmo", InsightsTags.PACE_BUDGET) { shows(InsightsTags.PACE_BUDGET, "Mercado") }

        // 45,00 < 90,00: "R$ 45,00 abaixo"; projeção 45 × 310/90 = 155.
        tx(mercado, 4_500, LocalDate(2026, 10, 5), listOf("feira"))
        awaitPace("45,00", "R$ 45,00 abaixo do ritmo ideal.", "No passo atual, o mês fecharia em R$ 155.")
        await("descrição do gráfico com 45,00", InsightsTags.PACE_CHART) {
            cdIs(
                InsightsTags.PACE_CHART,
                "Ritmo do mês de Mercado: R$ 45,00 até o dia 9, contra ritmo ideal de R$ 90,00; " +
                    "no passo atual, R$ 155 no fim do mês, para uma cota de R$ 310,00",
            )
        }

        // 45 + 135 = 180 > 90 e ≤ 310: "+ R$ 90,00 sobre"; 180 × 310/90 = 620.
        val t2 = tx(mercado, 13_500, LocalDate(2026, 10, 8), listOf("feira"))
        awaitPace("180,00", "+ R$ 90,00 sobre o ritmo ideal.", "No passo atual, o mês fecharia em R$ 620.")

        // 180 + 180 = 360 > 310: "R$ 50,00 além da cota."; 360 × 310/90 = 1.240.
        val t3 = tx(mercado, 18_000, LocalDate(2026, 10, 9), listOf("feira"))
        awaitPace("360,00", "R$ 50,00 além da cota.", "No passo atual, o mês fecharia em R$ 1.240.")
        // Verbas por mês: out 360 > 310 → um mês; mai–set antes do primeiro mês da verba (outubro): "sem cota".
        await("histórico com outubro fora", InsightsTags.HISTORY_SENTENCE, InsightsTags.HISTORY_CHART) {
            textIs(InsightsTags.HISTORY_SENTENCE, "Um mês fora da margem.") &&
                cdIs(
                    InsightsTags.HISTORY_CHART,
                    "Verbas por mês de Mercado: mai sem cota; jun sem cota; jul sem cota; ago sem cota; set sem cota; out 360,00 de 310,00",
                )
        }

        // Estorno de t3 (§6: não conta): de volta a 180.
        val e = estorno("e1", 18_000, at(9, 12, 0))
        assertTrue("registerRefund", fora { db.registerRefund(e, t3) })
        awaitPace("180,00 depois do estorno", "+ R$ 90,00 sobre o ritmo ideal.", "No passo atual, o mês fecharia em R$ 620.")
        await("histórico sem mês fora depois do estorno", InsightsTags.HISTORY_SENTENCE) {
            textIs(InsightsTags.HISTORY_SENTENCE, "Nenhum mês fora da margem.")
        }

        // Apagar t2: 45.
        fora { db.deleteTransaction(t2) }
        awaitPace("45,00 depois de apagar", "R$ 45,00 abaixo do ritmo ideal.", "No passo atual, o mês fecharia em R$ 155.")

        // + 45 = 90 == ideal: "No ritmo ideal."; 90 × 310/90 = 310.
        tx(mercado, 4_500, LocalDate(2026, 10, 9), listOf("feira"))
        awaitPace("90,00", "No ritmo ideal.", "No passo atual, o mês fecharia em R$ 310.")
    }

    @Test
    fun `Ritmo segue a cota mudada por fora`() {
        val mercado = budget("Mercado", 31_000)
        tx(mercado, 18_000, LocalDate(2026, 10, 6), listOf("feira"))
        composeRule.setContent { Content() }
        openInsights()
        awaitPace("cota 310", "+ R$ 90,00 sobre o ritmo ideal.", "No passo atual, o mês fecharia em R$ 620.")

        // Cota 620: passos 80 (4/31), 140 (7/31); ideal = 80 + 140 × 5/7 = 180 == gasto; projeção 180 × 620/180 = 620.
        assertTrue(fora { db.setLimit(mercado, Cents(62_000), DIA) })
        awaitPace("cota 620", "No ritmo ideal.", "No passo atual, o mês fecharia em R$ 620.")
        await("descrição com a cota nova", InsightsTags.PACE_CHART) {
            cdIs(
                InsightsTags.PACE_CHART,
                "Ritmo do mês de Mercado: R$ 180,00 até o dia 9, contra ritmo ideal de R$ 180,00; " +
                    "no passo atual, R$ 620 no fim do mês, para uma cota de R$ 620,00",
            )
        }

        // Cota 155: passos 20, 35; ideal = 20 + 35 × 5/7 = 45; 180 > 155 → 25 além; projeção 180 × 155/45 = 620.
        assertTrue(fora { db.setLimit(mercado, Cents(15_500), DIA) })
        awaitPace("cota 155", "R$ 25,00 além da cota.", "No passo atual, o mês fecharia em R$ 620.")
        await("histórico com a cota nova", InsightsTags.HISTORY_SENTENCE) { textIs(InsightsTags.HISTORY_SENTENCE, "Um mês fora da margem.") }
    }

    @Test
    fun `escritas concorrentes enquanto a tela está aberta chegam todas`() {
        val mercado = budget("Mercado", 31_000)
        composeRule.setContent { Content() }
        openInsights()
        await("Ritmo em zero", InsightsTags.PACE_SENTENCE) { shows(InsightsTags.PACE_SENTENCE, "R$ 90,00 abaixo do ritmo ideal.") }

        // 18 lançamentos de 10,00 em 5/10, escritos ao mesmo tempo: 180,00.
        val ids = runBlocking(Dispatchers.Default) {
            withTimeout(STORM_TIMEOUT_MS) {
                (1..18).map { i ->
                    async { db.addManual(form(mercado, 1_000, LocalDate(2026, 10, 5), listOf("feira"), "Sacolao $i")).transaction }
                }.awaitAll()
            }
        }
        assertEquals("18 lançamentos gravados", 18, ids.map { it.v }.filter { it != 0L }.toSet().size)

        // 180 > 90: "+ R$ 90,00 sobre"; 180 × 310/90 = 620.
        awaitPace("depois da tempestade", "+ R$ 90,00 sobre o ritmo ideal.", "No passo atual, o mês fecharia em R$ 620.")
        // Ranking: feira, 18 lançamentos, 180,00.
        await("ranking com os 18", InsightsTags.RANK_ROW + "0") {
            shows(InsightsTags.RANK_ROW + "0", "feira") && shows(InsightsTags.RANK_ROW + "0", "18 lançamentos") &&
                shows(InsightsTags.RANK_ROW + "0", "180,00")
        }
        // Calendário: 5/10 = 180,00.
        await("calendário com os 18", InsightsTags.CAL_DAY + "2026-10-05") { cdIs(InsightsTags.CAL_DAY + "2026-10-05", "5: R$ 180,00") }
    }

    // =====================================================================================
    // Escolha da verba (PACE_BUDGET, BUDGET_CHOICE)
    // =====================================================================================

    @Test
    fun `escolha da verba fica com escritas no Db e volta ao padrão quando a verba escolhida é apagada`() {
        val casa = budget("Casa", 31_000)
        val lazer = budget("Lazer", 31_000)
        // Lazer 120 > ideal 90: Acima do ritmo; Casa 0: No ritmo. §8.1: Acima do ritmo antes de No ritmo.
        tx(lazer, 12_000, LocalDate(2026, 10, 5), listOf("cinema"))
        composeRule.setContent { Content() }
        openInsights()
        await("padrão é Lazer, a mais em risco", InsightsTags.PACE_BUDGET) { shows(InsightsTags.PACE_BUDGET, "Lazer") }

        openChoice()
        await("as duas na escolha") { exists(InsightsTags.BUDGET_OPTION + casa.v) && exists(InsightsTags.BUDGET_OPTION + lazer.v) }
        assertTrue("nomes", shows(InsightsTags.BUDGET_OPTION + casa.v, "Casa") && shows(InsightsTags.BUDGET_OPTION + lazer.v, "Lazer"))
        assertTrue("ordem de risco: Lazer acima de Casa", top(InsightsTags.BUDGET_OPTION + lazer.v) < top(InsightsTags.BUDGET_OPTION + casa.v))
        tap(InsightsTags.BUDGET_OPTION + casa.v)
        await("Casa escolhida e a escolha fechada", InsightsTags.PACE_BUDGET) {
            !exists(InsightsTags.BUDGET_CHOICE) && shows(InsightsTags.PACE_BUDGET, "Casa")
        }
        // Sem gasto, sem projeção (decidido na triagem do bloco 6).
        await("Ritmo Casa em zero", InsightsTags.PACE_SENTENCE) { textIs(InsightsTags.PACE_SENTENCE, "R$ 90,00 abaixo do ritmo ideal.") }

        // Lazer passa da cota por fora (120 + 300 = 420): continua Casa.
        tx(lazer, 30_000, LocalDate(2026, 10, 6), listOf("cinema"))
        // Casa ganha 45 por fora: a frase segue, ainda em Casa.
        tx(casa, 4_500, LocalDate(2026, 10, 7), listOf("luz"))
        awaitPace("Casa com 45", "R$ 45,00 abaixo do ritmo ideal.", "No passo atual, o mês fecharia em R$ 155.")
        assertTrue("escolha mantida", shows(InsightsTags.PACE_BUDGET, "Casa"))

        // Verba nova por fora (400 > 310): entra na escolha, sem tirar Casa.
        val feira = budget("Feira", 31_000)
        tx(feira, 40_000, LocalDate(2026, 10, 8), listOf("feira"))
        settle()
        assertTrue("escolha mantida com a verba nova", shows(InsightsTags.PACE_BUDGET, "Casa"))
        openChoice()
        await("a verba nova na escolha") { exists(InsightsTags.BUDGET_OPTION + feira.v) }
        tap(InsightsTags.BUDGET_OPTION + casa.v)
        await("escolha fechada") { !exists(InsightsTags.BUDGET_CHOICE) && shows(InsightsTags.PACE_BUDGET, "Casa") }

        // Casa apagada por fora: volta ao padrão. §8.1: Fora da margem, maior excesso primeiro —
        // Lazer 420 − 310 = 110 > Feira 400 − 310 = 90.
        fora { db.deleteBudget(casa) }
        await("padrão depois de apagar a escolhida", InsightsTags.PACE_BUDGET, InsightsTags.PACE_SENTENCE) {
            shows(InsightsTags.PACE_BUDGET, "Lazer") && shows(InsightsTags.PACE_SENTENCE, "R$ 110,00 além da cota.")
        }
        openChoice()
        await("Casa saiu da escolha") { !exists(InsightsTags.BUDGET_OPTION + casa.v) && exists(InsightsTags.BUDGET_OPTION + lazer.v) }
    }

    @Test
    fun `sem escolha o padrão segue a ordem de risco quando o Db muda`() {
        val casa = budget("Casa", 31_000)
        val lazer = budget("Lazer", 31_000)
        // §8.1, No ritmo: menor margem em % primeiro — Casa 300/310 < Lazer 310/310.
        tx(casa, 1_000, LocalDate(2026, 10, 5), listOf("luz"))
        composeRule.setContent { Content() }
        openInsights()
        await("padrão Casa", InsightsTags.PACE_BUDGET) { shows(InsightsTags.PACE_BUDGET, "Casa") }

        // Lazer 120 > 90: Acima do ritmo, antes de No ritmo.
        tx(lazer, 12_000, LocalDate(2026, 10, 6), listOf("cinema"))
        await("padrão Lazer", InsightsTags.PACE_BUDGET) { shows(InsightsTags.PACE_BUDGET, "Lazer") }

        // Casa 10 + 400 = 410 > 310: Fora da margem, antes de Acima do ritmo.
        tx(casa, 40_000, LocalDate(2026, 10, 7), listOf("luz"))
        await("padrão Casa de novo", InsightsTags.PACE_BUDGET, InsightsTags.PACE_SENTENCE) {
            shows(InsightsTags.PACE_BUDGET, "Casa") && shows(InsightsTags.PACE_SENTENCE, "R$ 100,00 além da cota.")
        }
    }

    // =====================================================================================
    // Mês (MONTH_LABEL, MONTH_PREV, MONTH_NEXT)
    // =====================================================================================

    @Test
    fun `navegação de mês para no primeiro mês com cota e no mês atual e segue verba antiga criada por fora`() {
        val casa = budget("Casa", 31_000, created = LocalDate(2026, 8, 10))
        tx(casa, 12_000, LocalDate(2026, 9, 15), listOf("luz"))
        fora { db.ensureMonths(YearMonth(2026, 10)) }
        composeRule.setContent { Content() }
        openInsights()

        await("outubro, atual", InsightsTags.MONTH_LABEL) {
            shows(InsightsTags.MONTH_LABEL, "outubro de 2026") && showsIgnoreCase(InsightsTags.MONTH_LABEL, "1 a 31 · até sex, 9")
        }
        assertFalse("próximo desabilitado no mês atual", enabled(InsightsTags.MONTH_NEXT))
        assertTrue("anterior habilitado", enabled(InsightsTags.MONTH_PREV))

        tap(InsightsTags.MONTH_PREV)
        await("setembro, passado", InsightsTags.MONTH_LABEL) {
            shows(InsightsTags.MONTH_LABEL, "setembro de 2026") && showsIgnoreCase(InsightsTags.MONTH_LABEL, "1 a 30") &&
                !showsIgnoreCase(InsightsTags.MONTH_LABEL, "até")
        }
        assertTrue("próximo habilitado num mês passado", enabled(InsightsTags.MONTH_NEXT))
        assertTrue("anterior habilitado em setembro", enabled(InsightsTags.MONTH_PREV))
        // Mês passado: "Fechou em R$ 120,00, R$ 190,00 dentro da cota." (310 − 120 = 190).
        await("frase de mês passado", InsightsTags.PACE_SENTENCE) {
            textIs(InsightsTags.PACE_SENTENCE, "Fechou em R$ 120,00, R$ 190,00 dentro da cota.")
        }
        await("descrição de mês passado", InsightsTags.PACE_CHART) {
            cdIs(InsightsTags.PACE_CHART, "Ritmo do mês de Casa: R$ 120,00 no mês, para uma cota de R$ 310,00")
        }
        await("ranking de setembro", InsightsTags.RANK_ROW + "0") { shows(InsightsTags.RANK_ROW + "0", "luz") }

        tap(InsightsTags.MONTH_PREV)
        await("agosto, o primeiro", InsightsTags.MONTH_LABEL) {
            shows(InsightsTags.MONTH_LABEL, "agosto de 2026") && showsIgnoreCase(InsightsTags.MONTH_LABEL, "1 a 31")
        }
        await("anterior desabilitado no primeiro mês com cota") { !enabled(InsightsTags.MONTH_PREV) }
        await("nada lançado em agosto", InsightsTags.RANK_EMPTY, InsightsTags.CAL_EMPTY) {
            textIs(InsightsTags.RANK_EMPTY, "Nada lançado em agosto.") && textIs(InsightsTags.CAL_EMPTY, "Nada lançado em agosto.")
        }

        // Verba com cota em julho criada por fora: o anterior se habilita em agosto.
        budget("Velha", 20_000, created = LocalDate(2026, 7, 10))
        await("anterior habilitado com a verba de julho") { enabled(InsightsTags.MONTH_PREV) }
        tap(InsightsTags.MONTH_PREV)
        await("julho", InsightsTags.MONTH_LABEL) { shows(InsightsTags.MONTH_LABEL, "julho de 2026") }
        await("julho é o primeiro") { !enabled(InsightsTags.MONTH_PREV) }

        repeat(3) { i ->
            await("próximo habilitado ($i)") { enabled(InsightsTags.MONTH_NEXT) }
            tap(InsightsTags.MONTH_NEXT)
        }
        await("de volta a outubro", InsightsTags.MONTH_LABEL) {
            shows(InsightsTags.MONTH_LABEL, "outubro de 2026") && showsIgnoreCase(InsightsTags.MONTH_LABEL, "até sex, 9")
        }
        assertFalse("próximo desabilitado de novo", enabled(InsightsTags.MONTH_NEXT))
    }

    @Test
    fun `escolha fica ao trocar de mês quando a verba tem cota lá e volta ao padrão quando não tem`() {
        val (casa, lazer, feira) = threeBudgets()
        composeRule.setContent { Content() }
        openInsights()
        await("padrão de outubro: Feira", InsightsTags.PACE_BUDGET) { shows(InsightsTags.PACE_BUDGET, "Feira") }

        choose(casa, "Casa")
        tap(InsightsTags.MONTH_PREV)
        await("setembro mantém Casa (o padrão de setembro seria Lazer)", InsightsTags.MONTH_LABEL, InsightsTags.PACE_BUDGET) {
            shows(InsightsTags.MONTH_LABEL, "setembro de 2026") && shows(InsightsTags.PACE_BUDGET, "Casa")
        }
        // Setembro, Casa sem gasto: "Fechou em R$ 0,00, R$ 310,00 dentro da cota.".
        await("frase de Casa em setembro", InsightsTags.PACE_SENTENCE) {
            textIs(InsightsTags.PACE_SENTENCE, "Fechou em R$ 0,00, R$ 310,00 dentro da cota.")
        }
        // A escolha de setembro lista só as verbas com cota em setembro.
        openChoice()
        await("Casa e Lazer, sem Feira") {
            exists(InsightsTags.BUDGET_OPTION + casa.v) && exists(InsightsTags.BUDGET_OPTION + lazer.v) && !exists(InsightsTags.BUDGET_OPTION + feira.v)
        }
        tap(InsightsTags.BUDGET_OPTION + casa.v)
        await("escolha fechada") { !exists(InsightsTags.BUDGET_CHOICE) }

        tap(InsightsTags.MONTH_NEXT)
        await("outubro mantém Casa", InsightsTags.MONTH_LABEL, InsightsTags.PACE_BUDGET) {
            shows(InsightsTags.MONTH_LABEL, "outubro de 2026") && shows(InsightsTags.PACE_BUDGET, "Casa")
        }

        // Feira não tem cota em setembro: lá volta ao padrão de setembro (Lazer, §8.1).
        choose(feira, "Feira")
        tap(InsightsTags.MONTH_PREV)
        await("setembro sem cota de Feira: padrão Lazer", InsightsTags.MONTH_LABEL, InsightsTags.PACE_BUDGET) {
            shows(InsightsTags.MONTH_LABEL, "setembro de 2026") && shows(InsightsTags.PACE_BUDGET, "Lazer")
        }
    }

    // =====================================================================================
    // Recriação e saída da aba
    // =====================================================================================

    @Test
    fun `mês e verba escolhidos sobrevivem à recriação`() {
        val (casa, _, _) = threeBudgets()
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openInsights()
        await("padrão de outubro", InsightsTags.PACE_BUDGET) { shows(InsightsTags.PACE_BUDGET, "Feira") }
        tap(InsightsTags.MONTH_PREV)
        await("setembro, padrão Lazer", InsightsTags.MONTH_LABEL, InsightsTags.PACE_BUDGET) {
            shows(InsightsTags.MONTH_LABEL, "setembro de 2026") && shows(InsightsTags.PACE_BUDGET, "Lazer")
        }
        choose(casa, "Casa")

        restore.emulateSavedInstanceStateRestore()
        await("setembro e Casa depois da recriação", InsightsTags.MONTH_LABEL, InsightsTags.PACE_BUDGET) {
            shows(InsightsTags.MONTH_LABEL, "setembro de 2026") && shows(InsightsTags.PACE_BUDGET, "Casa")
        }
        await("frase de Casa em setembro", InsightsTags.PACE_SENTENCE) {
            textIs(InsightsTags.PACE_SENTENCE, "Fechou em R$ 0,00, R$ 310,00 dentro da cota.")
        }
    }

    @Test
    fun `sair da aba e voltar abre no mês atual com a verba padrão e Marcadores`() {
        val (casa, _, _) = threeBudgets()
        composeRule.setContent { Content() }
        openInsights()
        await("padrão de outubro", InsightsTags.PACE_BUDGET) { shows(InsightsTags.PACE_BUDGET, "Feira") }
        tap(InsightsTags.MONTH_PREV)
        await("setembro") { shows(InsightsTags.MONTH_LABEL, "setembro de 2026") }
        choose(casa, "Casa")
        tap(InsightsTags.RANK_MERCHANTS)
        await("Estabelecimentos escolhido") { selected(InsightsTags.RANK_MERCHANTS) && !selected(InsightsTags.RANK_TAGS) }

        tapTab("Verbas")
        await("Verbas aberta") { exists(BudgetTags.LIST) && !exists(InsightsTags.MONTH_LABEL) }
        tapTab("Leitura")
        // KDoc MONTH_LABEL: abre no mês atual; PACE_BUDGET e RANK_*: a escolha fica só enquanto a tela está aberta.
        await("de volta: outubro, Feira, Marcadores", InsightsTags.MONTH_LABEL, InsightsTags.PACE_BUDGET) {
            shows(InsightsTags.MONTH_LABEL, "outubro de 2026") && shows(InsightsTags.PACE_BUDGET, "Feira") &&
                selected(InsightsTags.RANK_TAGS) && !selected(InsightsTags.RANK_MERCHANTS)
        }
    }

    @Test
    fun `hoje muda de mês com a tela fechada e a volta abre no mês novo`() {
        today = LocalDate(2026, 10, 31) // sábado
        val casa = budget("Casa", 31_000)
        tx(casa, 18_000, LocalDate(2026, 10, 10), listOf("luz"))
        composeRule.setContent { Content() }
        openInsights()
        await("31 de outubro", InsightsTags.MONTH_LABEL) {
            shows(InsightsTags.MONTH_LABEL, "outubro de 2026") && showsIgnoreCase(InsightsTags.MONTH_LABEL, "1 a 31 · até sáb, 31")
        }

        tapTab("Verbas")
        await("Verbas aberta") { exists(BudgetTags.LIST) }
        today = LocalDate(2026, 11, 1) // domingo
        tapTab("Leitura")
        // Novembro de 2026: 30 dias. A tela garante a cota do mês (D30): Casa tem Ritmo em novembro.
        await("novembro, atual", InsightsTags.MONTH_LABEL) {
            shows(InsightsTags.MONTH_LABEL, "novembro de 2026") && showsIgnoreCase(InsightsTags.MONTH_LABEL, "1 a 30 · até dom, 1")
        }
        await("Ritmo de Casa em novembro", InsightsTags.PACE_BUDGET) { shows(InsightsTags.PACE_BUDGET, "Casa") }
        await("nada lançado em novembro", InsightsTags.RANK_EMPTY, InsightsTags.CAL_EMPTY) {
            textIs(InsightsTags.RANK_EMPTY, "Nada lançado em novembro.") && textIs(InsightsTags.CAL_EMPTY, "Nada lançado em novembro.")
        }
        assertFalse("próximo desabilitado em novembro", enabled(InsightsTags.MONTH_NEXT))
        assertTrue("anterior habilitado", enabled(InsightsTags.MONTH_PREV))

        tap(InsightsTags.MONTH_PREV)
        // Outubro agora é passado: "Fechou em R$ 180,00, R$ 130,00 dentro da cota." (310 − 180).
        await("outubro passado", InsightsTags.MONTH_LABEL, InsightsTags.PACE_SENTENCE) {
            shows(InsightsTags.MONTH_LABEL, "outubro de 2026") && !showsIgnoreCase(InsightsTags.MONTH_LABEL, "até") &&
                textIs(InsightsTags.PACE_SENTENCE, "Fechou em R$ 180,00, R$ 130,00 dentro da cota.")
        }
        assertTrue("próximo habilitado", enabled(InsightsTags.MONTH_NEXT))
    }

    // =====================================================================================
    // Ranking (RANK_*)
    // =====================================================================================

    @Test
    fun `ranking segue o Db e mantém Estabelecimentos e Ver todos enquanto a tela está aberta`() {
        val casa = budget("Casa", 100_000)
        composeRule.setContent { Content() }
        openInsights()
        await("ranking vazio", InsightsTags.RANK_EMPTY) { textIs(InsightsTags.RANK_EMPTY, "Nada lançado em outubro.") }
        assertTrue("Marcadores é o padrão", selected(InsightsTags.RANK_TAGS) && !selected(InsightsTags.RANK_MERCHANTS))
        assertFalse("sem frase no vazio", exists(InsightsTags.RANK_SENTENCE))
        assertFalse("sem aviso no vazio", exists(InsightsTags.RANK_NOTE))
        assertFalse("sem linhas no vazio", exists(InsightsTags.RANK_ROW + "0"))

        tx(casa, 4_500, LocalDate(2026, 10, 5), listOf("feira"), merchant = "Sacolao")
        await("primeira linha", InsightsTags.RANK_ROW + "0") {
            !exists(InsightsTags.RANK_EMPTY) && shows(InsightsTags.RANK_ROW + "0", "feira") &&
                shows(InsightsTags.RANK_ROW + "0", "1 lançamento") && shows(InsightsTags.RANK_ROW + "0", "45,00")
        }
        await("frase e aviso", InsightsTags.RANK_SENTENCE, InsightsTags.RANK_NOTE) {
            textIs(InsightsTags.RANK_SENTENCE, "feira puxa a fila.") &&
                textIs(InsightsTags.RANK_NOTE, "Com vários marcadores, o lançamento conta inteiro em cada um: a soma pode passar do total.")
        }
        assertFalse("sem Ver todos com 1 linha", exists(InsightsTags.RANK_MORE))

        tap(InsightsTags.RANK_MERCHANTS)
        await("Estabelecimentos", InsightsTags.RANK_ROW + "0") {
            selected(InsightsTags.RANK_MERCHANTS) && !selected(InsightsTags.RANK_TAGS) &&
                shows(InsightsTags.RANK_ROW + "0", "Sacolao") && textIs(InsightsTags.RANK_SENTENCE, "Sacolao puxa a fila.")
        }
        assertFalse("sem aviso fora de Marcadores", exists(InsightsTags.RANK_NOTE))

        // 7 estabelecimentos novos, por fora: 8 linhas. Ordem por valor: A 70, B 60, C 50, Sacolao 45, D 40, E 35, F 30, G 25.
        listOf(7_000, 6_000, 5_000, 4_000, 3_500, 3_000, 2_500).forEachIndexed { i, cents ->
            tx(casa, cents.toLong(), LocalDate(2026, 10, 6), listOf("loja"), merchant = "Loja ${'A' + i}")
        }
        await("8 linhas, 6 à vista", InsightsTags.RANK_ROW + "0", InsightsTags.RANK_MORE) {
            selected(InsightsTags.RANK_MERCHANTS) && shows(InsightsTags.RANK_ROW + "0", "Loja A") &&
                exists(InsightsTags.RANK_ROW + "5") && !exists(InsightsTags.RANK_ROW + "6") && textIs(InsightsTags.RANK_MORE, "Ver todos (8)")
        }
        assertTrue("Sacolao em 4º", shows(InsightsTags.RANK_ROW + "3", "Sacolao"))

        // Apelido por fora (estabelecimentos pelo apelido, spec §7.5).
        fora { db.setAlias("Sacolao", "Feira do Zé") }
        await("apelido na linha", InsightsTags.RANK_ROW + "3") { shows(InsightsTags.RANK_ROW + "3", "Feira do Zé") }

        tap(InsightsTags.RANK_MORE)
        await("todas as linhas", InsightsTags.RANK_ROW + "7") {
            exists(InsightsTags.RANK_ROW + "7") && shows(InsightsTags.RANK_ROW + "7", "Loja G") && !exists(InsightsTags.RANK_MORE)
        }

        // Mais um por fora, já aberto: aparece, sem Ver todos ("then all").
        tx(casa, 2_000, LocalDate(2026, 10, 7), listOf("loja"), merchant = "Loja H")
        await("nona linha", InsightsTags.RANK_ROW + "8") {
            shows(InsightsTags.RANK_ROW + "8", "Loja H") && !exists(InsightsTags.RANK_MORE) && selected(InsightsTags.RANK_MERCHANTS)
        }

        // Marcadores: loja = 70+60+50+40+35+30+25+20 = 330,00 em 8; feira 45,00 em 1.
        tap(InsightsTags.RANK_TAGS)
        await("Marcadores", InsightsTags.RANK_ROW + "0", InsightsTags.RANK_ROW + "1") {
            selected(InsightsTags.RANK_TAGS) && shows(InsightsTags.RANK_ROW + "0", "loja") &&
                shows(InsightsTags.RANK_ROW + "0", "8 lançamentos") && shows(InsightsTags.RANK_ROW + "0", "330,00") &&
                shows(InsightsTags.RANK_ROW + "1", "feira") && !exists(InsightsTags.RANK_ROW + "2")
        }
        assertTrue("aviso de volta", exists(InsightsTags.RANK_NOTE))

        // Tudo apagado por fora: vazio de novo.
        for (t in fora { db.transactions() }) fora { db.deleteTransaction(t.id) }
        await("ranking vazio de novo", InsightsTags.RANK_EMPTY) {
            textIs(InsightsTags.RANK_EMPTY, "Nada lançado em outubro.") && !exists(InsightsTags.RANK_ROW + "0") &&
                !exists(InsightsTags.RANK_SENTENCE) && !exists(InsightsTags.RANK_NOTE)
        }
    }

    @Test
    fun `Estabelecimentos fica ao trocar de mês`() {
        val casa = budget("Casa", 31_000, created = LocalDate(2026, 9, 10))
        tx(casa, 4_500, LocalDate(2026, 9, 15), listOf("feira"), merchant = "Sacolao")
        tx(casa, 4_500, LocalDate(2026, 10, 5), listOf("padaria"), merchant = "Padoca")
        fora { db.ensureMonths(YearMonth(2026, 10)) }
        composeRule.setContent { Content() }
        openInsights()
        await("outubro", InsightsTags.RANK_ROW + "0") { shows(InsightsTags.RANK_ROW + "0", "padaria") }
        tap(InsightsTags.RANK_MERCHANTS)
        await("Padoca") { shows(InsightsTags.RANK_ROW + "0", "Padoca") }
        tap(InsightsTags.MONTH_PREV)
        await("setembro por estabelecimento", InsightsTags.RANK_ROW + "0") {
            shows(InsightsTags.MONTH_LABEL, "setembro de 2026") && selected(InsightsTags.RANK_MERCHANTS) &&
                shows(InsightsTags.RANK_ROW + "0", "Sacolao")
        }
    }

    // =====================================================================================
    // Calendário (CAL_*)
    // =====================================================================================

    @Test
    fun `calendário segue o Db do vazio ao cheio e de volta`() {
        val casa = budget("Casa", 100_000)
        composeRule.setContent { Content() }
        openInsights()
        await("calendário vazio", InsightsTags.CAL_EMPTY) { textIs(InsightsTags.CAL_EMPTY, "Nada lançado em outubro.") }
        assertFalse("sem grade no vazio", exists(InsightsTags.CAL_DAY + "2026-10-05"))
        assertFalse("sem frase no vazio", exists(InsightsTags.CAL_SENTENCE))
        assertFalse("sem parte no vazio", exists(InsightsTags.CAL_SHARE))
        assertFalse("sem legenda no vazio", exists(InsightsTags.CAL_LEGEND))

        // 80,00 na segunda 5/10: degrau = 80 ÷ 4 = 20 (já múltiplo de 10); nível ⌈80/20⌉ = 4.
        val seg = tx(casa, 8_000, LocalDate(2026, 10, 5), listOf("luz"))
        await("dia 5 com gasto", InsightsTags.CAL_DAY + "2026-10-05") {
            !exists(InsightsTags.CAL_EMPTY) && cdIs(InsightsTags.CAL_DAY + "2026-10-05", "5: R$ 80,00") &&
                state(InsightsTags.CAL_DAY + "2026-10-05") == "degrau 4"
        }
        assertEquals("um dia por dia da janela de outubro", (1..31).map { LocalDate(2026, 10, it).toString() }.toSet(), suffixes(InsightsTags.CAL_DAY))
        assertTrue("dia 6 sem gasto", cdIs(InsightsTags.CAL_DAY + "2026-10-06", "6: sem gasto") && state(InsightsTags.CAL_DAY + "2026-10-06") == "degrau 0")
        await("legenda e frases", InsightsTags.CAL_LEGEND, InsightsTags.CAL_SENTENCE, InsightsTags.CAL_SHARE) {
            textIs(InsightsTags.CAL_LEGEND, "cada degrau ≈ R$ 20") && textIs(InsightsTags.CAL_SENTENCE, "As segundas pesam.") &&
                textIs(InsightsTags.CAL_SHARE, "100% do mês em quatro segundas")
        }

        // 120,00 no sábado 3/10: maior dia 120 → degrau 30; 3/10 ⌈120/30⌉ = 4, 5/10 ⌈80/30⌉ = 3.
        // Sábados pesam: 120 de 200 = 60%, cinco sábados (3, 10, 17, 24, 31).
        val sab = tx(casa, 12_000, LocalDate(2026, 10, 3), listOf("feira"))
        await("sábado pesa", InsightsTags.CAL_SENTENCE, InsightsTags.CAL_SHARE, InsightsTags.CAL_LEGEND) {
            textIs(InsightsTags.CAL_SENTENCE, "Os sábados pesam.") && textIs(InsightsTags.CAL_SHARE, "60% do mês em cinco sábados") &&
                textIs(InsightsTags.CAL_LEGEND, "cada degrau ≈ R$ 30")
        }
        assertEquals("degrau 4", state(InsightsTags.CAL_DAY + "2026-10-03"))
        assertEquals("degrau 3", state(InsightsTags.CAL_DAY + "2026-10-05"))

        // Estorno do sábado (não conta): de volta às segundas.
        ensureSource()
        val e = estorno("e1", 12_000, at(9, 12, 0))
        assertTrue("registerRefund", fora { db.registerRefund(e, sab) })
        await("segundas de novo", InsightsTags.CAL_SENTENCE, InsightsTags.CAL_DAY + "2026-10-03") {
            textIs(InsightsTags.CAL_SENTENCE, "As segundas pesam.") && cdIs(InsightsTags.CAL_DAY + "2026-10-03", "3: sem gasto") &&
                textIs(InsightsTags.CAL_LEGEND, "cada degrau ≈ R$ 20")
        }

        // A segunda apagada: só resta a compra estornada, nada conta: vazio.
        fora { db.deleteTransaction(seg) }
        await("calendário vazio de novo", InsightsTags.CAL_EMPTY) {
            textIs(InsightsTags.CAL_EMPTY, "Nada lançado em outubro.") && !exists(InsightsTags.CAL_DAY + "2026-10-05") &&
                !exists(InsightsTags.CAL_SENTENCE) && !exists(InsightsTags.CAL_LEGEND)
        }
    }

    // ---- Helpers: cenários ----

    /**
     * Casa e Lazer criadas em agosto (cota em ago, set, out), Feira só em outubro.
     * Outubro: Feira 120 > 90 Acima do ritmo; Casa e Lazer 0 No ritmo → padrão Feira (§8.1).
     * Setembro: Lazer 100, Casa 0 → padrão Lazer (No ritmo: menor margem em %, ou Acima se o ideal for 0).
     */
    private fun threeBudgets(): Triple<BudgetId, BudgetId, BudgetId> {
        val casa = budget("Casa", 31_000, created = LocalDate(2026, 8, 10))
        val lazer = budget("Lazer", 31_000, created = LocalDate(2026, 8, 10))
        fora { db.ensureMonths(YearMonth(2026, 10)) }
        val feira = budget("Feira", 31_000)
        tx(feira, 12_000, LocalDate(2026, 10, 5), listOf("feira"), merchant = "Sacolao")
        tx(lazer, 10_000, LocalDate(2026, 9, 10), listOf("cinema"), merchant = "Cine")
        return Triple(casa, lazer, feira)
    }

    // ---- Helpers: Db ----

    private fun ensureSource() {
        if (fora { db.sources() }.none { it.packageName == BANK }) assertTrue("addSource", fora { db.addSource(BANK, BANK_LABEL, at(1, 8, 0)) })
    }

    private fun budget(name: String, limit: Long, created: LocalDate = DIA): BudgetId =
        fora { db.createBudget(name, Cents(limit), created) }.also { assertNotEquals("createBudget $name", 0L, it.v) }

    private fun form(budget: BudgetId, cents: Long, date: LocalDate, tags: List<String>, merchant: String) =
        TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = 1, merchant = merchant, date = date, time = LocalTime(10, 0))

    private fun tx(budget: BudgetId, cents: Long, date: LocalDate, tags: List<String>, merchant: String = "Sacolao"): TransactionId =
        fora { db.addManual(form(budget, cents, date, tags, merchant)).transaction }.also { assertNotEquals("addManual $merchant", 0L, it.v) }

    /** Epoch ms de [day]/10/2026 [h]:[m] no fuso do sistema. */
    private fun at(day: Int, h: Int, m: Int): Long =
        LocalDateTime(2026, 10, day, h, m).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    /** Estorno genérico sem estabelecimento (ExtractTest: "Estorno de R$ 20,00 efetuado"), capturado de BANK. */
    private fun estorno(key: String, cents: Long, postedAt: Long): NoticeId {
        val reais = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
        val text = "Estorno de R$ $reais efetuado"
        val raw = RawNotice(
            packageName = BANK, appLabel = BANK_LABEL, key = key, postedAt = postedAt, whenMs = postedAt,
            title = "Estorno", text = text, bigText = text,
        )
        assertTrue("capture de $key", fora { capture(db, raw) })
        val row = fora { db.pending() }.first { it.raw.key == key }
        assertTrue("pré-condição: recado de estorno", row.parsed.isRefund)
        return row.id
    }

    /** Uma escrita ou leitura "por fora": outra coroutine, fora da thread da UI, com timeout. */
    private fun <T> fora(block: suspend () -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { block() } }

    // ---- Helpers: navegação ----

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun tapTab(label: String) {
        composeRule.onAllNodes(hasText(label) and tabRole).onFirst().performClick()
    }

    /** Pela barra de abas, como a pessoa. */
    private fun openInsights() {
        await("barra de abas") { composeRule.onAllNodes(hasText("Leitura") and tabRole).fetchSemanticsNodes().isNotEmpty() }
        tapTab("Leitura")
        await("Leitura aberta") { exists(InsightsTags.EMPTY) || exists(InsightsTags.MONTH_LABEL) }
    }

    private fun openChoice() {
        await("nome da verba") { exists(InsightsTags.PACE_BUDGET) }
        tap(InsightsTags.PACE_BUDGET)
        await("escolha aberta") { exists(InsightsTags.BUDGET_CHOICE) }
    }

    private fun choose(id: BudgetId, name: String) {
        openChoice()
        await("opção $name") { exists(InsightsTags.BUDGET_OPTION + id.v) }
        tap(InsightsTags.BUDGET_OPTION + id.v)
        await("$name escolhida", InsightsTags.PACE_BUDGET) { !exists(InsightsTags.BUDGET_CHOICE) && shows(InsightsTags.PACE_BUDGET, name) }
    }

    /** Espera o PACE_SENTENCE com [conclusion] e [projection] (KDoc: "then, … ' No passo atual, …'"). */
    private fun awaitPace(what: String, conclusion: String, projection: String) {
        await("Ritmo $what", InsightsTags.PACE_SENTENCE) {
            shows(InsightsTags.PACE_SENTENCE, conclusion) && shows(InsightsTags.PACE_SENTENCE, projection)
        }
    }

    // ---- Helpers: semântica ----

    private fun nodes(tag: String, merged: Boolean): List<SemanticsNode> =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = !merged).fetchSemanticsNodes()

    private fun node(tag: String): SemanticsNode? = nodes(tag, merged = false).firstOrNull()

    private fun exists(tag: String) = node(tag) != null

    private fun selected(tag: String) = node(tag)?.config?.getOrNull(SemanticsProperties.Selected) == true

    private fun enabled(tag: String) = node(tag)?.let { !it.config.contains(SemanticsProperties.Disabled) } == true

    private fun top(tag: String): Float = node(tag)?.positionInRoot?.y ?: throw AssertionError("sem nó $tag")

    private fun norm(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    private fun collect(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text) +
            n.children.flatMap(::collect)

    /** Texts of the node with [tag] and of its descendants (unmerged tree), normalized; empty when absent. */
    private fun texts(tag: String): List<String> = node(tag)?.let(::collect).orEmpty().map(::norm)

    private fun shows(tag: String, s: String): Boolean {
        val t = texts(tag)
        val want = norm(s)
        return t.any { it.contains(want) } || t.joinToString("").contains(want) || t.joinToString(" ").contains(want)
    }

    private fun showsIgnoreCase(tag: String, s: String): Boolean {
        val t = texts(tag).map { it.lowercase() }
        val want = norm(s).lowercase()
        return t.any { it.contains(want) } || t.joinToString("").contains(want) || t.joinToString(" ").contains(want)
    }

    private fun textIs(tag: String, s: String): Boolean {
        val t = texts(tag)
        val want = norm(s)
        return t.any { it.trim() == want } || t.joinToString("").trim() == want || t.joinToString(" ").trim() == want
    }

    /** Content description of the node with [tag] (merged or not), normalized. */
    private fun cd(tag: String): List<String> =
        (nodes(tag, merged = true) + nodes(tag, merged = false))
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .map(::norm)

    private fun cdIs(tag: String, s: String): Boolean = cd(tag).any { it.trim() == norm(s) }

    private fun state(tag: String): String? =
        (nodes(tag, merged = false) + nodes(tag, merged = true)).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.StateDescription) }

    private fun suffixes(prefix: String): Set<String> = composeRule
        .onAllNodes(
            SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true },
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix(prefix) }
        .toSet()

    private fun tap(tag: String) {
        val n = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst()
        runCatching { n.performScrollTo() }
        n.performClick()
    }

    /** Waits for [cond]; on timeout, fails with [what] and the texts and descriptions of [show]. */
    private fun await(what: String, vararg show: String, timeout: Long = UI_TIMEOUT_MS, cond: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeout) { cond() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what; na tela: " + show.joinToString { "$it=${texts(it)} cd=${cd(it)} state=${state(it)}" }, e)
        }
    }

    /** Lets the IO queries come back and the UI settle; an exception in the composition comes out here. */
    private fun settle() = repeat(10) {
        Thread.sleep(20)
        composeRule.waitForIdle()
    }

    private companion object {
        /** Sexta, 9 de outubro de 2026. */
        val DIA = LocalDate(2026, 10, 9)
        const val BANK = "com.bank"
        const val BANK_LABEL = "Banco Aurora"
        const val OP_TIMEOUT_MS = 10_000L
        const val STORM_TIMEOUT_MS = 60_000L
        const val UI_TIMEOUT_MS = 10_000L
    }
}
