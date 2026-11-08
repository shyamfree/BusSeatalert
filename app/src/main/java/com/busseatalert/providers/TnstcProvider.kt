package com.busseatalert.providers

import com.busseatalert.model.BusService
import com.busseatalert.model.Operator
import com.busseatalert.model.RouteStops
import com.busseatalert.model.StopTime
import com.busseatalert.model.Seat
import com.busseatalert.model.SeatLayout
import com.busseatalert.model.SeatStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Searches tnstc.in (OTRS). Place lookup returns "id:code:NAME^" records; the search
 * result is an HTML page with one `<div class="bus-list">` block per service, and each
 * bus's seat map is fetched from the same session.
 */
class TnstcProvider(private val http: OkHttpClient) : BusProvider {
    override val operator = Operator.TNSTC

    private data class Place(val id: String, val code: String, val name: String)

    override suspend fun search(from: String, to: String, date: String): RouteSearch = withContext(Dispatchers.IO) {
        val start = resolveAny(FROM_ACTION, FROM_FIELD, from)
        val end = resolveAny(TO_ACTION, TO_FIELD, to)
        val doj = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val form = FormBody.Builder()
            .add("hiddenStartPlaceID", start.id).add("hiddenEndPlaceID", end.id)
            .add("txtStartPlaceCode", start.code).add("txtEndPlaceCode", end.code)
            .add("selectStartPlace", start.code).add("selectEndPlace", end.code)
            .add("hiddenStartPlaceName", start.name).add("hiddenEndPlaceName", end.name)
            .add("matchStartPlace", start.name).add("matchEndPlace", end.name)
            .add("txtJourneyDate", doj).add("hiddenOnwardJourneyDate", doj)
            .add("hiddenReturnJourneyDate", "DD/MM/YYYY")
            .add("hiddenOnwardTimeSlab", "00:00-23:59")
            .add("hiddenClassCategoryLookupID", "0").add("selectClass", "0")
            .add("languageType", "E").add("hiddenMaxNoOfPassengers", "16")
            .add("txtAdultMales", "1").add("hiddenTotalMales", "1").add("hiddenTotalFemales", "0")
            .add("hiddenUserType", "G").add("checkSingleLady", "N")
            .build()
        // TNSTC also accepts the search as a plain link, so Book can open the results page pre-filled.
        val link = (0 until form.size).joinToString("&", prefix = "$BASE?hiddenAction=SearchService&") {
            "${form.encodedName(it)}=${form.encodedValue(it)}"
        }
        RouteSearch(start.name, end.name, parse(post("$BASE?hiddenAction=SearchService", form)).map { it.copy(bookUrl = link) })
    }

    /**
     * Reads the seat map table. Seat cells carry a class: seatAvailable / seatBooked, ladiesSeat /
     * ladiesSeatBooked (reserved for women), diffableSeat / DiffableSeatBooked (differently-abled),
     * conductorSeat. "UB" labels are upper berths. The list page's "N Seats Available" also counts
     * differently-abled seats, which most passengers can't book.
     */
    override suspend fun seatLayout(bus: BusService, date: String): SeatLayout = withContext(Dispatchers.IO) {
        if (bus.ref.isBlank()) return@withContext SeatLayout(emptyList()) // listed as "Full", no seat map to open
        val (serviceInfo, slNo) = bus.ref.split('|', limit = 2)
        val url = LAYOUT_URL.toHttpUrl().newBuilder()
            .addQueryParameter("hiddenAction", "SubmitToGetOnwardSeatLayout")
            .addQueryParameter("radOnwardServiceID", serviceInfo)
            .addQueryParameter("slNo", slNo)
            .build()
        val html = post(url.toString(), FormBody.Builder().build())
        check(html.contains("chkSelectedList") || html.contains("seatBooked")) { "TNSTC seat map didn't load" }
        SeatLayout(parseLayout(html))
    }

