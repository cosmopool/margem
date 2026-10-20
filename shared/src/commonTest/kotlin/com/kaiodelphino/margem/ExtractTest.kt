package com.kaiodelphino.margem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** What the accept screen pre-fills from a notice's text. Nubank texts are real, names anonymised. */
class ExtractTest {

    private val zone = TimeZone.of("America/Sao_Paulo")

    /** Posts like Nubank does: the same text as content and big text. */
    private fun read(pkg: String, title: String, text: String, whenMs: Long = T, postMs: Long = if (whenMs == 0L) T else whenMs) =
        extract(RawNotice(packageName = pkg, title = title, text = text, bigText = text, whenMs = whenMs, postedAt = postMs), zone)

    private fun nubank(text: String) = read(NUBANK, "Nubank", text)

    @Test
    fun `nubank card purchase gives merchant value and the notice's date and time`() {
        val p = read(NUBANK, "Compra no crédito aprovada", "Compra de R$ 31,50 APROVADA em GIPL COMERCIO DE ALIME para o cartão com final 1234.")
        assertEquals(Parsed(Cents(3150), "GIPL COMERCIO DE ALIME", LocalDate(2026, 10, 6), LocalTime(11, 43), isRefund = false), p)
    }

    @Test
    fun `nubank business card wording with the padded merchant collapsed`() {
        val p = nubank("Compra de R$ 161,70 APROVADA em DL          *UberRides no seu cartão Nu Empresas.")
        assertEquals("DL *UberRides" to Cents(16170), p.merchant to p.cents)
    }

    @Test
    fun `nubank money out and money in`() {
        assertEquals(
            listOf(
                "iFood" to 3391L,
                "CENTRO DE EDUCACAO INFANTIL PEQUENO" to 84500L,
                "" to 1083000L,
                "ACME SISTEMAS WEB LTDA" to 1083000L,
                "12.345.678 FULANO DE TAL" to 548870L,
            ),
            listOf(
                "R$ 33,91 em 1x no crédito sem juros com NuPay APROVADO em iFood.",
                "Seu pagamento de R$ 845,00 para CENTRO DE EDUCACAO INFANTIL PEQUENO foi realizado com sucesso.",
                "Pix de R$ 10.830,00 da conta Nu Empresas foi realizado com sucesso.",
                "Você recebeu R$ 10.830,00 de ACME SISTEMAS WEB LTDA na conta Nu Empresas.",
                "Você recebeu uma transferência de R$ 5.488,70 de 12.345.678 FULANO DE TAL.",
            ).map(::nubank).map { it.merchant to it.cents.v },
        )
    }

    @Test
    fun `nubank texts that move no money or are unknown give no value`() {
        listOf(
            "Kaio, traga seus dados do Banco Inter Pagamentos PF para o Nubank e aumente suas chances de ter mais limite de crédito",
            "Conheça as novidades do seu cartão Nu Empresas Mastercard Business Executive.",
            "Compra no cartão Nu Empresas de R$ 679,33 foi NEGADA, pois o limite disponível não é suficiente.",
            "O boleto CLINICA EXEMPLO LTDA no valor de R$ 235,14 vence amanhã. Clique aqui para pagar ou agendar.",
            "CENTRO DE EDUCACAO INFANTIL PEQUENO - R$ 985,00",
            "O débito automático da sua fatura no valor de R$ 3.351,63 está ativado. Menos uma conta pra você lembrar de pagar!",
            "Recebemos seu pagamento no valor de R$ 5.478,49. Obrigado!",
            "Seu limite aumentou para R$ 5.000,00",
        ).forEach { assertEquals(Cents(0), nubank(it).cents, it) }
    }

    @Test
    fun `a nubank estorno is read as a refund of the purchase it names`() {
        val p = nubank("A compra em DL          *UberRides no valor de R$ 7,02 foi estornada no seu cartão Nu Empresas.")
        assertEquals("DL *UberRides" to Cents(702), p.merchant to p.cents)
        assertTrue(p.isRefund)
    }

    @Test
    fun `generic bank reads amount merchant date and time from the text`() {
        val p = read(BANK, "Compra aprovada", "Compra de R$ 1.234,56 aprovada em PADARIA SAO JOSE em 05/10 às 14h32.")
        assertEquals(Parsed(Cents(123456), "PADARIA SAO JOSE", LocalDate(2026, 10, 5), LocalTime(14, 32)), p)
    }

