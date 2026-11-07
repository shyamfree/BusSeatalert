package com.busseatalert.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.busseatalert.check.CheckWorker
import com.busseatalert.check.Checker
import com.busseatalert.check.currentStatus
import com.busseatalert.notify.Notifier
import com.busseatalert.data.Store
import com.busseatalert.model.Operator
import com.busseatalert.model.Watch
import com.busseatalert.providers.PlaceNames
import com.busseatalert.providers.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private val CHECKED_AT = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)

/** The settings that change which buses are found; editing them means results need a new Search. */
private fun criteria(w: Watch) = w.copy(enabled = true, name = "", notifyPush = true, notifySms = true, notifyEmail = true, notifyWhatsapp = true, notifyNoChange = true, alertOn = false)

/**
 * The app's home: one search (sites, from, to, dates, optional extras) with its results and
 * filters right below. The same search is reused for other journeys by changing from/to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreen(store: Store, navigate: (Screen) -> Unit, selectTab: (Tab) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var w by remember { mutableStateOf(store.activeWatch()) }
    var searching by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val tick = rememberTicker(3000)
    var appOn by remember { mutableStateOf(store.appEnabled) }
    var moreOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ResultFilter()) }
    var filtersOpen by remember { mutableStateOf(false) }

    val today = LocalDate.now().toString()
    val saved = remember(tick, version) { store.watches.find { it.id == w.id } }
    val dates = (saved ?: w).journeyDates().filter { it >= today }
    var pickedDate by remember { mutableStateOf<String?>(null) }
    // The chosen date tab, or the first upcoming date if that one was removed or has passed.
    val date = pickedDate?.takeIf { it in dates } ?: dates.firstOrNull()
    val result = remember(tick, version, date) { date?.let { store.result(w.id, it) } }
    val checking = searching || remember(tick, version) { store.isChecking(w.id) }
    val dirty = saved == null || criteria(saved) != criteria(w)

    fun search() {
        val problem = when {
            w.operators.isEmpty() -> "Tick at least one site"
            w.from.isBlank() || w.to.isBlank() -> "Enter From and To"
            w.journeyDates().none { it >= today } -> "Add a journey date"
            else -> null
        }
        if (problem != null) {
            Toast.makeText(context, problem, Toast.LENGTH_SHORT).show()
            return
        }
        val next = w.copy(enabled = true, dates = w.journeyDates().filter { it >= today })
        // A different search (route, dates, sites, filters) needs its alert set again, and the phone
        // notification about the previous search goes away.
        val changed = saved == null || criteria(saved).copy(dates = saved.journeyDates().filter { it >= today }) != criteria(next)
        val watch = if (changed) next.copy(alertOn = false) else next
        if (changed && saved?.alertOn == true) Notifier.cancelPhoneNotification(context)
        w = watch
        store.upsertWatch(watch)
        searching = true
        scope.launch {
            withContext(Dispatchers.IO) { Checker(context).runWatch(watch, manual = true) }
            searching = false
            version++
        }
    }

    // Saves options that don't change the search (name, alerts) without needing a new Search.
    fun saveQuietly(next: Watch) {
        w = next
        if (saved != null && criteria(saved) == criteria(next)) store.upsertWatch(next)
    }

    AppScaffold(
        title = "Bus Seat Alert",
        actions = {
            IconButton(onClick = { navigate(Screen.Guide) }) { Icon(Icons.Default.Info, "How the app works") }
            IconButton(onClick = { navigate(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        },
        bottomBar = { BottomTabs(Tab.SEARCH, store, selectTab) },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                AlertsBar(appOn, store.settings.intervalMinutes) { on ->
                    store.appEnabled = on
                    appOn = on
                    if (on) {
                        CheckWorker.schedule(context)
                        CheckWorker.runNow(context, w.id)
                    } else CheckWorker.stopAll(context)
                }
            }

            // ---- The search form ----
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Search on", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Operator.entries.forEach { op ->
                                FilterChip(
                                    selected = op in w.operators,
                                    onClick = { w = w.copy(operators = if (op in w.operators) w.operators - op else w.operators + op) },
                                    label = { Text(op.label, color = op.color, fontWeight = FontWeight.Bold) },
                                    leadingIcon = { Text(if (op in w.operators) "☑" else "☐", color = op.color) },
                                )
                            }
                        }
                        PlaceField(
                            label = "From", value = w.from, operators = w.operators, isFrom = true, picks = w.fromsBySite,
                            onType = { w = w.copy(from = it, fromBySite = emptyMap(), fromsBySite = keepPicks(it, w.fromsBySite)) },
                            onToggle = { op, name ->
                                w = w.copy(from = w.from.ifPicked(name), fromBySite = emptyMap(), fromsBySite = w.fromsBySite.toggle(op, name))
                            },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                w = w.copy(from = w.to, to = w.from, fromBySite = w.toBySite, toBySite = w.fromBySite,
                                    fromsBySite = w.tosBySite, tosBySite = w.fromsBySite)
                            }) { Text("⇅ Swap") }
                        }
                        PlaceField(
                            label = "To", value = w.to, operators = w.operators, isFrom = false, picks = w.tosBySite,
                            onType = { w = w.copy(to = it, toBySite = emptyMap(), tosBySite = keepPicks(it, w.tosBySite)) },
                            onToggle = { op, name ->
                                w = w.copy(to = w.to.ifPicked(name), toBySite = emptyMap(), tosBySite = w.tosBySite.toggle(op, name))
                            },
                        )

                        Text("Journey date(s)", style = MaterialTheme.typography.labelLarge)
                        DateChips(w.journeyDates().filter { it >= today }) { d -> w = w.copy(dates = w.journeyDates() - d, date = "") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val tomorrow = LocalDate.now().plusDays(1).toString()
                            fun add(d: String) { w = w.copy(dates = (w.journeyDates() + d).filter { it >= today }.distinct().sorted(), date = "") }
                            AssistChip(onClick = { add(today) }, label = { Text("+ Today") })
                            AssistChip(onClick = { add(tomorrow) }, label = { Text("+ Tomorrow") })
                            AssistChip(onClick = {
                                val d = LocalDate.now().plusDays(2)
                                DatePickerDialog(context, { _, y, m, day -> add(LocalDate.of(y, m + 1, day).toString()) },
                                    d.year, d.monthValue - 1, d.dayOfMonth).apply {
                                    datePicker.minDate = System.currentTimeMillis() - 1000
                                }.show()
                            }, label = { Text("+ Pick a date") })
                        }

                        TextButton(onClick = { moreOpen = !moreOpen }) {
                            Text(if (moreOpen) "More options ▴" else "More options ▾  (price, bus types, alerts)")
                        }
                        AnimatedVisibility(moreOpen) { MoreOptions(w, onChange = { w = it }, onQuietChange = ::saveQuietly) }

                        Button(onClick = ::search, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                            if (checking) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("Searching…")
                            } else Text("🔍  Search")
                        }
                        if (dirty && saved?.from?.isNotBlank() == true) {
                            Text("You've changed the search. Press Search to update the results and alerts.",
                                style = MaterialTheme.typography.bodySmall, color = Color(0xFF8D4B00))
                        }
                    }
                }
            }

            // ---- Results ----
            val shownWatch = saved
            if (shownWatch == null || shownWatch.from.isBlank()) {
                item { Text("Pick the sites, enter From, To and a date, then press Search.", Modifier.padding(8.dp)) }
                item { Spacer(Modifier.padding(24.dp)) }
                return@LazyColumn
            }
            if (dates.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dates.forEach { d -> FilterChip(d == date, { pickedDate = d }, label = { Text(shortDate(d)) }) }
                }
            }
            date?.let { d ->
                item {
                    Text(
                        "${shownWatch.from} → ${shownWatch.to} · ${prettyDate(d)}",
                        color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            item(key = "alert") {
                AlertSetCard(shownWatch, appOn, checking) { on ->
                    val updated = shownWatch.copy(alertOn = on)
                    store.upsertWatch(updated)
                    w = w.copy(alertOn = on)
                    if (on) {
                        CheckWorker.schedule(context)
                        Notifier(context, store.settings).sendAlertSet(updated, currentStatus(store, updated))
                    } else Notifier.cancelPhoneNotification(context)
                    version++
                }
            }
            val r = result
            if (r == null) {
                item { Text(if (checking) "Searching the sites…" else "Not searched yet for this date. Press Search.", Modifier.padding(8.dp)) }
            } else {
                item {
                    Column {
                        Text("Checked ${CHECKED_AT.format(Date(r.time))}" + if (checking) " · updating…" else "",
                            style = MaterialTheme.typography.bodySmall)
                        r.searchedAs.forEach { (op, names) ->
                            val o = Operator.entries.firstOrNull { it.label == op }
                            Text("$op searched as: $names", style = MaterialTheme.typography.bodySmall, color = o?.color ?: Color.Unspecified)
                        }
                    }
                }
                r.errors.forEach { (op, message) ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text("$op: $message", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val matching = r.buses.filter { it.key in r.matchingKeys }
                val shown = filter.apply(if (filter.onlyMatching) matching else r.buses, shownWatch.minSeats)
                // Suggests the best of the buses on screen, so it follows the filters below.
                if (shown.any { it.seatsAvailable >= shownWatch.minSeats }) item(key = "best-pick") {
                    BestPickCard(shown, shownWatch, r.date, store) { bus ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bus.bookUrl.ifBlank { bus.operator.bookingUrl })))
                    }
                }
                item { FilterPanel(filter, filtersOpen, { filtersOpen = it }, r.buses, matching, shown.size) { filter = it } }
                if (shown.isEmpty()) item {
                    Text(
                        when {
                            r.buses.isEmpty() -> "No buses found on this route for this date."
                            filter.seats == SeatFilter.WITH_SEATS && filter.activeCount == 0 ->
                                "No bus has seats free right now. Open Filters & sort and pick Seats: Any to see full buses too."
                            else -> "No buses match these filters. Tap Reset to clear them."
                        },
                        Modifier.padding(vertical = 16.dp),
                    )
                }
                items(shown, key = { it.key }) { bus ->
                    BusCard(bus, shownWatch, r, matches = bus.key in r.matchingKeys, store = store) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bus.bookUrl.ifBlank { bus.operator.bookingUrl })))
                    }
                }
            }
            item { Spacer(Modifier.padding(24.dp)) }
        }
    }
}

/** What the box will show after picking [siteName], so the suggestion list can stay open for it. */
private fun String.ifPickedPreview(siteName: String): String = ifPicked(siteName)