    private fun parseLayout(html: String): List<Seat> {
        val start = html.indexOf("START_ONWARDLAYOT").takeIf { it >= 0 } ?: 0
        val end = html.indexOf("END_ONWARDLAYOT", start).takeIf { it > 0 } ?: html.length
        val seats = mutableListOf<Seat>()
        var row = 0
        var upperStart: Int? = null
        for (tr in TABLE_ROW.findAll(html.substring(start, end))) {
            val cells = LAYOUT_CELL.findAll(tr.groupValues[1]).toList()
            if (cells.isEmpty()) continue
            for (cell in cells) {
                val css = CELL_CLASS.find(cell.groupValues[2])?.groupValues?.get(1) ?: continue
                if (css == "seatBorder") continue
                val label = cell.groupValues[2].replace(Regex("<[^>]*>"), "").replace("&nbsp;", "").replace('\u00A0', ' ').replace(Regex("\\s+"), "").trim()
                if (label.isBlank()) continue
                val upper = label.endsWith("UB", ignoreCase = true)
                if (upper && upperStart == null) upperStart = row
                val lower = css.lowercase()
                seats += Seat(
                    label = label,
                    deck = if (upper) 2 else 1,
                    row = if (upper) row - upperStart!! else row,
                    col = cell.groupValues[1].toInt() - 1,
                    status = when {
                        lower.contains("conductor") -> SeatStatus.BLOCKED
                        lower.contains("booked") -> SeatStatus.BOOKED
                        else -> SeatStatus.AVAILABLE
                    },
                    ladies = lower.startsWith("ladies"),
                    accessible = lower.contains("diffable"),
                )
            }
            row++
        }
        return seats
    }

    /**
     * TNSTC's trip-details popup (the trip code link on its results): POST advanceNewBooking.do with
     * the bus's ids. Its layout isn't parsed field by field yet, so each table row or line becomes a
     * text note. The first boarding point is the start stand shown on the results.
     */
    override suspend fun routeStops(bus: BusService, date: String): RouteStops? = withContext(Dispatchers.IO) {
        val parts = bus.stopsRef.split('|')
        val base = RouteStops(
            boarding = listOfNotNull(bus.boarding.takeIf { it.isNotBlank() }?.let { StopTime(it) }),
            dropping = listOfNotNull(bus.dropping.takeIf { it.isNotBlank() }?.let { StopTime(it) }),
            via = bus.via.removePrefix("Via-").trim(),
        )
        if (parts.size < 6) return@withContext base
        val html = post(LAYOUT_URL, FormBody.Builder()
            .add("ServiceID", parts[0]).add("TripCode", parts[1]).add("StartPlaceID", parts[2])
            .add("EndPlaceID", parts[3]).add("JourneyDate", parts[4]).add("ClassID", parts[5]).build())
        val lines = Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(html).map { row ->
            Regex("(?is)<t[dh][^>]*>(.*?)</t[dh]>").findAll(row.groupValues[1])
                .map { it.groupValues[1].replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }.joinToString(" · ")
        }.filter { it.isNotBlank() }.take(40).toList()
        base.copy(notes = lines)
    }

