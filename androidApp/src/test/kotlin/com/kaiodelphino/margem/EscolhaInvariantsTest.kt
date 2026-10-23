package com.kaiodelphino.margem

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InboxTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.ui.marginText
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
import kotlin.random.Random

/**
 * Invariants of the lean form (spec §2.5, §7.3 "Verba" and "Marcadores"; D61, D66): properties that hold in every
 * state of the form, checked after each step of five scenarios — Lançar with a suggestion, Novo lançamento, Editar,
 * Lançar linked to a previsto and Editar previsto — and of a seeded walk over the tag search. Written blind from the
 * spec and the contract ([TransactionTags] KDoc), before the implementation.
 *
 * What every check asserts (`checkForm`, `checkSheetAndPick`, `checkTagSearch`):
 * - Margin: FORM_BUDGET reads "margem " + marginText(R), the chosen BUDGET row reads marginText(R) and FORM_PREVIEW,
 *   when shown, reads "<verba>: margem marginText(R) → …", where R = Db.budgetsOverview(Db.monthOf(form date), today,
 *   MANUAL) remaining of the chosen budget (KDoc of the "Verba" section: the month of the form's date, the same as
 *   the preview's). A budget without a limit row in that month shows no margin. Changing the date to another month
 *   moves every margin to that month.
 * - Exactly one BUDGET row `selected` when a budget is chosen, none otherwise; with an empty search the rows are all
 *   the budgets in manual order (§7.3 "na ordem da aba Verbas"); with a search, the ones whose name contains it
 *   under `fold`.
 * - Every TAG chip is `selected`; the chips are the expected set; once saved, the transaction's (or previsto's) tags
 *   are that set, order-insensitive.
 * - No tag is both a chip and a TAG_SHORTCUT / TAG_RESULT; every TAG_RESULT contains the trimmed text under `fold`;
 *   at most 4 results, at most 8 shortcuts.
 * - FORM_MISSING lists "a verba" iff FORM_BUDGET reads "Escolher a verba", "um marcador" iff there is no chip,
 *   "o valor" iff the total is 0, and is absent iff nothing is missing; FORM_SUBMIT is enabled iff FORM_MISSING is
 *   absent.
 * - stateDescription "sugerida" / "sugerido" only on FORM_BUDGET / the TAG chips that came from the suggestion in
 *   Lançar, never after a budget was picked in the sheet, never on a chip added in the form, never in the other forms.
 *
 * Data (today Friday, Oct 9 2026; month start day 1, so the budget month is the calendar month), made through the
 * public Db API and checked by hand in setUp:
 * - Mercado R$ 600,00, Lazer R$ 300,00, Casa R$ 1.500,00 created Sep 1; Saúde R$ 200,00 created Oct 1 (no September
 *   row). Manual order Mercado, Lazer, Casa, Saúde (neither creation order nor A–Z).
 * - Lazer: Sep 15 R$ 50,00 (Cinema Roxy); Oct 2 R$ 346,00 (Show da banda) → Sep 250,00, Oct −46,00 ("R$ 46,00 além").
 * - Mercado: Oct 3 R$ 123,45 (Feira); Oct 5 R$ 80,00 (PADARIA DO ZE, padaria + café) → Sep 600,00, Oct 396,55.
 * - Casa: five purchases of R$ 10,00 on Sep 10 that give the tags their uses → Sep 1.450,00, Oct 1.500,00.
 * - Tag uses: padaria 5, pão 4, bar 3, café 3, amigos 2, lanche 2, mercado 2, and 1 each for açaí, cafeteria,
 *   cinema, contas, farmácia, feira, show (14 tags: more than the 8 shortcuts, and "a" matches more than 4).
 *
 * Elements are found by tags only; text of a tagged node is read in the unmerged tree (the node and below).
 * Robolectric does not see paint (the dash of "sugerida"): stateDescription stands for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp")
class EscolhaInvariantsTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: Db
    private lateinit var path: String
    private lateinit var hostZone: java.util.TimeZone
    private var mercado = BudgetId(0)
    private var lazer = BudgetId(0)
    private var casa = BudgetId(0)
    private var saude = BudgetId(0)
    private var feira = TransactionId(0)

    /** Manual order (reorderBudgets in setUp): what the sheet lists with an empty search. */
    private val manual get() = listOf(mercado, lazer, casa, saude)
    private val names get() = mapOf(mercado to "Mercado", lazer to "Lazer", casa to "Casa", saude to "Saúde")

    @Before
    fun setUp() {
        hostZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"))
        path = tmp.newFile("margem-escolha-${Random.nextLong().toULong()}.db").also { it.delete() }.path
        db = Db(AndroidSQLiteDriver(), path, timeZone = ZONE)
        runBlocking {
            db.addSource(BANK, "Cartão Ipê", 0)
            lazer = db.createBudget("Lazer", Cents(30000), SEP_1)
            casa = db.createBudget("Casa", Cents(150000), SEP_1)
            mercado = db.createBudget("Mercado", Cents(60000), SEP_1)
            saude = db.createBudget("Saúde", Cents(20000), OCT_1)
            assertTrue("ordem manual", db.reorderBudgets(listOf(mercado, lazer, casa, saude)))
            // Gives September's budgets their October row (Saúde has only October's).
            db.ensureMonths(YearMonth(2026, 10))

            spend(lazer, SEP_15, 5000, "Cinema Roxy", "cinema")
            spend(lazer, OCT_2, 34600, "Show da banda", "show", "amigos")
            feira = spend(mercado, OCT_3, 12345, "Feira", "feira")
            spend(mercado, OCT_5, 8000, "PADARIA DO ZE", "padaria", "café")
            spend(casa, SEP_10, 1000, "Diversos 1", "padaria", "pão", "bar", "lanche")
            spend(casa, SEP_10, 1000, "Diversos 2", "padaria", "pão", "bar", "mercado")
            spend(casa, SEP_10, 1000, "Diversos 3", "padaria", "pão", "café", "amigos")
            spend(casa, SEP_10, 1000, "Diversos 4", "padaria", "pão", "café", "lanche", "bar")
            spend(casa, SEP_10, 1000, "Diversos 5", "cafeteria", "contas", "farmácia", "açaí", "mercado")
        }
        // The data by hand (spec §3: margem = cota − gasto do mês):
        // Oct: Mercado 600,00 − 123,45 − 80,00 = 396,55; Lazer 300,00 − 346,00 = −46,00; Casa 1.500,00; Saúde 200,00.
        // Sep: Mercado 600,00; Lazer 300,00 − 50,00 = 250,00; Casa 1.500,00 − 5 × 10,00 = 1.450,00; Saúde has no row.
        assertEquals(mapOf(mercado to 39655L, lazer to -4600L, casa to 150000L, saude to 20000L), margins(OCT_9))
        assertEquals(mapOf(mercado to 60000L, lazer to 25000L, casa to 145000L), margins(SEP_20))
        assertEquals("a visão geral em ordem manual", manual, runBlocking { db.budgetsOverview(YearMonth(2026, 10), OCT_9, BudgetOrder.MANUAL) }.map { it.id })
        val uses = runBlocking { db.tags() }.associate { it.name to it.uses }
        assertEquals(
            mapOf(
                "padaria" to 5, "pão" to 4, "bar" to 3, "café" to 3, "amigos" to 2, "lanche" to 2, "mercado" to 2, "açaí" to 1,
                "cafeteria" to 1, "cinema" to 1, "contas" to 1, "farmácia" to 1, "feira" to 1, "show" to 1,
            ),
            uses,
        )
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

    // ---- Data, through the public Db API ----

    private suspend fun spend(budget: BudgetId, date: LocalDate, cents: Long, merchant: String, vararg tags: String): TransactionId {
        val saved = db.addManual(TransactionForm(budget = budget, tags = tags.toList(), total = Cents(cents), installments = 1, merchant = merchant, date = date, time = LocalTime(12, 0)), OCT_9)
        assertTrue("lançamento $merchant", saved.transaction.v != 0L)
        return saved.transaction
    }

    /** The oracle of every margin: Db.budgetsOverview of the budget month of [date] (D66), remaining by budget. */
    private fun margins(date: LocalDate): Map<BudgetId, Long> = runBlocking {
        db.budgetsOverview(db.monthOf(date), OCT_9, BudgetOrder.MANUAL).associate { it.id to it.month.remaining.v }
    }

    private fun margin(budget: BudgetId, date: LocalDate): Cents? = margins(date)[budget]?.let { Cents(it) }

    private fun at(hour: Int, minute: Int, day: LocalDate = OCT_9) =
        LocalDateTime(day.year, day.month, day.day, hour, minute).toInstant(ZONE).toEpochMilliseconds()

    private fun post(text: String, postedAt: Long): NoticeId = runBlocking {
        assertTrue(capture(db, RawNotice(packageName = BANK, appLabel = "Cartão Ipê", key = "k$postedAt", postedAt = postedAt, title = "Cartão Ipê", text = text)))
        db.pending().first { it.raw.postedAt == postedAt }.id
    }

    /** Salvar previsto: a manual form dated after today; only the previsto is written. */
    private fun ahead(name: String, budget: BudgetId, tags: List<String>, cents: Long, date: LocalDate) {
        val saved = runBlocking {
            db.addManual(TransactionForm(budget = budget, tags = tags, total = Cents(cents), installments = 1, merchant = name, date = date, time = LocalTime(9, 0), repeats = Repeats.MONTHLY), OCT_9)
        }
        assertTrue("previsto $name", saved.planned.v != 0L && saved.transaction.v == 0L)
    }

    private fun occ(name: String): OccurrenceId = runBlocking { db.planned(OCT_9) }.first { it.name == name }.occurrence

    // ---- Finding things ----

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun hasTagPrefix(prefix: String) =
        SemanticsMatcher("testTag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    /** The names after [prefix] of every node tagged with it, top to bottom. */
    private fun named(prefix: String): List<String> =
        compose.onAllNodes(hasTagPrefix(prefix), useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { it.config[SemanticsProperties.TestTag].removePrefix(prefix) }
            .distinct()

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

    /** waitUntil with a message: [what] says the context (scenario, step, input) and what was on screen. */
    private fun await(what: () -> String, cond: () -> Boolean) {
        try {
            compose.waitUntil(5_000) { cond() }
        } catch (e: Throwable) {
            throw AssertionError(what(), e)
        }
    }

    private fun node(tag: String): SemanticsNode? =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()

    private fun merged(tag: String): SemanticsNode? = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()

    private fun subtree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { subtree(it) }

    private fun texts(tag: String): List<String> = node(tag)?.let { n ->
        subtree(n).flatMap { s ->
            s.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(s.config.getOrNull(SemanticsProperties.EditableText)?.text)
        }
    }.orEmpty()

    private fun shows(tag: String, s: String): Boolean {
        val t = texts(tag)
        return t.any { it.contains(s) } || t.joinToString("").contains(s) || t.joinToString(" ").contains(s)
    }

    private fun assertShows(ctx: String, tag: String, s: String) = assertTrue("$ctx: $tag deveria mostrar \"$s\": ${texts(tag)}", shows(tag, s))

    private fun assertNotShows(ctx: String, tag: String, s: String) = assertTrue("$ctx: $tag não deveria mostrar \"$s\": ${texts(tag)}", !shows(tag, s))

    private fun editable(tag: String): String =
        node(tag)?.let { n -> subtree(n).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text } } ?: ""

    private fun selected(tag: String): Boolean = merged(tag)?.config?.getOrNull(SemanticsProperties.Selected) == true

    private fun enabled(tag: String): Boolean = merged(tag)?.config?.getOrNull(SemanticsProperties.Disabled) == null

    private fun tagOf(n: SemanticsNode): String? {
        var p: SemanticsNode? = n
        while (p != null) {
            p.config.getOrNull(SemanticsProperties.TestTag)?.let { return it }
            p = p.parent
        }
        return null
    }

    /** Every "sugerida" / "sugerido" stateDescription on screen, as "nearest tag=state". */
    private fun suggestionMarks(): Set<String> =
        compose.onAllNodes(
            SemanticsMatcher("stateDescription sugerida ou sugerido") {
                it.config.getOrNull(SemanticsProperties.StateDescription).let { s -> s == "sugerida" || s == "sugerido" }
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { "${tagOf(it) ?: "<sem tag>"}=${it.config[SemanticsProperties.StateDescription]}" }.toSet()

    private fun mt(c: Cents) = marginText(c)

    // ---- Navigation and input ----

    private fun openPauta() = click(BudgetTags.PAUTA_STRIP)

    private fun openTransactions() {
        compose.onNode(hasText("Lançamentos") and hasClickAction()).performClick()
        waitFor(TransactionTags.NEW)
    }

    /** Picks [day] in the date dialog, coming from the form's date [from] (the dialog opens on its month). */
    private fun pickDate(from: LocalDate, day: LocalDate) {
        click(TransactionTags.FORM_DATE)
        waitFor(DateTimeTags.DIALOG)
        val steps = (from.year * 12 + from.month.ordinal) - (day.year * 12 + day.month.ordinal)
        repeat(kotlin.math.abs(steps)) { click(if (steps > 0) DateTimeTags.PREV else DateTimeTags.NEXT) }
        click(DateTimeTags.DAY + day)
        compose.waitUntil(5_000) { shows(DateTimeTags.TITLE, dateLong(day)) }
        click(DateTimeTags.DONE)
        waitGone(DateTimeTags.DIALOG)
        compose.waitUntil(5_000) { shows(TransactionTags.FORM_DATE, dateLong(day)) }
    }

    /** Moves the focus off the tag search (the footer hides while the keyboard is over it, §7.3) and saves. */
    private fun submit() {
        runCatching { compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performSemanticsAction(SemanticsActions.RequestFocus) }
        click(TransactionTags.FORM_SUBMIT)
    }

    // ---- The expected state, kept by the test from what it did ----

    private class Form(
        var budget: BudgetId = BudgetId(0),
        var date: LocalDate,
        var total: Cents,
        val chips: MutableSet<String> = mutableSetOf(),
        /** FORM_BUDGET "sugerida" (Lançar with a suggestion, until a pick in the sheet). */
        var budgetSuggested: Boolean = false,
        /** The chips still "sugerido": the suggestion's that were never removed. */
        val chipsSuggested: MutableSet<String> = mutableSetOf(),
        /** Editar: Db.previewAccept replacing this transaction (FORM_PREVIEW KDoc). */
        val replacing: TransactionId = TransactionId(0),
        /**
         * Whether FORM_PREVIEW must be present when a budget is chosen and total > 0. False in Editar previsto: its date
         * is ahead and FORM_FUTURE (which hides the preview) is said for Novo lançamento only — not said either way.
         */
        val previewSaid: Boolean = true,
    )

    /** Every form-level invariant, for the state [f] the test expects. */
    private fun checkForm(f: Form, ctx: String) {
        compose.waitForIdle()
        val chosen = f.budget.v != 0L
        val name = names[f.budget].orEmpty()
        val r = if (chosen) margin(f.budget, f.date) else null

        // The verba field: name and the margin of the budget month of the form's date, or "Escolher a verba".
        await({ "$ctx: FORM_BUDGET esperava ${if (chosen) "$name, margem ${r?.let(::mt)}" else "Escolher a verba"}: ${texts(TransactionTags.FORM_BUDGET)}" }) {
            if (!chosen) shows(TransactionTags.FORM_BUDGET, "Escolher a verba")
            else shows(TransactionTags.FORM_BUDGET, name) && (r == null || shows(TransactionTags.FORM_BUDGET, "margem ${mt(r)}"))
        }
        if (chosen) {
            assertNotShows(ctx, TransactionTags.FORM_BUDGET, "Escolher a verba")
            if (r == null) assertNotShows("$ctx (verba sem cota no mês: sem margem)", TransactionTags.FORM_BUDGET, "margem")
        } else {
            assertNotShows(ctx, TransactionTags.FORM_BUDGET, "margem")
        }

        // Chips: exactly the expected set, every one selected (they count).
        await({ "$ctx: chips esperados ${f.chips.sorted()}, na tela ${named(TransactionTags.TAG).sorted()}" }) {
            named(TransactionTags.TAG).toSet() == f.chips
        }
        for (c in f.chips) assertTrue("$ctx: o chip $c conta (selected)", selected(TransactionTags.TAG + c))

        // "sugerida" / "sugerido" only where the suggestion still stands.
        val marks = buildSet {
            if (f.budgetSuggested) add("${TransactionTags.FORM_BUDGET}=sugerida")
            for (c in f.chipsSuggested) add("${TransactionTags.TAG}$c=sugerido")
        }
        await({ "$ctx: tracejados esperados $marks, na tela ${suggestionMarks()}" }) { suggestionMarks() == marks }

        // The preview: same budget, same month, so its "before" is the field's margin.
        val future = count(TransactionTags.FORM_FUTURE) > 0
        if (!chosen || f.total.v == 0L) {
            await({ "$ctx: sem verba ou sem valor, sem prévia: ${texts(TransactionTags.FORM_PREVIEW)}" }) { count(TransactionTags.FORM_PREVIEW) == 0 }
        } else if (!future && r != null) {
            if (f.previewSaid) await({ "$ctx: com verba e valor, a prévia aparece" }) { count(TransactionTags.FORM_PREVIEW) > 0 }
            if (count(TransactionTags.FORM_PREVIEW) > 0) {
                val p = runBlocking { db.previewAccept(f.budget, f.date, f.total, 1, OCT_9, f.replacing) }
                assertEquals("$ctx: Db.previewAccept.before é a margem da visão geral do mesmo mês", r, p.before)
                val want = "$name: margem ${mt(r)} → ${mt(p.after)}"
                await({ "$ctx: FORM_PREVIEW deveria mostrar \"$want\": ${texts(TransactionTags.FORM_PREVIEW)}" }) { shows(TransactionTags.FORM_PREVIEW, want) }
            }
        }

        // What is missing, and the button.
        val missing = buildList {
            if (f.total.v == 0L) add("o valor")
            if (!chosen) add("a verba")
            if (f.chips.isEmpty()) add("um marcador")
        }
        if (missing.isEmpty()) {
            await({ "$ctx: nada falta, FORM_MISSING some: ${texts(TransactionTags.FORM_MISSING)}" }) { count(TransactionTags.FORM_MISSING) == 0 }
        } else {
            await({ "$ctx: falta $missing: ${texts(TransactionTags.FORM_MISSING)}" }) { missing.all { shows(TransactionTags.FORM_MISSING, it) } }
            for (s in listOf("o valor", "a verba", "um marcador") - missing.toSet()) assertNotShows(ctx, TransactionTags.FORM_MISSING, s)
        }
        if (count(TransactionTags.FORM_SUBMIT) > 0) {
            assertEquals("$ctx: FORM_SUBMIT habilitado sse FORM_MISSING ausente", count(TransactionTags.FORM_MISSING) == 0, enabled(TransactionTags.FORM_SUBMIT))
        }
    }

    /**
     * Opens the sheet and checks it: search empty, the rows (all budgets in manual order), exactly the chosen one
     * selected, each row's margin in the month of the form's date (none without a limit row). With [search], the rows
     * narrow to the names that contain it under `fold`, then the search is emptied. Then [pick] chooses and closes.
     */
    private fun checkSheetAndPick(f: Form, pick: BudgetId, ctx: String, search: String? = null) {
        click(TransactionTags.FORM_BUDGET)
        waitFor(TransactionTags.BUDGET_SHEET)
        val wantIds = manual.map { it.v.toString() }
        await({ "$ctx: folha com todas as verbas em ordem manual $wantIds: ${named(TransactionTags.BUDGET)}" }) { named(TransactionTags.BUDGET) == wantIds }
        assertEquals("$ctx: a busca abre vazia", "", editable(TransactionTags.BUDGET_SEARCH))
        val sel = manual.filter { selected(TransactionTags.BUDGET + it.v) }
        assertEquals("$ctx: exatamente a escolhida selecionada (ou nenhuma)", if (f.budget.v != 0L) listOf(f.budget) else emptyList<BudgetId>(), sel)
        val month = margins(f.date)
        for (b in manual) {
            val row = TransactionTags.BUDGET + b.v
            assertShows(ctx, row, names.getValue(b))
            val r = month[b]
            if (r != null) {
                // The chosen row reads the same margin FORM_BUDGET reads ("margem " + this), checked in checkForm.
                assertShows("$ctx: margem do mês da data do formulário", row, mt(Cents(r)))
            } else {
                assertTrue("$ctx: ${names[b]} sem cota no mês, sem margem: ${texts(row)}", texts(row).none { it.contains("R$") })
            }
        }
        if (search != null) {
            compose.onNodeWithTag(TransactionTags.BUDGET_SEARCH).performTextReplacement(search)
            val narrowed = manual.filter { fold(names.getValue(it)).contains(fold(search)) }.map { it.v.toString() }
            await({ "$ctx: busca \"$search\" deveria listar $narrowed: ${named(TransactionTags.BUDGET)}" }) { named(TransactionTags.BUDGET) == narrowed }
            compose.onNodeWithTag(TransactionTags.BUDGET_SEARCH).performTextReplacement("")
            await({ "$ctx: busca vazia de novo, todas: ${named(TransactionTags.BUDGET)}" }) { named(TransactionTags.BUDGET) == wantIds }
        }
        click(TransactionTags.BUDGET + pick.v)
        waitGone(TransactionTags.BUDGET_SHEET)
        f.budget = pick
        f.budgetSuggested = false
    }

    /**
     * Types [q] in the tag search and checks shortcuts (empty text) or results, "mais N", "Criar" and "Nenhum". Leaves
     * the text in place.
     */
    private fun checkTagSearch(f: Form, q: String, ctx: String) {
        compose.onNodeWithTag(TransactionTags.TAG_SEARCH).performTextReplacement(q)
        compose.waitForIdle()
        val uses = runBlocking { db.tags() }.associate { it.name to it.uses }
        val c = "$ctx, busca \"$q\""
        if (q.isEmpty()) {
            val left = uses.keys - f.chips
            await({ "$c: ${minOf(8, left.size)} atalhos: ${named(TransactionTags.TAG_SHORTCUT)}" }) { named(TransactionTags.TAG_SHORTCUT).size == minOf(8, left.size) }
            val s = named(TransactionTags.TAG_SHORTCUT).toSet()
            assertTrue("$c: atalho também é chip: ${s intersect f.chips}", (s intersect f.chips).isEmpty())
            assertTrue("$c: atalho que não é marcador existente fora dos escolhidos: ${s - left}", left.containsAll(s))
            val notShown = left - s
            if (s.isNotEmpty() && notShown.isNotEmpty()) {
                assertTrue(
                    "$c: os mais usados (${s.associateWith { uses[it] }}) não podem ter menos usos que um de fora (${notShown.associateWith { uses[it] }})",
                    s.minOf { uses.getValue(it) } >= notShown.maxOf { uses.getValue(it) },
                )
            }
            for (t in listOf(TransactionTags.TAG_CREATE, TransactionTags.TAG_NONE)) assertEquals("$c: sem texto, sem $t", 0, count(t))
            assertTrue("$c: sem texto, sem resultados", named(TransactionTags.TAG_RESULT).isEmpty())
            return
        }
        val t = q.trim()
        val clean = t.lowercase()
        // Spec §7.3 (2026-10-23): every tag and chosen name that matches, no limit; the chosen ones with ✓ (selected).
        val matches = (uses.keys + f.chips).filter { fold(it).contains(fold(t)) }
        await({ "$c: ${matches.size} resultados de $matches: ${named(TransactionTags.TAG_RESULT)}" }) {
            named(TransactionTags.TAG_RESULT).size == matches.size
        }
        val res = named(TransactionTags.TAG_RESULT)
        for (r in res) {
            assertTrue("$c: o resultado $r contém \"$t\" sem maiúsculas e acentos", fold(r).contains(fold(t)))
            assertTrue("$c: o resultado $r é um marcador existente ou escolhido", r in matches)
            assertEquals("$c: o resultado $r tem ✓ só se escolhido", r in f.chips, selected(TransactionTags.TAG_RESULT + r))
        }
        assertTrue("$c: com texto, sem atalhos", named(TransactionTags.TAG_SHORTCUT).isEmpty())
        // "Criar": absent when the clean text is a tag's or a chip's name; present when nothing folds to it. A name
        // equal only under fold ("cafe" for "café") is not said either way: not asserted.
        val known = uses.keys + f.chips
        if (clean in known) {
            assertEquals("$c: \"$clean\" já existe, sem Criar", 0, count(TransactionTags.TAG_CREATE))
        } else if (known.none { fold(it) == fold(clean) }) {
            await({ "$c: Criar “$clean”: ${texts(TransactionTags.TAG_CREATE)}" }) { shows(TransactionTags.TAG_CREATE, "Criar “$clean”") }
        }
        if (count(TransactionTags.TAG_CREATE) > 0 && res.isEmpty()) {
            assertShows(c, TransactionTags.TAG_NONE, "Nenhum marcador com “$clean”.")
        } else {
            assertEquals("$c: \"Nenhum\" só com Criar e sem resultado", 0, count(TransactionTags.TAG_NONE))
        }
    }

    /** Types [q] and taps the result [name]: chosen (not "sugerido"), the text empties. */
    private fun addByResult(f: Form, q: String, name: String, ctx: String) {
        checkTagSearch(f, q, ctx)
        click(TransactionTags.TAG_RESULT + name)
        f.chips += name
        await({ "$ctx: tocar o resultado limpa a busca: \"${editable(TransactionTags.TAG_SEARCH)}\"" }) { editable(TransactionTags.TAG_SEARCH).isEmpty() }
    }

    private fun addByCreate(f: Form, q: String, ctx: String) {
        checkTagSearch(f, q, ctx)
        click(TransactionTags.TAG_CREATE)
        f.chips += q.trim().lowercase()
        await({ "$ctx: Criar limpa a busca: \"${editable(TransactionTags.TAG_SEARCH)}\"" }) { editable(TransactionTags.TAG_SEARCH).isEmpty() }
    }

    private fun removeChip(f: Form, name: String) {
        click(TransactionTags.TAG + name)
        f.chips -= name
        f.chipsSuggested -= name
    }

    // ---- Lançar with a suggestion ----

    /** The PADARIA DO ZE recado: Mercado and padaria + café come from the suggestion (spec §2.5). */
    private fun padariaRecado(): Pair<NoticeId, Form> {
        val id = post("Compra aprovada: R$ 46,00 em PADARIA DO ZE", at(8, 0))
        val p = runBlocking { db.notice(id).parsed }
        assertEquals(Cents(4600), p.cents)
        val s = runBlocking { db.suggestion(p.merchant) }
        assertEquals("a sugestão é a do lançamento de 5 out", mercado, s.budget)
        assertEquals(setOf("padaria", "café"), s.tags.toSet())
        return id to Form(budget = mercado, date = p.date, total = p.cents, chips = mutableSetOf("padaria", "café"), budgetSuggested = true, chipsSuggested = mutableSetOf("padaria", "café"))
    }

    @Test
    fun `lancar with a suggestion keeps margins months and marks consistent until saved`() {
        val (id, f) = padariaRecado()
        assertEquals(OCT_9, f.date)
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        waitFor(TransactionTags.FORM_BUDGET)

        // Mercado in October: 396,55; preview 396,55 − 46,00 = 350,55.
        checkForm(f, "Lançar, aberto")
        assertShows("Lançar, aberto", TransactionTags.FORM_BUDGET, "margem ${mt(Cents(39655))}")
        assertShows("Lançar, aberto", TransactionTags.FORM_PREVIEW, "Mercado: margem ${mt(Cents(39655))} → ${mt(Cents(35055))}")
        checkTagSearch(f, "", "Lançar, aberto")
        checkTagSearch(f, "a", "Lançar, aberto")

        // Another month, no pick: still "sugerida"; Mercado in September 600,00; preview 600,00 − 46,00 = 554,00.
        pickDate(f.date, SEP_28)
        f.date = SEP_28
        checkForm(f, "Lançar, data em setembro")
        assertShows("Lançar, setembro", TransactionTags.FORM_BUDGET, "margem ${mt(Cents(60000))}")
        assertNotShows("Lançar, setembro: a margem de outubro sai", TransactionTags.FORM_BUDGET, "396,55")
        assertShows("Lançar, setembro", TransactionTags.FORM_PREVIEW, "Mercado: margem ${mt(Cents(60000))} → ${mt(Cents(55400))}")

        // The sheet in September (Saúde has no September row), then Lazer: no longer "sugerida", the chips still are.
        checkSheetAndPick(f, lazer, "Lançar, folha em setembro", search = "CA")
        checkForm(f, "Lançar, Lazer escolhida na folha")
        // Lazer in September 250,00; preview 250,00 − 46,00 = 204,00.
        assertShows("Lançar, Lazer em setembro", TransactionTags.FORM_PREVIEW, "Lazer: margem ${mt(Cents(25000))} → ${mt(Cents(20400))}")

        // Removing a suggested chip, then bringing it back from the search: full, not "sugerido".
        removeChip(f, "café")
        checkForm(f, "Lançar, café tirado")
        addByResult(f, "cafe", "café", "Lançar, café de volta")
        checkForm(f, "Lançar, café de volta pela busca")

        // Back to October: Lazer −46,00; preview −46,00 − 46,00 = −92,00.
        pickDate(f.date, OCT_9)
        f.date = OCT_9
        checkForm(f, "Lançar, de volta a outubro")
        assertShows("Lançar, outubro", TransactionTags.FORM_BUDGET, "margem ${mt(Cents(-4600))}")
        assertNotShows("Lançar, outubro: a margem de setembro sai", TransactionTags.FORM_BUDGET, "250,00")
        assertShows("Lançar, outubro", TransactionTags.FORM_PREVIEW, "Lazer: margem ${mt(Cents(-4600))} → ${mt(Cents(-9200))}")
        checkSheetAndPick(f, lazer, "Lançar, folha em outubro", search = "saú")
        checkForm(f, "Lançar, Lazer de novo")
        checkTagSearch(f, "", "Lançar, antes de lançar")

        submit()
        waitFor(SnackTags.TEXT)
        val tx = runBlocking { db.transactions() }.first { it.notice == id }
        assertEquals("verba gravada", lazer, tx.budget)
        assertEquals("marcadores gravados = chips", f.chips, tx.tags.toSet())
        // The preview's after is the margin once saved, and the snack's.
        assertEquals(Cents(-9200), margin(lazer, OCT_9))
        assertShows("Lançar, snack", SnackTags.TEXT, "Margem: ${mt(Cents(-9200))}.")
    }

    // ---- Novo lançamento ----

    @Test
    fun `novo lancamento never shows a suggestion and follows the date month`() {
        open()
        openTransactions()
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_BUDGET)
        val f = Form(date = OCT_9, total = Cents(0))

        checkForm(f, "Novo, vazio")
        assertShows("Novo, vazio", TransactionTags.FORM_MISSING, "Falta o valor, a verba e um marcador.")
        checkTagSearch(f, "", "Novo, vazio")

        checkSheetAndPick(f, saude, "Novo, folha sem escolha", search = "saú")
        checkForm(f, "Novo, Saúde sem valor")

        compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performTextReplacement("4600")
        f.total = Cents(4600)
        // Saúde in October 200,00; preview 200,00 − 46,00 = 154,00.
        checkForm(f, "Novo, R$ 46,00 em Saúde")
        assertShows("Novo, Saúde", TransactionTags.FORM_PREVIEW, "Saúde: margem ${mt(Cents(20000))} → ${mt(Cents(15400))}")

        // September: Saúde has no limit row there, so no margin on the field nor in its sheet row.
        pickDate(f.date, SEP_20)
        f.date = SEP_20
        checkForm(f, "Novo, Saúde em setembro")
        checkSheetAndPick(f, lazer, "Novo, folha em setembro")
        // Lazer in September 250,00; preview 250,00 − 46,00 = 204,00.
        checkForm(f, "Novo, Lazer em setembro")
        assertShows("Novo, Lazer em setembro", TransactionTags.FORM_PREVIEW, "Lazer: margem ${mt(Cents(25000))} → ${mt(Cents(20400))}")

        pickDate(f.date, OCT_9)
        f.date = OCT_9
        checkForm(f, "Novo, Lazer em outubro")
        assertShows("Novo, outubro", TransactionTags.FORM_BUDGET, "margem ${mt(Cents(-4600))}")
        assertNotShows("Novo, outubro", TransactionTags.FORM_BUDGET, "250,00")

        addByResult(f, "pao", "pão", "Novo, pão")
        checkForm(f, "Novo, com pão")
        addByCreate(f, " Festa Junina ", "Novo, criar")
        checkForm(f, "Novo, com festa junina")
        checkTagSearch(f, "festa junina", "Novo, criado e escolhido")
        checkTagSearch(f, "", "Novo, antes de lançar")

        val before = runBlocking { db.transactions() }.map { it.id }.toSet()
        submit()
        waitFor(SnackTags.TEXT)
        val tx = runBlocking { db.transactions() }.single { it.id !in before }
        assertEquals(lazer, tx.budget)
        assertEquals("marcadores gravados = chips", f.chips, tx.tags.toSet())
        assertEquals(Cents(-9200), margin(lazer, OCT_9))
        assertShows("Novo, snack", SnackTags.TEXT, "Margem: ${mt(Cents(-9200))}.")
    }

    // ---- Editar ----

    @Test
    fun `editar replaces itself in the preview and saves the chip set`() {
        open()
        openTransactions()
        click(TransactionTags.ROW + feira.v)
        waitFor(TransactionTags.FORM_BUDGET)
        val f = Form(budget = mercado, date = OCT_3, total = Cents(12345), chips = mutableSetOf("feira"), replacing = feira)

        // Mercado in October 396,55 (with the Feira itself); swapped for the same values, the after is the same.
        checkForm(f, "Editar, aberto")
        assertShows("Editar, aberto", TransactionTags.FORM_PREVIEW, "Mercado: margem ${mt(Cents(39655))} → ${mt(Cents(39655))}")
        checkTagSearch(f, "", "Editar, aberto")

        checkSheetAndPick(f, lazer, "Editar, folha", search = "ZZ")
        // Lazer in October −46,00; moving R$ 123,45 into it: −169,45.
        checkForm(f, "Editar, Lazer")
        assertShows("Editar, Lazer", TransactionTags.FORM_PREVIEW, "Lazer: margem ${mt(Cents(-4600))} → ${mt(Cents(-16945))}")

        removeChip(f, "feira")
        checkForm(f, "Editar, sem marcador")
        assertShows("Editar, sem marcador", TransactionTags.FORM_MISSING, "Falta um marcador.")
        checkTagSearch(f, "", "Editar, sem marcador")
        addByResult(f, "LAN", "lanche", "Editar, lanche")
        checkForm(f, "Editar, com lanche")

        submit()
        compose.waitUntil(5_000) { runBlocking { db.transaction(feira) }.row.budget == lazer }
        val row = runBlocking { db.transaction(feira) }.row
        assertEquals("marcadores gravados = chips", f.chips, row.tags.toSet())
        assertEquals("a prévia era o depois", Cents(-16945), margin(lazer, OCT_9))
        assertEquals("Mercado sem a Feira: 600,00 − 80,00", Cents(52000), margin(mercado, OCT_9))
    }

    // ---- Lançar linked to a previsto ----

    @Test
    fun `a linked form has the previsto budget and tags full and never suggested`() {
        ahead("Conta de luz", casa, listOf("contas", "luz"), 18000, OCT_12)
        val luz = occ("Conta de luz")
        val id = post("Pagamento de conta: R$ 187,40 em ENEL SP", at(18, 20))
        val p = runBlocking { db.notice(id).parsed }
        assertEquals(Cents(18740), p.cents)
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        waitFor(TransactionTags.FORM_BUDGET)

        val f = Form(date = p.date, total = p.cents)
        checkForm(f, "Ligar, antes de ligar (sem histórico, sem sugestão)")

        click(TransactionTags.FORM_LINK_OPEN)
        click(TransactionTags.LINK_CANDIDATE + luz.v)
        click(TransactionTags.LINK_SUBMIT)
        waitFor(TransactionTags.FORM_LINKED)
        f.budget = casa
        f.chips += listOf("contas", "luz")

        // Casa in October 1.500,00; preview 1.500,00 − 187,40 = 1.312,60.
        checkForm(f, "Ligado")
        assertShows("Ligado", TransactionTags.FORM_PREVIEW, "Casa: margem ${money(Cents(150000))} → ${money(Cents(131260))}")
        checkTagSearch(f, "", "Ligado")
        // "con" matches only contas, which is chosen: no result, so "Criar" and "Nenhum".
        checkTagSearch(f, "con", "Ligado")
        checkTagSearch(f, "", "Ligado, busca limpa")
        checkSheetAndPick(f, casa, "Ligado, folha", search = "casa")
        checkForm(f, "Ligado, Casa de novo")

        submit()
        waitFor(SnackTags.TEXT)
        val tx = runBlocking { db.transactions() }.first { it.notice == id }
        assertEquals(casa, tx.budget)
        assertEquals("marcadores gravados = chips", f.chips, tx.tags.toSet())
        assertEquals(Cents(131260), margin(casa, OCT_9))
    }

    // ---- Editar previsto ----

    @Test
    fun `editar previsto shows margins of its month and saves the chip set on the previsto`() {
        ahead("Internet", casa, listOf("contas"), 11990, OCT_12)
        val internet = occ("Internet")
        open()
        openTransactions()
        click(TransactionTags.ON_THE_WAY)
        waitFor("${TransactionTags.ON_THE_WAY}.open")
        click(TransactionTags.PLANNED_ROW + internet.v)
        waitFor(TransactionTags.PLANNED_SHEET)
        click(TransactionTags.PLANNED_EDIT)
        waitFor(TransactionTags.FORM_BUDGET)

        // The occurrence's date, Oct 12: the October margins.
        val f = Form(budget = casa, date = OCT_12, total = Cents(11990), chips = mutableSetOf("contas"), previewSaid = false)
        checkForm(f, "Editar previsto, aberto")
        assertShows("Editar previsto", TransactionTags.FORM_BUDGET, "margem ${money(Cents(150000))}")
        checkTagSearch(f, "", "Editar previsto")

        checkSheetAndPick(f, mercado, "Editar previsto, folha", search = "MER")
        checkForm(f, "Editar previsto, Mercado")
        assertShows("Editar previsto, Mercado", TransactionTags.FORM_BUDGET, "margem ${mt(Cents(39655))}")

        addByResult(f, " BAR ", "bar", "Editar previsto, bar")
        checkForm(f, "Editar previsto, com bar")
        checkTagSearch(f, "ar", "Editar previsto, com bar")
        checkTagSearch(f, "", "Editar previsto, antes de salvar")

        submit()
        compose.waitUntil(5_000) { runBlocking { db.planned(OCT_9) }.first { it.name == "Internet" }.budget == mercado }
        val pl = runBlocking { db.planned(OCT_9) }.first { it.name == "Internet" }
        assertEquals("marcadores gravados no previsto = chips", f.chips, pl.tags.toSet())
        assertEquals("editar previsto não lança: margem de Mercado igual", Cents(39655), margin(mercado, OCT_9))
    }

    // ---- A seeded walk over the tags ----

    @Test
    fun `a seeded walk over chips shortcuts results and create keeps every invariant`() {
        val (id, f) = padariaRecado()
        open()
        openPauta()
        click(InboxTags.ACCEPT + id.v)
        waitFor(TransactionTags.FORM_BUDGET)
        checkForm(f, "passeio, aberto")

        val queries = listOf("a", "CAF", "cafe", " pão ", "pa", "AR", "acai", "festa junina", "zz", "lanche", "e", "Mercado", "o")
        val rnd = Random(SEED)
        for (step in 1..STEPS) {
            val action = rnd.nextInt(5)
            val q = queries[rnd.nextInt(queries.size)]
            val ctx = "seed=$SEED passo=$step ação=$action entrada=\"$q\" chips=${f.chips.sorted()}"
            when (action) {
                // Type: only the search changes.
                0 -> checkTagSearch(f, q, ctx)
                // Type and tap a result, if any.
                1 -> {
                    checkTagSearch(f, q, ctx)
                    val res = named(TransactionTags.TAG_RESULT)
                    if (res.isNotEmpty()) {
                        val pick = res[rnd.nextInt(res.size)]
                        click(TransactionTags.TAG_RESULT + pick)
                        f.chips += pick
                        await({ "$ctx: tocar $pick limpa a busca" }) { editable(TransactionTags.TAG_SEARCH).isEmpty() }
                    }
                }
                // Empty search and tap a shortcut, if any.
                2 -> {
                    checkTagSearch(f, "", ctx)
                    val s = named(TransactionTags.TAG_SHORTCUT)
                    if (s.isNotEmpty()) {
                        val pick = s[rnd.nextInt(s.size)]
                        click(TransactionTags.TAG_SHORTCUT + pick)
                        f.chips += pick
                    }
                }
                // Tap a chip: it goes, suggested or not.
                3 -> if (f.chips.isNotEmpty()) removeChip(f, f.chips.sorted()[rnd.nextInt(f.chips.size)])
                // Type and tap "Criar", if there.
                else -> {
                    checkTagSearch(f, q, ctx)
                    if (count(TransactionTags.TAG_CREATE) > 0) {
                        click(TransactionTags.TAG_CREATE)
                        f.chips += q.trim().lowercase()
                        await({ "$ctx: Criar limpa a busca" }) { editable(TransactionTags.TAG_SEARCH).isEmpty() }
                    }
                }
            }
            checkForm(f, ctx)
        }

        if (f.chips.isEmpty()) {
            addByResult(f, "bar", "bar", "seed=$SEED fim")
            checkForm(f, "seed=$SEED fim")
        }
        submit()
        waitFor(SnackTags.TEXT)
        val tx = runBlocking { db.transactions() }.first { it.notice == id }
        assertEquals("seed=$SEED: verba sugerida gravada", mercado, tx.budget)
        assertEquals("seed=$SEED: marcadores gravados = chips", f.chips, tx.tags.toSet())
    }

    private companion object {
        const val BANK = "com.cartaoipe"
        const val SEED = 20261022L
        const val STEPS = 14
        val ZONE = TimeZone.of("America/Sao_Paulo")
        val SEP_1: LocalDate = LocalDate(2026, 9, 1)
        val SEP_10: LocalDate = LocalDate(2026, 9, 10)
        val SEP_15: LocalDate = LocalDate(2026, 9, 15)
        val SEP_20: LocalDate = LocalDate(2026, 9, 20)
        val SEP_28: LocalDate = LocalDate(2026, 9, 28)
        val OCT_1: LocalDate = LocalDate(2026, 10, 1)
        val OCT_2: LocalDate = LocalDate(2026, 10, 2)
        val OCT_3: LocalDate = LocalDate(2026, 10, 3)
        val OCT_5: LocalDate = LocalDate(2026, 10, 5)
        val OCT_9: LocalDate = LocalDate(2026, 10, 9)
        val OCT_12: LocalDate = LocalDate(2026, 10, 12)
    }
}
