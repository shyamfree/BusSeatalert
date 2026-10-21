package com.busseatalert.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.busseatalert.model.BusService
import com.busseatalert.model.Operator

enum class SeatFilter(val label: String) { ANY("Any"), WITH_SEATS("Seats available"), FULL("Full only") }

enum class TimeSlot(val label: String, val hours: Set<Int>) {
    MORNING("Morning 5–12", (5..11).toSet()),
    AFTERNOON("Afternoon 12–5", (12..16).toSet()),
    EVENING("Evening 5–9", (17..20).toSet()),
    NIGHT("Night 9–5", (21..23).toSet() + (0..4).toSet()),
}

enum class SortBy(val label: String) { DEPARTURE("Departure"), ARRIVAL("Arrival"), FARE("Cheapest"), SEATS("Most seats") }

/** Filters and sort for the bus list on the results screen. Empty sets mean "no restriction". */
data class ResultFilter(
    // Defaults: every bus found, only ones with seats free, cheapest first.
    val onlyMatching: Boolean = false,
    /** null = all on. A set lists exactly the chips left on (may be empty = none). */
    val sites: Set<Operator>? = null,
    val seats: SeatFilter = SeatFilter.WITH_SEATS,
    val times: Set<TimeSlot>? = null,
    val types: Set<String>? = null,
    val maxFare: Int? = null,
    val sort: SortBy = SortBy.FARE,
) {
    /** Number of filters changed from the defaults, besides "matching only" and sorting. */
    val activeCount: Int
        get() = listOf(sites != null, seats != SeatFilter.WITH_SEATS, times != null, types != null, maxFare != null)
            .count { it }

    val isDefault: Boolean get() = this == ResultFilter()

    fun apply(buses: List<BusService>, minSeats: Int): List<BusService> = buses
        .filter { sites == null || it.operator in sites }
        .filter {
            when (seats) {
                SeatFilter.ANY -> true
                SeatFilter.WITH_SEATS -> it.seatsAvailable >= minSeats
                SeatFilter.FULL -> it.seatsAvailable <= 0
            }
        }
        .filter { bus -> times == null || hour(bus.departure)?.let { h -> times.any { h in it.hours } } == true }
        .filter { types == null || it.busType in types }
        .filter { maxFare == null || it.fare <= maxFare }
        .let { list ->
            when (sort) {
                SortBy.DEPARTURE -> list.sortedBy { it.departure }
                SortBy.ARRIVAL -> list.sortedBy { it.arrival }
                SortBy.FARE -> list.sortedBy { it.fare }
                SortBy.SEATS -> list.sortedByDescending { it.seatsAvailable }
            }
        }

    companion object {
        fun hour(time: String): Int? = time.take(2).toIntOrNull()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterPanel(
    filter: ResultFilter,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    allBuses: List<BusService>,
    matchingBuses: List<BusService>,
    shownCount: Int,
    onChange: (ResultFilter) -> Unit,
) {
    // Options come from the buses actually found, with counts, so every choice gives results.
    val base = if (filter.onlyMatching) matchingBuses else allBuses
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { onExpand(!expanded) }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Filters & sort" + if (filter.activeCount > 0) " (${filter.activeCount} active)" else "",
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                if (!filter.isDefault) {
                    TextButton(onClick = { onChange(ResultFilter()) }) { Text("Reset") }
                }
                Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.outline)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter.onlyMatching, { onChange(filter.copy(onlyMatching = true)) },
                    label = { Text("Matching my search (${matchingBuses.size})") })
                FilterChip(!filter.onlyMatching, { onChange(filter.copy(onlyMatching = false)) },
                    label = { Text("All buses (${allBuses.size})") })
            }
            Text(
                "Showing $shownCount · ${filter.seats.label.lowercase()} · sorted by ${filter.sort.label.lowercase()}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            if (!expanded) return@Column

            val sites = Operator.entries.filter { op -> base.any { it.operator == op } }
            Section("Site", filter.sites != null, { onChange(filter.copy(sites = null)) }) {
                sites.forEach { op ->
                    FilterChip(filter.sites.isOn(op), { onChange(filter.copy(sites = filter.sites.flip(op, sites))) },
                        label = { Text("${op.label} (${base.count { it.operator == op }})", color = op.color, fontWeight = FontWeight.SemiBold) })
                }
            }
            Section("Seats") {
                SeatFilter.entries.forEach { s ->
                    FilterChip(filter.seats == s, { onChange(filter.copy(seats = s)) }, label = { Text(s.label) })
                }
            }
            Section("Departure time", filter.times != null, { onChange(filter.copy(times = null)) }) {
                TimeSlot.entries.forEach { t ->
                    val n = base.count { b -> ResultFilter.hour(b.departure)?.let { it in t.hours } == true }
                    FilterChip(filter.times.isOn(t), { onChange(filter.copy(times = filter.times.flip(t, TimeSlot.entries))) },
                        label = { Text("${t.label} ($n)") })
                }
            }
            val typeBase = base.filter { filter.sites == null || it.operator in filter.sites }
            val typeCounts = typeBase.groupingBy { it.busType }.eachCount().toSortedMap()
            Section("Bus type", filter.types != null, { onChange(filter.copy(types = null)) }) {
                typeCounts.forEach { (type, n) ->
                    val op = typeBase.first { it.busType == type }.operator
                    FilterChip(filter.types.isOn(type), { onChange(filter.copy(types = filter.types.flip(type, typeCounts.keys))) },
                        label = { Text("$type ($n)", color = op.color) })
                }
            }
            val fares = base.map { it.fare }.filter { it > 0 }
            if (fares.size > 1 && fares.min() < fares.max()) {
                val lo = fares.min(); val hi = fares.max()
                val value = (filter.maxFare ?: hi).coerceIn(lo, hi)
                Text(
                    if (filter.maxFare == null) "Max fare: any (up to ₹$hi)" else "Max fare: ₹$value",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = { v -> onChange(filter.copy(maxFare = v.toInt().takeIf { it < hi })) },
                    valueRange = lo.toFloat()..hi.toFloat(),
                )
            }
            Section("Sort by") {
                SortBy.entries.forEach { s ->
                    FilterChip(filter.sort == s, { onChange(filter.copy(sort = s)) }, label = { Text(s.label) })
                }
            }
        }
    }
}

/** A titled row of chips; "Select all" appears once some chips are turned off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, narrowed: Boolean = false, selectAll: () -> Unit = {}, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (narrowed) TextButton(onClick = selectAll) { Text("Select all") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

/** Chips start all on (null). Tapping one turns just that one off or back on. */
private fun <T> Set<T>?.isOn(item: T) = this == null || item in this

private fun <T> Set<T>?.flip(item: T, all: Collection<T>): Set<T>? {
    val on = this ?: all.toSet()
    val next = if (item in on) on - item else on + item
    return if (next.containsAll(all)) null else next
}
