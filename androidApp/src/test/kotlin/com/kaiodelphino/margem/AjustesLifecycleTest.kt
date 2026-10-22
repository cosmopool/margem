package com.kaiodelphino.margem

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.InboxTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.Screen
import com.kaiodelphino.margem.ui.SettingsTags
import com.kaiodelphino.margem.ui.dayTitle
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
import com.kaiodelphino.margem.widgets.money
import java.io.File
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DayOfWeek
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
 * Ajustes e importação (bloco 5), concorrência e ciclo de vida, pela UI (SettingsTags é o contrato).
 *
 * - Ajustes e as sub-telas seguem o Db mudado por fora (outra coroutine escrevendo direto no Db: D6, D19).
 * - Rascunho de Início do mês e da semana sobrevive à recriação (StateRestorationTester; D19, D41).
 * - Toque duplo grava uma vez; `importCobudget` concorrente com `capture` do mesmo recado deixa um recado só (D4, D29).
 *
 * Calendário (spec §3, §4; padrão: mês começa no dia 1, semana na segunda): hoje fixo (LocalToday) é
 * quarta, 14/10/2026. 13/10 é terça, 12/10 segunda, 5/10 segunda, 1/10 quinta.
 *
 * A escolha de arquivo (SAF) é respondida por um ActivityResultRegistry próprio, dado por
 * LocalActivityResultRegistryOwner (SettingsTags KDoc, architecture §10): devolve `Uri.fromFile` de um
 * arquivo temporário, ou null (escolha cancelada). Isso é a fronteira do sistema, não um mock.
 *
 * Plain Application: MargemApp abriria o Db de produção com o driver bundled (D28). Tela alta para as
 * listas caberem sem rolagem preguiçosa.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h1800dp")
class AjustesLifecycleTest {

    @get:Rule(order = 0) val tmp = TemporaryFolder()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db

    /** Tira o App da composição antes de fechar o Db. */
    private var show by mutableStateOf(true)

    /** App.tabRequest: abre uma aba como os widgets fazem (a barra de abas não tem tags). */
    private var tab by mutableStateOf<Screen?>(null)

