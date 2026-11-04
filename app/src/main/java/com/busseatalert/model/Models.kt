package com.busseatalert.model

import kotlinx.serialization.Serializable

enum class Operator(val label: String, val bookingUrl: String, val knownBusTypes: List<String>) {
    // Types seen across the busiest routes of each site in Oct 2026; a route may have others,
    // which the watch editor can load from the site.
    KERALA_RTC(
        "Kerala RTC", "https://onlineksrtcswift.com/",
        listOf(
            "SUPER FAST", "SUPER FAST PREMIUM", "FAST PASSENGER", "SUPER DELUXE AIR BUS", "MINNAL SUPER DELUXE AIR BUS",
            "LOWER FLOOR AC", "AC SEATER", "AC SEATER CUM SLEEPER", "AC SEATER VOLVO MULTI AXLE", "AC MULTI AXLE",
            "AC EXECUTIVE BUS", "AC PREMIUM SUPERFAST.", "AC SLEEPER", "AC SLEEPER MULTI AXLE VOLVO SLX",
            "A/C SLEEPER - MULTI AXLE VOLVO-GAJARAJ-SWIFT", "SWIFT-GARUDA A/C SEATER BUS", "SWIFT-HYBRID AC SEATER CUM SLEEPER",
            "SWIFT-HYBRID NON AC SEATER CUM SLEEPER", "GARUDA PREMIUM", "EXECUTIVE CLASS",
        ),
    ),
    KARNATAKA_RTC(
        "Karnataka RTC", "https://ksrtc.in/",
        listOf(
            "KARNATAKA SARIGE", "RAJAHAMSA EXECUTIVE", "ASHWAMEDHA (POINT TO POINT EXPRESS)", "EV POWER PLUS",
            "AIRAVAT", "AIRAVAT CLUB CLASS", "AIRAVAT CLUB CLASS 2.0", "FLYBUS", "NON AC SLEEPER",
            "PALLAKKI (NON AC SLEEPER)", "AMOGHAVARSHA (NON AC SLEEPER)", "AC SLEEPER", "AMBAARI UTSAV (AC SLEEPER)",
            "AMBAARI DREAM CLASS (AC SLEEPER)", "KALYANA RATHA (AC SLEEPER)",
        ),
    ),
    TNSTC(
        "TNSTC", "https://www.tnstc.in/",
        listOf(
            "ORDINARY", "DELUXE", "DELUXE 3X2", "ULTRA DELUXE", "CLASSIC", "AIR CONDITIONED", "AC SLEEPER",
            "AC SLEEPER SEATER", "AC LOWER BERTH SEATER", "NON AC SLEEPER", "NON AC SLEEPER SEATER",
            "NON AC LOWER BERTH SEATER", "VOLVO MULTI AXLE AC SEMI SLEEPER",
        ),
    ),
}

/** What the user wants to monitor. */
@Serializable
data class Watch(
    val id: String,
    val from: String,
    val to: String,
    /** Optional name shown on this search's notifications, e.g. "Diwali trip". */
    val name: String = "",
    /** Which channels this search alerts on (each also needs to be set up in Settings). */
    val notifyPush: Boolean = true,
    val notifySms: Boolean = true,
    val notifyEmail: Boolean = true,
    /** Journey dates as yyyy-MM-dd; only these dates are checked. */
    val dates: List<String> = emptyList(),
    /** Single date from older versions of the app; read via [journeyDates]. */
    val date: String = "",
    /** Site-specific place names, e.g. TNSTC calls Palakkad "PALGHAT". Blank = use [from]/[to]. */
    val fromBySite: Map<Operator, String> = emptyMap(),
    val toBySite: Map<Operator, String> = emptyMap(),
    /** Stops ticked per site; a site searches every from × to pair and merges the buses. */
    val fromsBySite: Map<Operator, List<String>> = emptyMap(),
    val tosBySite: Map<Operator, List<String>> = emptyMap(),
    /** Bus types picked per site, exactly as the site names them. Empty/missing = any type. */
    val busTypesBySite: Map<Operator, Set<String>> = emptyMap(),
    val operators: Set<Operator> = Operator.entries.toSet(),
    val minPrice: Int? = null,
    val maxPrice: Int? = null,
    /** Comma-separated keywords matched against the bus type, e.g. "Sleeper, AC". Empty = any. */
    val busTypeKeywords: String = "",
    val minSeats: Int = 1,
    val alertNewBus: Boolean = true,
    val alertSeatsFreed: Boolean = true,
    val enabled: Boolean = true,
) {
    val displayName: String get() = name.ifBlank { "$from → $to" }

    fun journeyDates(): List<String> = dates.ifEmpty { listOf(date) }.filter { it.isNotBlank() }.distinct().sorted()
    fun fromFor(op: Operator): String = fromBySite[op]?.takeIf { it.isNotBlank() } ?: from
    fun toFor(op: Operator): String = toBySite[op]?.takeIf { it.isNotBlank() } ?: to

    /** All the From names a site should search: ticked stops, else the single name above. */
    fun fromsFor(op: Operator): List<String> = fromsBySite[op].orEmpty().filter { it.isNotBlank() }.ifEmpty { listOf(fromFor(op)) }
    fun tosFor(op: Operator): List<String> = tosBySite[op].orEmpty().filter { it.isNotBlank() }.ifEmpty { listOf(toFor(op)) }
}

