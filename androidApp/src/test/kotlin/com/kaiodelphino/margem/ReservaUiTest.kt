package com.kaiodelphino.margem

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kaiodelphino.margem.ui.App
import com.kaiodelphino.margem.ui.BudgetTags
import com.kaiodelphino.margem.ui.InsightsTags
import com.kaiodelphino.margem.ui.LocalToday
import com.kaiodelphino.margem.ui.Screen
import com.kaiodelphino.margem.ui.TransactionTags
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.money
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
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
import kotlin.random.Random

/**
 * Margem honesta drawn on the screens (spec §2.13, §3, §4, §5, §7.1, §7.5; margem-plan M1–M13, §5.2; book 8.5,
 * 8.6, 8.10, 8.11, 10.1, 10.8; D68–D74): the Verbas header total fused with the teto (M9), the verba row's
 * TalkBack (M12), the detail's dek, "Previstos de outubro", "Soma = cota livre" and "cota atingida aqui" (M13),
 * and Leitura's "Ritmo do mês" sentence (variable − ideal, M6 projection, "além/dentro da cota" by the M1
 * margem). Blind contract tests (D74): written from the spec, the book and the tags' KDoc, before the
 * implementation; every expected number is computed by hand in a comment.
 *
 * Setup per test: a real Db (AndroidSQLiteDriver, D28) on a temporary "reserva-ui-*.db" (dropped with its
 * -wal/-shm), device and Db on TimeZone "America/Sao_Paulo", today fixed on Friday, Oct 9 2026 (book §10),
 * month 1–31, week from Monday (defaults), a tall screen. Data only through the public Db API: budgets,
 * lançamentos (addManual), previstos saved with a date after `today` (addManual, "Salvar previsto") and lançado
 * by closing their occurrence (addManual with `occurrence`, the "Lançar sem recado" / R6b path, D69: fixed).
 * Elements are found only by tags; texts are read in the unmerged tree (the node and below), with U+202F and
 * U+00A0 read as plain spaces (money() and the "4 out" dates put them; the spacing glyph is not the contract).
 *
 * Shared arithmetic (spec §4: passo = cota livre × dias da semana no mês ÷ dias do mês, rounded down, the last
 * automatic week takes the rest; §5: ideal = closed weeks + this week's step × days elapsed ÷ its days, rounded
 * down per week, KDoc BudgetMonth.idealByDay). October 2026: 31 days, weeks 1–4 (4 days), 5–11, 12–18, 19–25
 * (7), 26–31 (6); Oct 9 is day 5 of the week 5–11.
 * - Cota livre R$ 1.000,00 (cota 3.000 − previsto 2.000): 100000 ÷ 31 = 3225 r 25 → week 1 = 3225×4 + 25×4÷31
 *   = 12903; 7-day weeks = 22575 + 175÷31 = 22580; last = 100000 − 12903 − 3×22580 = 19357 (book 8.6:
 *   129,03 · 225,80 · 225,80 · 225,80 · 193,57). Ideal Oct 9 = 12903 + ⌊22580×5÷7⌋ = 12903 + 16128 = 29031.
 * - Cota livre R$ 900,00 (cota 3.000 − aluguel lançado 2.100): 90000 ÷ 31 = 2903 r 7 → week 1 = 11612 + 28÷31
 *   = 11612; 7-day = 20321 + 49÷31 = 20322. Ideal Oct 9 = 11612 + ⌊20322×5÷7⌋ = 11612 + 14515 = 26127.
 * - Cota R$ 3.000,00 without previsto: 300000 ÷ 31 = 9677 r 13 → week 1 = 38708 + 52÷31 = 38709; 7-day =
 *   67739 + 91÷31 = 67741. Ideal Oct 9 = 38709 + ⌊67741×5÷7⌋ = 38709 + 48386 = 87095.
 *
 * Robolectric does not see paint (dashed outline, tinta 3, the régua's order): checked on the emulator against
 * the book and the mock. What is asserted here is text, content descriptions, presence and order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp")
class ReservaUiTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private lateinit var db: Db
    private lateinit var path: String
    private lateinit var hostZone: java.util.TimeZone

    @Before
    fun setUp() {
        hostZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"))
        path = tmp.newFile("reserva-ui-${Random.nextLong().toULong()}.db").also { it.delete() }.path
        db = Db(AndroidSQLiteDriver(), path, timeZone = ZONE)
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

    private fun open(tab: Screen? = null) = compose.setContent {
        if (gone) return@setContent
        MargemTheme(dark = false) { CompositionLocalProvider(LocalToday provides { OCT_9 }) { App(db, tab) {} } }
    }

    // ---- Finding things ----

    private fun norm(s: String) = s.replace(' ', ' ').replace(' ', ' ')

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { count(tag) > 0 }

    private fun waitGone(tag: String) = compose.waitUntil(5_000) { count(tag) == 0 }

    private fun click(tag: String) {
        waitFor(tag)
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun node(tag: String): SemanticsNode? =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()

    private fun subtree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { subtree(it) }

    /** Every Text in the node with [tag] and below it, in tree order, spaces normalized. */
    private fun texts(tag: String): List<String> = node(tag)?.let { n ->
        subtree(n).flatMap { s -> s.config.getOrNull(SemanticsProperties.Text).orEmpty().map { norm(it.text) } }
    }.orEmpty()

    /** Substring of one text, or of the texts joined (a sentence split across pieces). */
    private fun shows(tag: String, s: String): Boolean {
        val t = texts(tag)
        val n = norm(s)
        return t.any { it.contains(n) } || t.joinToString("").contains(n) || t.joinToString(" ").contains(n)
    }

    private fun assertShows(tag: String, s: String) = assertTrue("$tag deveria mostrar \"${norm(s)}\": ${texts(tag)}", shows(tag, s))

    private fun assertNotShows(tag: String, s: String) = assertTrue("$tag não deveria mostrar \"${norm(s)}\": ${texts(tag)}", !shows(tag, s))

    /** The whole text of the node: one of its texts, or all of them joined, equals [s]. */
    private fun says(tag: String, s: String): Boolean {
        val t = texts(tag)
        val n = norm(s)
        return t.any { it == n } || t.joinToString("") == n || t.joinToString(" ") == n
    }

    private fun assertSays(tag: String, s: String) = assertTrue("$tag deveria dizer \"${norm(s)}\": ${texts(tag)}", says(tag, s))

    /** Content descriptions of the node with [tag]: merged (what TalkBack reads) and every node below it. */
    private fun descriptions(tag: String): List<String> {
        val merged = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        val below = node(tag)?.let { n -> subtree(n).flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } }.orEmpty()
        return (merged + below).map(::norm)
    }

    private fun assertDescribed(tag: String, s: String) =
        assertTrue("$tag deveria ser lido \"${norm(s)}\": ${descriptions(tag)}", descriptions(tag).any { it == norm(s) })

    private fun top(tag: String): Float = node(tag)!!.boundsInRoot.top

    private fun openDetail(budget: BudgetId) {
        click(BudgetTags.ROW + budget.v)
        waitFor(BudgetTags.DETAIL_HEADLINE)
    }

    // ---- Data, through the public Db API ----

    private fun budget(name: String, cents: Long, created: LocalDate = OCT_1): BudgetId =
        runBlocking { db.createBudget(name, Cents(cents), created) }.also { assertTrue("verba $name", it.v != 0L) }

    /** An ordinary lançamento (gasto variável). */
    private fun spend(budget: BudgetId, cents: Long, date: LocalDate, merchant: String, tag: String): TransactionId = runBlocking {
        db.addManual(TransactionForm(budget = budget, tags = listOf(tag), total = Cents(cents), installments = 1, merchant = merchant, date = date, time = LocalTime(12, 0)))
    }.transaction.also { assertTrue("lançamento $merchant", it.v != 0L) }

    /** Salvar previsto: a manual form dated after [today]; only the previsto, first occurrence on [date]. */
    private fun ahead(name: String, budget: BudgetId, tag: String, cents: Long, date: LocalDate, repeats: Repeats, today: LocalDate = OCT_9): PlannedId {
        val saved = runBlocking {
            db.addManual(TransactionForm(budget = budget, tags = listOf(tag), total = Cents(cents), installments = 1, merchant = name, date = date, time = LocalTime(9, 0), repeats = repeats), today)
        }
        assertTrue("previsto $name salvo", saved.planned.v != 0L)
        assertEquals("só o previsto", 0L, saved.transaction.v)
        return saved.planned
    }

    /** The open occurrence of the previsto [name] (late ones included: "A caminho" lists up to today + 10). */
    private fun occurrence(name: String): OccurrenceId = runBlocking { db.planned(OCT_9) }.first { it.name == name }.occurrence

    /** Lança the open occurrence of [name] at [cents] on [date], without a recado (R6b, D69: fixed). */
    private fun launch(name: String, budget: BudgetId, tag: String, cents: Long, date: LocalDate): Pair<OccurrenceId, TransactionId> {
        val occ = occurrence(name)
        val saved = runBlocking {
            db.addManual(
                TransactionForm(budget = budget, tags = listOf(tag), total = Cents(cents), installments = 1, merchant = name, date = date, time = LocalTime(9, 0), caption = name, occurrence = occ),
                OCT_9,
            )
        }
        assertTrue("lançar o previsto $name", saved.transaction.v != 0L)
        return occ to saved.transaction
    }

    /**
     * Book 8.5 / mock, on Oct 9: Casa, cota R$ 3.000,00, Aluguel previsto R$ 2.000,00 todo mês on day 10 (open,
     * not late: "esperando · todo mês"), R$ 40,00 of gasto variável on Oct 2.
     */
    private fun casaWithAluguel(spent: Long = 4000): BudgetId {
        val casa = budget("Casa", 300000)
        ahead("Aluguel", casa, "aluguel", 200000, OCT_10, Repeats.MONTHLY)
        if (spent > 0) spend(casa, spent, OCT_2, "Padaria do Zé", "padaria")
        return casa
    }

    // ---- Verbas header: the total fused with the teto (spec §7.1, M9, M11, M12; book 8.10; D73) ----

    /**
     * Book 8.10's example: cotas R$ 5.720,00 (Casa 3.000 + Mercado 2.720); gasto R$ 1.850,00 = 40 (Casa) + 1.200
     * (Mercado's "Feira do mês" previsto lançado) + 610 (Mercado); previstos em aberto R$ 2.300,00 = Aluguel 2.000
     * (Casa, Oct 10) + Clube 300 (Mercado, Oct 15). Margem = 5.720 − 1.850 − 2.300 = 1.570.
     */
    private fun bookTotal() {
        casaWithAluguel()
        val mercado = budget("Mercado", 272000)
        ahead("Feira do mês", mercado, "feira", 120000, OCT_4, Repeats.ONCE, today = OCT_1)
        launch("Feira do mês", mercado, "feira", 120000, OCT_4)
        ahead("Clube", mercado, "clube", 30000, OCT_15, Repeats.ONCE)
        spend(mercado, 61000, OCT_6, "Mercadinho Bom Preço", "mercado")
    }

    @Test
    fun `the header fuses the total of the month with the teto and opens it`() {
        bookTotal()
        runBlocking { db.ensureMonths(OCT); db.setCeiling(Cents(600000), onward = true, OCT_9) }
        open()
        waitFor(BudgetTags.CEILING)
        // M9/M12: "Margem R$ 1.570,00 de R$ 5.720,00" and "gasto 1.850 · previstos 2.300 · teto 6.000" (stepValue: whole reais).
        assertShows(BudgetTags.CEILING, "Margem ${money(Cents(157000))} de ${money(Cents(572000))}")
        assertShows(BudgetTags.CEILING, "gasto 1.850 · previstos 2.300 · teto 6.000")
        assertNotShows(BudgetTags.CEILING, "só outubro") // the month's teto is the default
        assertNotShows(BudgetTags.CEILING, "Pôr um teto")
        // Teto 6.000 − cotas 5.720 = 280.
        assertDescribed(
            BudgetTags.CEILING,
            "Outubro: margem de ${money(Cents(157000))} de ${money(Cents(572000))}. Gasto ${money(Cents(185000))}, previstos ${money(Cents(230000))}. " +
                "Teto ${money(Cents(600000))}, cotas a ${money(Cents(28000))} do teto. Tocar para mudar o teto.",
        )
        click(BudgetTags.CEILING)
        waitFor(BudgetTags.CEILING_DIALOG)
    }

    @Test
    fun `without a teto the total ends in por um teto`() {
        bookTotal()
        open()
        waitFor(BudgetTags.CEILING)
        assertShows(BudgetTags.CEILING, "Margem ${money(Cents(157000))} de ${money(Cents(572000))}")
        assertShows(BudgetTags.CEILING, "gasto 1.850 · previstos 2.300")
        assertShows(BudgetTags.CEILING, "Pôr um teto")
        assertNotShows(BudgetTags.CEILING, "teto 6.000")
        assertDescribed(
            BudgetTags.CEILING,
            "Outubro: margem de ${money(Cents(157000))} de ${money(Cents(572000))}. Gasto ${money(Cents(185000))}, previstos ${money(Cents(230000))}. " +
                "Sem teto. Tocar para pôr um teto.",
        )
        click(BudgetTags.CEILING)
        waitFor(BudgetTags.CEILING_DIALOG)
    }

    @Test
    fun `cotas past the teto and at the teto read so in the total`() {
        bookTotal()
        // Teto 5.420: cotas 5.720 pass it by 300 ("· + 300", whole reais).
        runBlocking { db.ensureMonths(OCT); db.setCeiling(Cents(542000), onward = true, OCT_9) }
        open()
        waitFor(BudgetTags.CEILING)
        assertShows(BudgetTags.CEILING, "gasto 1.850 · previstos 2.300 · teto 5.420")
        assertShows(BudgetTags.CEILING, "+ 300")
        assertDescribed(
            BudgetTags.CEILING,
            "Outubro: margem de ${money(Cents(157000))} de ${money(Cents(572000))}. Gasto ${money(Cents(185000))}, previstos ${money(Cents(230000))}. " +
                "Teto ${money(Cents(542000))}, cotas ${money(Cents(30000))} além do teto. Tocar para mudar o teto.",
        )

        // Teto = cotas = 5.720: "cotas fecham no teto".
        runBlocking { db.setCeiling(Cents(572000), onward = true, OCT_9) }
        val at = "Outubro: margem de ${money(Cents(157000))} de ${money(Cents(572000))}. Gasto ${money(Cents(185000))}, previstos ${money(Cents(230000))}. " +
            "Teto ${money(Cents(572000))}, cotas fecham no teto. Tocar para mudar o teto."
        compose.waitUntil(5_000) { descriptions(BudgetTags.CEILING).any { it == norm(at) } }
        assertShows(BudgetTags.CEILING, "gasto 1.850 · previstos 2.300 · teto 5.720")
        assertNotShows(BudgetTags.CEILING, "+ 300")
    }

    @Test
    fun `a month past its cotas reads alem in the total`() {
        // Casa alone: 3.000 − 1.100 − 2.000 (Aluguel open) = −100 → "R$ 100,00 além de R$ 3.000,00" (CEILING KDoc).
        casaWithAluguel(spent = 110000)
        open()
        waitFor(BudgetTags.CEILING)
        assertShows(BudgetTags.CEILING, "${money(Cents(10000))} além de ${money(Cents(300000))}")
        assertShows(BudgetTags.CEILING, "gasto 1.100 · previstos 2.000")
        assertShows(BudgetTags.CEILING, "Pôr um teto")
        assertNotShows(BudgetTags.CEILING, "Margem ${money(Cents(-10000))}")
    }

    // ---- The verba row (spec §7.1, M12; BudgetTags.ROW) ----

    @Test
    fun `the row says the open previsto in talkback and the reserved margem`() {
        val casa = casaWithAluguel()
        open()
        waitFor(BudgetTags.ROW + casa.v)
        // M1: 3.000 − 40 − 2.000 = 960. M3: variável 40 ≤ ideal of the cota livre 290,31 (29031) → No ritmo.
        assertShows(BudgetTags.ROW + casa.v, "margem ${money(Cents(96000))}")
        assertShows(BudgetTags.ROW + casa.v, "No ritmo")
        assertDescribed(
            BudgetTags.ROW + casa.v,
            "Casa, ${money(Cents(4000))} de ${money(Cents(300000))}, ${money(Cents(200000))} em previstos, no ritmo, margem de ${money(Cents(96000))}.",
        )
    }

    @Test
    fun `acima do ritmo of the cota livre and sem gasto read lowercase in the row`() {
        // Casa: variável 300 > ideal 290,31 → Acima do ritmo; margem 3.000 − 300 − 2.000 = 700 ≥ 0.
        val casa = casaWithAluguel(spent = 30000)
        // Lar: Condomínio R$ 2.000,00 open on Oct 10, nothing spent → sem gasto; margem 3.000 − 0 − 2.000 = 1.000.
        val lar = budget("Lar", 300000)
        ahead("Condomínio", lar, "condominio", 200000, OCT_10, Repeats.MONTHLY)
        open()
        waitFor(BudgetTags.ROW + casa.v)
        waitFor(BudgetTags.ROW + lar.v)
        assertShows(BudgetTags.ROW + casa.v, "Acima do ritmo")
        assertShows(BudgetTags.ROW + casa.v, "margem ${money(Cents(70000))}")
        assertDescribed(
            BudgetTags.ROW + casa.v,
            "Casa, ${money(Cents(30000))} de ${money(Cents(300000))}, ${money(Cents(200000))} em previstos, acima do ritmo, margem de ${money(Cents(70000))}.",
        )
        assertShows(BudgetTags.ROW + lar.v, "margem ${money(Cents(100000))}")
        assertDescribed(
            BudgetTags.ROW + lar.v,
            "Lar, ${money(Cents(0))} de ${money(Cents(300000))}, ${money(Cents(200000))} em previstos, sem gasto, margem de ${money(Cents(100000))}.",
        )

        // Detail dek, Acima do ritmo (DETAIL_HEADLINE KDoc): variable − ideal = 30000 − 29031 = 969.
        openDetail(casa)
        assertShows(BudgetTags.DETAIL_HEADLINE, money(Cents(70000)))
        assertShows(BudgetTags.DETAIL_HEADLINE, "Você lançou ${money(Cents(30000))} de ${money(Cents(300000))} e tem ${money(Cents(200000))} em previstos")
        assertShows(BudgetTags.DETAIL_HEADLINE, "${money(Cents(969))} acima do ritmo ideal de hoje.")
    }

    // ---- The detail (spec §7.1, M12; book 8.6, 10.8) ----

    @Test
    fun `the detail of casa reserves the aluguel apart from the passos`() {
        val casa = casaWithAluguel()
        val aluguel = occurrence("Aluguel")
        open()
        openDetail(casa)

        // Headline contains the margem (960); dek M12 with the ideal of the cota livre on Oct 9: R$ 290,31.
        assertShows(BudgetTags.DETAIL_HEADLINE, money(Cents(96000)))
        assertShows(
            BudgetTags.DETAIL_HEADLINE,
            "Você lançou ${money(Cents(4000))} de ${money(Cents(300000))} e tem ${money(Cents(200000))} em previstos. " +
                "No ritmo: o ideal da cota livre até hoje é ${money(Cents(29031))}.",
        )

        // "Previstos de outubro" (rubric, case ignored), between the meter and the steps.
        waitFor(BudgetTags.PLANNED_LINES)
        assertTrue(texts(BudgetTags.PLANNED_LINES).any { it.contains("Previstos de outubro", ignoreCase = true) })
        val line = BudgetTags.PLANNED_LINE + aluguel.v
        waitFor(line)
        assertShows(line, "Aluguel")
        assertShows(line, "esperando · todo mês")
        assertShows(line, money(Cents(200000)))
        assertShows(line, "10")
        assertNotShows(line, "lançado em")
        val firstWeek = BudgetTags.WEEK + OCT_1
        assertTrue("régua antes dos previstos", top(BudgetTags.DETAIL_METER) < top(BudgetTags.PLANNED_LINES))
        assertTrue("previstos antes dos passos", top(BudgetTags.PLANNED_LINES) < top(firstWeek))

        // Passos of the cota livre R$ 1.000,00: 129,03 · 225,80 · 225,80 · 225,80 · 193,57 (book 8.6).
        assertShows(firstWeek, "129,03")
        assertShows(BudgetTags.WEEK + OCT_5, "225,80")
        assertShows(BudgetTags.WEEK + OCT_26, "193,57")
        waitFor(BudgetTags.STEPS_SUM)
        assertShows(BudgetTags.STEPS_SUM, "Soma = cota livre")
        assertShows(BudgetTags.STEPS_SUM, "cota ${money(Cents(300000))} − previstos ${money(Cents(200000))}")
        assertShows(BudgetTags.STEPS_SUM, money(Cents(100000)))
        assertTrue("a soma vem depois da última semana", top(BudgetTags.WEEK + OCT_26) < top(BudgetTags.STEPS_SUM))

        // An open line opens the previsto sheet as everywhere (§2.13): Pular, Editar, Apagar.
        click(line)
        waitFor(TransactionTags.PLANNED_SHEET)
        assertShows(TransactionTags.PLANNED_SHEET, "Aluguel")
        assertNotShows(TransactionTags.PLANNED_SHEET, "Lançado em")
        assertEquals(1, count(TransactionTags.PLANNED_SKIP))
        assertEquals(1, count(TransactionTags.PLANNED_EDIT))
        assertEquals(1, count(TransactionTags.PLANNED_DELETE))
    }

    @Test
    fun `a lancado previsto counts at its value and is not said apart`() {
        // Aluguel R$ 2.000,00 on Oct 4, lançado at R$ 2.100,00 on Oct 4 (fixed, M4), plus R$ 40,00 variável.
        // fixed 2.100, open 0 → planned 2.100, cota livre 900, ideal 261,27 (26127); spent 2.140; margem 3.000 − 2.140 − 0 = 860.
        // Variável 40 ≤ 261,27 → No ritmo (the old rule, spent 2.140 > 870,95, would say Acima do ritmo).
        val casa = budget("Casa", 300000)
        ahead("Aluguel", casa, "aluguel", 200000, OCT_4, Repeats.MONTHLY, today = OCT_1)
        val (aluguel, _) = launch("Aluguel", casa, "aluguel", 210000, OCT_4)
        spend(casa, 4000, OCT_2, "Padaria do Zé", "padaria")
        open()
        waitFor(BudgetTags.ROW + casa.v)
        assertShows(BudgetTags.ROW + casa.v, "No ritmo")
        assertShows(BudgetTags.ROW + casa.v, "margem ${money(Cents(86000))}")
        // No open previsto: no "em previstos" part (a lançado one is already in the gasto).
        assertDescribed(BudgetTags.ROW + casa.v, "Casa, ${money(Cents(214000))} de ${money(Cents(300000))}, no ritmo, margem de ${money(Cents(86000))}.")

        openDetail(casa)
        assertShows(BudgetTags.DETAIL_HEADLINE, money(Cents(86000)))
        assertShows(BudgetTags.DETAIL_HEADLINE, "Você lançou ${money(Cents(214000))} de ${money(Cents(300000))}.")
        assertShows(BudgetTags.DETAIL_HEADLINE, "${money(Cents(26127))}.")
        assertNotShows(BudgetTags.DETAIL_HEADLINE, "em previstos")

        // planned 2.100 > 0: "Soma = cota livre", "cota R$ 3.000,00 − previstos R$ 2.100,00", R$ 900,00.
        waitFor(BudgetTags.STEPS_SUM)
        assertShows(BudgetTags.STEPS_SUM, "Soma = cota livre")
        assertShows(BudgetTags.STEPS_SUM, "cota ${money(Cents(300000))} − previstos ${money(Cents(210000))}")
        assertShows(BudgetTags.STEPS_SUM, money(Cents(90000)))

        // The lançada line: "lançado em 4 out", the lançado value.
        val line = BudgetTags.PLANNED_LINE + aluguel.v
        waitFor(line)
        assertShows(line, "Aluguel")
        assertShows(line, "lançado em 4 out")
        assertShows(line, money(Cents(210000)))
        assertNotShows(line, "esperando")

        // Its sheet: "Lançado em 4 out.", only Editar and Apagar previsto (2026-10-27).
        click(line)
        waitFor(TransactionTags.PLANNED_SHEET)
        assertShows(TransactionTags.PLANNED_SHEET, "Aluguel")
        assertShows(TransactionTags.PLANNED_SHEET, "Lançado em 4 out.")
        assertNotShows(TransactionTags.PLANNED_SHEET, "Esperando o recado")
        assertEquals(1, count(TransactionTags.PLANNED_EDIT))
        assertEquals(1, count(TransactionTags.PLANNED_DELETE))
        assertEquals("lançada: sem Pular", 0, count(TransactionTags.PLANNED_SKIP))
        assertEquals("lançada: sem Lançar sem recado", 0, count(TransactionTags.PLANNED_LAUNCH_NO_NOTICE))
    }

    @Test
    fun `without a previsto in the month the detail is as today`() {
        // Cota 3.000, gasto 40, nothing planned: margem 2.960, ideal 870,95 (87095), No ritmo.
        val casa = budget("Casa", 300000)
        spend(casa, 4000, OCT_2, "Padaria do Zé", "padaria")
        open()
        waitFor(BudgetTags.ROW + casa.v)
        assertDescribed(BudgetTags.ROW + casa.v, "Casa, ${money(Cents(4000))} de ${money(Cents(300000))}, no ritmo, margem de ${money(Cents(296000))}.")

        openDetail(casa)
        assertShows(BudgetTags.DETAIL_HEADLINE, money(Cents(296000)))
        assertShows(BudgetTags.DETAIL_HEADLINE, "Você lançou ${money(Cents(4000))} de ${money(Cents(300000))}.")
        assertShows(BudgetTags.DETAIL_HEADLINE, "${money(Cents(87095))}.")
        assertNotShows(BudgetTags.DETAIL_HEADLINE, "em previstos")
        waitFor(BudgetTags.STEPS_SUM)
        assertShows(BudgetTags.STEPS_SUM, "Soma = cota")
        assertShows(BudgetTags.STEPS_SUM, money(Cents(300000)))
        assertNotShows(BudgetTags.STEPS_SUM, "livre")
        assertNotShows(BudgetTags.STEPS_SUM, "previstos")
        assertEquals("sem previsto no mês, a seção some", 0, count(BudgetTags.PLANNED_LINES))
    }

    @Test
    fun `the cut falls where the margem reaches zero with the open previsto`() {
        // Book 10.8, Lazer on Oct 9: cota 465,00; Academia R$ 89,90 todo mês, day 5, open and late; lines
        // Arena Sul 315,00 (1) · Livraria Capitu 48,00 (3) · Cine Paraíso 56,00 (7) · Bar do Ponto 46,00 (8) ·
        // Banca Central 12,00 (9) → spent 477,00. Margem = 465 − 477 − 89,90 = −101,90 → Fora da margem.
        val lazer = budget("Lazer", 46500)
        ahead("Academia", lazer, "academia", 8990, OCT_5, Repeats.MONTHLY, today = OCT_1)
        val academia = occurrence("Academia")
        spend(lazer, 31500, OCT_1, "Arena Sul", "show")
        val livraria = spend(lazer, 4800, OCT_3, "Livraria Capitu", "livros")
        val cine = spend(lazer, 5600, OCT_7, "Cine Paraíso", "cinema")
        val bar = spend(lazer, 4600, OCT_8, "Bar do Ponto", "bar")
        spend(lazer, 1200, OCT_9, "Banca Central", "revistas")
        open()
        waitFor(BudgetTags.ROW + lazer.v)
        assertShows(BudgetTags.ROW + lazer.v, "Fora da margem")
        assertShows(BudgetTags.ROW + lazer.v, "${money(Cents(10190))} além")
        assertDescribed(
            BudgetTags.ROW + lazer.v,
            "Lazer, ${money(Cents(47700))} de ${money(Cents(46500))}, ${money(Cents(8990))} em previstos, fora da margem, ${money(Cents(10190))} além.",
        )

        openDetail(lazer)
        assertShows(BudgetTags.DETAIL_HEADLINE, "${money(Cents(10190))} além da cota.")
        assertShows(
            BudgetTags.DETAIL_HEADLINE,
            "Você lançou ${money(Cents(47700))} de ${money(Cents(46500))} e tem ${money(Cents(8990))} em previstos. Nada foi bloqueado. " +
                "O mês recomeça em 1 de novembro, com a cota inteira.",
        )

        val line = BudgetTags.PLANNED_LINE + academia.v
        waitFor(line)
        assertShows(line, "Academia")
        assertShows(line, "esperando desde 5 out")
        assertShows(line, money(Cents(8990)))

        // Cota livre 465 − 89,90 = 375,10 = 37510; 37510 ÷ 31 = 1210 r 0 → 48,40 · 84,70 · 84,70 · 84,70 · 72,60 (book 10.8).
        assertShows(BudgetTags.WEEK + OCT_1, "48,40")
        assertShows(BudgetTags.WEEK + OCT_12, "84,70")
        assertShows(BudgetTags.WEEK + OCT_26, "72,60")
        waitFor(BudgetTags.STEPS_SUM)
        assertShows(BudgetTags.STEPS_SUM, "Soma = cota livre")
        assertShows(BudgetTags.STEPS_SUM, "cota ${money(Cents(46500))} − previstos ${money(Cents(8990))}")
        assertShows(BudgetTags.STEPS_SUM, money(Cents(37510)))

        // M13: running sum + open (89,90) ≥ 465: 363 + 89,90 = 452,90 < 465; 419 + 89,90 = 508,90 ≥ 465 → after Cine Paraíso.
        waitFor(BudgetTags.CUT)
        assertEquals(1, count(BudgetTags.CUT))
        assertTrue("o corte vem depois de Livraria", top(BudgetTags.LINE + livraria.v) < top(BudgetTags.CUT))
        assertTrue("o corte vem depois de Cine Paraíso", top(BudgetTags.LINE + cine.v) < top(BudgetTags.CUT))
        assertTrue("o corte vem antes de Bar do Ponto", top(BudgetTags.CUT) < top(BudgetTags.LINE + bar.v))
        assertShows(BudgetTags.LINE_SUM + cine.v, "419,00")
    }

    // ---- Leitura, Ritmo do mês (spec §7.5, M6; InsightsTags.PACE_SENTENCE, PACE_CHART) ----

    @Test
    fun `leitura measures the variable gasto against the ideal of the cota livre`() {
        // Aluguel lançado R$ 2.100,00 on Oct 4 (fixed) + R$ 300,00 variável on Oct 8: cota livre 900, ideal 26127.
        // margem 3.000 − 2.400 = 600 ≥ 0; variável 30000 > 26127 → "+ R$ 38,73" (30000 − 26127 = 3873).
        // M6: 30000 × 90000 ÷ 26127 = 103341 (26127 × 103341 = 2.699.990.307) + planned 210000 = 313341 → R$ 3.133.
        val casa = budget("Casa", 300000)
        ahead("Aluguel", casa, "aluguel", 200000, OCT_4, Repeats.MONTHLY, today = OCT_1)
        launch("Aluguel", casa, "aluguel", 210000, OCT_4)
        spend(casa, 30000, OCT_8, "Bar do Ponto", "bar")
        open(Screen.Insights)
        waitFor(InsightsTags.PACE_SENTENCE)
        assertShows(InsightsTags.PACE_BUDGET, "Casa")
        assertSays(InsightsTags.PACE_SENTENCE, "+ ${money(Cents(3873))} sobre o ritmo ideal. No passo atual, o mês fecharia em R$ 3.133.")
        // The chart reads the gasto variável up to today (300,00, not the 2.400,00 of gasto) and the ideal of the cota livre.
        // Its "para uma cota de …" tail is left out (cota or cota livre: not said).
        val chart = "Ritmo do mês de Casa: ${money(Cents(30000))} até o dia 9, contra ritmo ideal de ${money(Cents(26127))}; no passo atual, R$ 3.133 no fim do mês"
        assertTrue("${descriptions(InsightsTags.PACE_CHART)}", descriptions(InsightsTags.PACE_CHART).any { it.startsWith(norm(chart)) })
    }

    @Test
    fun `leitura below the ideal of the cota livre projects the previstos`() {
        // Aluguel R$ 2.000,00 open (Oct 10) + R$ 50,00 variável: ideal 29031; 29031 − 5000 = 24031 abaixo.
        // M6: 5000 × 100000 ÷ 29031 = 17222 (29031 × 17222 = 499.971.882) + planned 200000 = 217222 → R$ 2.172.
        casaWithAluguel(spent = 5000)
        open(Screen.Insights)
        waitFor(InsightsTags.PACE_SENTENCE)
        assertSays(InsightsTags.PACE_SENTENCE, "${money(Cents(24031))} abaixo do ritmo ideal. No passo atual, o mês fecharia em R$ 2.172.")
    }

    @Test
    fun `leitura says alem da cota by the reserve of the open previsto`() {
        // Aluguel R$ 2.000,00 open + R$ 1.100,00 variável: margem 3.000 − 1.100 − 2.000 = −100 → "R$ 100,00 além da cota."
        // M6: 110000 × 100000 ÷ 29031 = 378905 (29031 × 378905 = 10.999.991.055) + 200000 = 578905 → R$ 5.789.
        casaWithAluguel(spent = 110000)
        open(Screen.Insights)
        waitFor(InsightsTags.PACE_SENTENCE)
        assertSays(InsightsTags.PACE_SENTENCE, "${money(Cents(10000))} além da cota. No passo atual, o mês fecharia em R$ 5.789.")
    }

    /**
     * September: Casa (cota R$ 3.000,00 since Sep 1) with an Aluguel R$ 2.000,00 "Só uma vez" on Sep 10 that was
     * never lançado (open and late: it keeps reserving in its own month, M8) and [spent] of gasto on Sep 15.
     */
    private fun september(spent: Long) {
        val casa = budget("Casa", 300000, created = SEP_1)
        ahead("Aluguel", casa, "aluguel", 200000, SEP_10, Repeats.ONCE, today = SEP_1)
        spend(casa, spent, SEP_15, "Mercadinho Bom Preço", "mercado")
        open(Screen.Insights)
        waitFor(InsightsTags.MONTH_PREV)
        click(InsightsTags.MONTH_PREV)
        compose.waitUntil(5_000) { shows(InsightsTags.MONTH_LABEL, "setembro de 2026") }
        waitFor(InsightsTags.PACE_SENTENCE)
    }

    @Test
    fun `a past month closes dentro da cota by the margem with the open previsto`() {
        // 3.000 − 900 − 2.000 = 100 → "R$ 100,00 dentro da cota." (old rule: 2.100,00); "Fechou em" = the gasto, 900,00.
        september(90000)
        compose.waitUntil(5_000) { shows(InsightsTags.PACE_SENTENCE, "Fechou em") }
        assertSays(InsightsTags.PACE_SENTENCE, "Fechou em ${money(Cents(90000))}, ${money(Cents(10000))} dentro da cota.")
    }

    @Test
    fun `a past month at margem zero closes exatamente a cota`() {
        // 3.000 − 1.000 − 2.000 = 0 → "exatamente a cota".
        september(100000)
        compose.waitUntil(5_000) { shows(InsightsTags.PACE_SENTENCE, "Fechou em") }
        assertSays(InsightsTags.PACE_SENTENCE, "Fechou em ${money(Cents(100000))}, exatamente a cota.")
    }

    @Test
    fun `a past month closes alem da cota by the open previsto`() {
        // 3.000 − 1.100 − 2.000 = −100 → "R$ 100,00 além da cota." (old rule: 1.900,00 dentro).
        september(110000)
        compose.waitUntil(5_000) { shows(InsightsTags.PACE_SENTENCE, "Fechou em") }
        assertSays(InsightsTags.PACE_SENTENCE, "Fechou em ${money(Cents(110000))}, ${money(Cents(10000))} além da cota.")
    }

    private companion object {
        val ZONE = TimeZone.of("America/Sao_Paulo")
        val OCT = YearMonth(2026, 10)
        val SEP_1: LocalDate = LocalDate(2026, 9, 1)
        val SEP_10: LocalDate = LocalDate(2026, 9, 10)
        val SEP_15: LocalDate = LocalDate(2026, 9, 15)
        val OCT_1: LocalDate = LocalDate(2026, 10, 1)
        val OCT_2: LocalDate = LocalDate(2026, 10, 2)
        val OCT_3: LocalDate = LocalDate(2026, 10, 3)
        val OCT_4: LocalDate = LocalDate(2026, 10, 4)
        val OCT_5: LocalDate = LocalDate(2026, 10, 5)
        val OCT_6: LocalDate = LocalDate(2026, 10, 6)
        val OCT_7: LocalDate = LocalDate(2026, 10, 7)
        val OCT_8: LocalDate = LocalDate(2026, 10, 8)
        val OCT_9: LocalDate = LocalDate(2026, 10, 9)
        val OCT_10: LocalDate = LocalDate(2026, 10, 10)
        val OCT_12: LocalDate = LocalDate(2026, 10, 12)
        val OCT_15: LocalDate = LocalDate(2026, 10, 15)
        val OCT_26: LocalDate = LocalDate(2026, 10, 26)
    }
}