/**
 * Keeps per-site picks while the typed text still looks like the same place (same alias group or
 * same first letters, e.g. Palghat/Palakkad); typing a different place drops them.
 */
private fun keepPicks(typed: String, picks: Map<Operator, List<String>>): Map<Operator, List<String>> {
    val t = PlaceNames.normalize(typed)
    if (t.length < 3) return picks
    val spellings = PlaceNames.variants(typed)
    return picks.mapValues { (_, names) ->
        names.filter { pick ->
            val n = PlaceNames.normalize(pick)
            n.take(3) == t.take(3) || spellings.any { n.startsWith(it) || it.startsWith(n) }
        }
    }.filterValues { it.isNotEmpty() }
}

private fun Map<Operator, List<String>>.toggle(op: Operator, name: String): Map<Operator, List<String>> {
    val now = this[op].orEmpty()
    val next = if (name in now) now - name else now + name
    return (this + (op to next)).filterValues { it.isNotEmpty() }
}

/** Typed text stays as typed; picking a suggestion into an empty or partial field fills in a tidy name. */
private fun String.ifPicked(siteName: String): String {
    val clean = PlaceNames.normalize(siteName).split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    return if (isBlank() || clean.lowercase().startsWith(trim().lowercase())) clean else this
}

/** Compact app-wide ON/OFF for background checks and alerts. */
@Composable
private fun AlertsBar(on: Boolean, intervalMinutes: Int, onChange: (Boolean) -> Unit) {
    val bg = if (on) Color(0xFF2E7D32) else Color(0xFF616161)
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).clickable { onChange(!on) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (on) "Alerts ON · main switch" else "Alerts OFF · main switch", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                if (on) "Searches with an alert set are checked every ${intervalMinutes.coerceAtLeast(15)} min"
                else "No background checks or notifications. Search still works when you press it.",
                color = Color.White, style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF66BB6A),
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF9E9E9E),
            ),
        )
    }
}

