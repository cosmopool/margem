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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InboxTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.Screen
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.DateTimeTags
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
import com.kaiodelphino.margem.widgets.dateLong
import com.kaiodelphino.margem.widgets.money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Ciclo do recado (bloco 4), concorrência e ciclo de vida: o Db muda por fora (outra coroutine, direto
 * no Db) com Lançar, Registrar estorno, Editar, a Pauta ou Lançamentos abertos e a tela segue o Db
 * (D6, D19) ou recusa sem gravar (spec §7.3, InboxTags.GONE_TEXT); o formulário e Registrar estorno
 * sobrevivem à recriação (D18, D19, TransactionTags: rememberSaveable); toque duplo grava um só; o
 * Desfazer do snackbar roda no escopo do App (InboxTags); `accept` concorrente no mesmo recado.
 *
 * Calendário (spec §3, §4; Ajustes padrão: mês começa no dia 1, semana na segunda): hoje fixo
 * (LocalToday) é quarta, 14/10/2026. 12/10 é segunda, 11/10 domingo, 10/10 sábado.
 *
 * Recados entram por `capture` de uma fonte cadastrada, no formato genérico já coberto pelo
 * ExtractTest ("Compra de R$ 1.234,56 aprovada em PADARIA SAO JOSE em 05/10 às 14h32."); cada
 * captura confere o que foi lido (Parsed) antes de o teste depender disso. postedAt fica no passado
 * (13/10/2026, fuso do sistema) para que "aberta em" (relógio de parede) seja sempre depois.
 *
 * Navegação só por tags: Pauta por BudgetTags.PAUTA_STRIP; Lançamentos não tem tag na barra de abas,
 * então o teste usa o parâmetro público `tabRequest` do App (o mesmo caminho dos widgets, §7).
 *
 * Plain Application: MargemApp abriria o Db de produção com o driver bundled (D28). Tela alta para
 * o formulário inteiro caber sem rolagem preguiçosa.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h1800dp")
class RecadoLifecycleTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    /** Tira o App da composição antes de fechar o Db, para nenhuma consulta rodar num Db fechado. */
    private var show by mutableStateOf(true)

    /** App.tabRequest: abre uma aba como os widgets fazem (a barra de abas não tem tags). */
    private var tab by mutableStateOf<Screen?>(null)

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
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

    // ---- Recado que sai da Pauta por fora com Lançar aberto (spec §7.3, InboxTags.GONE_TEXT) ----

    @Test
    fun `Lançar aberto e o recado lançado por fora - lançar é recusado, avisa e volta sem gravar`() {
        val lazer = budget("Lazer")
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        lancarComRecadoQueSaiu("lançado por fora", n, lazer) {
            assertNotEquals("accept por fora", 0L, db.accept(n, form(lazer, 4_600, tags = listOf("fora"))).v)
        }
        val txs = fora { db.transactions() }
        assertEquals("só o lançamento feito por fora", 1, txs.size)
        assertEquals(listOf("fora"), txs.single().tags)
    }

    @Test
    fun `Lançar aberto e o recado riscado por fora - lançar é recusado, avisa e volta sem gravar`() {
        val lazer = budget("Lazer")
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        lancarComRecadoQueSaiu("riscado por fora", n, lazer) { assertTrue("dismiss por fora", db.dismiss(n)) }
        assertTrue("nenhum lançamento", fora { db.transactions() }.isEmpty())
        assertEquals("o recado continua riscado", listOf(n), fora { db.dismissed() }.map { it.id })
    }

    @Test
    fun `Lançar aberto e o recado estornado por fora - lançar é recusado, avisa e volta sem gravar`() {
        val lazer = budget("Lazer")
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        val e = estorno("e1", 4_600, at(13, 22, 0))
        lancarComRecadoQueSaiu("estornado por fora", n, lazer) {
            // §6: estorno de recado não lançado — os dois saem da Pauta.
            assertTrue("registerRefund(recado) por fora", db.registerRefund(e, n))
        }
        assertTrue("nenhum lançamento", fora { db.transactions() }.isEmpty())
        assertTrue("nem o recado nem o estorno voltam à Pauta", fora { db.pending() }.none { it.id == n || it.id == e })
    }

    @Test
    fun `Riscar no Lançar de um recado já lançado por fora é recusado e não risca`() {
        val lazer = budget("Lazer")
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        composeRule.setContent { Content() }
        openPost(n)
        assertNotEquals("accept por fora", 0L, fora { db.accept(n, form(lazer, 4_600)) }.v)
        settle()
        if (exists(TransactionTags.FORM_DISMISS)) runCatching { tap(TransactionTags.FORM_DISMISS) }
        await("snack '${InboxTags.GONE_TEXT}' ao riscar", SnackTags.TEXT) { shows(SnackTags.TEXT, InboxTags.GONE_TEXT) }
        awaitPauta("depois de riscar um recado que saiu")
        assertTrue("nada riscado", fora { db.dismissed() }.isEmpty())
        assertEquals("o lançamento feito por fora fica", 1, fora { db.transactions() }.size)
    }

    /**
     * Abre Lançar de [n], escolhe [budget] e um marcador novo ("petisco"), roda [porFora] direto no Db e
     * toca Lançar (se a tela ainda estiver aberta): o snack diz GONE_TEXT, a tela volta à Pauta, o card
     * some e nada do formulário é gravado (nem o marcador novo: TransactionTags.TAG_CREATE).
     */
    private fun lancarComRecadoQueSaiu(oQueHouve: String, n: NoticeId, budget: BudgetId, porFora: suspend () -> Unit) {
        composeRule.setContent { Content() }
        openPost(n)
        chooseBudget(budget)
        addTag("petisco")
        await("Lançar habilitado com verba e marcador", TransactionTags.FORM_SUBMIT, TransactionTags.FORM_MISSING) {
            enabled(TransactionTags.FORM_SUBMIT) && !exists(TransactionTags.FORM_MISSING)
        }

        fora { porFora() }
        val depoisDeFora = fora { db.transactions() }
        settle()
        // A tela pode recusar já ao perceber a mudança; senão, a recusa vem ao tocar Lançar.
        if (exists(TransactionTags.FORM_SUBMIT)) runCatching { tap(TransactionTags.FORM_SUBMIT) }

        await("snack '${InboxTags.GONE_TEXT}' depois do recado $oQueHouve", SnackTags.TEXT) {
            shows(SnackTags.TEXT, InboxTags.GONE_TEXT)
        }
        awaitPauta("depois do recado $oQueHouve")
        await("card do recado some da Pauta") { !exists(InboxTags.CARD + n.v) }
        settle()
        assertEquals("o formulário não gravou nada ($oQueHouve)", depoisDeFora, fora { db.transactions() })
        assertTrue("o marcador novo do formulário não foi gravado", fora { db.tags() }.none { it.name == "petisco" })
    }

    // ---- O mesmo com Registrar estorno aberto ----

    @Test
    fun `Registrar estorno aberto e o estorno registrado por fora em outra compra - recusado sem gravar`() {
        val casa = budget("Casa")
        val (p1, p2) = duasCompras(casa)
        val e = estorno("e1", 2_000, at(13, 22, 0))
        estornoComRecadoQueSaiu("estornado por fora", e, p1) { assertTrue("registerRefund por fora", db.registerRefund(e, p2)) }
        assertEquals("P2 estornada pelo estorno de fora", e, fora { db.transaction(p2) }.row.estorno)
        assertEquals("P1 continua contando", NoticeId(0), fora { db.transaction(p1) }.row.estorno)
    }

    @Test
    fun `Registrar estorno aberto e o estorno riscado por fora - recusado sem gravar`() {
        val casa = budget("Casa")
        val (p1, p2) = duasCompras(casa)
        val e = estorno("e1", 2_000, at(13, 22, 0))
        estornoComRecadoQueSaiu("riscado por fora", e, p1) { assertTrue("dismiss por fora", db.dismiss(e)) }
        assertEquals("P1 continua contando", NoticeId(0), fora { db.transaction(p1) }.row.estorno)
        assertEquals("P2 continua contando", NoticeId(0), fora { db.transaction(p2) }.row.estorno)
        assertEquals("o estorno continua riscado", listOf(e), fora { db.dismissed() }.map { it.id })
    }

    @Test
    fun `Registrar estorno aberto e o estorno lançado por fora - recusado sem gravar`() {
        val casa = budget("Casa")
        val (p1, _) = duasCompras(casa)
        val e = estorno("e1", 2_000, at(13, 22, 0))
        estornoComRecadoQueSaiu("lançado por fora", e, p1) {
            // Db.accept só pede um recado pendente; o de estorno também é.
            assertNotEquals("accept do estorno por fora", 0L, db.accept(e, form(casa, 2_000)).v)
        }
        assertEquals("P1 continua contando", NoticeId(0), fora { db.transaction(p1) }.row.estorno)
        assertEquals("as duas compras e o lançamento de fora", 3, fora { db.transactions() }.size)
    }

    private fun estornoComRecadoQueSaiu(oQueHouve: String, e: NoticeId, escolhida: TransactionId, porFora: suspend () -> Unit) {
        composeRule.setContent { Content() }
        openRefund(e)
        val cand = InboxTags.CANDIDATE_TX + escolhida.v
        await("candidata ${escolhida.v} na lista") { exists(cand) }
        tap(cand)
        await("candidata selecionada e Registrar estorno habilitado", cand, InboxTags.REFUND_SUBMIT) {
            selected(cand) && enabled(InboxTags.REFUND_SUBMIT)
        }

        fora { porFora() }
        settle()
        if (exists(InboxTags.REFUND_SUBMIT)) runCatching { tap(InboxTags.REFUND_SUBMIT) }

        await("snack '${InboxTags.GONE_TEXT}' depois do estorno $oQueHouve", SnackTags.TEXT) {
            shows(SnackTags.TEXT, InboxTags.GONE_TEXT)
        }
        awaitPauta("depois do estorno $oQueHouve")
        await("card do estorno some da Pauta") { !exists(InboxTags.CARD + e.v) }
    }

    /** Duas compras manuais de R$ 20,00 (como o estorno): nenhuma é "provável" (sem fonte), nada vem selecionado. */
    private fun duasCompras(budget: BudgetId): Pair<TransactionId, TransactionId> {
        val p1 = fora { db.addManual(form(budget, 2_000, merchant = "Mercado Bom", date = LocalDate(2026, 10, 12))).transaction }
        val p2 = fora { db.addManual(form(budget, 2_000, merchant = "Padaria Pao Quente", date = LocalDate(2026, 10, 11))).transaction }
        assertNotEquals("addManual P1", 0L, p1.v)
        assertNotEquals("addManual P2", 0L, p2.v)
        return p1 to p2
    }

    // ---- Captura com a Pauta aberta (spec §2.11, InboxTags.NEW) ----

    @Test
    fun `captura com a Pauta aberta aparece como nova e deixa de ser nova ao sair e voltar`() {
        val x = compra("x", 1_000, "LOJA ANTIGA", 13, 10, 0)
        composeRule.setContent { Content() }
        openPauta()
        await("recado antigo aparece como novo (nunca abriram a Pauta)") { exists(InboxTags.CARD + x.v) && exists(InboxTags.NEW + x.v) }

        // Postado antes da abertura (13/10), capturado com a Pauta aberta.
        val a = compra("a", 2_500, "LOJA NOVA", 13, 18, 0)
        await("card do recado capturado com a Pauta aberta (recompõe por db.version)", InboxTags.CARD + a.v) {
            exists(InboxTags.CARD + a.v) && shows(InboxTags.CARD + a.v, money(Cents(2_500)))
        }
        await("o capturado aparece como novo") { exists(InboxTags.NEW + a.v) }
        assertTrue("os novos continuam novos enquanto a Pauta está aberta", exists(InboxTags.NEW + x.v))

        // Postado agora (relógio de parede): depois do momento em que a Pauta foi aberta.
        Thread.sleep(20)
        val now = System.currentTimeMillis()
        val b = capturar("b", "Compra de R$ 30,00 aprovada em LOJA AGORA.", now)
        await("card do recado postado agora") { exists(InboxTags.CARD + b.v) && exists(InboxTags.NEW + b.v) }

        leavePauta()
        Thread.sleep(20)
        openPauta()
        await("ao voltar, o que foi postado até a abertura anterior deixa de ser novo", InboxTags.CARD + a.v) {
            exists(InboxTags.CARD + a.v) && exists(InboxTags.CARD + x.v) && !exists(InboxTags.NEW + a.v) && !exists(InboxTags.NEW + x.v)
        }
        // InboxTags.NEW: sair marca como visto o que foi postado até a abertura; b veio depois dela.
        assertTrue("o postado depois da abertura anterior ainda é novo", exists(InboxTags.NEW + b.v))

        leavePauta()
        openPauta()
        await("na terceira visita nada é novo") {
            exists(InboxTags.CARD + b.v) && listOf(x, a, b).none { exists(InboxTags.NEW + it.v) }
        }
    }

    // ---- Recriação da Activity / morte do processo (D18, D19, TransactionTags) ----

    @Test
    fun `Lançar no meio do preenchimento sobrevive à recriação - valor, emendas, apelido, banco, data, verba, parcelas e marcadores`() {
        val lazer = budget("Lazer", 50_000)
        val casa = budget("Casa", 80_000)
        assertNotEquals(0L, fora { db.addManual(form(lazer, 900, tags = listOf("cafe"), merchant = "CAFE CENTRAL", date = LocalDate(2026, 10, 5))).transaction }.v)
        assertNotEquals(0L, fora { db.addManual(form(lazer, 6_000, tags = listOf("bar", "amigos"), merchant = "BAR PONTO CERTO", date = LocalDate(2026, 10, 10))).transaction }.v)
        assertEquals("pré-condição: sugestão de BAR PONTO CERTO é Lazer", lazer, fora { db.suggestion("BAR PONTO CERTO") }.budget)
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)

        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openPost(n)
        // Pré-preenchido (TransactionTags: Lançar) — total do Parsed e a sugestão (§2.5).
        await("Lançar R$ 46,00 com Lazer e bar/amigos sugeridos", TransactionTags.FORM_SUBMIT) {
            shows(TransactionTags.FORM_SUBMIT, money(Cents(4_600))) && shows(TransactionTags.FORM_BUDGET, "Lazer") &&
                state(TransactionTags.FORM_BUDGET) == "sugerida" &&
                suggested("bar") && suggested("amigos") && !exists(TransactionTags.TAG + "cafe")
        }

        // Emendas e escolhas.
        replaceText(TransactionTags.FORM_TOTAL, "5290")
        await("emenda do valor", TransactionTags.FORM_SUBMIT) {
            exists(TransactionTags.FORM_TOTAL_EMENDA) && shows(TransactionTags.FORM_SUBMIT, money(Cents(5_290)))
        }
        replaceText(TransactionTags.FORM_ALIAS, "Bar do Zé")
        tap(TransactionTags.FORM_BANK)
        await("campo do texto do banco") { exists(TransactionTags.FORM_BANK_INPUT) }
        replaceText(TransactionTags.FORM_BANK_INPUT, "BAR PONTO CERTO LTDA")
        await("emenda do texto do banco") { exists(TransactionTags.FORM_BANK_EMENDA) }
        pickDateTime(LocalDate(2026, 10, 12), "1905")
        await("data 12/10 19:05 no formulário", TransactionTags.FORM_DATE) {
            shows(TransactionTags.FORM_DATE, dateLong(LocalDate(2026, 10, 12))) && shows(TransactionTags.FORM_DATE, "19:05") &&
                exists(TransactionTags.FORM_DATE_EMENDA)
        }
        chooseBudget(casa)
        tap(TransactionTags.INSTALLMENTS_PLUS)
        tap(TransactionTags.INSTALLMENTS_PLUS)
        await("3 parcelas", TransactionTags.INSTALLMENTS) { shows(TransactionTags.INSTALLMENTS, "3") }
        addTag("cafe")
        addTag("petisco")

        checkFilledForm("antes da recriação", lazer, casa)
        restore.emulateSavedInstanceStateRestore()
        checkFilledForm("depois da recriação", lazer, casa)

        tap(TransactionTags.FORM_SUBMIT)
        await("lançamento gravado") { fora { db.transactions() }.size == 3 }
        val row = fora { db.transactions() }.single { it.notice == n }
        assertEquals("verba", casa, row.budget)
        assertEquals("valor total", Cents(5_290), row.total)
        assertEquals("parcelas", 3, row.installments)
        assertEquals("texto do banco emendado", "BAR PONTO CERTO LTDA", row.merchant)
        assertEquals("apelido", "Bar do Zé", row.alias)
        assertEquals("data", LocalDate(2026, 10, 12), row.date)
        assertEquals("hora", LocalTime(19, 5), row.time)
        // §2.5: sugeridos já contam, tracejados ou não.
        assertEquals("marcadores", listOf("amigos", "bar", "cafe", "petisco"), row.tags)
        assertTrue("emendado", row.corrected)
        // §6: 5290 / 3 = 1763 (para baixo); a última absorve: 5290 − 2 × 1763 = 1764; meses seguidos.
        assertEquals(
            listOf(Cents(1_763), Cents(1_763), Cents(1_764)) to listOf(LocalDate(2026, 10, 12), LocalDate(2026, 11, 12), LocalDate(2026, 12, 12)),
            fora { db.transaction(row.id) }.installments.let { i -> i.map { it.cents } to i.map { it.date } },
        )
        assertFalse("o recado saiu da Pauta", fora { db.pending() }.any { it.id == n })
    }

    private fun checkFilledForm(stage: String, lazer: BudgetId, casa: BudgetId) {
        val f = TransactionTags
        await(
            "formulário preenchido ($stage)", f.FORM_SUBMIT, f.FORM_ALIAS, f.FORM_BANK, f.FORM_BANK_INPUT, f.FORM_DATE,
            f.INSTALLMENTS, f.INSTALLMENTS_PREVIEW, f.FORM_PREVIEW, f.FORM_MISSING,
        ) {
            shows(f.FORM_SUBMIT, money(Cents(5_290))) && exists(f.FORM_TOTAL_EMENDA) &&
                shows(f.FORM_ALIAS, "Bar do Zé") &&
                (shows(f.FORM_BANK_INPUT, "BAR PONTO CERTO LTDA") || shows(f.FORM_BANK, "BAR PONTO CERTO LTDA")) &&
                exists(f.FORM_BANK_EMENDA) &&
                shows(f.FORM_DATE, dateLong(LocalDate(2026, 10, 12))) && shows(f.FORM_DATE, "19:05") && exists(f.FORM_DATE_EMENDA) &&
                shows(f.FORM_BUDGET, "Casa") && state(f.FORM_BUDGET) != "sugerida" &&
                shows(f.INSTALLMENTS, "3") &&
                // "n × money(total / n)" e " · última" quando a última absorve centavos: 3 × 17,63 · última 17,64.
                shows(f.INSTALLMENTS_PREVIEW, "3 × ${money(Cents(1_763))}") && shows(f.INSTALLMENTS_PREVIEW, "última ${money(Cents(1_764))}") &&
                // Prévia na verba escolhida: Casa 800,00 sem gasto; em outubro conta só a 1ª parcela, 17,63 → 782,37.
                shows(f.FORM_PREVIEW, "Casa") && shows(f.FORM_PREVIEW, money(Cents(80_000))) && shows(f.FORM_PREVIEW, money(Cents(78_237))) &&
                suggested("bar") && suggested("amigos") && confirmed("cafe") && confirmed("petisco") &&
                !exists(f.FORM_MISSING) && enabled(f.FORM_SUBMIT)
        }
    }

    @Test
    fun `Novo lançamento preenchido sobrevive à recriação - nome, valor, verba, parcelas e marcador`() {
        val casa = budget("Casa", 80_000)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openTransactions()
        tap(TransactionTags.NEW)
        await("formulário de Novo lançamento") { exists(TransactionTags.FORM_NAME) && exists(TransactionTags.FORM_SUBMIT) }
        assertFalse("manual não tem apelido nem texto do banco", exists(TransactionTags.FORM_ALIAS) || exists(TransactionTags.FORM_BANK))
        replaceText(TransactionTags.FORM_NAME, "Feira do bairro")
        replaceText(TransactionTags.FORM_TOTAL, "1500")
        chooseBudget(casa)
        tap(TransactionTags.INSTALLMENTS_PLUS)
        addTag("feira")
        pickDateTime(LocalDate(2026, 10, 10), "0815")
        val check = { stage: String ->
            await("Novo lançamento preenchido ($stage)", TransactionTags.FORM_NAME, TransactionTags.FORM_SUBMIT, TransactionTags.FORM_DATE) {
                shows(TransactionTags.FORM_NAME, "Feira do bairro") && shows(TransactionTags.FORM_SUBMIT, money(Cents(1_500))) &&
                    shows(TransactionTags.FORM_BUDGET, "Casa") && shows(TransactionTags.INSTALLMENTS, "2") && confirmed("feira") &&
                    shows(TransactionTags.FORM_DATE, dateLong(LocalDate(2026, 10, 10))) && shows(TransactionTags.FORM_DATE, "08:15") &&
                    // 1500 / 2 = 750 sem resto: sem " · última".
                    shows(TransactionTags.INSTALLMENTS_PREVIEW, "2 × ${money(Cents(750))}") && !shows(TransactionTags.INSTALLMENTS_PREVIEW, "última")
            }
        }
        check("antes da recriação")
        restore.emulateSavedInstanceStateRestore()
        check("depois da recriação")

        tap(TransactionTags.FORM_SUBMIT)
        await("lançamento manual gravado") { fora { db.transactions() }.size == 1 }
        val row = fora { db.transactions() }.single()
        assertEquals(NoticeId(0), row.notice)
        assertEquals("Feira do bairro", row.merchant)
        assertEquals(Cents(1_500), row.total)
        assertEquals(2, row.installments)
        assertEquals(casa, row.budget)
        assertEquals(listOf("feira"), row.tags)
        assertEquals(LocalDate(2026, 10, 10) to LocalTime(8, 15), row.date to row.time)
        assertFalse("manual nunca é emendado", row.corrected)
    }

    @Test
    fun `recriação com o seletor de data aberto - o formulário guarda a data confirmada e o seletor segue usável`() {
        val lazer = budget("Lazer")
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openPost(n)
        chooseBudget(lazer)
        addTag("bar")
        pickDateTime(LocalDate(2026, 10, 12), "1905")
        await("data confirmada 12/10 19:05", TransactionTags.FORM_DATE) {
            shows(TransactionTags.FORM_DATE, dateLong(LocalDate(2026, 10, 12))) && shows(TransactionTags.FORM_DATE, "19:05")
        }

        // Abre de novo e escolhe outra data sem tocar Pronto.
        tap(TransactionTags.FORM_DATE)
        await("seletor aberto") { exists(DateTimeTags.DIALOG) }
        tap(DateTimeTags.DAY + "2026-10-10")
        replaceText(DateTimeTags.TIME, "0815")

        restore.emulateSavedInstanceStateRestore()

        // Sem Pronto, nada chegou ao formulário (DateTimeTags.DONE / CANCEL).
        await("o formulário guarda a data confirmada depois da recriação", TransactionTags.FORM_DATE) {
            shows(TransactionTags.FORM_DATE, dateLong(LocalDate(2026, 10, 12))) && shows(TransactionTags.FORM_DATE, "19:05")
        }
        // O seletor pode ou não continuar aberto (o contrato não diz); de um jeito ou de outro, ele funciona.
        if (!exists(DateTimeTags.DIALOG)) tap(TransactionTags.FORM_DATE)
        await("seletor aberto depois da recriação") { exists(DateTimeTags.DIALOG) && exists(DateTimeTags.DAY + "2026-10-11") }
        tap(DateTimeTags.DAY + "2026-10-11")
        replaceText(DateTimeTags.TIME, "0745")
        tap(DateTimeTags.DONE)
        await("seletor fechado e data 11/10 07:45", TransactionTags.FORM_DATE) {
            !exists(DateTimeTags.DIALOG) && shows(TransactionTags.FORM_DATE, dateLong(LocalDate(2026, 10, 11))) &&
                shows(TransactionTags.FORM_DATE, "07:45")
        }
        assertTrue("verba e marcador continuam", shows(TransactionTags.FORM_BUDGET, "Lazer") && confirmed("bar"))

        tap(TransactionTags.FORM_SUBMIT)
        await("lançado") { fora { db.transactions() }.size == 1 }
        val row = fora { db.transactions() }.single()
        assertEquals(LocalDate(2026, 10, 11) to LocalTime(7, 45), row.date to row.time)
    }

    @Test
    fun `Registrar estorno sobrevive à recriação com busca e seleção`() {
        val casa = budget("Casa", 80_000)
        val (p1, p2) = duasCompras(casa) // Mercado Bom, Padaria Pao Quente; R$ 20,00 cada
        val e = estorno("e1", 2_000, at(13, 22, 0))
        val c1 = InboxTags.CANDIDATE_TX + p1.v
        val c2 = InboxTags.CANDIDATE_TX + p2.v

        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openRefund(e)
        await("as duas compras como candidatas") { exists(c1) && exists(c2) }
        replaceText(InboxTags.REFUND_SEARCH, "mercado")
        await("busca 'mercado' deixa só Mercado Bom") { exists(c1) && !exists(c2) }
        tap(c1)
        // 800,00 − 40,00 = 760,00; estornar 20,00 → 780,00 (§6: a compra estornada não soma).
        val check = { stage: String ->
            await("busca e seleção ($stage)", InboxTags.REFUND_SEARCH, InboxTags.REFUND_PREVIEW) {
                shows(InboxTags.REFUND_SEARCH, "mercado") && exists(c1) && !exists(c2) && selected(c1) &&
                    enabled(InboxTags.REFUND_SUBMIT) && shows(InboxTags.REFUND_PREVIEW, money(Cents(76_000))) &&
                    shows(InboxTags.REFUND_PREVIEW, money(Cents(78_000)))
            }
        }
        check("antes da recriação")
        restore.emulateSavedInstanceStateRestore()
        check("depois da recriação")

        // A seleção fica mesmo quando a busca a esconde (InboxTags.REFUND_SEARCH).
        replaceText(InboxTags.REFUND_SEARCH, "padaria")
        await("busca 'padaria' esconde a selecionada") { exists(c2) && !exists(c1) && enabled(InboxTags.REFUND_SUBMIT) }
        tap(InboxTags.REFUND_SUBMIT)
        await("estorno registrado em Mercado Bom") { fora { db.transaction(p1) }.row.estorno == e }
        assertEquals("Padaria continua contando", NoticeId(0), fora { db.transaction(p2) }.row.estorno)
        awaitPauta("depois de registrar o estorno")
        await("snack do estorno", SnackTags.TEXT) { shows(SnackTags.TEXT, "Estorno registrado") }
    }

    // ---- Toque duplo ----

    @Test
    fun `toque duplo em Lançar grava um lançamento só`() {
        val lazer = budget("Lazer")
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        composeRule.setContent { Content() }
        openPost(n)
        chooseBudget(lazer)
        addTag("bar")
        await("Lançar habilitado") { enabled(TransactionTags.FORM_SUBMIT) }
        doubleTap(TransactionTags.FORM_SUBMIT)
        awaitPauta("depois do toque duplo em Lançar")
        await("lançamento gravado") { fora { db.transactions() }.isNotEmpty() }
        settle()
        assertEquals("um lançamento só", 1, fora { db.transactions() }.size)
        // O segundo toque "não faz nada": nem uma segunda tentativa recusada (GONE_TEXT) por cima do snack.
        assertTrue("snack do lançamento, não de recusa: ${texts(SnackTags.TEXT)}", shows(SnackTags.TEXT, "Lançado em") && !shows(SnackTags.TEXT, InboxTags.GONE_TEXT))
    }

    @Test
    fun `toque duplo em Lançar do Novo lançamento grava um lançamento só`() {
        val casa = budget("Casa")
        composeRule.setContent { Content() }
        openTransactions()
        tap(TransactionTags.NEW)
        await("formulário de Novo lançamento") { exists(TransactionTags.FORM_NAME) }
        replaceText(TransactionTags.FORM_NAME, "Feira do bairro")
        replaceText(TransactionTags.FORM_TOTAL, "1500")
        chooseBudget(casa)
        addTag("feira")
        await("Lançar R$ 15,00 habilitado", TransactionTags.FORM_SUBMIT) {
            enabled(TransactionTags.FORM_SUBMIT) && shows(TransactionTags.FORM_SUBMIT, money(Cents(1_500)))
        }
        doubleTap(TransactionTags.FORM_SUBMIT)
        await("de volta a Lançamentos") { !exists(TransactionTags.FORM_TITLE) && exists(TransactionTags.NEW) }
        await("lançamento gravado") { fora { db.transactions() }.isNotEmpty() }
        settle()
        val txs = fora { db.transactions() }
        assertEquals("um lançamento só: $txs", 1, txs.size)
        await("uma linha só em Lançamentos") { rowIds(TransactionTags.ROW) == setOf(txs.single().id.v) }
    }

    // ---- Desfazer do snackbar depois de sair da tela que o mostrou (InboxTags: escopo do App) ----

    @Test
    fun `Desfazer de Riscar funciona depois de trocar de aba`() {
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        composeRule.setContent { Content() }
        openPauta()
        await("card do recado") { exists(InboxTags.DISMISS + n.v) }
        tap(InboxTags.DISMISS + n.v)
        await("snack 'Recado riscado.' com Desfazer", SnackTags.TEXT) { shows(SnackTags.TEXT, "Recado riscado.") && exists(SnackTags.ACTION) }
        assertEquals("riscado no Db", listOf(n), fora { db.dismissed() }.map { it.id })

        leavePauta() // Voltar da raiz da Pauta leva à aba Verbas
        assertTrue("snack ainda na tela em Verbas", exists(SnackTags.ACTION))
        tap(SnackTags.ACTION)
        await("recado de volta à Pauta") { fora { db.pending() }.any { it.id == n } }
        assertTrue("nada riscado", fora { db.dismissed() }.isEmpty())
        await("faixa da Pauta em Verbas volta a aparecer") { exists(BudgetTags.PAUTA_STRIP) }
    }

    @Test
    fun `Desfazer de Lançar funciona já de volta à Pauta`() {
        val lazer = budget("Lazer")
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        composeRule.setContent { Content() }
        openPost(n)
        chooseBudget(lazer)
        addTag("bar")
        tap(TransactionTags.FORM_SUBMIT)
        awaitPauta("depois de lançar")
        await("snack 'Lançado em Lazer…' com Desfazer", SnackTags.TEXT) { shows(SnackTags.TEXT, "Lançado em Lazer") && exists(SnackTags.ACTION) }
        assertEquals("lançado no Db", 1, fora { db.transactions() }.size)
        tap(SnackTags.ACTION)
        await("Desfazer apaga o lançamento e o recado volta") { fora { db.transactions() }.isEmpty() && fora { db.pending() }.any { it.id == n } }
        await("card do recado de volta na Pauta") { exists(InboxTags.CARD + n.v) }
    }

    @Test
    fun `Desfazer de Registrar estorno funciona depois de trocar de aba`() {
        val casa = budget("Casa")
        val (p1, _) = duasCompras(casa)
        val e = estorno("e1", 2_000, at(13, 22, 0))
        composeRule.setContent { Content() }
        openRefund(e)
        val cand = InboxTags.CANDIDATE_TX + p1.v
        await("candidata") { exists(cand) }
        tap(cand)
        await("Registrar estorno habilitado") { enabled(InboxTags.REFUND_SUBMIT) }
        tap(InboxTags.REFUND_SUBMIT)
        awaitPauta("depois de registrar o estorno")
        await("snack do estorno com Desfazer", SnackTags.TEXT) { shows(SnackTags.TEXT, "Estorno registrado") && exists(SnackTags.ACTION) }
        assertEquals(e, fora { db.transaction(p1) }.row.estorno)

        leavePauta()
        tap(SnackTags.ACTION)
        await("Desfazer: a compra volta a contar e o estorno volta à Pauta") {
            fora { db.transaction(p1) }.row.estorno == NoticeId(0) && fora { db.pending() }.any { it.id == e }
        }
    }

    // ---- accept concorrente no mesmo recado ----

    @Test
    fun `accept concorrente no mesmo recado - só um vence, com a Pauta aberta`() {
        val budgets = listOf("Lazer", "Casa", "Mercado").map { budget(it) }
        val merchants = listOf("LOJA UM", "LOJA DOIS", "LOJA TRES", "LOJA QUATRO", "LOJA CINCO", "LOJA SEIS")
        val notices = merchants.mapIndexed { i, m -> compra("c$i", 1_000L + i * 100, m, 13, 10 + i, 0) }
        composeRule.setContent { Content() }
        openPauta()
        await("os seis cards") { notices.all { exists(InboxTags.CARD + it.v) } }

        val results = runBlocking(Dispatchers.Default) {
            withTimeout(STORM_TIMEOUT_MS) {
                notices.flatMap { n ->
                    budgets.map { b -> async { Triple(n, b, withTimeout(OP_TIMEOUT_MS) { db.accept(n, form(b, 1_000, tags = listOf("t${b.v}"))) }) } }
                }.awaitAll()
            }
        }
        val txs = fora { db.transactions() }
        for (n in notices) {
            val won = results.filter { it.first == n && it.third.v != 0L }
            assertEquals("um accept vence o recado ${n.v}: $results", 1, won.size)
            val row = txs.single { it.notice == n }
            assertEquals("o lançamento é o do vencedor", won.single().third, row.id)
            assertEquals("verba do vencedor", won.single().second, row.budget)
        }
        assertEquals("um lançamento por recado", notices.size, txs.size)
        assertEquals("Pauta vazia no Db", 0, fora { db.pendingCount() })
        await("Pauta limpa na tela") { exists(InboxTags.EMPTY) && notices.none { exists(InboxTags.CARD + it.v) } }
    }

    // ---- Lançamento apagado por fora com a edição aberta ----

    @Test
    fun `lançamento apagado por fora com a edição aberta - Salvar avisa que não existe mais e volta`() {
        val lazer = budget("Lazer")
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        val t = fora { db.accept(n, form(lazer, 4_600, tags = listOf("bar"))) }
        assertNotEquals("accept", 0L, t.v)
        composeRule.setContent { Content() }
        openEditFromBudget(lazer, t)
        await("Salvar habilitado na edição") { enabled(TransactionTags.FORM_SUBMIT) }

        fora { db.deleteTransaction(t) }
        settle()
        if (exists(TransactionTags.FORM_SUBMIT)) {
            assertTrue("a edição guarda o que estava no formulário: Salvar habilitado", enabled(TransactionTags.FORM_SUBMIT))
            runCatching { tap(TransactionTags.FORM_SUBMIT) }
        }
        await("snack 'Este lançamento não existe mais.'", SnackTags.TEXT) { shows(SnackTags.TEXT, "Este lançamento não existe mais.") }
        await("a edição fecha") { !exists(TransactionTags.FORM_TITLE) }
        settle()
        assertTrue("Salvar não recria o lançamento", fora { db.transactions() }.isEmpty())
        assertTrue("o recado voltou à Pauta (§2.8) e fica lá", fora { db.pending() }.any { it.id == n })
    }

    // ---- Verba apagada por fora com o formulário aberto ----

    /**
     * O contrato diz: accept recusa uma verba que não existe (nada gravado) e o formulário tem uma opção
     * por verba. Não diz que mensagem a tela mostra nem se ela fica ou volta: o teste só exige que não
     * quebre, não grave e que a opção suma; se a tela ficar, lançar em outra verba funciona.
     */
    @Test
    fun `verba apagada por fora com o formulário aberto e selecionada - lançar não grava nem quebra`() {
        val lazer = budget("Lazer")
        val casa = budget("Casa")
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        composeRule.setContent { Content() }
        openPost(n)
        chooseBudget(lazer)
        addTag("petisco")
        await("Lançar habilitado") { enabled(TransactionTags.FORM_SUBMIT) }

        fora { db.deleteBudget(lazer) }
        await("a verba apagada deixa de estar escolhida") { !shows(TransactionTags.FORM_BUDGET, "Lazer") && shows(TransactionTags.FORM_BUDGET, "Escolher a verba") }
        if (exists(TransactionTags.FORM_SUBMIT) && enabled(TransactionTags.FORM_SUBMIT)) runCatching { tap(TransactionTags.FORM_SUBMIT) }
        settle() // uma exceção na composição sairia aqui

        assertTrue("nada gravado com a verba apagada", fora { db.transactions() }.isEmpty())
        assertTrue("o recado continua na Pauta", fora { db.pending() }.any { it.id == n })
        assertTrue("o marcador novo não foi gravado", fora { db.tags() }.none { it.name == "petisco" })

        if (exists(TransactionTags.FORM_TITLE)) {
            chooseBudget(casa)
            await("Casa escolhida") { enabled(TransactionTags.FORM_SUBMIT) }
            tap(TransactionTags.FORM_SUBMIT)
            await("lançado em Casa") { fora { db.transactions() }.singleOrNull()?.budget == casa }
            awaitPauta("depois de lançar em Casa")
        } else {
            awaitPauta("a tela voltou sozinha")
            await("o card continua na Pauta") { exists(InboxTags.CARD + n.v) }
        }
    }

    // ---- Lançamentos e Pauta seguem o Db (D6, D19) ----

    @Test
    fun `Lançamentos atualiza sozinho com estorno registrado, desfeito e lançamento apagado por fora`() {
        val casa = budget("Casa")
        val (p1, p2) = duasCompras(casa)
        val e = estorno("e1", 2_000, at(13, 22, 0))
        composeRule.setContent { Content() }
        openTransactions()
        val r1 = TransactionTags.ROW + p1.v
        await("as duas linhas") { rowIds(TransactionTags.ROW) == setOf(p1.v, p2.v) }
        assertFalse("sem estorno ainda", shows(r1, "estornada") || exists(TransactionTags.UNDO_REFUND + p1.v))

        assertTrue(fora { db.registerRefund(e, p1) })
        await("linha estornada com Desfazer estorno", r1) { shows(r1, "estornada") && exists(TransactionTags.UNDO_REFUND + p1.v) }
        assertFalse("a outra não", exists(TransactionTags.UNDO_REFUND + p2.v))

        assertTrue(fora { db.undoRefund(e) })
        await("estorno desfeito por fora", r1) { !shows(r1, "estornada") && !exists(TransactionTags.UNDO_REFUND + p1.v) }

        val p3 = fora { db.addManual(form(casa, 3_300, merchant = "Acougue", date = LocalDate(2026, 10, 13))).transaction }
        await("linha nova") { rowIds(TransactionTags.ROW) == setOf(p1.v, p2.v, p3.v) && shows(TransactionTags.ROW + p3.v, money(Cents(3_300))) }

        fora { db.deleteTransaction(p1) }
        await("linha apagada some") { rowIds(TransactionTags.ROW) == setOf(p2.v, p3.v) }
        fora { db.deleteTransaction(p2); db.deleteTransaction(p3) }
        await("Lançamentos vazia") { exists(TransactionTags.EMPTY) && rowIds(TransactionTags.ROW).isEmpty() }
    }

    @Test
    fun `Pauta atualiza sozinha com estorno registrado, desfeito e lançamento apagado por fora`() {
        val casa = budget("Casa")
        val (p1, _) = duasCompras(casa)
        val e = estorno("e1", 2_000, at(13, 22, 0))
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        composeRule.setContent { Content() }
        openPauta()
        await("cards do estorno e da compra") { exists(InboxTags.CARD + e.v) && exists(InboxTags.CARD + n.v) }

        assertTrue(fora { db.registerRefund(e, p1) })
        await("estorno registrado por fora sai da Pauta") { !exists(InboxTags.CARD + e.v) && exists(InboxTags.CARD + n.v) }
        assertTrue(fora { db.undoRefund(e) })
        await("estorno desfeito por fora volta à Pauta") { exists(InboxTags.CARD + e.v) }

        val t = fora { db.accept(n, form(casa, 4_600)) }
        assertNotEquals(0L, t.v)
        await("lançado por fora sai da Pauta") { !exists(InboxTags.CARD + n.v) }
        fora { db.deleteTransaction(t) }
        await("lançamento apagado por fora: o recado volta à Pauta (§2.8)") { exists(InboxTags.CARD + n.v) }

        // Estorno de recado não lançado (§6): os dois saem; desfeito, os dois voltam.
        assertTrue(fora { db.registerRefund(e, n) })
        await("estorno de recado: os dois saem") { !exists(InboxTags.CARD + e.v) && !exists(InboxTags.CARD + n.v) }
        await("Pauta limpa") { exists(InboxTags.EMPTY) }
        assertTrue(fora { db.undoRefund(e) })
        await("desfeito: os dois voltam") { exists(InboxTags.CARD + e.v) && exists(InboxTags.CARD + n.v) }
    }

    // ---- Helpers: estado no Db ----

    private fun budget(name: String, limit: Long = 50_000): BudgetId =
        fora { db.createBudget(name, Cents(limit), DIA) }.also { assertNotEquals("createBudget $name", 0L, it.v) }

    private fun form(
        budget: BudgetId, cents: Long, tags: List<String> = listOf("teste"), merchant: String = "Sacolao",
        date: LocalDate = DIA, time: LocalTime = LocalTime(10, 0),
    ) = TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = 1, merchant = merchant, date = date, time = time)

    /** Epoch ms de [day]/10/2026 [h]:[m] no fuso do sistema (o mesmo em que o app lê recados). */
    private fun at(day: Int, h: Int, m: Int): Long =
        LocalDateTime(2026, 10, day, h, m).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private fun capturar(key: String, text: String, postedAt: Long, title: String = "Compra aprovada"): NoticeId {
        val raw = RawNotice(
            packageName = BANK, appLabel = BANK_LABEL, key = key, postedAt = postedAt, whenMs = postedAt,
            title = title, text = text, bigText = text,
        )
        assertTrue("capture de $key", fora { capture(db, raw) })
        val row = fora { db.pending() }.firstOrNull { it.raw.key == key }
        assertNotNull("recado $key pendente depois da captura", row)
        return row!!.id
    }

    /** Compra no formato do ExtractTest; confere o valor e o estabelecimento lidos. */
    private fun compra(key: String, cents: Long, merchant: String, day: Int, h: Int, m: Int): NoticeId {
        val reais = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
        val text = "Compra de R$ $reais aprovada em $merchant em ${day.toString().padStart(2, '0')}/10 às ${h}h${m.toString().padStart(2, '0')}."
        val id = capturar(key, text, at(day, h, m))
        val parsed = fora { db.notice(id) }.parsed
        assertEquals("pré-condição: leitura de '$text'", Cents(cents) to merchant, parsed.cents to parsed.merchant)
        assertFalse("pré-condição: compra não é estorno", parsed.isRefund)
        return id
    }

    /** Estorno genérico sem estabelecimento (ExtractTest: "Estorno de R$ 20,00 efetuado"). */
    private fun estorno(key: String, cents: Long, postedAt: Long): NoticeId {
        val reais = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
        val id = capturar(key, "Estorno de R$ $reais efetuado", postedAt, title = "Estorno")
        assertTrue("pré-condição: recado de estorno", fora { db.notice(id) }.parsed.isRefund)
        return id
    }

    /** Uma escrita ou leitura "por fora": outra coroutine, fora da thread da UI, com timeout. */
    private fun <T> fora(block: suspend () -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { block() } }

    // ---- Helpers: navegação ----

    private fun openPauta() {
        if (!exists(InboxTags.LIST) && !exists(InboxTags.EMPTY)) {
            settle()
            if (exists(BudgetTags.PAUTA_STRIP)) tap(BudgetTags.PAUTA_STRIP) else tab = Screen.Inbox
        }
        awaitPauta("Pauta aberta")
    }

    /** Voltar da raiz da Pauta leva a Verbas (outra aba). */
    private fun leavePauta() {
        pressBack()
        await("Verbas depois de sair da Pauta") { exists(BudgetTags.HEADER) && !exists(InboxTags.LIST) && !exists(InboxTags.EMPTY) }
    }

    private fun openTransactions() {
        await("App aberto em Verbas") { exists(BudgetTags.HEADER) }
        tab = Screen.Transactions
        await("Lançamentos aberta") { exists(TransactionTags.NEW) }
    }

    private fun openPost(n: NoticeId) {
        openPauta()
        await("Lançar do recado ${n.v}") { exists(InboxTags.ACCEPT + n.v) }
        tap(InboxTags.ACCEPT + n.v)
        await("formulário de Lançar do recado ${n.v}") { exists(TransactionTags.FORM_SUBMIT) && exists(TransactionTags.FORM_TOTAL) }
    }

    private fun openRefund(e: NoticeId) {
        openPauta()
        await("Registrar estorno do recado ${e.v}") { exists(InboxTags.ACCEPT + e.v) }
        tap(InboxTags.ACCEPT + e.v)
        await("tela Registrar estorno") { exists(InboxTags.REFUND_TITLE) && exists(InboxTags.REFUND_SUBMIT) }
    }

    /** Verbas › detalhe da verba › linha (BudgetTags.LINE) › edição. */
    private fun openEditFromBudget(budget: BudgetId, t: TransactionId) {
        await("linha da verba ${budget.v}") { exists(BudgetTags.ROW + budget.v) }
        tap(BudgetTags.ROW + budget.v)
        await("linha do lançamento ${t.v} no detalhe") { exists(BudgetTags.LINE + t.v) }
        tap(BudgetTags.LINE + t.v)
        await("edição aberta") { exists(TransactionTags.FORM_TITLE) && exists(TransactionTags.FORM_SUBMIT) }
    }

    private fun awaitPauta(what: String) = await("Pauta na tela: $what", SnackTags.TEXT) {
        !exists(TransactionTags.FORM_TITLE) && !exists(InboxTags.REFUND_TITLE) && (exists(InboxTags.LIST) || exists(InboxTags.EMPTY))
    }

    private fun pressBack() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    // ---- Helpers: formulário ----

    /** Book 8.12: o campo abre a folha; a linha escolhe e fecha (TransactionTags.BUDGET_SHEET). */
    private fun chooseBudget(b: BudgetId) {
        await("campo da verba") { exists(TransactionTags.FORM_BUDGET) }
        tap(TransactionTags.FORM_BUDGET)
        await("opção da verba ${b.v}") { exists(TransactionTags.BUDGET + b.v) }
        tap(TransactionTags.BUDGET + b.v)
        await("verba ${b.v} escolhida, folha fechada") { !exists(TransactionTags.BUDGET_SHEET) }
    }

    /** Digita na busca e toca o resultado, ou "Criar" quando não há (TransactionTags.TAG_SEARCH). */
    private fun addTag(name: String) {
        replaceText(TransactionTags.TAG_SEARCH, name)
        await("resultado ou Criar para $name") { exists(TransactionTags.TAG_RESULT + name) || exists(TransactionTags.TAG_CREATE) }
        tap(if (exists(TransactionTags.TAG_RESULT + name)) TransactionTags.TAG_RESULT + name else TransactionTags.TAG_CREATE)
        await("marcador $name escolhido") { confirmed(name) }
    }

    /** Abre o seletor, escolhe o dia e digita a hora ("1905" = 19:05), Pronto. */
    private fun pickDateTime(day: LocalDate, digits: String) {
        tap(TransactionTags.FORM_DATE)
        await("seletor de data e hora") { exists(DateTimeTags.DIALOG) && exists(DateTimeTags.DAY + day) }
        tap(DateTimeTags.DAY + day)
        await("dia $day escolhido", DateTimeTags.TITLE) { selected(DateTimeTags.DAY + day) && shows(DateTimeTags.TITLE, dateLong(day)) }
        replaceText(DateTimeTags.TIME, digits)
        tap(DateTimeTags.DONE)
        await("seletor fechado", DateTimeTags.TIME_ERROR) { !exists(DateTimeTags.DIALOG) }
    }

    private fun suggested(name: String) = selected(TransactionTags.TAG + name) && state(TransactionTags.TAG + name) == "sugerido"

    private fun confirmed(name: String) = selected(TransactionTags.TAG + name) && state(TransactionTags.TAG + name) != "sugerido"

    // ---- Helpers: semântica ----

    private fun node(tag: String): SemanticsNode? = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()

    private fun exists(tag: String) = node(tag) != null

    private fun selected(tag: String) = node(tag)?.config?.getOrNull(SemanticsProperties.Selected) == true

    private fun enabled(tag: String) = node(tag)?.let { !it.config.contains(SemanticsProperties.Disabled) } == true

    private fun state(tag: String): String? = node(tag)?.config?.getOrNull(SemanticsProperties.StateDescription)

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

    private fun rowIds(prefix: String): Set<Long> = composeRule
        .onAllNodes(SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true })
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix(prefix)?.toLongOrNull() }
        .toSet()

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
        const val STORM_TIMEOUT_MS = 60_000L
        const val UI_TIMEOUT_MS = 10_000L
    }
}