    private val picker = Picker()
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = picker
    }

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
            CompositionLocalProvider(LocalToday provides { DIA }, LocalActivityResultRegistryOwner provides owner) {
                App(db, tab) { tab = null }
            }
        }
    }

    // =====================================================================================
    // Ajustes: linhas seguem o Db (SettingsTags: SOURCES, START_DAYS, THEME, WIDGET_ORDER)
    // =====================================================================================

    @Test
    fun `Fontes em Ajustes segue fontes adicionadas e removidas por fora`() {
        composeRule.setContent { Content() }
        openSettings()
        await("sem fontes: 'nenhuma fonte escolhida'", SettingsTags.SOURCES) {
            shows(SettingsTags.SOURCES, "Fontes") && shows(SettingsTags.SOURCES, "nenhuma fonte escolhida")
        }

        // Db.sources: por rótulo, ignorando caixa — "Banco Aurora" antes de "cartão Ipê"; juntas por ", ".
        assertTrue(fora { db.addSource("com.ipe", "cartão Ipê", at(1, 8, 0)) })
        assertTrue(fora { db.addSource(BANK, BANK_LABEL, at(1, 9, 0)) })
        await("duas fontes na ordem de Db.sources", SettingsTags.SOURCES) {
            shows(SettingsTags.SOURCES, "Banco Aurora, cartão Ipê") && !shows(SettingsTags.SOURCES, "nenhuma fonte")
        }

        fora { db.removeSource("com.ipe") }
        await("fonte removida por fora some", SettingsTags.SOURCES) {
            shows(SettingsTags.SOURCES, "Banco Aurora") && !shows(SettingsTags.SOURCES, "Ipê")
        }
        fora { db.removeSource(BANK) }
        await("de volta a nenhuma fonte", SettingsTags.SOURCES) { shows(SettingsTags.SOURCES, "nenhuma fonte escolhida") }
    }

    @Test
    fun `Início do mês e da semana em Ajustes segue mudanças feitas por fora`() {
        composeRule.setContent { Content() }
        openSettings()
        // Padrão (spec §3, §4): dia 1, segunda.
        await("padrão 'dia 1 · segunda'", SettingsTags.START_DAYS) {
            shows(SettingsTags.START_DAYS, "Início do mês e da semana") && shows(SettingsTags.START_DAYS, "dia 1 · segunda")
        }
        assertTrue(fora { db.setMonthStartDay(5, DIA) })
        await("dia 5 por fora", SettingsTags.START_DAYS) { shows(SettingsTags.START_DAYS, "dia 5 · segunda") }
        fora { db.setWeekStart(DayOfWeek.SUNDAY, DIA) }
        await("domingo por fora", SettingsTags.START_DAYS) { shows(SettingsTags.START_DAYS, "dia 5 · domingo") }
        assertTrue(fora { db.setMonthStartDay(31, DIA) })
        fora { db.setWeekStart(DayOfWeek.SATURDAY, DIA) }
        await("dia 31 · sábado por fora", SettingsTags.START_DAYS) { shows(SettingsTags.START_DAYS, "dia 31 · sábado") }
    }

    @Test
    fun `Tema e ordem do widget seguem o Db e o toque grava e marca selected`() {
        composeRule.setContent { Content() }
        openSettings()
        // Padrões (spec §7.6): tema do sistema, widget por risco.
        await("padrões selecionados") { onlySelected(SettingsTags.THEME, "system") && onlySelected(SettingsTags.WIDGET_ORDER, "risk") }
        assertTrue("rótulos do tema", shows(SettingsTags.THEME + "system", "sistema") && shows(SettingsTags.THEME + "light", "claro") && shows(SettingsTags.THEME + "dark", "escuro"))
        assertTrue("rótulos da ordem", shows(SettingsTags.WIDGET_ORDER + "risk", "risco") && shows(SettingsTags.WIDGET_ORDER + "manual", "manual") && shows(SettingsTags.WIDGET_ORDER + "az", "A–Z"))

        // Por fora.
        fora { db.setTheme(Theme.DARK) }
        await("tema escuro por fora") { onlySelected(SettingsTags.THEME, "dark") }
        fora { db.setWidgetOrder(BudgetOrder.MANUAL) }
        await("widget manual por fora") { onlySelected(SettingsTags.WIDGET_ORDER, "manual") }

        // Pelo toque.
        tap(SettingsTags.THEME + "light")
        await("claro gravado") { fora { db.settings() }.theme == Theme.LIGHT }
        await("claro selected") { onlySelected(SettingsTags.THEME, "light") }
        tap(SettingsTags.WIDGET_ORDER + "az")
        await("A–Z gravado") { fora { db.settings() }.widgetOrder == BudgetOrder.AZ }
        await("A–Z selected") { onlySelected(SettingsTags.WIDGET_ORDER, "az") }
        // Ordem do widget não é a da aba Verbas (Settings.budgetOrder, spec §7.1 / §7.6).
        assertEquals("a ordem da aba Verbas não muda", BudgetOrder.RISK, fora { db.settings() }.budgetOrder)
        assertEquals("o tema não muda com a ordem", Theme.LIGHT, fora { db.settings() }.theme)
    }

    @Test
    fun `cada linha de Ajustes abre a sua tela e voltar retorna a Ajustes`() {
        composeRule.setContent { Content() }
        openSettings()
        val rows = listOf(
            Triple(SettingsTags.DISMISSED, "Riscados", { exists(SettingsTags.DISMISSED_LIST) || exists(SettingsTags.DISMISSED_EMPTY) }),
            Triple(SettingsTags.TAGS, "Marcadores", { exists(SettingsTags.TAGS_LIST) || exists(SettingsTags.TAGS_EMPTY) }),
            Triple(SettingsTags.START_DAYS, "Início do mês e da semana", { exists(SettingsTags.START_DAY) && exists(SettingsTags.START_SAVE) }),
            Triple(SettingsTags.BACKUP, "Backup", { exists(SettingsTags.BACKUP_EXPORT) && exists(SettingsTags.BACKUP_IMPORT) }),
            Triple(SettingsTags.COBUDGET, "Importar do cobudget", { exists(SettingsTags.COBUDGET_PICK) }),
            Triple(SettingsTags.SOURCES, "Fontes", { !exists(SettingsTags.LIST) }),
        )
        for ((row, title, opened) in rows) {
            await("linha $row") { exists(row) }
            assertTrue("$row mostra '$title': ${texts(row)}", shows(row, title))
            tap(row)
            await("$row abriu a sua tela") { !exists(SettingsTags.LIST) && opened() }
            pressBack()
            await("voltar de $row leva a Ajustes") { exists(SettingsTags.LIST) && exists(row) }
        }
        assertTrue("subtítulos", shows(SettingsTags.DISMISSED, "trazer recados de volta à pauta") && shows(SettingsTags.TAGS, "renomear, juntar, apagar") &&
            shows(SettingsTags.BACKUP, "exportar · importar") && shows(SettingsTags.COBUDGET, "recados de um backup do app antigo"))
    }

    @Test
    fun `aviso de anotação pausada some quando o acesso é ligado e o app volta ao primeiro plano`() {
        composeRule.setContent { Content() }
        openSettings()
        // Robolectric: nenhum listener habilitado (Settings.Secure vazio) — acesso desligado.
        await("aviso de anotação pausada", SettingsTags.PAUSED) {
            exists(SettingsTags.PAUSED) && shows(SettingsTags.PAUSED, "A anotação automática está pausada.")
        }
        val pkg = composeRule.activity.packageName
        android.provider.Settings.Secure.putString(
            composeRule.activity.contentResolver, "enabled_notification_listeners",
            ComponentName(pkg, "com.kaiodelphino.margem.NoticeListener").flattenToString(),
        )
        // App relê o acesso no ON_RESUME (MainActivity/App: LifecycleEventEffect).
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await("aviso some com o acesso ligado") { !exists(SettingsTags.PAUSED) && exists(SettingsTags.LIST) }
    }

    // =====================================================================================
    // Riscados (spec §2.3, §7.6; SettingsTags DISMISSED_*, RESTORE)
    // =====================================================================================

    @Test
    fun `Riscados segue riscar e trazer de volta feitos por fora`() {
        ensureSource()
        val n = compra("n1", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        composeRule.setContent { Content() }
        openDismissed()
        await("Riscados vazia", SettingsTags.DISMISSED_EMPTY) {
            shows(SettingsTags.DISMISSED_EMPTY, "Nenhum recado riscado.") && ids(SettingsTags.DISMISSED_CARD).isEmpty()
        }

        assertTrue(fora { db.dismiss(n) })
        await("card aparece com o dia") {
            ids(SettingsTags.DISMISSED_CARD) == setOf(n.v) && exists(SettingsTags.DISMISSED_DAY + "2026-10-13") && !exists(SettingsTags.DISMISSED_EMPTY)
        }

        assertTrue(fora { db.restore(n) })
        await("restaurado por fora: card some e volta o vazio") {
            ids(SettingsTags.DISMISSED_CARD).isEmpty() && exists(SettingsTags.DISMISSED_EMPTY) && !exists(SettingsTags.DISMISSED_DAY + "2026-10-13")
        }

        // Riscado de novo por fora: volta à lista.
        assertTrue(fora { db.dismiss(n) })
        await("riscado de novo") { ids(SettingsTags.DISMISSED_CARD) == setOf(n.v) }
    }

    @Test
    fun `Riscados agrupa por dia com os mais recentes no topo e mostra o que o card da Pauta mostra`() {
        ensureSource()
        val a = compra("a", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        val b = compra("b", 1_250, "PADARIA SAO JOSE", 13, 8, 0)
        val c = compra("c", 9_990, "LOJA CENTRAL", 12, 10, 15)
        for (n in listOf(a, b, c)) assertTrue(fora { db.dismiss(n) })
        composeRule.setContent { Content() }
        openDismissed()
        await("três cards") { ids(SettingsTags.DISMISSED_CARD) == setOf(a.v, b.v, c.v) }

        val d13 = SettingsTags.DISMISSED_DAY + "2026-10-13"
        val d12 = SettingsTags.DISMISSED_DAY + "2026-10-12"
        assertEquals("dias", setOf("2026-10-13", "2026-10-12"), suffixes(SettingsTags.DISMISSED_DAY))
        // InboxTags.DAY / dayTitle: 13/10 é ontem (hoje 14/10); 12/10 é segunda. O cabeçalho de dia (book `.dayh`,
        // DayHeader) imprime o título em caixa-alta, como na Pauta: o contrato são as palavras, não a caixa.
        fun title(tag: String, day: LocalDate) = texts(tag).joinToString("").contains(dayTitle(day, DIA), ignoreCase = true)
        assertTrue("título de 13/10: ${texts(d13)}", title(d13, LocalDate(2026, 10, 13)))
        assertTrue("título de 12/10: ${texts(d12)}", title(d12, LocalDate(2026, 10, 12)))
        // Mais recentes no topo: dia 13 acima do 12; dentro do 13, 21:03 acima de 08:00 (Db.dismissed order).
        assertTrue("dia 13 acima do 12", top(d13) < top(d12))
        val ca = SettingsTags.DISMISSED_CARD + a.v
        val cb = SettingsTags.DISMISSED_CARD + b.v
        val cc = SettingsTags.DISMISSED_CARD + c.v
        assertTrue("ordem dos cards a < b < c", top(ca) < top(cb) && top(cb) < top(cc))
        assertTrue("card b sob o dia 13 e acima do dia 12", top(d13) < top(cb) && top(cb) < top(d12))
        assertTrue("card c sob o dia 12", top(d12) < top(cc))

        // O que o card da Pauta mostra (InboxTags.CARD): fonte, hora, estabelecimento e valor.
        // A fonte sai em caixa-alta no card (book 8.4, como na Pauta): o contrato é o nome, não a caixa.
        assertTrue("card a: ${texts(ca)}", texts(ca).any { it.equals(BANK_LABEL, ignoreCase = true) } && shows(ca, "21:03") && shows(ca, "BAR PONTO CERTO") && shows(ca, money(Cents(4_600))))
        assertTrue("card c: ${texts(cc)}", shows(cc, "10:15") && shows(cc, "LOJA CENTRAL") && shows(cc, money(Cents(9_990))))
        assertTrue("Trazer de volta em cada card", listOf(a, b, c).all { shows(SettingsTags.RESTORE + it.v, "Trazer de volta") })
    }

    @Test
    fun `Trazer de volta devolve o recado à Pauta na hora sem snack`() {
        ensureSource()
        val a = compra("a", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        val b = compra("b", 1_250, "PADARIA SAO JOSE", 13, 8, 0)
        for (n in listOf(a, b)) assertTrue(fora { db.dismiss(n) })
        composeRule.setContent { Content() }
        openDismissed()
        await("dois cards") { ids(SettingsTags.DISMISSED_CARD) == setOf(a.v, b.v) }

        tap(SettingsTags.RESTORE + a.v)
        await("card a sai da lista") { ids(SettingsTags.DISMISSED_CARD) == setOf(b.v) }
        assertEquals("a pendente no Db", listOf(a), fora { db.pending() }.map { it.id })
        assertEquals("b continua riscado", listOf(b), fora { db.dismissed() }.map { it.id })
        settle()
        assertFalse("sem snack nem Desfazer (spec §7.6): ${texts(SnackTags.TEXT)}", exists(SnackTags.TEXT) || exists(SnackTags.ACTION))

        tab = Screen.Inbox
        await("a na Pauta") { exists(InboxTags.CARD + a.v) && !exists(InboxTags.CARD + b.v) }
    }

    @Test
    fun `Riscados aberta e o recado lançado ou trazido de volta por fora - a tela segue e o toque não faz nada`() {
        ensureSource()
        val casa = budget("Casa")
        val a = compra("a", 4_600, "BAR PONTO CERTO", 13, 21, 3)
        assertTrue(fora { db.dismiss(a) })
        composeRule.setContent { Content() }
        openDismissed()
        await("card a") { exists(SettingsTags.RESTORE + a.v) }

        // Trazido de volta por fora e lançado em seguida: deixa de ser riscado.
        assertTrue(fora { db.restore(a) })
        assertNotEquals(0L, fora { db.accept(a, form(casa, 4_600)) }.v)
        await("card a some") { !exists(SettingsTags.DISMISSED_CARD + a.v) }
        if (exists(SettingsTags.RESTORE + a.v)) runCatching { tap(SettingsTags.RESTORE + a.v) }
        settle()
        assertEquals("o lançamento feito por fora fica", 1, fora { db.transactions() }.size)
        assertTrue("nada pendente", fora { db.pending() }.isEmpty())
    }

    // =====================================================================================
    // Marcadores (spec §2.9, §7.6; SettingsTags TAG_*)
    // =====================================================================================

    /**
     * Cenário dos marcadores (cada lançamento manual, Casa):
     * t1 [mercado], t2 [mercado, padaria], t4 [mercado], t5 [cafe, mercado]; t3 = Lançar do recado n, [padaria];
     * feira criada sem uso.
     * Contas à mão (TagRow.uses / soleUses):
     *   cafe: uses 1 (t5), sole 0 · feira: 0, 0 · mercado: uses 4 (t1, t2, t4, t5), sole 2 (t1, t4) ·
     *   padaria: uses 2 (t2, t3), sole 1 (t3).
     */
    private inner class Cenario {
        val casa = budget("Casa")
        val n: NoticeId
        val t1: TransactionId
        val t2: TransactionId
        val t3: TransactionId
        val t4: TransactionId
        val t5: TransactionId

        init {
            ensureSource()
            n = compra("n", 2_000, "PADARIA SAO JOSE", 13, 8, 0)
            t1 = manual(casa, 1_000, listOf("mercado"))
            t2 = manual(casa, 1_100, listOf("mercado", "padaria"))
            t3 = fora { db.accept(n, form(casa, 2_000, tags = listOf("padaria"))) }.also { assertNotEquals("accept n", 0L, it.v) }
            t4 = manual(casa, 1_200, listOf("mercado"))
            t5 = manual(casa, 1_300, listOf("cafe", "mercado"))
            assertNotEquals("createTag feira", 0L, fora { db.createTag("feira") }.v)
            val got = fora { db.tags() }.map { Triple(it.name, it.uses, it.soleUses) }
            assertEquals(
                "pré-condição: Db.tags",
                listOf(Triple("cafe", 1, 0), Triple("feira", 0, 0), Triple("mercado", 4, 2), Triple("padaria", 2, 1)), got,
            )
        }

        fun id(name: String): TagId = fora { db.tags() }.first { it.name == name }.id
    }

    @Test
    fun `Marcadores lista por nome com a contagem e segue mudanças por fora`() {
        val c = Cenario()
        composeRule.setContent { Content() }
        openTags()
        val cafe = c.id("cafe"); val feira = c.id("feira"); val mercado = c.id("mercado"); val padaria = c.id("padaria")
        await("quatro linhas") { ids(SettingsTags.TAG_ROW) == setOf(cafe.v, feira.v, mercado.v, padaria.v) }
        val r = { t: TagId -> SettingsTags.TAG_ROW + t.v }
        assertTrue("cafe: ${texts(r(cafe))}", shows(r(cafe), "cafe") && shows(r(cafe), "1 lançamento"))
        assertTrue("feira: ${texts(r(feira))}", shows(r(feira), "feira") && shows(r(feira), "nenhum lançamento"))
        assertTrue("mercado: ${texts(r(mercado))}", shows(r(mercado), "mercado") && shows(r(mercado), "4 lançamentos"))
        assertTrue("padaria: ${texts(r(padaria))}", shows(r(padaria), "padaria") && shows(r(padaria), "2 lançamentos"))
        assertTrue("ordem por nome", top(r(cafe)) < top(r(feira)) && top(r(feira)) < top(r(mercado)) && top(r(mercado)) < top(r(padaria)))

        // Por fora: renomear, um uso a mais, apagar.
        assertTrue(fora { db.renameTag(feira, "zebra") })
        await("feira renomeada para zebra, agora por último", r(feira)) {
            shows(r(feira), "zebra") && top(r(padaria)) < top(r(feira))
        }
        manual(c.casa, 500, listOf("cafe"))
        await("cafe com 2 lançamentos", r(cafe)) { shows(r(cafe), "2 lançamentos") }
        fora { db.deleteTag(padaria) }
        await("padaria some") { !exists(r(padaria)) }
        // t3 só tinha padaria: apagado, o recado n volta à Pauta (§2.9); t2 fica com mercado.
        await("mercado continua com 4 lançamentos (t1, t2, t4, t5)", r(mercado)) { shows(r(mercado), "4 lançamentos") }
        assertTrue("n de volta à Pauta", fora { db.pending() }.any { it.id == c.n })

        for (t in fora { db.tags() }) fora { db.deleteTag(t.id) }
        await("vazio", SettingsTags.TAGS_EMPTY) {
            ids(SettingsTags.TAG_ROW).isEmpty() && shows(SettingsTags.TAGS_EMPTY, "Nenhum marcador.") && shows(SettingsTags.TAGS_EMPTY, "Marcadores nascem ao lançar.")
        }
    }

    @Test
    fun `renomear marcador grava aparado e em caixa-baixa e a linha segue`() {
        val c = Cenario()
        val padaria = c.id("padaria")
        composeRule.setContent { Content() }
        openTags()
        openTagDialog(padaria)
        assertTrue("nome preenchido: ${texts(SettingsTags.TAG_NAME)}", shows(SettingsTags.TAG_NAME, "padaria"))
        assertTrue("Salvar: ${texts(SettingsTags.TAG_SAVE)}", shows(SettingsTags.TAG_SAVE, "Salvar"))
        assertFalse("Salvar desabilitado com o próprio nome", enabled(SettingsTags.TAG_SAVE))

        replaceText(SettingsTags.TAG_NAME, "  Pão Doce  ")
        await("Salvar habilitado", SettingsTags.TAG_SAVE) { enabled(SettingsTags.TAG_SAVE) && shows(SettingsTags.TAG_SAVE, "Salvar") }
        tap(SettingsTags.TAG_SAVE)
        await("diálogo fecha") { !exists(SettingsTags.TAG_DIALOG) }
        await("renomeado no Db") { fora { db.tags() }.firstOrNull { it.id == padaria }?.name == "pão doce" }
        await("linha segue", SettingsTags.TAG_ROW + padaria.v) { shows(SettingsTags.TAG_ROW + padaria.v, "pão doce") }
        assertEquals("os lançamentos continuam com ele", listOf("mercado", "pão doce"), fora { db.transaction(c.t2) }.row.tags)
    }

    @Test
    fun `Salvar do marcador fica desabilitado com nome em branco ou o próprio nome`() {
        val c = Cenario()
        val cafe = c.id("cafe")
        composeRule.setContent { Content() }
        openTags()
        openTagDialog(cafe)
        assertFalse("próprio nome", enabled(SettingsTags.TAG_SAVE))
        replaceText(SettingsTags.TAG_NAME, "")
        await("vazio: desabilitado") { exists(SettingsTags.TAG_SAVE) && !enabled(SettingsTags.TAG_SAVE) }
        replaceText(SettingsTags.TAG_NAME, "    ")
        await("só espaços: desabilitado") { exists(SettingsTags.TAG_SAVE) && !enabled(SettingsTags.TAG_SAVE) }
        replaceText(SettingsTags.TAG_NAME, "cha")
        await("nome novo: habilitado") { enabled(SettingsTags.TAG_SAVE) }
        replaceText(SettingsTags.TAG_NAME, "cafe")
        await("de volta ao próprio nome: desabilitado") { exists(SettingsTags.TAG_SAVE) && !enabled(SettingsTags.TAG_SAVE) }

        val v = version()
        tap(SettingsTags.TAG_CANCEL)
        await("Cancelar fecha") { !exists(SettingsTags.TAG_DIALOG) }
        settle()
        assertEquals("Cancelar não grava", v, version())
        assertEquals("cafe intacto", "cafe", fora { db.tags() }.first { it.id == cafe }.name)
    }

    @Test
    fun `nome de outro marcador vira Juntar com e junta os lançamentos`() {
        val c = Cenario()
        val padaria = c.id("padaria"); val mercado = c.id("mercado")
        composeRule.setContent { Content() }
        openTags()
        openTagDialog(padaria)
        // Aparado e em caixa-baixa, " MERCADO " é o nome de outro marcador.
        replaceText(SettingsTags.TAG_NAME, " MERCADO ")
        await("Juntar com mercado", SettingsTags.TAG_SAVE) { shows(SettingsTags.TAG_SAVE, "Juntar com mercado") && enabled(SettingsTags.TAG_SAVE) }
        tap(SettingsTags.TAG_SAVE)
        await("diálogo fecha") { !exists(SettingsTags.TAG_DIALOG) }
        await("padaria some da lista") { !exists(SettingsTags.TAG_ROW + padaria.v) }
        // mercado: t1, t2, t4, t5 + t3 (era só padaria) = 5.
        await("mercado com 5 lançamentos", SettingsTags.TAG_ROW + mercado.v) { shows(SettingsTags.TAG_ROW + mercado.v, "5 lançamentos") }
        assertTrue("padaria some do Db", fora { db.tags() }.none { it.id == padaria })
        assertEquals("t3 passa para mercado", listOf("mercado"), fora { db.transaction(c.t3) }.row.tags)
        assertEquals("t2 fica só com mercado", listOf("mercado"), fora { db.transaction(c.t2) }.row.tags)
        assertEquals("nenhum lançamento apagado", 5, fora { db.transactions() }.size)
    }

    @Test
    fun `apagar marcador mostra quantos lançamentos somem e confirma ou cancela`() {
        val c = Cenario()
        val mercado = c.id("mercado"); val padaria = c.id("padaria"); val feira = c.id("feira")
        composeRule.setContent { Content() }
        openTags()

        // mercado: soleUses 2 (t1, t4) — plural.
        openDeleteDialog(mercado)
        await("texto do diálogo de mercado", SettingsTags.TAG_DELETE_DIALOG) {
            shows(SettingsTags.TAG_DELETE_DIALOG, "Apagar o marcador mercado?") &&
                shows(SettingsTags.TAG_DELETE_DIALOG, "2 lançamentos ficam sem marcador e são apagados; os recados deles voltam à Pauta.")
        }
        val v = version()
        tap(SettingsTags.TAG_DELETE_DIALOG + ".cancel")
        await("cancelar fecha") { !exists(SettingsTags.TAG_DELETE_DIALOG) }
        settle()
        assertEquals("cancelar não grava", v, version())
        assertEquals(5, fora { db.transactions() }.size)

        // feira: soleUses 0.
        openDeleteDialog(feira)
        await("texto do diálogo de feira", SettingsTags.TAG_DELETE_DIALOG) {
            shows(SettingsTags.TAG_DELETE_DIALOG, "Apagar o marcador feira?") && shows(SettingsTags.TAG_DELETE_DIALOG, "Nenhum lançamento é apagado.")
        }
        tap(SettingsTags.TAG_DELETE_DIALOG + ".cancel")
        await("fecha") { !exists(SettingsTags.TAG_DELETE_DIALOG) }

        // padaria: soleUses 1 (t3, do recado n) — singular; confirmar apaga.
        openDeleteDialog(padaria)
        await("texto do diálogo de padaria", SettingsTags.TAG_DELETE_DIALOG) {
            shows(SettingsTags.TAG_DELETE_DIALOG, "Apagar o marcador padaria?") &&
                shows(SettingsTags.TAG_DELETE_DIALOG, "1 lançamento fica sem marcador e é apagado; o recado dele volta à Pauta.")
        }
        assertTrue("botão Apagar", shows(SettingsTags.TAG_DELETE_DIALOG + ".confirm", "Apagar"))
        tap(SettingsTags.TAG_DELETE_DIALOG + ".confirm")
        await("diálogo fecha e a linha some") { !exists(SettingsTags.TAG_DELETE_DIALOG) && !exists(SettingsTags.TAG_ROW + padaria.v) }
        val txs = fora { db.transactions() }.map { it.id }.toSet()
        assertEquals("t3 apagado; os outros ficam", setOf(c.t1, c.t2, c.t4, c.t5), txs)
        assertEquals("t2 fica com mercado", listOf("mercado"), fora { db.transaction(c.t2) }.row.tags)
        assertTrue("o recado de t3 volta à Pauta", fora { db.pending() }.any { it.id == c.n })
    }

    @Test
    fun `marcador apagado por fora com o diálogo dele aberto - Salvar não recria nada`() {
        val c = Cenario()
        val padaria = c.id("padaria")
        composeRule.setContent { Content() }
        openTags()
        openTagDialog(padaria)
        replaceText(SettingsTags.TAG_NAME, "doce")
        await("Salvar habilitado") { enabled(SettingsTags.TAG_SAVE) }

        fora { db.deleteTag(padaria) }
        val depois = fora { db.tags() }
        val txs = fora { db.transactions() }
        await("a linha some") { !exists(SettingsTags.TAG_ROW + padaria.v) }
        if (exists(SettingsTags.TAG_SAVE) && enabled(SettingsTags.TAG_SAVE)) runCatching { tap(SettingsTags.TAG_SAVE) }
        settle()
        if (exists(SettingsTags.TAG_CANCEL)) runCatching { tap(SettingsTags.TAG_CANCEL) }
        settle()
        assertEquals("nenhum marcador criado ou mudado", depois, fora { db.tags() })
        assertTrue("nada chamado doce", fora { db.tags() }.none { it.name == "doce" })
        assertEquals("lançamentos intactos", txs, fora { db.transactions() })
        await("lista sem o marcador apagado") { ids(SettingsTags.TAG_ROW) == depois.map { it.id.v }.toSet() }
    }

    @Test
    fun `marcador apagado por fora com o diálogo de apagar aberto - confirmar não apaga mais nada`() {
        val c = Cenario()
        val mercado = c.id("mercado")
        composeRule.setContent { Content() }
        openTags()
        openDeleteDialog(mercado)

        fora { db.deleteTag(mercado) }
        val tags = fora { db.tags() }
        val txs = fora { db.transactions() }
        // mercado apagado: t1 e t4 (só mercado) somem; t2 fica com padaria, t5 com cafe.
        assertEquals(setOf(c.t2, c.t3, c.t5), txs.map { it.id }.toSet())
        await("a linha some") { !exists(SettingsTags.TAG_ROW + mercado.v) }
        if (exists(SettingsTags.TAG_DELETE_DIALOG + ".confirm")) runCatching { tap(SettingsTags.TAG_DELETE_DIALOG + ".confirm") }
        settle()
        assertEquals("marcadores intactos", tags, fora { db.tags() })
        assertEquals("lançamentos intactos", txs, fora { db.transactions() })
    }

    // =====================================================================================
    // Início do mês e da semana (spec §4, §7.6, D41; SettingsTags START_*)
    // =====================================================================================

    @Test
    fun `rascunho de Início do mês e da semana sobrevive à recriação sem gravar`() {
        val restore = StateRestorationTester(composeRule)
        restore.setContent { Content() }
        openStartDays()
        await("começa nos ajustes salvos") { textIs(SettingsTags.START_DAY, "1") && selected(SettingsTags.START_WEEK + "monday") }
        repeat(4) { tap(SettingsTags.START_DAY + ".plus") }
        tap(SettingsTags.START_WEEK + "sunday")
        await("rascunho dia 5 domingo", SettingsTags.START_DAY) { textIs(SettingsTags.START_DAY, "5") && selected(SettingsTags.START_WEEK + "sunday") }

        restore.emulateSavedInstanceStateRestore()
        await("rascunho restaurado", SettingsTags.START_DAY) {
            textIs(SettingsTags.START_DAY, "5") && selected(SettingsTags.START_WEEK + "sunday") && !selected(SettingsTags.START_WEEK + "monday")
        }
        assertTrue("Salvar habilitado depois da recriação", enabled(SettingsTags.START_SAVE))
        val s = fora { db.settings() }
        assertEquals("nada gravado", 1 to DayOfWeek.MONDAY, s.monthStartDay to s.weekStart)

        tap(SettingsTags.START_SAVE)
        await("salvo e de volta a Ajustes") { exists(SettingsTags.LIST) && !exists(SettingsTags.START_DAY) }
        val t = fora { db.settings() }
        assertEquals(5 to DayOfWeek.SUNDAY, t.monthStartDay to t.weekStart)
    }

    @Test
    fun `Salvar fica desabilitado enquanto o rascunho é igual ao salvo e o stepper para em 1 e 31`() {
        assertTrue(fora { db.setMonthStartDay(30, DIA) })
        composeRule.setContent { Content() }
        openStartDays()
        await("começa no dia 30") { textIs(SettingsTags.START_DAY, "30") }
        assertFalse("igual ao salvo: desabilitado", enabled(SettingsTags.START_SAVE))
        tap(SettingsTags.START_DAY + ".plus")
        await("31 e Salvar habilitado") { textIs(SettingsTags.START_DAY, "31") && enabled(SettingsTags.START_SAVE) }
        assertFalse("+ desabilitado em 31", enabled(SettingsTags.START_DAY + ".plus"))
        tap(SettingsTags.START_DAY + ".minus")
        await("de volta a 30: desabilitado") { textIs(SettingsTags.START_DAY, "30") && !enabled(SettingsTags.START_SAVE) }
        tap(SettingsTags.START_WEEK + "friday")
        await("semana mudou: habilitado") { selected(SettingsTags.START_WEEK + "friday") && enabled(SettingsTags.START_SAVE) }
        tap(SettingsTags.START_WEEK + "monday")
        await("semana de volta: desabilitado") { selected(SettingsTags.START_WEEK + "monday") && !enabled(SettingsTags.START_SAVE) }

        pressBack()
        await("Ajustes") { exists(SettingsTags.LIST) }
        assertTrue(fora { db.setMonthStartDay(2, DIA) })
        tap(SettingsTags.START_DAYS)
        await("começa no dia 2") { textIs(SettingsTags.START_DAY, "2") }
        tap(SettingsTags.START_DAY + ".minus")
        await("1: − desabilitado") { textIs(SettingsTags.START_DAY, "1") && !enabled(SettingsTags.START_DAY + ".minus") }
        assertEquals("nada gravado", 2, fora { db.settings() }.monthStartDay)
    }

    @Test
    fun `sem edição de passo Salvar grava os dois sem diálogo e volta a Ajustes`() {
        // Verba sem edição: nada a apagar (D41: sem aviso).
        budget("Casa")
        composeRule.setContent { Content() }
        openStartDays()
        repeat(4) { tap(SettingsTags.START_DAY + ".plus") }
        tap(SettingsTags.START_WEEK + "sunday")
        await("rascunho") { textIs(SettingsTags.START_DAY, "5") && enabled(SettingsTags.START_SAVE) }
        tap(SettingsTags.START_SAVE)
        await("de volta a Ajustes", SettingsTags.START_DIALOG) { exists(SettingsTags.LIST) && !exists(SettingsTags.START_DAY) }
        assertFalse("sem diálogo", exists(SettingsTags.START_DIALOG))
        val s = fora { db.settings() }
        assertEquals(5 to DayOfWeek.SUNDAY, s.monthStartDay to s.weekStart)
        await("linha de Ajustes segue", SettingsTags.START_DAYS) { shows(SettingsTags.START_DAYS, "dia 5 · domingo") }
    }

    @Test
    fun `edição só em mês passado não pede aviso`() {
        // Verba criada em setembro com passo editado em setembro (semana de 7/9, segunda).
        val casa = fora { db.createBudget("Casa", Cents(50_000), LocalDate(2026, 9, 10)) }
        assertNotEquals(0L, casa.v)
        val set = YearMonth(2026, 9)
        val week = fora { db.budgetDetail(casa, set, DIA) }.month.weeks.first { it.start == LocalDate(2026, 9, 7) }
        assertFalse(week.locked)
        assertTrue(fora { db.setStep(casa, set, week.start, Cents(1_000)) })
        fora { db.ensureMonths(YearMonth(2026, 10)) }
        composeRule.setContent { Content() }
        openStartDays()
        // Só a semana muda; o dia continua 1. Corte (spec §4, D10): início do mês de hoje = 1/10.
        // 7/9 < 1/10: nada é apagado, então Salvar grava direto.
        tap(SettingsTags.START_WEEK + "sunday")
        await("Salvar habilitado") { enabled(SettingsTags.START_SAVE) }
        tap(SettingsTags.START_SAVE)
        await("de volta a Ajustes sem diálogo", SettingsTags.START_DIALOG) { exists(SettingsTags.LIST) && !exists(SettingsTags.START_DAY) }
        assertFalse(exists(SettingsTags.START_DIALOG))
        assertEquals(DayOfWeek.SUNDAY, fora { db.settings() }.weekStart)
    }

    @Test
    fun `edição a apagar abre o diálogo e cancelar mantém o rascunho e confirmar grava e volta`() {
        val casa = budget("Casa")
        val out = YearMonth(2026, 10)
        // Padrão: outubro de 1/10 a 31/10, semanas na segunda: 1–4, 5–11, 12–18, 19–25, 26–31. Edita 5/10.
        assertTrue(fora { db.setStep(casa, out, LocalDate(2026, 10, 5), Cents(1_000)) })
        composeRule.setContent { Content() }
        openStartDays()
        repeat(4) { tap(SettingsTags.START_DAY + ".plus") }
        await("rascunho dia 5") { textIs(SettingsTags.START_DAY, "5") }
        tap(SettingsTags.START_SAVE)
        // Corte: início do mês de hoje pelo dia velho (1/10) ou novo (5/10), o mais cedo = 1/10; 5/10 ≥ 1/10.
        await("diálogo", SettingsTags.START_DIALOG) {
            shows(SettingsTags.START_DIALOG, "Mudar o início do mês?") &&
                shows(
                    SettingsTags.START_DIALOG,
                    "As edições de passo de outubro em diante são apagadas, em todas as verbas, e os passos voltam ao padrão. Meses passados são recalculados com o dia novo.",
                )
        }
        assertTrue("botão Mudar", shows(SettingsTags.START_DIALOG + ".confirm", "Mudar"))

        val v = version()
        tap(SettingsTags.START_DIALOG + ".cancel")
        await("diálogo fecha, rascunho fica") { !exists(SettingsTags.START_DIALOG) && textIs(SettingsTags.START_DAY, "5") && enabled(SettingsTags.START_SAVE) }
        settle()
        assertEquals("cancelar não grava", v, version())
        assertEquals(1, fora { db.settings() }.monthStartDay)
        assertTrue("a edição continua", fora { db.budgetDetail(casa, out, DIA) }.month.weeks.any { it.edited })

        tap(SettingsTags.START_SAVE)
        await("diálogo de novo") { exists(SettingsTags.START_DIALOG + ".confirm") }
        tap(SettingsTags.START_DIALOG + ".confirm")
        await("de volta a Ajustes") { exists(SettingsTags.LIST) && !exists(SettingsTags.START_DAY) && !exists(SettingsTags.START_DIALOG) }
        val s = fora { db.settings() }
        assertEquals(5 to DayOfWeek.MONDAY, s.monthStartDay to s.weekStart)
        fora { db.ensureMonths(out) }
        assertTrue("edições apagadas", fora { db.budgetDetail(casa, out, DIA) }.month.weeks.none { it.edited })
        await("linha de Ajustes", SettingsTags.START_DAYS) { shows(SettingsTags.START_DAYS, "dia 5 · segunda") }
    }

    @Test
    fun `título do diálogo diz o que o rascunho muda`() {
        val casa = budget("Casa")
        assertTrue(fora { db.setStep(casa, YearMonth(2026, 10), LocalDate(2026, 10, 5), Cents(1_000)) })
        composeRule.setContent { Content() }
        openStartDays()

        tap(SettingsTags.START_WEEK + "sunday")
        tap(SettingsTags.START_SAVE)
        await("só a semana", SettingsTags.START_DIALOG) { shows(SettingsTags.START_DIALOG, "Mudar o início da semana?") }
        tap(SettingsTags.START_DIALOG + ".cancel")
        await("fecha") { !exists(SettingsTags.START_DIALOG) }

        tap(SettingsTags.START_DAY + ".plus")
        tap(SettingsTags.START_SAVE)
        await("mês e semana", SettingsTags.START_DIALOG) { shows(SettingsTags.START_DIALOG, "Mudar o início do mês e da semana?") }
        tap(SettingsTags.START_DIALOG + ".cancel")
        await("fecha") { !exists(SettingsTags.START_DIALOG) }

        tap(SettingsTags.START_WEEK + "monday")
        tap(SettingsTags.START_SAVE)
        await("só o mês", SettingsTags.START_DIALOG) {
            shows(SettingsTags.START_DIALOG, "Mudar o início do mês?") && !shows(SettingsTags.START_DIALOG, "semana?")
        }
        tap(SettingsTags.START_DIALOG + ".cancel")
        await("fecha") { !exists(SettingsTags.START_DIALOG) }
        val s = fora { db.settings() }
        assertEquals("nada gravado", 1 to DayOfWeek.MONDAY, s.monthStartDay to s.weekStart)
    }

    @Test
    fun `o mês do diálogo é o de hoje pelos ajustes salvos`() {
        // Dia 20 salvo: hoje 14/10 cai no mês que começa em 20/9 (setembro, §3).
        assertTrue(fora { db.setMonthStartDay(20, DIA) })
        val casa = budget("Casa")
        val set = YearMonth(2026, 9)
        val weeks = fora { db.budgetDetail(casa, set, DIA) }.month.weeks
        assertEquals("pré-condição: janela de setembro começa em 20/9", LocalDate(2026, 9, 20), weeks.first().start)
        val w = weeks.first { !it.locked && it.start > LocalDate(2026, 9, 20) }
        assertTrue(fora { db.setStep(casa, set, w.start, Cents(1_000)) })
        composeRule.setContent { Content() }
        openStartDays()
        await("começa no 20") { textIs(SettingsTags.START_DAY, "20") }
        tap(SettingsTags.START_DAY + ".plus")
        tap(SettingsTags.START_SAVE)
        // Corte: 20/9 (velho) ou 21/9 (novo), o mais cedo = 20/9; a edição em ${w.start} fica depois.
        await("diálogo com setembro", SettingsTags.START_DIALOG) {
            shows(SettingsTags.START_DIALOG, "As edições de passo de setembro em diante são apagadas")
        }
        tap(SettingsTags.START_DIALOG + ".confirm")
        await("de volta a Ajustes") { exists(SettingsTags.LIST) }
        assertEquals(21, fora { db.settings() }.monthStartDay)
        for (m in listOf(set, YearMonth(2026, 10))) {
            fora { db.ensureMonths(m) }
            assertTrue("sem edição em $m", fora { db.budgetDetail(casa, m, DIA) }.month.weeks.none { it.edited })
        }
    }

    @Test
    fun `edição feita por fora com a tela aberta - Salvar pergunta antes`() {
        val casa = budget("Casa")
        composeRule.setContent { Content() }
        openStartDays()
        repeat(2) { tap(SettingsTags.START_DAY + ".plus") }
        await("rascunho dia 3") { textIs(SettingsTags.START_DAY, "3") && enabled(SettingsTags.START_SAVE) }

        // Por fora, depois de a tela abrir: edição em 12/10 (≥ corte 1/10).
        assertTrue(fora { db.setStep(casa, YearMonth(2026, 10), LocalDate(2026, 10, 12), Cents(2_000)) })
        settle()
        tap(SettingsTags.START_SAVE)
        await("diálogo, porque agora há edição a apagar", SettingsTags.START_DIALOG) { exists(SettingsTags.START_DIALOG) }
        assertEquals("nada gravado antes de confirmar", 1, fora { db.settings() }.monthStartDay)
        assertTrue(fora { db.budgetDetail(casa, YearMonth(2026, 10), DIA) }.month.weeks.any { it.edited })
    }

    // =====================================================================================
    // Backup (spec §7.6, D14; SettingsTags BACKUP_*)
    // =====================================================================================

    @Test
    fun `Exportar grava uma cópia que importFrom aceita com o nome do dia`() {
        budget("Casa")
        assertTrue(fora { db.setMonthStartDay(5, DIA) })
        val out = File(tmp.root, "export-out.db")
        picker.answer = Uri.fromFile(out)
        composeRule.setContent { Content() }
        openBackup()
        tap(SettingsTags.BACKUP_EXPORT)
        await("snack Backup exportado.", SnackTags.TEXT) { shows(SnackTags.TEXT, "Backup exportado.") }
        assertEquals("um seletor", 1, picker.launches.size)
        assertEquals("CreateDocument com o nome do dia (LocalToday)", "CreateDocument" to "margem-2026-10-14.db", picker.launches.single())
        assertTrue("arquivo escrito", out.length() > 0)

        // Por fora: muda tudo; restaurar a cópia volta ao estado exportado.
        budget("Lazer")
        assertTrue(fora { db.setMonthStartDay(10, DIA) })
        assertTrue("importFrom aceita o exportado", fora { db.importFrom(out.path) })
        assertEquals(5, fora { db.settings() }.monthStartDay)
        assertEquals(listOf("Casa"), budgetNames())

        pressBack()
        await("Ajustes segue o banco restaurado", SettingsTags.START_DAYS) { shows(SettingsTags.START_DAYS, "dia 5 · segunda") }
    }

    @Test
    fun `Exportar para onde não dá para escrever avisa e escolha cancelada não faz nada`() {
        budget("Casa")
        composeRule.setContent { Content() }
        openBackup()

        picker.answer = null
        val v = version()
        tap(SettingsTags.BACKUP_EXPORT)
        await("seletor aberto") { picker.launches.size == 1 }
        settle()
        assertFalse("escolha cancelada: sem snack ${texts(SnackTags.TEXT)}", exists(SnackTags.TEXT))
        assertEquals(v, version())

        picker.answer = Uri.fromFile(File(tmp.root, "nao-existe/margem.db"))
        tap(SettingsTags.BACKUP_EXPORT)
        await("snack de falha", SnackTags.TEXT) { shows(SnackTags.TEXT, "Não deu para exportar o backup.") }
        assertEquals(listOf("Casa"), budgetNames())
    }

    @Test
    fun `Importar pede confirmação - cancelar não muda nada e Restaurar troca o banco`() {
        budget("Casa")
        val backup = File(tmp.root, "backup-a.db")
        assertTrue(fora { db.exportTo(backup.path) })
        budget("Lazer")
        fora { db.setTheme(Theme.DARK) }
        picker.answer = Uri.fromFile(backup)
        composeRule.setContent { Content() }
        openBackup()

        tap(SettingsTags.BACKUP_IMPORT)
        await("diálogo de restaurar", SettingsTags.BACKUP_DIALOG) {
            shows(SettingsTags.BACKUP_DIALOG, "Restaurar este backup?") &&
                shows(SettingsTags.BACKUP_DIALOG, "Verbas, lançamentos, recados e ajustes atuais dão lugar aos do backup. Não dá para desfazer.")
        }
        assertEquals("OpenDocument", picker.launches.single().first)
        assertTrue("botão Restaurar", shows(SettingsTags.BACKUP_DIALOG + ".confirm", "Restaurar"))
        val v = version()
        tap(SettingsTags.BACKUP_DIALOG + ".cancel")
        await("fecha") { !exists(SettingsTags.BACKUP_DIALOG) }
        settle()
        assertEquals("cancelar não grava", v, version())
        assertEquals(listOf("Casa", "Lazer"), budgetNames())
        assertEquals(Theme.DARK, fora { db.settings() }.theme)

        tap(SettingsTags.BACKUP_IMPORT)
        await("diálogo de novo") { exists(SettingsTags.BACKUP_DIALOG + ".confirm") }
        tap(SettingsTags.BACKUP_DIALOG + ".confirm")
        await("snack Backup restaurado.", SnackTags.TEXT) { shows(SnackTags.TEXT, "Backup restaurado.") }
        assertEquals(listOf("Casa"), budgetNames())
        assertEquals(Theme.SYSTEM, fora { db.settings() }.theme)
        assertTrue("version incrementada", version() > v)

        pressBack()
        await("Ajustes segue o tema restaurado") { exists(SettingsTags.LIST) && onlySelected(SettingsTags.THEME, "system") }
    }

    @Test
    fun `Importar arquivo que não é backup do Margem avisa sem mudar nada`() {
        budget("Casa")
        val lixo = File(tmp.root, "notas.txt").apply { writeText("isto não é um banco do Margem") }
        picker.answer = Uri.fromFile(lixo)
        composeRule.setContent { Content() }
        openBackup()
        val v = version()
        tap(SettingsTags.BACKUP_IMPORT)
        await("diálogo") { exists(SettingsTags.BACKUP_DIALOG + ".confirm") }
        settle()
        assertEquals("nada muda antes de confirmar", v, version())
        tap(SettingsTags.BACKUP_DIALOG + ".confirm")
        await("snack de recusa", SnackTags.TEXT) { shows(SnackTags.TEXT, "Este arquivo não é um backup do Margem.") }
        assertEquals(v, version())
        assertEquals(listOf("Casa"), budgetNames())
        assertEquals("isto não é um banco do Margem", lixo.readText())
    }

    @Test
    fun `Importar com a escolha cancelada não abre o diálogo nem muda nada`() {
        budget("Casa")
        picker.answer = null
        composeRule.setContent { Content() }
        openBackup()
        val v = version()
        tap(SettingsTags.BACKUP_IMPORT)
        await("seletor aberto") { picker.launches.size == 1 }
        settle()
        assertFalse("sem diálogo", exists(SettingsTags.BACKUP_DIALOG))
        assertFalse("sem snack", exists(SnackTags.TEXT))
        assertEquals(v, version())
        assertEquals(listOf("Casa"), budgetNames())
    }

    @Test
    fun `toque duplo em Restaurar restaura uma vez só`() {
        budget("Casa")
        val backup = File(tmp.root, "backup-b.db")
        assertTrue(fora { db.exportTo(backup.path) })
        budget("Lazer")
        picker.answer = Uri.fromFile(backup)
        composeRule.setContent { Content() }
        openBackup()
        tap(SettingsTags.BACKUP_IMPORT)
        await("diálogo") { exists(SettingsTags.BACKUP_DIALOG + ".confirm") }
        settle()
        val v = version()
        doubleTap(SettingsTags.BACKUP_DIALOG + ".confirm")
        await("snack Backup restaurado.", SnackTags.TEXT) { shows(SnackTags.TEXT, "Backup restaurado.") }
        settle()
        // D6/D14: um importFrom = um incremento de version.
        assertEquals("um importFrom só", v + 1, version())
        assertEquals(listOf("Casa"), budgetNames())
    }

    @Test
    fun `toque duplo em Exportar abre um seletor só`() {
        val out = File(tmp.root, "export-duplo.db")
        picker.answer = Uri.fromFile(out)
        composeRule.setContent { Content() }
        openBackup()
        doubleTap(SettingsTags.BACKUP_EXPORT)
        await("snack Backup exportado.", SnackTags.TEXT) { shows(SnackTags.TEXT, "Backup exportado.") }
        settle()
        assertEquals("um seletor só: ${picker.launches}", 1, picker.launches.size)
        assertTrue(fora { db.importFrom(out.path) })
    }

    // =====================================================================================
    // Importar do cobudget (spec §7.6, D3, D4, D40; SettingsTags COBUDGET_*)
    // =====================================================================================

    @Test
    fun `cobudget - arquivo recusado avisa sem gravar e escolha cancelada não faz nada`() {
        val texto = File(tmp.root, "texto.db").apply { writeText("não sou sqlite") }
        val v6 = cobudgetFile("v6.db", listOf(row("a", 13, 10, 0, 1_000)), userVersion = 6)
        composeRule.setContent { Content() }
        openCobudget()
        assertFalse("sem resultado antes de importar", exists(SettingsTags.COBUDGET_RESULT))

        picker.answer = null
        val v = version()
        tap(SettingsTags.COBUDGET_PICK)
        await("seletor aberto") { picker.launches.size == 1 }
        settle()
        assertFalse("cancelado: sem resultado", exists(SettingsTags.COBUDGET_RESULT))

        for (f in listOf(texto, v6)) {
            picker.answer = Uri.fromFile(f)
            tap(SettingsTags.COBUDGET_PICK)
            await("recusa de ${f.name}", SettingsTags.COBUDGET_RESULT) {
                shows(SettingsTags.COBUDGET_RESULT, "Este arquivo não é um backup do cobudget.")
            }
            settle()
            assertEquals("nada gravado com ${f.name}", v, version())
        }
        assertTrue(fora { db.pending() }.isEmpty() && fora { db.dismissed() }.isEmpty())
        assertTrue("OpenDocument", picker.launches.all { it.first == "OpenDocument" })
    }

    @Test
    fun `cobudget - importação normal diz quantos entraram e reimportar não duplica`() {
        // 7 linhas: 3 pendentes (uma de app que não é fonte, D4), 2 riscadas, 1 repetida (mesma chave,
        // conteúdo e when ≠ 0 da linha a), 1 sem texto. Entram 5 (3 na Pauta, 2 em Riscados), 2 ficam de fora.
        val rows = listOf(
            row("a", 13, 10, 0, 1_000),
            row("b", 12, 9, 0, 2_000, pkg = "com.old.bank", label = "Banco Antigo"),
            row("c", 11, 18, 30, 3_000),
            row("d", 10, 12, 0, 4_000, dismissed = 1),
            row("e", 9, 12, 0, 5_000, dismissed = 1),
            row("a", 13, 10, 0, 1_000, postedShift = 3_600_000L),
            CobRow(key = "f", postedAt = at(8, 8, 0), whenMs = at(8, 8, 0), title = null, text = null, bigText = null),
        )
        val file = cobudgetFile("cobudget.db", rows)
        picker.answer = Uri.fromFile(file)
        composeRule.setContent { Content() }
        openCobudget()
        tap(SettingsTags.COBUDGET_PICK)
        await("resultado", SettingsTags.COBUDGET_RESULT) {
            shows(SettingsTags.COBUDGET_RESULT, "5 recados importados: 3 na Pauta, 2 em Riscados. 2 não entraram (repetidos ou sem texto).")
        }
        val pend = fora { db.pending() }
        val disp = fora { db.dismissed() }
        assertEquals("pendentes", setOf("a", "b", "c"), pend.map { it.raw.key }.toSet())
        assertEquals("um recado por chave", 3, pend.size)
        assertEquals("riscados", setOf("d", "e"), disp.map { it.raw.key }.toSet())

        // Reimportar: tudo repetido ou sem texto.
        val v = version()
        tap(SettingsTags.COBUDGET_PICK)
        await("resultado da reimportação", SettingsTags.COBUDGET_RESULT) {
            shows(SettingsTags.COBUDGET_RESULT, "Nenhum recado importado. 7 não entraram (repetidos ou sem texto).")
        }
        settle()
        assertEquals("reimportar não grava", v, version())
        assertEquals(pend.map { it.id }, fora { db.pending() }.map { it.id })
        assertEquals(disp.map { it.id }, fora { db.dismissed() }.map { it.id })

        // Resultado some ao sair da tela.
        pressBack()
        await("Ajustes") { exists(SettingsTags.LIST) }
        tap(SettingsTags.COBUDGET)
        await("tela de novo") { exists(SettingsTags.COBUDGET_PICK) }
        assertFalse("sem resultado depois de sair", exists(SettingsTags.COBUDGET_RESULT))

        // Os pendentes aparecem na Pauta; os riscados em Riscados.
        tab = Screen.Inbox
        await("os três na Pauta") { pend.all { exists(InboxTags.CARD + it.id.v) } }
        assertFalse("riscados fora da Pauta", disp.any { exists(InboxTags.CARD + it.id.v) })
        tab = Screen.Settings
        await("Ajustes") { exists(SettingsTags.DISMISSED) }
        tap(SettingsTags.DISMISSED)
        await("os dois em Riscados") { ids(SettingsTags.DISMISSED_CARD) == disp.map { it.id.v }.toSet() }
    }

    @Test
    fun `cobudget - singular e sem nenhum de fora`() {
        val um = cobudgetFile(
            "um.db",
            listOf(row("a", 13, 10, 0, 1_000), CobRow(key = "f", postedAt = at(8, 8, 0), whenMs = at(8, 8, 0), title = null, text = null, bigText = null)),
        )
        val tres = cobudgetFile("tres.db", listOf(row("x", 12, 10, 0, 700), row("y", 12, 11, 0, 800), row("z", 12, 12, 0, 900, dismissed = 1)))
        composeRule.setContent { Content() }
        openCobudget()

        picker.answer = Uri.fromFile(um)
        tap(SettingsTags.COBUDGET_PICK)
        await("singular", SettingsTags.COBUDGET_RESULT) {
            shows(SettingsTags.COBUDGET_RESULT, "1 recado importado: 1 na Pauta, 0 em Riscados. 1 não entrou (repetido ou sem texto).")
        }

        picker.answer = Uri.fromFile(tres)
        tap(SettingsTags.COBUDGET_PICK)
        await("três, nenhum de fora", SettingsTags.COBUDGET_RESULT) {
            shows(SettingsTags.COBUDGET_RESULT, "3 recados importados: 2 na Pauta, 1 em Riscados.") && !shows(SettingsTags.COBUDGET_RESULT, "não entr")
        }
    }

    @Test
    fun `cobudget - Pauta aberta mostra os recados importados por fora`() {
        val file = cobudgetFile("fora.db", listOf(row("a", 13, 10, 0, 1_000), row("b", 12, 9, 0, 2_000)))
        composeRule.setContent { Content() }
        tab = Screen.Inbox
        await("Pauta limpa") { exists(InboxTags.EMPTY) }
        val r = fora { importCobudget(db, file.path) }
        assertEquals(CobudgetImport(opened = true, pending = 2, dismissed = 0, skipped = 0), r)
        val ids = fora { db.pending() }.map { it.id }
        await("os dois cards") { ids.all { exists(InboxTags.CARD + it.v) } && !exists(InboxTags.EMPTY) }
    }

    @Test
    fun `toque duplo em Escolher arquivo importa uma vez só`() {
        val file = cobudgetFile("duplo.db", listOf(row("a", 13, 10, 0, 1_000), row("b", 12, 9, 0, 2_000)))
        picker.answer = Uri.fromFile(file)
        composeRule.setContent { Content() }
        openCobudget()
        doubleTap(SettingsTags.COBUDGET_PICK)
        await("resultado", SettingsTags.COBUDGET_RESULT) { exists(SettingsTags.COBUDGET_RESULT) }
        settle()
        // Uma importação só: o resultado é o da primeira, não o de uma segunda que acharia tudo repetido.
        assertTrue(
            "resultado da primeira importação: ${texts(SettingsTags.COBUDGET_RESULT)}",
            shows(SettingsTags.COBUDGET_RESULT, "2 recados importados: 2 na Pauta, 0 em Riscados.") && !shows(SettingsTags.COBUDGET_RESULT, "não entr"),
        )
        assertEquals(2, fora { db.pending() }.size)
    }

    // ---- importCobudget concorrente com capture do mesmo recado (D4, D29) ----

    @Test
    fun `importCobudget concorrente com capture do mesmo recado deixa um recado só`() {
        val seed = 20261014L
        val rnd = Random(seed)
        repeat(ROUNDS) { step ->
            val d = Db(AndroidSQLiteDriver(), tmp.newFile("round$step.db").also { it.delete() }.path)
            try {
                runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { d.addSource(BANK, BANK_LABEL, at(1, 8, 0)) } }
                val dismissed = rnd.nextBoolean()
                val shared = row("shared$step", 13, 10, step % 60, 4_200 + step.toLong(), dismissed = if (dismissed) 1 else 0)
                // Outras linhas, de chaves distintas, para a importação durar e se intercalar com a captura.
                val others = (0 until OTHERS).map { i -> row("o$step-$i", 12, 8 + i % 10, i % 60, 100L + i) }
                val order = rnd.nextInt(others.size + 1)
                val file = cobudgetFile("round$step-cob.db", others.subList(0, order) + shared + others.subList(order, others.size))
                val raw = RawNotice(
                    packageName = BANK, appLabel = BANK_LABEL, key = shared.key, postedAt = shared.postedAt, whenMs = shared.whenMs,
                    title = shared.title!!, text = shared.text!!, bigText = shared.bigText!!,
                )
                val waitImport = rnd.nextLong(0, 6)
                val waitCapture = rnd.nextLong(0, 6)
                val (imp, captured) = runBlocking(Dispatchers.Default) {
                    withTimeout(STORM_TIMEOUT_MS) {
                        val a = async { delay(waitImport); importCobudget(d, file.path) }
                        val b = async { delay(waitCapture); capture(d, raw) }
                        a.await() to b.await()
                    }
                }
                val all = runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { d.pending() + d.dismissed() } }
                val ctx = "seed=$seed step=$step dismissed=$dismissed order=$order waits=$waitImport/$waitCapture import=$imp captured=$captured"
                assertTrue("arquivo aceito; $ctx", imp.opened)
                assertEquals("um recado só com a chave compartilhada; $ctx", 1, all.count { it.raw.key == shared.key })
                // Entram as OTHERS e, se a captura não venceu, a compartilhada; o total da importação é OTHERS + 1.
                assertEquals("contas da importação; $ctx", OTHERS + 1, imp.pending + imp.dismissed + imp.skipped)
                val sharedEntered = imp.pending + imp.dismissed - OTHERS
                assertEquals("ou a captura ou a importação gravou; $ctx", 1, sharedEntered + (if (captured) 1 else 0))
                assertEquals("as outras todas; $ctx", OTHERS, all.count { it.raw.key.startsWith("o$step-") })
                val one = all.single { it.raw.key == shared.key }
                val expected = if (!captured && dismissed) NoticeStatus.DISMISSED else NoticeStatus.PENDING
                assertEquals("estado do recado compartilhado; $ctx", expected, one.status)
            } finally {
                d.close()
            }
        }
    }

    @Test
    fun `importCobudget e capture do mesmo recado com a Pauta aberta - um card só`() {
        ensureSource()
        val shared = row("shared", 13, 21, 3, 4_600)
        // Poucas linhas: todos os cards cabem na tela (a Pauta é preguiçosa).
        val file = cobudgetFile("pauta.db", (0 until 3).map { i -> row("o$i", 12, 8 + i, i, 100L + i) } + shared)
        composeRule.setContent { Content() }
        tab = Screen.Inbox
        await("Pauta limpa") { exists(InboxTags.EMPTY) }
        val raw = RawNotice(
            packageName = BANK, appLabel = BANK_LABEL, key = shared.key, postedAt = shared.postedAt, whenMs = shared.whenMs,
            title = shared.title!!, text = shared.text!!, bigText = shared.bigText!!,
        )
        val (imp, captured) = runBlocking(Dispatchers.Default) {
            withTimeout(STORM_TIMEOUT_MS) {
                val a = async { importCobudget(db, file.path) }
                val b = async { capture(db, raw) }
                a.await() to b.await()
            }
        }
        val ctx = "import=$imp captured=$captured"
        val sharedRows = fora { db.pending() }.filter { it.raw.key == "shared" }
        assertEquals("um recado só; $ctx", 1, sharedRows.size)
        val all = fora { db.pending() }
        assertEquals("todos pendentes; $ctx", 4, all.size)
        await("um card por recado; $ctx") { ids(InboxTags.CARD) == all.map { it.id.v }.toSet() }
    }

    // ---- Helpers: Db ----

    private fun ensureSource() {
        if (fora { db.sources() }.none { it.packageName == BANK }) assertTrue("addSource", fora { db.addSource(BANK, BANK_LABEL, at(1, 8, 0)) })
    }

    private fun budget(name: String, limit: Long = 50_000): BudgetId =
        fora { db.createBudget(name, Cents(limit), DIA) }.also { assertNotEquals("createBudget $name", 0L, it.v) }

    private fun budgetNames(): List<String> = fora {
        val m = db.monthOf(DIA)
        db.ensureMonths(m)
        db.budgetsOverview(m, DIA, BudgetOrder.AZ).map { it.name }
    }

    private fun form(budget: BudgetId, cents: Long, tags: List<String> = listOf("teste"), merchant: String = "Sacolao") =
        TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = 1, merchant = merchant, date = DIA, time = LocalTime(10, 0))

    private fun manual(budget: BudgetId, cents: Long, tags: List<String>): TransactionId =
        fora { db.addManual(form(budget, cents, tags)).transaction }.also { assertNotEquals("addManual $tags", 0L, it.v) }

    private fun version(): Long = db.version.value

    /** Epoch ms de [day]/10/2026 [h]:[m] no fuso do sistema (o mesmo em que o app lê recados). */
    private fun at(day: Int, h: Int, m: Int): Long =
        LocalDateTime(2026, 10, day, h, m).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private fun compraText(cents: Long, merchant: String, day: Int, h: Int, m: Int): String {
        val reais = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
        return "Compra de R$ $reais aprovada em $merchant em ${day.toString().padStart(2, '0')}/10 às ${h}h${m.toString().padStart(2, '0')}."
    }

    /** Compra no formato genérico do ExtractTest, capturada de BANK; confere valor e estabelecimento lidos. */
    private fun compra(key: String, cents: Long, merchant: String, day: Int, h: Int, m: Int): NoticeId {
        val text = compraText(cents, merchant, day, h, m)
        val raw = RawNotice(
            packageName = BANK, appLabel = BANK_LABEL, key = key, postedAt = at(day, h, m), whenMs = at(day, h, m),
            title = "Compra aprovada", text = text, bigText = text,
        )
        assertTrue("capture de $key", fora { capture(db, raw) })
        val row = fora { db.pending() }.firstOrNull { it.raw.key == key }
        assertNotNull("recado $key pendente", row)
        assertEquals("pré-condição: leitura de '$text'", Cents(cents) to merchant, row!!.parsed.cents to row.parsed.merchant)
        return row.id
    }

    /** Uma linha de captured_notifications do cobudget v7 (KDoc de importCobudget). */
    private data class CobRow(
        val pkg: String = BANK, val label: String = BANK_LABEL, val key: String,
        val postedAt: Long, val whenMs: Long,
        val title: String?, val text: String?, val bigText: String?,
        val dismissed: Int = 0,
    )

    private fun row(
        key: String, day: Int, h: Int, m: Int, cents: Long, dismissed: Int = 0,
        pkg: String = BANK, label: String = BANK_LABEL, postedShift: Long = 0,
    ): CobRow {
        val text = compraText(cents, "LOJA ${key.uppercase()}", day, h, m)
        return CobRow(pkg, label, key, at(day, h, m) + postedShift, at(day, h, m), "Compra aprovada", text, text, dismissed)
    }

    /**
     * Backup do cobudget: arquivo novo, `CREATE TABLE captured_notifications` do KDoc de importCobudget,
     * as linhas na ordem dada (ids 1..n) e `PRAGMA user_version` = [userVersion].
     */
    private fun cobudgetFile(name: String, rows: List<CobRow>, userVersion: Int = 7): File {
        val f = File(tmp.root, name).also { it.delete() }
        val c = AndroidSQLiteDriver().open(f.path)
        try {
            c.execSQL(
                "CREATE TABLE `captured_notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, " +
                    "`postedAt` INTEGER NOT NULL, `notificationWhen` INTEGER NOT NULL DEFAULT 0, `title` TEXT, `text` TEXT, " +
                    "`bigText` TEXT, `subText` TEXT, `textLines` TEXT, `category` TEXT, `channelId` TEXT, " +
                    "`extrasJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `dismissed` INTEGER NOT NULL DEFAULT 0)",
            )
            for (r in rows) {
                c.prepare(
                    "INSERT INTO captured_notifications(packageName, appLabel, notificationKey, postedAt, notificationWhen, " +
                        "title, text, bigText, subText, textLines, category, channelId, extrasJson, contentHash, dismissed) " +
                        "VALUES (?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL,'{}','old-hash',?)",
                ).use { st ->
                    st.bindText(1, r.pkg)
                    st.bindText(2, r.label)
                    st.bindText(3, r.key)
                    st.bindLong(4, r.postedAt)
                    st.bindLong(5, r.whenMs)
                    if (r.title == null) st.bindNull(6) else st.bindText(6, r.title)
                    if (r.text == null) st.bindNull(7) else st.bindText(7, r.text)
                    if (r.bigText == null) st.bindNull(8) else st.bindText(8, r.bigText)
                    st.bindLong(9, r.dismissed.toLong())
                    st.step()
                }
            }
            c.execSQL("PRAGMA user_version = $userVersion")
        } finally {
            c.close()
        }
        return f
    }

    /** Uma escrita ou leitura "por fora": outra coroutine, fora da thread da UI, com timeout. */
    private fun <T> fora(block: suspend () -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(OP_TIMEOUT_MS) { block() } }

    // ---- Helpers: seletor de arquivo (SAF) ----

    /**
     * Responde ao seletor como a pessoa: [answer] (Uri de arquivo temporário, ou null = cancelado), na
     * thread principal, depois do launch. [launches]: nome do contrato e a entrada de cada abertura.
     */
    private class Picker : ActivityResultRegistry() {
        val launches: MutableList<Pair<String, Any?>> = Collections.synchronizedList(mutableListOf())

        @Volatile var answer: Uri? = null

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += contract.javaClass.simpleName to input
            val result = answer
            Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, result) }
        }
    }

    // ---- Helpers: navegação ----

    private fun openSettings() {
        tab = Screen.Settings
        await("Ajustes aberta") { exists(SettingsTags.LIST) }
    }

    private fun openSub(row: String, opened: () -> Boolean) {
        if (!exists(SettingsTags.LIST)) openSettings()
        await("linha $row") { exists(row) }
        tap(row)
        await("$row aberta") { opened() }
    }

    private fun openDismissed() = openSub(SettingsTags.DISMISSED) { exists(SettingsTags.DISMISSED_LIST) || exists(SettingsTags.DISMISSED_EMPTY) }

    private fun openTags() = openSub(SettingsTags.TAGS) { exists(SettingsTags.TAGS_LIST) || exists(SettingsTags.TAGS_EMPTY) }

    private fun openStartDays() = openSub(SettingsTags.START_DAYS) { exists(SettingsTags.START_DAY) && exists(SettingsTags.START_SAVE) }

    private fun openBackup() = openSub(SettingsTags.BACKUP) { exists(SettingsTags.BACKUP_EXPORT) }

    private fun openCobudget() = openSub(SettingsTags.COBUDGET) { exists(SettingsTags.COBUDGET_PICK) }

    private fun openTagDialog(t: TagId) {
        await("linha do marcador ${t.v}") { exists(SettingsTags.TAG_ROW + t.v) }
        tap(SettingsTags.TAG_ROW + t.v)
        await("diálogo do marcador ${t.v}") { exists(SettingsTags.TAG_DIALOG) && exists(SettingsTags.TAG_NAME) && exists(SettingsTags.TAG_SAVE) }
    }

    private fun openDeleteDialog(t: TagId) {
        openTagDialog(t)
        tap(SettingsTags.TAG_DELETE)
        await("Apagar fecha o diálogo do marcador e abre o de apagar") { !exists(SettingsTags.TAG_DIALOG) && exists(SettingsTags.TAG_DELETE_DIALOG) }
    }

    private fun pressBack() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    // ---- Helpers: semântica ----

    private fun node(tag: String): SemanticsNode? = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()

    private fun exists(tag: String) = node(tag) != null

    private fun selected(tag: String) = node(tag)?.config?.getOrNull(SemanticsProperties.Selected) == true

    private fun enabled(tag: String) = node(tag)?.let { !it.config.contains(SemanticsProperties.Disabled) } == true

    /** Exactly the option [value] of the picker [prefix] is `selected`. */
    private fun onlySelected(prefix: String, value: String): Boolean =
        suffixes(prefix).let { all -> value in all && all.all { selected(prefix + it) == (it == value) } }

    private fun top(tag: String): Float = node(tag)?.positionInRoot?.y ?: throw AssertionError("sem nó $tag")

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

    private fun textIs(tag: String, s: String): Boolean = texts(tag).joinToString("").trim() == s

    private fun suffixes(prefix: String): Set<String> = composeRule
        .onAllNodes(SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true })
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix(prefix) }
        .toSet()

    private fun ids(prefix: String): Set<Long> = suffixes(prefix).mapNotNull { it.toLongOrNull() }.toSet()

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
        const val ROUNDS = 12
        const val OTHERS = 40
        const val OP_TIMEOUT_MS = 10_000L
        const val STORM_TIMEOUT_MS = 60_000L
        const val UI_TIMEOUT_MS = 10_000L
    }
}
