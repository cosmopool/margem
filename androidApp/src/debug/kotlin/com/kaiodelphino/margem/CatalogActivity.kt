package com.kaiodelphino.margem

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiodelphino.margem.widgets.ButtonKind
import com.kaiodelphino.margem.widgets.Button
import com.kaiodelphino.margem.widgets.CapturePaused
import com.kaiodelphino.margem.widgets.Check
import com.kaiodelphino.margem.widgets.ChipState
import com.kaiodelphino.margem.widgets.DayHeader
import com.kaiodelphino.margem.widgets.DayPicker
import com.kaiodelphino.margem.widgets.DialogCard
import kotlinx.datetime.LocalTime
import com.kaiodelphino.margem.widgets.time
import com.kaiodelphino.margem.widgets.dateLong
import com.kaiodelphino.margem.widgets.Emenda
import com.kaiodelphino.margem.widgets.DateTimeDialog
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Field
import com.kaiodelphino.margem.widgets.Gap
import com.kaiodelphino.margem.widgets.HomeBar
import com.kaiodelphino.margem.widgets.Icon
import com.kaiodelphino.margem.widgets.IconButton
import com.kaiodelphino.margem.widgets.ListRow
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.MargemDialog
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.Mark
import com.kaiodelphino.margem.widgets.CeilingLegend
import com.kaiodelphino.margem.widgets.Meter
import com.kaiodelphino.margem.widgets.MoneyField
import com.kaiodelphino.margem.widgets.MonthPicker
import com.kaiodelphino.margem.widgets.NoticeCard
import com.kaiodelphino.margem.widgets.OptionPicker
import com.kaiodelphino.margem.widgets.PageHeader
import com.kaiodelphino.margem.widgets.SectionLine
import com.kaiodelphino.margem.widgets.SeriesMark
import com.kaiodelphino.margem.widgets.Snack
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.Snackbar
import com.kaiodelphino.margem.widgets.SnackbarHost
import com.kaiodelphino.margem.widgets.StatusLabel
import com.kaiodelphino.margem.widgets.Stepper
import com.kaiodelphino.margem.widgets.StepsEditor
import com.kaiodelphino.margem.widgets.Tab
import com.kaiodelphino.margem.widgets.TabBar
import com.kaiodelphino.margem.widgets.TagChip
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.sans
import com.kaiodelphino.margem.widgets.serif
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * Debug only (architecture §7): every component in the states of book §8, on the book's scenario
 * (Friday, 9 October 2026), switching light and dark. The components are checked here, by eye.
 */
class CatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var dark by rememberSaveable { mutableStateOf(intent.getBooleanExtra("dark", false)) }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            MargemTheme(dark) { Catalog(dark) { dark = it } }
        }
    }
}

private val TODAY = LocalDate(2026, 10, 9)

