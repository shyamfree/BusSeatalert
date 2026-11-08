package com.busseatalert.check

import android.content.Context
import com.busseatalert.data.Store
import com.busseatalert.model.BusService
import com.busseatalert.model.CheckResult
import com.busseatalert.model.LogKind
import com.busseatalert.model.Watch
import com.busseatalert.notify.Alert
import com.busseatalert.notify.Notifier
import com.busseatalert.providers.Providers
import com.busseatalert.ui.prettyDate
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * Compares one check's matching buses with what was known before and returns the alerts plus the
 * new memory of buses (last known seats per bus key). Every bus ever seen is remembered, so a failed
 * check or a site briefly leaving a bus out never makes it look "new" later, and a list count (seat
 * map didn't load) never overwrites a count read from the seat map.
 */
fun detectChanges(
    watch: Watch, date: String, matching: List<BusService>, previous: Map<String, Int>?,
): Pair<List<Alert>, Map<String, Int>> {
    val known = previous.orEmpty()
    val snapshot = known + matching.associate { bus ->
        bus.key to (if (bus.seatsVerified || bus.key !in known) bus.seatsAvailable else known.getValue(bus.key))
    }
    val alerts = mutableListOf<Alert>()
    for (bus in matching) {
        val hasSeats = bus.seatsAvailable >= watch.minSeats
        val before = known[bus.key]
        when {
            // First check of this date: report what's bookable right now.
            previous == null -> if (hasSeats) alerts += Alert(watch, date, bus, Alert.Kind.AVAILABLE)
            // A new bus is only worth an alert if it has seats; a full one is just remembered.
            before == null -> if (hasSeats && watch.alertNewBus) alerts += Alert(watch, date, bus, Alert.Kind.NEW_BUS)
            // Only trust a rise counted from the seat map, not the sites' rougher list numbers.
            hasSeats && bus.seatsVerified && bus.seatsAvailable > before ->
                if (watch.alertSeatsFreed) alerts += Alert(watch, date, bus, Alert.Kind.SEATS_FREED, before)
        }
    }
    return alerts to snapshot
}

/** What one check found for one date, for the "no change" notification. */
data class DateStatus(val date: String, val withSeats: List<BusService>, val matching: Int, val failedSites: List<String>)

class Checker(private val context: Context) {
    private val store = Store(context)

    suspend fun runAll() {
        if (!store.appEnabled) return
        store.watches.filter { it.enabled && it.alertOn && it.from.isNotBlank() && it.to.isNotBlank() && upcomingDates(it).isNotEmpty() }
            .forEach { runWatch(it) }
    }

    /**
     * Checks every upcoming date of one watch. [manual] is a search the user pressed: it runs even
     * when the app is switched off (alerts are still not sent then).
     */
    suspend fun runWatch(watch: Watch, manual: Boolean = false) {
        if (!store.appEnabled && !manual) return
        val dates = upcomingDates(watch)
        if (dates.isEmpty()) return
        val summaries = mutableListOf<String>()
        val statuses = mutableListOf<DateStatus>()
        store.setChecking(watch.id, true)
        val alerts = try {
            dates.flatMap { date -> check(watch, date, summaries, statuses) }
        } finally {
            store.setChecking(watch.id, false)
        }
        store.addLog(
            "Checked ${watch.from} → ${watch.to} (${dates.size} date${if (dates.size > 1) "s" else ""})",
            LogKind.CHECK, summaries.joinToString("\n\n"), watch.id,
        )
        alerts.groupBy { it.bus.operator }.forEach { (op, group) ->
            store.addLog("${op.label}: ${Alert.headline(group)}", LogKind.ALERT, group.joinToString("\n") { it.line }, watch.id)
        }
        // A plain Search only shows results; notifications are for searches with an alert set.
        if (!watch.alertOn) return
        // Push/SMS/email only on a change; otherwise optionally a silent "no change" update of the push.
        if (alerts.isNotEmpty()) Notifier(context, store.settings).send(alerts)
        else Notifier(context, store.settings).sendNoChange(watch, statuses)
    }

    private fun upcomingDates(watch: Watch): List<String> {
        val today = LocalDate.now().toString()
        return watch.journeyDates().filter { it >= today }
    }

    private suspend fun check(
        watch: Watch, date: String, summaries: MutableList<String>, statuses: MutableList<DateStatus>,
    ): List<Alert> {
        val errors = mutableMapOf<String, String>()
        val searchedAs = mutableMapOf<String, String>()
        val results = mutableListOf<BusService>()
        for (op in watch.operators) {
            val provider = Providers.get(op)
            try {
                val froms = mutableSetOf<String>()
                val tos = mutableSetOf<String>()
                val seen = mutableSetOf<String>()
                // Every ticked from × to pair; the same bus can show up for several stops, keep it once.
                for (from in watch.fromsFor(op)) for (to in watch.tosFor(op)) {
                    val search = provider.search(from, to, date)
                    froms += search.from; tos += search.to
                    // Seat maps right after their own search (TNSTC keeps only the last search in its session).
                    // Only open seat maps for buses the user cares about, to keep requests down.
                    results += search.buses.filter { seen.add(it.key) }.map { bus ->
                        if (!matches(watch, bus)) return@map bus
                        runCatching { provider.seatLayout(bus, date) }
                            .map { bus.copy(seatsAvailable = it.bookable, seatsVerified = true, seatInfo = it.summary()) }
                            .getOrDefault(bus)
                            .also { delay(300) }
                    }
                }
                searchedAs[op.label] = "${froms.joinToString(", ")} → ${tos.joinToString(", ")}"
            } catch (e: Exception) {
                errors[op.label] = e.message ?: e.javaClass.simpleName
                store.addLog("${op.label} search failed", LogKind.ERROR,
                    "${watch.fromsFor(op).joinToString()} → ${watch.tosFor(op).joinToString()}, ${prettyDate(date)}\n${errors[op.label]}", watch.id)
            }
        }
        val matching = results.filter { matches(watch, it) }
        val previous = store.snapshot(watch.id, date)
        val (found, snapshot) = detectChanges(watch, date, matching, previous)
        // Memory saved by versions before 1.4.1 lost buses after failed checks; rebuild it once
        // quietly rather than alerting on buses that only look new.
        val alerts = if (previous != null && store.needsRebaseline(watch.id, date)) emptyList() else found
        store.markBaselined(watch.id, date)
        store.saveSnapshot(watch.id, date, snapshot)
        store.saveResult(
            watch.id, date,
            CheckResult(System.currentTimeMillis(), date, searchedAs, results, matching.map { it.key }.toSet(), previous, errors),
        )

        val withSeats = matching.filter { it.seatsAvailable >= watch.minSeats }
        statuses += DateStatus(date, withSeats, matching.size, errors.keys.toList())
        summaries += buildString {
            appendLine("${prettyDate(date)}: ${withSeats.size} of ${matching.size} matching buses have ${watch.minSeats}+ seats")
            watch.operators.forEach { op ->
                val found = results.count { it.operator == op }
                appendLine("  ${op.label}: ${errors[op.label]?.let { "failed ($it)" } ?: "$found buses, searched as ${searchedAs[op.label]}"}")
            }
        }.trimEnd()

        return alerts
    }

    private fun matches(watch: Watch, bus: BusService): Boolean {
        if (watch.minPrice != null && bus.fare < watch.minPrice) return false
        if (watch.maxPrice != null && bus.fare > watch.maxPrice) return false
        val types = watch.busTypesBySite[bus.operator]
        if (!types.isNullOrEmpty() && types.none { it.equals(bus.busType, ignoreCase = true) }) return false
        val keywords = watch.busTypeKeywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return keywords.isEmpty() || keywords.any { bus.busType.contains(it, ignoreCase = true) }
    }
}

/**
 * Fetches a fresh seat map for [bus]. Searches again first because TNSTC only serves a seat map
 * for the search in the current session, and so the seat map matches the latest listing.
 */
suspend fun loadSeatLayout(watch: Watch, date: String, bus: BusService): com.busseatalert.model.SeatLayout {
    val op = bus.operator
    val provider = Providers.get(op)
    for (from in watch.fromsFor(op)) for (to in watch.tosFor(op)) {
        val fresh = provider.search(from, to, date).buses.firstOrNull { it.key == bus.key } ?: continue
        return provider.seatLayout(fresh, date)
    }
    error("This bus is no longer listed for ${prettyDate(date)}")
}

/** The latest stored result for each upcoming date of [watch], for status notifications. */
fun currentStatus(store: com.busseatalert.data.Store, watch: Watch): List<DateStatus> {
    val today = LocalDate.now().toString()
    return watch.journeyDates().filter { it >= today }.mapNotNull { date ->
        store.result(watch.id, date)?.let { r ->
            val matching = r.buses.filter { it.key in r.matchingKeys }
            DateStatus(date, matching.filter { it.seatsAvailable >= watch.minSeats }, matching.size, r.errors.keys.toList())
        }
    }
}
