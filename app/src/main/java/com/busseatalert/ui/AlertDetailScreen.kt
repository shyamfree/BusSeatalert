package com.busseatalert.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.busseatalert.check.Checker
import com.busseatalert.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AT = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH)

/**
 * One notification opened in full: what it said and when it was fetched, then the same buses'
 * status from the latest check, with Refresh now to check them again on the spot.
 */
@Composable
internal fun AlertDetailScreen(alertIds: List<String>, store: Store, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records = remember { store.alerts.filter { it.id in alertIds } }
    var version by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { alertIds.forEach(store::markRead) }

    val watch = records.firstOrNull()?.let { r -> store.watches.find { it.id == r.watchId } }
    val buses = records.flatMap { it.buses }.distinct()
    val lastCheck = remember(version) {
        watch?.let { w -> buses.mapNotNull { store.result(w.id, it.date)?.time }.maxOrNull() }
    }
    val current = remember(version) {
        watch?.let { w -> buses.associateWith { ab -> store.result(w.id, ab.date) } }.orEmpty()
    }

    AppScaffold(title = "Notification", onBack = back) { modifier ->
        LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (records.isEmpty()) {
                item { Text("This notification has been cleared.", Modifier.padding(top = 16.dp)) }
                return@LazyColumn
            }
            // ---- What the notification said ----
            item { Text("What was found", style = MaterialTheme.typography.titleSmall) }
            records.forEach { a ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = a.operator.tint)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(a.operator.label, color = a.operator.color, fontWeight = FontWeight.Bold)
                            Text(a.headline, fontWeight = FontWeight.SemiBold)
                            a.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            Text(
                                "Fetched ${AT.format(Date(a.time))}",
                                color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 4.dp).background(a.operator.color, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // ---- Same buses, now ----
            if (watch == null) {
                item { Text("The search for this notification no longer exists.", Modifier.padding(top = 8.dp)) }
                return@LazyColumn
            }
            item {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Status now", style = MaterialTheme.typography.titleSmall)
                    Text(
                        lastCheck?.let { "Last checked ${AT.format(Date(it))}" } ?: "Not checked since",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            refreshing = true; refreshError = null
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { Checker(context).runWatch(watch, manual = true) } }
                                    .onFailure { refreshError = it.message }
                                refreshing = false
                                version++
                            }
                        },
                        enabled = !refreshing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Checking the sites…")
                        } else Text("🔄  Refresh now")
                    }
                    refreshError?.let { Text("Couldn't refresh: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (buses.isEmpty()) item {
                Text("This notification was saved by an older version, so its buses can't be shown here. " +
                    "Open the Search tab to see the current results.", style = MaterialTheme.typography.bodySmall)
            }
            buses.forEach { ab ->
                item(key = "${ab.date}|${ab.key}|$version") {
                    val result = current[ab]
                    val bus = result?.buses?.firstOrNull { it.key == ab.key }
                    if (result == null || bus == null) {
                        Text("${shortDate(ab.date)}: this bus isn't in the latest results (it may have left or been withdrawn).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        BusCard(bus, watch, result, matches = bus.key in result.matchingKeys, store = store) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bus.bookUrl.ifBlank { bus.operator.bookingUrl })))
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}