private fun notice(app: String, body: String, cents: Long = 0, merchant: String = "", alias: String = "", new: Boolean = false, refund: Boolean = false) = NoticeRow(
    raw = RawNotice(appLabel = app, bigText = body),
    parsed = Parsed(cents = Cents(cents), merchant = merchant, isRefund = refund),
    alias = alias, isNew = new, looksLikeAd = cents == 0L && merchant.isEmpty(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Catalog(dark: Boolean, setDark: (Boolean) -> Unit) {
    val c = Margem.colors
    val snack = SnackHost(rememberCoroutineScope())
    var dialog by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(LocalDate(2026, 10, 8) to LocalTime(23, 10)) }
    Box(Modifier.fillMaxSize().background(c.paper).windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TopBar("Catálogo")
            Column(Modifier.padding(horizontal = 24.dp)) {
                OptionPicker(listOf(false to "claro", true to "escuro"), dark, setDark)

                SectionLine("8.1 Botões", aside = "padrão · press · foco · desab.")
                for (kind in ButtonKind.entries) {
                    val (label, icon) = when (kind) {
                        ButtonKind.PRIMARY -> "Lançar" to R.drawable.i_lancar
                        ButtonKind.SECONDARY -> "Ajustar cota" to 0
                        ButtonKind.DISMISS -> "Riscar" to R.drawable.i_riscar
                        ButtonKind.TEXT -> "Desfazer" to 0
                    }
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(label, {}, kind = kind, icon = icon)
                        Button(label, {}, kind = kind, icon = icon, pressed = true)
                        Button(label, {}, kind = kind, icon = icon, focused = true)
                        Button(label, {}, kind = kind, icon = icon, enabled = false)
                    }
                }
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Riscar", {}, kind = ButtonKind.DISMISS, icon = R.drawable.i_riscar, small = true)
                    Button("Lançar", {}, small = true)
                    IconButton(R.drawable.i_mais, {})
                }
                Button("Novo lançamento", {}, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.SECONDARY, icon = R.drawable.i_mais)

                SectionLine("8.2 Campos")
                var name by remember { mutableStateOf("") }
                Field(name, { name = it }, "Nome da verba", placeholder = "Ex.: Mercado", hint = "Vazio")
                Field("Mercad", {}, "Nome da verba", hint = "Em foco", focused = true)
                Field("Mercado", {}, "Nome da verba", hint = "Preenchido")
                Field("Mercado", {}, "Nome da verba", error = "Já existe uma verba com esse nome.")
                Field("PAG*JOSEDASILVA 0231", {}, "Texto do banco", hint = "Somente leitura", enabled = false)
                Field("Padaria do Zé", {}, "Estabelecimento", hint = "Sublinhado pontilhado em argila = apelido.", alias = true) {
                    Icon(R.drawable.i_apelido, tint = c.brand, size = 20.dp)
                }
                var value by remember { mutableStateOf(Cents(0)) }
                MoneyField(value, { value = it }, "Valor", hint = "Vazio — digite a partir dos centavos")
                MoneyField(Cents(2390), {}, "Valor", hint = "Em foco", focused = true)
                MoneyField(Cents(2390), {}, "Valor", original = Cents(3290))
                MoneyField(Cents(120000), {}, "Valor total", hint = "Parcelado: informa-se o valor da compra inteira, não o da parcela.")
                MoneyField(Cents(0), {}, "Valor", size = 30, error = "Não conseguimos ler o valor deste recado. O resto já está preenchido.")
                var parcelas by remember { mutableStateOf(10) }
                Text("PARCELAS", sans(11f, 600, 14f, 0.12f), Modifier.padding(top = 8.dp, bottom = 8.dp), c.inkTertiary)
                Stepper(parcelas, { parcelas = it }, 1..400)
                Text("10 × ${money(Cents(12000))}", serif(19, 400, 24, tabular = true), Modifier.padding(top = 12.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Check(true); Check(false)
                }

                SectionLine("8.3 Marcadores")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagChip("feira")
                    TagChip("mercado", state = ChipState.SELECTED)
                    TagChip("padaria", state = ChipState.SUGGESTED)
                    TagChip("café", focused = true)
                    TagChip("viagem", state = ChipState.DISABLED)
                    TagChip("café", state = ChipState.SELECTED, removable = true)
                    TagChip("padaria", state = ChipState.SUGGESTED, removable = true)
                }

                SectionLine("8.4 Recado")
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NoticeCard(
                        notice("Cartão Ipê", "Compra aprovada no cartão final 4410: R$ 23,90 em PAG*JOSEDASILVA 0231.", 2390, "PAG*JOSEDASILVA 0231", "Padaria do Zé", new = true),
                        {}, {}, time = "07:42",
                    )
                    NoticeCard(notice("Banco Aurora · Pix", "Pix enviado: R$ 86,00 para MERCADINHO BOM PRECO LTDA.", 8600, "Mercadinho Bom Preço"), {}, {}, time = "07:15")
                    NoticeCard(notice("Cartão Ipê", "Estorno de compra: R$ 59,90 — LOJA ESTACAO.", 5990, "Loja Estação", refund = true), {}, {}, time = "ontem, 21:03")
                    NoticeCard(notice("PagFácil", "Você ganhou 10% de cashback em lojas parceiras! Confira."), {}, {}, time = "08:05")
                    NoticeCard(notice("PagFácil", "Você ganhou 10% de cashback em lojas parceiras! Confira.", new = true), {}, {}, time = "08:05", acceptEnabled = false)
                }

                SectionLine("8.5 Régua")
                Meter(Cents(72000), Cents(100000), Cents(57000), PaceStatus.AHEAD, Modifier.padding(top = 16.dp), tall = true)
                for ((spent, status) in listOf(53000L to PaceStatus.ON_PACE, 72000L to PaceStatus.AHEAD, 108000L to PaceStatus.OVERRUN, 0L to PaceStatus.ON_PACE)) {
                    Column(Modifier.padding(top = 14.dp)) {
                        StatusLabel(status, empty = spent == 0L)
                        Meter(Cents(spent), Cents(100000), Cents(if (spent == 0L) 29000 else 57000), status, Modifier.padding(top = 6.dp))
                    }
                }

                SectionLine("8.10 Cabeçalho do teto")
                val oct = YearMonth(2026, 10)
                for (ceiling in listOf(
                    Ceiling(oct, Cents(0), Cents(0), Cents(372000)),
                    Ceiling(oct, Cents(410000), Cents(410000), Cents(372000)),
                    Ceiling(oct, Cents(410000), Cents(410000), Cents(410000)),
                    Ceiling(oct, Cents(410000), Cents(410000), Cents(440000)),
                    Ceiling(oct, Cents(440000), Cents(410000), Cents(440000)),
                )) CeilingLegend(ceiling, Modifier.padding(top = 10.dp))

                SectionLine("8.6 Mês e passos")
                MonthPicker(YearMonth(2026, 10), "1 a 31 · mês atual", {}, {}, canNext = false)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Mês começa no dia", Margem.type.body)
                    var day by remember { mutableStateOf(1) }
                    Stepper(day, { day = it }, 1..31)
                }
                var weekStart by remember { mutableStateOf(DayOfWeek.MONDAY) }
                Text("Semana começa na", Margem.type.body, Modifier.padding(top = 12.dp))
                DayPicker(weekStart, { weekStart = it })
                SectionLine("Passos semanais", aside = "toque para editar")
                StepsEditor(
                    listOf(
                        Week(LocalDate(2026, 10, 1), LocalDate(2026, 10, 4), Cents(43000), edited = true),
                        Week(LocalDate(2026, 10, 5), LocalDate(2026, 10, 11), Cents(21000), edited = true),
                        Week(LocalDate(2026, 10, 12), LocalDate(2026, 10, 18), Cents(21000), edited = true),
                        Week(LocalDate(2026, 10, 19), LocalDate(2026, 10, 25), Cents(21000), edited = true),
                        Week(LocalDate(2026, 10, 26), LocalDate(2026, 10, 31), Cents(18000), locked = true),
                    ),
                    TODAY, {},
                )
                MoneyField(Cents(90000), {}, "Passo de 5 a 11 out", size = 30, error = "O máximo para esta semana é ${money(Cents(81000))}.")

                SectionLine("8.7 Navegação")
                HomeBar { IconButton(R.drawable.i_mais, {}) }
                TopBar("Lazer", onBack = {}) { Button("Editar", {}, kind = ButtonKind.TEXT) }
                TopBar("Nova verba", onBack = {}, close = true) { Button("Salvar", {}, kind = ButtonKind.TEXT, color = c.ink) }
                TabBar(Tab.BUDGETS, {}, badge = 4)
                var order by remember { mutableStateOf(BudgetOrder.RISK) }
                OptionPicker(listOf(BudgetOrder.RISK to "risco", BudgetOrder.MANUAL to "manual", BudgetOrder.AZ to "A–Z"), order, { order = it }, Modifier.padding(top = 12.dp))

                SectionLine("Página")
                PageHeader("Mês de 1 a 31 de outubro", "Dia 9 de 31.", Modifier.padding(top = 12.dp), dek = "Faltam 23 dias, contando hoje.")
                DayHeader("Hoje · sexta, 9 de outubro")
                ListRow("Fontes", sub = "Banco Aurora, Cartão Ipê, PagFácil", onClick = {}) { Icon(R.drawable.i_avancar, tint = c.inkTertiary, size = 20.dp) }
                ListRow("Banca Central", sub = "Lazer · revistas") { com.kaiodelphino.margem.widgets.Amount(Cents(1200), size = 17) }
                ListRow("Drogaria Vida", sub = "Farmácia · estornada") { com.kaiodelphino.margem.widgets.Amount(Cents(3200), size = 17, color = c.inkTertiary, struck = true) }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in c.series) SeriesMark(s, size = 18.dp)
                }
                Gap(16.dp)
                CapturePaused({}, onManual = {})
                EmptyState(R.drawable.i_pauta, "Pauta limpa.", "Nenhum recado esperando. Os próximos chegam sozinhos.")
                EmptyState(R.drawable.i_verba, "Nenhuma verba ainda.", "Verba é um limite que você escolhe: um nome e uma cota por mês.") {
                    Button("Criar primeira verba", {})
                }

                SectionLine("8.8 Diálogo e snackbar")
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).background(c.scrim).padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                    DialogCard("Apagar a verba Lazer?", "Os 5 lançamentos dela também são apagados. Os que vieram de recados voltam à pauta.", "Apagar", {}, {})
                }
                Button("Abrir diálogo", { dialog = true }, Modifier.padding(top = 8.dp), kind = ButtonKind.SECONDARY)
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Snackbar("Lançado em Mercado. Margem: R$ 641,70.", action = "Desfazer")
                    Snackbar("Recado riscado.", action = "Desfazer")
                    Snackbar("Apelido salvo: “Padaria do Zé”.")
                    Snackbar("Estorno registrado: a compra de 2 out não conta mais.", action = "Desfazer")
                }
                Button("Mostrar snackbar", { snack.show(Snack("Recado riscado.", "Desfazer")) }, Modifier.padding(top = 8.dp), kind = ButtonKind.SECONDARY)

                SectionLine("8.9 Data e hora")
                Text("${dateLong(picked.first)} · ${time(picked.second)}", serif(19, 400, 24, tabular = true), Modifier.padding(top = 12.dp))
                Emenda("qui, 8 out, 23:10", { picked = LocalDate(2026, 10, 8) to LocalTime(23, 10) })
                Button("Abrir data e hora", { picking = true }, Modifier.padding(top = 8.dp), kind = ButtonKind.SECONDARY)

                SectionLine("6.3 Ícones · 3 Marca")
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (id in ICONS) Icon(id, size = 32.dp)
                }
                Row(Modifier.padding(top = 16.dp, bottom = 120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Mark(48.dp); Mark(32.dp); Mark(24.dp)
                    Box(Modifier.width(1.dp))
                    Text("margem", serif(30, 500, 30, italic = true, tracking = -0.025f))
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
    if (picking) DateTimeDialog(picked.first, picked.second, LocalDate(2026, 10, 9), DayOfWeek.MONDAY, { d, t -> picked = d to t }, { picking = false })
    if (dialog) MargemDialog("Mudar o início da semana?", "As edições de passo de outubro em diante são apagadas, em todas as verbas, e os passos voltam ao padrão.", "Mudar", { dialog = false }, { dialog = false })
}

private val ICONS = listOf(
    R.drawable.i_anotacao, R.drawable.i_recado, R.drawable.i_pauta, R.drawable.i_lancar, R.drawable.i_riscar, R.drawable.i_lancamento,
    R.drawable.i_marcador, R.drawable.i_emenda, R.drawable.i_apelido, R.drawable.i_estorno, R.drawable.i_verba, R.drawable.i_periodo,
    R.drawable.i_ritmo, R.drawable.i_fora, R.drawable.i_leitura, R.drawable.i_fontes, R.drawable.i_margem, R.drawable.i_mais,
    R.drawable.i_voltar, R.drawable.i_avancar, R.drawable.i_fechar, R.drawable.i_ajustes, R.drawable.i_pausa, R.drawable.i_aviso,
    R.drawable.i_desfazer, R.drawable.i_lupa, R.drawable.i_sinal, R.drawable.i_bat,
)
