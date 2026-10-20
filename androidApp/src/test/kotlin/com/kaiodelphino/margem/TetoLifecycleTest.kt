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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.Screen
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.moneyParts
import com.kaiodelphino.margem.widgets.moneyPlain
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
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
 * Teto (spec §3, §7.1; D42, D46), concorrência e ciclo de vida: o cabeçalho de Verbas segue o Db quando o
 * teto ou uma cota muda por fora (D6, D19); o diálogo do teto aberto enquanto o Db muda (a dica segue o
 * Db, o que se grava é o digitado); o estado do diálogo sobrevive à restauração (rememberSaveable);
 * Desfazer do snackbar funciona depois de sair de Verbas (ações no escopo do App, SnackHost); verba
 * apagada com o diálogo ou Editar verba abertos; "Passar do teto?" e Voltar não gravam nada; toque duplo
 * numa opção grava uma vez; a virada do mês (LocalToday avança) volta ao padrão depois de "só este mês".
 *
 * Calendário: 14/10/2026 (quarta); mês começa no dia 1 (padrão de Ajustes), outubro = mês atual.
 * Dígitos nos campos de dinheiro são centavos ("435000" = R$ 4.350,00, BudgetTags).
 *
 * Plain Application: MargemApp abriria o Db de produção com o driver bundled (D28). Tela alta para o
 * formulário e o diálogo caberem sem rolagem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h1800dp")
class TetoLifecycleTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    /** Tira o App da composição antes de fechar o Db, para nenhuma consulta rodar num Db fechado. */
    private var show by mutableStateOf(true)

    /** App.tabRequest: abre uma aba como os widgets fazem (a barra de abas não tem tags). */
    private var tab by mutableStateOf<Screen?>(null)

    /** O dia de LocalToday; mudar vira o mês com o app aberto. */
    private var day by mutableStateOf(DIA_FIXO)

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
        val d = day
        MargemTheme(dark = false) {
            CompositionLocalProvider(LocalToday provides { d }) { App(db, tab) { tab = null } }
        }
    }

    // ---- Cabeçalho segue o Db (D6, D19) ----

    @Test
    fun `cabeçalho segue teto e cotas mudados por fora com Verbas aberta`() {
        val mercado = budget("Mercado", 200_000)
        val casa = budget("Casa", 172_000)
        composeRule.setContent { Content() }

        // Sem teto (§7.1): Σ cotas = 2.000,00 + 1.720,00 = 3.720,00.
        await("sem teto: 'As cotas somam R$ 3.720,00.' e 'Pôr um teto'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "As cotas somam ${money(Cents(372_000))}.") && shows(BudgetTags.CEILING, "Pôr um teto") &&
                described(BudgetTags.CEILING, "As cotas de outubro somam ${money(Cents(372_000))}. Sem teto. Tocar para pôr um teto.")
        }

        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        // Abaixo: 4.100,00 − 3.720,00 = 380,00 do teto.
        await("teto 4.100,00 em diante, a 380,00 do teto", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "cotas ${money(Cents(372_000))}") && shows(BudgetTags.CEILING, "teto ${money(Cents(410_000))}") &&
                !shows(BudgetTags.CEILING, "só outubro") && !shows(BudgetTags.CEILING, "Pôr um teto") &&
                described(BudgetTags.CEILING, "Teto de outubro: ${money(Cents(410_000))}. Cotas somam ${money(Cents(372_000))}, a ${money(Cents(38_000))} do teto. Tocar para mudar.")
        }

        assertTrue(fora { db.setCeiling(Cents(440_000), onward = false, today = DIA_FIXO) })
        // Só este mês: o do mês (4.400,00) difere do padrão (4.100,00) → "· só outubro".
        await("teto 4.400,00 só outubro", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "teto ${money(Cents(440_000))}") && shows(BudgetTags.CEILING, "só outubro") &&
                described(BudgetTags.CEILING, "a ${money(Cents(68_000))} do teto")
        }

        assertTrue(fora { db.setLimit(mercado, Cents(268_000), DIA_FIXO) })
        // 2.680,00 + 1.720,00 = 4.400,00 = teto: "fecham no teto", sem excesso.
        await("cotas fecham no teto", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "cotas ${money(Cents(440_000))}") && !shows(BudgetTags.CEILING, "+ ") &&
                described(BudgetTags.CEILING, "Teto de outubro: ${money(Cents(440_000))}. Cotas somam ${money(Cents(440_000))}, fecham no teto. Tocar para mudar.")
        }

        assertTrue(fora { db.setLimit(mercado, Cents(298_000), DIA_FIXO) })
        // 2.980,00 + 1.720,00 = 4.700,00: 300,00 além; legenda "+ 300" (reais inteiros).
        await("cotas acima: '+ 300'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "cotas ${money(Cents(470_000))}") && shows(BudgetTags.CEILING, "+ 300") &&
                !shows(BudgetTags.CEILING, "+ 300,") &&
                described(BudgetTags.CEILING, "Cotas somam ${money(Cents(470_000))}, ${money(Cents(30_000))} além do teto. Tocar para mudar.")
        }

        assertTrue(fora { db.setLimit(casa, Cents(172_050), DIA_FIXO) })
        // 2.980,00 + 1.720,50 = 4.700,50: "+ 300,50" (com centavos quando há).
        await("cotas acima com centavos: '+ 300,50'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "cotas ${money(Cents(470_050))}") && shows(BudgetTags.CEILING, "+ 300,50") &&
                described(BudgetTags.CEILING, "${money(Cents(30_050))} além do teto")
        }

        assertTrue(fora { db.setCeiling(Cents(0), onward = true, today = DIA_FIXO) })
        await("teto tirado em diante: de novo 'Pôr um teto'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "As cotas somam ${money(Cents(470_050))}.") && shows(BudgetTags.CEILING, "Pôr um teto") &&
                !shows(BudgetTags.CEILING, "só outubro")
        }

        fora { db.deleteBudget(mercado) }
        fora { db.deleteBudget(casa) }
        // Sem verbas, a linha some (§7.1).
        await("sem verbas a linha do teto some") { exists(BudgetTags.EMPTY) && !exists(BudgetTags.CEILING) }
    }

    @Test
    fun `linha do teto aparece com a primeira verba criada por fora e o cabeçalho sem verba não abre nada`() {
        assertTrue(fora { db.setCeiling(Cents(100_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        await("Verbas vazia, sem linha do teto") { exists(BudgetTags.EMPTY) && exists(BudgetTags.HEADER) }
        assertFalse("sem verba não há linha do teto", exists(BudgetTags.CEILING))
        // BudgetTags.CEILING: sem verba, HEADER não abre nada.
        tap(BudgetTags.HEADER)
        settle()
        assertFalse("o cabeçalho sem verba abriu o diálogo", exists(BudgetTags.CEILING_DIALOG))

        val id = budget("Lazer", 120_000)
        // 1.200,00 > 1.000,00: 200,00 além.
        await("linha do teto com a verba nova: '+ 200'", BudgetTags.CEILING) {
            exists(BudgetTags.ROW + id.v) && shows(BudgetTags.CEILING, "cotas ${money(Cents(120_000))}") &&
                shows(BudgetTags.CEILING, "teto ${money(Cents(100_000))}") && shows(BudgetTags.CEILING, "+ 200")
        }
        // Com verba, o cabeçalho é o mesmo alvo da linha (§7.1).
        tap(BudgetTags.HEADER)
        await("HEADER abre o diálogo do teto", BudgetTags.CEILING_DIALOG) { shows(BudgetTags.CEILING_DIALOG, "Teto de outubro") }
        tap(BudgetTags.CEILING_CANCEL)
        await("Cancelar fecha") { !exists(BudgetTags.CEILING_DIALOG) }
        assertEquals(Ceiling(OUT, Cents(100_000), Cents(100_000), Cents(120_000)), fora { db.ceiling(OUT) })
    }

    @Test
    fun `tempestade de setCeiling e setLimit por fora - o cabeçalho termina igual ao Db`() {
        val a = budget("Mercado", 200_000)
        val b = budget("Casa", 172_000)
        composeRule.setContent { Content() }
        await("linha do teto") { exists(BudgetTags.CEILING) }

        val attempted = Collections.synchronizedSet(mutableSetOf(0L))
        val storm = inParallel {
            (0 until WORKERS).map { w ->
                async {
                    val rnd = Random(SEED + w)
                    repeat(OPS_PER_WORKER) { i ->
                        if (rnd.nextInt(100) < 60) {
                            val cents = if (rnd.nextInt(10) == 0) 0L else rnd.nextLong(1, 800_000)
                            attempted += cents
                            val ok = withTimeout(OP_TIMEOUT_MS) { db.setCeiling(Cents(cents), rnd.nextBoolean(), DIA_FIXO) }
                            assertTrue("seed=${SEED + w} op=$i setCeiling($cents)", ok)
                        } else {
                            val id = if (rnd.nextBoolean()) a else b
                            val limit = rnd.nextLong(1, 400_000)
                            assertTrue("seed=${SEED + w} op=$i setLimit($limit)", withTimeout(OP_TIMEOUT_MS) { db.setLimit(id, Cents(limit), DIA_FIXO) })
                        }
                        // Retrato consistente (D29): o teto e o padrão lidos foram pedidos por alguém.
                        val c = withTimeout(OP_TIMEOUT_MS) { db.ceiling(OUT) }
                        val tried = synchronized(attempted) { attempted.toSet() }
                        assertTrue("teto ${c.cents.v} não pedido; seed=${SEED + w} op=$i", c.cents.v in tried)
                        assertTrue("padrão ${c.standard.v} não pedido; seed=${SEED + w} op=$i", c.standard.v in tried)
                        yield()
                    }
                }
            }.awaitAll()
        }
        await("escritas terminaram", timeout = STORM_TIMEOUT_MS + 10_000) { storm.isCompleted }
        runBlocking { storm.await() }

        val final = fora { db.ceiling(OUT) }
        val limits = fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.sumOf { it.month.limit.v }
        assertEquals("Ceiling.limits = Σ cotas do mês", limits, final.limits.v)
        await("cabeçalho = Db final $final", BudgetTags.CEILING) { headerMatches(final) }

        // D30: Verbas parada não escreve.
        val version = db.version.value
        settle()
        assertEquals("version mudou com Verbas parada", version, db.version.value)
    }

    // ---- Diálogo aberto enquanto o Db muda por fora ----

    @Test
    fun `diálogo aberto - a dica segue as cotas mudadas por fora e grava o valor digitado`() {
        val mercado = budget("Mercado", 372_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        openCeilingDialog()
        // O campo começa com o teto do mês: dica a 380,00 do teto.
        await("dica com o teto do mês", BudgetTags.CEILING_HINT) {
            shows(BudgetTags.CEILING_HINT, "As cotas de outubro somam ${money(Cents(372_000))}: ficam a ${money(Cents(38_000))} do teto.")
        }
        assertTrue("Tirar teto aparece com teto no mês", exists(BudgetTags.CEILING_REMOVE))

        composeRule.onNodeWithTag(BudgetTags.CEILING_FIELD).performTextReplacement("435000")
        // 4.350,00 − 3.720,00 = 630,00.
        await("dica com 4.350,00 digitados", BudgetTags.CEILING_HINT) {
            shows(BudgetTags.CEILING_HINT, "As cotas de outubro somam ${money(Cents(372_000))}: ficam a ${money(Cents(63_000))} do teto.")
        }

        assertTrue(fora { db.setLimit(mercado, Cents(450_000), DIA_FIXO) })
        // 4.500,00 − 4.350,00 = 150,00 além.
        await("dica segue a cota mudada por fora", BudgetTags.CEILING_HINT) {
            shows(BudgetTags.CEILING_HINT, "As cotas de outubro somam ${money(Cents(450_000))}: ficariam ${money(Cents(15_000))} além do teto. Dá para salvar assim.")
        }

        assertTrue(fora { db.setLimit(mercado, Cents(435_000), DIA_FIXO) })
        await("dica no teto exato", BudgetTags.CEILING_HINT) {
            shows(BudgetTags.CEILING_HINT, "As cotas de outubro somam ${money(Cents(435_000))}: fecham no teto.")
        }

        // Padrão mudado por fora depois de digitar: o que se grava é o digitado.
        assertTrue(fora { db.setCeiling(Cents(380_000), onward = true, today = DIA_FIXO) })
        settle()
        tap(BudgetTags.CEILING_SAVE)
        await("Vale para quais meses?", BudgetTags.CEILING_SCOPE) { shows(BudgetTags.CEILING_SCOPE, "Vale para quais meses?") }
        await("'Só em outubro' com o padrão de agora", BudgetTags.CEILING_ONLY) {
            shows(BudgetTags.CEILING_ONLY, "Só em outubro") && shows(BudgetTags.CEILING_ONLY, "Em novembro, volta a ${money(Cents(380_000))}.")
        }
        assertTrue(shows(BudgetTags.CEILING_ONWARD, "De outubro em diante"))
        tap(BudgetTags.CEILING_ONLY)

        await("diálogo fechado e snack", SnackTags.TEXT) {
            !exists(BudgetTags.CEILING_DIALOG) && shows(SnackTags.TEXT, "Teto de ${money(Cents(435_000))} só em outubro.") && exists(SnackTags.ACTION)
        }
        assertEquals(Ceiling(OUT, Cents(435_000), Cents(380_000), Cents(435_000)), fora { db.ceiling(OUT) })
        await("cabeçalho 'teto R$ 4.350,00 · só outubro'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "teto ${money(Cents(435_000))}") && shows(BudgetTags.CEILING, "só outubro")
        }
    }

    @Test
    fun `diálogo aberto - teto tirado por fora some com Tirar teto e a sub-linha diz que volta a não ter teto`() {
        budget("Mercado", 200_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        openCeilingDialog()
        await("Tirar teto no mês com teto") { exists(BudgetTags.CEILING_REMOVE) }

        assertTrue(fora { db.setCeiling(Cents(0), onward = true, today = DIA_FIXO) })
        // CEILING_REMOVE: só quando o mês tem teto.
        await("Tirar teto some com o teto tirado por fora") { !exists(BudgetTags.CEILING_REMOVE) }

        composeRule.onNodeWithTag(BudgetTags.CEILING_FIELD).performTextReplacement("300000")
        await("dica com 3.000,00", BudgetTags.CEILING_HINT) {
            shows(BudgetTags.CEILING_HINT, "As cotas de outubro somam ${money(Cents(200_000))}: ficam a ${money(Cents(100_000))} do teto.")
        }
        tap(BudgetTags.CEILING_SAVE)
        await("'Só em outubro' sem padrão", BudgetTags.CEILING_ONLY) {
            shows(BudgetTags.CEILING_ONLY, "Em novembro, volta a não ter teto.")
        }
        tap(BudgetTags.CEILING_ONLY)
        await("snack só em outubro", SnackTags.TEXT) { shows(SnackTags.TEXT, "Teto de ${money(Cents(300_000))} só em outubro.") }
        assertEquals(Ceiling(OUT, Cents(300_000), Cents(0), Cents(200_000)), fora { db.ceiling(OUT) })
    }

    @Test
    fun `verba apagada por fora com o diálogo aberto - a dica segue e salvar grava`() {
        budget("Mercado", 200_000)
        val casa = budget("Casa", 172_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        openCeilingDialog()
        await("dica com as duas verbas", BudgetTags.CEILING_HINT) {
            shows(BudgetTags.CEILING_HINT, "somam ${money(Cents(372_000))}: ficam a ${money(Cents(38_000))} do teto.")
        }

        fora { db.deleteBudget(casa) }
        settle() // uma exceção na composição sairia aqui
        // Sobra Mercado: 4.100,00 − 2.000,00 = 2.100,00.
        await("dica sem Casa", BudgetTags.CEILING_HINT) {
            shows(BudgetTags.CEILING_HINT, "As cotas de outubro somam ${money(Cents(200_000))}: ficam a ${money(Cents(210_000))} do teto.")
        }

        composeRule.onNodeWithTag(BudgetTags.CEILING_FIELD).performTextReplacement("300000")
        tap(BudgetTags.CEILING_SAVE)
        await("pergunta dos meses") { exists(BudgetTags.CEILING_ONWARD) }
        tap(BudgetTags.CEILING_ONWARD)
        await("snack em diante", SnackTags.TEXT) { shows(SnackTags.TEXT, "Teto de ${money(Cents(300_000))} de outubro em diante.") }
        assertEquals(Ceiling(OUT, Cents(300_000), Cents(300_000), Cents(200_000)), fora { db.ceiling(OUT) })
        await("cabeçalho 'teto R$ 3.000,00' sem 'só outubro'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "cotas ${money(Cents(200_000))}") && shows(BudgetTags.CEILING, "teto ${money(Cents(300_000))}") &&
                !shows(BudgetTags.CEILING, "só outubro")
        }
    }

    // ---- Restauração (rememberSaveable) ----

    @Test
    fun `valor digitado e passo dos meses sobrevivem à restauração`() {
        budget("Mercado", 372_000)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openCeilingDialog()
        // Sem teto: campo zerado, Salvar desabilitado, sem dica e sem Tirar teto.
        composeRule.onNodeWithTag(BudgetTags.CEILING_SAVE).assertIsNotEnabled()
        assertFalse("sem dica no zero", exists(BudgetTags.CEILING_HINT))
        assertFalse("sem Tirar teto sem teto", exists(BudgetTags.CEILING_REMOVE))

        composeRule.onNodeWithTag(BudgetTags.CEILING_FIELD).performTextInput("400000")
        // 4.000,00 − 3.720,00 = 280,00.
        val hint = "As cotas de outubro somam ${money(Cents(372_000))}: ficam a ${money(Cents(28_000))} do teto."
        await("dica com 4.000,00", BudgetTags.CEILING_HINT) { shows(BudgetTags.CEILING_HINT, hint) }

        restore.emulateSavedInstanceStateRestore()

        await("diálogo e valor digitado depois da restauração", BudgetTags.CEILING_HINT) {
            shows(BudgetTags.CEILING_DIALOG, "Teto de outubro") && shows(BudgetTags.CEILING_HINT, hint)
        }
        composeRule.onNodeWithTag(BudgetTags.CEILING_SAVE).assertIsEnabled()
        tap(BudgetTags.CEILING_SAVE)
        await("Vale para quais meses?", BudgetTags.CEILING_SCOPE) { shows(BudgetTags.CEILING_SCOPE, "Vale para quais meses?") }

        restore.emulateSavedInstanceStateRestore()

        await("ainda na pergunta dos meses depois da restauração", BudgetTags.CEILING_SCOPE, BudgetTags.CEILING_ONLY) {
            shows(BudgetTags.CEILING_SCOPE, "Vale para quais meses?") &&
                shows(BudgetTags.CEILING_ONWARD, "Vira o teto dos próximos meses.") &&
                shows(BudgetTags.CEILING_ONLY, "Em novembro, volta a não ter teto.")
        }
        assertEquals("nada gravado antes de escolher", Ceiling(OUT, Cents(0), Cents(0), Cents(372_000)), fora { db.ceiling(OUT) })

        // Voltar: de volta ao valor, com o digitado; nada gravado.
        tap(BudgetTags.CEILING_BACK)
        await("Voltar mantém o valor", BudgetTags.CEILING_HINT) { !exists(BudgetTags.CEILING_SCOPE) && shows(BudgetTags.CEILING_HINT, hint) }
        assertEquals(Ceiling(OUT, Cents(0), Cents(0), Cents(372_000)), fora { db.ceiling(OUT) })

        tap(BudgetTags.CEILING_SAVE)
        await("pergunta dos meses de novo") { exists(BudgetTags.CEILING_ONWARD) }
        tap(BudgetTags.CEILING_ONWARD)
        await("snack em diante", SnackTags.TEXT) { shows(SnackTags.TEXT, "Teto de ${money(Cents(400_000))} de outubro em diante.") }
        assertEquals(Ceiling(OUT, Cents(400_000), Cents(400_000), Cents(372_000)), fora { db.ceiling(OUT) })
    }

    @Test
    fun `pergunta de tirar o teto sobrevive à restauração e Só em outubro tira só o mês`() {
        budget("Mercado", 372_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openCeilingDialog()
        await("Tirar teto") { exists(BudgetTags.CEILING_REMOVE) }
        tap(BudgetTags.CEILING_REMOVE)
        await("Tirar o teto de quais meses?", BudgetTags.CEILING_SCOPE, BudgetTags.CEILING_ONWARD) {
            shows(BudgetTags.CEILING_SCOPE, "Tirar o teto de quais meses?") &&
                shows(BudgetTags.CEILING_ONWARD, "Os próximos meses ficam sem teto.")
        }

        restore.emulateSavedInstanceStateRestore()

        await("ainda na pergunta de tirar depois da restauração", BudgetTags.CEILING_SCOPE, BudgetTags.CEILING_ONLY) {
            shows(BudgetTags.CEILING_SCOPE, "Tirar o teto de quais meses?") &&
                shows(BudgetTags.CEILING_ONWARD, "De outubro em diante") &&
                shows(BudgetTags.CEILING_ONLY, "Só em outubro") &&
                shows(BudgetTags.CEILING_ONLY, "Em novembro, volta a ${money(Cents(410_000))}.")
        }
        assertEquals("nada gravado antes de escolher", Ceiling(OUT, Cents(410_000), Cents(410_000), Cents(372_000)), fora { db.ceiling(OUT) })

        tap(BudgetTags.CEILING_ONLY)
        await("snack 'Outubro sem teto.'", SnackTags.TEXT) { shows(SnackTags.TEXT, "Outubro sem teto.") && exists(SnackTags.ACTION) }
        assertEquals(Ceiling(OUT, Cents(0), Cents(410_000), Cents(372_000)), fora { db.ceiling(OUT) })
        // T10: mês sem teto com padrão que tem → o mesmo "sem teto", sem menção ao padrão.
        await("cabeçalho sem teto, sem menção ao padrão", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "As cotas somam ${money(Cents(372_000))}.") && shows(BudgetTags.CEILING, "Pôr um teto") &&
                !shows(BudgetTags.CEILING, money(Cents(410_000))) && !shows(BudgetTags.CEILING, "só outubro")
        }
    }

    // ---- Desfazer depois de sair de Verbas (SnackHost no escopo do App) ----

    @Test
    fun `Desfazer de em diante funciona depois de trocar de aba e volta padrão e mês`() {
        budget("Mercado", 372_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        assertTrue(fora { db.setCeiling(Cents(380_000), onward = false, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        await("cabeçalho 'teto R$ 3.800,00 · só outubro'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "teto ${money(Cents(380_000))}") && shows(BudgetTags.CEILING, "só outubro")
        }
        openCeilingDialog()
        composeRule.onNodeWithTag(BudgetTags.CEILING_FIELD).performTextReplacement("450000")
        tap(BudgetTags.CEILING_SAVE)
        await("pergunta dos meses") { exists(BudgetTags.CEILING_ONWARD) }
        tap(BudgetTags.CEILING_ONWARD)
        await("snack em diante com Desfazer", SnackTags.TEXT) {
            shows(SnackTags.TEXT, "Teto de ${money(Cents(450_000))} de outubro em diante.") && exists(SnackTags.ACTION)
        }
        assertEquals(Ceiling(OUT, Cents(450_000), Cents(450_000), Cents(372_000)), fora { db.ceiling(OUT) })

        tab = Screen.Insights
        await("fora de Verbas") { !exists(BudgetTags.CEILING) && !exists(BudgetTags.HEADER) }
        assertTrue("snack ainda na tela na Leitura", exists(SnackTags.ACTION))
        tap(SnackTags.ACTION)
        // D46: Desfazer = padrão de antes (4.100,00) e teto do mês de antes (3.800,00).
        await("Desfazer volta padrão e mês") {
            fora { db.ceiling(OUT) } == Ceiling(OUT, Cents(380_000), Cents(410_000), Cents(372_000))
        }

        tab = Screen.Budgets
        await("cabeçalho de volta a 'teto R$ 3.800,00 · só outubro'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "teto ${money(Cents(380_000))}") && shows(BudgetTags.CEILING, "só outubro")
        }
    }

    @Test
    fun `Desfazer de tirar só outubro funciona do detalhe da verba`() {
        val id = budget("Mercado", 372_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        openCeilingDialog()
        await("Tirar teto") { exists(BudgetTags.CEILING_REMOVE) }
        tap(BudgetTags.CEILING_REMOVE)
        await("pergunta de tirar") { exists(BudgetTags.CEILING_ONLY) }
        tap(BudgetTags.CEILING_ONLY)
        await("snack 'Outubro sem teto.'", SnackTags.TEXT) { shows(SnackTags.TEXT, "Outubro sem teto.") && exists(SnackTags.ACTION) }
        assertEquals(Ceiling(OUT, Cents(0), Cents(410_000), Cents(372_000)), fora { db.ceiling(OUT) })

        tap(BudgetTags.ROW + id.v)
        await("detalhe aberto", BudgetTags.DETAIL_TITLE) { shows(BudgetTags.DETAIL_TITLE, "Mercado") }
        assertTrue("snack ainda na tela no detalhe", exists(SnackTags.ACTION))
        tap(SnackTags.ACTION)
        await("Desfazer volta o teto de outubro") {
            fora { db.ceiling(OUT) } == Ceiling(OUT, Cents(410_000), Cents(410_000), Cents(372_000))
        }
        pressBack()
        await("cabeçalho com teto de novo, sem 'só outubro'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "teto ${money(Cents(410_000))}") && !shows(BudgetTags.CEILING, "só outubro")
        }
    }

    // ---- Toque duplo numa opção ----

    @Test
    fun `toque duplo rápido numa opção grava uma vez e o Desfazer volta ao de antes`() {
        budget("Mercado", 372_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        assertTrue(fora { db.setCeiling(Cents(380_000), onward = false, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        openCeilingDialog()
        composeRule.onNodeWithTag(BudgetTags.CEILING_FIELD).performTextReplacement("450000")
        tap(BudgetTags.CEILING_SAVE)
        await("pergunta dos meses") { exists(BudgetTags.CEILING_ONWARD) }

        composeRule.onNodeWithTag(BudgetTags.CEILING_ONWARD).performTouchInput {
            click()
            advanceEventTime(40)
            click()
        }
        await("diálogo fechado e um snack", SnackTags.TEXT) {
            !exists(BudgetTags.CEILING_DIALOG) && shows(SnackTags.TEXT, "Teto de ${money(Cents(450_000))} de outubro em diante.")
        }
        settle()
        assertEquals("um snack só", 1, composeRule.onAllNodesWithTag(SnackTags.TEXT).fetchSemanticsNodes().size)
        assertEquals(Ceiling(OUT, Cents(450_000), Cents(450_000), Cents(372_000)), fora { db.ceiling(OUT) })

        // Uma segunda gravação que lesse o "antes" depois da primeira faria o Desfazer voltar a 4.500,00.
        tap(SnackTags.ACTION)
        await("Desfazer volta ao de antes do toque duplo") {
            fora { db.ceiling(OUT) } == Ceiling(OUT, Cents(380_000), Cents(410_000), Cents(372_000))
        }
    }

    // ---- Nova verba e Editar verba com o Db mudando por fora ----

    @Test
    fun `Nova verba - o aviso segue o Db e Voltar de Passar do teto não grava nada`() {
        val mercado = budget("Mercado", 300_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        await("+") { exists(BudgetTags.ADD) }
        tap(BudgetTags.ADD)
        await("Nova verba") { exists(BudgetTags.FORM_NAME) && exists(BudgetTags.FORM_LIMIT) }
        composeRule.onNodeWithTag(BudgetTags.FORM_NAME).performTextInput("Viagem")
        composeRule.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextReplacement("100000")
        // 3.000,00 + 1.000,00 = 4.000,00 ≤ 4.100,00: sem aviso.
        settle()
        assertFalse("sem aviso abaixo do teto: ${texts(BudgetTags.FORM_CEILING)}", exists(BudgetTags.FORM_CEILING))

        assertTrue(fora { db.setLimit(mercado, Cents(350_000), DIA_FIXO) })
        // 3.500,00 + 1.000,00 = 4.500,00: 400,00 além.
        val warn = "Com esta cota, as cotas de outubro somam ${money(Cents(450_000))}: ${money(Cents(40_000))} além do teto."
        await("aviso segue a cota mudada por fora", BudgetTags.FORM_CEILING) { shows(BudgetTags.FORM_CEILING, warn) }

        tap(BudgetTags.FORM_SUBMIT)
        await("Passar do teto?", BudgetTags.CEILING_WARN) {
            shows(BudgetTags.CEILING_WARN, "Passar do teto?") &&
                shows(
                    BudgetTags.CEILING_WARN,
                    "As cotas de outubro somariam ${money(Cents(450_000))}, ${money(Cents(40_000))} além do teto de ${money(Cents(410_000))}. O teto é só um limite de planejamento.",
                )
        }
        assertTrue(shows(BudgetTags.CEILING_WARN_CONFIRM, "Criar mesmo assim"))
        assertTrue(shows(BudgetTags.CEILING_WARN_CANCEL, "Voltar"))
        val version = db.version.value
        tap(BudgetTags.CEILING_WARN_CANCEL)
        await("Voltar fecha e fica no formulário") { !exists(BudgetTags.CEILING_WARN) && exists(BudgetTags.FORM_NAME) }
        settle()
        assertEquals("Voltar não grava nada", version, db.version.value)
        assertEquals(listOf("Mercado"), fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.map { it.name })

        // Teto subido por fora: a soma já não passa, o aviso some e criar não pergunta.
        assertTrue(fora { db.setCeiling(Cents(500_000), onward = true, today = DIA_FIXO) })
        await("aviso some com o teto subido por fora") { !exists(BudgetTags.FORM_CEILING) }
        tap(BudgetTags.FORM_SUBMIT)
        await("Viagem criada sem perguntar") {
            fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.any { it.name == "Viagem" }
        }
        assertFalse("não perguntou", exists(BudgetTags.CEILING_WARN))
        await("de volta a Verbas: cotas 4.500,00, teto 5.000,00", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "cotas ${money(Cents(450_000))}") && shows(BudgetTags.CEILING, "teto ${money(Cents(500_000))}") &&
                !shows(BudgetTags.CEILING, "+ ")
        }
    }

    @Test
    fun `Nova verba - Criar mesmo assim cria e o cabeçalho mostra o excesso`() {
        budget("Mercado", 350_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        await("+") { exists(BudgetTags.ADD) }
        tap(BudgetTags.ADD)
        await("Nova verba") { exists(BudgetTags.FORM_NAME) }
        composeRule.onNodeWithTag(BudgetTags.FORM_NAME).performTextInput("Viagem")
        composeRule.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextReplacement("100000")
        await("aviso", BudgetTags.FORM_CEILING) {
            shows(BudgetTags.FORM_CEILING, "Com esta cota, as cotas de outubro somam ${money(Cents(450_000))}: ${money(Cents(40_000))} além do teto.")
        }
        tap(BudgetTags.FORM_SUBMIT)
        await("Passar do teto?") { exists(BudgetTags.CEILING_WARN_CONFIRM) }
        tap(BudgetTags.CEILING_WARN_CONFIRM)
        await("Viagem criada") { fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.any { it.name == "Viagem" } }
        assertEquals(Ceiling(OUT, Cents(410_000), Cents(410_000), Cents(450_000)), fora { db.ceiling(OUT) })
        await("cabeçalho '+ 400'", BudgetTags.CEILING) { shows(BudgetTags.CEILING, "+ 400") && !shows(BudgetTags.CEILING, "+ 400,") }
    }

    @Test
    fun `Editar verba - outra verba apagada por fora tira o aviso e Salvar grava sem perguntar`() {
        budget("Mercado", 200_000)
        val casa = budget("Casa", 172_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        openEdit(casa)
        composeRule.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextReplacement("250000")
        // 2.000,00 + 2.500,00 = 4.500,00 > 4.100,00, e a soma sobe (3.720,00 → 4.500,00).
        await("aviso em Editar verba", BudgetTags.FORM_CEILING) {
            shows(BudgetTags.FORM_CEILING, "Com esta cota, as cotas de outubro somam ${money(Cents(450_000))}: ${money(Cents(40_000))} além do teto.")
        }

        val mercado = fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.single { it.name == "Mercado" }.id
        fora { db.deleteBudget(mercado) }
        // Sobra Casa: 2.500,00 ≤ 4.100,00.
        await("aviso some sem Mercado") { !exists(BudgetTags.FORM_CEILING) }

        tap(BudgetTags.FORM_SUBMIT)
        await("Casa com cota 2.500,00") {
            fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.singleOrNull()?.month?.limit == Cents(250_000)
        }
        assertFalse("não perguntou", exists(BudgetTags.CEILING_WARN))
    }

    @Test
    fun `Editar verba - Voltar de Passar do teto não grava nome nem cota`() {
        val id = budget("Mercado", 372_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        openEdit(id)
        composeRule.onNodeWithTag(BudgetTags.FORM_NAME).performTextReplacement("Feira")
        composeRule.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextReplacement("440000")
        // 4.400,00 − 4.100,00 = 300,00 além.
        await("aviso", BudgetTags.FORM_CEILING) {
            shows(BudgetTags.FORM_CEILING, "Com esta cota, as cotas de outubro somam ${money(Cents(440_000))}: ${money(Cents(30_000))} além do teto.")
        }
        tap(BudgetTags.FORM_SUBMIT)
        await("Passar do teto?", BudgetTags.CEILING_WARN) {
            shows(BudgetTags.CEILING_WARN, "Passar do teto?") &&
                shows(BudgetTags.CEILING_WARN, "As cotas de outubro somariam ${money(Cents(440_000))}, ${money(Cents(30_000))} além do teto de ${money(Cents(410_000))}.")
        }
        assertTrue(shows(BudgetTags.CEILING_WARN_CONFIRM, "Salvar mesmo assim"))
        val version = db.version.value
        tap(BudgetTags.CEILING_WARN_CANCEL)
        await("fica no formulário") { !exists(BudgetTags.CEILING_WARN) && exists(BudgetTags.FORM_LIMIT) }
        settle()
        assertEquals("Voltar não grava nada", version, db.version.value)
        val row = fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.single()
        assertEquals("Mercado", row.name)
        assertEquals(Cents(372_000), row.month.limit)

        tap(BudgetTags.FORM_SUBMIT)
        await("pergunta de novo") { exists(BudgetTags.CEILING_WARN_CONFIRM) }
        tap(BudgetTags.CEILING_WARN_CONFIRM)
        await("Salvar mesmo assim grava nome e cota") {
            fora { db.budgetsOverview(OUT, DIA_FIXO, BudgetOrder.RISK) }.single().let { it.name == "Feira" && it.month.limit == Cents(440_000) }
        }
        await("de volta ao detalhe", BudgetTags.DETAIL_TITLE) { shows(BudgetTags.DETAIL_TITLE, "Feira") }
    }

    @Test
    fun `Editar verba - a própria verba apagada por fora não quebra e não mexe no teto`() {
        val id = budget("Mercado", 300_000)
        assertTrue(fora { db.setCeiling(Cents(310_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        openEdit(id)
        composeRule.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextReplacement("350000")
        await("aviso", BudgetTags.FORM_CEILING) {
            shows(BudgetTags.FORM_CEILING, "Com esta cota, as cotas de outubro somam ${money(Cents(350_000))}: ${money(Cents(40_000))} além do teto.")
        }

        fora { db.deleteBudget(id) }
        settle()
        if (exists(BudgetTags.FORM_SUBMIT)) {
            tap(BudgetTags.FORM_SUBMIT)
            settle()
        }
        if (exists(BudgetTags.CEILING_WARN_CONFIRM)) {
            tap(BudgetTags.CEILING_WARN_CONFIRM)
            settle()
        }
        if (exists(BudgetTags.LIMIT_DIALOG_CONFIRM)) {
            tap(BudgetTags.LIMIT_DIALOG_CONFIRM)
            settle()
        }
        // Só setCeiling muda o teto (D46): teto e padrão continuam 3.100,00.
        val c = fora { db.ceiling(OUT) }
        assertEquals(Cents(310_000), c.cents)
        assertEquals(Cents(310_000), c.standard)
    }

    // ---- Virada do mês com o app aberto (D42) ----

    @Test
    fun `virada do mês depois de só outubro mostra o padrão e só novembro sem teto volta em dezembro`() {
        budget("Mercado", 200_000)
        budget("Casa", 172_000)
        assertTrue(fora { db.setCeiling(Cents(410_000), onward = true, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        openCeilingDialog()
        composeRule.onNodeWithTag(BudgetTags.CEILING_FIELD).performTextReplacement("440000")
        tap(BudgetTags.CEILING_SAVE)
        await("Só em outubro volta ao padrão", BudgetTags.CEILING_ONLY) {
            shows(BudgetTags.CEILING_ONLY, "Só em outubro") && shows(BudgetTags.CEILING_ONLY, "Em novembro, volta a ${money(Cents(410_000))}.")
        }
        tap(BudgetTags.CEILING_ONLY)
        await("snack só em outubro", SnackTags.TEXT) { shows(SnackTags.TEXT, "Teto de ${money(Cents(440_000))} só em outubro.") }
        await("cabeçalho 'teto R$ 4.400,00 · só outubro'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "teto ${money(Cents(440_000))}") && shows(BudgetTags.CEILING, "só outubro")
        }

        day = NOV_2
        // D42: novembro recebe o padrão (4.100,00), não o de outubro; as cotas seguem (D8).
        await("novembro com o padrão", BudgetTags.CEILING, BudgetTags.HEADER) {
            shows(BudgetTags.HEADER, "novembro") &&
                shows(BudgetTags.CEILING, "cotas ${money(Cents(372_000))}") && shows(BudgetTags.CEILING, "teto ${money(Cents(410_000))}") &&
                !shows(BudgetTags.CEILING, "só") && !shows(BudgetTags.CEILING, money(Cents(440_000))) &&
                described(BudgetTags.CEILING, "Teto de novembro: ${money(Cents(410_000))}. Cotas somam ${money(Cents(372_000))}, a ${money(Cents(38_000))} do teto. Tocar para mudar.")
        }
        assertEquals(Cents(440_000), fora { db.ceiling(OUT) }.cents)
        assertEquals(Ceiling(NOV, Cents(410_000), Cents(410_000), Cents(372_000)), fora { db.ceiling(NOV) })

        openCeilingDialog()
        await("diálogo de novembro", BudgetTags.CEILING_DIALOG, BudgetTags.CEILING_HINT) {
            shows(BudgetTags.CEILING_DIALOG, "Teto de novembro") &&
                shows(BudgetTags.CEILING_HINT, "As cotas de novembro somam ${money(Cents(372_000))}: ficam a ${money(Cents(38_000))} do teto.")
        }
        tap(BudgetTags.CEILING_REMOVE)
        await("pergunta de tirar em novembro", BudgetTags.CEILING_ONLY, BudgetTags.CEILING_ONWARD) {
            shows(BudgetTags.CEILING_SCOPE, "Tirar o teto de quais meses?") &&
                shows(BudgetTags.CEILING_ONWARD, "De novembro em diante") &&
                shows(BudgetTags.CEILING_ONLY, "Só em novembro") && shows(BudgetTags.CEILING_ONLY, "Em dezembro, volta a ${money(Cents(410_000))}.")
        }
        tap(BudgetTags.CEILING_ONLY)
        await("snack 'Novembro sem teto.'", SnackTags.TEXT) { shows(SnackTags.TEXT, "Novembro sem teto.") }
        await("novembro sem teto, sem menção ao padrão", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "As cotas somam ${money(Cents(372_000))}.") && shows(BudgetTags.CEILING, "Pôr um teto") &&
                described(BudgetTags.CEILING, "As cotas de novembro somam ${money(Cents(372_000))}. Sem teto. Tocar para pôr um teto.")
        }

        day = DEZ_3
        await("dezembro volta ao padrão", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "teto ${money(Cents(410_000))}") && !shows(BudgetTags.CEILING, "Pôr um teto") &&
                described(BudgetTags.CEILING, "Teto de dezembro: ${money(Cents(410_000))}.")
        }
        assertEquals(Cents(0), fora { db.ceiling(NOV) }.cents)
    }

    @Test
    fun `virada do mês depois de só outubro sem padrão mostra Pôr um teto`() {
        budget("Mercado", 372_000)
        assertTrue(fora { db.setCeiling(Cents(440_000), onward = false, today = DIA_FIXO) })
        composeRule.setContent { Content() }
        await("outubro 'teto R$ 4.400,00 · só outubro'", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "teto ${money(Cents(440_000))}") && shows(BudgetTags.CEILING, "só outubro")
        }
        day = NOV_2
        await("novembro sem teto", BudgetTags.CEILING) {
            shows(BudgetTags.CEILING, "As cotas somam ${money(Cents(372_000))}.") && shows(BudgetTags.CEILING, "Pôr um teto")
        }
        assertEquals(Ceiling(NOV, Cents(0), Cents(0), Cents(372_000)), fora { db.ceiling(NOV) })
        assertEquals(Cents(440_000), fora { db.ceiling(OUT) }.cents)
    }

    // ---- Helpers ----

    private fun budget(name: String, cents: Long): BudgetId {
        val id = fora { db.createBudget(name, Cents(cents), DIA_FIXO) }
        assertNotEquals("createBudget $name", 0L, id.v)
        return id
    }

    /** O cabeçalho mostra [c] como manda o §7.1 / BudgetTags.CEILING. */
    private fun headerMatches(c: Ceiling): Boolean {
        val t = BudgetTags.CEILING
        if (c.cents.v == 0L) {
            return shows(t, "As cotas somam ${money(c.limits)}.") && shows(t, "Pôr um teto") &&
                described(t, "As cotas de outubro somam ${money(c.limits)}. Sem teto. Tocar para pôr um teto.")
        }
        val only = c.cents != c.standard
        val distance = when {
            c.limits < c.cents -> "a ${money(c.cents - c.limits)} do teto"
            c.limits == c.cents -> "fecham no teto"
            else -> "${money(c.limits - c.cents)} além do teto"
        }
        val over = c.limits - c.cents
        val plus = if (over.v <= 0) "" else if (over.v % 100 == 0L) "+ ${moneyParts(over).first}" else "+ ${moneyPlain(over)}"
        return shows(t, "cotas ${money(c.limits)}") && shows(t, "teto ${money(c.cents)}") &&
            shows(t, "só outubro") == only &&
            (if (plus.isEmpty()) !shows(t, "+ ") else shows(t, plus)) &&
            described(t, "Teto de outubro: ${money(c.cents)}. Cotas somam ${money(c.limits)}, $distance. Tocar para mudar.")
    }

    private fun openCeilingDialog() {
        await("linha do teto") { exists(BudgetTags.CEILING) }
        tap(BudgetTags.CEILING)
        await("diálogo do teto") { exists(BudgetTags.CEILING_DIALOG) && exists(BudgetTags.CEILING_FIELD) }
    }

    private fun openEdit(id: BudgetId) {
        val row = BudgetTags.ROW + id.v
        await("linha da verba ${id.v}") { exists(row) }
        tap(row)
        await("detalhe") { exists(BudgetTags.DETAIL_EDIT) }
        tap(BudgetTags.DETAIL_EDIT)
        await("Editar verba") { exists(BudgetTags.FORM_LIMIT) && exists(BudgetTags.FORM_NAME) }
    }

    private fun tap(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
    }

    /** Uma escrita ou leitura "por fora": outra coroutine, fora da thread da UI, com timeout. */
    private fun <T> fora(block: suspend () -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { block() } }

    /** Roda [block] em Dispatchers.Default enquanto a UI segue; a falha sai no await(). */
    private fun <T> inParallel(block: suspend CoroutineScope.() -> T): Deferred<T> =
        CoroutineScope(Dispatchers.Default).async { withTimeout(STORM_TIMEOUT_MS) { block() } }

    private fun exists(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** Text + EditableText of the node with [tag]; empty when absent. */
    private fun texts(tag: String): List<String> {
        val node = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull() ?: return emptyList()
        return node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    /** Like assertTextContains(substring = true), also accepting a value split across pieces of text. */
    private fun shows(tag: String, s: String): Boolean {
        val t = texts(tag)
        return t.any { it.contains(s) } || t.joinToString("").contains(s) || t.joinToString(" ").contains(s)
    }

    /** The content description (leitor de tela) of the node with [tag] contains [s]. */
    private fun described(tag: String, s: String): Boolean {
        val node = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull() ?: return false
        return node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ").contains(s)
    }

    /** Waits for [cond]; on timeout, fails with [what] and the texts of [show]. */
    private fun await(what: String, vararg show: String, timeout: Long = UI_TIMEOUT_MS, cond: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeout) { cond() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what; na tela: " + show.joinToString { tag ->
                val cd = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
                    ?.config?.getOrNull(SemanticsProperties.ContentDescription)
                "$tag=${texts(tag)} cd=$cd"
            }, e)
        }
    }

    /** Lets the IO queries come back and the UI settle; an exception in the composition comes out here. */
    private fun settle() = repeat(20) {
        Thread.sleep(25)
        composeRule.waitForIdle()
    }

    private fun pressBack() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    private companion object {
        /** Quarta, 14 de outubro de 2026. */
        val DIA_FIXO = LocalDate(2026, 10, 14)
        val NOV_2 = LocalDate(2026, 11, 2)
        val DEZ_3 = LocalDate(2026, 12, 3)
        val OUT = YearMonth(2026, 10)
        val NOV = YearMonth(2026, 11)

        const val SEED = 20_261_016L
        const val WORKERS = 3
        const val OPS_PER_WORKER = 20
        const val OP_TIMEOUT_MS = 10_000L
        const val STORM_TIMEOUT_MS = 60_000L
        const val UI_TIMEOUT_MS = 10_000L
    }
}
