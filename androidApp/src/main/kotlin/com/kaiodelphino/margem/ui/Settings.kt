package com.kaiodelphino.margem.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kaiodelphino.margem.BudgetOrder
import com.kaiodelphino.margem.CobudgetImport
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.TagRow
import com.kaiodelphino.margem.Theme
import com.kaiodelphino.margem.accessSettingsIntent
import com.kaiodelphino.margem.copyIn
import com.kaiodelphino.margem.deleteTemp
import com.kaiodelphino.margem.exportInto
import com.kaiodelphino.margem.importCobudget
import com.kaiodelphino.margem.widgets.Button
import com.kaiodelphino.margem.widgets.ButtonKind
import com.kaiodelphino.margem.widgets.CapturePaused
import com.kaiodelphino.margem.widgets.Check
import com.kaiodelphino.margem.widgets.DayHeader
import com.kaiodelphino.margem.widgets.DayPicker
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Field
import com.kaiodelphino.margem.widgets.Icon
import com.kaiodelphino.margem.widgets.ListRow
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.MargemDialog
import com.kaiodelphino.margem.widgets.NoticeCard
import com.kaiodelphino.margem.widgets.OptionPicker
import com.kaiodelphino.margem.widgets.SectionLine
import com.kaiodelphino.margem.widgets.Snack
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.Stepper
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.monthLong
import com.kaiodelphino.margem.widgets.sans
import com.kaiodelphino.margem.widgets.serif
import com.kaiodelphino.margem.widgets.weekdayLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Test tags of Ajustes and its sub-screens (spec §7.6, book 10.9, D35): the contract of their UI tests.
 * Same setup and rules as [BudgetTags]: `App(db, null) {}` in `MargemTheme(dark = false)` with
 * `LocalToday` fixed, a Db on AndroidSQLiteDriver (D28), elements found only by tags. Ajustes is reached
 * by the tab bar (Tab.SETTINGS) or by `App`'s public `tabRequest` (Screen.Settings); each sub-screen by
 * its row here, and back by the top bar's back arrow or the system back, to Ajustes.
 *
 * Files (Backup, cobudget) are picked through the Storage Access Framework with
 * `rememberLauncherForActivityResult`: `ActivityResultContracts.CreateDocument("application/octet-stream")`
 * to export, `ActivityResultContracts.OpenDocument()` (any MIME type) to import. Tests answer
 * the picker with their own registry, `CompositionLocalProvider(LocalActivityResultRegistryOwner provides …)`,
 * returning a `file://` Uri of a temporary file (`Uri.fromFile`), or null for a cancelled pick (nothing
 * happens). The app reads and writes that Uri through `ContentResolver`.
 *
 * Snacks go through the app-wide snackbar ([com.kaiodelphino.margem.widgets.SnackTags]).
 */
object SettingsTags {
    // ---- Ajustes (book 10.9) ----

    /** The scrollable page. Plain container. */
    const val LIST = "settings.list"

    /** "A anotação automática está pausada.", first on the page, only while notification access is off. */
    const val PAUSED = "settings.paused"

    /**
     * "Fontes", with the labels of the sources (Db.sources order) joined by ", ", or "nenhuma fonte
     * escolhida". Click opens Fontes.
     */
    const val SOURCES = "settings.sources"

    /** "Riscados" · "trazer recados de volta à pauta". Click opens Riscados. */
    const val DISMISSED = "settings.dismissed"

    /** "Marcadores" · "renomear, juntar, apagar". Click opens Marcadores. */
    const val TAGS = "settings.tags"

    /**
     * "Início do mês e da semana" · "dia 5 · segunda" (Settings.monthStartDay and the weekStart in
     * lowercase, `weekdayLong`: segunda, terça, quarta, quinta, sexta, sábado, domingo). Click opens it.
     */
    const val START_DAYS = "settings.start"

