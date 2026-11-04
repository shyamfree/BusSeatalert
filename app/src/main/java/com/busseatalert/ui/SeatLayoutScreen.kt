package com.busseatalert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busseatalert.check.loadSeatLayout
import com.busseatalert.data.Store
import com.busseatalert.model.BusService
import com.busseatalert.model.Seat
import com.busseatalert.model.SeatLayout
import com.busseatalert.model.SeatStatus
import com.busseatalert.model.Watch

private val UNIT_W = 40.dp
private val UNIT_H = 32.dp

private data class SeatStyle(val fill: Color, val border: Color, val text: Color, val mark: String, val meaning: String)

private val FREE = SeatStyle(Color.White, Color(0xFF2E7D32), Color(0xFF1B5E20), "", "Available")
private val FREE_LADIES = SeatStyle(Color(0xFFFCE4EC), Color(0xFFD81B60), Color(0xFFAD1457), "♀", "Available · ladies only")
private val FREE_ACCESSIBLE = SeatStyle(Color(0xFFE3F2FD), Color(0xFF1565C0), Color(0xFF0D47A1), "♿", "Available · differently-abled only")
private val BOOKED = SeatStyle(Color(0xFFE0E0E0), Color(0xFFBDBDBD), Color(0xFF757575), "", "Booked")
private val BOOKED_WOMAN = SeatStyle(Color(0xFFF3D3DE), Color(0xFFE0A3B8), Color(0xFF8E5A6C), "♀", "Booked by a woman / ladies seat")
private val BLOCKED = SeatStyle(Color(0xFF616161), Color(0xFF424242), Color.White, "", "Not for sale (crew)")

private fun styleOf(seat: Seat): SeatStyle = when (seat.status) {
    SeatStatus.BLOCKED -> BLOCKED
    SeatStatus.BOOKED -> if (seat.bookedByWoman || seat.ladies) BOOKED_WOMAN else BOOKED
    SeatStatus.AVAILABLE -> when {
        seat.accessible -> FREE_ACCESSIBLE
        seat.ladies -> FREE_LADIES
        else -> FREE
    }
}

@Composable
internal fun SeatLayoutScreen(watchId: String, date: String, bus: BusService, store: Store, back: () -> Unit) {
    val watch: Watch = store.watches.find { it.id == watchId } ?: run { back(); return }
    val dep = datedTime(bus.departure, date)
    AppScaffold(title = "Seat layout", onBack = back) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${bus.operator.label} · ${bus.busType}", color = bus.operator.color, fontWeight = FontWeight.Bold)
            Text(
                "${dep?.let { "${shortDate(it.date)}, ${it.time}" } ?: bus.departure} · ${bus.name} · trip ${bus.serviceId}",
                style = MaterialTheme.typography.bodySmall,
            )
            SeatMapPanel(watch, date, bus, store)
            Spacer(Modifier.padding(16.dp))
        }
    }
}

/**
 * The live seat map for one bus, view only. Used as a drop-down inside a bus card; loads when
 * first shown. If the live count differs from the last check, says so and updates the card.
 */
@Composable
internal fun SeatMapPanel(watch: Watch, date: String, bus: BusService, store: Store, onLiveCount: (Int) -> Unit = {}) {
    var reload by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<SeatLayout?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Seat?>(null) }
    val listed = remember { bus.seatsAvailable }
    val lastCheck = remember { store.result(watch.id, date)?.time }

    LaunchedEffect(reload) {
        layout = null; error = null; picked = null
        runCatching { loadSeatLayout(watch, date, bus) }
            .onSuccess {
                layout = it
                // Keep the bus card in step with what the live seat map shows.
                store.updateSeats(watch.id, date, bus.key, it.bookable, it.summary())
                onLiveCount(it.bookable)
            }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Live seat map · view only. Book on the ${bus.operator.label} site.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline)
        val l = layout
        when {
            error != null -> {
                Text("Couldn't load the seat map: $error", color = MaterialTheme.colorScheme.error)
                Button(onClick = { reload++ }) { Text("Try again") }
            }
            l == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Loading the live seat map…")
            }
            l.seats.isEmpty() -> Text("The site shows this bus as full and has no seat map to open.")
            else -> {
                if (l.bookable != listed) {
                    val was = if (listed <= 0) "full" else "$listed free"
                    val at = lastCheck?.let { " (${java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(it))})" }.orEmpty()
                    Text(
                        "Live now: ${l.bookable} free · the last check$at said $was. Seats change as people book and cancel.",
                        color = Color(0xFF8D4B00), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF3E0), RoundedCornerShape(6.dp)).padding(8.dp),
                    )
                }
                Summary(l)
                Legend(l)
                picked?.let { SeatInfo(it) } ?: Text("Tap a seat to see what it is.", style = MaterialTheme.typography.bodySmall)
                l.decks.forEach { deck ->
                    if (l.decks.size > 1) {
                        Text(if (deck == 1) "Lower deck" else "Upper deck",
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    Box(
                        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp))
                            .horizontalScroll(rememberScrollState()).padding(12.dp),
                    ) {
                        DeckGrid(l.seats.filter { it.deck == deck }, picked) { picked = it }
                    }
                }
                TextButton(onClick = { reload++ }) { Text("↻ Refresh seat map") }
            }
        }
    }
}