/**
 * A From/To box. While typing (3+ letters) it asks each ticked site for matching place names and
 * lists them in the site's colour. The list stays open so a name can be picked for every site;
 * all ticked sites are then shown below the box with the name each will search.
 */
@Composable
private fun PlaceField(
    label: String, value: String, operators: Set<Operator>, isFrom: Boolean, picks: Map<Operator, List<String>>,
    onType: (String) -> Unit, onToggle: (Operator, String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Text set by a pick (or Done); don't fetch again for it, keep the list as it is.
    var keepFor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Pair<Operator, String>>>(emptyList()) }

    LaunchedEffect(value, operators, focused) {
        if (!focused || value.trim().length < 3 || operators.isEmpty()) {
            suggestions = emptyList(); loading = false
            return@LaunchedEffect
        }
        if (value == keepFor) return@LaunchedEffect
        delay(400) // wait for typing to pause
        loading = true
        suggestions = coroutineScope {
            Operator.entries.filter { it in operators }.map { op ->
                async {
                    runCatching { Providers.get(op).suggestPlaces(value, isFrom) }.getOrDefault(emptyList())
                        .map { op to it }
                }
            }.awaitAll().flatten()
        }
        loading = false
    }

    Column {
        OutlinedTextField(
            value, { keepFor = null; onType(it) },
            label = { Text(label) }, singleLine = true,
            trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        val shown = suggestions.filter { (op, _) -> op in operators }
        if (shown.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 2.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${shown.size} places found · tick every stop you want, per site", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                    TextButton(onClick = { keepFor = value; suggestions = emptyList() }) { Text("Done") }
                }
                HorizontalDivider()
                // Its own scroll area, so long lists (e.g. every Chennai stand) don't push the form away.
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Operator.entries.filter { op -> shown.any { it.first == op } }.forEach { op ->
                        Text(
                            op.label, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.fillMaxWidth().background(op.color).padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        shown.filter { it.first == op }.forEach { (_, name) ->
                            val chosen = name in picks[op].orEmpty()
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (chosen) op.tint else Color.Transparent)
                                    .clickable {
                                        onToggle(op, name)
                                        // The pick may tidy the box's text; keep this list open for more ticks.
                                        keepFor = value.ifPickedPreview(name)
                                    }.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = chosen,
                                    onCheckedChange = { onToggle(op, name); keepFor = value.ifPickedPreview(name) },
                                    colors = CheckboxDefaults.colors(checkedColor = op.color),
                                )
                                Text(name, modifier = Modifier.weight(1f),
                                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        // Every ticked site, in its colour, with the name it will search.
        if (value.isNotBlank()) {
            Column(Modifier.padding(top = 4.dp, start = 4.dp)) {
                Operator.entries.filter { it in operators }.forEach { op ->
                    val names = picks[op].orEmpty()
                    Text(
                        if (names.isNotEmpty()) "${op.label}: ${names.joinToString(", ")} ✓"
                        else "${op.label}: not picked · will try \"${value.trim()}\"",
                        color = if (names.isNotEmpty()) op.color else op.color.copy(alpha = 0.55f),
                        fontWeight = if (names.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Optional extras, tucked away: price, seats, bus types per site, what to alert on and how, a name. */
@Composable
private fun MoreOptions(w: Watch, onChange: (Watch) -> Unit, onQuietChange: (Watch) -> Unit) {
    val store = Store(LocalContext.current)
    val settings = remember { store.settings }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Price & seats", style = MaterialTheme.typography.labelLarge)
        Row {
            OutlinedTextField(w.minPrice?.toString() ?: "", { onChange(w.copy(minPrice = it.filter(Char::isDigit).toIntOrNull())) },
                label = { Text("Min ₹") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(w.maxPrice?.toString() ?: "", { onChange(w.copy(maxPrice = it.filter(Char::isDigit).toIntOrNull())) },
                label = { Text("Max ₹") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(w.minSeats.toString(), { onChange(w.copy(minSeats = it.filter(Char::isDigit).toIntOrNull()?.coerceAtLeast(1) ?: 1)) },
                label = { Text("Seats") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.8f))
        }

        Text("Bus types (per site)", style = MaterialTheme.typography.labelLarge)
        Operator.entries.filter { it in w.operators }.forEach { op ->
            Text(op.label, color = op.color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            BusTypePicker(op, w.fromsFor(op).first(), w.tosFor(op).first(), w.journeyDates(), w.busTypesBySite[op].orEmpty()) { types ->
                onChange(w.copy(busTypesBySite = (w.busTypesBySite + (op to types)).filterValues { it.isNotEmpty() }))
            }
        }

        Text("Alert me when", style = MaterialTheme.typography.labelLarge)
        CheckRow("A newly added bus appears", w.alertNewBus) { onChange(w.copy(alertNewBus = it)) }
        CheckRow("Seats open up (cancellations)", w.alertSeatsFreed) { onChange(w.copy(alertSeatsFreed = it)) }

        Text("Send alerts by", style = MaterialTheme.typography.labelLarge)
        CheckRow("Push notification" + if (!settings.pushEnabled) " (off in Settings)" else "", w.notifyPush) { onQuietChange(w.copy(notifyPush = it)) }
        CheckRow("SMS" + if (!settings.smsEnabled) " (set up in Settings)" else "", w.notifySms) { onQuietChange(w.copy(notifySms = it)) }
        if (settings.smsEnabled && w.notifySms) SmsPermissionRow()
        CheckRow("WhatsApp" + if (!settings.whatsappEnabled) " (set up in Settings)" else "", w.notifyWhatsapp) { onQuietChange(w.copy(notifyWhatsapp = it)) }
        CheckRow("Email" + if (!settings.emailEnabled) " (set up in Settings)" else "", w.notifyEmail) { onQuietChange(w.copy(notifyEmail = it)) }
        CheckRow("Show \"No change\" after each check (silent push only)", w.notifyNoChange) { onQuietChange(w.copy(notifyNoChange = it)) }
        Text(
            "Push, SMS, WhatsApp and email are sent only when something changes: seats free up (e.g. a cancellation), " +
                "more seats appear, or a new bus with seats is added. With \"No change\" on, the phone notification " +
                "is also quietly updated after checks where nothing changed. SMS, WhatsApp and email never send \"no change\". " +
                "Every alert is kept in the Notifications tab.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
        if (w.fromBySite.isNotEmpty() || w.toBySite.isNotEmpty() || w.fromsBySite.isNotEmpty() || w.tosBySite.isNotEmpty()) {
            TextButton(onClick = {
                onChange(w.copy(fromBySite = emptyMap(), toBySite = emptyMap(), fromsBySite = emptyMap(), tosBySite = emptyMap()))
            }) {
                Text("Clear the site-specific place names")
            }
        }
    }
}

/**
 * Under the search: sets or removes the alert for exactly this search. Search alone only shows
 * results; with the alert set, the app checks in the background and notifies on changes.
 */
@Composable
private fun AlertSetCard(watch: Watch, appOn: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    val dates = watch.journeyDates().filter { it >= LocalDate.now().toString() }.joinToString { shortDate(it) }
    if (!watch.alertOn) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFFFFF8E1), RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Happy with this search?", fontWeight = FontWeight.Bold)
            Text(
                "Set an alert and the app keeps checking ${watch.from} → ${watch.to} on $dates, and tells you when " +
                    "seats free up or a new bus with seats is added.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { onChange(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("🔔  Set alert for this search")
            }
        }
    } else {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFFE8F5E9), RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔔 Alert is set for this search", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(false) }) { Text("Turn off") }
            }
            Text("${watch.from} → ${watch.to} · $dates", style = MaterialTheme.typography.bodySmall)
            Text(
                if (appOn) "You'll be notified only when something changes. Changing the search turns this alert off."
                else "Paused: the main Alerts switch at the top is OFF.",
                style = MaterialTheme.typography.bodySmall,
                color = if (appOn) MaterialTheme.colorScheme.outline else Color(0xFF8D4B00),
            )
        }
    }
}