    /**
     * Prefix: THEME + "system" / "light" / "dark": "sistema" · "claro" · "escuro". The one `selected` is
     * Settings.theme; click saves it (Db.setTheme) and the app follows.
     */
    const val THEME = "settings.theme."

    /**
     * Prefix: WIDGET_ORDER + "risk" / "manual" / "az": "risco" · "manual" · "A–Z". The one `selected` is
     * Settings.widgetOrder; click saves it (Db.setWidgetOrder).
     */
    const val WIDGET_ORDER = "settings.widget."

    /** "Backup" · "exportar · importar". Click opens Backup. */
    const val BACKUP = "settings.backup"

    /** "Importar do cobudget" · "recados de um backup do app antigo". Click opens Importar do cobudget. */
    const val COBUDGET = "settings.cobudget"

    // ---- Riscados (spec §2.3, §7.6) ----

    /** The scrollable list of dismissed notices. Plain container. */
    const val DISMISSED_LIST = "dismissed.list"

    /** "Nenhum recado riscado.", instead of the cards when nothing is dismissed. */
    const val DISMISSED_EMPTY = "dismissed.empty"

    /**
     * Prefix: DISMISSED_DAY + the date ("dismissed.day.2026-10-09"), one per day with dismissed notices,
     * newest first, titled like the Pauta's (InboxTags.DAY, [dayTitle]).
     */
    const val DISMISSED_DAY = "dismissed.day."

    /**
     * Prefix: DISMISSED_CARD + notice id, one per dismissed notice (Db.dismissed order: newest posted
     * first), under its day. Shows what the Pauta card shows (InboxTags.CARD): source, time, bank text
     * and, unless it looks like an ad, merchant and value.
     */
    const val DISMISSED_CARD = "dismissed.card."

    /**
     * Prefix: RESTORE + notice id: "Trazer de volta". Click restores it (Db.restore): the card leaves the
     * list and the notice is pending in the Pauta. No snack, no Desfazer.
     */
    const val RESTORE = "dismissed.restore."

    // ---- Marcadores (spec §2.9, §7.6) ----

    /** The scrollable list of tags. Plain container. */
    const val TAGS_LIST = "tags.list"

    /** "Nenhum marcador." with "Marcadores nascem ao lançar.", instead of the rows when there is no tag. */
    const val TAGS_EMPTY = "tags.empty"

    /**
     * Prefix: TAG_ROW + tag id, one per tag in Db.tags order (by name): the name and "1 lançamento",
     * "3 lançamentos" or "nenhum lançamento" (TagRow.uses). Click opens [TAG_DIALOG] for it.
     */
    const val TAG_ROW = "tags.row."

    /** The tag's dialog. Plain container: [TAG_NAME], [TAG_SAVE], [TAG_DELETE], [TAG_CANCEL]. */
    const val TAG_DIALOG = "tags.dialog"

    /** The name, a text field filled with the tag's name. */
    const val TAG_NAME = "tags.name"

    /**
     * "Salvar", or "Juntar com mercado" when the typed name (trimmed, lowercased) is another tag's name.
     * Disabled while the typed name is blank or the tag's own name. Click renames (Db.renameTag) or merges
     * this tag into the other (Db.mergeTags: its transactions get the other tag, it disappears) and closes
     * the dialog.
     */
    const val TAG_SAVE = "tags.save"

    /** "Apagar": closes the tag's dialog and opens [TAG_DELETE_DIALOG]. */
    const val TAG_DELETE = "tags.delete"

    /** "Cancelar": closes the dialog, nothing written. */
    const val TAG_CANCEL = "tags.cancel"

    /**
     * MargemDialog "Apagar o marcador padaria?", with "3 lançamentos ficam sem marcador e são apagados; os
     * recados deles voltam à Pauta." ("1 lançamento fica sem marcador e é apagado; o recado dele volta à
     * Pauta.") when TagRow.soleUses > 0, else "Nenhum lançamento é apagado.". "$TAG_DELETE_DIALOG.confirm"
     * ("Apagar") deletes it (Db.deleteTag); "$TAG_DELETE_DIALOG.cancel" closes, nothing written.
     */
    const val TAG_DELETE_DIALOG = "tags.deleteDialog"