    @Test
    fun `generic amounts - nbsp first wins dot decimals none absurd`() {
        assertEquals(Cents(1200), read(BANK, "Compra", "Compra de R$ 12,00 em LOJA. Seu saldo R$ 95,50").cents)
        assertEquals(Cents(1290), read(BANK, "SMS", "Em 3 dias vence o seu bonus de R$12.90. Desconto").cents)
        assertEquals(Cents(0), read(BANK, "Card purchase", "You spent $4.50 at Coffee").cents)
        assertEquals(Cents(0), read(BANK, "Compra", "Compra de R$ 99999999999999999999 em LOJA").cents)
    }

    @Test
    fun `generic text with estorn is a refund here naming no merchant`() {
        val p = read(BANK, "Estorno", "Estorno de R$ 20,00 efetuado")
        assertEquals("" to Cents(2000), p.merchant to p.cents)
        assertTrue(p.isRefund)
        assertFalse(read(BANK, "Compra", "Compra de R$ 20,00 em LOJA").isRefund)
    }

    @Test
    fun `a date without year picks the closest year and when 0 falls back to post time`() {
        val lastYear = read(BANK, "Compra", "Compra de R$ 10,00 em LOJA em 31/12", JAN_2_2027)
        assertEquals(LocalDate(2026, 12, 31) to LocalTime(10, 0), lastYear.date to lastYear.time)
        assertEquals(LocalDate(2026, 10, 8), read(BANK, "Compra", "Compra de R$ 11,00 em LOJA em 08/10").date)
        val noWhen = read(BANK, "Compra", "Compra de R$ 12,00 em LOJA", whenMs = 0, postMs = T + MIN)
        assertEquals(LocalDate(2026, 10, 6) to LocalTime(11, 44), noWhen.date to noWhen.time)
    }

    @Test
    fun `the event time wins over the post time - two-digit years are 20yy and a date past new year is next year's`() {
        val reposted = read(BANK, "Compra", "Compra de R$ 12,00 em LOJA", whenMs = T, postMs = T + 2 * DAY)
        assertEquals(LocalDate(2026, 10, 6) to LocalTime(11, 43), reposted.date to reposted.time)
        assertEquals(LocalDate(2025, 10, 5), read(BANK, "Compra", "Compra de R$ 10,00 em LOJA em 05/10/25").date)
        assertEquals(LocalDate(2027, 1, 1), read(BANK, "Compra", "Compra de R$ 10,00 em LOJA em 01/01", JAN_2_2027 - 2 * DAY).date)
    }

    @Test
    fun `a date that exists in no year keeps the value and falls back to the notice's date`() {
        val p = read(BANK, "Compra", "Compra de R$ 10,00 em LOJA em 31/02")
        assertEquals(Parsed(Cents(1000), "LOJA", LocalDate(2026, 10, 6), LocalTime(11, 43)), p)
    }

    @Test
    fun `a text without a value still gives its date and time`() {
        val p = read(BANK, "Fatura", "Sua fatura fecha em 10/11 às 09:00")
        assertEquals(Parsed(Cents(0), "", LocalDate(2026, 11, 10), LocalTime(9, 0)), p)
    }

    @Test
    fun `nubank reads the big text - else the lines - else the text`() {
        fun nu(text: String = "", lines: String = "", big: String = "") =
            extract(RawNotice(packageName = NUBANK, title = "Nubank", text = text, textLines = lines, bigText = big, whenMs = T, postedAt = T), zone).cents
        val a = "Compra de R$ 31,50 APROVADA em LOJA A para o cartão com final 1234."
        val b = "Compra de R$ 9,94 APROVADA em LOJA B para o cartão com final 1234."
        assertEquals(Cents(3150), nu(text = a))
        assertEquals(Cents(3150), nu(text = b, lines = a))
        assertEquals(Cents(3150), nu(text = b, lines = b, big = a))
    }

    @Test
    fun `typed values`() {
        assertEquals(Cents(3150), parseBrl("31,50"))
        assertEquals(Cents(123456), parseBrl("1.234,56"))
        assertEquals(Cents(1200), parseBrl("R$ 12"))
        assertEquals(Cents(1250), parseBrl(" R$ 12,5 "))
        assertEquals(Cents(99_999_999_999_999_999), parseBrl("999999999999999,99"))
        listOf("", "abc", "12,345", "-5", "1234567890123456", "1.23,45", "R$", "12,").forEach { assertEquals(Cents(0), parseBrl(it), it) }
    }

    private companion object {
        const val NUBANK = "com.nu.production"
        const val BANK = "com.bank"
        const val MIN = 60_000L
        const val DAY = 24 * 60 * MIN
        /** 2026-10-06 11:43:59 in São Paulo. */
        const val T = 1_791_297_839_102L
        /** 2027-01-02 10:00 in São Paulo. */
        const val JAN_2_2027 = 1_798_894_800_000L
    }
}
