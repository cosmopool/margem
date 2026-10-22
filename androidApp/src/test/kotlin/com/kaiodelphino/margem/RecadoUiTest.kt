package com.kaiodelphino.margem

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
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
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.DateTimeTags
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
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

/**
 * The recado cycle end to end (D23, D35): Pauta → Lançar → Lançamentos, estornos, the manual form and
 * the date dialog, on a real Db (AndroidSQLiteDriver, D28), today fixed on Friday, Oct 9 2026 (book §10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecadoUiTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: Db
    private var lazer = BudgetId(0)

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
        runBlocking {
            db.addSource(BANK, "Cartão Ipê", 0)
            lazer = db.createBudget("Lazer", Cents(46500), OCT_9)
        }
    }

    @After
    fun tearDown() = db.close()

    private fun open() = compose.setContent {
        MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { OCT_9 }) { App(db, null) {} } }
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    /** Waits for [tag], scrolling the lazy lists to it: on Robolectric's small screen, items below the fold aren't composed. */
    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        for (list in listOf(InboxTags.LIST, InboxTags.REFUND_LIST, TransactionTags.LIST)) {
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
        LocalDateTime(day.year, day.month, day.day, hour, minute).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    /** Captures a notice from the bank and returns its id. */
    private fun post(text: String, postedAt: Long = at(7, 42)): NoticeId = runBlocking {
        assertTrue(capture(db, RawNotice(packageName = BANK, appLabel = "Cartão Ipê", key = "k$postedAt", postedAt = postedAt, title = "Cartão Ipê", text = text)))
        db.pending().first { it.raw.postedAt == postedAt }.id
    }

    private fun openPauta() = click(BudgetTags.PAUTA_STRIP)

    private val suggested = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "sugerido")

    @Test
    fun `a notice is launched with a budget a tag and installments`() {
        val id = post("Compra aprovada: R$ 1.200,00 em MOVELARIA REAL")
        open()
        openPauta()
        waitFor(InboxTags.CARD + id.v)
        assertTrue(count(InboxTags.NEW + id.v) == 1)
        click(InboxTags.ACCEPT + id.v)

        waitFor(TransactionTags.FORM_SUBMIT)
        compose.onNodeWithTag(TransactionTags.FORM_SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(TransactionTags.FORM_MISSING).assertTextContains("Falta a verba e um marcador.")
        click(TransactionTags.BUDGET + lazer.v)
        compose.onNodeWithTag(TransactionTags.FORM_MISSING).assertTextContains("Falta um marcador.")
        click(TransactionTags.TAG_NEW)
        compose.onNodeWithTag(TransactionTags.TAG_INPUT).performTextReplacement(" Móveis ")
        click(TransactionTags.TAG_ADD)
        waitFor(TransactionTags.TAG + "móveis")
        compose.onNodeWithTag(TransactionTags.TAG + "móveis").assertIsSelected()
        waitGone(TransactionTags.FORM_MISSING)
        repeat(9) { click(TransactionTags.INSTALLMENTS_PLUS) }
        compose.onNodeWithTag(TransactionTags.INSTALLMENTS_PREVIEW).assertTextContains("10 × ${money(Cents(12000))}")
        // Lazer, October: 465,00 − the first installment 120,00 = 345,00.
        waitFor(TransactionTags.FORM_PREVIEW)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(TransactionTags.FORM_PREVIEW).assertTextContains("Lazer: margem ${money(Cents(46500))} → ${money(Cents(34500))}") }.isSuccess }
        compose.onNodeWithTag(TransactionTags.FORM_SUBMIT).assertIsEnabled().assertTextContains("Lançar ${money(Cents(120000))}")
        click(TransactionTags.FORM_SUBMIT)

        waitFor(SnackTags.TEXT)
        compose.onNodeWithTag(SnackTags.TEXT).assertTextContains("Lançado em Lazer. Margem: ${money(Cents(34500))}.")
        waitFor(InboxTags.EMPTY)
        val row = runBlocking { db.transactions(OCT_9) }.single()
        assertEquals(listOf("móveis") to 10, row.tags to row.installments)
        assertEquals(Cents(34500), runBlocking { db.budgetDetail(lazer, OCT, OCT_9) }.month.remaining)

        openTransactions()
        waitFor(TransactionTags.ROW + row.id.v)
        compose.onNodeWithTag(TransactionTags.ROW + row.id.v).assertTextContains("Parcela 1 de 10", substring = true)
            .assertTextContains("Lazer · móveis", substring = true)
        compose.onNodeWithTag(TransactionTags.DAY + "2026-10-09").assertTextContains("Hoje · sexta, 9 de outubro", substring = true, ignoreCase = true)
    }

    /** The tab bar has no tags (block 2): Lançamentos is found by its label. */
    private fun openTransactions() {
        compose.onNode(hasText("Lançamentos") and hasClickAction()).performClick()
        waitFor(TransactionTags.NEW)
    }

    @Test
    fun `the merchant's last budget and tags come suggested and count`() {
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
        compose.onNodeWithTag(TransactionTags.BUDGET + lazer.v).assertIsSelected()
        compose.onNodeWithTag(TransactionTags.TAG + "padaria").assertIsSelected().assert(suggested)
        click(TransactionTags.TAG + "café") // confirm
        compose.onNodeWithTag(TransactionTags.TAG + "café").assertIsSelected().assert(suggested.not())
        click(TransactionTags.TAG + "café") // and take it off
        compose.onNodeWithTag(TransactionTags.TAG + "café").assertIsNotSelected()
        click(TransactionTags.FORM_SUBMIT)
        waitFor(InboxTags.EMPTY)
        assertEquals(listOf("padaria"), runBlocking { db.transactions() }.first { it.notice == second }.tags)
    }

    @Test
    fun `an emended value shows the original and the alias names the merchant`() {
        val id = post("Compra aprovada: R$ 92,00 em BAR E REST PONTO CERTO")
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        waitFor(TransactionTags.FORM_TOTAL)
        assertEquals(0, count(TransactionTags.FORM_TOTAL_EMENDA))
        compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performTextReplacement("4600")
        waitFor(TransactionTags.FORM_TOTAL_EMENDA)
        compose.onNodeWithTag(TransactionTags.FORM_TOTAL_EMENDA).assertTextContains(money(Cents(9200)), substring = true)
        click(TransactionTags.FORM_TOTAL_EMENDA + ".undo")
        waitGone(TransactionTags.FORM_TOTAL_EMENDA)
        compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performTextReplacement("4600")
        compose.onNodeWithTag(TransactionTags.FORM_ALIAS).performTextReplacement("Bar do Ponto")
        compose.onNodeWithTag(TransactionTags.FORM_BANK).assertTextContains("No banco: BAR E REST PONTO CERTO")
        click(TransactionTags.BUDGET + lazer.v)
        click(TransactionTags.TAG_NEW)
        compose.onNodeWithTag(TransactionTags.TAG_INPUT).performTextReplacement("bar")
        click(TransactionTags.TAG_ADD)
        // Book 10.3: Lazer margem 465,00 → 419,00.
        click(TransactionTags.FORM_SUBMIT)
        waitFor(InboxTags.EMPTY)

        val row = runBlocking { db.transactions() }.single()
        assertEquals(Triple("Bar do Ponto", Cents(4600), true), Triple(row.name, row.total, row.corrected))
        post("Compra aprovada: R$ 30,00 em BAR E REST PONTO CERTO", at(9, 0))
        compose.waitUntil(5_000) { runBlocking { db.pending() }.size == 1 }
        val next = runBlocking { db.pending() }.single()
        waitFor(InboxTags.CARD + next.id.v)
        compose.onNodeWithTag(InboxTags.CARD + next.id.v).assertTextContains("Bar do Ponto", substring = true)
    }

    @Test
    fun `an estorno of a transaction can be undone from the snack and from the row`() {
        val purchase = post("Compra aprovada: R$ 59,90 em LOJA ESTACAO", at(7, 0, LocalDate(2026, 10, 2)))
        val tx = runBlocking {
            val p = db.notice(purchase).parsed
            db.accept(purchase, TransactionForm(lazer, listOf("casa"), p.cents, 1, p.merchant, p.date, p.time))
        }
        val estorno = post("Estorno de compra: R$ 59,90 em LOJA ESTACAO", at(21, 3, LocalDate(2026, 10, 8)))
        assertTrue(runBlocking { db.notice(estorno) }.parsed.isRefund)
        open()
        openPauta()
        waitFor(InboxTags.ACCEPT + estorno.v)
        compose.onNodeWithTag(InboxTags.ACCEPT + estorno.v).assertTextContains("Registrar estorno")
        click(InboxTags.ACCEPT + estorno.v)

        waitFor(InboxTags.REFUND_LIKELY)
        waitFor(InboxTags.CANDIDATE_TX + tx.v)
        compose.onNodeWithTag(InboxTags.CANDIDATE_TX + tx.v).assertIsSelected()
        // Lazer, October: 465,00 − 59,90 = 405,10 → back to 465,00.
        waitFor(InboxTags.REFUND_PREVIEW)
        compose.onNodeWithTag(InboxTags.REFUND_PREVIEW).assertTextContains("Lazer: margem ${money(Cents(40510))} → ${money(Cents(46500))}")
        click(InboxTags.REFUND_SUBMIT)
        waitFor(SnackTags.ACTION)
        compose.onNodeWithTag(SnackTags.TEXT).assertTextContains("Estorno registrado: a compra de 2 out não conta mais.")
        assertTrue(runBlocking { db.transactions() }.single().refunded)
        click(SnackTags.ACTION)
        compose.waitUntil(5_000) { !runBlocking { db.transactions() }.single().refunded }

        assertTrue(runBlocking { db.registerRefund(estorno, tx) })
        openTransactions()
        waitFor(TransactionTags.UNDO_REFUND + tx.v)
        compose.onNodeWithTag(TransactionTags.ROW + tx.v).assertTextContains("estornada", substring = true)
        click(TransactionTags.UNDO_REFUND + tx.v)
        waitGone(TransactionTags.UNDO_REFUND + tx.v)
        assertEquals(listOf(estorno), runBlocking { db.pending() }.map { it.id })
    }

    @Test
    fun `an estorno of a pending notice takes both out and a misread one becomes a purchase`() {
        val purchase = post("Compra aprovada: R$ 86,00 em MERCADINHO", at(7, 15))
        val estorno = post("Estorno de compra: R$ 86,00 em MERCADINHO", at(8, 0))
        val wrong = post("Estorno de compra: R$ 10,00 em BANCA CENTRAL", at(9, 0))
        open()
        openPauta()
        click(InboxTags.ACCEPT + estorno.v)
        waitFor(InboxTags.CANDIDATE_NOTICE + purchase.v)
        compose.onNodeWithTag(InboxTags.CANDIDATE_NOTICE + purchase.v).assertIsSelected().assertTextContains("hoje, 07:15 · recado na pauta", substring = true)
        assertEquals(0, count(InboxTags.REFUND_PREVIEW))
        compose.onNodeWithTag(InboxTags.REFUND_SEARCH).performTextReplacement("zzz")
        waitFor(InboxTags.REFUND_EMPTY)
        compose.onNodeWithTag(InboxTags.REFUND_EMPTY).assertTextContains("Nada encontrado.")
        compose.onNodeWithTag(InboxTags.REFUND_SEARCH).performTextReplacement("86,0")
        waitFor(InboxTags.CANDIDATE_NOTICE + purchase.v)
        click(InboxTags.REFUND_SUBMIT)
        waitFor(InboxTags.CARD + wrong.v)
        compose.waitUntil(5_000) { runBlocking { db.pending() }.map { it.id } == listOf(wrong) }

        click(InboxTags.ACCEPT + wrong.v)
        click(InboxTags.REFUND_AS_PURCHASE)
        waitFor(TransactionTags.FORM_TOTAL)
        compose.onNodeWithTag(TransactionTags.FORM_TITLE).assertTextContains("Lançar recado")
    }

    @Test
    fun `a manual transaction gets its date from the dialog and deleting one asks first`() {
        val notice = post("Compra aprovada: R$ 40,50 em CANTINA NONA")
        val fromNotice = runBlocking {
            val p = db.notice(notice).parsed
            db.accept(notice, TransactionForm(lazer, listOf("almoço"), p.cents, 1, p.merchant, p.date, p.time))
        }
        open()
        openTransactions()
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_NAME)
        compose.onNodeWithTag(TransactionTags.FORM_NAME).performTextReplacement("Feira")
        compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performTextReplacement("1200")
        click(TransactionTags.BUDGET + lazer.v)
        click(TransactionTags.TAG + "almoço")
        click(TransactionTags.FORM_DATE)
        waitFor(DateTimeTags.DIALOG)
        // Spec §7.3 (previsto, P4): only the manual form takes days ahead; they make a previsto.
        compose.onNodeWithTag(DateTimeTags.NEXT).assertIsEnabled()
        compose.onNodeWithTag(DateTimeTags.DAY + "2026-10-10").assertIsEnabled()
        compose.onNodeWithTag(DateTimeTags.WEEKDAYS).assertTextContains("seg")
        click(DateTimeTags.PREV)
        compose.onNodeWithTag(DateTimeTags.MONTH).assertTextContains("setembro de 2026")
        click(DateTimeTags.DAY + "2026-09-12")
        compose.onNodeWithTag(DateTimeTags.TITLE).assertTextContains("sábado, 12 de setembro")
        compose.onNodeWithTag(DateTimeTags.TIME).performTextReplacement("2510")
        click(DateTimeTags.DONE)
        waitFor(DateTimeTags.TIME_ERROR)
        compose.onNodeWithTag(DateTimeTags.TIME).performTextReplacement("1430")
        click(DateTimeTags.DONE)
        waitGone(DateTimeTags.DIALOG)
        compose.onNodeWithTag(TransactionTags.FORM_DATE).assertTextContains("sábado, 12 de setembro · 14:30", substring = true)
        click(TransactionTags.FORM_SUBMIT)
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.size == 2 }
        val manual = runBlocking { db.transactions() }.first { it.notice.v == 0L }
        assertEquals(Triple("Feira", LocalDate(2026, 9, 12), LocalTime(14, 30)), Triple(manual.merchant, manual.date, manual.time))

        click(TransactionTags.ROW + fromNotice.v)
        click(TransactionTags.FORM_DELETE)
        waitFor(TransactionTags.DELETE_DIALOG)
        compose.onNodeWithTag(TransactionTags.DELETE_DIALOG).assertTextContains("O recado volta à Pauta.", substring = true)
        click(TransactionTags.DELETE_DIALOG_CONFIRM)
        waitGone(TransactionTags.ROW + fromNotice.v)
        assertEquals(listOf(notice), runBlocking { db.pending() }.map { it.id })
    }

    @Test
    @Config(shadows = [ActiveKeyboard::class])
    fun `a keyboard that composes the whole number loses no digit in the value and the hour`() {
        val notice = post("Compra aprovada: R$ 40,50 em CANTINA NONA") // brings the tag "almoço"
        runBlocking { db.notice(notice).parsed.let { db.accept(notice, TransactionForm(lazer, listOf("almoço"), it.cents, 1, it.merchant, it.date, it.time)) } }
        open()
        openTransactions()
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_NAME)
        compose.onNodeWithTag(TransactionTags.FORM_NAME).performTextReplacement("Feira")
        compose.typeComposing(TransactionTags.FORM_TOTAL, "123456")
        click(TransactionTags.BUDGET + lazer.v)
        click(TransactionTags.TAG + "almoço")
        click(TransactionTags.FORM_DATE)
        waitFor(DateTimeTags.DIALOG)
        compose.onNodeWithTag(DateTimeTags.TIME).performTextReplacement("")
        compose.typeComposing(DateTimeTags.TIME, "1430")
        click(DateTimeTags.DONE)
        waitGone(DateTimeTags.DIALOG)
        click(TransactionTags.FORM_SUBMIT)
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.size == 2 }
        val manual = runBlocking { db.transactions() }.first { it.notice.v == 0L }
        assertEquals(Cents(123456) to LocalTime(14, 30), manual.total to manual.time)
    }

    private companion object {
        const val BANK = "com.cartaoipe"
        val OCT_9 = LocalDate(2026, 10, 9)
        val OCT = YearMonth(2026, 10)
    }
}