    // ---- Início do mês e da semana (spec §4, §7.6, book 8.6, D41) ----

    /**
     * Stepper "Mês começa no dia" (1..31): the number; "$START_DAY.minus" and "$START_DAY.plus" change it
     * by one (disabled at 1 and 31). Starts at Settings.monthStartDay. A draft until [START_SAVE].
     */
    const val START_DAY = "start.day"

    /**
     * Prefix: START_WEEK + DayOfWeek name in lowercase ("start.week.monday"): "Semana começa na"; the
     * `selected` one is the draft, starting at Settings.weekStart. Click changes the draft only.
     */
    const val START_WEEK = "start.week."

    /**
     * "Salvar". Disabled while the draft equals the saved settings. Click: when Db.startDropsEdits is
     * true, opens [START_DIALOG]; otherwise saves (Db.setMonthStartDay, then Db.setWeekStart) and goes
     * back to Ajustes.
     */
    const val START_SAVE = "start.save"

    /**
     * MargemDialog "Mudar o início do mês?" ("…da semana?", "…do mês e da semana?", by what the draft
     * changes), with "As edições de passo de outubro em diante são apagadas, em todas as verbas, e os
     * passos voltam ao padrão. Meses passados são recalculados com o dia novo." (the month of today by
     * the saved settings, `monthLong` in lowercase). "$START_DIALOG.confirm" ("Mudar") saves like
     * [START_SAVE] and goes back to Ajustes; "$START_DIALOG.cancel" closes it, nothing written, the draft kept.
     */
    const val START_DIALOG = "start.dialog"

    // ---- Backup (spec §7.6, D14) ----

    /**
     * "Exportar": opens the picker to create a file named "margem-2026-10-14.db" (LocalToday). Then the
     * file gets a copy of the database (Db.exportTo) and the snack "Backup exportado." appears, or
     * "Não deu para exportar o backup." when it can't be written.
     */
    const val BACKUP_EXPORT = "backup.export"

    /**
     * "Importar": opens the picker to choose a file, then [BACKUP_DIALOG]. Nothing changes before the
     * dialog is confirmed.
     */
    const val BACKUP_IMPORT = "backup.import"

    /**
     * MargemDialog "Restaurar este backup?" with "Verbas, lançamentos, recados e ajustes atuais dão lugar
     * aos do backup. Não dá para desfazer.". "$BACKUP_DIALOG.confirm" ("Restaurar") replaces the database
     * (Db.importFrom) and shows the snack "Backup restaurado.", or "Este arquivo não é um backup do Margem."
     * with the database untouched; "$BACKUP_DIALOG.cancel" closes it, nothing written.
     */
    const val BACKUP_DIALOG = "backup.dialog"

    // ---- Importar do cobudget (spec §7.6, D3, D40) ----

    /** "Escolher arquivo": opens the picker; the chosen file goes through importCobudget. */
    const val COBUDGET_PICK = "cobudget.pick"

    /**
     * Shown after an import, until the screen is left: for a refused file, "Este arquivo não é um backup
     * do cobudget."; otherwise "312 recados importados: 280 na Pauta, 32 em Riscados." ("1 recado
     * importado: 1 na Pauta, 0 em Riscados.", "Nenhum recado importado." when both are 0), followed by
     * " 40 não entraram (repetidos ou sem texto)." ("1 não entrou (repetido ou sem texto).") when
     * CobudgetImport.skipped > 0. Absent before the first import.
     */
    const val COBUDGET_RESULT = "cobudget.result"
}

