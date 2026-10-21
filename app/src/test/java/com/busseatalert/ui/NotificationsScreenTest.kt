package com.busseatalert.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.busseatalert.data.Store
import com.busseatalert.model.AlertRecord
import com.busseatalert.model.Operator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class NotificationsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun swipeSelectAndClearAll() {
        val store = Store(ApplicationProvider.getApplicationContext())
        listOf("A", "B", "C").forEachIndexed { i, n ->
            store.addAlert(AlertRecord("id$n", 1_000L * i, "w", Operator.entries[i], "Trip $n", "headline $n", listOf("line $n")))
        }
        rule.setContent { AppTheme { NotificationsScreen(store, navigate = {}, selectTab = {}) } }

        // Swipe one away, then undo it.
        rule.onNodeWithText("Trip B").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(listOf("idC", "idA"), store.alerts.map { it.id })
        rule.onNodeWithText("UNDO").performClick()
        assertEquals(3, store.alerts.size)

        // Select one and clear it.
        rule.onNodeWithText("Select").performClick()
        rule.onNodeWithText("Trip A").performClick()
        rule.onNodeWithText("Clear").performClick()
        assertEquals(setOf("idB", "idC"), store.alerts.map { it.id }.toSet())

        // Clear all, with confirmation.
        rule.onNodeWithText("Clear all").performClick()
        rule.onAllNodesWithText("Clear all")[1].performClick()
        assertEquals(0, store.alerts.size)
        rule.onNodeWithText("No notifications.").assertExists()
    }
}
