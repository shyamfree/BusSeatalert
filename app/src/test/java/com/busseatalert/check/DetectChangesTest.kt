package com.busseatalert.check

import com.busseatalert.model.BusService
import com.busseatalert.model.Operator
import com.busseatalert.model.Watch
import com.busseatalert.notify.Alert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectChangesTest {
    private val watch = Watch("w", "Palghat", "Bengaluru", dates = listOf("2026-11-10"), alertOn = true)
    private val date = "2026-11-10"
    private fun bus(id: String, seats: Int, verified: Boolean = true) =
        BusService(Operator.KERALA_RTC, id, "Route", "AC SEATER", "21:00", "05:00", 900, seats, seatsVerified = verified)

    @Test
    fun firstCheckReportsOnlyBusesWithSeats() {
        val (alerts, memory) = detectChanges(watch, date, listOf(bus("A", 5), bus("B", 0)), null)
        assertEquals(listOf("A"), alerts.map { it.bus.serviceId })
        assertEquals(mapOf(bus("A", 5).key to 5, bus("B", 0).key to 0), memory)
    }

    @Test
    fun failedCheckDoesNotMakeBusesNewLater() {
        // The screenshot's case: a check where the sites failed (no buses), then a normal check.
        val (_, first) = detectChanges(watch, date, listOf(bus("A", 5), bus("B", 0)), null)
        val (failedAlerts, afterFail) = detectChanges(watch, date, emptyList(), first)
        assertTrue(failedAlerts.isEmpty())
        val (alerts, _) = detectChanges(watch, date, listOf(bus("A", 5), bus("B", 0)), afterFail)
        assertTrue("same buses again: no alerts", alerts.isEmpty())
    }

    @Test
    fun fullNewBusIsNotAnAlert() {
        val (_, first) = detectChanges(watch, date, listOf(bus("A", 5)), null)
        val (alerts, memory) = detectChanges(watch, date, listOf(bus("A", 5), bus("NEWFULL", 0), bus("NEWOPEN", 3)), first)
        assertEquals(listOf("NEWOPEN" to Alert.Kind.NEW_BUS), alerts.map { it.bus.serviceId to it.kind })
        assertEquals("full new bus is remembered", 0, memory[bus("NEWFULL", 0).key])
    }

    @Test
    fun cancellationOnlyFromSeatMap() {
        val (_, first) = detectChanges(watch, date, listOf(bus("A", 0)), null)
        // List count says 2 but the seat map didn't load: no alert, keep the known 0.
        val (rough, mem) = detectChanges(watch, date, listOf(bus("A", 2, verified = false)), first)
        assertTrue(rough.isEmpty())
        assertEquals(0, mem[bus("A", 0).key])
        // Seat map confirms 2 free: alert, "was full".
        val (real, _) = detectChanges(watch, date, listOf(bus("A", 2)), mem)
        assertEquals(listOf(Alert.Kind.SEATS_FREED), real.map { it.kind })
        assertEquals(0, real.single().previousSeats)
    }
}
