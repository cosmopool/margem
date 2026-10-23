package com.kaiodelphino.margem

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InboxTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.MargemTheme
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
 * Fuzz of the lean form's choosing (spec §2.5, §7.3 "Verba" and "Marcadores"; D61, D66): random but
 * reproducible texts typed in "Buscar ou criar marcador" and "Buscar verba", and random sequences of
 * choose / remove / create / Pronto, checked step by step against the rules of the contract
 * ([TransactionTags] KDoc: TAG_RESULT, TAG_MORE, TAG_CREATE, TAG_NONE, TAG_SHORTCUT, TAG, BUDGET_SHEET,
 * BUDGET, FORM_BUDGET, FORM_MISSING). Written blind, before the implementation.
 *
 * The oracle is plain filters over the data this test seeded, read back through the public Db API
 * (`Db.tags()` for uses, `Db.budgetsOverview(…, MANUAL)` for margins), with `fold` (D61) as the comparison
 * key the contract names. Tag uses are seeded all distinct (0..21), so "uses desc, then name" never needs
 * the name tie-break: how names compare on a tie (plain String order or fold) is left open by the contract.
 *
 * Fixed seeds, at most 40 steps per test, every wait bounded; a failure says seed, step and input.
 * Robolectric does not see paint (dashed chips, ink): only text, presence, order, selection and state
 * descriptions are asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp")
class EscolhaFuzzTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: Db
    private lateinit var path: String
    private lateinit var hostZone: java.util.TimeZone

    /** Budgets in creation order (index into [BUDGET_NAMES]). */
    private val budgets = mutableListOf<BudgetId>()

    /** The manual order after the seeded shuffle: what the sheet lists. */
    private var manualOrder = listOf<BudgetId>()

    /** Clean tag names in catalog order, and the uses each was seeded with. */
    private val tagUses = linkedMapOf<String, Int>()

    /** The tags of the transaction the notice's merchant suggests (the 3 most used). */
    private var suggestedTags = listOf<String>()
    private var suggestedBudget = BudgetId(0)

    @Before
    fun setUp() {
        hostZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"))
        path = tmp.newFile("margem-escolha-${Random.nextLong().toULong()}.db").also { it.delete() }.path
        db = Db(AndroidSQLiteDriver(), path, timeZone = ZONE)
        val r = Random(SETUP_SEED)
        runBlocking {
            db.addSource(BANK, "Cartão Ipê", 0)
            for ((name, limit) in BUDGET_NAMES) {
                val id = db.createBudget(name, Cents(limit), OCT_1)
                assertTrue("verba $name criada", id.v != 0L)
                budgets += id
            }
            manualOrder = budgets.shuffled(r)
            assertTrue("ordem manual embaralhada", db.reorderBudgets(manualOrder))

            // Tags typed messy (case, edge spaces): Db.createTag cleans them to trim().lowercase().
            val uses = TAG_INPUTS.indices.shuffled(r)
            for ((i, input) in TAG_INPUTS.withIndex()) {
                assertTrue("marcador \"$input\" criado", db.createTag(input).v != 0L)
                tagUses[input.trim().lowercase()] = uses[i]
            }
            // Transaction k carries every tag seeded with more than k uses, so each tag ends with exactly its uses.
            val maxUses = tagUses.values.max()
            for (k in 0 until maxUses) {
                val tags = tagUses.filterValues { it > k }.keys.toList()
                val merchant = if (k == SUGGESTION_K) SUGGESTION_MERCHANT else "SEED $k"
                val budget = budgets[k % budgets.size]
                val saved = db.addManual(
                    TransactionForm(
                        budget = budget, tags = tags, total = Cents(1500L + 100L * k), installments = 1,
                        merchant = merchant, date = LocalDate(2026, 10, 1 + k % 8), time = LocalTime(12, 0),
                    ),
                    OCT_9,
                )
                assertTrue("lançamento semente $k", saved.transaction.v != 0L)
                if (k == SUGGESTION_K) { suggestedTags = tags; suggestedBudget = budget }
            }
            // The oracle reads uses back from the Db (the contract's source): it must be what was seeded.
            val read = db.tags().associate { it.name to it.uses }
            assertEquals("usos semeados", tagUses.toMap(), read)
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

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { count(tag) > 0 }

    private fun waitGone(tag: String) = compose.waitUntil(5_000) { count(tag) == 0 }

    /** Waits up to [ms] for [cond]; never throws: the assertion after it says what went wrong. */
    private fun settle(ms: Long = 3_000, cond: () -> Boolean) {
        runCatching { compose.waitUntil(ms) { cond() } }
    }

    private fun click(tag: String) {
        waitFor(tag)
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun node(tag: String): SemanticsNode? =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()

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

    private fun editable(tag: String): String =
        node(tag)?.let { n -> subtree(n).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text } } ?: ""

    private fun state(tag: String): String? =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun selected(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Selected) == true

    /** The suffixes of the tags starting with [prefix], in reading order (top, then left). */
    private fun shown(prefix: String): List<String> =
        compose.onAllNodes(hasTagPrefix(prefix), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix(prefix) to it.boundsInRoot }
            .distinctBy { it.first }
            .sortedWith(compareBy({ it.second.top }, { it.second.left }))
            .map { it.first }

    // ---- Navigation and form ----

    private fun openTransactions() {
        compose.onNode(hasText("Lançamentos") and hasClickAction()).performClick()
        waitFor(TransactionTags.NEW)
    }

    /** Novo lançamento with a name, a total and a budget: only a marcador can be missing. */
    private fun openManualForm(budget: BudgetId) {
        open()
        openTransactions()
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_NAME)
        compose.onNodeWithTag(TransactionTags.FORM_NAME).performTextReplacement(MANUAL_NAME)
        compose.onNodeWithTag(TransactionTags.FORM_TOTAL).performTextReplacement("4600")
        chooseBudget(budget)
    }

    private fun chooseBudget(id: BudgetId) {
        click(TransactionTags.FORM_BUDGET)
        waitFor(TransactionTags.BUDGET_SHEET)
        click(TransactionTags.BUDGET + id.v)
        waitGone(TransactionTags.BUDGET_SHEET)
    }

    private fun typeTag(text: String) = compose.onNodeWithTag(TransactionTags.TAG_SEARCH).performTextReplacement(text)

    private fun typeBudget(text: String) = compose.onNodeWithTag(TransactionTags.BUDGET_SEARCH).performTextReplacement(text)

    // ---- Oracle: the KDoc rules as plain filters over the seeded data ----

    /**
     * TAG_RESULT (spec §7.3, 2026-10-23): every tag and chosen name containing the trimmed text (fold), no
     * limit; starting with it first, then uses desc (a name made here and not saved: −1), then name.
     */
    private fun matches(text: String, chosen: List<String>): List<String> {
        if (text.isBlank()) return emptyList()
        val q = fold(text.trim())
        return (tagUses.keys + chosen).distinct().filter { fold(it).contains(q) }
            .sortedWith(compareBy({ !fold(it).startsWith(q) }, { -(tagUses[it] ?: -1) }, { it }))
    }

    /** TAG_SHORTCUT: the 8 most used not chosen (uses desc, then name). */
    private fun shortcuts(chosen: List<String>): List<String> =
        tagUses.keys.filter { it !in chosen }.sortedWith(compareBy({ -tagUses.getValue(it) }, { it })).take(8)

    /**
     * Whether "is the name of an existing tag or of a chosen one" is open for [clean]: a name that folds the
     * same but is not the same text ("cafe" against "café"). The KDoc says text is compared with fold, while
     * the name made is trim().lowercase() like Db.createTag (which would make a second tag): not asserted.
     */
    private fun createAmbiguous(clean: String, chosen: List<String>): Boolean =
        (tagUses.keys + chosen).any { it != clean && fold(it) == fold(clean) }

    private fun usesText(n: Int) = Regex("(?<!\\d)$n lançamento" + if (n == 1) "(?!s)" else "s")

    /** Checks results, "mais n", "Criar" and "Nenhum" for [text] against the oracle. */
    private fun checkSearch(where: String, text: String, chosen: List<String>) {
        val expected = matches(text, chosen)
        val clean = text.trim().lowercase()
        val create = clean.isNotEmpty() && clean !in tagUses.keys && clean !in chosen
        val ambiguous = createAmbiguous(clean, chosen)
        settle {
            shown(TransactionTags.TAG_RESULT) == expected &&
                (ambiguous || (count(TransactionTags.TAG_CREATE) > 0) == create)
        }
        val msg = "$where input=\"$text\" chosen=$chosen"
        assertEquals("$msg: TAG_RESULT (nomes e ordem)", expected, shown(TransactionTags.TAG_RESULT))
        for (name in expected) {
            val tag = TransactionTags.TAG_RESULT + name
            assertTrue("$msg: $tag mostra o nome: ${texts(tag)}", shows(tag, name))
            assertEquals("$msg: $tag marcado ✓ só se escolhido", name in chosen, selected(tag))
            val uses = tagUses[name] ?: 0
            // "33 lançamentos" ("1 lançamento"); zero uses is not spelled out by the contract.
            if (uses > 0) assertTrue("$msg: $tag mostra $uses lançamento(s): ${texts(tag)}", usesText(uses).containsMatchIn(texts(tag).joinToString(" ")))
        }
        if (!ambiguous) {
            assertEquals("$msg: TAG_CREATE presente (limpo \"$clean\")", create, count(TransactionTags.TAG_CREATE) > 0)
            assertEquals("$msg: TAG_NONE presente", text.isNotBlank() && expected.isEmpty(), count(TransactionTags.TAG_NONE) > 0)
            if (create) assertTrue("$msg: Criar mostra \"$clean\": ${texts(TransactionTags.TAG_CREATE)}", shows(TransactionTags.TAG_CREATE, clean))
            if (create && expected.isEmpty()) assertTrue("$msg: Nenhum mostra \"$clean\"", shows(TransactionTags.TAG_NONE, clean))
        }
        if (text.isBlank()) {
            assertEquals("$msg: sem texto, sem Criar", 0, count(TransactionTags.TAG_CREATE))
            assertEquals("$msg: sem texto, sem Nenhum", 0, count(TransactionTags.TAG_NONE))
        }
    }

    /** The chips are exactly [chosen], in the order chosen, all selected; [suggested] ones read "sugerido", the others don't. */
    private fun checkChips(where: String, chosen: List<String>, suggested: Set<String>) {
        settle { shown(TransactionTags.TAG) == chosen }
        val msg = "$where chosen=$chosen"
        assertEquals("$msg: chips (nomes e ordem)", chosen, shown(TransactionTags.TAG))
        for (name in chosen) {
            val tag = TransactionTags.TAG + name
            assertTrue("$msg: $tag selected", selected(tag))
            assertEquals("$msg: $tag stateDescription", if (name in suggested) "sugerido" else null, state(tag).takeIf { it == "sugerido" })
        }
        settle { (count(TransactionTags.FORM_MISSING) > 0) == chosen.isEmpty() }
        assertEquals("$msg: FORM_MISSING presente só sem marcador", chosen.isEmpty(), count(TransactionTags.FORM_MISSING) > 0)
        if (chosen.isEmpty()) {
            assertTrue("$msg: falta um marcador", shows(TransactionTags.FORM_MISSING, "um marcador"))
            compose.onNodeWithTag(TransactionTags.FORM_SUBMIT).assertIsNotEnabled()
        }
    }

    // ---- Random inputs ----

    private fun addAccents(s: String, r: Random): String = buildString {
        for (ch in s) {
            val options = ACCENTS[ch]
            append(if (options != null && r.nextBoolean()) options[r.nextInt(options.length)] else ch)
        }
    }

    private fun spaces(r: Random) = " ".repeat(r.nextInt(1, 3))

    /** A search text: a piece of a known name (or a whole one, a new word, a short probe), with case, accents and edge spaces varied. */
    private fun searchText(r: Random, names: List<String>): String {
        var t = when (r.nextInt(10)) {
            0 -> NEW_WORDS.random(r)
            1 -> names.random(r)
            2 -> PROBES.random(r)
            else -> {
                val n = names.random(r)
                val i = r.nextInt(n.length)
                n.substring(i, r.nextInt(i, n.length) + 1)
            }
        }
        if (r.nextInt(3) == 0) t = fold(t)
        if (r.nextInt(3) == 0) t = addAccents(t, r)
        if (r.nextInt(3) == 0) t = t.uppercase()
        if (r.nextInt(4) == 0) t = spaces(r) + t + (if (r.nextBoolean()) spaces(r) else "")
        if (r.nextInt(20) == 0) t = spaces(r)
        return t
    }

    /** A text whose clean form is exactly [clean], with case and edge spaces varied (no accents changed). */
    private fun messy(clean: String, r: Random): String {
        var t = clean
        when (r.nextInt(3)) {
            0 -> t = t.uppercase()
            1 -> t = t.replaceFirstChar { it.uppercaseChar() }
        }
        if (r.nextBoolean()) t = spaces(r) + t + spaces(r)
        return t
    }

    /**
     * [steps] random operations on the tag picker, each checked: type and pick a result, type and "Criar",
     * the keyboard's Pronto (existing name, chosen name, new word or blank), remove a chip, pick a shortcut.
     */
    private fun runTagOps(seed: Long, steps: Int, chosen: MutableList<String>, suggested: MutableSet<String>) {
        val r = Random(seed)
        val names = tagUses.keys.toList()
        for (step in 0 until steps) {
            val where = "seed=$seed step=$step"
            when (r.nextInt(5)) {
                0 -> {
                    val text = searchText(r, names)
                    typeTag(text)
                    checkSearch("$where op=result", text, chosen)
                    val results = shown(TransactionTags.TAG_RESULT).filter { it !in chosen } // a chosen result does nothing (✓)
                    if (results.isNotEmpty()) {
                        val pick = results.random(r)
                        click(TransactionTags.TAG_RESULT + pick)
                        chosen += pick
                        suggested -= pick
                        settle { editable(TransactionTags.TAG_SEARCH).isEmpty() }
                        assertEquals("$where op=result input=\"$text\" pick=$pick: texto limpo", "", editable(TransactionTags.TAG_SEARCH))
                    }
                }
                1 -> {
                    val word = NEW_WORDS.random(r)
                    val text = messy(word, r)
                    typeTag(text)
                    checkSearch("$where op=create", text, chosen)
                    if (word !in chosen) {
                        click(TransactionTags.TAG_CREATE)
                        chosen += word
                        settle { editable(TransactionTags.TAG_SEARCH).isEmpty() }
                        assertEquals("$where op=create input=\"$text\": texto limpo", "", editable(TransactionTags.TAG_SEARCH))
                    }
                }
                2 -> {
                    val clean = when (r.nextInt(4)) {
                        0 -> names.filter { it !in suggested }.random(r)
                        // Pronto on a suggested chip's name: whether it stays "sugerido" is not in the contract.
                        1 -> chosen.filter { it !in suggested }.randomOrNull(r) ?: names.filter { it !in suggested }.random(r)
                        2 -> NEW_WORDS.random(r)
                        else -> ""
                    }
                    val text = if (clean.isEmpty()) spaces(r) else messy(clean, r)
                    typeTag(text)
                    compose.waitForIdle()
                    compose.onNodeWithTag(TransactionTags.TAG_SEARCH).performImeAction()
                    // Marks the tag of that name, else creates it; a chosen name is never added twice; blank does nothing.
                    if (clean.isNotEmpty() && clean !in chosen) chosen += clean
                    if (clean.isNotEmpty()) {
                        settle { editable(TransactionTags.TAG_SEARCH).isEmpty() }
                        assertEquals("$where op=done input=\"$text\": texto limpo", "", editable(TransactionTags.TAG_SEARCH))
                    }
                }
                3 -> {
                    val pick = chosen.randomOrNull(r)
                    if (pick != null) {
                        click(TransactionTags.TAG + pick)
                        chosen -= pick
                        suggested -= pick
                    }
                }
                else -> {
                    typeTag("")
                    val expected = shortcuts(chosen)
                    settle { shown(TransactionTags.TAG_SHORTCUT) == expected }
                    assertEquals("$where op=shortcut chosen=$chosen: Mais usados", expected, shown(TransactionTags.TAG_SHORTCUT))
                    assertEquals("$where op=shortcut: sem resultados com o texto vazio", emptyList<String>(), shown(TransactionTags.TAG_RESULT))
                    val pick = expected.randomOrNull(r)
                    if (pick != null) {
                        click(TransactionTags.TAG_SHORTCUT + pick)
                        chosen += pick
                    }
                }
            }
            checkChips(where, chosen, suggested)
        }
    }

    // ---- Tests ----

    @Test
    fun `random tag searches show the contract results in the contract order`() {
        val seed = 0x5EA2C4L
        val r = Random(seed)
        val names = tagUses.keys.toList()
        openManualForm(budgets[0])
        val chosen = mutableListOf<String>()
        for (step in 0 until 36) {
            val where = "seed=$seed step=$step"
            val text = searchText(r, names)
            typeTag(text)
            checkSearch(where, text, chosen)
            // Now and then a pick or a removal, so "not chosen" is exercised too.
            val results = shown(TransactionTags.TAG_RESULT).filter { it !in chosen } // a chosen result does nothing (✓)
            if (results.isNotEmpty() && r.nextInt(4) == 0) {
                val pick = results.random(r)
                click(TransactionTags.TAG_RESULT + pick)
                chosen += pick
                checkChips("$where pick=$pick", chosen, emptySet())
            } else if (chosen.isNotEmpty() && r.nextInt(8) == 0) {
                val pick = chosen.random(r)
                click(TransactionTags.TAG + pick)
                chosen -= pick
                checkChips("$where remove=$pick", chosen, emptySet())
            }
        }
    }

    @Test
    fun `random choose remove create and done sequences save exactly the chips shown`() {
        val seed = 0xC0FFEEL
        openManualForm(budgets[1])
        click(TransactionTags.TAG_SEARCH)
        val chosen = mutableListOf<String>()
        runTagOps(seed, 30, chosen, mutableSetOf())
        if (chosen.isEmpty()) {
            typeTag("pizzaria")
            click(TransactionTags.TAG_CREATE)
            chosen += "pizzaria"
            checkChips("seed=$seed final", chosen, emptySet())
        }
        click(TransactionTags.FORM_SUBMIT)
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.any { it.merchant == MANUAL_NAME } }
        val row = runBlocking { db.transactions() }.first { it.merchant == MANUAL_NAME }
        assertEquals("seed=$seed: sem marcador repetido ${row.tags}", row.tags.toSet().size, row.tags.size)
        assertEquals("seed=$seed: marcadores gravados = chips", chosen.toSet(), row.tags.toSet())
        for (t in row.tags) assertEquals("seed=$seed: \"$t\" limpo", t.trim().lowercase(), t)
        val saved = runBlocking { db.tags() }.map { it.name }.toSet()
        assertTrue("seed=$seed: criados viram marcadores $chosen ⊂ $saved", saved.containsAll(chosen))
    }

    @Test
    fun `random edits of a suggestion keep dashed only the suggested chips left and launch them`() {
        val seed = 0xD1CEL
        val postedAt = LocalDateTime(2026, 10, 9, 7, 42).toInstant(ZONE).toEpochMilliseconds()
        val notice = runBlocking {
            assertTrue(capture(db, RawNotice(packageName = BANK, appLabel = "Cartão Ipê", key = "k$postedAt", postedAt = postedAt, title = "Cartão Ipê", text = "Compra aprovada: R$ 46,00 em $SUGGESTION_MERCHANT")))
            db.pending().first { it.raw.postedAt == postedAt }.id
        }
        open()
        click(BudgetTags.PAUTA_STRIP)
        click(InboxTags.ACCEPT + notice.v)
        waitFor(TransactionTags.FORM_SUBMIT)

        // The suggestion: its budget "sugerida", its tags chosen and "sugerido" (spec §2.5).
        val chosen = suggestedTags.toMutableList()
        val suggested = suggestedTags.toMutableSet()
        settle { state(TransactionTags.FORM_BUDGET) == "sugerida" }
        assertEquals("seed=$seed: verba sugerida", "sugerida", state(TransactionTags.FORM_BUDGET))
        assertTrue("seed=$seed: o campo mostra a verba sugerida", shows(TransactionTags.FORM_BUDGET, BUDGET_NAMES[budgets.indexOf(suggestedBudget)].first))
        // The chips' order among the suggestion's own tags is not fixed by the contract: compare as a set first.
        settle { shown(TransactionTags.TAG).toSet() == chosen.toSet() }
        val initial = shown(TransactionTags.TAG)
        assertEquals("seed=$seed: chips da sugestão", chosen.toSet(), initial.toSet())
        chosen.clear()
        chosen += initial
        checkChips("seed=$seed start", chosen, suggested)

        click(TransactionTags.TAG_SEARCH)
        runTagOps(seed, 24, chosen, suggested)

        // Choosing in the sheet, even the suggested budget, makes it plain.
        chooseBudget(suggestedBudget)
        settle { state(TransactionTags.FORM_BUDGET) != "sugerida" }
        assertTrue("seed=$seed: escolhida na folha, não é mais sugerida", state(TransactionTags.FORM_BUDGET) != "sugerida")
        if (chosen.isEmpty()) {
            typeTag("jantar")
            click(TransactionTags.TAG_CREATE)
            chosen += "jantar"
        }
        checkChips("seed=$seed final", chosen, suggested)

        click(TransactionTags.FORM_SUBMIT)
        compose.waitUntil(5_000) { runBlocking { db.transactions() }.any { it.notice == notice } }
        val row = runBlocking { db.transactions() }.first { it.notice == notice }
        assertEquals("seed=$seed: sem marcador repetido ${row.tags}", row.tags.toSet().size, row.tags.size)
        assertEquals("seed=$seed: lançado com os chips (tracejados contam)", chosen.toSet(), row.tags.toSet())
        assertEquals("seed=$seed: verba lançada", suggestedBudget, row.budget)
    }

    @Test
    fun `random budget searches list the matching budgets in manual order and one choice sticks`() {
        val seed = 0xB0D6E7L
        val r = Random(seed)
        val month = runBlocking { db.monthOf(OCT_9) }
        val overview = runBlocking { db.budgetsOverview(month, OCT_9, BudgetOrder.MANUAL) }.associateBy { it.id }
        val nameOf = budgets.withIndex().associate { (i, id) -> id to BUDGET_NAMES[i].first }
        val names = nameOf.values.toList()
        fun marginOk(tag: String, id: BudgetId): Boolean {
            val rem = overview[id]?.month?.remaining ?: return true
            return if (rem.v < 0) shows(tag, money(Cents(-rem.v))) && shows(tag, "além") else shows(tag, money(rem)) && !shows(tag, "além")
        }

        open()
        openTransactions()
        click(TransactionTags.NEW)
        waitFor(TransactionTags.FORM_BUDGET)
        assertTrue("seed=$seed: vazio, \"Escolher a verba\"", shows(TransactionTags.FORM_BUDGET, "Escolher a verba"))
        var chosen = BudgetId(0)
        var step = 0
        for (round in 0 until 8) {
            click(TransactionTags.FORM_BUDGET)
            waitFor(TransactionTags.BUDGET_SHEET)
            val where = "seed=$seed round=$round"
            assertEquals("$where: busca vazia ao abrir", "", editable(TransactionTags.BUDGET_SEARCH))
            settle { shown(TransactionTags.BUDGET) == manualOrder.map { it.v.toString() } }
            assertEquals("$where: sem texto, todas na ordem manual", manualOrder.map { it.v.toString() }, shown(TransactionTags.BUDGET))
            for (id in manualOrder) {
                assertEquals("$where: ${nameOf[id]} selected", id == chosen, selected(TransactionTags.BUDGET + id.v))
                assertTrue("$where: ${nameOf[id]} mostra nome e margem: ${texts(TransactionTags.BUDGET + id.v)}", shows(TransactionTags.BUDGET + id.v, nameOf.getValue(id)) && marginOk(TransactionTags.BUDGET + id.v, id))
            }
            var last = ""
            repeat(r.nextInt(1, 4)) {
                // No edge spaces: the contract doesn't say the budget search trims (the tag search does).
                var text = searchText(r, names).trim()
                if (text.isEmpty()) text = names.random(r).take(2)
                last = text
                typeBudget(text)
                val expected = manualOrder.filter { fold(nameOf.getValue(it)).contains(fold(text)) }.map { it.v.toString() }
                settle { shown(TransactionTags.BUDGET) == expected }
                assertEquals("$where step=$step input=\"$text\": linhas da folha", expected, shown(TransactionTags.BUDGET))
                val rows = shown(TransactionTags.BUDGET)
                if (rows.isNotEmpty()) {
                    val lastRow = node(TransactionTags.BUDGET + rows.last())!!.boundsInRoot.top
                    assertTrue("$where step=$step input=\"$text\": Nova verba por último", node(TransactionTags.BUDGET_SHEET_NEW)!!.boundsInRoot.top > lastRow)
                } else {
                    assertTrue("$where step=$step input=\"$text\": Nova verba sempre", count(TransactionTags.BUDGET_SHEET_NEW) > 0)
                }
                step++
            }
            var rows = shown(TransactionTags.BUDGET)
            if (rows.isEmpty()) {
                typeBudget("")
                settle { shown(TransactionTags.BUDGET).size == manualOrder.size }
                rows = shown(TransactionTags.BUDGET)
            }
            val pick = BudgetId(rows.random(r).toLong())
            click(TransactionTags.BUDGET + pick.v)
            waitGone(TransactionTags.BUDGET_SHEET)
            chosen = pick
            val name = nameOf.getValue(pick)
            settle { shows(TransactionTags.FORM_BUDGET, name) }
            assertTrue("$where last=\"$last\": o campo mostra $name: ${texts(TransactionTags.FORM_BUDGET)}", shows(TransactionTags.FORM_BUDGET, name))
            assertTrue("$where: margem de $name no campo: ${texts(TransactionTags.FORM_BUDGET)}", marginOk(TransactionTags.FORM_BUDGET, pick))
            assertTrue("$where: escolhida, sem \"Escolher a verba\"", !shows(TransactionTags.FORM_BUDGET, "Escolher a verba"))
            assertTrue("$where: escolhida na folha não é sugerida", state(TransactionTags.FORM_BUDGET) != "sugerida")
        }
    }

    private companion object {
        const val BANK = "com.cartaoipe"
        val ZONE = TimeZone.of("America/Sao_Paulo")
        val OCT_1: LocalDate = LocalDate(2026, 10, 1)
        val OCT_9: LocalDate = LocalDate(2026, 10, 9)
        const val SETUP_SEED = 0x7A65L
        const val MANUAL_NAME = "Feira Fuzz"
        const val SUGGESTION_MERCHANT = "PADARIA DO ZE"

        /** The seeding transaction whose merchant the notice names: it carries the 3 most used tags. */
        const val SUGGESTION_K = 18

        /** Name and limit (cents) of each budget; Caçamba and Saude Bucal are small, so the seed overspends them. */
        val BUDGET_NAMES = listOf(
            "Lazer" to 50_000L, "Saúde" to 50_000L, "Educação" to 50_000L, "Alimentação" to 50_000L,
            "Café e Pão" to 50_000L, "Casa" to 50_000L, "Caçamba" to 1_000L, "Saude Bucal" to 2_000L,
        )

        /** Typed messy on purpose; no two fold to the same key. 22 tags, uses 0..21. */
        val TAG_INPUTS = listOf(
            "  Café ", "CAFETERIA", "café da manhã", "Açaí", "padaria", "Pão", "pão de queijo", "Mercado",
            "FARMÁCIA", "farmacia popular", "Ônibus", "uber", "bar", "Barbearia", "saúde", "academia",
            "Água", "luz", "festa junina", "presente", "sacolão", "caça",
        )

        /** Clean names that are no tag and fold apart from every tag and from each other. */
        val NEW_WORDS = listOf("pizzaria", "cinema", "livros", "pet shop", "viagem", "estacionamento", "fêira", "jantar")

        val PROBES = listOf("a", "e", "ca", "pa", "ã", "ç", "ao", "x", "zz", "de", " ")

        val ACCENTS = mapOf('a' to "áãâ", 'e' to "éê", 'i' to "í", 'o' to "óô", 'u' to "ú", 'c' to "ç")
    }
}
