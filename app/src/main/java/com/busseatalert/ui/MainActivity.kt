package com.busseatalert.ui

import android.Manifest
import android.app.DatePickerDialog
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.busseatalert.check.CheckWorker
import com.busseatalert.data.Store
import com.busseatalert.model.AppSettings
import com.busseatalert.model.Operator
import com.busseatalert.model.Watch
import com.busseatalert.notify.Notifier
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class Tab(val label: String) { SEARCH("Search"), NOTIFICATIONS("Notifications") }

sealed interface Screen {
    data class Home(val tab: Tab) : Screen
    data class Edit(val watch: Watch?) : Screen
    data object Settings : Screen
    data object Log : Screen
    data object Guide : Screen
    data class AlertDetail(val alertIds: List<String>) : Screen
    data class Detail(val watchId: String) : Screen
    data class Seats(val watchId: String, val date: String, val bus: com.busseatalert.model.BusService) : Screen
}

class MainActivity : ComponentActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Red top bar runs under the status bar, so use white status bar icons.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        Notifier.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33) askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        CheckWorker.schedule(this)
        val store = Store(this)
        setContent {
            AppTheme {
                // Opened from a notification: show that route's results, with the Alerts tab underneath.
                val startWatch = intent.getStringExtra(EXTRA_WATCH_ID)?.takeIf { id -> store.watches.any { it.id == id } }
                // The active search lives on the Search tab; older searches open in their own detail screen.
                val active = store.activeWatch().id
                // Tapping the phone notification opens that notification's details.
                val alertIds = intent.getStringArrayExtra(EXTRA_ALERT_IDS)?.toList().orEmpty()
                var stack by remember {
                    mutableStateOf(
                        when {
                            alertIds.isNotEmpty() -> listOf(Screen.Home(Tab.NOTIFICATIONS), Screen.AlertDetail(alertIds))
                            startWatch == null || startWatch == active -> listOf(Screen.Home(Tab.SEARCH))
                            else -> listOf(Screen.Home(Tab.NOTIFICATIONS), Screen.Detail(startWatch))
                        },
                    )
                }
                val navigate: (Screen) -> Unit = { stack = stack + it }
                val back: () -> Unit = { stack = stack.dropLast(1) }
                val selectTab: (Tab) -> Unit = { stack = listOf(Screen.Home(it)) }
                BackHandler(stack.size > 1, back)
                when (val s = stack.last()) {
                    is Screen.Home -> when (s.tab) {
                        Tab.SEARCH -> SearchScreen(store, navigate, selectTab)
                        Tab.NOTIFICATIONS -> NotificationsScreen(store, navigate, selectTab)
                    }
                    is Screen.Edit -> EditScreen(s.watch, store, onDeleted = { stack = listOf(Screen.Home(Tab.SEARCH)) }, done = back)
                    Screen.Settings -> SettingsScreen(store, onSmsEnabled = {
                        askPermissions.launch(arrayOf(Manifest.permission.SEND_SMS))
                    }, done = back)
                    Screen.Log -> LogScreen(store, navigate, back)
                    Screen.Guide -> GuideScreen(back)
                    is Screen.AlertDetail -> AlertDetailScreen(s.alertIds, store, back)
                    is Screen.Detail -> DetailScreen(s.watchId, store, navigate, back)
                    is Screen.Seats -> SeatLayoutScreen(s.watchId, s.date, s.bus, store, back)
                }
            }
        }
    }

    companion object {
        const val EXTRA_WATCH_ID = "watchId"
        const val EXTRA_FROM_ALERT = "fromAlert"
        const val EXTRA_ALERT_IDS = "alertIds"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    fab: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KsrtcRed, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                ),
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = { actions() },
            )
        },
        floatingActionButton = fab,
        bottomBar = bottomBar,
    ) { padding -> content(Modifier.padding(padding).padding(horizontal = 16.dp)) }
}

internal fun describeFilters(w: Watch): String = listOfNotNull(
    when {
        w.minPrice != null && w.maxPrice != null -> "₹${w.minPrice}–₹${w.maxPrice}"
        w.maxPrice != null -> "up to ₹${w.maxPrice}"
        w.minPrice != null -> "from ₹${w.minPrice}"
        else -> null
    },
    w.busTypeKeywords.takeIf { it.isNotBlank() },
    "${w.minSeats}+ seats",
).joinToString(" · ")

@Composable
internal fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
private fun SettingsScreen(store: Store, onSmsEnabled: () -> Unit, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(store.settings) }
    var interval by remember { mutableStateOf(s.intervalMinutes.toString()) }

    var testing by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun current(): AppSettings = s.copy(intervalMinutes = (interval.toIntOrNull() ?: 15).coerceAtLeast(15))

    // Tests use the values on screen, even if they haven't been saved yet.
    fun runTest(label: String, test: suspend (Notifier) -> String) {
        testing = label
        scope.launch {
            testResult = runCatching { test(Notifier(context, current())) }
                .fold({ true to it }, { false to (it.message ?: it.javaClass.simpleName) })
            testing = null
        }
    }

    @Composable
    fun TestButton(label: String, run: (String, suspend (Notifier) -> String) -> Unit, test: suspend (Notifier) -> String) {
        OutlinedButton(onClick = { run(label, test) }, enabled = testing == null, modifier = Modifier.fillMaxWidth()) {
            Text(if (testing == label) "Sending…" else label)
        }
    }

    testResult?.let { (ok, message) ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            confirmButton = { TextButton(onClick = { testResult = null }) { Text("OK") } },
            title = { Text(if (ok) "Sent" else "Failed") },
            text = { Text(message) },
        )
    }

    AppScaffold(title = "Settings", onBack = done) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("Check every (minutes)") },
                supportingText = { Text("Minimum 15 — Android doesn't allow more frequent background checks") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

            Text("Push notifications", style = MaterialTheme.typography.titleSmall)
            CheckRow("Show a notification on this phone", s.pushEnabled) { s = s.copy(pushEnabled = it) }
            TestButton("Test push notification", ::runTest) { it.testPush() }

            Text("SMS", style = MaterialTheme.typography.titleSmall)
            CheckRow("Send an SMS from this phone (carrier charges apply)", s.smsEnabled) {
                s = s.copy(smsEnabled = it); if (it) onSmsEnabled()
            }
            OutlinedTextField(s.smsNumber, { s = s.copy(smsNumber = it) }, label = { Text("Send SMS to (phone number)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            TestButton("Test SMS", ::runTest) { it.testSms() }

            Text("Email (Gmail)", style = MaterialTheme.typography.titleSmall)
            CheckRow("Send an email", s.emailEnabled) { s = s.copy(emailEnabled = it) }
            OutlinedTextField(s.emailFrom, { s = s.copy(emailFrom = it.trim()) }, label = { Text("Your Gmail address (sender)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(s.emailAppPassword, { s = s.copy(emailAppPassword = it.replace(" ", "")) },
                label = { Text("Gmail app password") }, visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Create one at myaccount.google.com/apppasswords (needs 2-step verification)") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(s.emailTo, { s = s.copy(emailTo = it.trim()) }, label = { Text("Send email to") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            TestButton("Test email", ::runTest) { it.testEmail() }

            Button(onClick = {
                store.settings = current()
                CheckWorker.schedule(context)
                done()
            }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            Spacer(Modifier.padding(16.dp))
        }
    }
}