/** Ajustes (spec §7.6, book 10.9): [SettingsTags]. */
@Composable
fun Settings(db: Db, granted: Boolean, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = query(db) { it.sources() }
    val settings = query(db) { it.settings() }
    Column {
        TopBar("Ajustes")
        Column(Modifier.testTag(SettingsTags.LIST).verticalScroll(rememberScrollState()).padding(start = Margem.space.page, end = Margem.space.page, bottom = 24.dp)) {
            if (!granted) CapturePaused({ context.startActivity(accessSettingsIntent(context)) }, Modifier.padding(top = 8.dp, bottom = 8.dp).testTag(SettingsTags.PAUSED).semantics(mergeDescendants = true) {})
            SectionLine("Anotação", Modifier.padding(top = 0.dp))
            Entry("Fontes", sources?.joinToString(", ") { it.label }?.ifEmpty { "nenhuma fonte escolhida" }.orEmpty(), SettingsTags.SOURCES) { go(Screen.Sources) }
            Entry("Riscados", "trazer recados de volta à pauta", SettingsTags.DISMISSED) { go(Screen.Dismissed) }
            Entry("Marcadores", "renomear, juntar, apagar", SettingsTags.TAGS) { go(Screen.Tags) }
            if (settings == null) return@Column
            SectionLine("Mês e semana")
            Entry("Início do mês e da semana", "dia ${settings.monthStartDay} · ${weekdayLong(settings.weekStart)}", SettingsTags.START_DAYS) { go(Screen.StartDays) }
            Text("Mudar apaga as edições de passo do mês atual em diante; o app avisa antes.", sans(12.5f, 400, 17f), Modifier.padding(top = 4.dp), Margem.colors.inkTertiary)
            SectionLine("Aparência")
            Text("Tema · vale também para os widgets", sans(13f, 400, 17f), Modifier.padding(top = 6.dp), Margem.colors.inkSecondary)
            OptionPicker(
                listOf(Theme.SYSTEM to "sistema", Theme.LIGHT to "claro", Theme.DARK to "escuro"), settings.theme,
                { scope.launch { db.setTheme(it) } }, tag = SettingsTags.THEME,
            )
            Text("Ordem do widget de Verbas", sans(13f, 400, 17f), Modifier.padding(top = 12.dp), Margem.colors.inkSecondary)
            OptionPicker(
                listOf(BudgetOrder.RISK to "risco", BudgetOrder.MANUAL to "manual", BudgetOrder.AZ to "A–Z"), settings.widgetOrder,
                { scope.launch { db.setWidgetOrder(it) } }, tag = SettingsTags.WIDGET_ORDER,
            )
            SectionLine("Dados")
            Entry("Backup", "exportar · importar", SettingsTags.BACKUP) { go(Screen.Backup) }
            Entry("Importar do cobudget", "recados de um backup do app antigo", SettingsTags.COBUDGET) { go(Screen.Cobudget) }
        }
    }
}

/** A row of Ajustes that opens a sub-screen. */
@Composable
private fun Entry(title: String, sub: String, tag: String, onClick: () -> Unit) =
    ListRow(title, Modifier.testTag(tag), sub = sub, onClick = onClick) { Icon(R.drawable.i_avancar, tint = Margem.colors.inkTertiary, size = 20.dp) }

