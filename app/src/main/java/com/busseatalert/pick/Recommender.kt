package com.busseatalert.pick

import com.busseatalert.model.BusService

/** What matters most to the traveller when picking a bus. */
enum class PickMode(val label: String) {
    BALANCED("Balanced"),
    CHEAPEST("Cheapest"),
    COMFORT("Most comfortable"),
    FASTEST("Fastest"),
}

/** Preferred seat position; buses with that kind of seat free score higher. */
enum class SeatPref(val label: String) {
    ANY("Any seat"),
    WINDOW("Window"),
    AISLE("Aisle"),
    LOWER("Lower deck"),
    UPPER("Upper deck"),
}

/** One bus as ranked by [Recommender], with the reasons shown to the user. */
data class Pick(
    val bus: BusService,
    val score: Double,
    /** 1 (basic) to 5 (premium sleeper). */
    val comfort: Double,
    val durationMinutes: Int?,
    /** Free seats of the preferred kind; null when the seat map hasn't been read. */
    val preferredSeats: Int?,
    val reasons: List<String>,
)

/**
 * Suggests the best bus among those with seats, weighing budget, comfort (judged from the
 * operator's bus type name), journey time and whether the preferred seat position is free.
 */
object Recommender {
    private data class Weights(val price: Double, val comfort: Double, val time: Double, val seat: Double)

    private fun weights(mode: PickMode, pref: SeatPref): Weights {
        val w = when (mode) {
            PickMode.BALANCED -> Weights(0.40, 0.35, 0.15, 0.10)
            PickMode.CHEAPEST -> Weights(0.75, 0.12, 0.08, 0.05)
            PickMode.COMFORT -> Weights(0.12, 0.65, 0.13, 0.10)
            PickMode.FASTEST -> Weights(0.20, 0.20, 0.55, 0.05)
        }
        // A chosen seat position should count for more than "any".
        return if (pref == SeatPref.ANY) w else w.copy(seat = w.seat + 0.25)
    }

    /**
     * Comfort from the bus type name, 1–5. The three sites name their classes differently
     * (e.g. Airavat Club Class, Swift Garuda, Ultra Deluxe), so this works on keywords.
     */
    fun comfort(busType: String): Double {
        val t = busType.uppercase()
        val nonAc = Regex("""NON[\s-]*A/?C""").containsMatchIn(t)
        val ac = !nonAc && (Regex("""\bA/?C\b""").containsMatchIn(t) || listOf(
            "AIR CONDITIONED", "AIRAVAT", "GARUDA", "VOLVO", "AMBAARI", "FLYBUS", "EV POWER", "GAJARAJ",
            "KALYANA RATHA", "LOWER FLOOR AC", "AIR BUS",
        ).any { it in t } && "SUPER DELUXE AIR BUS" !in t)
        val sleeper = "SLEEPER" in t || "PALLAKKI" in t || "AMBAARI" in t || "AMOGHAVARSHA" in t || "BERTH" in t
        val semi = "SEMI SLEEPER" in t
        var score = when {
            ac && sleeper && !semi && "SEATER" !in t -> 4.8
            ac && (semi || sleeper) -> 4.5
            ac -> 4.0
            sleeper -> 3.5
            listOf("SUPER DELUXE", "ULTRA DELUXE", "DELUXE", "RAJAHAMSA", "EXECUTIVE", "MINNAL", "PREMIUM", "CLASSIC")
                .any { it in t } -> 3.0
            listOf("SUPER FAST", "EXPRESS", "ASHWAMEDHA").any { it in t } -> 2.3
            else -> 2.0 // ordinary, fast passenger, Karnataka Sarige
        }
        if ("MULTI AXLE" in t || "MULTI-AXLE" in t) score += 0.2
        if ("CLUB CLASS" in t || "DREAM CLASS" in t || "PREMIUM" in t && ac) score += 0.2
        return score.coerceIn(1.0, 5.0)
    }

    /** Journey time in minutes from "7h 45m", TNSTC's "6.25Hrs" (6 h 25 min), or the clock times. */
    fun durationMinutes(bus: BusService): Int? {
        Regex("""(\d+)h\s*(\d+)m""").find(bus.duration)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        Regex("""(\d+)\.(\d{1,2})\s*Hrs""", RegexOption.IGNORE_CASE).find(bus.duration)?.let {
            return it.groupValues[1].toInt() * 60 + it.groupValues[2].padEnd(2, '0').toInt()
        }
        val dep = clock(bus.departure) ?: return null
        val arr = clock(bus.arrival) ?: return null
        val days = Regex("""\(\+(\d+) day""").find(bus.arrival)?.groupValues?.get(1)?.toInt() ?: if (arr < dep) 1 else 0
        return (arr + days * 1440 - dep).takeIf { it > 0 }
    }

    private fun clock(time: String): Int? =
        Regex("""(\d{1,2}):(\d{2})""").find(time)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    fun preferredSeats(bus: BusService, pref: SeatPref): Int? {
        val info = bus.seatInfo
        return when (pref) {
            SeatPref.ANY -> bus.seatsAvailable
            SeatPref.WINDOW -> info?.window
            SeatPref.AISLE -> info?.aisle
            SeatPref.LOWER -> info?.lowerDeck
            SeatPref.UPPER -> info?.upperDeck
        }
    }

