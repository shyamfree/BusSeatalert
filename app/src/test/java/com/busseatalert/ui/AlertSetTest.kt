package com.busseatalert.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.busseatalert.data.Store
import com.busseatalert.model.BusService
import com.busseatalert.model.CheckResult
import com.busseatalert.model.Operator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h4000dp")
class AlertSetTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun setAndTurnOffAlertForTheSearch() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val store = Store(app)
        val date = LocalDate.now().plusDays(3).toString()
        val w = store.activeWatch().copy(from = "Palghat", to = "Bengaluru", dates = listOf(date))
        store.upsertWatch(w)
        val bus = BusService(Operator.TNSTC, "T1", "SETC", "ULTRA DELUXE", "22:00", "07:00", 437, 5)
        store.saveResult(w.id, date, CheckResult(System.currentTimeMillis(), date, emptyMap(), listOf(bus), setOf(bus.key)))

        rule.setContent { AppTheme { SearchScreen(store, navigate = {}, selectTab = {}) } }
        assertFalse(store.activeWatch().alertOn)
        rule.onNodeWithText("🔔  Set alert for this search").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue("alert is on for the search", store.activeWatch().alertOn)
        rule.onNodeWithText("🔔 Alert is set for this search").performScrollTo()
        rule.onNodeWithText("Turn off").performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(store.activeWatch().alertOn)
    }
}
