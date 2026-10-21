package com.busseatalert.providers

import com.busseatalert.model.BusService
import com.busseatalert.model.Operator
import com.busseatalert.model.Seat
import com.busseatalert.model.SeatLayout
import com.busseatalert.model.SeatStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Kerala (onlineksrtcswift.com) and Karnataka (ksrtc.in) both run the same booking platform,
 * with a JSON city list at /api/resource/getStaticCityList and route search at searchRoutesV4.
 */
class IamgdsProvider(
    override val operator: Operator,
    private val baseUrl: String,
    private val http: OkHttpClient,
) : BusProvider {
    private data class City(val id: String, val name: String)

    @Volatile private var cities: List<City>? = null
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(from: String, to: String, date: String): RouteSearch = withContext(Dispatchers.IO) {
        val all = cities()
        val start = PlaceNames.best(from, all) { it.name }
            ?: throw IllegalArgumentException("${operator.label} doesn't know the place \"$from\". Use \"Find names on each site\" in the watch.")
        val end = PlaceNames.best(to, all) { it.name }
            ?: throw IllegalArgumentException("${operator.label} doesn't know the place \"$to\". Use \"Find names on each site\" in the watch.")
        val url = "$baseUrl/api/resource/searchRoutesV4".toHttpUrl().newBuilder()
            .addQueryParameter("fromCityID", start.id)
            .addQueryParameter("toCityID", end.id)
            .addQueryParameter("fromCityName", start.name)
            .addQueryParameter("toCityName", end.name)
            .addQueryParameter("journeyDate", date)
            .addQueryParameter("mode", "oneway")
            .build()
        val body = get(url.toString())
        // An empty body means no services on this route/date.
        val buses = if (body.isBlank()) emptyList()
        else json.parseToJsonElement(body).jsonArray.mapNotNull { parse(it.jsonObject, date, start, end) }
        RouteSearch(start.name, end.name, buses)
    }

    /**
     * Reads the seat map. The search list's AvailableSeats on ksrtc.in is often a few seats off
     * from the seat map, which is what the user sees when booking.
     * Gender "F" on a free seat means it's reserved for women; on a booked seat, a woman booked it.
     */
    override suspend fun seatLayout(bus: BusService, date: String): SeatLayout = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/resource/seatArrangement".toHttpUrl().newBuilder()
            .addQueryParameter("routeID", bus.ref)
            .addQueryParameter("journeyDate", date)
            .build()
        val seats = json.parseToJsonElement(get(url.toString())).jsonObject["APIGetChartMicrositeResult"]
            ?.jsonObject?.get("Seats") as? JsonArray ?: error("${operator.label} seat map didn't load")
        SeatLayout(seats.mapNotNull { e ->
            val o = e.jsonObject
            fun int(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            fun bool(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull == "true"
            val label = (o["SeatLabel"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (label.isBlank() || bool("IsAisle")) return@mapNotNull null
            val free = int("IsAvailable") == 1
            val female = (o["Gender"] as? JsonPrimitive)?.contentOrNull.equals("F", ignoreCase = true)
            val femaleOnly = bool("HasFemaleValidation") || bool("HasDoubleSeaterFemaleValidation") || bool("HasDoubleSlpFemaleValidation")
            Seat(
                label = label, deck = int("Deck").coerceAtLeast(1), row = int("RowNo"), col = int("ColumnNo"),
                width = int("Width").coerceAtLeast(1), height = int("Height").coerceAtLeast(1),
                status = if (free) SeatStatus.AVAILABLE else SeatStatus.BOOKED,
                ladies = free && (female || femaleOnly),
                bookedByWoman = !free && female,
                fare = int("Fare").takeIf { it > 0 },
            )
        })
    }

    override suspend fun suggestPlaces(query: String, isFrom: Boolean): List<String> = withContext(Dispatchers.IO) {
        val spellings = PlaceNames.variants(query)
        cities().map { it.name }.distinct()
            .filter { name -> PlaceNames.normalize(name).let { n -> spellings.any { n.contains(it) } } }
            .sortedBy { name -> PlaceNames.normalize(name).let { n -> if (spellings.any { n.startsWith(it) }) 0 else 1 } }
            .take(20)
    }

    private fun cities(): List<City> = cities ?: loadCities().also { cities = it }

    private fun parse(o: JsonObject, date: String, start: City, end: City): BusService? {
        val from = start.name
        val to = end.name
        fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull ?: 0
        if (int("IsReservable") == 0 && o.containsKey("IsReservable")) return null
        val tripCode = str("TripCode").ifBlank { str("RouteScheduleId") }.ifBlank { return null }
        val departure = runCatching { LocalDateTime.parse(str("DepartureTime")) }.getOrNull()
        val arrival = runCatching { LocalDateTime.parse(str("ArrivalTime")) }.getOrNull()
        val fares = listOf("SeaterFareNAC", "SleeperFareNAC", "SeaterFareAC", "SleeperFareAC").map(::int).filter { it > 0 }.distinct().sorted()
        val fare = fares.minOrNull() ?: int("Fare")
        val duration = if (departure != null && arrival != null) {
            val mins = java.time.Duration.between(departure, arrival).toMinutes()
            if (mins > 0) "%dh %02dm".format(mins / 60, mins % 60) else ""
        } else ""
        return BusService(
            operator = operator,
            serviceId = tripCode,
            name = listOf(str("RouteName"), str("BusLabel")).filter { it.isNotBlank() }.joinToString(" "),
            busType = str("ServiceType").ifBlank { str("BusType") },
            departure = departure?.let { time(it, date) } ?: str("DepartureTime"),
            arrival = arrival?.let { time(it, date) } ?: str("ArrivalTime"),
            fare = fare,
            seatsAvailable = int("AvailableSeats"),
            boarding = PlaceNames.normalize(from).replaceFirstChar { it.uppercase() },
            dropping = PlaceNames.normalize(to).replaceFirstChar { it.uppercase() },
            duration = duration,
            via = str("ViaPlaces").takeIf { it.isNotBlank() }?.let { "Via $it" }.orEmpty(),
            fareText = fares.joinToString("/").ifBlank { fare.toString() },
            ref = str("RouteScheduleId"),
            bookUrl = seatChartUrl(str("RouteScheduleId"), start, end, date),
        )
    }

    /**
     * The site's own seat-chart page for one bus, with route and date filled in, e.g.
     * /seatchart/10055~10049~368~296~2026-10-25/368/Bengaluru/296/Mysuru/25-10-2026/undefined/oneway/false/chart.
     * Falls back to the pre-filled search page when the bus id is missing.
     */
    private fun seatChartUrl(routeScheduleId: String, start: City, end: City, date: String): String {
        val day = LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return if (routeScheduleId.isNotBlank()) {
            "$baseUrl/seatchart/${enc(routeScheduleId).replace("%7E", "~")}/${start.id}/${enc(start.name)}/${end.id}/${enc(end.name)}/$day" +
                "/undefined/oneway/false/chart?stationInFromCity=&stationInToCity=&singleLady=0"
        } else {
            "$baseUrl/search?mode=oneway&fromCity=${start.id}%7C${enc(start.name)}&toCity=${end.id}%7C${enc(end.name)}&departDate=$day"
        }
    }

    /** "HH:mm", with the day noted when it isn't the journey date (overnight buses). */
    private fun time(t: LocalDateTime, journeyDate: String): String {
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(journeyDate), t.toLocalDate())
        return if (days == 0L) hhmm else "$hhmm (${if (days > 0) "+" else ""}$days day)"
    }

    /** Karnataka returns {"0": {ID, Name}}, Kerala returns {"id": "name"}; accept both. */
    private fun loadCities(): List<City> {
        val data = json.parseToJsonElement(get("$baseUrl/api/resource/getStaticCityList")).jsonObject["data"]
            ?: error("${operator.label} city list was empty")
        val entries: List<Pair<String, JsonElement>> = when (data) {
            is JsonObject -> data.entries.map { it.key to it.value }
            is JsonArray -> data.mapIndexed { i, e -> i.toString() to e }
            else -> emptyList()
        }
        return entries.mapNotNull { (key, value) ->
            when (value) {
                is JsonPrimitive -> City(key, value.content)
                is JsonObject -> {
                    val id = value["ID"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    City(id, value["Name"]?.jsonPrimitive?.content ?: return@mapNotNull null)
                }
                else -> null
            }
        }
    }

    private fun get(url: String): String = withRetry {
        val request = Request.Builder().url(url).header("User-Agent", TnstcProvider.USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "${operator.label} returned HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
    }
}