    private fun isOvernight(bus: BusService, minutes: Int?): Boolean {
        val dep = clock(bus.departure) ?: return false
        return (minutes ?: 0) >= 360 && (dep >= 19 * 60 || dep < 2 * 60)
    }

    /** Ranks [buses] with at least [minSeats] free, best first. */
    fun rank(buses: List<BusService>, minSeats: Int, mode: PickMode, pref: SeatPref): List<Pick> {
        val candidates = buses.filter { it.seatsAvailable >= minSeats }
        if (candidates.isEmpty()) return emptyList()
        val w = weights(mode, pref)
        val fares = candidates.map { it.fare }
        val times = candidates.mapNotNull(::durationMinutes)
        val comforts = candidates.associateWith { bus ->
            val base = comfort(bus.busType)
            // Lying down matters more on a long night journey.
            val sleeper = bus.seatInfo?.sleeper == true || "SLEEPER" in bus.busType.uppercase()
            if (sleeper && isOvernight(bus, durationMinutes(bus))) (base + 0.3).coerceAtMost(5.0) else base
        }

        // Proportional, so ₹700 vs ₹720 is a small gap but ₹450 vs ₹1300 is a big one.
        fun ratio(value: Int, best: Int?): Double = if (best == null || value <= 0) 0.5 else best.toDouble() / value

        val scored = candidates.map { bus ->
            val minutes = durationMinutes(bus)
            val seats = preferredSeats(bus, pref)
            val seatScore = when {
                pref == SeatPref.ANY -> 1.0
                seats == null -> 0.4 // seat map not read yet
                seats >= minSeats -> 1.0
                else -> 0.0
            }
            val score = w.price * ratio(bus.fare, fares.minOrNull()) +
                w.comfort * ((comforts.getValue(bus) - 1) / 4) +
                w.time * (minutes?.let { ratio(it, times.minOrNull()) } ?: 0.5) +
                w.seat * seatScore
            Pick(bus, score / (w.price + w.comfort + w.time + w.seat), comforts.getValue(bus), minutes, seats, emptyList())
        }.sortedByDescending { it.score }

        return scored.mapIndexed { i, p -> p.copy(reasons = reasons(p, scored, pref, minSeats, best = i == 0)) }
    }

    private fun reasons(p: Pick, all: List<Pick>, pref: SeatPref, minSeats: Int, best: Boolean): List<String> {
        val out = mutableListOf<String>()
        val bus = p.bus
        val minFare = all.minOf { it.bus.fare }
        val maxComfort = all.maxOf { it.comfort }
        val minTime = all.mapNotNull { it.durationMinutes }.minOrNull()
        if (bus.fare == minFare) out += "Cheapest of ${all.size} buses with seats (₹$minFare)"
        else out += "₹${bus.fare - minFare} more than the cheapest (₹$minFare)"
        if (p.comfort >= maxComfort - 0.05) out += "Most comfortable: ${comfortWord(p.comfort)}"
        else out += "Comfort: ${comfortWord(p.comfort)}"
        if (p.durationMinutes != null && p.durationMinutes == minTime && all.size > 1) out += "Fastest: ${hm(p.durationMinutes)}"
        else p.durationMinutes?.let { out += "Journey ${hm(it)}" }
        if (isOvernight(bus, p.durationMinutes) && (bus.seatInfo?.sleeper == true || "SLEEPER" in bus.busType.uppercase())) {
            out += "Overnight trip: a sleeper lets you lie flat"
        }
        val info = bus.seatInfo
        if (info != null) {
            val where = buildList {
                if (info.window > 0) add("${info.window} window")
                if (info.aisle > 0) add("${info.aisle} aisle")
                if (info.lowerDeck > 0) add("${info.lowerDeck} lower deck")
                if (info.upperDeck > 0) add("${info.upperDeck} upper deck")
            }
            if (where.isNotEmpty()) out += "Free: ${where.joinToString(", ")}"
            if (info.ladiesOnly > 0) out += "${info.ladiesOnly} more seat${if (info.ladiesOnly > 1) "s" else ""} for women only"
        }
        if (pref != SeatPref.ANY) {
            out += when (val n = p.preferredSeats) {
                null -> "${pref.label} seats not known yet (open Seats to check)"
                0 -> "No ${pref.label.lowercase()} seats free"
                else -> if (n >= minSeats) "$n ${pref.label.lowercase()} seat${if (n > 1) "s" else ""} free" else "Only $n ${pref.label.lowercase()} seat free"
            }
        }
        if (bus.seatsAvailable <= 3) out += "Only ${bus.seatsAvailable} seat${if (bus.seatsAvailable > 1) "s" else ""} left: book soon"
        return if (best) out else out.take(3)
    }

    fun comfortWord(c: Double): String = when {
        c >= 4.7 -> "premium AC sleeper"
        c >= 4.4 -> "AC sleeper / semi-sleeper"
        c >= 3.9 -> "AC seater"
        c >= 3.4 -> "non-AC sleeper"
        c >= 2.8 -> "deluxe non-AC"
        c >= 2.2 -> "express non-AC"
        else -> "basic non-AC"
    }

    fun hm(minutes: Int): String = "${minutes / 60}h ${"%02d".format(minutes % 60)}m"
}
