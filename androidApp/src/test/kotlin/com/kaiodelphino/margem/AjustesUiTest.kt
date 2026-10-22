package com.kaiodelphino.margem

import android.app.Application
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.Screen
import com.kaiodelphino.margem.ui.SettingsTags
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackTags
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
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
 * Ajustes end to end (spec §7.6, D35): pickers, Riscados, Marcadores, the start days with their warning,
 * Backup and Importar do cobudget, on a real Db (AndroidSQLiteDriver, D28), today fixed on Wednesday,
 * Oct 14 2026. The file picker is answered by [registry] with [answer].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AjustesUiTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: Db

    /** What the picker returns next; null = cancelled. */
    private var answer: Uri? = null
    /** The input of the last picker launched (a file name to create, or the MIME types to open). */
    private var asked: Any? = null
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            asked = input
            dispatchResult(requestCode, answer)
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @Before
    fun setUp() {
        db = Db(AndroidSQLiteDriver(), tmp.newFile("margem.db").also { it.delete() }.path)
        runBlocking { db.addSource(BANK, "Cartão Ipê", 0) }
    }

    @After
    fun tearDown() = db.close()

    private fun open() = compose.setContent {
        MargemTheme(dark = false) {
            CompositionLocalProvider(LocalToday provides { OCT_14 }, LocalActivityResultRegistryOwner provides owner) { App(db, Screen.Settings) {} }
        }
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        for (list in listOf(SettingsTags.DISMISSED_LIST, SettingsTags.TAGS_LIST)) {
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
    private fun waitText(tag: String, text: String) = compose.waitUntil(5_000) {
        runCatching { compose.onNodeWithTag(tag).assertTextContains(text) }.isSuccess
    }

    private fun post(text: String, at: Long): NoticeId = runBlocking {
        assertTrue(capture(db, RawNotice(packageName = BANK, appLabel = "Cartão Ipê", key = "k$at", postedAt = at, title = "Cartão Ipê", text = text)))
        db.pending().first { it.raw.postedAt == at }.id
    }

    @Test
    fun `theme and widget order are saved from Ajustes`() {
        open()
        waitFor(SettingsTags.START_DAYS)
        compose.onNodeWithTag(SettingsTags.START_DAYS).assertTextContains("dia 1 · segunda", substring = true)
        compose.onNodeWithTag(SettingsTags.THEME + "system").assertIsSelected()
        click(SettingsTags.THEME + "dark")
        click(SettingsTags.WIDGET_ORDER + "az")
        compose.waitUntil(5_000) { runBlocking { db.settings() }.let { it.theme == Theme.DARK && it.widgetOrder == BudgetOrder.AZ } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(SettingsTags.WIDGET_ORDER + "az").assertIsSelected() }.isSuccess }
    }

    @Test
    fun `a dismissed notice comes back to the Pauta from Riscados`() {
        val id = post("Compra de R$ 12,00 aprovada em PADARIA", 1_791_900_000_000)
        runBlocking { assertTrue(db.dismiss(id)) }
        open()
        click(SettingsTags.DISMISSED)
        waitFor(SettingsTags.DISMISSED_CARD + id.v)
        click(SettingsTags.RESTORE + id.v)
        waitGone(SettingsTags.DISMISSED_CARD + id.v)
        waitFor(SettingsTags.DISMISSED_EMPTY)
        assertEquals(listOf(id), runBlocking { db.pending() }.map { it.id })
    }

    @Test
    fun `renaming a tag to another's name joins them and deleting says what goes`() {
        val (mercado, feira) = runBlocking {
            val b = db.createBudget("Casa", Cents(50000), OCT_14)
            db.addManual(TransactionForm(b, listOf("mercado"), Cents(1000), 1, "A", OCT_14, NO_TIME)).transaction
            db.addManual(TransactionForm(b, listOf("feira"), Cents(2000), 1, "B", OCT_14, NO_TIME)).transaction
            val tags = db.tags()
            tags.first { it.name == "mercado" }.id to tags.first { it.name == "feira" }.id
        }
        open()
        click(SettingsTags.TAGS)
        click(SettingsTags.TAG_ROW + feira.v)
        compose.onNodeWithTag(SettingsTags.TAG_SAVE).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTags.TAG_NAME).performTextReplacement(" Mercado ")
        compose.onNodeWithTag(SettingsTags.TAG_SAVE).assertTextContains("Juntar com mercado").assertIsEnabled()
        click(SettingsTags.TAG_SAVE)
        waitGone(SettingsTags.TAG_ROW + feira.v)
        assertEquals(listOf(TagRow(mercado, "mercado", 2, 2)), runBlocking { db.tags() })

        click(SettingsTags.TAG_ROW + mercado.v)
        click(SettingsTags.TAG_DELETE)
        waitFor(SettingsTags.TAG_DELETE_DIALOG)
        compose.onNodeWithTag(SettingsTags.TAG_DELETE_DIALOG)
            .assertTextContains("2 lançamentos ficam sem marcador e são apagados; os recados deles voltam à Pauta.", substring = true)
        click(SettingsTags.TAG_DELETE_DIALOG + ".confirm")
        waitFor(SettingsTags.TAGS_EMPTY)
        assertTrue(runBlocking { db.transactions() }.isEmpty())
    }

    @Test
    fun `changing the start days warns only when step edits go`() {
        open()
        click(SettingsTags.START_DAYS)
        waitFor(SettingsTags.START_SAVE)
        compose.onNodeWithTag(SettingsTags.START_SAVE).assertIsNotEnabled()
        click(SettingsTags.START_WEEK + "sunday")
        click(SettingsTags.START_SAVE)
        // No edits: saved at once, back to Ajustes.
        waitText(SettingsTags.START_DAYS, "dia 1 · domingo")
        assertEquals(DayOfWeek.SUNDAY, runBlocking { db.settings() }.weekStart)

        runBlocking {
            val b = db.createBudget("Casa", Cents(60000), OCT_14)
            db.ensureMonths(YearMonth(2026, 10))
            // Sunday weeks in Oct 2026: Oct 1–3, 4–10, 11–17, …
            assertTrue(db.setStep(b, YearMonth(2026, 10), LocalDate(2026, 10, 11), Cents(10000)))
        }
        click(SettingsTags.START_DAYS)
        click(SettingsTags.START_DAY + ".plus")
        click(SettingsTags.START_SAVE)
        waitFor(SettingsTags.START_DIALOG)
        compose.onNodeWithTag(SettingsTags.START_DIALOG).assertTextContains("Mudar o início do mês?", substring = true)
        compose.onNodeWithTag(SettingsTags.START_DIALOG).assertTextContains("As edições de passo de outubro em diante", substring = true)
        click(SettingsTags.START_DIALOG + ".cancel")
        compose.onNodeWithTag(SettingsTags.START_DAY).assertTextContains("2")
        click(SettingsTags.START_SAVE)
        click(SettingsTags.START_DIALOG + ".confirm")
        waitText(SettingsTags.START_DAYS, "dia 2 · domingo")
    }

    @Test
    fun `notices of a cobudget backup land in the Pauta and Riscados and a second import adds nothing`() {
        val file = tmp.newFile("cobudget.db").also { it.delete() }
        val old = AndroidSQLiteDriver().open(file.path)
        old.execSQL(
            "CREATE TABLE `captured_notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, " +
                "`appLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, `postedAt` INTEGER NOT NULL, `notificationWhen` INTEGER NOT NULL DEFAULT 0, " +
                "`title` TEXT, `text` TEXT, `bigText` TEXT, `subText` TEXT, `textLines` TEXT, `category` TEXT, `channelId` TEXT, " +
                "`extrasJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `dismissed` INTEGER NOT NULL DEFAULT 0)",
        )
        for ((i, dismissed) in listOf(0, 0, 1).withIndex()) old.execSQL(
            "INSERT INTO captured_notifications(packageName, appLabel, notificationKey, postedAt, title, text, extrasJson, contentHash, dismissed) " +
                "VALUES ('com.old', 'Velho', 'k$i', ${1_791_900_000_000 + i}, 'Velho', 'Recado $i', '{}', '', $dismissed)",
        )
        old.execSQL("PRAGMA user_version = 7")
        old.close()

        open()
        click(SettingsTags.COBUDGET)
        answer = Uri.fromFile(file)
        click(SettingsTags.COBUDGET_PICK)
        waitText(SettingsTags.COBUDGET_RESULT, "3 recados importados: 2 na Pauta, 1 em Riscados.")
        assertEquals(2, runBlocking { db.pendingCount() })
        click(SettingsTags.COBUDGET_PICK)
        waitText(SettingsTags.COBUDGET_RESULT, "Nenhum recado importado. 3 não entraram (repetidos ou sem texto).")

        answer = Uri.fromFile(tmp.newFile("junk.db").also { it.writeText("not sqlite") })
        click(SettingsTags.COBUDGET_PICK)
        waitText(SettingsTags.COBUDGET_RESULT, "Este arquivo não é um backup do cobudget.")
    }

    @Test
    fun `an exported backup restores after the dialog and junk is refused`() {
        val budget = runBlocking { db.createBudget("Casa", Cents(50000), OCT_14) }
        val out = tmp.newFile("export.db")
        open()
        click(SettingsTags.BACKUP)
        answer = Uri.fromFile(out)
        click(SettingsTags.BACKUP_EXPORT)
        waitText(SnackTags.TEXT, "Backup exportado.")
        assertEquals("margem-2026-10-14.db", asked)
        runBlocking { db.deleteBudget(budget) }

        click(SettingsTags.BACKUP_IMPORT)
        waitFor(SettingsTags.BACKUP_DIALOG)
        click(SettingsTags.BACKUP_DIALOG + ".cancel")
        waitGone(SettingsTags.BACKUP_DIALOG)
        assertTrue(runBlocking { db.budgetsOverview(YearMonth(2026, 10), OCT_14, BudgetOrder.AZ) }.isEmpty())

        click(SettingsTags.BACKUP_IMPORT)
        click(SettingsTags.BACKUP_DIALOG + ".confirm")
        waitText(SnackTags.TEXT, "Backup restaurado.")
        assertEquals(listOf("Casa"), runBlocking { db.ensureMonths(YearMonth(2026, 10)); db.budgetsOverview(YearMonth(2026, 10), OCT_14, BudgetOrder.AZ) }.map { it.name })

        answer = Uri.fromFile(File(tmp.root, "junk.db").also { it.writeText("not sqlite") })
        click(SettingsTags.BACKUP_IMPORT)
        click(SettingsTags.BACKUP_DIALOG + ".confirm")
        waitText(SnackTags.TEXT, "Este arquivo não é um backup do Margem.")
    }

    private companion object {
        const val BANK = "com.bank"
        val OCT_14 = LocalDate(2026, 10, 14)
    }
}
