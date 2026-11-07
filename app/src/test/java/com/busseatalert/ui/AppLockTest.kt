package com.busseatalert.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.busseatalert.data.Store
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class AppLockTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun launch(lock: Boolean): ActivityScenario<MainActivity> {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Store(app).appLock = lock
        return ActivityScenario.launch(MainActivity::class.java)
    }

    @Test
    fun lockedAppShowsLockScreen() {
        launch(lock = true).use {
            rule.onNodeWithText("Bus Seat Alert is locked").assertExists()
            rule.onNodeWithText("Search on").assertDoesNotExist()
        }
    }

    @Test
    fun unlockedAppOpensSearch() {
        launch(lock = false).use {
            rule.onNodeWithText("Search on").assertExists()
        }
    }
}