    /** TNSTC lists the classes on a route directly ("id:NAME^" records), so no search is needed. */
    override suspend fun busTypes(from: String, to: String, dates: List<String>): List<String> = withContext(Dispatchers.IO) {
        val start = resolveAny(FROM_ACTION, FROM_FIELD, from)
        val end = resolveAny(TO_ACTION, TO_FIELD, to)
        val doj = LocalDate.parse(dates.firstOrNull() ?: LocalDate.now().toString()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val body = post(BASE, FormBody.Builder().add("hiddenAction", "GetClass")
            .add("Sid", start.id).add("Eid", end.id).add("Doj", doj).build())
        body.split('^').mapNotNull { it.substringAfter(':', "").trim().takeIf { n -> n.isNotBlank() } }
            .filterNot { it.equals("ALL CLASSES", ignoreCase = true) }
    }

    override suspend fun suggestPlaces(query: String, isFrom: Boolean): List<String> = withContext(Dispatchers.IO) {
        val (action, field) = if (isFrom) FROM_ACTION to FROM_FIELD else TO_ACTION to TO_FIELD
        PlaceNames.variants(query).filter { it.length >= 3 }
            .flatMap { lookup(action, field, it) }.map { it.name }.distinct().take(20)
    }

    /** Tries each known spelling, e.g. "Palakkad" -> "Palghat", which is what TNSTC uses. */
    private fun resolveAny(action: String, field: String, query: String): Place {
        for (name in PlaceNames.variants(query)) {
            val places = lookup(action, field, name)
            // Exact name first: "CHENNAI" covers every Chennai stand.
            PlaceNames.best(name, places) { it.name }?.let { return it }
        }
        throw IllegalArgumentException("TNSTC doesn't know the place \"$query\". Use \"Find names on each site\" in the watch.")
    }

    private fun lookup(action: String, field: String, query: String): List<Place> {
        val body = post(BASE, FormBody.Builder().add("hiddenAction", action).add(field, query.trim()).build())
        return body.split('^').mapNotNull { rec ->
            val p = rec.trim().split(':')
            if (p.size >= 3) Place(p[0], p[1], p.drop(2).joinToString(":")) else null
        }
    }

    private fun post(url: String, form: FormBody): String = withRetry {
        val request = Request.Builder().url(url).post(form).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "TNSTC returned HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
    }

    private fun parse(html: String): List<BusService> = html.split("<div class=\"bus-list\"").drop(1).mapNotNull { block ->
        val corp = OPERATOR_NAME.find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
        val busType = BUS_TYPE.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        val tripCode = TRIP_CODE.find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
        val route = ROUTE_NO.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        val departure = DEPARTURE.find(block)?.groupValues
        val arrival = ARRIVAL.find(block)?.groupValues
        val priceText = PRICE.find(block)?.groupValues?.get(1)?.replace(COMMENT, "").orEmpty()
        // "437/675" is seater/sleeper fare; filter on the cheapest.
        val fare = NUMBER.findAll(priceText).map { it.value.toInt() }.minOrNull() ?: return@mapNotNull null
        val seats = SEATS.find(block)?.groupValues?.get(1)?.toInt() ?: 0
        val layout = SHOW_LAYOUT.find(block)?.groupValues
        BusService(
            operator, tripCode, "$corp $route".trim(), busType,
            departure = departure?.get(1)?.trim().orEmpty(),
            arrival = arrival?.get(1)?.trim().orEmpty(),
            fare = fare,
            seatsAvailable = seats,
            boarding = departure?.get(2)?.trim().orEmpty(),
            dropping = arrival?.get(2)?.trim().orEmpty(),
            duration = DURATION.find(block)?.groupValues?.get(1)?.trim().orEmpty(),
            via = VIA.find(block)?.groupValues?.get(1)?.trim().orEmpty(),
            fareText = priceText.replace(Regex("<[^>]*>|Rs\\.?"), " ").replace(Regex("\\s+"), "").trim(),
            ref = layout?.let { "${it[1]}|${it[2]}" }.orEmpty(),
            stopsRef = TRIP_DETAILS.find(block)?.groupValues?.drop(1)?.joinToString("|").orEmpty(),
        )
    }
        // The same service is sometimes listed twice (once as "Full"); keep the bookable listing.
        .groupBy { it.key }.values.map { dupes -> dupes.maxBy { it.seatsAvailable } }

    companion object {
        private const val BASE = "https://www.tnstc.in/OTRSOnline/jqreq.do"
        private const val LAYOUT_URL = "https://www.tnstc.in/OTRSOnline/advanceNewBooking.do"
        private const val FROM_ACTION = "LoadFromPlaceList"
        private const val FROM_FIELD = "matchStartPlace"
        private const val TO_ACTION = "LoadTOPlaceList"
        private const val TO_FIELD = "matchEndPlace"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        private val OPERATOR_NAME = Regex("""class="[^"]*operator-name">([^<]+)<""")
        private val BUS_TYPE = Regex("""operator-name">[^<]*</span>\s*<span[^>]*>([^<]+)<""")
        private val TRIP_CODE = Regex("""loadTripDetails\('[^']*',\s*'([^']+)'""")
        private val ROUTE_NO = Regex("""</B>\s*/\s*([^<\s]+)""")
        private val DEPARTURE = Regex("""class="text-4 text-dark">([^<]+)</span>\s*<small[^>]*>([^<]*)<""")
        private val ARRIVAL = Regex("""class="text-5 text-dark">([^<]+)</span>\s*<small[^>]*>([^<]*)<""")
        private val DURATION = Regex("""class="text-3 duration">([^<]+)<""")
        private val VIA = Regex("""<b>\s*(Via-[^<]+?)\s*</b>""")
        private val PRICE = Regex("""class="[^"]*\bprice\b[^"]*">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val NUMBER = Regex("""\d+""")
        private val SEATS = Regex("""(\d+)\s+Seats? Available""", RegexOption.IGNORE_CASE)
        /** loadTripDetails('serviceInfo','tripCode','startPlaceID','endPlaceID','dd/MM/yyyy','classID') */
        private val TRIP_DETAILS = Regex("""loadTripDetails\('([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)'\)""")
        private val SHOW_LAYOUT = Regex("""showLayOut\('([^']+)','(\d+)'""")
        /** A seat-map cell holding a tick box, i.e. a seat that can be selected. */
        private val TABLE_ROW = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
        private val LAYOUT_CELL = Regex("""<!--\s*START_Col(\d+)\s*-->(.*?)<!--\s*END_Col\1\s*-->""", RegexOption.DOT_MATCHES_ALL)
        private val CELL_CLASS = Regex("""class="([^"]+)"""")
    }
}
