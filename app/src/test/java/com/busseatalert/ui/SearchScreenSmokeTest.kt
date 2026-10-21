package com.busseatalert.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.busseatalert.data.Store
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the Search screen headlessly and walks it, to catch layout crashes without a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class SearchScreenSmokeTest {
    @get:Rule val rule = createComposeRule()

    /** Hits the real sites for suggestions, so only with -Dlive=true. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun tickSeveralStopsFromSuggestions() {
        org.junit.Assume.assumeTrue(System.getProperty("live") == "true")
        val store = Store(ApplicationProvider.getApplicationContext())
        rule.setContent { AppTheme { SearchScreen(store, navigate = {}, selectTab = {}) } }
        rule.onNodeWithText("From").performClick().performTextInput("Chennai")
        rule.waitUntil(30_000) { rule.onAllNodesWithText("CHENNAI-KILAMBAKKAM-KCBT").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("CHENNAI-KILAMBAKKAM-KCBT").performScrollTo().performClick()
        rule.onNodeWithText("CHENNAI TAMBARAM").performScrollTo().performClick()
        rule.onNodeWithText("TNSTC: CHENNAI-KILAMBAKKAM-KCBT, CHENNAI TAMBARAM ✓").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Kerala RTC", substring = true).fetchSemanticsNodes().also { println("Kerala nodes: ${it.size}") }
        println("found places: " + rule.onAllNodesWithText("places found", substring = true).fetchSemanticsNodes()
            .map { it.config.toString().take(120) })
        // Picks survive a small edit of the same place.
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("TNSTC: CHENNAI-KILAMBAKKAM-KCBT, CHENNAI TAMBARAM ✓").assertExists()
    }

    @Test
    fun searchScreenRendersAndReacts() {
        val store = Store(ApplicationProvider.getApplicationContext())
        rule.setContent { AppTheme { SearchScreen(store, navigate = {}, selectTab = {}) } }
        rule.onNodeWithText("Search on").assertIsDisplayed()
        rule.onNodeWithText("From").performTextInput("Palakkad")
        rule.onNodeWithText("To").performTextInput("Bengaluru")
        rule.onNodeWithText("⇅ Swap").performClick()
        rule.onNodeWithText("Palakkad").assertExists()  // now in To
        rule.onNodeWithText("+ Tomorrow").performClick()
        rule.onNodeWithText("More options ▾  (price, bus types, alerts)").performClick()
        rule.onNodeWithText("Bus types (per site)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Send alerts by").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("More options ▴").performScrollTo().performClick()
        rule.onNodeWithText("🔍  Search").performScrollTo().assertIsDisplayed()
    }
}
