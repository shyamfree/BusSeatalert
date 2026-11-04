package com.busseatalert.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.busseatalert.data.Store
import com.busseatalert.model.BusService
import com.busseatalert.model.Operator
import com.busseatalert.model.SeatSummary
import com.busseatalert.model.Watch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class BestPickCardTest {
    @get:Rule val rule = createComposeRule()

    private val buses = listOf(
        BusService(Operator.TNSTC, "T1", "SETC 729NS", "ULTRA DELUXE", "22:00", "07:00", 437, 12, duration = "9.00Hrs",
            seatInfo = SeatSummary(12, 6, 6, 0, 0, 0, false)),
        BusService(Operator.KERALA_RTC, "K1", "Ernakulam - Bangalore", "A/C SLEEPER - MULTI AXLE VOLVO-GAJARAJ-SWIFT",
            "23:25", "06:40 (+1 day)", 1003, 5, duration = "7h 15m", seatInfo = SeatSummary(5, 2, 3, 1, 4, 1, true)),
        BusService(Operator.KARNATAKA_RTC, "B1", "KTM-HSR-BNG", "RAJAHAMSA EXECUTIVE", "20:22", "05:17 (+1 day)", 949, 0),
    )

    @Test
    fun suggestsAndCompares() {
        val store = Store(ApplicationProvider.getApplicationContext())
        val watch = Watch("w", "Palghat", "Bengaluru", dates = listOf("2026-11-10"))
        rule.setContent { AppTheme { BestPickCard(buses, watch, "2026-11-10", store) {} } }

        rule.onNodeWithText("⭐ Best pick for you").assertIsDisplayed()
        rule.onNodeWithText("from 2 with seats").assertExists()           // the full Karnataka bus is left out
        rule.onNodeWithText("Cheapest").performClick()
        assertTrue(rule.onAllNodesWithText("ULTRA DELUXE").fetchSemanticsNodes().size == 2)  // pick details + compare row
        rule.onAllNodesWithText("• Cheapest of 2 buses with seats (₹437)").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onNodeWithText("Most comfortable").performClick()
        rule.onAllNodesWithText("• Most comfortable: premium AC sleeper").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onAllNodesWithText("• Overnight trip: a sleeper lets you lie flat").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        // Tap the second row of the comparison to show that bus in full.
        rule.onNodeWithText("22:00 TNSTC").performClick()
        rule.onNodeWithText("Showing another option. The top pick is #1 below.").assertExists()
    }
}
