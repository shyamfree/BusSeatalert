package com.busseatalert.pick

import com.busseatalert.model.BusService
import com.busseatalert.model.Operator
import com.busseatalert.model.SeatSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {
    private fun bus(
        id: String, type: String, fare: Int, seats: Int, dep: String = "21:00", arr: String = "05:00 (+1 day)",
        duration: String = "", info: SeatSummary? = null, op: Operator = Operator.KERALA_RTC,
    ) = BusService(op, id, "Route", type, dep, arr, fare, seats, duration = duration, seatInfo = info)

    @Test
    fun comfortFollowsBusClass() {
        val c = Recommender::comfort
        assertTrue(c("A/C SLEEPER - MULTI AXLE VOLVO-GAJARAJ-SWIFT") > c("SWIFT-GARUDA A/C SEATER BUS"))
        assertTrue(c("SWIFT-GARUDA A/C SEATER BUS") > c("SUPER DELUXE AIR BUS"))   // Kerala's "Air Bus" is non-AC
        assertTrue(c("AMBAARI DREAM CLASS (AC SLEEPER)") > c("AIRAVAT CLUB CLASS 2.0"))
        assertTrue(c("NON AC SLEEPER") > c("ULTRA DELUXE"))
        assertTrue(c("NON AC SLEEPER") < c("AC SLEEPER"))                         // "NON AC" isn't AC
        assertTrue(c("ULTRA DELUXE") > c("FAST PASSENGER"))
        assertTrue(c("RAJAHAMSA EXECUTIVE") > c("KARNATAKA SARIGE"))
    }

    @Test
    fun durationFormats() {
        assertEquals(465, Recommender.durationMinutes(bus("a", "X", 1, 1, duration = "7h 45m")))
        assertEquals(385, Recommender.durationMinutes(bus("a", "X", 1, 1, duration = "6.25Hrs")))  // TNSTC: 6 h 25 min
        assertEquals(480, Recommender.durationMinutes(bus("a", "X", 1, 1, dep = "22:00", arr = "06:00")))
    }

    @Test
    fun modesChangeTheWinner() {
        val cheap = bus("cheap", "FAST PASSENGER", 450, 10, duration = "9h 00m")
        val plush = bus("plush", "AC SLEEPER MULTI AXLE VOLVO SLX", 1300, 10, duration = "8h 00m")
        val quick = bus("quick", "SWIFT-GARUDA A/C SEATER BUS", 900, 10, duration = "6h 30m")
        val full = bus("full", "AC SLEEPER", 500, 0)
        val all = listOf(cheap, plush, quick, full)

        assertEquals("cheap", Recommender.rank(all, 1, PickMode.CHEAPEST, SeatPref.ANY).first().bus.serviceId)
        assertEquals("plush", Recommender.rank(all, 1, PickMode.COMFORT, SeatPref.ANY).first().bus.serviceId)
        assertEquals("quick", Recommender.rank(all, 1, PickMode.FASTEST, SeatPref.ANY).first().bus.serviceId)
        assertEquals("full buses are never suggested", 3, Recommender.rank(all, 1, PickMode.BALANCED, SeatPref.ANY).size)
    }

    @Test
    fun seatPreferenceCounts() {
        val noWindow = bus("a", "AC SEATER", 700, 6, duration = "7h 00m", info = SeatSummary(6, 0, 6, 0, 0, 0, false))
        val window = bus("b", "AC SEATER", 720, 6, duration = "7h 00m", info = SeatSummary(6, 4, 2, 0, 0, 0, false))
        assertEquals("a", Recommender.rank(listOf(noWindow, window), 1, PickMode.BALANCED, SeatPref.ANY).first().bus.serviceId)
        val best = Recommender.rank(listOf(noWindow, window), 1, PickMode.BALANCED, SeatPref.WINDOW).first()
        assertEquals("b", best.bus.serviceId)
        assertTrue(best.reasons.any { it == "4 window seats free" })
    }
}
