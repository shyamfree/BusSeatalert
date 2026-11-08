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
        AppLock.unlockedThisRun = false
        AppLock.phoneLockedSinceLastUse = false
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

    @Test
    fun asksAgainOnlyAfterThePhoneWasLocked() {
        val store = Store(ApplicationProvider.getApplicationContext())
        launch(lock = false).use { scenario ->
            store.appLock = true
            // Switching to another app and back: no prompt.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            rule.onNodeWithText("Search on").assertExists()
            // Phone locked (screen off) while away, then back: asks.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            AppLock.phoneLockedSinceLastUse = true
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            rule.onNodeWithText("Bus Seat Alert is locked").assertExists()
        }
    }

    @Test
    fun notificationTapWhileUnlockedDoesNotAskAgain() {
        launch(lock = true).use { rule.onNodeWithText("Bus Seat Alert is locked").assertExists() }
        // The user unlocked it; a notification tap restarts the screen.
        AppLock.unlockedThisRun = true
        ActivityScenario.launch(MainActivity::class.java).use { rule.onNodeWithText("Search on").assertExists() }
        // ...but after the phone was locked, it asks again.
        AppLock.phoneLockedSinceLastUse = true
        ActivityScenario.launch(MainActivity::class.java).use { rule.onNodeWithText("Bus Seat Alert is locked").assertExists() }
    }
}