/** One bus service as returned by an operator's search. */
@Serializable
data class BusService(
    val operator: Operator,
    /** Stable id for this service on this date (trip code / service number). */
    val serviceId: String,
    val name: String,
    val busType: String,
    val departure: String,
    val arrival: String,
    /** Lowest fare, used for the price filter. */
    val fare: Int,
    val seatsAvailable: Int,
    val boarding: String = "",
    val dropping: String = "",
    val duration: String = "",
    val via: String = "",
    /** Fare as the site shows it, e.g. "437/675" for seater/sleeper. */
    val fareText: String = "",
    /** Provider data needed to open this bus's seat map. */
    val ref: String = "",
    /** True when [seatsAvailable] was counted from the seat map rather than the search list. */
    val seatsVerified: Boolean = false,
    /** Link that opens the operator's site with this route and date already set (seat chart where possible). */
    val bookUrl: String = "",
    /** Where the free seats are; null until the seat map has been read. */
    val seatInfo: SeatSummary? = null,
) {
    val key: String get() = "${operator.name}|$serviceId|$departure"
}

@Serializable
data class AppSettings(
    val intervalMinutes: Int = 15,
    val pushEnabled: Boolean = true,
    val smsEnabled: Boolean = false,
    val smsNumber: String = "",
    val emailEnabled: Boolean = false,
    val emailFrom: String = "",
    val emailAppPassword: String = "",
    val emailTo: String = "",
)

enum class LogKind { CHECK, ALERT, ERROR }

@Serializable
data class LogEntry(
    val time: Long,
    val message: String,
    val kind: LogKind = LogKind.CHECK,
    val detail: String = "",
    val watchId: String? = null,
)

/** Everything the last check of a watch found for one date, for the details screen. */
@Serializable
data class CheckResult(
    val time: Long,
    val date: String = "",
    /** Operator label -> the place names the site actually searched, e.g. "PALGHAT → BENGALURU". */
    val searchedAs: Map<String, String> = emptyMap(),
    /** All buses found, including ones outside the price/type filters. */
    val buses: List<BusService>,
    val matchingKeys: Set<String>,
    /** Seats per bus at the check before this one; missing key = bus wasn't listed then. */
    val previousSeats: Map<String, Int>? = null,
    /** Operator label -> error message, for operators whose search failed. */
    val errors: Map<String, String> = emptyMap(),
)

/** An alert as shown in the in-app Alerts inbox, kept even if the push/SMS/email was missed. */
@Serializable
data class AlertRecord(
    val id: String,
    val time: Long,
    val watchId: String,
    val operator: Operator,
    val title: String,
    val headline: String,
    val lines: List<String>,
    val read: Boolean = false,
    /** The buses this alert was about, so the details screen can show their current status. */
    val buses: List<AlertBus> = emptyList(),
)

@Serializable
data class AlertBus(val date: String, val key: String)