/** Ajustes › Riscados: dismissed notices by day, each with "Trazer de volta" (no Desfazer, spec §7.6). */
@Composable
fun Dismissed(db: Db, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dismissed = query(db) { it.dismissed() }
    val today = LocalToday.current()
    val zone = TimeZone.currentSystemDefault()
    Column {
        TopBar("Riscados", onBack = back)
        LazyColumn(
            Modifier.testTag(SettingsTags.DISMISSED_LIST),
            contentPadding = PaddingValues(start = Margem.space.page, end = Margem.space.page, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("Recados que não eram gasto. Trazer de volta devolve o recado à Pauta.", sans(15f, 400, 22f), color = Margem.colors.inkSecondary) }
            if (dismissed == null) return@LazyColumn
            if (dismissed.isEmpty()) item {
                EmptyState(
                    R.drawable.i_riscar, "Nenhum recado riscado.", "Os recados riscados na Pauta aparecem aqui.",
                    Modifier.testTag(SettingsTags.DISMISSED_EMPTY).semantics(mergeDescendants = true) {},
                )
            }
            val byDay = dismissed.groupBy { Instant.fromEpochMilliseconds(it.raw.postedAt).toLocalDateTime(zone).date }
            for ((day, notices) in byDay) {
                item(key = "day-$day") { DayHeader(dayTitle(day, today), Modifier.testTag(SettingsTags.DISMISSED_DAY + day).semantics(mergeDescendants = true) {}) }
                items(notices, key = { it.id.v }) { n ->
                    NoticeCard(
                        n.copy(isNew = false), onAccept = {}, onDismiss = {},
                        modifier = Modifier.testTag(SettingsTags.DISMISSED_CARD + n.id.v).semantics(mergeDescendants = true) {},
                        onRestore = { scope.launch { db.restore(n.id) } }, restoreTag = SettingsTags.RESTORE + n.id.v,
                    )
                }
            }
        }
    }
}

private fun uses(n: Int) = when (n) {
    0 -> "nenhum lançamento"
    1 -> "1 lançamento"
    else -> "$n lançamentos"
}

/** Ajustes › Marcadores (spec §2.9): rename, join by renaming to another tag's name, delete. */
@Composable
fun Tags(db: Db, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tags = query(db) { it.tags() }
    var open by rememberSaveable { mutableLongStateOf(0L) }
    var deleting by rememberSaveable { mutableLongStateOf(0L) }
    // A tag deleted or merged from elsewhere closes its dialogs.
    LaunchedEffect(tags) {
        if (tags == null) return@LaunchedEffect
        if (tags.none { it.id.v == open }) open = 0
        if (tags.none { it.id.v == deleting }) deleting = 0
    }
    Column {
        TopBar("Marcadores", onBack = back)
        LazyColumn(Modifier.testTag(SettingsTags.TAGS_LIST), contentPadding = PaddingValues(start = Margem.space.page, end = Margem.space.page, bottom = 24.dp)) {
            item {
                Text(
                    "Tocar num marcador muda o nome. Com o nome de outro marcador, os dois viram um só.",
                    sans(15f, 400, 22f), Modifier.padding(bottom = 8.dp), Margem.colors.inkSecondary,
                )
            }
            if (tags == null) return@LazyColumn
            if (tags.isEmpty()) item {
                EmptyState(
                    R.drawable.i_marcador, "Nenhum marcador.", "Marcadores nascem ao lançar.",
                    Modifier.testTag(SettingsTags.TAGS_EMPTY).semantics(mergeDescendants = true) {},
                )
            }
            items(tags, key = { it.id.v }) { t -> ListRow(t.name, Modifier.testTag(SettingsTags.TAG_ROW + t.id.v), sub = uses(t.uses), onClick = { open = t.id.v }) }
        }
    }
    val tag = tags?.firstOrNull { it.id.v == open }
    if (tag != null) TagDialog(db, tag, tags, close = { open = 0 }, delete = { open = 0; deleting = tag.id.v })
    val doomed = tags?.firstOrNull { it.id.v == deleting }
    if (doomed != null) MargemDialog(
        "Apagar o marcador ${doomed.name}?",
        when (doomed.soleUses) {
            0 -> "Nenhum lançamento é apagado."
            1 -> "1 lançamento fica sem marcador e é apagado; o recado dele volta à Pauta."
            else -> "${doomed.soleUses} lançamentos ficam sem marcador e são apagados; os recados deles voltam à Pauta."
        } + when (doomed.solePlanned) {
            // D59: a previsto left without tags goes too.
            0 -> ""
            1 -> " 1 previsto fica sem marcador e é apagado."
            else -> " ${doomed.solePlanned} previstos ficam sem marcador e são apagados."
        },
        "Apagar", { deleting = 0; scope.launch { db.deleteTag(doomed.id) } }, { deleting = 0 }, tag = SettingsTags.TAG_DELETE_DIALOG,
    )
}

