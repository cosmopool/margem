package com.kaiodelphino.margem

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InboxTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.SettingsTags
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
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
 * The legenda drawn on the screens (spec §2.12, §7.1, §7.3, §7.4; D54–D57): the caption field of the form,
 * the row of Lançamentos, the budget detail line, the estorno candidates and search, and where the legenda
 * must not show. The contract is the tags and their KDoc ([TransactionTags], [BudgetTags], [InboxTags], and
 * [SettingsTags] for Riscados — D35); these tests run before the implementation and are triaged when it lands.
 *
 * Setup per test: a real Db (AndroidSQLiteDriver, D28) on a temporary "margem-legenda-*.db" (dropped with its
 * -wal/-shm), the device and the Db on TimeZone "America/Sao_Paulo", today fixed on Friday, Oct 9 2026.
 * Elements are found only by the tags; a tagged node's text is read in the unmerged tree (the node and below),
 * so a field's placeholder and a row's lines count as what the node shows.
 *
 * Robolectric does not see paint: the italic, the "…" cut (2 lines in the lists, 1 in the candidates) and the
 * ink 3 / strike of an estornada are checked on the emulator against the mockup (the plan's step 7). What is
 * asserted here is the text those rows show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LegendaUiTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: Db
    private lateinit var path: String
    private lateinit var hostZone: java.util.TimeZone
    private var lazer = BudgetId(0)

    @Before
    fun setUp() {
        hostZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"))
        path = tmp.newFile("margem-legenda-${Random.nextLong().toULong()}.db").also { it.delete() }.path
        db = Db(AndroidSQLiteDriver(), path, timeZone = ZONE)
        runBlocking {
            db.addSource(BANK, "Cartão Ipê", 0)
            lazer = db.createBudget("Lazer", Cents(30000), OCT_9)
        }
    }

    /** Set by the teardown: the screen leaves before the Db closes, so no query in flight meets a closed Db. */
    private var gone by mutableStateOf(false)

    @After
    fun tearDown() {
        gone = true
        compose.waitForIdle()
        db.close()
        for (suffix in listOf("", "-wal", "-shm")) java.io.File(path + suffix).delete()
        java.util.TimeZone.setDefault(hostZone)
    }

    private fun open() = compose.setContent {
        if (gone) return@setContent
        MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { OCT_9 }) { App(db, null) {} } }
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    /** Waits for [tag], scrolling the lazy lists to it: on Robolectric's small screen, items below the fold aren't composed. */
    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        for (list in listOf(InboxTags.LIST, InboxTags.REFUND_LIST, TransactionTags.LIST, SettingsTags.DISMISSED_LIST)) {
            if (count(tag) == 0 && count(list) > 0) runCatching { compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)) }
        }
        count(tag) > 0
    }

    private fun waitGone(tag: String) = compose.waitUntil(5_000) { count(tag) == 0 }

    private fun click(tag: String) {
        waitFor(tag)
        // Bottom bars and dialogs don't scroll; everything else may be below the fold.
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun at(hour: Int, minute: Int, day: LocalDate = OCT_9) =
        LocalDateTime(day.year, day.month, day.day, hour, minute).toInstant(ZONE).toEpochMilliseconds()

    /** Captures a notice from the bank and returns its id. */
    private fun post(text: String, postedAt: Long = at(7, 42)): NoticeId = runBlocking {
        assertTrue(capture(db, RawNotice(packageName = BANK, appLabel = "Cartão Ipê", key = "k$postedAt", postedAt = postedAt, title = "Cartão Ipê", text = text)))
        db.pending().first { it.raw.postedAt == postedAt }.id
    }

    /** Launches [notice] into Lazer with the tag "casa", its parsed value, date and time, plus [caption] and [alias]. */
    private fun seed(notice: NoticeId, caption: String = "", alias: String = ""): TransactionId = runBlocking {
        val p = db.notice(notice).parsed
        db.accept(notice, TransactionForm(budget = lazer, tags = listOf("casa"), total = p.cents, installments = 1, merchant = p.merchant, date = p.date, time = p.time, alias = alias, caption = caption))
    }

    /** Book 8.12: types [name] in the search and taps its result, or "Criar" when there is none. */
    private fun addTag(name: String) {
        compose.onNodeWithTag(TransactionTags.TAG_SEARCH).performTextReplacement(name)
        compose.waitUntil(5_000) { count(TransactionTags.TAG_RESULT + name) + count(TransactionTags.TAG_CREATE) > 0 }
        click(if (count(TransactionTags.TAG_RESULT + name) > 0) TransactionTags.TAG_RESULT + name else TransactionTags.TAG_CREATE)
        waitFor(TransactionTags.TAG + name)
    }

    /** Book 8.12: the verba field opens the sheet; a row chooses and closes it. */
    private fun chooseBudget(id: BudgetId) {
        click(TransactionTags.FORM_BUDGET)
        click(TransactionTags.BUDGET + id.v)
        waitGone(TransactionTags.BUDGET_SHEET)
    }

    private fun openPauta() = click(BudgetTags.PAUTA_STRIP)

    private fun openTransactions() = openTab("Lançamentos", TransactionTags.NEW)

    private fun openAjustes() = openTab("Ajustes", SettingsTags.DISMISSED)

    /** The tab bar has no tags (block 2): a tab is found by its label. */
    private fun openTab(label: String, ready: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        waitFor(ready)
    }

    /** After a search on Registrar estorno: [tag] is among the candidates and [out] is gone from the list entirely. */
    private fun searchShows(tag: String, out: String) {
        waitFor(tag)
        val found = runCatching { compose.onNodeWithTag(InboxTags.REFUND_LIST).performScrollToNode(hasTestTag(out)) }.isSuccess
        assertTrue("$out ainda está entre os candidatos: ${texts(out)}", !found && count(out) == 0)
    }

    // ---- Semantics helpers (unmerged tree: a tag may sit inside a merged parent) ----

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

    /** Like assertTextContains(substring = true), also accepting a value split across pieces of text. */
    private fun shows(tag: String, s: String): Boolean {
        val t = texts(tag)
        return t.any { it.contains(s) } || t.joinToString("").contains(s) || t.joinToString(" ").contains(s)
    }

    /** What a text field holds. */
    private fun editable(tag: String): String =
        node(tag)?.let { n -> subtree(n).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text } } ?: ""

    // ---- The form (spec §2.12, §7.3) ----

    @Test
    fun `the legenda link opens the field in place and the x folds it back`() {
        val id = post("Compra aprovada: R$ 46,00 em PADARIA ZE 0231")
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)

        waitFor(TransactionTags.FORM_CAPTION_OPEN)
        assertTrue("link: ${texts(TransactionTags.FORM_CAPTION_OPEN)}", shows(TransactionTags.FORM_CAPTION_OPEN, "+ Legenda · só deste lançamento"))
        assertEquals("campo recolhido", 0, count(TransactionTags.FORM_CAPTION))
        click(TransactionTags.FORM_CAPTION_OPEN)
        waitFor(TransactionTags.FORM_CAPTION)
        assertEquals("link some com o campo aberto", 0, count(TransactionTags.FORM_CAPTION_OPEN))
        compose.onNodeWithTag(TransactionTags.FORM_CAPTION).performTextReplacement(LEGENDA)
        click(TransactionTags.FORM_CAPTION_CLEAR)
        waitGone(TransactionTags.FORM_CAPTION)
        waitFor(TransactionTags.FORM_CAPTION_OPEN)
        assertEquals("no lançar não há legenda salva: sem linha de tirada", 0, count(TransactionTags.FORM_CAPTION_REMOVED))
    }

    @Test
    fun `an edit with a saved legenda opens with the field and the removed line undoes`() {
        val tx = seed(post("Compra aprovada: R$ 46,00 em PADARIA ZE 0231"), LEGENDA, "Padaria do Zé")
        open()
        openTransactions()
        click(TransactionTags.ROW + tx.v)

        waitFor(TransactionTags.FORM_CAPTION)
        assertEquals("campo à vista com a legenda salva", LEGENDA, editable(TransactionTags.FORM_CAPTION))
        assertEquals("sem o link com a legenda salva", 0, count(TransactionTags.FORM_CAPTION_OPEN))
        click(TransactionTags.FORM_CAPTION_CLEAR)
        waitGone(TransactionTags.FORM_CAPTION)
        waitFor(TransactionTags.FORM_CAPTION_REMOVED)
        assertTrue("linha de tirada: ${texts(TransactionTags.FORM_CAPTION_REMOVED)}", shows(TransactionTags.FORM_CAPTION_REMOVED, "legenda tirada:"))
        assertTrue("a legenda tirada fica na linha", shows(TransactionTags.FORM_CAPTION_REMOVED, LEGENDA))
        click(TransactionTags.FORM_CAPTION_REMOVED + ".undo")
        waitFor(TransactionTags.FORM_CAPTION)
        assertEquals("Desfazer devolve o texto", LEGENDA, editable(TransactionTags.FORM_CAPTION))
        waitGone(TransactionTags.FORM_CAPTION_REMOVED)

        // Typing a new text also makes the line go away.
        click(TransactionTags.FORM_CAPTION_CLEAR)
        waitFor(TransactionTags.FORM_CAPTION_REMOVED)
        click(TransactionTags.FORM_CAPTION_OPEN)
        waitFor(TransactionTags.FORM_CAPTION)
        compose.onNodeWithTag(TransactionTags.FORM_CAPTION).performTextReplacement("Bolo novo")
        waitGone(TransactionTags.FORM_CAPTION_REMOVED)
    }

    @Test
    fun `saving an edit with the text blank takes the legenda off the row`() {
        val tx = seed(post("Compra aprovada: R$ 46,00 em PADARIA ZE 0231"), LEGENDA, "Padaria do Zé")
        open()
        openTransactions()
        click(TransactionTags.ROW + tx.v)
        waitFor(TransactionTags.FORM_CAPTION)
        click(TransactionTags.FORM_CAPTION_CLEAR)
        waitFor(TransactionTags.FORM_CAPTION_REMOVED)
        click(TransactionTags.FORM_SUBMIT)

        waitFor(TransactionTags.ROW + tx.v)
        assertTrue("volta ao nome: ${texts(TransactionTags.ROW + tx.v)}", shows(TransactionTags.ROW + tx.v, "Padaria do Zé"))
        assertTrue("sem legenda na linha", !shows(TransactionTags.ROW + tx.v, LEGENDA))
    }

    @Test
    fun `the counter shows from seventy and the field stops at eighty`() {
        open()
        openTransactions()
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_CAPTION_OPEN)
        click(TransactionTags.FORM_CAPTION_OPEN)
        waitFor(TransactionTags.FORM_CAPTION)

        compose.onNodeWithTag(TransactionTags.FORM_CAPTION).performTextReplacement("x".repeat(69))
        assertEquals("sem contador até 69", 0, count(TransactionTags.FORM_CAPTION_COUNT))
        compose.onNodeWithTag(TransactionTags.FORM_CAPTION).performTextReplacement("x".repeat(70))
        waitFor(TransactionTags.FORM_CAPTION_COUNT)
        assertTrue("contador: ${texts(TransactionTags.FORM_CAPTION_COUNT)}", shows(TransactionTags.FORM_CAPTION_COUNT, "70/80"))
        compose.onNodeWithTag(TransactionTags.FORM_CAPTION).performTextReplacement("x".repeat(85))
        assertEquals("corta em 80", 80, editable(TransactionTags.FORM_CAPTION).length)
        assertTrue(shows(TransactionTags.FORM_CAPTION_COUNT, "80/80"))
        // D55: the cut never leaves a lone high surrogate — "🎉" is 2 units, so the 79 x stay alone.
        compose.onNodeWithTag(TransactionTags.FORM_CAPTION).performTextReplacement("x".repeat(79) + "🎉")
        assertEquals("x".repeat(79), editable(TransactionTags.FORM_CAPTION))
    }

    @Test
    @Config(shadows = [ActiveKeyboard::class])
    fun `a composing word is cut at eighty with the counter`() {
        open()
        openTransactions()
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_CAPTION_OPEN)
        click(TransactionTags.FORM_CAPTION_OPEN)
        waitFor(TransactionTags.FORM_CAPTION)

        compose.typeComposing(TransactionTags.FORM_CAPTION, KEYS)
        assertEquals("o campo corta a palavra composta", KEYS.take(80), editable(TransactionTags.FORM_CAPTION))
        assertTrue(shows(TransactionTags.FORM_CAPTION_COUNT, "80/80"))
    }

    // ---- The rows (spec §7.4, §7.1, §7.3) ----

    @Test
    fun `a row shows the legenda as its name and the merchant as its credit`() {
        val withAlias = seed(post("Compra aprovada: R$ 46,00 em PADARIA ZE 0231", at(7, 42, OCT_5)), LEGENDA, "Padaria do Zé")
        val bankOnly = seed(post("Compra aprovada: R$ 59,90 em LOJA ESTACAO", at(8, 0, OCT_5)), "Feira de outubro")
        val noCaption = seed(post("Compra aprovada: R$ 12,00 em PADARIA ZE 0231", at(9, 0, OCT_5)), "", "Padaria do Zé")
        open()
        openTransactions()

        waitFor(TransactionTags.ROW + withAlias.v)
        assertTrue("legenda é o nome: ${texts(TransactionTags.ROW + withAlias.v)}", shows(TransactionTags.ROW + withAlias.v, LEGENDA))
        assertTrue("crédito do apelido", shows(TransactionTags.ROW + withAlias.v, "em Padaria do Zé"))
        waitFor(TransactionTags.ROW + bankOnly.v)
        assertTrue(shows(TransactionTags.ROW + bankOnly.v, "Feira de outubro"))
        assertTrue("crédito do texto do banco", shows(TransactionTags.ROW + bankOnly.v, "em LOJA ESTACAO"))
        waitFor(TransactionTags.ROW + noCaption.v)
        assertTrue(shows(TransactionTags.ROW + noCaption.v, "Padaria do Zé"))
        assertTrue("sem legenda, tudo como hoje: sem crédito", !shows(TransactionTags.ROW + noCaption.v, "em Padaria do Zé"))
    }

    @Test
    fun `a budget detail line draws the legenda like the row`() {
        val withCaption = seed(post("Compra aprovada: R$ 46,00 em PADARIA ZE 0231", at(7, 42, OCT_5)), LEGENDA, "Padaria do Zé")
        val noCaption = seed(post("Compra aprovada: R$ 12,00 em LOJA ESTACAO", at(8, 0, OCT_5)))
        open()
        click(BudgetTags.ROW + lazer.v)
        waitFor(BudgetTags.DETAIL_TITLE)

        waitFor(BudgetTags.LINE + withCaption.v)
        assertTrue("linha do detalhe: ${texts(BudgetTags.LINE + withCaption.v)}", shows(BudgetTags.LINE + withCaption.v, LEGENDA))
        assertTrue("crédito no detalhe", shows(BudgetTags.LINE + withCaption.v, "em Padaria do Zé"))
        waitFor(BudgetTags.LINE + noCaption.v)
        assertTrue(shows(BudgetTags.LINE + noCaption.v, "LOJA ESTACAO"))
        assertTrue("sem legenda, sem crédito", !shows(BudgetTags.LINE + noCaption.v, "em LOJA ESTACAO"))
    }

    @Test
    fun `an estornada keeps the legenda and the credit in the row`() {
        val tx = seed(post("Compra aprovada: R$ 59,90 em PADARIA ZE 0231", at(7, 0, OCT_2)), LEGENDA, "Padaria do Zé")
        val estorno = post("Estorno de compra: R$ 59,90 em PADARIA ZE 0231", at(21, 3, OCT_8))
        assertTrue(runBlocking { db.registerRefund(estorno, tx) })
        open()
        openTransactions()

        val row = TransactionTags.ROW + tx.v
        waitFor(row)
        // Tinta 3 and the strike are paint (only the value struck): checked on the emulator, the plan's step 7.
        assertTrue("estornada: ${texts(row)}", shows(row, LEGENDA))
        assertTrue("o crédito continua", shows(row, "em Padaria do Zé"))
        assertTrue("a marca estornada", shows(row, "estornada"))
        assertTrue("o valor", shows(row, money(Cents(5990))))
    }

    // ---- Registrar estorno (spec §7.3) ----

    @Test
    fun `a refund candidate shows the legenda with a credit and one without a name has none`() {
        val withName = seed(post("Compra aprovada: R$ 46,00 em PADARIA ZE 0231", at(7, 42, OCT_5)), LEGENDA, "Padaria do Zé")
        val manual = runBlocking {
            db.addManual(TransactionForm(budget = lazer, tags = listOf("casa"), total = Cents(1200), installments = 1, merchant = "", date = OCT_5, time = LocalTime(14, 30), caption = "Feira de domingo")).transaction
        }
        val notice = post("Compra aprovada: R$ 30,00 em BANCA CENTRAL", at(9, 0, OCT_5))
        val estorno = post("Estorno de compra: R$ 46,00 em PADARIA ZE 0231", at(21, 3))
        open()
        openPauta()
        click(InboxTags.ACCEPT + estorno.v)

        waitFor(InboxTags.CANDIDATE_TX + withName.v)
        assertTrue("candidato com legenda: ${texts(InboxTags.CANDIDATE_TX + withName.v)}", shows(InboxTags.CANDIDATE_TX + withName.v, LEGENDA))
        assertTrue("crédito do candidato", shows(InboxTags.CANDIDATE_TX + withName.v, "em Padaria do Zé"))
        waitFor(InboxTags.CANDIDATE_TX + manual.v)
        assertTrue(shows(InboxTags.CANDIDATE_TX + manual.v, "Feira de domingo"))
        assertTrue("manual sem estabelecimento: sem linha de crédito", !shows(InboxTags.CANDIDATE_TX + manual.v, "em "))
        waitFor(InboxTags.CANDIDATE_NOTICE + notice.v)
        assertTrue(shows(InboxTags.CANDIDATE_NOTICE + notice.v, "BANCA CENTRAL"))
        assertTrue("recado não tem legenda", !shows(InboxTags.CANDIDATE_NOTICE + notice.v, LEGENDA))
    }

    @Test
    fun `the search finds by legenda alias bank text and value ignoring case and accents`() {
        val captioned = seed(post("Compra aprovada: R$ 46,00 em PADARIA ZE 0231", at(7, 42, OCT_5)), LEGENDA, "Café da Ana")
        val plain = seed(post("Compra aprovada: R$ 59,90 em LOJA ESTACAO", at(8, 0, OCT_5)))
        val estorno = post("Estorno de compra: R$ 10,00 em BANCA CENTRAL", at(21, 3))
        open()
        openPauta()
        click(InboxTags.ACCEPT + estorno.v)
        waitFor(InboxTags.CANDIDATE_TX + captioned.v)
        waitFor(InboxTags.CANDIDATE_TX + plain.v)
        assertTrue("placeholder: ${texts(InboxTags.REFUND_SEARCH)}", shows(InboxTags.REFUND_SEARCH, "nome, legenda ou valor"))

        compose.onNodeWithTag(InboxTags.REFUND_SEARCH).performTextReplacement("aniversário")
        searchShows(InboxTags.CANDIDATE_TX + captioned.v, InboxTags.CANDIDATE_TX + plain.v)
        compose.onNodeWithTag(InboxTags.REFUND_SEARCH).performTextReplacement("café da ana")
        searchShows(InboxTags.CANDIDATE_TX + captioned.v, InboxTags.CANDIDATE_TX + plain.v)
        compose.onNodeWithTag(InboxTags.REFUND_SEARCH).performTextReplacement("0231")
        searchShows(InboxTags.CANDIDATE_TX + captioned.v, InboxTags.CANDIDATE_TX + plain.v)
        compose.onNodeWithTag(InboxTags.REFUND_SEARCH).performTextReplacement("estação")
        searchShows(InboxTags.CANDIDATE_TX + plain.v, InboxTags.CANDIDATE_TX + captioned.v)
        compose.onNodeWithTag(InboxTags.REFUND_SEARCH).performTextReplacement("59,9")
        searchShows(InboxTags.CANDIDATE_TX + plain.v, InboxTags.CANDIDATE_TX + captioned.v)
        compose.onNodeWithTag(InboxTags.REFUND_SEARCH).performTextReplacement("zzz")
        waitFor(InboxTags.REFUND_EMPTY)
        assertTrue(shows(InboxTags.REFUND_EMPTY, "Nada encontrado."))
    }

    // ---- Where the legenda does not show (spec §2.12) ----

    @Test
    fun `the legenda stays out of snacks the pauta card and the delete dialog`() {
        val id = post("Compra aprovada: R$ 46,00 em PADARIA ZE 0231")
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        waitFor(TransactionTags.FORM_CAPTION_OPEN)
        click(TransactionTags.FORM_CAPTION_OPEN)
        compose.onNodeWithTag(TransactionTags.FORM_CAPTION).performTextReplacement(LEGENDA)
        chooseBudget(lazer)
        addTag("casa")
        click(TransactionTags.FORM_SUBMIT)

        waitFor(SnackTags.TEXT)
        assertTrue("lançado: ${texts(SnackTags.TEXT)}", shows(SnackTags.TEXT, "Lançado em Lazer."))
        assertTrue("snackbar sem legenda", !shows(SnackTags.TEXT, LEGENDA))
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.size == 1 }
        val tx = runBlocking { db.transactions() }.single().id

        openTransactions()
        click(TransactionTags.ROW + tx.v)
        click(TransactionTags.FORM_DELETE)
        waitFor(TransactionTags.DELETE_DIALOG)
        assertTrue("diálogo: ${texts(TransactionTags.DELETE_DIALOG)}", shows(TransactionTags.DELETE_DIALOG, "O recado volta à Pauta."))
        assertTrue("diálogo sem legenda", !shows(TransactionTags.DELETE_DIALOG, LEGENDA))
        click(TransactionTags.DELETE_DIALOG_CONFIRM)
        // Back on Lançamentos first: the edit form has no rows, so "row gone" alone holds before the screen leaves.
        waitFor(TransactionTags.LIST)
        waitGone(TransactionTags.ROW + tx.v)

        openTab("Verbas", BudgetTags.LIST)
        openPauta()
        waitFor(InboxTags.CARD + id.v)
        assertTrue("card da Pauta sem legenda: ${texts(InboxTags.CARD + id.v)}", !shows(InboxTags.CARD + id.v, LEGENDA))
        click(InboxTags.DISMISS + id.v)
        waitFor(SnackTags.TEXT)
        assertTrue("snack de riscar sem legenda", !shows(SnackTags.TEXT, LEGENDA))
        openAjustes()
        click(SettingsTags.DISMISSED)
        waitFor(SettingsTags.DISMISSED_CARD + id.v)
        assertTrue("Riscados sem legenda", !shows(SettingsTags.DISMISSED_CARD + id.v, LEGENDA))
    }

    // ---- The preview (spec §7.3; 2026-10-20: the name is the verba's — the legenda stays out) ----

    @Test
    fun `the preview keeps the budget name and the legenda stays out of it`() {
        val id = post("Compra aprovada: R$ 46,00 em PADARIA ZE 0231")
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        waitFor(TransactionTags.FORM_CAPTION_OPEN)
        click(TransactionTags.FORM_CAPTION_OPEN)
        compose.onNodeWithTag(TransactionTags.FORM_CAPTION).performTextReplacement(LEGENDA)
        chooseBudget(lazer)
        waitFor(TransactionTags.FORM_PREVIEW)

        // Lazer, October: cota 300,00 − 46,00 = 254,00. The name is the verba's (2026-10-20 decision:
        // "Mercado" in the spec is the verba; the round wrote it the other way and was corrected here).
        val expected = "Lazer: margem ${money(Cents(30000))} → ${money(Cents(25400))}"
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_PREVIEW, expected) }
        assertTrue("prévia: ${texts(TransactionTags.FORM_PREVIEW)}", shows(TransactionTags.FORM_PREVIEW, expected))
        assertTrue("a legenda não entra na prévia", !shows(TransactionTags.FORM_PREVIEW, LEGENDA))
    }

    private companion object {
        const val BANK = "com.cartaoipe"
        val ZONE = TimeZone.of("America/Sao_Paulo")
        val OCT_2: LocalDate = LocalDate(2026, 10, 2)
        val OCT_5: LocalDate = LocalDate(2026, 10, 5)
        val OCT_8: LocalDate = LocalDate(2026, 10, 8)
        val OCT_9: LocalDate = LocalDate(2026, 10, 9)
        const val LEGENDA = "Bolo do aniversário da Ana"
        const val KEYS = "Bolo do aniversário da Ana com morango, chocolate e chantilly na cobertura da festa!"
    }
}
