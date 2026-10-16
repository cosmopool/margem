package com.kaiodelphino.margem.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kaiodelphino.margem.BudgetId
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.MargemApp
import com.kaiodelphino.margem.NoticeId
import com.kaiodelphino.margem.Theme
import com.kaiodelphino.margem.TransactionId
import com.kaiodelphino.margem.isAccessGranted
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.SnackbarHost
import com.kaiodelphino.margem.widgets.Tab
import com.kaiodelphino.margem.widgets.TabBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Intent extra with the tab to open ("budgets", "inbox", …): how the widgets open the app (§7). */
const val EXTRA_TAB = "tab"

/** Navigation (D18): a stack of these, a `when` and BackHandler. */
sealed interface Screen {
    data object Budgets : Screen
    data object Inbox : Screen
    data object Transactions : Screen
    data object Insights : Screen
    data object Settings : Screen
    data class Budget(val id: BudgetId, val month: YearMonth) : Screen
    data object NewBudget : Screen
    data class EditBudget(val id: BudgetId) : Screen
    data class Post(val notice: NoticeId) : Screen
    data class Refund(val estorno: NoticeId) : Screen
    data class Edit(val id: TransactionId) : Screen
    data object NewTransaction : Screen
    data object Dismissed : Screen
    data object Sources : Screen
    data object Tags : Screen
    data object StartDays : Screen
    data object Backup : Screen
    data object Cobudget : Screen
}

private fun encode(s: Screen): String = when (s) {
    Screen.Budgets -> "budgets"
    Screen.Inbox -> "inbox"
    Screen.Transactions -> "transactions"
    Screen.Insights -> "insights"
    Screen.Settings -> "settings"
    is Screen.Budget -> "budget:${s.id.v}:${s.month}"
    Screen.NewBudget -> "newBudget"
    is Screen.EditBudget -> "editBudget:${s.id.v}"
    is Screen.Post -> "post:${s.notice.v}"
    is Screen.Refund -> "refund:${s.estorno.v}"
    is Screen.Edit -> "edit:${s.id.v}"
    Screen.NewTransaction -> "new"
    Screen.Dismissed -> "dismissed"
    Screen.Sources -> "sources"
    Screen.Tags -> "tags"
    Screen.StartDays -> "startDays"
    Screen.Backup -> "backup"
    Screen.Cobudget -> "cobudget"
}

/** The inverse of [encode]; anything unknown is Verbas. */
private fun decode(s: String): Screen {
    val p = s.split(':')
    return when (p[0]) {
        "inbox" -> Screen.Inbox
        "transactions" -> Screen.Transactions
        "insights" -> Screen.Insights
        "settings" -> Screen.Settings
        "budget" -> Screen.Budget(BudgetId(p[1].toLong()), YearMonth.parse(p[2]))
        "newBudget" -> Screen.NewBudget
        "editBudget" -> Screen.EditBudget(BudgetId(p[1].toLong()))
        "post" -> Screen.Post(NoticeId(p[1].toLong()))
        "refund" -> Screen.Refund(NoticeId(p[1].toLong()))
        "edit" -> Screen.Edit(TransactionId(p[1].toLong()))
        "new" -> Screen.NewTransaction
        "dismissed" -> Screen.Dismissed
        "sources" -> Screen.Sources
        "tags" -> Screen.Tags
        "startDays" -> Screen.StartDays
        "backup" -> Screen.Backup
        "cobudget" -> Screen.Cobudget
        else -> Screen.Budgets
    }
}

private val StackSaver = Saver<List<Screen>, ArrayList<String>>(
    save = { stack -> ArrayList(stack.map(::encode)) },
    restore = { saved -> saved.map(::decode) },
)

private fun tabOf(s: Screen): Tab = when (s) {
    Screen.Inbox, is Screen.Post, is Screen.Refund -> Tab.INBOX
    Screen.Transactions, is Screen.Edit, Screen.NewTransaction -> Tab.TRANSACTIONS
    Screen.Insights -> Tab.INSIGHTS
    Screen.Settings, Screen.Dismissed, Screen.Sources, Screen.Tags, Screen.StartDays, Screen.Backup, Screen.Cobudget -> Tab.SETTINGS
    Screen.Budgets, is Screen.Budget, Screen.NewBudget, is Screen.EditBudget -> Tab.BUDGETS
}

