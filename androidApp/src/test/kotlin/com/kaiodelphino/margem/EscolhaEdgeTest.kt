package com.kaiodelphino.margem

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InboxTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.DateTimeTags
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.dateLong
import com.kaiodelphino.margem.widgets.money
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
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
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt

/**
 * Edges of the verba field and sheet and of the marcador search in the one transaction form (spec §2.5, §7.3
 * "Verba" and "Marcadores", glossary, D61 fold, D66). Written blind against the contract: the KDoc of
 * [TransactionTags] (FORM_BUDGET, BUDGET_SHEET, BUDGET_SEARCH, BUDGET, BUDGET_SHEET_NEW, BUDGETS_EMPTY, TAG,
 * TAG_SEARCH, TAG_SHORTCUT, TAG_RESULT, TAG_MORE, TAG_CREATE, TAG_NONE, FORM_MISSING, FORM_SUBMIT,
 * FORM_PREVIEW) before the implementation exists; triaged when it lands.
 *
 * Setup per test: a real Db (AndroidSQLiteDriver, D28) on a temporary file, today fixed on Friday, Oct 9 2026,
 * month start day 1 (default), no ceiling, a tall screen so sheet rows and form parts are composed together.
 * Budgets Lazer (R$ 300,00), Casa (R$ 1.500,00) and Saúde (R$ 200,00) are created on Sep 1 and carried into
 * October with ensureMonths, so both months have a limit row. Tag use counts come from manual transactions in
 * Casa (R$ 1,00 each, Oct 1), which no margin assertion here reads.
 *
 * Only tags, texts, `selected`, stateDescription and focus are read from the screen; Db reads check what was
 * written. Ordering is read in reading order (top, then left), so a column and a flow of chips both work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp")
class EscolhaEdgeTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db
    private var lazer = BudgetId(0)
    private var casa = BudgetId(0)
    private var saude = BudgetId(0)

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem-escolha.db").also { it.delete() }.path)
        runBlocking {
            db.addSource(BANK, "Cartão Ipê", 0)
            lazer = db.createBudget("Lazer", Cents(30000), SEP_1)
            casa = db.createBudget("Casa", Cents(150000), SEP_1)
            saude = db.createBudget("Saúde", Cents(20000), SEP_1)
            db.ensureMonths(OCTOBER)
        }
    }

    /** Set by the teardown: the screen leaves before the Db closes, so no query in flight meets a closed Db. */
    private var gone by mutableStateOf(false)

    @After
    fun tearDown() {
        gone = true
        compose.waitForIdle()
        db.close()
    }

    private fun open() = compose.setContent {
        if (gone) return@setContent
        MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { OCT_9 }) { App(db, null) {} } }
    }

    // ---- Finding things ----

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun hasTagPrefix(prefix: String) =
        SemanticsMatcher("testTag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    /** The suffixes of the tags starting with [prefix] on screen, in reading order (top, then left). */
    private fun order(prefix: String): List<String> =
        compose.onAllNodes(hasTagPrefix(prefix)).fetchSemanticsNodes()
            .sortedWith(compareBy<SemanticsNode>({ it.boundsInRoot.top.roundToInt() }, { it.boundsInRoot.left }))
            .map { it.config[SemanticsProperties.TestTag].removePrefix(prefix) }
            .distinct()

    private fun countPrefix(prefix: String) = order(prefix).size

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

    private fun waitShows(tag: String, s: String) = compose.waitUntil(5_000) { shows(tag, s) }

    /** What a text field holds. */
    private fun editable(tag: String): String =
        node(tag)?.let { n -> subtree(n).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text } } ?: ""

    private fun state(tag: String): String? =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun selected(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Selected) == true

    // ---- Navigation ----

    private fun openPauta() = click(BudgetTags.PAUTA_STRIP)

    /** The tab bar has no tags (block 2): a tab is found by its label. */
    private fun openTab(label: String, ready: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        waitFor(ready)
    }

    /** Novo lançamento: empty, today, no suggestion. */
    private fun openManual() {
        openTab("Lançamentos", TransactionTags.NEW)
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_NAME)
    }

    /** The system back of the Activity (forms are screens); then waits for [ready]. */
    private fun pressBack(ready: String) {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(ready)
    }

    /** The system back on the open sheet: sheets are dialog windows, so the back goes to the latest dialog. */
    @Suppress("DEPRECATION")
    private fun pressBackOnSheet() {
        compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
    }

    private fun openSheet() {
        click(TransactionTags.FORM_BUDGET)
        waitFor(TransactionTags.BUDGET_SHEET)
    }

    private fun chooseBudget(id: BudgetId) {
        if (count(TransactionTags.BUDGET_SHEET) == 0) openSheet()
        click(TransactionTags.BUDGET + id.v)
        waitGone(TransactionTags.BUDGET_SHEET)
    }

    private fun searchBudget(text: String) {
        compose.onNodeWithTag(TransactionTags.BUDGET_SEARCH).performTextReplacement(text)
        compose.waitForIdle()
    }

    /** Focuses "Buscar ou criar marcador" with no text: the shortcuts. */
    private fun focusTagSearch() {
        click(TransactionTags.TAG_SEARCH)
        compose.waitForIdle()
    }

    private fun searchTag(text: String) {
        compose.onNodeWithTag(TransactionTags.TAG_SEARCH).performTextReplacement(text)
        compose.waitForIdle()
    }

    /** The keyboard's done on the tag search. */
    private fun doneTag() {
        compose.onNodeWithTag(TransactionTags.TAG_SEARCH).performImeAction()
        compose.waitForIdle()
    }

    /** Opens the date dialog, goes back months as needed, chooses [day] and keeps the time. */
    private fun pickDate(day: LocalDate) {
        click(TransactionTags.FORM_DATE)
        waitFor(DateTimeTags.DIALOG)
        // Triage: the dialog opens on the form's month; from September, October is ahead (NEXT), not behind.
        if (count(DateTimeTags.DAY + day) == 0) click(DateTimeTags.NEXT)
        repeat(3) { if (count(DateTimeTags.DAY + day) == 0) click(DateTimeTags.PREV) }
        click(DateTimeTags.DAY + day)
        click(DateTimeTags.DONE)
        waitGone(DateTimeTags.DIALOG)
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_DATE, dateLong(day)) }
    }

    /** Fills Nova verba (BudgetTags form) and submits; the app goes back to where it came from. */
    private fun createBudgetOnScreen(name: String, digits: String) {
        waitFor(BudgetTags.FORM_NAME)
        compose.onNodeWithTag(BudgetTags.FORM_NAME).performTextReplacement(name)
        compose.onNodeWithTag(BudgetTags.FORM_LIMIT).performTextReplacement(digits)
        click(BudgetTags.FORM_SUBMIT)
    }

    // ---- Data, through the public Db API ----

    private fun at(hour: Int, minute: Int, day: LocalDate = OCT_9) =
        LocalDateTime(day.year, day.month, day.day, hour, minute).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private fun post(text: String, postedAt: Long): NoticeId = runBlocking {
        assertTrue(capture(db, RawNotice(packageName = BANK, appLabel = "Cartão Ipê", key = "k$postedAt", postedAt = postedAt, title = "Cartão Ipê", text = text)))
        db.pending().first { it.raw.postedAt == postedAt }.id
    }

    private fun spend(budget: BudgetId, cents: Long, date: LocalDate, tag: String = "gasto") = runBlocking {
        assertTrue(db.addManual(TransactionForm(budget = budget, tags = listOf(tag), total = Cents(cents), installments = 1, merchant = "Loja", date = date, time = LocalTime(10, 0))).transaction.v != 0L)
    }

    /**
     * Gives each tag its use count: transaction j (0-based) carries every tag with more than j uses, so a tag
     * with n uses is on exactly n transactions. All in Casa, R$ 1,00, Oct 1.
     */
    private fun uses(vararg tags: Pair<String, Int>) = runBlocking {
        val max = tags.maxOf { it.second }
        for (j in 0 until max) {
            val names = tags.filter { it.second > j }.map { it.first }
            assertTrue(db.addManual(TransactionForm(budget = casa, tags = names, total = Cents(100), installments = 1, merchant = "Loja $j", date = OCT_1, time = LocalTime(10, 0))).transaction.v != 0L)
        }
        val got = db.tags().associate { it.name to it.uses }
        for ((name, n) in tags) assertEquals("uses de $name", n, got[name])
    }

    /**
     * The Lançar form of a second PADARIA DO ZE recado (R$ 12,00), after a first one was launched in Lazer
     * with padaria and café: Lazer comes "sugerida", padaria and café "sugerido" (spec §2.5).
     */
    private fun openSuggested(): NoticeId {
        val first = post("Compra aprovada: R$ 23,90 em PADARIA DO ZE", at(7, 0))
        runBlocking {
            val p = db.notice(first).parsed
            assertTrue(db.accept(first, TransactionForm(lazer, listOf("padaria", "café"), p.cents, 1, p.merchant, p.date, p.time)).v != 0L)
        }
        val second = post("Compra aprovada: R$ 12,00 em PADARIA DO ZE", at(8, 0))
        open()
        openPauta()
        click(InboxTags.ACCEPT + second.v)
        waitFor(TransactionTags.TAG + "padaria")
        waitFor(TransactionTags.FORM_BUDGET)
        assertEquals("a verba da sugestão vem sugerida", "sugerida", state(TransactionTags.FORM_BUDGET))
        return second
    }

    private fun budgetNamed(name: String): BudgetId =
        runBlocking { db.budgetsOverview(OCTOBER, OCT_9, BudgetOrder.MANUAL) }.first { it.name == name }.id

    private fun tagNames(): List<String> = runBlocking { db.tags() }.map { it.name }

    // ---- Verba: no budgets (BUDGETS_EMPTY) ----

    @Test
    fun `with no budget the form shows the empty line instead of the verba field`() {
        runBlocking { for (b in listOf(lazer, casa, saude)) db.deleteBudget(b) }
        open()
        openManual()

        waitFor(TransactionTags.BUDGETS_EMPTY)
        assertShows(TransactionTags.BUDGETS_EMPTY, "Crie uma verba antes de lançar.")
        assertShows(TransactionTags.BUDGETS_NEW, "Nova verba")
        assertEquals("sem verba, o campo não existe", 0, count(TransactionTags.FORM_BUDGET))
        // FORM_MISSING: total 0, no budget, no tag → "o valor", "a verba", "um marcador" joined with ", " and " e ".
        assertShows(TransactionTags.FORM_MISSING, "Falta o valor, a verba e um marcador.")
        compose.onNodeWithTag(TransactionTags.FORM_SUBMIT).assertIsNotEnabled()

        click(TransactionTags.BUDGETS_NEW)
        createBudgetOnScreen("Viagem", "31000")
        waitFor(TransactionTags.FORM_BUDGET)
        assertEquals("com uma verba, a linha vazia sai", 0, count(TransactionTags.BUDGETS_EMPTY))
    }

    // ---- Verba: margins ----

    @Test
    fun `a margin past the limit reads alem in the field and in the sheet`() {
        // October: Lazer 300,00 − 334,00 = −34,00 → "R$ 34,00 além".
        spend(lazer, 33400, OCT_2)
        open()
        openManual()
        chooseBudget(lazer)

        waitShows(TransactionTags.FORM_BUDGET, "Lazer")
        assertShows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(3400))} além")
        openSheet()
        assertShows(TransactionTags.BUDGET + lazer.v, "${money(Cents(3400))} além")
        // Casa 1.500,00 with nothing spent in October; Saúde 200,00.
        assertShows(TransactionTags.BUDGET + casa.v, money(Cents(150000)))
        assertNotShows(TransactionTags.BUDGET + casa.v, "além")
        assertShows(TransactionTags.BUDGET + saude.v, money(Cents(20000)))
    }

    @Test
    fun `the margin is the one of the month of the form date`() {
        // September: 300,00 − 100,00 = 200,00. October: 300,00 − 334,00 = 34,00 além.
        spend(lazer, 10000, SEP_15)
        spend(lazer, 33400, OCT_2)
        open()
        openManual()
        chooseBudget(lazer)
        waitShows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(3400))} além")

        pickDate(SEP_20)
        waitShows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(20000))}")
        assertNotShows(TransactionTags.FORM_BUDGET, "além")
        openSheet()
        assertShows(TransactionTags.BUDGET + lazer.v, money(Cents(20000)))
        assertNotShows(TransactionTags.BUDGET + lazer.v, "além")
    }

    @Test
    fun `a budget with no limit row in the form month shows no margin`() {
        // Viagem is created on Oct 9: a limit row in October only (ensureMonths never fills backwards).
        val viagem = runBlocking { db.createBudget("Viagem", Cents(31000), OCT_9) }
        open()
        openManual()
        chooseBudget(viagem)
        waitShows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(31000))}")

        pickDate(SEP_20)
        waitShows(TransactionTags.FORM_BUDGET, "Viagem")
        compose.waitUntil(5_000) { !shows(TransactionTags.FORM_BUDGET, "margem") }
        assertNotShows(TransactionTags.FORM_BUDGET, "R$")
        assertShows(TransactionTags.FORM_BUDGET, "sem cota em setembro") // spec §7.3, 2026-10-23
        openSheet()
        // Still listed, still chosen, with no margin; Lazer has its September row: 300,00, nothing spent.
        assertShows(TransactionTags.BUDGET + viagem.v, "Viagem")
        assertNotShows(TransactionTags.BUDGET + viagem.v, "R$")
        assertShows(TransactionTags.BUDGET + viagem.v, "sem cota")
        assertTrue("a escolhida segue marcada na folha", selected(TransactionTags.BUDGET + viagem.v))
        assertShows(TransactionTags.BUDGET + lazer.v, money(Cents(30000)))
        chooseBudget(viagem)

        pickDate(OCT_8)
        waitShows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(31000))}")
    }

    @Test
    fun `the field and the sheet leave out the value being typed`() {
        open()
        openManual()
        chooseBudget(casa)
        compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performTextReplacement("50000")
        // FORM_PREVIEW: 1.500,00 → 1.500,00 − 500,00 = 1.000,00. The field and the sheet keep 1.500,00.
        waitFor(TransactionTags.FORM_PREVIEW)
        waitShows(TransactionTags.FORM_PREVIEW, money(Cents(100000)))
        assertShows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(150000))}")
        assertNotShows(TransactionTags.FORM_BUDGET, money(Cents(100000)))
        openSheet()
        assertShows(TransactionTags.BUDGET + casa.v, money(Cents(150000)))
        assertNotShows(TransactionTags.BUDGET + casa.v, money(Cents(100000)))
    }

    // ---- Verba: a budget deleted while the form is open ----

    @Test
    fun `a budget deleted while the form is open stops being chosen`() {
        open()
        openManual()
        chooseBudget(saude)
        waitShows(TransactionTags.FORM_BUDGET, "Saúde")

        runBlocking { db.deleteBudget(saude) }
        waitShows(TransactionTags.FORM_BUDGET, "Escolher a verba")
        assertNotShows(TransactionTags.FORM_BUDGET, "Saúde")
        assertShows(TransactionTags.FORM_MISSING, "a verba")
        assertEquals("sem verba escolhida, sem prévia", 0, count(TransactionTags.FORM_PREVIEW))
        openSheet()
        assertEquals("a apagada sai da folha", 0, count(TransactionTags.BUDGET + saude.v))
        assertEquals(listOf(lazer.v.toString(), casa.v.toString()), order(TransactionTags.BUDGET))
        assertTrue("nenhuma escolhida", order(TransactionTags.BUDGET).none { selected(TransactionTags.BUDGET + it) })
    }

    // ---- Verba: the sheet ----

    @Test
    fun `the sheet lists budgets in manual order with nova verba last`() {
        // Manual order differs from creation (Lazer, Casa, Saúde) and from A–Z (Casa, Lazer, Saúde).
        assertTrue(runBlocking { db.reorderBudgets(listOf(saude, lazer, casa)) })
        open()
        openManual()
        openSheet()

        assertEquals(listOf(saude.v, lazer.v, casa.v).map { it.toString() }, order(TransactionTags.BUDGET))
        assertTrue("sem verba escolhida, nenhuma marcada", order(TransactionTags.BUDGET).none { selected(TransactionTags.BUDGET + it) })
        val last = order(TransactionTags.BUDGET).maxOf { node(TransactionTags.BUDGET + it)!!.boundsInRoot.top }
        assertTrue("Nova verba vem por último", node(TransactionTags.BUDGET_SHEET_NEW)!!.boundsInRoot.top > last)
        assertShows(TransactionTags.BUDGET_SHEET_NEW, "Nova verba")
        assertEquals("a busca abre vazia", "", editable(TransactionTags.BUDGET_SEARCH))
    }

    @Test
    fun `the sheet search ignores case and accents and keeps nova verba`() {
        open()
        openManual()
        openSheet()

        // fold("Saúde") = "saude": "saude", "SAÚDE" and "UD" all find it, and only it.
        for (text in listOf("saude", "SAÚDE", "UD")) {
            searchBudget(text)
            assertEquals("busca \"$text\"", listOf(saude.v.toString()), order(TransactionTags.BUDGET))
            assertEquals("busca \"$text\": Nova verba fica", 1, count(TransactionTags.BUDGET_SHEET_NEW))
        }
        // "a" is in Lazer, Casa and Saúde: all three, in manual order.
        searchBudget("A")
        assertEquals(listOf(lazer.v, casa.v, saude.v).map { it.toString() }, order(TransactionTags.BUDGET))
        searchBudget("xyz")
        assertEquals("nenhuma verba com xyz", 0, countPrefix(TransactionTags.BUDGET))
        assertEquals("Nova verba fica sem achado", 1, count(TransactionTags.BUDGET_SHEET_NEW))
    }

    @Test
    fun `the sheet search is empty again when the sheet reopens`() {
        open()
        openManual()
        openSheet()
        // Triage: "sa" is also in "Casa" (BUDGET_SHEET: contains), so the probe is "sau". A miss names the text (2026-10-23).
        searchBudget("xyz")
        assertShows(TransactionTags.BUDGET_NONE, "Nenhuma verba com “xyz”.")
        searchBudget("sau")
        assertEquals(listOf(saude.v.toString()), order(TransactionTags.BUDGET))
        chooseBudget(saude)

        openSheet()
        assertEquals("a busca volta vazia", "", editable(TransactionTags.BUDGET_SEARCH))
        assertEquals(3, countPrefix(TransactionTags.BUDGET))
        assertTrue(selected(TransactionTags.BUDGET + saude.v))
        assertTrue("exatamente uma escolhida", !selected(TransactionTags.BUDGET + lazer.v) && !selected(TransactionTags.BUDGET + casa.v))
    }

    @Test
    fun `choosing the suggested budget in the sheet makes it no longer sugerida`() {
        openSuggested()
        openSheet()
        assertTrue("a sugerida aparece escolhida na folha", selected(TransactionTags.BUDGET + lazer.v))
        assertTrue(!selected(TransactionTags.BUDGET + casa.v) && !selected(TransactionTags.BUDGET + saude.v))

        chooseBudget(lazer)
        compose.waitUntil(5_000) { state(TransactionTags.FORM_BUDGET) != "sugerida" }
        assertShows(TransactionTags.FORM_BUDGET, "Lazer")
        // The tags stay as they came.
        assertEquals("sugerido", state(TransactionTags.TAG + "padaria"))
    }

    @Test
    fun `back from the sheet changes nothing`() {
        openSuggested()
        openSheet()
        searchBudget("ca")
        pressBackOnSheet()
        waitGone(TransactionTags.BUDGET_SHEET)

        assertShows(TransactionTags.FORM_BUDGET, "Lazer")
        assertEquals("segue sugerida", "sugerida", state(TransactionTags.FORM_BUDGET))
        openSheet()
        assertEquals("a busca volta vazia", "", editable(TransactionTags.BUDGET_SEARCH))
        assertTrue(selected(TransactionTags.BUDGET + lazer.v))
    }

    @Test
    fun `nova verba from the sheet comes back chosen and not sugerida`() {
        val notice = openSuggested()
        openSheet()
        click(TransactionTags.BUDGET_SHEET_NEW)
        createBudgetOnScreen("Viagem", "31000")

        waitFor(TransactionTags.FORM_BUDGET)
        waitShows(TransactionTags.FORM_BUDGET, "Viagem")
        // Created on Oct 9 with 310,00; nothing spent; the form's date is the recado's, Oct 9.
        assertShows(TransactionTags.FORM_BUDGET, "margem ${money(Cents(31000))}")
        assertTrue("a criada vem cheia", state(TransactionTags.FORM_BUDGET) != "sugerida")
        val viagem = budgetNamed("Viagem")
        openSheet()
        assertTrue(selected(TransactionTags.BUDGET + viagem.v))
        assertTrue("exatamente uma", !selected(TransactionTags.BUDGET + lazer.v))
        chooseBudget(viagem)

        // The suggested tags still count: nothing is missing.
        assertEquals(0, count(TransactionTags.FORM_MISSING))
        click(TransactionTags.FORM_SUBMIT)
        waitGone(TransactionTags.FORM_TITLE)
        assertEquals(viagem, runBlocking { db.transactions() }.first { it.notice == notice }.budget)
    }

    @Test
    fun `coming back from nova verba without creating changes nothing`() {
        openSuggested()
        openSheet()
        click(TransactionTags.BUDGET_SHEET_NEW)
        waitFor(BudgetTags.FORM_NAME)
        pressBack(TransactionTags.FORM_BUDGET)

        waitShows(TransactionTags.FORM_BUDGET, "Lazer")
        assertEquals("segue sugerida", "sugerida", state(TransactionTags.FORM_BUDGET))
        assertEquals("sugerido", state(TransactionTags.TAG + "padaria"))
        assertEquals("nenhuma verba nova", 3, runBlocking { db.budgetsOverview(OCTOBER, OCT_9, BudgetOrder.MANUAL) }.size)
    }

    // ---- Marcadores: the search ----

    @Test
    fun `tag search ignores case accents and edge spaces and ranks the ones starting with it first`() {
        uses("café" to 2, "cafeteria" to 5, "descafeinado" to 1, "padaria" to 3)
        open()
        openManual()

        // fold("  CAFE ".trim()) = "cafe". Starting with it: cafeteria (5), café (2) — uses desc;
        // then containing it: descafeinado. padaria doesn't contain it.
        searchTag("  CAFE ")
        assertEquals(listOf("cafeteria", "café", "descafeinado"), order(TransactionTags.TAG_RESULT))
        assertShows(TransactionTags.TAG_RESULT + "café", "2 lançamentos")
        assertShows(TransactionTags.TAG_RESULT + "cafeteria", "5 lançamentos")
        assertEquals("com achados, sem Nenhum", 0, count(TransactionTags.TAG_NONE))

        // Clean text "café" is the name of an existing tag: no Criar.
        searchTag("  CAFÉ  ")
        assertEquals(listOf("cafeteria", "café", "descafeinado"), order(TransactionTags.TAG_RESULT))
        assertEquals("texto é nome de marcador: sem Criar", 0, count(TransactionTags.TAG_CREATE))
        assertEquals(0, count(TransactionTags.TAG_NONE))
        assertEquals("digitando, sem atalhos", 0, countPrefix(TransactionTags.TAG_SHORTCUT))
    }

    @Test
    fun `one use reads lancamento in the singular`() {
        uses("luz" to 1, "lugar" to 2)
        open()
        openManual()
        searchTag("lu")

        // Both start with "lu": lugar (2) before luz (1).
        assertEquals(listOf("lugar", "luz"), order(TransactionTags.TAG_RESULT))
        assertShows(TransactionTags.TAG_RESULT + "luz", "1 lançamento")
        assertNotShows(TransactionTags.TAG_RESULT + "luz", "1 lançamentos")
        assertShows(TransactionTags.TAG_RESULT + "lugar", "2 lançamentos")
    }

    @Test
    fun `a chosen result shows its check and no count`() {
        // Spec §7.3: the chosen one is a result "com ✓ e nada mais".
        uses("luz" to 1, "lugar" to 2)
        open()
        openManual()
        searchTag("lu")
        click(TransactionTags.TAG_RESULT + "luz")
        waitFor(TransactionTags.TAG + "luz")

        searchTag("lu")
        assertTrue(selected(TransactionTags.TAG_RESULT + "luz"))
        assertNotShows(TransactionTags.TAG_RESULT + "luz", "lançamento")
        assertShows(TransactionTags.TAG_RESULT + "lugar", "2 lançamentos")
    }

    /** The ranges drawn bold (weight 600 or more) in the Text reading [text], in the node with [tag] and below. */
    private fun boldRanges(tag: String, text: String): List<IntRange> = node(tag)?.let { n ->
        subtree(n).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.filter { it.text == text }
            .flatMap { a -> a.spanStyles.filter { (it.item.fontWeight?.weight ?: 0) >= 600 }.map { it.start until it.end } }
    }.orEmpty()

    @Test
    fun `the found part of each result is bold`() {
        // Book 8.12 (D66): the part found, folded like the search (case and accents ignored), in bold.
        uses("café" to 2, "cafeteria" to 5, "descafeinado" to 1)
        open()
        openManual()
        searchTag("CAFE")
        assertEquals(listOf("cafeteria", "café", "descafeinado"), order(TransactionTags.TAG_RESULT))
        assertEquals(listOf(0 until 4), boldRanges(TransactionTags.TAG_RESULT + "cafeteria", "cafeteria"))
        assertEquals(listOf(0 until 4), boldRanges(TransactionTags.TAG_RESULT + "café", "café"))
        assertEquals(listOf(3 until 7), boldRanges(TransactionTags.TAG_RESULT + "descafeinado", "descafeinado"))
    }

    @Test
    fun `uppercase text with spaces offers criar in lowercase trimmed and nenhum`() {
        uses("feira" to 2)
        open()
        openManual()
        searchTag("  Festa Junina  ")

        waitFor(TransactionTags.TAG_CREATE)
        assertShows(TransactionTags.TAG_CREATE, "Criar “festa junina”")
        assertShows(TransactionTags.TAG_CREATE, "novo, só é gravado ao lançar")
        assertNotShows(TransactionTags.TAG_CREATE, "Festa Junina")
        assertEquals(0, countPrefix(TransactionTags.TAG_RESULT))
        assertShows(TransactionTags.TAG_NONE, "Nenhum marcador com “festa junina”.")
    }

    @Test
    fun `nenhum appears only while nothing matches`() {
        uses("feira" to 2)
        open()
        openManual()

        searchTag("pizzaria")
        assertShows(TransactionTags.TAG_NONE, "Nenhum marcador com “pizzaria”.")
        assertShows(TransactionTags.TAG_CREATE, "Criar “pizzaria”")
        assertEquals(0, countPrefix(TransactionTags.TAG_RESULT))

        // "fe" is in feira and is no tag's name: a result and Criar, no Nenhum.
        searchTag("fe")
        assertEquals(listOf("feira"), order(TransactionTags.TAG_RESULT))
        assertShows(TransactionTags.TAG_CREATE, "Criar “fe”")
        assertEquals(0, count(TransactionTags.TAG_NONE))
    }

    @Test
    fun `every match is listed and a chosen one stays with its check`() {
        uses("bar" to 1, "barbearia" to 2, "embarque" to 3, "rebarba" to 4, "barco" to 5, "barraca" to 6)
        open()
        openManual()

        // "bar" (spec §7.3, 2026-10-23: no limit, the list scrolls): starting with it, by uses — barraca 6,
        // barco 5, barbearia 2, bar 1; then containing it — rebarba 4, embarque 3.
        val all = listOf("barraca", "barco", "barbearia", "bar", "rebarba", "embarque")
        searchTag("bar")
        assertEquals(all, order(TransactionTags.TAG_RESULT))
        assertEquals("bar é nome de marcador: sem Criar", 0, count(TransactionTags.TAG_CREATE))

        click(TransactionTags.TAG_RESULT + "barraca")
        waitFor(TransactionTags.TAG + "barraca")
        assertEquals("o toque limpa o texto", "", editable(TransactionTags.TAG_SEARCH))
        // The chosen one stays in its place, selected (✓); a touch on it does nothing.
        searchTag("bar")
        assertEquals(all, order(TransactionTags.TAG_RESULT))
        assertTrue(selected(TransactionTags.TAG_RESULT + "barraca"))
        assertTrue(!selected(TransactionTags.TAG_RESULT + "barco"))
        click(TransactionTags.TAG_RESULT + "barraca")
        assertEquals("tocar no escolhido não faz nada", listOf("barraca"), order(TransactionTags.TAG))
        assertEquals("bar", editable(TransactionTags.TAG_SEARCH))
    }

    @Test
    fun `text equal to a chosen tag shows it checked with no criar and no nenhum`() {
        uses("luz" to 1, "feira" to 2)
        open()
        openManual()
        searchTag("luz")
        click(TransactionTags.TAG_RESULT + "luz")
        waitFor(TransactionTags.TAG + "luz")

        searchTag(" LUZ ")
        // Spec §7.3 (2026-10-23): the chosen one is a result with its ✓.
        assertEquals("o escolhido aparece, marcado", listOf("luz"), order(TransactionTags.TAG_RESULT))
        assertTrue(selected(TransactionTags.TAG_RESULT + "luz"))
        assertEquals("nome de um escolhido: sem Criar", 0, count(TransactionTags.TAG_CREATE))
        assertEquals("há resultado: sem Nenhum", 0, count(TransactionTags.TAG_NONE))

        doneTag()
        assertEquals("nunca duas vezes", listOf("luz"), order(TransactionTags.TAG))
        compose.waitUntil(5_000) { editable(TransactionTags.TAG_SEARCH) == "" }
    }

    // ---- Marcadores: the shortcuts ----

    @Test
    fun `shortcuts are the eight most used not chosen with ties by name`() {
        uses(
            "zebra" to 5, "mercado" to 4, "banco" to 3, "aluguel" to 3, "carro" to 3,
            "feira" to 2, "internet" to 2, "luz" to 1, "gas" to 1, "pet" to 1,
        )
        open()
        openManual()
        focusTagSearch()

        // uses desc, then name: zebra 5, mercado 4, aluguel/banco/carro 3, feira/internet 2, gas/luz/pet 1 → first 8.
        assertEquals(listOf("zebra", "mercado", "aluguel", "banco", "carro", "feira", "internet", "gas"), order(TransactionTags.TAG_SHORTCUT))
        assertEquals("vazio: sem resultados", 0, countPrefix(TransactionTags.TAG_RESULT))

        click(TransactionTags.TAG_SHORTCUT + "zebra")
        waitFor(TransactionTags.TAG + "zebra")
        assertTrue("escolhido pelo atalho não é sugerido", state(TransactionTags.TAG + "zebra") != "sugerido")
        compose.onNodeWithTag(TransactionTags.TAG_SEARCH).assertIsFocused()
        // zebra leaves; luz (next by name among the 1s) enters as the eighth.
        compose.waitUntil(5_000) { count(TransactionTags.TAG_SHORTCUT + "zebra") == 0 }
        assertEquals(listOf("mercado", "aluguel", "banco", "carro", "feira", "internet", "gas", "luz"), order(TransactionTags.TAG_SHORTCUT))
    }

    @Test
    fun `with fewer than eight tags every unchosen one is a shortcut and none once all are chosen`() {
        uses("feira" to 2, "luz" to 1, "gas" to 1)
        open()
        openManual()
        focusTagSearch()
        assertEquals(listOf("feira", "gas", "luz"), order(TransactionTags.TAG_SHORTCUT))

        for (t in listOf("feira", "gas", "luz")) {
            click(TransactionTags.TAG_SHORTCUT + t)
            waitFor(TransactionTags.TAG + t)
        }
        compose.waitUntil(5_000) { countPrefix(TransactionTags.TAG_SHORTCUT) == 0 }
        assertEquals("na ordem em que foram escolhidos", listOf("feira", "gas", "luz"), order(TransactionTags.TAG))
    }

    // ---- Marcadores: the keyboard's done ----

    @Test
    fun `done on blank text adds nothing`() {
        uses("feira" to 2)
        open()
        openManual()
        focusTagSearch()

        searchTag("   ")
        doneTag()
        assertEquals("nada marcado", 0, countPrefix(TransactionTags.TAG))
        searchTag("")
        doneTag()
        assertEquals("nada marcado", 0, countPrefix(TransactionTags.TAG))
        assertShows(TransactionTags.FORM_MISSING, "um marcador")
        assertEquals(listOf("feira"), tagNames())
    }

    @Test
    fun `done marks the existing tag of the clean name or makes a new one`() {
        uses("feira" to 2)
        open()
        openManual()
        focusTagSearch()

        // trim().lowercase() of "  FEIRA " is "feira", an existing tag: marked, nothing new.
        searchTag("  FEIRA ")
        doneTag()
        waitFor(TransactionTags.TAG + "feira")
        compose.waitUntil(5_000) { editable(TransactionTags.TAG_SEARCH) == "" }
        compose.onNodeWithTag(TransactionTags.TAG_SEARCH).assertIsFocused()

        searchTag(" Nova Coisa ")
        doneTag()
        waitFor(TransactionTags.TAG + "nova coisa")
        assertEquals(listOf("feira", "nova coisa"), order(TransactionTags.TAG))
        assertTrue("criado aqui não é sugerido", state(TransactionTags.TAG + "nova coisa") != "sugerido")
        assertEquals("só é gravado ao lançar", listOf("feira"), tagNames())
    }

    // ---- Marcadores: created here, written on save ----

    @Test
    fun `a tag created in the form is written only when the form is saved`() {
        uses("feira" to 2)
        open()
        openManual()
        searchTag("  Festa Junina  ")
        click(TransactionTags.TAG_CREATE)

        waitFor(TransactionTags.TAG + "festa junina")
        assertTrue(selected(TransactionTags.TAG + "festa junina"))
        assertTrue(state(TransactionTags.TAG + "festa junina") != "sugerido")
        assertEquals("o toque limpa o texto", "", editable(TransactionTags.TAG_SEARCH))
        compose.onNodeWithTag(TransactionTags.TAG_SEARCH).assertIsFocused()
        assertEquals("antes de salvar, nada no Db", listOf("feira"), tagNames())

        compose.onNodeWithTag(TransactionTags.FORM_NAME).performTextReplacement("Arraial")
        compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performTextReplacement("4600")
        chooseBudget(lazer)
        compose.waitUntil(5_000) { count(TransactionTags.FORM_MISSING) == 0 }
        click(TransactionTags.FORM_SUBMIT)
        waitGone(TransactionTags.FORM_TITLE)

        val tags = runBlocking { db.tags() }.associate { it.name to it.uses }
        assertEquals("gravado ao lançar, com um uso", 1, tags["festa junina"])
        assertEquals(listOf("festa junina"), runBlocking { db.transactions() }.first { it.total == Cents(4600) }.tags)
    }

    // ---- Marcadores: suggested chips ----

    @Test
    fun `the first tap on a suggested chip removes it and it comes back as a shortcut`() {
        openSuggested()
        assertEquals("sugerido", state(TransactionTags.TAG + "café"))
        assertEquals("sugeridas contam: nada falta", 0, count(TransactionTags.FORM_MISSING))
        focusTagSearch()
        assertEquals("escolhidos não são atalhos", 0, count(TransactionTags.TAG_SHORTCUT + "padaria") + count(TransactionTags.TAG_SHORTCUT + "café"))

        click(TransactionTags.TAG + "padaria")
        waitGone(TransactionTags.TAG + "padaria")
        assertEquals("o outro segue sugerido", "sugerido", state(TransactionTags.TAG + "café"))
        assertEquals(0, count(TransactionTags.FORM_MISSING))
        click(TransactionTags.TAG + "café")
        waitGone(TransactionTags.TAG + "café")
        waitShows(TransactionTags.FORM_MISSING, "Falta um marcador.")

        // Not chosen any more: both are shortcuts again (2 tags in the Db, fewer than 8).
        focusTagSearch()
        compose.waitUntil(5_000) { countPrefix(TransactionTags.TAG_SHORTCUT) == 2 }
        assertEquals(setOf("padaria", "café"), order(TransactionTags.TAG_SHORTCUT).toSet())
    }

    @Test
    fun `tags added here come after the suggested ones in the order they were chosen`() {
        openSuggested()
        searchTag("Bolo")
        click(TransactionTags.TAG_CREATE)
        waitFor(TransactionTags.TAG + "bolo")
        searchTag("agua")
        click(TransactionTags.TAG_CREATE)
        waitFor(TransactionTags.TAG + "agua")

        val chips = order(TransactionTags.TAG)
        assertEquals(setOf("padaria", "café"), chips.take(2).toSet())
        assertEquals("os daqui depois, na ordem da escolha", listOf("bolo", "agua"), chips.drop(2))
        assertTrue(state(TransactionTags.TAG + "bolo") != "sugerido")
    }

    private companion object {
        const val BANK = "com.cartaoipe"
        val OCTOBER = YearMonth(2026, 10)
        val SEP_1: LocalDate = LocalDate(2026, 9, 1)
        val SEP_15: LocalDate = LocalDate(2026, 9, 15)
        val SEP_20: LocalDate = LocalDate(2026, 9, 20)
        val OCT_1: LocalDate = LocalDate(2026, 10, 1)
        val OCT_2: LocalDate = LocalDate(2026, 10, 2)
        val OCT_8: LocalDate = LocalDate(2026, 10, 8)
        val OCT_9: LocalDate = LocalDate(2026, 10, 9)
    }
}
