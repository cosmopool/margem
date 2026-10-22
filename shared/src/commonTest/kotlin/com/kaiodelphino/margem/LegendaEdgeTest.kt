package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Edge cases of the legenda do lançamento (spec §2.12, D52–D55), through the public API only: the
 * 80-unit limit of accept, addManual and edit, the cleaning of TransactionForm.caption, removing on
 * edit (the §2.10 contrast with the apelido), deleteTransaction, suggestion, the Leitura reads,
 * refundCandidates and the migration of a v1 database (open and importFrom).
 * Expected values come from spec §2.5, §2.8, §2.10, §2.12, §7.3–§7.5, architecture D52–D55 and the
 * KDoc of the contract, computed by hand.
 *
 * Cleaning (KDoc of TransactionForm.caption): every line break (LF, CRLF, CR, U+2028, U+2029)
 * becomes one space, the edges are trimmed, the text goes through `storable` (U+FEFF and friends
 * become U+FFFD, one unit for one unit) and whitespace-only becomes "" (no legenda). The limit is
 * counted after cleaning, by `String.length` (D55): UTF-16 units — an astral emoji is 2, an NFD
 * "é" is 2 while the NFC one is 1.
 */
class LegendaEdgeTest {

    private fun withDb(block: suspend (Db) -> Unit): TestResult = runTest(timeout = 60.seconds) {
        val path = Path(SystemTemporaryDirectory, "margem-legenda-${Random.nextLong().toULong()}.db").toString()
        val db = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))
        try {
            db.addSource(NU, "Nubank", 0)
            block(db)
        } finally {
            db.close()
            for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
        }
    }

    private fun day(y: Int, m: Int, d: Int) = LocalDate(y, m, d)
    private fun ym(y: Int, m: Int) = YearMonth(y, m)

    /** Captures notice [n] (its own key, posted [n] minutes after T) and returns its id. */
    private suspend fun Db.post(n: Int, text: String): NoticeId {
        val at = T + n * MIN
        val key = "legenda-edge|$n"
        assertTrue(capture(this, RawNotice(packageName = NU, appLabel = "Nubank", key = key, postedAt = at, whenMs = at, title = "Nubank", text = text, bigText = text)), "capture $n")
        return pending().single { it.raw.key == key }.id
    }

    private suspend fun Db.purchase(n: Int, cents: String = "31,50", merchant: String = "LOJA X") =
        post(n, "Compra de R$ $cents APROVADA em $merchant para o cartão com final 1234.")

    private suspend fun Db.estorno(n: Int, cents: String = "31,50", merchant: String = "LOJA X") =
        post(n, "A compra em $merchant no valor de R$ $cents foi estornada no seu cartão.")

    private suspend fun Db.budget(name: String, limit: Long, today: LocalDate = TODAY): BudgetId =
        createBudget(name, Cents(limit), today).also { assertNotEquals(0L, it.v, "createBudget $name") }

    private fun form(
        budget: BudgetId, total: Long, installments: Int = 1, date: LocalDate = OCT_6, merchant: String = "LOJA X",
        time: LocalTime = NOON, tags: List<String> = listOf("teste"), alias: String = "", caption: String = "",
    ) = TransactionForm(budget, tags, Cents(total), installments, merchant, date, time, alias, caption)

    /** The form of exactly what was read from the notice. */
    private suspend fun Db.asRead(id: NoticeId, budget: BudgetId, installments: Int = 1, tags: List<String> = listOf("teste")): TransactionForm {
        val p = notice(id).parsed
        return TransactionForm(budget, tags, p.cents, installments, p.merchant, p.date, p.time)
    }

    // ---- Limite: 80 unidades UTF-16 (spec §2.12, D53, D55) ----

    @Test
    fun `80 unidades cabem na legenda e 81 recusam o formulário inteiro`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val id = db.purchase(1)
        val read = db.asRead(id, b)

        val tx = db.accept(id, read.copy(caption = A80))
        assertNotEquals(TransactionId(0), tx, "accept 80")
        assertEquals(A80, db.transaction(tx).row.caption)
        assertEquals(80, db.transaction(tx).row.caption.length)
        val manual = db.addManual(form(b, 500, caption = A80)).transaction
        assertNotEquals(TransactionId(0), manual, "addManual 80")
        assertEquals(A80, db.transaction(manual).row.caption)
        assertTrue(db.edit(manual, form(b, 500, caption = "b".repeat(80))), "edit 80")
        assertEquals("b".repeat(80), db.transaction(manual).row.caption)

        // 81: the whole form is refused, nothing is written (D53).
        val id2 = db.purchase(2)
        val read2 = db.asRead(id2, b)
        val version = db.version.value
        assertEquals(TransactionId(0), db.accept(id2, read2.copy(caption = A81)), "accept 81")
        assertEquals(TransactionId(0), db.addManual(form(b, 500, caption = A81)).transaction, "addManual 81")
        assertEquals(version, db.version.value, "a refused write writes nothing")
        assertEquals(listOf(id2), db.pending().map { it.id }, "the notice is untouched")
        assertEquals(2, db.transactions().size)

        val before = db.transaction(manual).row
        assertFalse(db.edit(manual, form(b, 500, caption = A81)), "edit 81")
        assertEquals(before, db.transaction(manual).row, "a refused edit keeps the previous state")
    }

    @Test
    fun `a contagem é depois da limpeza - 81 crus que viram 80 cabem e 81 limpos não`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val id = db.purchase(1)
        val read = db.asRead(id, b)

        // Raw units over 80 that clean to exactly the 80 a's are accepted.
        val padded = " $A80"
        assertEquals(81, padded.length)
        val tx = db.accept(id, read.copy(caption = padded))
        assertNotEquals(TransactionId(0), tx, "accept 81 raw → 80 clean")
        assertEquals(A80, db.transaction(tx).row.caption)
        val manual = db.addManual(form(b, 500, caption = "$A80\t")).transaction
        assertNotEquals(TransactionId(0), manual, "addManual 84 raw → 80 clean")
        assertEquals(A80, db.transaction(manual).row.caption)
        assertTrue(db.edit(tx, read.copy(caption = "  $A80 \n")), "edit raw → 80 clean")
        assertEquals(A80, db.transaction(tx).row.caption)

        // 82 raw units that clean to 81 ("a"×79 + " xy"): refused everywhere.
        val over = "a".repeat(79) + "\nxy"
        assertEquals(82, over.length)
        val id2 = db.purchase(2)
        val version = db.version.value
        assertEquals(TransactionId(0), db.accept(id2, db.asRead(id2, b).copy(caption = over)), "accept 81 clean")
        assertEquals(TransactionId(0), db.addManual(form(b, 500, caption = over)).transaction, "addManual 81 clean")
        assertEquals(version, db.version.value, "a refused write writes nothing")
        val before = db.transaction(tx).row
        assertFalse(db.edit(tx, read.copy(caption = over)), "edit 81 clean")
        assertEquals(before, db.transaction(tx).row, "a refused edit keeps the previous state")
    }

    @Test
    fun `emoji astral ocupa duas casas das oitenta`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val emoji = "\uD83D\uDE00" // 😀: one code point in two UTF-16 units
        val forty = emoji.repeat(40)
        assertEquals(80, forty.length)

        val tx = db.addManual(form(b, 500, caption = forty)).transaction
        assertNotEquals(TransactionId(0), tx, "40 emojis = 80 units")
        assertEquals(forty, db.transaction(tx).row.caption)
        assertEquals(80, db.transaction(tx).row.caption.length)

        assertEquals(TransactionId(0), db.addManual(form(b, 500, caption = emoji.repeat(41))).transaction, "41 emojis = 82 units")
        assertFalse(db.edit(tx, form(b, 500, caption = emoji.repeat(41))), "41 emojis = 82 units")
        assertEquals(forty, db.transaction(tx).row.caption)
    }

    @Test
    fun `a contagem é o comprimento UTF-16 e não os code points - NFD tem uma casa a mais`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val nfc = "\u00E9"  // é: one unit
        val nfd = "e\u0301" // é decomposto: two units
        assertEquals(1, nfc.length)
        assertEquals(2, nfd.length)
        val withNfc = "a".repeat(78) + nfc + "b" // 80 units and 80 code points
        val withNfd = "a".repeat(78) + nfd + "b" // 81 units and 80 code points
        assertEquals(80, withNfc.length)
        assertEquals(81, withNfd.length)

        val tx = db.addManual(form(b, 500, caption = withNfc)).transaction
        assertNotEquals(TransactionId(0), tx, "80 units")
        assertEquals(withNfc, db.transaction(tx).row.caption)
        assertEquals(TransactionId(0), db.addManual(form(b, 500, caption = withNfd)).transaction, "81 units in 80 code points")
    }

    // ---- Limpeza (KDoc of TransactionForm.caption) ----

    @Test
    fun `espaços nas pontas saem e o que se lê é o texto limpo`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val tx = db.addManual(form(b, 500, caption = " \t Bolo da Ana \n ")).transaction
        assertNotEquals(TransactionId(0), tx)
        assertEquals("Bolo da Ana", db.transaction(tx).row.caption)

        val id = db.purchase(1)
        val accepted = db.accept(id, db.asRead(id, b).copy(caption = "   Bolo   "))
        assertNotEquals(TransactionId(0), accepted)
        assertEquals("Bolo", db.transaction(accepted).row.caption)
    }

    @Test
    fun `só espaços tabulação ou quebra de linha não tem legenda`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        for ((i, blank) in listOf("", " ", "\t", "\n", " \t\n ", "   ").withIndex()) {
            val tx = db.addManual(form(b, 500, merchant = "LOJA $i", caption = blank)).transaction
            assertNotEquals(TransactionId(0), tx, "blank '$blank'")
            val row = db.transaction(tx).row
            assertEquals("", row.caption, "blank '$blank'")
            assertEquals(row.name, row.title, "blank '$blank': the title is the name")
        }
    }

    @Test
    fun `toda quebra de linha vira um espaço`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val cases = listOf(
            "a\nb" to "a b",             // LF
            "a\r\nb" to "a b",           // CRLF: one break, one space
            "a\rb" to "a b",             // CR
            "a\u2028b" to "a b",         // line separator
            "a\u2029b" to "a b",         // paragraph separator
            "Bolo\nda Ana\r\ncom leite" to "Bolo da Ana com leite",
        )
        for ((i, case) in cases.withIndex()) {
            val (raw, clean) = case
            val tx = db.addManual(form(b, 500, merchant = "LOJA $i", caption = raw)).transaction
            assertNotEquals(TransactionId(0), tx, "raw $i")
            assertEquals(clean, db.transaction(tx).row.caption, "raw $i")
        }
    }

    @Test
    fun `o storable troca U+FEFF por U+FFFD sem mudar o comprimento`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val bom = "\uFEFF"
        val ok = "a".repeat(39) + bom + "a".repeat(40) // 80 units with a U+FEFF inside
        assertEquals(80, ok.length)
        val tx = db.addManual(form(b, 500, caption = ok)).transaction
        assertNotEquals(TransactionId(0), tx, "80 units with a U+FEFF inside")
        val back = db.transaction(tx).row.caption
        assertEquals("a".repeat(39) + "\uFFFD" + "a".repeat(40), back)
        assertEquals(80, back.length, "storable replaces one unit with one unit")

        // The replacement keeps the count honest: 81 units with a U+FEFF inside stay 81 and are refused.
        assertEquals(TransactionId(0), db.addManual(form(b, 500, caption = "a".repeat(40) + bom + "a".repeat(40))).transaction)
    }

    // ---- Edição (spec §2.10, §2.12) ----

    @Test
    fun `apagar o texto tira a legenda e o apelido em branco não tira o apelido`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val n = db.purchase(1, "12,00", "PADARIA DO ZE")
        val read = db.asRead(n, b)
        val tx = db.accept(n, read.copy(caption = "Bolo da Ana", alias = "Padaria do Zé"))
        assertNotEquals(TransactionId(0), tx)
        val row = db.transaction(tx).row
        assertEquals("Bolo da Ana" to "Padaria do Zé", row.caption to row.name)
        assertEquals("Bolo da Ana", row.title)

        // A blank alias leaves the alias as it is (§2.10).
        assertTrue(db.edit(tx, read.copy(caption = "Bolo da Ana", alias = " ")))
        assertEquals("Padaria do Zé", db.transaction(tx).row.name)

        // A blank caption removes the legenda (unlike the alias).
        assertTrue(db.edit(tx, read.copy(caption = "")))
        val gone = db.transaction(tx).row
        assertEquals("", gone.caption)
        assertEquals("Padaria do Zé", gone.name)
        assertEquals(gone.name, gone.title)

        // Whitespace-only removes it too: só espaços = sem legenda.
        val manual = db.addManual(form(b, 500, caption = "Bolo")).transaction
        assertTrue(db.edit(manual, form(b, 500, caption = "   ")))
        assertEquals("", db.transaction(manual).row.caption)
    }

    @Test
    fun `mudar só a legenda não é emenda`() = withDb { db ->
        val b = db.budget("Casa", 60000)
        val cases = listOf<Pair<String, (TransactionForm) -> TransactionForm>>(
            "as read" to { it },
            "caption only" to { it.copy(caption = "Bolo da Ana") },
            "caption and total" to { it.copy(caption = "Bolo da Ana", total = Cents(it.total.v + 1)) },
            "caption and merchant" to { it.copy(caption = "Bolo da Ana", merchant = it.merchant + " X") },
        )
        val expected = mapOf(
            "as read" to false, "caption only" to false, "caption and total" to true, "caption and merchant" to true,
        )
        for ((i, case) in cases.withIndex()) {
            val (name, change) = case
            val id = db.purchase(i + 1) // same text as the others: same Parsed except its own posting time
            val tx = db.accept(id, change(db.asRead(id, b)))
            assertNotEquals(TransactionId(0), tx, name)
            assertEquals(expected.getValue(name), db.transaction(tx).row.corrected, name)

            // edit recomputes it against the notice's Parsed: a caption-only edit is never an emenda.
            assertTrue(db.edit(tx, db.asRead(id, b).copy(caption = "Outra legenda")))
            assertFalse(db.transaction(tx).row.corrected, "$name edited with a caption only")
        }

        val manual = db.addManual(form(b, 500, caption = "Bolo")).transaction
        assertFalse(db.transaction(manual).row.corrected, "manual with a legenda")
        assertTrue(db.edit(manual, form(b, 500, caption = "Outro bolo")))
        assertFalse(db.transaction(manual).row.corrected, "manual edited with a legenda")
    }

    // ---- Apagar (spec §2.8, §2.12) ----

    @Test
    fun `apagar o lançamento perde a legenda e o recado volta à Pauta sem ela`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        val n = db.purchase(1)
        val read = db.asRead(n, b)
        val tx = db.accept(n, read.copy(caption = "Bolo da Ana"))
        assertNotEquals(TransactionId(0), tx)

        db.deleteTransaction(tx)
        assertEquals(TransactionDetail(), db.transaction(tx))
        assertEquals(listOf(n), db.pending().map { it.id }, "the notice is pending again")

        // Relançado a partir do recado: vem sem legenda (recados não têm uma).
        val again = db.accept(n, db.asRead(n, b))
        assertNotEquals(TransactionId(0), again)
        val row = db.transaction(again).row
        assertEquals("", row.caption)
        assertEquals(row.name, row.title)

        // A manual one simply goes.
        val manual = db.addManual(form(b, 500, caption = "Bolo")).transaction
        db.deleteTransaction(manual)
        assertTrue(db.transactions().none { it.id == manual })
        assertEquals(TransactionDetail(), db.transaction(manual))
    }

    // ---- Sugestão (spec §2.5, §2.12) ----

    @Test
    fun `suggestion nunca vem com legenda`() = withDb { db ->
        val b = db.budget("Mercado", 60000)
        assertNotEquals(TransactionId(0), db.addManual(form(b, 500, date = OCT_6, merchant = "LOJA X", tags = listOf("feira"), caption = "Bolo antigo")).transaction)
        // The last lançamento of the merchant has a legenda: the suggestion still is verba e marcadores.
        assertNotEquals(TransactionId(0), db.addManual(form(b, 700, date = OCT_10, merchant = "LOJA X", tags = listOf("feira"), caption = "Bolo da Ana")).transaction)
        assertEquals(Suggestion(b, listOf("feira")), db.suggestion("LOJA X"))

        assertNotEquals(TransactionId(0), db.addManual(form(b, 900, date = OCT_10, merchant = "LOJA Y", tags = listOf("feira"))).transaction)
        assertEquals(db.suggestion("LOJA Y"), db.suggestion("LOJA X"), "the legenda changes nothing")
    }

    // ---- Leitura (spec §7.5: a legenda não entra) ----

    @Test
    fun `merchantRanking tagRanking e history são os de um lançamento idêntico sem legenda`() = withDb { db ->
        val b = db.budget("Casa", 90000)
        fun of(caption: String) = form(b, 3000, date = OCT_6, merchant = "PADARIA DO ZE", tags = listOf("feira"), alias = "Padaria do Zé", caption = caption)
        val with = db.addManual(of("Bolo da Ana")).transaction
        val without = db.addManual(of("")).transaction
        assertNotEquals(TransactionId(0), with)
        assertNotEquals(TransactionId(0), without)

        val oct1 = day(2026, 10, 1)
        val oct31 = day(2026, 10, 31)
        suspend fun reads() = Triple(db.merchantRanking(oct1, oct31), db.tagRanking(oct1, oct31), db.history(ym(2026, 10), ym(2026, 10)))

        // Two identical lançamentos differing only in the legenda: one line, named without it.
        assertEquals(listOf(RankRow("Padaria do Zé", Cents(6000), 2)), reads().first)
        assertEquals(listOf(RankRow("feira", Cents(6000), 2)), reads().second)
        assertEquals(listOf(HistoryRow(b, "Casa", ym(2026, 10), Cents(90000), Cents(6000))), reads().third)

        val before = reads()
        assertTrue(db.edit(with, of("")))
        assertEquals(before, reads(), "removing the legenda changes no reading")
    }

    // ---- Escolha da compra estornada (spec §7.3) ----

    @Test
    fun `refundCandidates carrega legenda apelido e texto do banco e o recado pendente tem legenda vazia`() = withDb { db ->
        val b = db.budget("Casa", 60000)
        val n = db.purchase(1, "12,00", "PADARIA DO ZE")
        val tx = db.accept(n, db.asRead(n, b).copy(caption = "Bolo da Ana", alias = "Padaria do Zé"))
        assertNotEquals(TransactionId(0), tx)
        val pend = db.purchase(2, "20,00", "FEIRA DA ESQUINA")
        val e = db.estorno(3, "12,00", "PADARIA DO ZE")

        val candidates = db.refundCandidates(e)
        val t = candidates.single { it.transaction == tx }
        assertEquals("Bolo da Ana" to "Padaria do Zé", t.caption to t.name)
        assertEquals("Padaria do Zé" to "PADARIA DO ZE", t.alias to t.merchant)
        assertEquals("Bolo da Ana", t.title)

        val p = candidates.single { it.notice == pend }
        assertEquals("", p.caption, "recados não têm legenda")
        assertEquals("" to "FEIRA DA ESQUINA", p.alias to p.merchant)
        assertEquals("FEIRA DA ESQUINA", p.name)
        assertEquals(p.name, p.title)
    }

    // ---- Migração (D52): um banco v1 lê sem legenda e os dados ficam intactos ----

    @Test
    fun `banco v1 migrado e restaurado por importFrom mantém os dados e lê sem legenda`() = runTest(timeout = 60.seconds) {
        val path = Path(SystemTemporaryDirectory, "margem-legenda-${Random.nextLong().toULong()}.db").toString()
        val a = "$path.v1a"
        val b = "$path.v1b"
        try {
            // (a) opened by the Db: the migration fills the new columns and nothing else moves.
            writeV1(a)
            var db = Db(BundledSQLiteDriver(), a, timeZone = TimeZone.of("America/Sao_Paulo"))
            assertV1Intact(db, "aberto")
            db.close()
            db = Db(BundledSQLiteDriver(), a, timeZone = TimeZone.of("America/Sao_Paulo")) // reopening is idempotent
            assertV1Intact(db, "reaberto")
            val tx = db.addManual(form(BudgetId(1), 900, caption = "Bolo da Ana")).transaction
            assertNotEquals(TransactionId(0), tx, "the migrated database writes the legenda")
            assertEquals("Bolo da Ana", db.transaction(tx).row.caption)
            db.close()

            // (b) restored with importFrom: the same reads, and restoring again changes nothing.
            writeV1(b)
            val live = Db(BundledSQLiteDriver(), path, timeZone = TimeZone.of("America/Sao_Paulo"))
            assertTrue(live.importFrom(b), "a v1 backup imports")
            assertV1Intact(live, "importado")
            assertTrue(live.importFrom(b), "importing it again")
            assertV1Intact(live, "importado de novo")
            live.close()
        } finally {
            for (p in listOf(path, a, b)) {
                for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(p + suffix), mustExist = false)
            }
        }
    }

    /** A Margem v1 database at [file] (DDL of SCHEMA_V1) with one lançamento and one pending recado. */
    private fun writeV1(file: String) {
        val c = BundledSQLiteDriver().open(file)
        for (ddl in V1_DDL) c.execSQL(ddl)
        c.exec("INSERT INTO settings(key, value) VALUES ('month_start_day', '1')")
        c.exec("INSERT INTO sources(package, label, added_at) VALUES (?, ?, ?)", NU, "Nubank", 0L)
        c.exec(
            "INSERT INTO notices(id, package, app_label, key, posted_at, when_ms, title, text, big_text, sub_text, text_lines, " +
                "category, channel_id, extras_json, content_hash, dismissed) VALUES (1, ?, 'Nubank', 'legenda-v1|1', ?, ?, 'Nubank', ?, '', '', '', '', '', '{}', 'old', 0)",
            NU, T, T, PURCHASE_TEXT,
        )
        c.exec(
            "INSERT INTO notices(id, package, app_label, key, posted_at, when_ms, title, text, big_text, sub_text, text_lines, " +
                "category, channel_id, extras_json, content_hash, dismissed) VALUES (2, ?, 'Nubank', 'legenda-v1|2', ?, ?, 'Nubank', ?, '', '', '', '', '', '{}', 'old', 0)",
            NU, T + MIN, T + MIN, PURCHASE_TEXT_2,
        )
        c.exec("INSERT INTO budgets(id, name, position) VALUES (1, 'Mercado', 0)")
        c.exec("INSERT INTO budget_months(budget_id, month, limit_cents) VALUES (1, '2026-10', 60000)")
        c.exec(
            "INSERT INTO transactions(id, notice_id, budget_id, merchant, merchant_key, date, time, total_cents, corrected) " +
                "VALUES (1, 1, 1, 'PADARIA DO ZE', 'padariadoze', '2026-10-06', '12:00', 3150, 0)",
        )
        c.exec("INSERT INTO installments(transaction_id, k, date, cents) VALUES (1, 1, '2026-10-06', 3150)")
        c.exec("INSERT INTO tags(id, name) VALUES (1, 'feira')")
        c.exec("INSERT INTO transaction_tags(transaction_id, tag_id) VALUES (1, 1)")
        c.exec("INSERT INTO merchant_aliases(merchant_key, alias) VALUES ('padariadoze', 'Padaria do Zé')")
        c.execSQL("PRAGMA user_version = 1")
        c.close()
    }

    /** What a v1 database must still give after the migration: the rows intact, no legenda anywhere. */
    private suspend fun assertV1Intact(db: Db, tag: String) {
        val rows = db.transactions()
        assertEquals(1, rows.size, tag)
        val row = rows.single()
        assertEquals(TransactionId(1), row.id, tag)
        assertEquals(NoticeId(1) to BudgetId(1), row.notice to row.budget, tag)
        assertEquals("Mercado" to "PADARIA DO ZE", row.budgetName to row.merchant, tag)
        assertEquals("Padaria do Zé" to "Padaria do Zé", row.alias to row.name, tag)
        assertEquals(Cents(3150) to 1, row.total to row.installments, tag)
        assertEquals(OCT_6 to NOON, row.date to row.time, tag)
        assertEquals(listOf("feira"), row.tags, tag)
        assertFalse(row.corrected, tag)
        assertEquals(NoticeId(0), row.estorno, tag)
        // D52: the rows of before the legenda read as none.
        assertEquals("", row.caption, tag)
        assertEquals(row.name, row.title, tag)

        assertEquals(NoticeStatus.PROCESSED, db.notice(NoticeId(1)).status, tag)
        assertEquals(NoticeStatus.PENDING, db.notice(NoticeId(2)).status, tag)
        assertEquals("Padaria do Zé", db.notice(NoticeId(1)).alias, tag)
        assertEquals(listOf(NoticeId(2)), db.pending().map { it.id }, tag)

        db.ensureMonths(ym(2026, 10))
        val detail = db.budgetDetail(BudgetId(1), ym(2026, 10), TODAY)
        assertEquals(Cents(3150), detail.month.spent, tag)
        assertEquals(listOf(TransactionId(1)), detail.lines.map { it.row.id }, tag)
        assertEquals(Suggestion(BudgetId(1), listOf("feira")), db.suggestion("PADARIA DO ZE"), tag)
    }

    private companion object {
        const val NU = "com.nu.production"
        const val MIN = 60_000L
        /** 2026-10-06 11:43:59 in São Paulo: notices without a date in the text are read as Oct 6. */
        const val T = 1_791_297_839_102L
        val TODAY = LocalDate(2026, 10, 14)
        val OCT_6 = LocalDate(2026, 10, 6)
        val OCT_10 = LocalDate(2026, 10, 10)
        val NOON = LocalTime(12, 0)
        val A80 = "a".repeat(80)
        val A81 = "a".repeat(81)
        const val PURCHASE_TEXT = "Compra de R$ 31,50 APROVADA em PADARIA DO ZE para o cartão com final 1234."
        const val PURCHASE_TEXT_2 = "Compra de R$ 20,00 APROVADA em FEIRA DA ESQUINA para o cartão com final 1234."

        /** The v1 DDL of the Margem (copied from SCHEMA_V1 in Db.kt): the database the legenda found. */
        val V1_DDL = listOf(
            "CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE sources(package TEXT PRIMARY KEY, label TEXT NOT NULL, added_at INTEGER NOT NULL)",
            "CREATE TABLE notices(id INTEGER PRIMARY KEY, package TEXT NOT NULL, app_label TEXT NOT NULL, key TEXT NOT NULL, " +
                "posted_at INTEGER NOT NULL, when_ms INTEGER NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, big_text TEXT NOT NULL, " +
                "sub_text TEXT NOT NULL, text_lines TEXT NOT NULL, category TEXT NOT NULL, channel_id TEXT NOT NULL, " +
                "extras_json TEXT NOT NULL, content_hash TEXT NOT NULL, dismissed INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE, position INTEGER NOT NULL)",
            "CREATE TABLE budget_months(budget_id INTEGER NOT NULL, month TEXT NOT NULL, limit_cents INTEGER NOT NULL, PRIMARY KEY(budget_id, month))",
            "CREATE TABLE week_steps(budget_id INTEGER NOT NULL, week_start TEXT NOT NULL, cents INTEGER NOT NULL, PRIMARY KEY(budget_id, week_start))",
            "CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, notice_id INTEGER NOT NULL, budget_id INTEGER NOT NULL, " +
                "merchant TEXT NOT NULL, merchant_key TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, " +
                "total_cents INTEGER NOT NULL, corrected INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE installments(transaction_id INTEGER NOT NULL, k INTEGER NOT NULL, date TEXT NOT NULL, cents INTEGER NOT NULL, " +
                "PRIMARY KEY(transaction_id, k))",
            "CREATE TABLE refunds(estorno_notice_id INTEGER PRIMARY KEY, transaction_id INTEGER NOT NULL, notice_id INTEGER NOT NULL)",
            "CREATE TABLE tags(id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE)",
            "CREATE TABLE transaction_tags(transaction_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(transaction_id, tag_id))",
            "CREATE TABLE merchant_aliases(merchant_key TEXT PRIMARY KEY, alias TEXT NOT NULL)",
            "CREATE INDEX notices_key ON notices(key, posted_at)",
            "CREATE INDEX notices_posted_at ON notices(posted_at)",
            "CREATE INDEX transactions_notice ON transactions(notice_id)",
            "CREATE INDEX transactions_budget ON transactions(budget_id)",
            "CREATE INDEX transactions_merchant ON transactions(merchant_key)",
            "CREATE INDEX installments_date ON installments(date)",
            "CREATE INDEX refunds_transaction ON refunds(transaction_id)",
            "CREATE INDEX refunds_notice ON refunds(notice_id)",
            "CREATE INDEX transaction_tags_tag ON transaction_tags(tag_id)",
        )
    }
}
