package com.kaiodelphino.margem

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.kaiodelphino.margem.widgets.moneyPlain
import com.kaiodelphino.margem.widgets.stepValue
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Verbas (bloco 3), concorrência e ciclo de vida: o Db muda por fora (outra coroutine) com a lista ou
 * o detalhe abertos e a tela segue o Db (D6, D19); a verba some com o detalhe ou o diálogo de passo
 * abertos; o estado sobrevive à restauração (D18, rememberSaveable); escritas concorrentes de passo e
 * cota mantêm Σ passos = cota (D11); `ensureMonths` da lista corre junto com escritas (D30).
 *
 * Calendário usado (spec §3, §4; padrões de Ajustes: mês começa no dia 1, semana na segunda):
 * 14/10/2026 é quarta; 1/10/2026 é quinta. Outubro tem 31 dias e as semanas, cortadas no mês,
 * são 1–4 (4 dias), 5–11, 12–18, 19–25 (7 dias) e 26–31 (6 dias).
 *
 * Plain Application: MargemApp abriria o Db de produção com o driver bundled (D28).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class BudgetsLifecycleTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    /** Tira o App da composição antes de fechar o Db, para nenhuma consulta rodar num Db fechado. */
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
            CompositionLocalProvider(LocalToday provides { DIA_FIXO }) { App(db, null) {} }
        }
    }

    // ---- Escrita por fora com a tela aberta (D6, D19) ----

    @Test
    fun `Verbas aberta recompõe a linha quando o Db muda por fora`() {
        val id = fora { db.createBudget("Mercado", Cents(60_000), DIA_FIXO) }
        assertNotEquals(0L, id.v)
        composeRule.setContent { Content() }
        val row = BudgetTags.ROW + id.v
        await("linha de Mercado com 'de 600,00'") { shows(row, "Mercado") && shows(row, moneyPlain(Cents(60_000))) }

        assertNotEquals(0L, fora { db.addManual(form(id, 12_345)).transaction }.v)
        // §3: margem = cota − gasto = 600,00 − 123,45 = 476,55.
        await("gasto 123,45 e margem 476,55 na linha", row) {
            shows(row, money(Cents(12_345))) && shows(row, money(Cents(47_655)))
        }

        assertTrue(fora { db.setLimit(id, Cents(80_000), DIA_FIXO) })
        // Cota nova vale do mês atual (§3): margem = 800,00 − 123,45 = 676,55.
        await("cota 800,00 e margem 676,55 na linha", row) {
            shows(row, moneyPlain(Cents(80_000))) && shows(row, money(Cents(67_655)))
        }

        assertTrue(fora { db.renameBudget(id, "Feira") })
        await("nome novo na linha", row) { shows(row, "Feira") }

        assertNotEquals(0L, fora { db.addManual(form(id, 70_000)).transaction }.v)
        // Gasto 123,45 + 700,00 = 823,45 > cota 800,00: Fora da margem, "R$ 23,45 além" (§5, BudgetTags.ROW).
        await("gasto 823,45 e 'R$ 23,45 além' na linha", row) {
            shows(row, money(Cents(82_345))) && shows(row, money(Cents(2_345))) && shows(row, "além")
        }
    }

    @Test
    fun `detalhe aberto recompõe lançamentos, passos, nome e cota quando o Db muda por fora`() {
        val id = fora { db.createBudget("Mercado", Cents(60_000), DIA_FIXO) }
        assertNotEquals(0L, id.v)
        composeRule.setContent { Content() }
        openDetail(id)
        await("título Mercado", BudgetTags.DETAIL_TITLE) { shows(BudgetTags.DETAIL_TITLE, "Mercado") }

        // §4, passos padrão de 600,00 em 31 dias, arredondados para baixo, a última semana fica com a
        // diferença: 1–4 = 60000×4/31 = 7741; semanas de 7 dias = 60000×7/31 = 13548;
        // 26–31 = 60000 − 7741 − 3×13548 = 11615.
        await("passos padrão", W12, W26) { shows(W12, stepValue(Cents(13_548))) && shows(W26, stepValue(Cents(11_615))) }

        val t = fora { db.addManual(form(id, 12_345)).transaction }
        assertNotEquals(0L, t.v)
        val line = BudgetTags.LINE + t.v
        val sum = BudgetTags.LINE_SUM + t.v
        // Margem 600,00 − 123,45 = 476,55; soma corrida da única linha = 123,45.
        await("linha nova, soma corrida 123,45 e margem 476,55", sum, BudgetTags.DETAIL_HEADLINE) {
            exists(line) && shows(sum, moneyPlain(Cents(12_345))) && shows(BudgetTags.DETAIL_HEADLINE, money(Cents(47_655)))
        }

        assertTrue(fora { db.setStep(id, OUT, LocalDate(2026, 10, 12), Cents(5_000)) })
        // §4: 12–18 editada em 50,00; as outras dividem 55000 em 24 dias: 1–4 = 55000×4/24 = 9166;
        // 5–11 e 19–25 = 55000×7/24 = 16041; 26–31 = 55000 − 9166 − 2×16041 = 13752.
        await("passo editado 50 e última semana 137,52", W12, W26) {
            shows(W12, stepValue(Cents(5_000))) && shows(W26, stepValue(Cents(13_752)))
        }

        assertTrue(fora { db.clearStep(id, OUT, LocalDate(2026, 10, 12)) })
        // Voltar ao automático (§4): de novo os passos padrão.
        await("passos padrão depois do clearStep", W12, W26) {
            shows(W12, stepValue(Cents(13_548))) && shows(W26, stepValue(Cents(11_615)))
        }

        assertTrue(fora { db.renameBudget(id, "Feira") })
        await("título novo", BudgetTags.DETAIL_TITLE) { shows(BudgetTags.DETAIL_TITLE, "Feira") }

        assertTrue(fora { db.setStep(id, OUT, LocalDate(2026, 10, 12), Cents(5_000)) })
        await("passo editado de novo", W12) { shows(W12, stepValue(Cents(5_000))) }
        assertTrue(fora { db.setLimit(id, Cents(4_000), DIA_FIXO) })
        // §4: cota nova 40,00 < 50,00 editados → as edições do mês somem. Passos de 40,00 em 31 dias:
        // 1–4 = 4000×4/31 = 516; 7 dias = 4000×7/31 = 903; 26–31 = 4000 − 516 − 3×903 = 775.
        // §5: gasto 123,45 > 40,00 → "R$ 83,45 além da cota." (BudgetTags.DETAIL_HEADLINE).
        await("cota 40,00: passos automáticos e Fora da margem", W12, W26, BudgetTags.DETAIL_HEADLINE) {
            shows(W12, stepValue(Cents(903))) && shows(W26, stepValue(Cents(775))) &&
                shows(BudgetTags.DETAIL_HEADLINE, money(Cents(8_345))) && shows(BudgetTags.DETAIL_HEADLINE, "além")
        }
    }

    // ---- Verba apagada por fora ----

    @Test
    fun `verba apagada por fora com o detalhe aberto não quebra e some da lista`() {
        val id = fora { db.createBudget("Lazer", Cents(30_000), DIA_FIXO) }
        assertNotEquals(0L, fora { db.addManual(form(id, 4_500)).transaction }.v)
        composeRule.setContent { Content() }
        openDetail(id)
        await("detalhe de Lazer", BudgetTags.DETAIL_TITLE) { shows(BudgetTags.DETAIL_TITLE, "Lazer") }

        fora { db.deleteBudget(id) }
        settle() // uma exceção na composição sairia aqui

        backToBudgets()
        await("Verbas vazia depois de apagar a única verba") { exists(BudgetTags.EMPTY) && !exists(BudgetTags.ROW + id.v) }
        assertTrue(fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.isEmpty())
    }

    @Test
    fun `verba apagada por fora com o diálogo de passo aberto não quebra e Salvar não deixa edição órfã`() {
        val id = fora { db.createBudget("Lazer", Cents(60_000), DIA_FIXO) }
        composeRule.setContent { Content() }
        openDetail(id)
        await("semana 12–18 no detalhe", W12) { exists(W12) }
        composeRule.onNodeWithTag(W12).performClick()
        await("diálogo de passo") { exists(BudgetTags.STEP_DIALOG) }

        fora { db.deleteBudget(id) }
        settle()

        // Quem tocou Salvar depois que a verba sumiu: a escrita chega a um id desconhecido.
        if (exists(BudgetTags.STEP_FIELD) && exists(BudgetTags.STEP_SAVE)) {
            composeRule.onNodeWithTag(BudgetTags.STEP_FIELD).performTextReplacement("1000")
            composeRule.onNodeWithTag(BudgetTags.STEP_SAVE).performClick()
            settle()
        }
        if (exists(BudgetTags.STEP_CANCEL)) {
            composeRule.onNodeWithTag(BudgetTags.STEP_CANCEL).performClick()
            settle()
        }
        backToBudgets()
        await("Verbas vazia") { exists(BudgetTags.EMPTY) }

        assertNoOrphanEdits()
    }

    @Test
    fun `verba apagada por fora com o diálogo de semana editada aberto não quebra e Voltar ao automático não deixa resto`() {
        val id = fora { db.createBudget("Lazer", Cents(60_000), DIA_FIXO) }
        assertTrue(fora { db.setStep(id, OUT, LocalDate(2026, 10, 12), Cents(5_000)) })
        composeRule.setContent { Content() }
        openDetail(id)
        await("semana 12–18 editada em 50", W12) { shows(W12, stepValue(Cents(5_000))) }
        composeRule.onNodeWithTag(W12).performClick()
        // BudgetTags.STEP_AUTO: presente nas semanas editadas.
        await("Voltar ao automático no diálogo da semana editada") { exists(BudgetTags.STEP_AUTO) }

        fora { db.deleteBudget(id) }
        settle()

        if (exists(BudgetTags.STEP_AUTO)) {
            composeRule.onNodeWithTag(BudgetTags.STEP_AUTO).performClick()
            settle()
        }
        if (exists(BudgetTags.STEP_CANCEL)) {
            composeRule.onNodeWithTag(BudgetTags.STEP_CANCEL).performClick()
            settle()
        }
        backToBudgets()
        await("Verbas vazia") { exists(BudgetTags.EMPTY) }

        assertNoOrphanEdits()
    }

    /**
     * Apagar uma verba apaga os passos dela (§5 da arquitetura) e uma escrita velha não pode deixar
     * edição: a verba criada depois (mesmo nome, mesmo dia; o id pode até ser reaproveitado pelo
     * SQLite) nasce com os passos padrão (§3 cota cheia, §4 passo padrão).
     */
    private fun assertNoOrphanEdits() {
        val novo = fora { db.createBudget("Lazer", Cents(60_000), DIA_FIXO) }
        assertNotEquals("o nome ficou livre ao apagar", 0L, novo.v)
        val month = fora { db.budgetDetail(novo, OUT, DIA_FIXO) }.month
        assertEquals(WEEK_STARTS, month.weeks.map { it.start })
        assertTrue("nenhuma semana editada na verba nova: ${month.weeks}", month.weeks.none { it.edited })
        // 60000×7/31 = 13548 (§4).
        assertEquals(Cents(13_548), month.weeks[2].step)
        assertEquals(listOf(novo), fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.map { it.id })
        await("linha da verba nova") { exists(BudgetTags.ROW + novo.v) }
    }

    // ---- Restauração de estado (D18, rememberSaveable) ----

    @Test
    fun `Nova verba preenchida sobrevive à restauração e cria a verba com nome e cota digitados`() {
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        await("botão +") { exists(BudgetTags.ADD) }
        composeRule.onNodeWithTag(BudgetTags.ADD).performClick()
        await("formulário de Nova verba") { exists(BudgetTags.FORM_NAME) && exists(BudgetTags.FORM_LIMIT) }
        composeRule.onNodeWithTag(BudgetTags.FORM_NAME).performTextInput("Viagem")
        // Dígitos são centavos: "31000" = R$ 310,00 (BudgetTags).
        composeRule.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextReplacement("31000")
        // BudgetTags.FORM_PREVIEW: 31000 ÷ 31 dias = R$ 10,00 por dia; 31000 × 7 ÷ 31 = R$ 70,00 a semana cheia.
        await("prévia de 310,00", BudgetTags.FORM_PREVIEW) {
            shows(BudgetTags.FORM_PREVIEW, money(Cents(1_000))) && shows(BudgetTags.FORM_PREVIEW, money(Cents(7_000)))
        }

        restore.emulateSavedInstanceStateRestore()

        await("nome continua depois da restauração", BudgetTags.FORM_NAME) { shows(BudgetTags.FORM_NAME, "Viagem") }
        await("cota continua (prévia de 310,00) depois da restauração", BudgetTags.FORM_PREVIEW) {
            shows(BudgetTags.FORM_PREVIEW, money(Cents(1_000))) && shows(BudgetTags.FORM_PREVIEW, money(Cents(7_000)))
        }
        composeRule.onNodeWithTag(BudgetTags.FORM_SUBMIT).performClick()
        await("Viagem criada no Db") {
            fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.any { it.name == "Viagem" }
        }
        val created = fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.single()
        assertEquals("Viagem", created.name)
        assertEquals(Cents(31_000), created.month.limit)
        await("de volta a Verbas, com a linha nova") { exists(BudgetTags.ROW + created.id.v) }
    }

    @Test
    fun `detalhe no mês passado sobrevive à restauração no mesmo mês e Voltar leva a Verbas`() {
        val id = fora { db.createBudget("Viagem", Cents(40_000), LocalDate(2026, 9, 10)) }
        assertNotEquals(0L, id.v)
        assertNotEquals(0L, fora { db.addManual(form(id, 5_000, LocalDate(2026, 9, 20))).transaction }.v)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openDetail(id)
        await("detalhe em outubro", BudgetTags.MONTH_LABEL) { shows(BudgetTags.MONTH_LABEL, "outubro de 2026", ignoreCase = true) }
        composeRule.onNodeWithTag(BudgetTags.MONTH_PREV).performClick()
        // Setembro: margem = 400,00 − 50,00 = 350,00 (§3); outubro teria 400,00.
        await("detalhe em setembro", BudgetTags.MONTH_LABEL, BudgetTags.DETAIL_HEADLINE) {
            shows(BudgetTags.MONTH_LABEL, "setembro de 2026", ignoreCase = true) &&
                shows(BudgetTags.DETAIL_HEADLINE, money(Cents(35_000)))
        }

        restore.emulateSavedInstanceStateRestore()

        await("ainda em setembro depois da restauração", BudgetTags.MONTH_LABEL, BudgetTags.DETAIL_HEADLINE) {
            shows(BudgetTags.MONTH_LABEL, "setembro de 2026", ignoreCase = true) &&
                shows(BudgetTags.DETAIL_HEADLINE, money(Cents(35_000))) &&
                shows(BudgetTags.DETAIL_TITLE, "Viagem")
        }
        // Setembro é o primeiro mês da verba (criada em 10/9); outubro é o mês atual.
        composeRule.onNodeWithTag(BudgetTags.MONTH_PREV).assertIsNotEnabled()
        composeRule.onNodeWithTag(BudgetTags.MONTH_NEXT).assertIsEnabled()

        // A pilha restaurada ainda tem Verbas embaixo do detalhe.
        pressBack()
        await("Voltar leva à lista") { exists(BudgetTags.ROW + id.v) }
    }

    // ---- Escritas concorrentes de passo e cota ----

    @Test
    fun `setStep, clearStep e setLimit concorrentes mantêm Σ passos = cota e a tela mostra o estado final`() {
        val id = fora { db.createBudget("Casa", Cents(60_000), DIA_FIXO) }
        assertNotEquals(0L, id.v)
        composeRule.setContent { Content() }
        openDetail(id)
        await("detalhe de Casa", BudgetTags.DETAIL_TITLE) { shows(BudgetTags.DETAIL_TITLE, "Casa") }

        val attemptedLimits = Collections.synchronizedSet(mutableSetOf(60_000L))
        val storm = inParallel {
            (0 until WORKERS).map { w ->
                async {
                    val seed = SEED + w
                    val rnd = Random(seed)
                    repeat(OPS_PER_WORKER) { i ->
                        val week = WEEK_STARTS[rnd.nextInt(WEEK_STARTS.size)]
                        val op = when (rnd.nextInt(100)) {
                            in 0 until 45 -> {
                                val step = Cents(rnd.nextLong(0, 30_000))
                                val ok = withTimeout(OP_TIMEOUT_MS) { db.setStep(id, OUT, week, step) }
                                "setStep($week, ${step.v}) = $ok"
                            }
                            in 45 until 75 -> {
                                val ok = withTimeout(OP_TIMEOUT_MS) { db.clearStep(id, OUT, week) }
                                "clearStep($week) = $ok"
                            }
                            else -> {
                                val limit = Cents(rnd.nextLong(5_000, 90_000))
                                attemptedLimits += limit.v
                                val ok = withTimeout(OP_TIMEOUT_MS) { db.setLimit(id, limit, DIA_FIXO) }
                                "setLimit(${limit.v}) = $ok"
                            }
                        }
                        // Cada leitura é um retrato consistente (D29): nele o mês já vale as regras do §4.
                        val month = withTimeout(OP_TIMEOUT_MS) { db.budgetDetail(id, OUT, DIA_FIXO) }.month
                        checkSteps(month, attemptedLimits, "seed=$seed worker=$w op=$i $op")
                    }
                }
            }.awaitAll()
        }
        await("tempestade de escritas terminou", timeout = STORM_TIMEOUT_MS + 10_000) { storm.isCompleted }
        runBlocking { storm.await() }

        val final = fora { db.budgetDetail(id, OUT, DIA_FIXO) }
        checkSteps(final.month, attemptedLimits, "estado final (seed=$SEED)")
        val weekTags = final.month.weeks.map { BudgetTags.WEEK + it.start }.toTypedArray()
        await("a tela mostra os passos e a margem finais do Db: ${final.month}", *weekTags, BudgetTags.DETAIL_HEADLINE) {
            final.month.weeks.all { shows(BudgetTags.WEEK + it.start, stepValue(it.step)) } &&
                shows(BudgetTags.DETAIL_HEADLINE, money(final.month.remaining))
        }
        // BudgetTags.WEEK: clicável e habilitada salvo Week.locked.
        for (w in final.month.weeks) {
            val node = composeRule.onNodeWithTag(BudgetTags.WEEK + w.start)
            if (w.locked) node.assertIsNotEnabled() else node.assertIsEnabled()
        }
    }

    /** §4 e D11 num retrato do mês: as 5 semanas de outubro, passos ≥ 0, Σ passos = cota, uma automática. */
    private fun checkSteps(m: BudgetMonth, attemptedLimits: Set<Long>, ctx: String) {
        assertEquals("semanas de outubro; $ctx", WEEK_STARTS, m.weeks.map { it.start })
        assertTrue("cota ${m.limit.v} não foi pedida por ninguém; $ctx", m.limit.v in synchronized(attemptedLimits) { attemptedLimits.toSet() })
        assertEquals("Σ passos = cota (D11); $ctx; ${m.weeks}", m.limit.v, m.weeks.sumOf { it.step.v })
        assertTrue("passo negativo (§4: semana automática nunca abaixo de 0); $ctx; ${m.weeks}", m.weeks.all { it.step.v >= 0 })
        assertTrue("uma semana fica sempre automática (§4); $ctx; ${m.weeks}", m.weeks.any { !it.edited })
        assertTrue(
            "Σ editadas ≤ cota (§4); $ctx; ${m.weeks}",
            m.weeks.filter { it.edited }.sumOf { it.step.v } <= m.limit.v,
        )
    }

    // ---- ensureMonths da lista em paralelo com escritas (D30) ----

    @Test
    fun `abrir Verbas em paralelo com escritas termina com a lista coerente com o Db`() {
        val jun = LocalDate(2026, 6, 10)
        val aug = LocalDate(2026, 8, 15)
        // Criadas em junho: só têm cota em outubro depois de ensureMonths (D8, D30).
        val a = fora { db.createBudget("Aluguel", Cents(150_000), jun) }
        val b = fora { db.createBudget("Bar", Cents(30_000), jun) }
        assertNotEquals(0L, a.v)
        assertNotEquals(0L, b.v)

        val storm = inParallel {
            val limitsAndSpending = async {
                val rnd = Random(SEED)
                var limit = 150_000L
                var spentInOctober = 0L
                repeat(30) { i ->
                    if (rnd.nextBoolean()) {
                        val l = rnd.nextLong(50_000, 200_000)
                        val day = listOf(jun, aug, DIA_FIXO)[rnd.nextInt(3)]
                        // Vale do mês de `day` em diante (§3): outubro sempre recebe a última cota pedida.
                        assertTrue("seed=$SEED op=$i setLimit($l, $day)", withTimeout(OP_TIMEOUT_MS) { db.setLimit(a, Cents(l), day) })
                        limit = l
                    } else {
                        // 1/6 + 0..135 dias = de 1/6 a 14/10 (30 + 31 + 31 + 30 = 122 dias até 1/10).
                        val date = LocalDate(2026, 6, 1).plus(rnd.nextInt(0, 136), DateTimeUnit.DAY)
                        val cents = rnd.nextLong(100, 20_000)
                        val t = withTimeout(OP_TIMEOUT_MS) { db.addManual(form(a, cents, date)).transaction }
                        assertNotEquals("seed=$SEED op=$i addManual($cents, $date)", 0L, t.v)
                        if (date >= LocalDate(2026, 10, 1)) spentInOctober += cents
                    }
                    yield()
                }
                limit to spentInOctober
            }
            val budgetsComeAndGo = async {
                val c = withTimeout(OP_TIMEOUT_MS) { db.createBudget("Cinema", Cents(20_000), LocalDate(2026, 7, 3)) }
                assertNotEquals("createBudget Cinema", 0L, c.v)
                val d = withTimeout(OP_TIMEOUT_MS) { db.createBudget("Doces", Cents(10_000), DIA_FIXO) }
                assertNotEquals("createBudget Doces", 0L, d.v)
                yield()
                assertTrue("rename Cinema → Clube", withTimeout(OP_TIMEOUT_MS) { db.renameBudget(c, "Clube") })
                assertTrue("setLimit Clube", withTimeout(OP_TIMEOUT_MS) { db.setLimit(c, Cents(25_000), aug) })
                yield()
                withTimeout(OP_TIMEOUT_MS) { db.deleteBudget(d) }
                assertTrue("rename Bar → Boteco", withTimeout(OP_TIMEOUT_MS) { db.renameBudget(b, "Boteco") })
                c
            }
            // Como um widget: ensureMonths de meses passados ao mesmo tempo (até setembro, para que a
            // linha de outubro de Bar só possa vir da lista).
            val widget = async {
                val rnd = Random(SEED + 1)
                repeat(40) {
                    withTimeout(OP_TIMEOUT_MS) { db.ensureMonths(YearMonth(2026, 6 + rnd.nextInt(0, 4))) }
                    yield()
                }
            }
            widget.await()
            limitsAndSpending.await() to budgetsComeAndGo.await()
        }
        // Abrir Verbas dispara ensureMonths(outubro) enquanto as escritas correm.
        composeRule.setContent { Content() }
        await("escritas terminaram", timeout = STORM_TIMEOUT_MS + 10_000) { storm.isCompleted }
        val (aResult, c) = runBlocking { storm.await() }
        val (aLimit, aSpent) = aResult

        val expected = mapOf(a.v to ("Aluguel" to aLimit), b.v to ("Boteco" to 30_000L), c.v to ("Clube" to 25_000L))
        await("lista = Aluguel, Boteco e Clube com as cotas finais; esperado $expected") {
            rowIds() == expected.keys && expected.all { (id, nl) ->
                val row = BudgetTags.ROW + id
                shows(row, nl.first) && shows(row, moneyPlain(Cents(nl.second)))
            } && shows(BudgetTags.ROW + a.v, money(Cents(aSpent)))
        }

        // O Db, sem chamar ensureMonths aqui: a linha de outubro de Bar veio da lista.
        val overview = fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }
        assertEquals(expected.keys, overview.map { it.id.v }.toSet())
        for (s in overview) {
            assertEquals("nome de ${s.id}", expected.getValue(s.id.v).first, s.name)
            assertEquals("cota de outubro de ${s.name}", Cents(expected.getValue(s.id.v).second), s.month.limit)
        }
        assertEquals("gasto de outubro de Aluguel", Cents(aSpent), overview.single { it.id == a }.month.spent)

        // D30: a lista parada não escreve (ensureMonths não incrementa version; nada reconsulta em laço).
        val version = db.version.value
        settle()
        assertEquals("version mudou com Verbas parada", version, db.version.value)
    }

    // ---- Helpers ----

    private fun form(budget: BudgetId, cents: Long, date: LocalDate = DIA_FIXO) = TransactionForm(
        budget = budget,
        tags = listOf("teste"),
        total = Cents(cents),
        installments = 1,
        merchant = "Sacolão",
        date = date,
        time = LocalTime(10, 0),
    )

    /** Uma escrita ou leitura "por fora": outra coroutine, fora da thread da UI, com timeout. */
    private fun <T> fora(block: suspend () -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { block() } }

    /** Roda [block] em Dispatchers.Default enquanto a UI segue; a falha sai no await(). */
    private fun <T> inParallel(block: suspend CoroutineScope.() -> T): Deferred<T> =
        CoroutineScope(Dispatchers.Default).async { withTimeout(STORM_TIMEOUT_MS) { block() } }

    private fun exists(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** Text + EditableText of the (merged) node with [tag]; empty when absent. */
    private fun texts(tag: String): List<String> {
        val node = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull() ?: return emptyList()
        return node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    /** Like assertTextContains(substring = true), also accepting a value split across pieces of text. */
    private fun shows(tag: String, s: String, ignoreCase: Boolean = false): Boolean {
        val t = texts(tag)
        return t.any { it.contains(s, ignoreCase) } || t.joinToString("").contains(s, ignoreCase)
    }

    private fun rowIds(): Set<Long> = composeRule
        .onAllNodes(SemanticsMatcher("tag starts with ${BudgetTags.ROW}") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(BudgetTags.ROW) == true })
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix(BudgetTags.ROW)?.toLongOrNull() }
        .toSet()

    /** Waits for [cond]; on timeout, fails with [what] and the texts of [show]. */
    private fun await(what: String, vararg show: String, timeout: Long = UI_TIMEOUT_MS, cond: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeout) { cond() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what; na tela: " + show.joinToString { "$it=${texts(it)}" }, e)
        }
    }

    /** Lets the IO queries come back and the UI settle; an exception in the composition comes out here. */
    private fun settle() = repeat(20) {
        Thread.sleep(25)
        composeRule.waitForIdle()
    }

    private fun openDetail(id: BudgetId) {
        val row = BudgetTags.ROW + id.v
        await("linha da verba ${id.v}") { exists(row) }
        composeRule.onNodeWithTag(row).performClick()
        await("detalhe da verba ${id.v}") { exists(BudgetTags.DETAIL_TITLE) }
    }

    private fun pressBack() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    /** Back to Verbas unless the screen already went there by itself (pressing back on Verbas would leave the app). */
    private fun backToBudgets() {
        if (!exists(BudgetTags.LIST) && !exists(BudgetTags.EMPTY)) {
            pressBack()
            settle()
        }
    }

    private companion object {
        /** Quarta, 14 de outubro de 2026. */
        val DIA_FIXO = LocalDate(2026, 10, 14)
        val OUT = YearMonth(2026, 10)
        /** §4: semanas de outubro/2026 começando na segunda, cortadas no mês. */
        val WEEK_STARTS = listOf(1, 5, 12, 19, 26).map { LocalDate(2026, 10, it) }
        val W12 = BudgetTags.WEEK + "2026-10-12"
        val W26 = BudgetTags.WEEK + "2026-10-26"

        const val SEED = 20_261_014L
        const val WORKERS = 4
        const val OPS_PER_WORKER = 25
        const val OP_TIMEOUT_MS = 10_000L
        const val STORM_TIMEOUT_MS = 60_000L
        const val UI_TIMEOUT_MS = 10_000L
    }
}
