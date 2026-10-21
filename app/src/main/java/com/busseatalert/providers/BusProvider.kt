package com.busseatalert.providers

import com.busseatalert.model.BusService
import com.busseatalert.model.Operator
import com.busseatalert.model.Seat
import com.busseatalert.model.SeatLayout
import com.busseatalert.model.SeatStatus
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** What a site returned for one route and date, with the place names it actually used. */
data class RouteSearch(val from: String, val to: String, val buses: List<BusService>)

interface BusProvider {
    val operator: Operator

    /** Returns every service on the route for [date] (yyyy-MM-dd), with fare and listed free seats. */
    suspend fun search(from: String, to: String, date: String): RouteSearch

    /**
     * Reads [bus]'s seat map. Must be called right after [search] for the same date
     * (TNSTC keeps the search in its session).
     */
    suspend fun seatLayout(bus: BusService, date: String): SeatLayout

    /** Seats that can actually be selected on the seat map. */
    suspend fun bookableSeats(bus: BusService, date: String): Int = seatLayout(bus, date).bookable

    /** Bus types this site runs on the route on any of [dates], for the bus type picker. */
    suspend fun busTypes(from: String, to: String, dates: List<String>): List<String> =
        dates.take(3).flatMap { search(from, to, it).buses.map { b -> b.busType } }.filter { it.isNotBlank() }.distinct().sorted()

    /** Place names this site knows that match [query], for the per-site name picker. */
    suspend fun suggestPlaces(query: String, isFrom: Boolean): List<String>
}

/** Keeps cookies in memory so a site's search session carries over to its seat-map request. */
private class MemoryCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, MutableMap<String, Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val forHost = this.cookies.getOrPut(url.host) { mutableMapOf() }
        cookies.forEach { forHost[it.name] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cookies[url.host]?.values?.filter { it.expiresAt > now && it.matches(url) }.orEmpty()
    }
}

object Providers {
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val all: Map<Operator, BusProvider> = listOf(
        IamgdsProvider(Operator.KERALA_RTC, "https://onlineksrtcswift.com", http),
        IamgdsProvider(Operator.KARNATAKA_RTC, "https://ksrtc.in", http),
        TnstcProvider(http),
    ).associateBy { it.operator }

    fun get(operator: Operator): BusProvider = all.getValue(operator)
}

/** Runs [block], retrying twice on network errors: ksrtc.in sometimes resets connections. */
internal inline fun <T> withRetry(block: () -> T): T {
    var last: Exception? = null
    repeat(3) { attempt ->
        try {
            return block()
        } catch (e: java.io.IOException) {
            last = e
            Thread.sleep(2000L * (attempt + 1))
        }
    }
    throw last!!
}
