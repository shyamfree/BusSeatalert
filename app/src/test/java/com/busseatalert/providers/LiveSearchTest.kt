package com.busseatalert.providers

import com.busseatalert.model.Operator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/** Hits the real sites, so it only runs with -Dlive=true. */
class LiveSearchTest {
    private val date = LocalDate.now().plusDays(1).toString()

    private fun search(op: Operator, from: String, to: String) = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val provider = Providers.get(op)
        val result = provider.search(from, to, date)
        val buses = result.buses
        println("== ${op.label} $from -> $to $date, searched as ${result.from} -> ${result.to}: ${buses.size} buses")
        buses.take(6).forEach { bus ->
            println("${bus.serviceId} ${bus.departure} ${bus.busType}: listed=${bus.seatsAvailable} seatmap=${provider.bookableSeats(bus, date)}")
        }
        println("suggest from: " + provider.suggestPlaces(from, true))
        println("BOOKURL ${op.name} " + buses.first { it.seatsAvailable > 0 }.bookUrl)
        val again = provider.search(from, to, date).buses
        again.filter { it.seatsAvailable > 0 }.take(2).forEach { bus ->
            val l = provider.seatLayout(bus, date)
            println("LAYOUT ${bus.serviceId}: ${l.seats.size} seats, decks=${l.decks}, bookable=${l.bookable}, " +
                "ladiesFree=${l.seats.count { it.ladies && it.status.name == "AVAILABLE" }}, bookedByWoman=${l.seats.count { it.bookedByWoman }}, " +
                "accessible=${l.seats.count { it.accessible }}, blocked=${l.seats.count { it.status.name == "BLOCKED" }}")
            println("   sample: " + l.seats.take(6).joinToString { "${it.label}@d${it.deck}r${it.row}c${it.col}${if (it.height > 1) "h" + it.height else ""}:${it.status.name.take(4)}${if (it.ladies) "/L" else ""}" })
        }
        assertTrue(buses.isNotEmpty())
        assertTrue(buses.all { it.fare > 0 && it.departure.isNotBlank() && it.busType.isNotBlank() })
    }

    @Test fun tnstc() = search(Operator.TNSTC, "Palakkad", "Bengaluru")
    @Test fun kerala() = search(Operator.KERALA_RTC, "Palghat", "Bengaluru")
    @Test fun karnataka() = search(Operator.KARNATAKA_RTC, "Bangalore", "Mysuru")

    @Test fun placeAliases() {
        val names = listOf("Palakkad (9)", "Bangalore", "Ernakulam (7)")
        assertEquals("Palakkad (9)", PlaceNames.best("palghat", names) { it })
        assertEquals("Bangalore", PlaceNames.best("Bengaluru", names) { it })
        assertEquals("Ernakulam (7)", PlaceNames.best("Kochi", names) { it })
    }
}