private fun screenOf(t: Tab): Screen = when (t) {
    Tab.BUDGETS -> Screen.Budgets
    Tab.INBOX -> Screen.Inbox
    Tab.TRANSACTIONS -> Screen.Transactions
    Tab.INSIGHTS -> Screen.Insights
    Tab.SETTINGS -> Screen.Settings
}

/**
 * Data on screen (D19): runs [block] on IO now and again whenever `db.version` changes (or [keys]).
 * Null until the first result; afterwards the previous result stays while the next one loads.
 */
@Composable
fun <T> query(db: Db, vararg keys: Any?, block: suspend (Db) -> T): T? {
    val version by db.version.collectAsState()
    return produceState<T?>(null, version, *keys) { value = withContext(Dispatchers.IO) { block(db) } }.value
}

fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** Today's date for the screens: the system clock; tests provide a fixed day (D35). */
val LocalToday = staticCompositionLocalOf<() -> LocalDate> { ::today }

class MainActivity : ComponentActivity() {
    private var tabRequest by mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = (application as MargemApp).db
        if (savedInstanceState == null) tabRequest = intent.getStringExtra(EXTRA_TAB)?.let(::decode)
        // One small read on start so the first frame already has the chosen theme.
        val initialTheme = runBlocking { db.settings().theme }
        setContent {
            val theme = query(db) { it.settings().theme } ?: initialTheme
            val dark = when (theme) {
                Theme.SYSTEM -> isSystemInDarkTheme()
                Theme.LIGHT -> false
                Theme.DARK -> true
            }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            MargemTheme(dark) { App(db, tabRequest) { tabRequest = null } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabRequest = intent.getStringExtra(EXTRA_TAB)?.let(::decode)
    }
}

@Composable
fun App(db: Db, tabRequest: Screen?, onTabOpened: () -> Unit) {
    val context = LocalContext.current
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.Budgets)) }
    var granted by remember { mutableStateOf(isAccessGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = isAccessGranted(context) }
    LaunchedEffect(tabRequest) {
        if (tabRequest == null) return@LaunchedEffect
        stack = listOf(tabRequest)
        onTabOpened()
    }
    val appScope = rememberCoroutineScope()
    val snack = remember { SnackHost(appScope) }
    val pending = query(db) { it.pendingCount() } ?: 0

    val screen = stack.last()
    val go: (Screen) -> Unit = { stack = stack + it }
    val back: () -> Unit = { stack = if (stack.size > 1) stack.dropLast(1) else listOf(Screen.Budgets) }
    val home: () -> Unit = { stack = listOf(Screen.Budgets) }
    val replace: (Screen) -> Unit = { stack = stack.dropLast(1) + it }
    // Back from another tab's root goes to Verbas; from Verbas it leaves the app.
    BackHandler(stack.size > 1 || screen != Screen.Budgets, back)
    // Forms (lançar, editar, novo) take the whole page; everything else keeps the tab bar.
    val tabs = screen !is Screen.Post && screen !is Screen.Refund && screen !is Screen.Edit && screen != Screen.NewTransaction &&
        screen != Screen.NewBudget && screen !is Screen.EditBudget

    Column(Modifier.fillMaxSize().background(Margem.colors.paper).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.Budgets -> Budgets(db, snack, go)
                Screen.Inbox -> Inbox(db, granted, snack, go)
                Screen.Transactions -> Transactions(db, snack, go)
                Screen.Insights -> Insights(db)
                Screen.Settings -> Settings(db, granted, go)
                Screen.Sources -> Sources(db, back)
                is Screen.Budget -> BudgetDetailScreen(db, screen.id, screen.month, go, { stack = stack.dropLast(1) + Screen.Budget(screen.id, it) }, back)
                Screen.NewBudget -> NewBudget(db, back)
                is Screen.EditBudget -> EditBudget(db, screen.id, back, home)
                is Screen.Post -> PostScreen(db, screen.notice, snack, go, replace, back)
                is Screen.Refund -> RefundScreen(db, screen.estorno, snack, replace, back)
                is Screen.Edit -> EditTransaction(db, screen.id, snack, go, back)
                Screen.NewTransaction -> NewTransaction(db, snack, go, back)
                Screen.Dismissed -> Dismissed(db, back)
                Screen.Tags -> Tags(db, back)
                Screen.StartDays -> StartDays(db, back)
                Screen.Backup -> BackupScreen(db, snack, back)
                Screen.Cobudget -> CobudgetScreen(db, back)
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
        if (tabs) TabBar(tabOf(screen), { stack = listOf(screenOf(it)) }, badge = pending)
        else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