@Composable
private fun Summary(l: SeatLayout) {
    val free = l.seats.filter { it.status == SeatStatus.AVAILABLE }
    val ladies = free.count { it.ladies }
    val accessible = free.count { it.accessible }
    val general = free.size - ladies - accessible
    Text(
        buildString {
            append("$general open to anyone")
            if (ladies > 0) append(" · $ladies ladies only")
            if (accessible > 0) append(" · $accessible differently-abled only")
            append(" · ${l.seats.count { it.status == SeatStatus.BOOKED }} booked")
        },
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(l: SeatLayout) {
    val used = l.seats.map(::styleOf).distinct()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        used.forEach { st ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(16.dp).background(st.fill, RoundedCornerShape(3.dp)).border(1.5.dp, st.border, RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(st.mark, fontSize = 9.sp, color = st.text) }
                Spacer(Modifier.size(4.dp))
                Text(st.meaning, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SeatInfo(seat: Seat) {
    val st = styleOf(seat)
    Text(
        "Seat ${seat.label}: ${st.meaning}" + (seat.fare?.let { " · ₹$it" } ?: "") +
            if (seat.height > 1) " · sleeper berth" else "",
        color = st.text, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(st.fill, RoundedCornerShape(6.dp))
            .border(1.dp, st.border, RoundedCornerShape(6.dp)).padding(8.dp),
    )
}

/**
 * Draws one deck at the site's grid positions. Empty columns (aisles) are squeezed to a
 * narrow gap so the bus fits on a phone.
 */
@Composable
private fun DeckGrid(seats: List<Seat>, picked: Seat?, onPick: (Seat) -> Unit) {
    if (seats.isEmpty()) return
    val cols = seats.flatMap { s -> (s.col until s.col + s.width) }.distinct().sorted()
    val colX = mutableMapOf<Int, Dp>()
    var x = 0.dp
    cols.forEachIndexed { i, c ->
        if (i > 0 && c - cols[i - 1] > 1) x += UNIT_W / 2 // aisle
        colX[c] = x
        x += UNIT_W
    }
    val minRow = seats.minOf { it.row }
    val height = UNIT_H * (seats.maxOf { it.row + it.height } - minRow)
    Text("Front ↑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    Box(Modifier.padding(top = 16.dp).size(x, height)) {
        seats.forEach { seat ->
            val st = styleOf(seat)
            val selected = seat == picked
            Box(
                Modifier
                    .offset(colX.getValue(seat.col), UNIT_H * (seat.row - minRow))
                    .size(UNIT_W * seat.width - 4.dp, UNIT_H * seat.height - 4.dp)
                    .background(st.fill, RoundedCornerShape(5.dp))
                    .border(if (selected) 3.dp else 1.5.dp, if (selected) Color.Black else st.border, RoundedCornerShape(5.dp))
                    .clickable { onPick(seat) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    seat.label + if (st.mark.isNotEmpty()) "\n${st.mark}" else "",
                    fontSize = 10.sp, lineHeight = 11.sp, color = st.text, textAlign = TextAlign.Center,
                    fontWeight = if (seat.status == SeatStatus.AVAILABLE) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
