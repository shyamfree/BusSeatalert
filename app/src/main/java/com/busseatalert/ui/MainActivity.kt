package com.busseatalert.ui

import android.Manifest
import android.app.DatePickerDialog
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
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

// FragmentActivity (a ComponentActivity) is needed for the fingerprint/PIN prompt.
class MainActivity : FragmentActivity() {
    private lateinit var store: Store
    private var locked by mutableStateOf(false)
    private var backgroundSince = 0L

    private fun unlock() = AppLock.prompt(this, "Unlock Bus Seat Alert", onSuccess = { locked = false })

    override fun onStart() {
        super.onStart()
        // Lock again after a minute away (and on every fresh start).
        if (::store.isInitialized && store.appLock &&
            (backgroundSince == 0L || System.currentTimeMillis() - backgroundSince > AppLock.RELOCK_AFTER_MS)) locked = true
    }

    override fun onStop() {
        super.onStop()
        backgroundSince = System.currentTimeMillis()
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Red top bar runs under the status bar, so use white status bar icons.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        Notifier.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33) askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        CheckWorker.schedule(this)
        store = Store(this)
        locked = store.appLock
        setContent {
            AppTheme {
                if (locked) LockScreen(::unlock) else {
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
                    Screen.Settings -> SettingsScreen(store, done = back)
                    Screen.Log -> LogScreen(store, navigate, back)
                    Screen.Guide -> GuideScreen(back)
                    is Screen.AlertDetail -> AlertDetailScreen(s.alertIds, store, back)
                    is Screen.Detail -> DetailScreen(s.watchId, store, navigate, back)
                    is Screen.Seats -> SeatLayoutScreen(s.watchId, s.date, s.bus, store, back)
                }
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
private fun SettingsScreen(store: Store, done: () -> Unit) {
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

            AppLockSetting(store)

            Text("Push notifications", style = MaterialTheme.typography.titleSmall)
            CheckRow("Show a notification on this phone", s.pushEnabled) { s = s.copy(pushEnabled = it) }
            TestButton("Test push notification", ::runTest) { it.testPush() }

            Text("SMS", style = MaterialTheme.typography.titleSmall)
            CheckRow("Send an SMS from this phone (carrier charges apply)", s.smsEnabled) { s = s.copy(smsEnabled = it) }
            if (s.smsEnabled) SmsPermissionRow()
            OutlinedTextField(s.smsNumber, { s = s.copy(smsNumber = it) }, label = { Text("Send SMS to (phone number)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            TestButton("Test SMS", ::runTest) { it.testSms() }
            if (s.smsEnabled) {
                Text(
                    if (store.smsDirectBlocked) "This phone blocks apps from sending SMS directly, so each alert comes with a " +
                        "\"Send SMS\" button that opens Messages with the text ready: one tap on Send."
                    else "If your phone blocks direct sending, alerts get a \"Send SMS\" button that opens Messages instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (store.smsDirectBlocked) Color(0xFF8D4B00) else MaterialTheme.colorScheme.outline,
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${s.smsNumber}"))
                                .putExtra("sms_body", "Bus Seat Alert test: SMS through Messages works."),
                        )
                    },
                    enabled = s.smsNumber.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send a test SMS via Messages") }
            }

            Text("WhatsApp", style = MaterialTheme.typography.titleSmall)
            CheckRow("Send alerts to WhatsApp (free, via CallMeBot)", s.whatsappEnabled) { s = s.copy(whatsappEnabled = it) }
            if (s.whatsappEnabled) {
                Text(
                    "One-time setup (2 minutes):\n" +
                        "1. Tap How to get the free key: CallMeBot's page shows its current WhatsApp number.\n" +
                        "2. Save that number in your contacts and send it, from WhatsApp, the message the page shows " +
                        "(\"I allow callmebot to send me messages\").\n" +
                        "3. It replies with your API key. Enter your WhatsApp number and the key below, then Test.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.callmebot.com/blog/free-api-whatsapp-messages/")))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("How to get the free key") }
                OutlinedTextField(s.whatsappPhone, { s = s.copy(whatsappPhone = it.trim()) },
                    label = { Text("Your WhatsApp number, with country code") }, placeholder = { Text("+919876543210") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(s.whatsappApiKey, { s = s.copy(whatsappApiKey = it.trim()) },
                    label = { Text("CallMeBot API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TestButton("Test WhatsApp", ::runTest) { it.testWhatsapp() }
                Text("CallMeBot is a free third-party service for personal use; messages can take up to a minute.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

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

/** Settings row for the app lock; changing it needs the phone's fingerprint or PIN first. */
@Composable
private fun AppLockSetting(store: Store) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    var on by remember { mutableStateOf(store.appLock) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("App lock", style = MaterialTheme.typography.titleSmall)
    CheckRow("Lock the app with fingerprint or phone PIN", on) { want ->
        val problem = AppLock.unavailableReason(context)
        if (want && problem != null) {
            message = problem
            return@CheckRow
        }
        AppLock.prompt(activity, if (want) "Turn on app lock" else "Turn off app lock",
            onSuccess = { store.appLock = want; on = want; message = null },
            onFail = { message = it })
    }
    Text(
        message ?: if (on) "Locked on every start and after a minute away. Notifications still arrive."
        else "Uses your phone's own lock: fingerprint, face, PIN, pattern or password.",
        style = MaterialTheme.typography.bodySmall,
        color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
    )
}
