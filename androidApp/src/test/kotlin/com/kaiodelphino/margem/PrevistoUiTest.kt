package com.kaiodelphino.margem

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.percentOffset
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InboxTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.DateTimeTags
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
import com.kaiodelphino.margem.widgets.dateLong
import com.kaiodelphino.margem.widgets.money
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The previsto drawn on the screens (spec §2.13, §7.2, §7.3, §7.4; book 8.4, 8.9, 8.11, 10.2, 10.5; D59–D64):
 * "A caminho" in Lançamentos, the previsto sheet and its actions, "Repete", "Data à frente" in Novo lançamento,
 * "É um previsto? Ligar" and the linked form, and the recognized card in the Pauta. The contract is the tags
 * and their KDoc ([TransactionTags], [InboxTags], [DateTimeTags] — D35); these tests are written blind against
 * it, before the implementation, and are triaged when it lands.
 *
 * Setup per test: a real Db (AndroidSQLiteDriver, D28) on a temporary "margem-previsto-*.db" (dropped with its
 * -wal/-shm), the device and the Db on TimeZone "America/Sao_Paulo", today fixed on Friday, Oct 9 2026, a tall
 * screen so "A caminho" and its rows are composed together. Previstos are made through the public Db API only:
 * a recado of September accepted with "Todo mês" (a linked previsto whose first occurrence is in October),
 * addManual with a date after `today` (an unlinked one), and addManual with an earlier `today` for an occurrence
 * that is already late on Oct 9. Elements are found only by tags; a tagged node's text is read in the unmerged
 * tree (the node and below).
 *
 * Robolectric does not see paint: the dashed stamp, ink 3 of the late day, anil of "recado na Pauta" and of
 * the bands are checked on the emulator against the book. What is asserted here is text, presence and order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp")
class PrevistoUiTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db
    private lateinit var path: String
    private lateinit var hostZone: java.util.TimeZone
    private var lazer = BudgetId(0)
    private var casa = BudgetId(0)
    private var transporte = BudgetId(0)

    @Before
    fun setUp() {
        hostZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"))
        path = tmp.newFile("margem-previsto-${Random.nextLong().toULong()}.db").also { it.delete() }.path
        db = Db(AndroidSQLiteDriver(), path, timeZone = ZONE)
        runBlocking {
            db.addSource(BANK, "Cartão Ipê", 0)
            // The budgets exist since September: the recados of September that made the monthly previstos fit them.
            lazer = db.createBudget("Lazer", Cents(30000), SEP_1)
            casa = db.createBudget("Casa", Cents(150000), SEP_1)
            transporte = db.createBudget("Transporte", Cents(40000), SEP_1)
        }
    }

    @After
    fun tearDown() {
        db.close()
        for (suffix in listOf("", "-wal", "-shm")) java.io.File(path + suffix).delete()
        java.util.TimeZone.setDefault(hostZone)
    }

    private fun open() = compose.setContent {
        MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { OCT_9 }) { App(db, null) {} } }
    }

    // ---- Finding things ----

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun hasTagPrefix(prefix: String) =
        SemanticsMatcher("testTag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    private fun countPrefix(prefix: String) = compose.onAllNodes(hasTagPrefix(prefix), useUnmergedTree = true).fetchSemanticsNodes().size

    /** Waits for [tag], scrolling the lazy lists to it: items below the fold aren't composed. */
    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        for (list in listOf(InboxTags.LIST, TransactionTags.LIST)) {
            if (count(tag) == 0 && count(list) > 0) runCatching { compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)) }
        }
        count(tag) > 0
    }

    private fun waitGone(tag: String) = compose.waitUntil(5_000) { count(tag) == 0 }

    private fun click(tag: String) {
        waitFor(tag)
        // Bottom bars, sheets and dialogs don't scroll; everything else may be below the fold.
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun node(tag: String): SemanticsNode? =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()

    private fun subtree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { subtree(it) }

    /** Every Text and EditableText in the node with [tag] and below it, in tree order. */
    private fun texts(tag: String): List<String> = node(tag)?.let { n ->
        subtree(n).flatMap { s ->
            s.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(s.config.getOrNull(SemanticsProperties.EditableText)?.text)
        }
    }.orEmpty()

    /** Like assertTextContains(substring = true), also accepting a text split across pieces. */
    private fun shows(tag: String, s: String): Boolean {
        val t = texts(tag)
        return t.any { it.contains(s) } || t.joinToString("").contains(s) || t.joinToString(" ").contains(s)
    }

    private fun assertShows(tag: String, s: String) = assertTrue("$tag deveria mostrar \"$s\": ${texts(tag)}", shows(tag, s))

    private fun assertNotShows(tag: String, s: String) = assertTrue("$tag não deveria mostrar \"$s\": ${texts(tag)}", !shows(tag, s))

    /** What a text field holds. */
    private fun editable(tag: String): String =
        node(tag)?.let { n -> subtree(n).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text } } ?: ""

    private fun top(tag: String): Float = node(tag)!!.boundsInRoot.top

    private fun suggested(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription) == "sugerido"

    private fun selected(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Selected) == true

    // ---- Navigation ----

    private fun openPauta() = click(BudgetTags.PAUTA_STRIP)

    private fun openTransactions() = openTab("Lançamentos", TransactionTags.NEW)

    /** The tab bar has no tags (block 2): a tab is found by its label. */
    private fun openTab(label: String, ready: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        waitFor(ready)
    }

    /** The system back (forms hide the tab bar and their close button has no tag); then waits for [ready]. */
    private fun pressBack(ready: String) {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(ready)
    }

    private fun openOnTheWay() {
        click(TransactionTags.ON_THE_WAY)
        waitFor("${TransactionTags.ON_THE_WAY}.open")
    }

    private fun openSheet(occurrence: OccurrenceId) {
        if (count("${TransactionTags.ON_THE_WAY}.open") == 0) openOnTheWay()
        click(TransactionTags.PLANNED_ROW + occurrence.v)
        waitFor(TransactionTags.PLANNED_SHEET)
    }

    /** Opens the date and time dialog of the form, chooses [day] and keeps the time. */
    private fun pickDate(day: LocalDate) {
        click(TransactionTags.FORM_DATE)
        waitFor(DateTimeTags.DIALOG)
        click(DateTimeTags.DAY + day)
        compose.waitUntil(5_000) { shows(DateTimeTags.TITLE, dateLong(day)) }
        click(DateTimeTags.DONE)
        waitGone(DateTimeTags.DIALOG)
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_DATE, dateLong(day)) }
    }

    private fun addTag(name: String) {
        click(TransactionTags.TAG_NEW)
        compose.onNodeWithTag(TransactionTags.TAG_INPUT).performTextReplacement(name)
        click(TransactionTags.TAG_ADD)
        waitFor(TransactionTags.TAG + name)
    }

    // ---- Data, through the public Db API ----

    private fun at(hour: Int, minute: Int, day: LocalDate = OCT_9) =
        LocalDateTime(day.year, day.month, day.day, hour, minute).toInstant(ZONE).toEpochMilliseconds()

    /** Captures a recado from the bank and returns its id. */
    private fun post(text: String, postedAt: Long): NoticeId = runBlocking {
        assertTrue(capture(db, RawNotice(packageName = BANK, appLabel = "Cartão Ipê", key = "k$postedAt", postedAt = postedAt, title = "Cartão Ipê", text = text)))
        db.pending().first { it.raw.postedAt == postedAt }.id
    }

    /** A recado of [day] accepted with "Todo mês" and the legenda [name]: a previsto linked to it, first occurrence the month after. */
    private fun monthlyFrom(text: String, day: LocalDate, budget: BudgetId, tags: List<String>, name: String): TransactionId {
        val id = post(text, at(9, 0, day))
        return runBlocking {
            val p = db.notice(id).parsed
            db.accept(id, TransactionForm(budget = budget, tags = tags, total = p.cents, installments = 1, merchant = p.merchant, date = p.date, time = p.time, caption = name, repeats = Repeats.MONTHLY))
        }.also { assertTrue("lançar com Todo mês", it.v != 0L) }
    }

    /** Salvar previsto: a manual form dated after [today]; nothing but the previsto. */
    private fun ahead(name: String, budget: BudgetId, tags: List<String>, cents: Long, date: LocalDate, repeats: Repeats, today: LocalDate = OCT_9): PlannedId {
        val saved = runBlocking {
            db.addManual(TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = 1, merchant = name, date = date, time = LocalTime(9, 0), repeats = repeats), today)
        }
        assertTrue("previsto salvo", saved.planned.v != 0L)
        assertEquals("só o previsto", 0L, saved.transaction.v)
        return saved.planned
    }

    private fun plannedNow(): List<Planned> = runBlocking { db.planned(OCT_9) }

    private fun occ(name: String): OccurrenceId = plannedNow().first { it.name == name }.occurrence

    /**
     * Book 8.11's four: Academia (Lazer, late since Oct 5, linked to SMARTFIT), Internet (Casa, Oct 10, linked to
     * VIVO FIBRA, with its recado in the Pauta), Conta de luz (Casa, Oct 12, todo mês, unlinked) and
     * Estacionamento (Transporte, Oct 15, só uma vez, unlinked). Returns the pending VIVO FIBRA recado.
     */
    private fun four(): NoticeId {
        monthlyFrom("Compra aprovada: R$ 89,90 em SMARTFIT", SEP_5, lazer, listOf("academia"), "Academia")
        monthlyFrom("Compra aprovada: R$ 119,90 em VIVO FIBRA", SEP_10, casa, listOf("contas", "internet"), "Internet")
        val vivo = post("Compra aprovada: R$ 119,90 em VIVO FIBRA", at(7, 30))
        ahead("Conta de luz", casa, listOf("contas", "luz"), 18000, OCT_12, Repeats.MONTHLY)
        ahead("Estacionamento", transporte, listOf("estacionamento"), 15000, OCT_15, Repeats.ONCE)
        return vivo
    }

    /** Internet (Casa · contas, internet · dia 10, R$ 119,90), linked to VIVO FIBRA, and its October recado ([text], R$ 124,90 by default). */
    private fun internetWithRecado(text: String = "Compra aprovada: R$ 124,90 em VIVO FIBRA"): NoticeId {
        monthlyFrom("Compra aprovada: R$ 119,90 em VIVO FIBRA", SEP_10, casa, listOf("contas", "internet"), "Internet")
        return post(text, at(7, 30))
    }

    // ---- A caminho: the collapsed line (spec §7.4, book 8.11) ----

    @Test
    fun `there is no a caminho line without a previsto`() {
        runBlocking {
            db.addManual(TransactionForm(budget = lazer, tags = listOf("feira"), total = Cents(4600), installments = 1, merchant = "Feira", date = OCT_8, time = LocalTime(10, 0)))
        }
        val tx = runBlocking { db.transactions() }.single().id
        open()
        openTransactions()
        waitFor(TransactionTags.ROW + tx.v)
        assertEquals("sem previsto, a linha não existe", 0, count(TransactionTags.ON_THE_WAY))
    }

    @Test
    fun `the collapsed line counts sums and says what asks attention`() {
        four()
        open()
        openTransactions()

        waitFor(TransactionTags.ON_THE_WAY)
        assertShows(TransactionTags.ON_THE_WAY, "A caminho")
        // Σ Planned.cents: 89,90 + 119,90 + 180,00 + 150,00 = 539,80 (book 8.11 prints 539,70 for these same four).
        assertShows(TransactionTags.ON_THE_WAY, "4 previstos · ${money(Cents(53980))}")
        assertShows(TransactionTags.ON_THE_WAY, "1 com recado na Pauta · 1 atrasado")
        assertEquals("sempre abre recolhido", 0, count("${TransactionTags.ON_THE_WAY}.open"))
        assertEquals("recolhido, sem linhas de previsto", 0, countPrefix(TransactionTags.PLANNED_ROW))
    }

    @Test
    fun `one previsto on its way reads in the singular with no attention parts`() {
        ahead("Conta de luz", casa, listOf("contas", "luz"), 18000, OCT_12, Repeats.MONTHLY)
        open()
        openTransactions()

        waitFor(TransactionTags.ON_THE_WAY)
        assertShows(TransactionTags.ON_THE_WAY, "1 previsto · ${money(Cents(18000))}")
        assertNotShows(TransactionTags.ON_THE_WAY, "previstos")
        assertNotShows(TransactionTags.ON_THE_WAY, "com recado na Pauta")
        assertNotShows(TransactionTags.ON_THE_WAY, "atrasado")
    }

    @Test
    fun `two late previstos read atrasados with no recado part`() {
        // Saved on Oct 1 for Oct 5 and Oct 7: on Oct 9 both are past their day with no recado.
        ahead("Academia", lazer, listOf("academia"), 8990, OCT_5, Repeats.MONTHLY, today = OCT_1)
        ahead("Aula de violão", lazer, listOf("aula"), 12000, OCT_7, Repeats.ONCE, today = OCT_1)
        open()
        openTransactions()

        waitFor(TransactionTags.ON_THE_WAY)
        assertShows(TransactionTags.ON_THE_WAY, "2 previstos · ${money(Cents(20990))}")
        assertShows(TransactionTags.ON_THE_WAY, "2 atrasados")
        assertNotShows(TransactionTags.ON_THE_WAY, "com recado na Pauta")
    }

    @Test
    fun `a caminho always opens collapsed toggles and stays open while the screen is open`() {
        four()
        open()
        openTransactions()
        waitFor(TransactionTags.ON_THE_WAY)
        assertEquals(0, count("${TransactionTags.ON_THE_WAY}.open"))

        openOnTheWay()
        compose.waitUntil(5_000) { countPrefix(TransactionTags.PLANNED_ROW) == 4 }

        // A write elsewhere (a new recado) reloads the data: the group stays open.
        post("Compra aprovada: R$ 12,00 em BANCA CENTRAL", at(11, 0))
        compose.waitForIdle()
        assertEquals("aberto fica aberto com a tela aberta", 1, count("${TransactionTags.ON_THE_WAY}.open"))
        assertEquals(4, countPrefix(TransactionTags.PLANNED_ROW))

        click(TransactionTags.ON_THE_WAY)
        waitGone("${TransactionTags.ON_THE_WAY}.open")
        compose.waitUntil(5_000) { countPrefix(TransactionTags.PLANNED_ROW) == 0 }

        // Open again, leave the tab and come back: it doesn't remember.
        openOnTheWay()
        openTab("Verbas", BudgetTags.LIST)
        openTransactions()
        waitFor(TransactionTags.ON_THE_WAY)
        assertEquals("reaberta a tela, recolhido", 0, count("${TransactionTags.ON_THE_WAY}.open"))
        assertEquals(0, countPrefix(TransactionTags.PLANNED_ROW))
    }

    // ---- A caminho: the rows ----

    @Test
    fun `open rows come late first then by date with what each is waiting for`() {
        four()
        val academia = occ("Academia")
        val internet = occ("Internet")
        val luz = occ("Conta de luz")
        val estacionamento = occ("Estacionamento")
        open()
        openTransactions()
        openOnTheWay()

        val rows = listOf(academia, internet, luz, estacionamento).map { TransactionTags.PLANNED_ROW + it.v }
        rows.forEach(::waitFor)
        val tops = rows.map(::top)
        assertEquals("atrasado primeiro, depois pelo dia: $tops", tops.sorted(), tops)
        assertTrue("as linhas vêm depois da linha A caminho", top(TransactionTags.ON_THE_WAY) < tops.first())

        val a = rows[0]
        assertShows(a, "Academia")
        assertShows(a, "esperando desde 5 out")
        assertShows(a, money(Cents(8990)))
        assertShows(a, "out")
        assertShows(a, "5")

        val i = rows[1]
        assertShows(i, "Internet")
        assertShows(i, "recado na Pauta")
        assertNotShows(i, "esperando desde")
        assertShows(i, money(Cents(11990)))

        val l = rows[2]
        assertShows(l, "Conta de luz")
        assertShows(l, "Casa · contas, luz · todo mês")
        assertNotShows(l, "recado na Pauta")
        assertShows(l, "12")
        assertShows(l, money(Cents(18000)))

        val e = rows[3]
        assertShows(e, "Estacionamento")
        assertShows(e, "Transporte · estacionamento · só uma vez")
        assertShows(e, money(Cents(15000)))
    }

    // ---- The previsto sheet (spec §2.13, book 8.11) ----

    @Test
    fun `the sheet of a late linked previsto shows its lines and every action`() {
        four()
        open()
        openTransactions()
        openSheet(occ("Academia"))

        val s = TransactionTags.PLANNED_SHEET
        assertShows(s, "Academia")
        assertShows(s, "Lazer · academia · todo mês, dia 5")
        assertShows(s, money(Cents(8990)))
        assertShows(s, "Esperando o recado desde 5 de outubro.")
        assertShows(s, "Ligado a SMARTFIT, do Cartão Ipê.")

        waitFor(TransactionTags.PLANNED_LAUNCH_NO_NOTICE)
        assertShows(TransactionTags.PLANNED_LAUNCH_NO_NOTICE, "Lançar sem recado")
        assertShows(TransactionTags.PLANNED_LAUNCH_NO_NOTICE, "vira um lançamento de 5 out, sem ligação")
        assertShows(TransactionTags.PLANNED_SKIP, "Pular este mês")
        assertShows(TransactionTags.PLANNED_SKIP, "some sem lançar; volta em 5 de novembro")
        assertShows(TransactionTags.PLANNED_EDIT, "Editar previsto")
        assertShows(TransactionTags.PLANNED_DELETE, "Apagar previsto")
        assertShows(TransactionTags.PLANNED_DELETE, "este e os próximos meses")
    }

    @Test
    fun `before its day the sheet has no lancar sem recado and no waiting line`() {
        four()
        open()
        openTransactions()
        openSheet(occ("Conta de luz"))

        assertShows(TransactionTags.PLANNED_SHEET, "Casa · contas, luz · todo mês, dia 12")
        assertNotShows(TransactionTags.PLANNED_SHEET, "Esperando o recado")
        assertNotShows(TransactionTags.PLANNED_SHEET, "Ligado a")
        waitFor(TransactionTags.PLANNED_SKIP)
        assertEquals("antes do dia, sem Lançar sem recado (R6)", 0, count(TransactionTags.PLANNED_LAUNCH_NO_NOTICE))
        assertShows(TransactionTags.PLANNED_SKIP, "volta em 12 de novembro")
        assertEquals(1, count(TransactionTags.PLANNED_EDIT))
        assertEquals(1, count(TransactionTags.PLANNED_DELETE))
    }

    @Test
    fun `on the day of the occurrence the sheet offers lancar sem recado`() {
        // Saved on Oct 1 for today: the day has come, and it isn't late.
        ahead("Mensalidade", lazer, listOf("escola"), 50000, OCT_9, Repeats.MONTHLY, today = OCT_1)
        open()
        openTransactions()
        waitFor(TransactionTags.ON_THE_WAY)
        assertNotShows(TransactionTags.ON_THE_WAY, "atrasado")
        openSheet(occ("Mensalidade"))

        waitFor(TransactionTags.PLANNED_LAUNCH_NO_NOTICE)
        assertShows(TransactionTags.PLANNED_LAUNCH_NO_NOTICE, "vira um lançamento de 9 out, sem ligação")
        assertNotShows(TransactionTags.PLANNED_SHEET, "Esperando o recado")
    }

    @Test
    fun `pular takes the occurrence away and the sheet closes`() {
        four()
        val luz = occ("Conta de luz")
        open()
        openTransactions()
        openSheet(luz)

        click(TransactionTags.PLANNED_SKIP)
        waitGone(TransactionTags.PLANNED_SHEET)
        waitGone(TransactionTags.PLANNED_ROW + luz.v)
        compose.waitUntil(5_000) { plannedNow().none { it.occurrence == luz } }
        compose.waitUntil(5_000) { shows(TransactionTags.ON_THE_WAY, "3 previstos") }
        assertEquals("pular não lança", 2, runBlocking { db.transactions() }.size)
    }

    @Test
    fun `pular the only previsto of so uma vez says it goes and the line goes with it`() {
        ahead("Estacionamento", transporte, listOf("estacionamento"), 15000, OCT_15, Repeats.ONCE)
        val e = occ("Estacionamento")
        open()
        openTransactions()
        openSheet(e)

        waitFor(TransactionTags.PLANNED_SKIP)
        assertShows(TransactionTags.PLANNED_SKIP, "some sem lançar")
        assertNotShows(TransactionTags.PLANNED_SKIP, "volta em")
        click(TransactionTags.PLANNED_SKIP)
        waitGone(TransactionTags.PLANNED_SHEET)
        waitGone(TransactionTags.ON_THE_WAY)
    }

    @Test
    fun `lancar sem recado opens novo lancamento from the previsto and saving closes the occurrence`() {
        four()
        val academia = occ("Academia")
        open()
        openTransactions()
        openSheet(academia)
        click(TransactionTags.PLANNED_LAUNCH_NO_NOTICE)

        waitFor(TransactionTags.FORM_TITLE)
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_TITLE, "Novo lançamento") }
        waitFor(TransactionTags.FORM_TOTAL)
        // The total input holds digits (cents); the submit says it in money.
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_SUBMIT, "Lançar ${money(Cents(8990))}") }
        assertEquals("sem recado no formulário", 0, count(TransactionTags.FORM_NOTICE))
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_DATE, dateLong(OCT_5)) }
        compose.onNodeWithTag(TransactionTags.BUDGET + lazer.v).assertIsSelected()
        compose.onNodeWithTag(TransactionTags.TAG + "academia").assertIsSelected()
        assertEquals("legenda = nome do previsto", "Academia", editable(TransactionTags.FORM_CAPTION))

        click(TransactionTags.FORM_SUBMIT)
        waitFor(TransactionTags.NEW)
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.any { it.caption == "Academia" && it.date == OCT_5 } }
        val t = runBlocking { db.transactions() }.first { it.caption == "Academia" && it.date == OCT_5 }
        assertEquals(Cents(8990), t.total)
        assertEquals(lazer, t.budget)
        assertTrue("a ocorrência fechou", plannedNow().none { it.occurrence == academia })
    }

    @Test
    fun `editar previsto opens the manual form with the previsto and saving changes it`() {
        four()
        open()
        openTransactions()
        openSheet(occ("Conta de luz"))
        click(TransactionTags.PLANNED_EDIT)

        waitFor(TransactionTags.FORM_TITLE)
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_TITLE, "Editar previsto") }
        waitFor(TransactionTags.FORM_REPEATS)
        compose.onNodeWithTag(TransactionTags.FORM_REPEATS_MONTHLY).assertIsSelected()
        compose.onNodeWithTag(TransactionTags.FORM_REPEATS_ONCE).assertIsNotSelected()
        assertShows(TransactionTags.FORM_REPEATS_MONTHLY, "Todo mês, dia 12")
        compose.onNodeWithTag(TransactionTags.BUDGET + casa.v).assertIsSelected()
        compose.onNodeWithTag(TransactionTags.TAG + "luz").assertIsSelected()
        assertTrue(
            "o nome do previsto está no formulário: ${texts(TransactionTags.FORM_NAME)} / ${texts(TransactionTags.FORM_CAPTION)}",
            editable(TransactionTags.FORM_NAME) == "Conta de luz" || editable(TransactionTags.FORM_CAPTION) == "Conta de luz",
        )

        compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performTextReplacement("19000")
        click(TransactionTags.FORM_SUBMIT)
        compose.waitUntil(5_000) { plannedNow().first { it.name == "Conta de luz" }.cents == Cents(19000) }
        assertEquals("editar previsto não lança", 2, runBlocking { db.transactions() }.size)
    }

    @Test
    fun `apagar previsto asks in a dialog and confirming deletes it`() {
        four()
        val academia = occ("Academia")
        open()
        openTransactions()
        openSheet(academia)

        click(TransactionTags.PLANNED_DELETE)
        waitFor(TransactionTags.PLANNED_DELETE_DIALOG)
        assertShows(TransactionTags.PLANNED_DELETE_DIALOG, "Apagar o previsto Academia?")
        assertShows(TransactionTags.PLANNED_DELETE_DIALOG, "Este e os próximos meses somem. O que já foi lançado continua.")
        click(TransactionTags.PLANNED_DELETE_CANCEL)
        waitGone(TransactionTags.PLANNED_DELETE_DIALOG)
        assertEquals("cancelar só fecha o diálogo", 1, count(TransactionTags.PLANNED_SHEET))
        assertTrue(plannedNow().any { it.occurrence == academia })

        click(TransactionTags.PLANNED_DELETE)
        waitFor(TransactionTags.PLANNED_DELETE_DIALOG)
        click(TransactionTags.PLANNED_DELETE_CONFIRM)
        waitGone(TransactionTags.PLANNED_DELETE_DIALOG)
        waitGone(TransactionTags.PLANNED_SHEET)
        waitGone(TransactionTags.PLANNED_ROW + academia.v)
        compose.waitUntil(5_000) { plannedNow().none { it.name == "Academia" } }
        assertTrue("o lançamento de setembro continua", runBlocking { db.transactions() }.any { it.caption == "Academia" && it.date == SEP_5 })
    }

    // ---- Repete (spec §2.13, §7.3, book 8.11) ----

    @Test
    fun `repete shows in novo lancamento with so uma vez and the day of the date`() {
        open()
        openTransactions()
        click(TransactionTags.NEW)

        waitFor(TransactionTags.FORM_REPEATS)
        compose.onNodeWithTag(TransactionTags.FORM_REPEATS_ONCE).assertIsSelected()
        compose.onNodeWithTag(TransactionTags.FORM_REPEATS_MONTHLY).assertIsNotSelected()
        assertShows(TransactionTags.FORM_REPEATS_ONCE, "Só uma vez")
        assertShows(TransactionTags.FORM_REPEATS_MONTHLY, "Todo mês, dia 9")

        pickDate(OCT_5)
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_REPEATS_MONTHLY, "Todo mês, dia 5") }
        click(TransactionTags.FORM_REPEATS_MONTHLY)
        compose.onNodeWithTag(TransactionTags.FORM_REPEATS_MONTHLY).assertIsSelected()
        compose.onNodeWithTag(TransactionTags.FORM_REPEATS_ONCE).assertIsNotSelected()
        assertEquals("a dica de Todo mês é só do Lançar recado", 0, count(TransactionTags.FORM_REPEATS_HINT))
    }

    @Test
    fun `repete in lancar recado gives the hint of next month with todo mes`() {
        val id = post("Compra aprovada: R$ 21,90 em SPOTIFY", at(8, 0))
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)

        waitFor(TransactionTags.FORM_REPEATS)
        compose.onNodeWithTag(TransactionTags.FORM_REPEATS_ONCE).assertIsSelected()
        assertEquals("sem Todo mês, sem dica", 0, count(TransactionTags.FORM_REPEATS_HINT))
        click(TransactionTags.FORM_REPEATS_MONTHLY)
        waitFor(TransactionTags.FORM_REPEATS_HINT)
        assertShows(TransactionTags.FORM_REPEATS_HINT, "Em novembro, o recado do SPOTIFY chega ligado.")
        click(TransactionTags.FORM_REPEATS_ONCE)
        waitGone(TransactionTags.FORM_REPEATS_HINT)
    }

    @Test
    fun `todo mes in lancar recado saves a previsto linked to the recado for next month`() {
        val id = post("Compra aprovada: R$ 21,90 em SPOTIFY", at(8, 0))
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        waitFor(TransactionTags.FORM_REPEATS)
        click(TransactionTags.BUDGET + lazer.v)
        addTag("música")
        click(TransactionTags.FORM_REPEATS_MONTHLY)
        click(TransactionTags.FORM_SUBMIT)

        waitFor(SnackTags.TEXT)
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.size == 1 }

        // Nov 9 is more than 10 days away on Oct 9: not on its way yet.
        openTransactions()
        waitFor(TransactionTags.ROW + runBlocking { db.transactions() }.single().id.v)
        assertEquals(0, count(TransactionTags.ON_THE_WAY))

        // Read as of Nov 1 only now: that read creates the November occurrence.
        val next = runBlocking { db.planned(NOV_1) }
        assertTrue("previsto de novembro, dia 9, ligado ao SPOTIFY: $next", next.any { it.date == NOV_9 && it.linked && it.linkText == "SPOTIFY" && it.repeats == Repeats.MONTHLY })
    }

    @Test
    fun `repete shows in editar lancamento`() {
        val id = post("Compra aprovada: R$ 21,90 em SPOTIFY", at(8, 0))
        val tx = runBlocking {
            val p = db.notice(id).parsed
            db.accept(id, TransactionForm(budget = lazer, tags = listOf("música"), total = p.cents, installments = 1, merchant = p.merchant, date = p.date, time = p.time))
        }
        open()
        openTransactions()
        click(TransactionTags.ROW + tx.v)

        waitFor(TransactionTags.FORM_REPEATS)
        compose.onNodeWithTag(TransactionTags.FORM_REPEATS_ONCE).assertIsSelected()
        assertShows(TransactionTags.FORM_REPEATS_MONTHLY, "Todo mês, dia 9")
    }

    // ---- Data à frente (spec §7.3, book 8.9, 8.11) ----

    @Test
    fun `a future date in novo lancamento shows the band and salvar previsto saves only the previsto`() {
        open()
        openTransactions()
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_NAME)
        compose.onNodeWithTag(TransactionTags.FORM_NAME).performTextReplacement("Conta de luz")
        compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performTextReplacement("18000")
        click(TransactionTags.BUDGET + casa.v)
        addTag("luz")
        waitFor(TransactionTags.FORM_PREVIEW)
        assertEquals("hoje não é data à frente", 0, count(TransactionTags.FORM_FUTURE))
        assertShows(TransactionTags.FORM_SUBMIT, "Lançar ${money(Cents(18000))}")

        pickDate(OCT_12)
        waitFor(TransactionTags.FORM_FUTURE)
        assertShows(TransactionTags.FORM_FUTURE, "Data à frente: vira previsto. Fica em Lançamentos esperando o recado, sem contar na verba.")
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_SUBMIT, "Salvar previsto") }
        waitGone(TransactionTags.FORM_PREVIEW)
        assertEquals("Repete continua à vista", 1, count(TransactionTags.FORM_REPEATS))

        // Back to a day that has come: a lançamento again.
        pickDate(OCT_9)
        waitGone(TransactionTags.FORM_FUTURE)
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_SUBMIT, "Lançar ${money(Cents(18000))}") }

        pickDate(OCT_12)
        waitFor(TransactionTags.FORM_FUTURE)
        click(TransactionTags.FORM_SUBMIT)
        waitFor(TransactionTags.NEW)
        waitFor(TransactionTags.ON_THE_WAY)
        compose.waitUntil(5_000) { shows(TransactionTags.ON_THE_WAY, "1 previsto · ${money(Cents(18000))}") }
        assertTrue("nenhum lançamento", runBlocking { db.transactions() }.isEmpty())
        val p = plannedNow().single()
        assertEquals("Conta de luz", p.name)
        assertEquals(OCT_12, p.date)
        assertEquals(Cents(18000), p.cents)
        assertEquals(casa, p.budget)
        assertEquals(Repeats.ONCE, p.repeats)
        assertTrue("sem ligação", !p.linked)
    }

    @Test
    fun `the date dialog takes days ahead only in novo lancamento`() {
        val id = post("Compra aprovada: R$ 21,90 em SPOTIFY", at(8, 0))
        val tx = runBlocking {
            db.addManual(TransactionForm(budget = lazer, tags = listOf("feira"), total = Cents(4600), installments = 1, merchant = "Feira", date = OCT_8, time = LocalTime(10, 0))).transaction
        }
        open()

        openTransactions()
        click(TransactionTags.NEW)
        click(TransactionTags.FORM_DATE)
        waitFor(DateTimeTags.DIALOG)
        compose.onNodeWithTag(DateTimeTags.DAY + OCT_12).assertIsEnabled()
        compose.onNodeWithTag(DateTimeTags.NEXT).assertIsEnabled()
        click(DateTimeTags.CANCEL)
        waitGone(DateTimeTags.DIALOG)
        pressBack(TransactionTags.NEW)

        click(TransactionTags.ROW + tx.v)
        click(TransactionTags.FORM_DATE)
        waitFor(DateTimeTags.DIALOG)
        compose.onNodeWithTag(DateTimeTags.DAY + OCT_12).assertIsNotEnabled()
        compose.onNodeWithTag(DateTimeTags.NEXT).assertIsNotEnabled()
        click(DateTimeTags.CANCEL)
        waitGone(DateTimeTags.DIALOG)
        pressBack(TransactionTags.NEW)

        openTab("Verbas", BudgetTags.LIST)
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        click(TransactionTags.FORM_DATE)
        waitFor(DateTimeTags.DIALOG)
        compose.onNodeWithTag(DateTimeTags.DAY + OCT_12).assertIsNotEnabled()
        compose.onNodeWithTag(DateTimeTags.NEXT).assertIsNotEnabled()
    }

    @Test
    @Config(shadows = [ActiveKeyboard::class])
    fun `a composing keyboard types the value of a salvar previsto form`() {
        open()
        openTransactions()
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_NAME)
        compose.onNodeWithTag(TransactionTags.FORM_NAME).performTextReplacement("Conta de luz")
        click(TransactionTags.BUDGET + casa.v)
        addTag("luz")
        pickDate(OCT_12)
        waitFor(TransactionTags.FORM_FUTURE)

        compose.typeComposing(TransactionTags.FORM_TOTAL, "18000")
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_SUBMIT, "Salvar previsto") }
        click(TransactionTags.FORM_SUBMIT)
        compose.waitUntil(5_000) { plannedNow().isNotEmpty() }
        assertEquals("nenhum dígito perdido", Cents(18000), plannedNow().single().cents)
    }

    // ---- É um previsto? Ligar (spec §2.13, §7.3, book 8.11) ----

    @Test
    fun `ligar opens the sheet of open previstos and links the form`() {
        ahead("Conta de luz", casa, listOf("contas", "luz"), 18000, OCT_12, Repeats.MONTHLY)
        ahead("Estacionamento", transporte, listOf("estacionamento"), 15000, OCT_15, Repeats.MONTHLY)
        val luz = occ("Conta de luz")
        val estacionamento = occ("Estacionamento")
        val id = post("Pagamento de conta: R$ 187,40 em ENEL SP", at(18, 20))
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)

        waitFor(TransactionTags.FORM_LINK_OPEN)
        assertShows(TransactionTags.FORM_LINK_OPEN, "É um previsto? Ligar")
        click(TransactionTags.FORM_LINK_OPEN)
        waitFor(TransactionTags.LINK_SHEET)
        assertShows(TransactionTags.LINK_SHEET, "Qual previsto é este recado?")
        assertShows(TransactionTags.LINK_SHEET, "Só desta vez: nos próximos meses, o recado da ENEL SP chega ligado.")
        val l = TransactionTags.LINK_CANDIDATE + luz.v
        val e = TransactionTags.LINK_CANDIDATE + estacionamento.v
        waitFor(l)
        waitFor(e)
        assertEquals("um candidato por previsto em aberto", 2, countPrefix(TransactionTags.LINK_CANDIDATE))
        assertTrue("dia mais perto primeiro (12 antes de 15, recado de 9)", top(l) < top(e))
        assertShows(l, "Conta de luz")
        assertShows(l, "mais provável")
        assertNotShows(e, "mais provável")
        compose.onNodeWithTag(TransactionTags.LINK_SUBMIT).assertIsNotEnabled()

        click(l)
        compose.onNodeWithTag(TransactionTags.LINK_SUBMIT).assertIsEnabled()
        assertShows(TransactionTags.LINK_SUBMIT, "Ligar à Conta de luz")
        click(TransactionTags.LINK_SUBMIT)
        waitGone(TransactionTags.LINK_SHEET)

        waitFor(TransactionTags.FORM_LINKED)
        assertShows(TransactionTags.FORM_LINKED, "Ligado à Conta de luz")
        assertShows(TransactionTags.FORM_LINKED, "Todo mês, dia 12. Verba, marcadores e nome vieram do previsto.")
        assertShows(TransactionTags.FORM_UNLINK, "Desligar")
        assertEquals("a faixa toma o lugar do link", 0, count(TransactionTags.FORM_LINK_OPEN))
        assertEquals("ligado, sem Repete", 0, count(TransactionTags.FORM_REPEATS))
        compose.onNodeWithTag(TransactionTags.BUDGET + casa.v).assertIsSelected()
        compose.onNodeWithTag(TransactionTags.TAG + "luz").assertIsSelected()
        compose.onNodeWithTag(TransactionTags.TAG + "contas").assertIsSelected()
        assertTrue("marcadores cheios, não sugeridos", !suggested(TransactionTags.TAG + "luz") && !suggested(TransactionTags.TAG + "contas"))
        assertEquals("o nome entra como legenda", "Conta de luz", editable(TransactionTags.FORM_CAPTION))
        assertShows(TransactionTags.FORM_SUBMIT, "Lançar ${money(Cents(18740))}")
        assertEquals("o valor é o do recado, sem emenda (P7)", 0, count(TransactionTags.FORM_TOTAL_EMENDA))
        assertEquals(0, count(TransactionTags.FORM_DATE_EMENDA))
    }

    @Test
    fun `desligar puts the form back as it was`() {
        ahead("Conta de luz", casa, listOf("contas", "luz"), 18000, OCT_12, Repeats.MONTHLY)
        val luz = occ("Conta de luz")
        val id = post("Pagamento de conta: R$ 187,40 em ENEL SP", at(18, 20))
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        waitFor(TransactionTags.FORM_REPEATS)
        assertTrue("sem sugestão, nenhuma verba", !selected(TransactionTags.BUDGET + casa.v))

        click(TransactionTags.FORM_LINK_OPEN)
        click(TransactionTags.LINK_CANDIDATE + luz.v)
        click(TransactionTags.LINK_SUBMIT)
        waitFor(TransactionTags.FORM_LINKED)

        click(TransactionTags.FORM_UNLINK)
        waitGone(TransactionTags.FORM_LINKED)
        waitFor(TransactionTags.FORM_LINK_OPEN)
        waitFor(TransactionTags.FORM_REPEATS)
        assertTrue("a verba volta a nenhuma", !selected(TransactionTags.BUDGET + casa.v))
        assertTrue("os marcadores do previsto saem", !selected(TransactionTags.TAG + "luz") && !selected(TransactionTags.TAG + "contas"))
        assertEquals("a legenda volta recolhida", 0, count(TransactionTags.FORM_CAPTION))
        waitFor(TransactionTags.FORM_CAPTION_OPEN)
        assertShows(TransactionTags.FORM_SUBMIT, "Lançar ${money(Cents(18740))}")
    }

    @Test
    fun `lancar a linked form closes the occurrence with the previsto as legenda`() {
        ahead("Conta de luz", casa, listOf("contas", "luz"), 18000, OCT_12, Repeats.MONTHLY)
        val luz = occ("Conta de luz")
        val id = post("Pagamento de conta: R$ 187,40 em ENEL SP", at(18, 20))
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        click(TransactionTags.FORM_LINK_OPEN)
        click(TransactionTags.LINK_CANDIDATE + luz.v)
        click(TransactionTags.LINK_SUBMIT)
        waitFor(TransactionTags.FORM_LINKED)
        click(TransactionTags.FORM_SUBMIT)

        waitFor(SnackTags.TEXT)
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.size == 1 }
        val t = runBlocking { db.transactions() }.single()
        assertEquals("Conta de luz", t.caption)
        assertEquals(Cents(18740), t.total)
        assertEquals(casa, t.budget)
        assertTrue("sem emenda", !t.corrected)
        assertTrue("a ocorrência fechou", plannedNow().none { it.occurrence == luz })
    }

    @Test
    fun `ligar in novo lancamento asks about este lancamento with no recado hint`() {
        ahead("Conta de luz", casa, listOf("contas", "luz"), 18000, OCT_12, Repeats.MONTHLY)
        val luz = occ("Conta de luz")
        open()
        openTransactions()
        click(TransactionTags.NEW)

        waitFor(TransactionTags.FORM_LINK_OPEN)
        assertShows(TransactionTags.FORM_LINK_OPEN, "É um previsto? Ligar")
        click(TransactionTags.FORM_LINK_OPEN)
        waitFor(TransactionTags.LINK_SHEET)
        assertShows(TransactionTags.LINK_SHEET, "Qual previsto é este lançamento?")
        assertNotShows(TransactionTags.LINK_SHEET, "Só desta vez")
        waitFor(TransactionTags.LINK_CANDIDATE + luz.v)
        click(TransactionTags.LINK_CANDIDATE + luz.v)
        click(TransactionTags.LINK_SUBMIT)
        waitFor(TransactionTags.FORM_LINKED)
        assertShows(TransactionTags.FORM_LINKED, "Ligado à Conta de luz")
        assertEquals(0, count(TransactionTags.FORM_REPEATS))
    }

    @Test
    fun `ligar is absent with no open previsto and in editar`() {
        val id = post("Pagamento de conta: R$ 187,40 em ENEL SP", at(18, 20))
        val tx = runBlocking {
            db.addManual(TransactionForm(budget = lazer, tags = listOf("feira"), total = Cents(4600), installments = 1, merchant = "Feira", date = OCT_8, time = LocalTime(10, 0))).transaction
        }
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        waitFor(TransactionTags.FORM_REPEATS)
        compose.waitForIdle()
        assertEquals("sem previsto em aberto, sem link", 0, count(TransactionTags.FORM_LINK_OPEN))

        // A previsto now (the screen follows the Db, D19): the link shows in Lançar; Editar still has none.
        ahead("Conta de luz", casa, listOf("contas", "luz"), 18000, OCT_12, Repeats.MONTHLY)
        waitFor(TransactionTags.FORM_LINK_OPEN)
        pressBack(InboxTags.LIST)
        openTab("Lançamentos", TransactionTags.NEW)
        click(TransactionTags.ROW + tx.v)
        waitFor(TransactionTags.FORM_REPEATS)
        compose.waitForIdle()
        assertEquals("na edição, sem link", 0, count(TransactionTags.FORM_LINK_OPEN))
    }

    // ---- The Pauta: a recognized recado (spec §7.2, book 8.4, 10.2) ----

    @Test
    fun `a recognized card shows the band and lancar previsto launches with a snack and desfazer`() {
        val id = internetWithRecado()
        open()
        openPauta()

        val band = InboxTags.PLANNED_BAND + id.v
        waitFor(band)
        assertShows(band, "É o previsto Internet")
        assertShows(band, "Casa · contas, internet · dia 10")
        assertShows(InboxTags.PLANNED_NOT_IT + id.v, "Não é")
        assertShows(InboxTags.PLANNED_LAUNCH + id.v, "Lançar previsto")

        click(InboxTags.PLANNED_LAUNCH + id.v)
        waitGone(InboxTags.CARD + id.v)
        waitFor(SnackTags.TEXT)
        assertShows(SnackTags.TEXT, "Lançado: Internet, em Casa. O próximo é 10 nov.")
        assertShows(SnackTags.ACTION, "Desfazer")
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.any { it.date == OCT_9 } }
        val t = runBlocking { db.transactions() }.first { it.date == OCT_9 }
        assertEquals("legenda = nome do previsto", "Internet", t.caption)
        assertEquals("o valor é o do recado (P7)", Cents(12490), t.total)
        assertEquals(casa, t.budget)
        assertTrue("sem emenda", !t.corrected)

        click(SnackTags.ACTION)
        waitFor(InboxTags.CARD + id.v)
        waitFor(band)
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.none { it.date == OCT_9 } }
        assertTrue("a ocorrência volta a A caminho (S1)", plannedNow().any { it.name == "Internet" && it.noticeInPauta })
    }

    @Test
    fun `lancar previsto of a so uma vez previsto says only lancado`() {
        // A Só uma vez previsto linked by a first launch that was then deleted: open again, waiting and linked (S1).
        ahead("Conta de luz", casa, listOf("contas", "luz"), 18000, OCT_12, Repeats.ONCE)
        val luz = occ("Conta de luz")
        val id = post("Pagamento de conta: R$ 187,40 em ENEL SP", at(18, 20))
        runBlocking {
            val p = db.notice(id).parsed
            val tx = db.accept(id, TransactionForm(budget = casa, tags = listOf("contas", "luz"), total = p.cents, installments = 1, merchant = p.merchant, date = p.date, time = p.time, caption = "Conta de luz", occurrence = luz))
            assertTrue(tx.v != 0L)
            db.deleteTransaction(tx)
        }
        open()
        openPauta()

        waitFor(InboxTags.PLANNED_BAND + id.v)
        assertShows(InboxTags.PLANNED_BAND + id.v, "É o previsto Conta de luz")
        click(InboxTags.PLANNED_LAUNCH + id.v)
        waitFor(SnackTags.TEXT)
        assertShows(SnackTags.TEXT, "Lançado: Conta de luz, em Casa.")
        assertNotShows(SnackTags.TEXT, "O próximo")
    }

    @Test
    fun `nao e takes the band away only while the pauta stays open`() {
        val id = internetWithRecado()
        open()
        openPauta()
        waitFor(InboxTags.PLANNED_BAND + id.v)

        click(InboxTags.PLANNED_NOT_IT + id.v)
        waitGone(InboxTags.PLANNED_BAND + id.v)
        waitFor(InboxTags.ACCEPT + id.v)
        assertShows(InboxTags.ACCEPT + id.v, "Lançar")
        assertNotShows(InboxTags.ACCEPT + id.v, "previsto")
        assertEquals(0, count(InboxTags.PLANNED_LAUNCH + id.v))
        assertEquals(0, count(InboxTags.PLANNED_NOT_IT + id.v))
        assertEquals(1, count(InboxTags.DISMISS + id.v))
        assertTrue("Não é não grava nada", runBlocking { db.pending() }.first { it.id == id }.planned.occurrence.v != 0L)
        assertTrue("nada lançado", runBlocking { db.transactions() }.none { it.date == OCT_9 })

        openTab("Verbas", BudgetTags.LIST)
        openPauta()
        waitFor(InboxTags.PLANNED_BAND + id.v)
        assertShows(InboxTags.PLANNED_BAND + id.v, "É o previsto Internet")
    }

    @Test
    fun `the body of a recognized card opens lancar already linked`() {
        val id = internetWithRecado()
        open()
        openPauta()
        waitFor(InboxTags.PLANNED_BAND + id.v)

        // The top of the card (fonte and hora) is its body, away from the band and the buttons.
        compose.onNodeWithTag(InboxTags.CARD + id.v).performTouchInput { click(percentOffset(0.5f, 0.06f)) }
        waitFor(TransactionTags.FORM_TITLE)
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_TITLE, "Lançar recado") }
        waitFor(TransactionTags.FORM_LINKED)
        assertShows(TransactionTags.FORM_LINKED, "Ligado à Internet")
        assertShows(TransactionTags.FORM_LINKED, "Todo mês, dia 10.")
        assertEquals("ligado, sem Repete", 0, count(TransactionTags.FORM_REPEATS))
        assertEquals("ligado, sem o link", 0, count(TransactionTags.FORM_LINK_OPEN))
        assertEquals("Internet", editable(TransactionTags.FORM_CAPTION))
        compose.onNodeWithTag(TransactionTags.BUDGET + casa.v).assertIsSelected()
        assertShows(TransactionTags.FORM_SUBMIT, "Lançar ${money(Cents(12490))}")
        assertEquals(0, count(TransactionTags.FORM_TOTAL_EMENDA))
    }

    private companion object {
        const val BANK = "com.cartaoipe"
        val ZONE = TimeZone.of("America/Sao_Paulo")
        val SEP_1: LocalDate = LocalDate(2026, 9, 1)
        val SEP_5: LocalDate = LocalDate(2026, 9, 5)
        val SEP_10: LocalDate = LocalDate(2026, 9, 10)
        val OCT_1: LocalDate = LocalDate(2026, 10, 1)
        val OCT_5: LocalDate = LocalDate(2026, 10, 5)
        val OCT_7: LocalDate = LocalDate(2026, 10, 7)
        val OCT_8: LocalDate = LocalDate(2026, 10, 8)
        val OCT_9: LocalDate = LocalDate(2026, 10, 9)
        val OCT_12: LocalDate = LocalDate(2026, 10, 12)
        val OCT_15: LocalDate = LocalDate(2026, 10, 15)
        val NOV_1: LocalDate = LocalDate(2026, 11, 1)
        val NOV_9: LocalDate = LocalDate(2026, 11, 9)
    }
}
