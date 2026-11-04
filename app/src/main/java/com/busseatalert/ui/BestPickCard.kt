package com.busseatalert.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.busseatalert.data.Store
import com.busseatalert.model.BusService
import com.busseatalert.model.Watch
import com.busseatalert.pick.Pick
import com.busseatalert.pick.PickMode
import com.busseatalert.pick.Recommender
import com.busseatalert.pick.SeatPref

private val GOLD = Color(0xFFFFB300)

/**
 * The summary at the top of the results: compares the buses with seats on budget, comfort,
 * journey time and seat position, suggests the best one, and shows the runners-up.
 */
@Composable
internal fun BestPickCard(
    buses: List<BusService>, watch: Watch, date: String, store: Store, onBook: (BusService) -> Unit,
) {
    var mode by remember { mutableStateOf(runCatching { PickMode.valueOf(store.pickMode) }.getOrDefault(PickMode.BALANCED)) }
    var pref by remember { mutableStateOf(runCatching { SeatPref.valueOf(store.seatPref) }.getOrDefault(SeatPref.ANY)) }
    val ranked = remember(buses, mode, pref, watch.minSeats) { Recommender.rank(buses, watch.minSeats, mode, pref) }
    if (ranked.isEmpty()) return
    // The bus shown in full: the top pick unless the user taps another row in the comparison.
    var chosenKey by remember(ranked) { mutableStateOf(ranked.first().bus.key) }
    val chosen = ranked.firstOrNull { it.bus.key == chosenKey } ?: ranked.first()
    var seatsOpen by remember(chosen.bus.key) { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
        border = BorderStroke(1.5.dp, GOLD),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⭐ Best pick for you", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text("from ${ranked.size} with seats", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            Text("What matters most", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PickMode.entries.forEach { m ->
                    FilterChip(m == mode, { mode = m; store.pickMode = m.name }, label = { Text(m.label) })
                }
            }
            Text("Seat I'd like", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SeatPref.entries.forEach { p ->
                    FilterChip(p == pref, { pref = p; store.seatPref = p.name }, label = { Text(p.label) })
                }
            }

            PickDetails(chosen, isTop = chosen == ranked.first(), date)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { seatsOpen = !seatsOpen }, modifier = Modifier.weight(1f)) {
                    Text(if (seatsOpen) "Seats ▴" else "Seats ▾")
                }
                OutlinedButton(onClick = { onBook(chosen.bus) }, modifier = Modifier.weight(1f)) { Text("Book ↗") }
            }
            AnimatedVisibility(seatsOpen) { SeatMapPanel(watch, date, chosen.bus, store) }

            if (ranked.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Compare (tap one to see it above)", style = MaterialTheme.typography.labelMedium)
                CompareHeader()
                ranked.take(5).forEachIndexed { i, p ->
                    CompareRow(i + 1, p, pref, selected = p.bus.key == chosen.bus.key) { chosenKey = p.bus.key }
                }
            }
        }
    }
}

@Composable
private fun PickDetails(p: Pick, isTop: Boolean, date: String) {
    val bus = p.bus
    val dep = datedTime(bus.departure, date)
    val arr = datedTime(bus.arrival, date, departure = bus.departure)
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (!isTop) Text("Showing another option. The top pick is #1 below.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                bus.operator.label, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(bus.operator.color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stars(p.comfort), color = GOLD, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("₹${bus.fareText.ifBlank { bus.fare.toString() }}", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
        }
        Text(bus.busType, color = bus.operator.color, fontWeight = FontWeight.Bold)
        Text(
            "${dep?.let { "${shortDate(it.date)} ${it.time}" } ?: bus.departure} → " +
                "${arr?.let { "${shortDate(it.date)} ${it.time}" } ?: bus.arrival}" +
                (p.durationMinutes?.let { "  ·  ${Recommender.hm(it)}" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (bus.boarding.isNotBlank()) Text("${bus.boarding} → ${bus.dropping}", style = MaterialTheme.typography.bodySmall)
        Text("${bus.seatsAvailable} seats free", fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
        Text("Why:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
        p.reasons.forEach { r ->
            Text("• $r", style = MaterialTheme.typography.bodySmall,
                color = if (r.startsWith("Only") || r.startsWith("No ")) Color(0xFFB45309) else Color.Unspecified)
        }
    }
}

@Composable
private fun CompareHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        listOf("#" to 0.35f, "Bus" to 2.4f, "Fare" to 0.9f, "Comfort" to 1.1f, "Time" to 1f, "Seats" to 0.8f).forEach { (t, w) ->
            Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(w))
        }
    }
}

@Composable
private fun CompareRow(rank: Int, p: Pick, pref: SeatPref, selected: Boolean, onClick: () -> Unit) {
    val bus = p.bus
    val seats = if (pref == SeatPref.ANY) "${bus.seatsAvailable}" else "${p.preferredSeats ?: "?"}/${bus.seatsAvailable}"
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) bus.operator.tint else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (rank == 1) "⭐" else "$rank", modifier = Modifier.weight(0.35f), fontWeight = FontWeight.Bold)
        Column(Modifier.weight(2.4f)) {
            Text("${bus.departure.take(5)} ${bus.operator.label}", color = bus.operator.color, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(bus.busType, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("₹${bus.fare}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.9f))
        Text(stars(p.comfort), color = GOLD, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.1f))
        Text(p.durationMinutes?.let(Recommender::hm) ?: "?", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(seats, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.8f))
    }
}

/** Comfort 1–5 as stars, e.g. 4.5 → "★★★★½". */
private fun stars(c: Double): String {
    val full = c.toInt()
    val half = c - full >= 0.5
    return "★".repeat(full) + if (half) "½" else ""
}