/** The tag's name; the name of another tag turns Salvar into "Juntar com …". */
@Composable
private fun TagDialog(db: Db, tag: TagRow, all: List<TagRow>, close: () -> Unit, delete: () -> Unit) {
    val c = Margem.colors
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(tag.id.v) { mutableStateOf(tag.name) }
    var busy by remember { mutableStateOf(false) }
    val clean = name.trim().lowercase()
    val other = all.firstOrNull { it.name == clean && it.id != tag.id }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.testTag(SettingsTags.TAG_DIALOG).padding(horizontal = 32.dp).widthIn(max = 340.dp).fillMaxWidth()
                .shadow(14.dp, shape, ambientColor = c.shadow, spotColor = c.shadow).background(c.surface, shape)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
        ) {
            Text("Marcador", serif(22, 500, 28))
            Field(
                name, { name = it }, "Nome", tag = SettingsTags.TAG_NAME,
                hint = if (other != null) "Os lançamentos de ${tag.name} passam para ${other.name}." else "",
            )
            Button("Apagar", delete, Modifier.testTag(SettingsTags.TAG_DELETE), kind = ButtonKind.TEXT, color = c.overrun)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                Button("Cancelar", close, Modifier.testTag(SettingsTags.TAG_CANCEL), kind = ButtonKind.TEXT)
                Button(
                    if (other != null) "Juntar com ${other.name}" else "Salvar",
                    {
                        busy = true
                        scope.launch {
                            val ok = if (other != null) db.mergeTags(tag.id, other.id) else db.renameTag(tag.id, clean)
                            // Refused: a tag got that name meanwhile; the list updates and the button offers to join.
                            if (ok) close() else busy = false
                        }
                    },
                    Modifier.testTag(SettingsTags.TAG_SAVE), kind = ButtonKind.TEXT, enabled = !busy && clean.isNotEmpty() && clean != tag.name,
                )
            }
        }
    }
}

/** Ajustes › Início do mês e da semana (book 8.6, D41): a draft saved by Salvar, with the warning when edits go. */
@Composable
fun StartDays(db: Db, back: () -> Unit) {
    val today = LocalToday.current()
    val scope = rememberCoroutineScope()
    val saved = query(db) { it.settings() }
    val month = query(db, today) { it.monthOf(today) }
    // 0 = not loaded yet; the week is its ISO number.
    var day by rememberSaveable { mutableIntStateOf(0) }
    var week by rememberSaveable { mutableIntStateOf(0) }
    var ask by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        if (saved == null || day != 0) return@LaunchedEffect
        day = saved.monthStartDay
        week = saved.weekStart.isoDayNumber
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Início do mês e da semana", onBack = back)
        if (saved == null || day == 0) return@Column
        val weekDay = DayOfWeek(week)
        val changed = day != saved.monthStartDay || weekDay != saved.weekStart

        fun save() {
            busy = true
            scope.launch {
                db.setMonthStartDay(day, today)
                db.setWeekStart(weekDay, today)
                back()
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Margem.space.page)) {
            Text(
                "Os mesmos para todas as verbas. O mês vai do dia escolhido até a véspera dele no mês seguinte; dia 29 a 31 vira o último dia nos meses mais curtos.",
                sans(15f, 400, 22f), Modifier.padding(bottom = 8.dp), Margem.colors.inkSecondary,
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mês começa no dia", serif(17, 500, 22))
                Stepper(day, { day = it }, 1..31, tag = SettingsTags.START_DAY)
            }
            Text("Semana começa na", serif(17, 500, 22), Modifier.padding(top = 20.dp, bottom = 8.dp))
            DayPicker(weekDay, { week = it.isoDayNumber }, tag = SettingsTags.START_WEEK)
            Text(
                "Mudar apaga as edições de passo do mês atual em diante, em todas as verbas. Meses passados são recalculados com o dia novo.",
                sans(12.5f, 400, 17f), Modifier.padding(top = 16.dp), Margem.colors.inkTertiary,
            )
        }
        Button(
            "Salvar",
            {
                busy = true
                scope.launch {
                    if (db.startDropsEdits(day, weekDay, today)) {
                        ask = true
                        busy = false
                    } else {
                        save()
                    }
                }
            },
            Modifier.fillMaxWidth().padding(horizontal = Margem.space.page, vertical = 16.dp).testTag(SettingsTags.START_SAVE), enabled = changed && !busy,
        )
        if (ask) MargemDialog(
            when {
                day == saved.monthStartDay -> "Mudar o início da semana?"
                weekDay == saved.weekStart -> "Mudar o início do mês?"
                else -> "Mudar o início do mês e da semana?"
            },
            "As edições de passo de ${month?.let { monthLong(it.month) }.orEmpty()} em diante são apagadas, em todas as verbas, e os passos voltam ao padrão. " +
                "Meses passados são recalculados com o dia novo.",
            "Mudar", { ask = false; save() }, { ask = false }, tag = SettingsTags.START_DIALOG,
        )
    }
}

