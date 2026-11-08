package com.busseatalert.check

import com.busseatalert.model.BusService
import com.busseatalert.model.Operator
import com.busseatalert.model.Watch
import com.busseatalert.model.bookingOpensLater
import com.busseatalert.notify.Alert
import com.busseatalert.pick.PickMode
import com.busseatalert.pick.Recommender
import com.busseatalert.pick.SeatPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BookingOpensTest {
    private val now = LocalDateTime.of(2026, 11, 8, 20, 30)
    private val opens = LocalDateTime.now().plusHours(5).withSecond(0).withNano(0)
    private val bus = BusService(Operator.KERALA_RTC, "2100EKMBNG", "Ernakulam - Bangalore", "AC SLEEPER", "00:25", "07:40",
        1301, 14, bookingOpensAt = opens.toString())

    @Test
    fun onlyFutureTimesCount() {
        assertEquals(LocalDateTime.of(2026, 11, 9, 0, 25), bus.copy(bookingOpensAt = "2026-11-09T00:25").bookingOpensLater(now))
        assertNull(bus.copy(bookingOpensAt = "2026-11-08T00:25").bookingOpensLater(now))   // already open
        assertNull(bus.copy(bookingOpensAt = "").bookingOpensLater(now))                   // bookable any time
    }

    @Test
    fun shownInAlertsAndBestPick() {
        val watch = Watch("w", "Palghat", "Bengaluru")
        val line = Alert(watch, "2026-11-10", bus, Alert.Kind.NEW_BUS).line
        assertTrue(line, line.contains(" · booking opens "))
        val reasons = Recommender.rank(listOf(bus), 1, PickMode.BALANCED, SeatPref.ANY).single().reasons
        assertTrue(reasons.toString(), reasons.any { it.startsWith("Booking opens ") })
    }
}
