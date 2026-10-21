package com.busseatalert.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.busseatalert.check.Checker
import com.busseatalert.data.Store
import com.busseatalert.model.BusService
import com.busseatalert.model.CheckResult
import com.busseatalert.model.LogEntry
import com.busseatalert.model.LogKind
import com.busseatalert.model.Watch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME = SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH)
private val GREEN = Color(0xFF2E7D32)
private val AMBER = Color(0xFFEF6C00)
private val RED = Color(0xFFC62828)

internal fun resultSummary(watch: Watch, results: List<CheckResult>): String {
    if (results.isEmpty()) return "Not checked yet"
    val withSeats = results.sumOf { r -> r.buses.count { it.key in r.matchingKeys && it.seatsAvailable >= watch.minSeats } }
    val matching = results.sumOf { it.matchingKeys.size }
    val errors = results.sumOf { it.errors.size }.let { if (it == 0) "" else " · $it site error(s)" }
    return "Last checked ${TIME.format(Date(results.maxOf { it.time }))}: $withSeats of $matching buses have seats$errors"
}

@Composable
internal fun DetailScreen(watchId: String, store: Store, navigate: (Screen) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val watch = store.watches.find { it.id == watchId } ?: run { back(); return }
    val today = java.time.LocalDate.now().toString()
    val dates = watch.journeyDates().filter { it >= today }
    var date by remember { mutableStateOf(dates.firstOrNull()) }
    var version by remember { mutableStateOf(0) }
    val result = remember(date, version) { date?.let { store.result(watchId, it) } }
    var checking by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ResultFilter()) }
    var filtersOpen by remember { mutableStateOf(false) }

    AppScaffold(
        title = watch.displayName,
        onBack = back,
        actions = { IconButton(onClick = { navigate(Screen.Edit(watch)) }) { Icon(Icons.Default.Edit, "Edit watch") } },
    ) { modifier ->
        LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column {
                    Text(watch.operators.joinToString { it.label })
                    Text(describeFilters(watch), style = MaterialTheme.typography.bodySmall)
                    Text(resultSummary(watch, dates.mapNotNull { store.result(watchId, it) }), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (dates.isEmpty()) item {
                Text("All journey dates have passed. Edit the watch to add new dates.", color = MaterialTheme.colorScheme.error)
            }
            if (dates.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dates.forEach { d -> FilterChip(d == date, { date = d }, label = { Text(shortDate(d)) }) }
                }
            }
            if (dates.isNotEmpty()) item {
                Button(
                    onClick = {
                        checking = true
                        scope.launch {
                            withContext(Dispatchers.IO) { Checker(context).runWatch(watch) }
                            version++
                            checking = false
                        }
                    },
                    enabled = !checking && store.appEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (!store.appEnabled) Text("App is OFF — turn it on in Searches")
                    else if (checking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Checking all dates…")
                    } else Text("Check now")
                }
            }
            date?.let { d ->
                item {
                    Text(
                        "Journey date: ${prettyDate(d)}",
                        color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            result?.let { r ->
                val lastChecked = TIME.format(Date(r.time))
                item {
                    Column {
                        Text("Checked $lastChecked", style = MaterialTheme.typography.bodySmall)
                        r.searchedAs.forEach { (op, names) ->
                            Text("$op searched as: $names", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            result?.errors?.forEach { (op, message) ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text("$op: $message", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val r = result
            if (r == null && date != null) item { Text("This date hasn't been checked yet. Tap Check now.") }
            if (r != null) {
                val matching = r.buses.filter { it.key in r.matchingKeys }
                val shown = filter.apply(if (filter.onlyMatching) matching else r.buses, watch.minSeats)
                item {
                    FilterPanel(filter, filtersOpen, { filtersOpen = it }, r.buses, matching, shown.size) { filter = it }
                }
                if (shown.isEmpty()) item {
                    Text(
                        when {
                            r.buses.isEmpty() -> "No buses found on this route for this date."
                            filter.seats == SeatFilter.WITH_SEATS && filter.activeCount == 0 ->
                                "No bus has seats free right now. Open Filters & sort and pick Seats: Any to see full buses too."
                            filter.activeCount > 0 -> "No buses match these filters. Tap Reset to clear them."
                            else -> "No buses match your search's price/type settings. Tap \"All buses\" to see everything."
                        },
                        Modifier.padding(vertical = 16.dp),
                    )
                }
                items(shown, key = { it.key }) { bus ->
                    BusCard(bus, watch, r, matches = bus.key in r.matchingKeys, store = store) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bus.bookUrl.ifBlank { bus.operator.bookingUrl })))
                    }
                }
            }
            item { Spacer(Modifier.padding(24.dp)) }
        }
    }
}

@Composable
internal fun BusCard(
    bus: BusService, watch: Watch, result: CheckResult, matches: Boolean, store: Store,
    onBook: () -> Unit,
) {
    var seatsOpen by remember(bus.key, result.date) { mutableStateOf(false) }
    val dep = datedTime(bus.departure, result.date)
    val arr = datedTime(bus.arrival, result.date, departure = bus.departure)
    val seatColor = when {
        bus.seatsAvailable <= 0 -> Color(0xFF616161) // grey, so "FULL" stands out on Kerala's red cards too
        bus.seatsAvailable < watch.minSeats || bus.seatsAvailable <= 5 -> AMBER
        else -> GREEN
    }
    val previous = result.previousSeats
    val change = when {
        previous == null || !matches -> null
        bus.key !in previous -> "NEW BUS"
        else -> {
            val diff = bus.seatsAvailable - previous.getValue(bus.key)
            when {
                diff > 0 -> "+$diff since last check"
                diff < 0 -> "$diff since last check"
                else -> null
            }
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bus.operator.tint),
        border = androidx.compose.foundation.BorderStroke(1.dp, bus.operator.color.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            bus.operator.label, color = Color.White, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.background(bus.operator.color, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(bus.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    // The bus service type in the site's colour, so it stands out when scanning.
                    Text(bus.busType, color = bus.operator.color, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp))
                }
                Text(
                    if (bus.seatsAvailable <= 0) "FULL" else "${bus.seatsAvailable} seats",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(seatColor, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider()
            // After-midnight departures are easy to mistake for the evening before; spell it out.
            if (dep != null && (dep.time.take(2).toIntOrNull() ?: 12) < 5) {
                Text(
                    "Leaves after midnight: night of ${shortDate(dep.date.minusDays(1))} → early ${shortDate(dep.date)}",
                    color = AMBER, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
                )
            }
            Row {
                Column(Modifier.weight(1f)) {
                    TimeWithDate(dep, bus.departure)
                    if (bus.boarding.isNotBlank()) Text(bus.boarding, style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bus.duration.isNotBlank()) Text(bus.duration, style = MaterialTheme.typography.bodySmall)
                    if (bus.via.isNotBlank()) Text(bus.via, style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    TimeWithDate(arr, bus.arrival)
                    if (bus.dropping.isNotBlank()) Text(bus.dropping, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("₹${bus.fareText.ifBlank { bus.fare.toString() }}", fontWeight = FontWeight.SemiBold)
                    Text("Trip ${bus.serviceId}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (bus.seatsVerified) "Seats counted from the seat map" else "Seats as listed (seat map not opened)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                    if (change != null) Text(change, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall)
                    if (!matches) Text("Outside your filters", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Button(onClick = { seatsOpen = !seatsOpen }) { Text(if (seatsOpen) "Seats ▴" else "Seats ▾") }
                    OutlinedButton(onClick = onBook) { Text("Book ↗") }
                }
            }
            // Seat map drops down inside the card, like the booking sites; tap Seats again to collapse.
            AnimatedVisibility(seatsOpen) {
                Column {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SeatMapPanel(watch, result.date, bus, store)
                    TextButton(onClick = { seatsOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Hide seats ▴") }
                }
            }
        }
    }
}

@Composable
internal fun LogScreen(store: Store, navigate: (Screen) -> Unit, back: () -> Unit) {
    var log by remember { mutableStateOf(store.log) }
    var filter by remember { mutableStateOf<LogKind?>(null) }
    val watchIds = remember { store.watches.map { it.id }.toSet() }
    AppScaffold(title = "Activity", onBack = back, actions = {
        TextButton(onClick = { log = store.log }) { Text("Refresh") }
        TextButton(onClick = { store.clearLog(); log = emptyList() }) { Text("Clear") }
    }) { modifier ->
        LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == null, { filter = null }, label = { Text("All") })
                    FilterChip(filter == LogKind.ALERT, { filter = LogKind.ALERT }, label = { Text("Alerts") })
                    FilterChip(filter == LogKind.CHECK, { filter = LogKind.CHECK }, label = { Text("Checks") })
                    FilterChip(filter == LogKind.ERROR, { filter = LogKind.ERROR }, label = { Text("Errors") })
                }
            }
            val shown = log.filter { filter == null || it.kind == filter }
            if (shown.isEmpty()) item { Text("Nothing here yet.", Modifier.padding(top = 16.dp)) }
            items(shown) { entry ->
                LogCard(entry, canOpen = entry.watchId in watchIds) { navigate(Screen.Detail(entry.watchId!!)) }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun LogCard(entry: LogEntry, canOpen: Boolean, open: () -> Unit) {
    var expanded by remember { mutableStateOf(entry.kind == LogKind.ALERT) }
    val (icon, tint) = when (entry.kind) {
        LogKind.ALERT -> Icons.Default.Notifications to GREEN
        LogKind.ERROR -> Icons.Default.Warning to RED
        LogKind.CHECK -> Icons.Default.Info to MaterialTheme.colorScheme.primary
    }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.message, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(TIME.format(Date(entry.time)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                if (entry.detail.isNotBlank()) Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.outline)
            }
            if (expanded && entry.detail.isNotBlank()) {
                Text(entry.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (expanded && canOpen) {
                TextButton(onClick = open) { Text("Open this route") }
            }
        }
    }
}

/** "Sun 25 Oct" over "00:25"; falls back to the raw text if the time can't be read. */
@Composable
private fun TimeWithDate(dated: DatedTime?, raw: String) {
    if (dated == null) {
        Text(raw, style = MaterialTheme.typography.titleMedium)
        return
    }
    Text(shortDate(dated.date), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
    Text(dated.time, style = MaterialTheme.typography.titleMedium)
}