/** Ajustes › Backup (D14): export a copy; import restores it after a dialog. */
@Composable
fun BackupScreen(db: Db, snack: SnackHost, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalToday.current()
    // The picked file, copied locally, waiting for the dialog.
    var incoming by rememberSaveable { mutableStateOf("") }
    // A picker or a copy is under way: a double tap opens one picker only.
    var busy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        snack.scope.launch {
            snack.show(Snack(if (exportInto(context, db, uri)) "Backup exportado." else "Não deu para exportar o backup."))
            busy = false
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val path = copyIn(context, uri)
            if (path.isEmpty()) snack.show(Snack(NOT_A_BACKUP)) else incoming = path
            busy = false
        }
    }
    Column {
        TopBar("Backup", onBack = back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Margem.space.page)) {
            Text(
                "Exportar grava uma cópia de tudo — verbas, lançamentos, recados e ajustes — num arquivo que você escolhe.",
                sans(15f, 400, 22f), Modifier.padding(bottom = 12.dp), Margem.colors.inkSecondary,
            )
            Button("Exportar", { if (!busy) { busy = true; export.launch("margem-$today.db") } }, Modifier.testTag(SettingsTags.BACKUP_EXPORT), kind = ButtonKind.SECONDARY)
            Text(
                "Importar restaura um backup do Margem no lugar dos dados atuais.",
                sans(15f, 400, 22f), Modifier.padding(top = 24.dp, bottom = 12.dp), Margem.colors.inkSecondary,
            )
            Button("Importar", { if (!busy && incoming.isEmpty()) { busy = true; pick.launch(arrayOf("*/*")) } }, Modifier.testTag(SettingsTags.BACKUP_IMPORT), kind = ButtonKind.SECONDARY)
        }
    }
    if (incoming.isNotEmpty()) MargemDialog(
        "Restaurar este backup?", "Verbas, lançamentos, recados e ajustes atuais dão lugar aos do backup. Não dá para desfazer.", "Restaurar",
        {
            val path = incoming
            incoming = ""
            snack.scope.launch {
                val ok = db.importFrom(path)
                deleteTemp(path)
                snack.show(Snack(if (ok) "Backup restaurado." else NOT_A_BACKUP))
            }
        },
        { deleteTemp(incoming); incoming = "" }, tag = SettingsTags.BACKUP_DIALOG,
    )
}

private const val NOT_A_BACKUP = "Este arquivo não é um backup do Margem."

