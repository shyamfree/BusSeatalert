package com.busseatalert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.busseatalert.check.loadRouteStops
import com.busseatalert.data.Store
import com.busseatalert.model.BusService
import com.busseatalert.model.RouteStops
import com.busseatalert.model.StopTime
import com.busseatalert.model.Watch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val STOP_TIME = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)

internal fun StopTime.timeText(): String =
    runCatching { LocalDateTime.parse(time).format(STOP_TIME) }.getOrDefault("")

/** One-line summary for the card: the first boarding point and its time. */
internal fun RouteStops.boardingSummary(): String? = boarding.firstOrNull()?.let { b ->
    listOf(b.name, b.timeText()).filter { it.isNotBlank() }.joinToString(" · ") +
        if (boarding.size > 1) "  (+${boarding.size - 1} more)" else ""
}

/**
 * Boarding points in the From city and dropping points in the To city with times, the route and
 * the operator's notes. Uses what the last check saved, or fetches it live.
 */
@Composable
internal fun StopsPanel(watch: Watch, date: String, bus: BusService, store: Store) {
    var reload by remember { mutableIntStateOf(0) }
    var stops by remember { mutableStateOf(bus.stops) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        if (stops != null && reload == 0) return@LaunchedEffect
        loading = true; error = null
        runCatching { loadRouteStops(watch, date, bus) }
            .onSuccess { s -> stops = s; s?.let { store.updateStops(watch.id, date, bus.key, it) } }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading boarding points…")
            }
            error != null -> Text("Couldn't load boarding points: $error", color = MaterialTheme.colorScheme.error)
            stops == null -> Text("${bus.operator.label} doesn't list boarding points for this bus.")
        }
        stops?.let { s ->
            if (s.boarding.isNotEmpty()) StopList("Boarding (${watch.fromFor(bus.operator)})", s.boarding, Color(0xFF2E7D32))
            if (s.dropping.isNotEmpty()) StopList("Dropping (${watch.toFor(bus.operator)})", s.dropping, Color(0xFFC62828))
            if (s.via.isNotBlank()) Text("Route: via ${s.via}", style = MaterialTheme.typography.bodySmall)
            s.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
        if (!loading) TextButton(onClick = { reload++ }) { Text("↻ Refresh boarding points") }
    }
}

@Composable
private fun StopList(title: String, stops: List<StopTime>, dot: Color) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    stops.forEach { st ->
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Text("●", color = dot, modifier = Modifier.padding(end = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(st.name, fontWeight = FontWeight.SemiBold)
                val extra = listOf(st.timeText(), st.note).filter { it.isNotBlank() }.joinToString(" · ")
                if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
