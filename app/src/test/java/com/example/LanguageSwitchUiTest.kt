package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LanguageSwitchUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchingToPersianAndNavigatingTabsDoesNotCrash() {
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("nav_tab_settings").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag("psiphon_intro_overlay").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("psiphon_intro_overlay").performClick()
        }
        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.waitForIdle()

        // Switch to Persian (RTL)
        composeRule.onNodeWithTag("lang_chip_persian").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("اسکنر").assertIsDisplayed()

        // Navigate across all tabs in Persian
        composeRule.onNodeWithTag("nav_tab_scanner").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tab_saved").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tab_geoip").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tab_cf_diag").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.waitForIdle()

        // Switch back to English and System without crashing
        composeRule.onNodeWithTag("lang_chip_english").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Scanner").assertIsDisplayed()

        composeRule.onNodeWithTag("lang_chip_system").performClick()
        composeRule.waitForIdle()
    }
}