/** The result line of Importar do cobudget ([SettingsTags.COBUDGET_RESULT]). */
private fun cobudgetResult(r: CobudgetImport): String {
    if (!r.opened) return "Este arquivo não é um backup do cobudget."
    val total = r.pending + r.dismissed
    val head = when (total) {
        0 -> "Nenhum recado importado."
        1 -> "1 recado importado: ${r.pending} na Pauta, ${r.dismissed} em Riscados."
        else -> "$total recados importados: ${r.pending} na Pauta, ${r.dismissed} em Riscados."
    }
    return when (r.skipped) {
        0 -> head
        1 -> "$head 1 não entrou (repetido ou sem texto)."
        else -> "$head ${r.skipped} não entraram (repetidos ou sem texto)."
    }
}

/** Ajustes › Importar do cobudget (D3, D40): only notices; repeats stay out, so it can run again. */
@Composable
fun CobudgetScreen(db: Db, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val path = copyIn(context, uri)
            val r = if (path.isEmpty()) CobudgetImport() else importCobudget(db, path)
            deleteTemp(path)
            result = cobudgetResult(r)
            busy = false
        }
    }
    Column {
        TopBar("Importar do cobudget", onBack = back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Margem.space.page)) {
            Text(
                "Traz os recados de um backup do cobudget. Os riscados lá entram em Riscados; os outros, mesmo os que já tinham lançamento, " +
                    "entram na Pauta para lançar com verba. Fontes, marcadores e apelidos não vêm. Recados repetidos não entram, então importar de novo quase nunca duplica algo.",
                sans(15f, 400, 22f), Modifier.padding(bottom = 12.dp), Margem.colors.inkSecondary,
            )
            Button("Escolher arquivo", { if (!busy) { busy = true; pick.launch(arrayOf("*/*")) } }, Modifier.testTag(SettingsTags.COBUDGET_PICK), kind = ButtonKind.SECONDARY, enabled = !busy)
            if (result.isNotEmpty()) Text(result, serif(17, 400, 24), Modifier.padding(top = 20.dp).testTag(SettingsTags.COBUDGET_RESULT))
        }
    }
}

/** An app with a launcher icon (Ajustes › Fontes). */
class LauncherApp(val packageName: String, val label: String)

/**
 * Apps with a launcher icon, by label. Visibility comes from the LAUNCHER `<queries>` entry in the
 * manifest, so no QUERY_ALL_PACKAGES permission is needed.
 */
suspend fun launcherApps(context: Context): List<LauncherApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map { LauncherApp(it.activityInfo.packageName, it.activityInfo.applicationInfo.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
}

/**
 * Ajustes › Fontes: the apps whose notifications become notices. Chosen ones first; a source whose
 * app is gone still shows, so it can be unchecked.
 */
@Composable
fun Sources(db: Db, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = produceState<List<LauncherApp>?>(null) { value = launcherApps(context) }.value
    val sources = query(db) { it.sources() }
    Column {
        TopBar("Fontes", onBack = back)
        LazyColumn(contentPadding = PaddingValues(start = Margem.space.page, end = Margem.space.page, bottom = 24.dp)) {
            item {
                Text(
                    "Bancos, cartões e carteiras cujas notificações viram recados na Pauta. As outras notificações não são lidas.",
                    sans(15f, 400, 22f), Modifier.padding(bottom = 8.dp), Margem.colors.inkSecondary,
                )
            }
            if (apps == null || sources == null) return@LazyColumn
            val chosen = sources.map { it.packageName }.toSet()
            val gone = sources.filter { s -> apps.none { it.packageName == s.packageName } }.map { LauncherApp(it.packageName, it.label) }
            val all = (apps + gone).sortedWith(compareBy({ it.packageName !in chosen }, { it.label.lowercase() }))
            items(all, key = { it.packageName }) { app ->
                val on = app.packageName in chosen
                ListRow(app.label, sub = app.packageName, onClick = {
                    scope.launch {
                        if (on) db.removeSource(app.packageName)
                        else db.addSource(app.packageName, app.label, Clock.System.now().toEpochMilliseconds())
                    }
                }) { Check(on) }
            }
        }
    }
}
