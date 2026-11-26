package com.raulshma.grack.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.UserPreferences
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpressiveSegmentedPillGroupTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun switchingAuthenticationDoesNotCrashShapeAnimation() {
        var selected by mutableStateOf("SSH Key")

        composeTestRule.setContent {
            GlassrackTheme(UserPreferences(useExpressiveMotion = true)) {
                ExpressiveSegmentedPillGroup(
                    options = listOf("SSH Key", "Password"),
                    selected = selected,
                    onSelected = { selected = it },
                    label = { it },
                )
            }
        }

        composeTestRule.onNodeWithText("Password").performClick()
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.runOnIdle { assertEquals("Password", selected) }

        composeTestRule.onNodeWithText("SSH Key").performClick()
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.runOnIdle { assertEquals("SSH Key", selected) }
    }

    @Test
    fun switchingThemeModeDoesNotCrashShapeAnimation() {
        var selected by mutableStateOf("System")

        composeTestRule.setContent {
            GlassrackTheme(UserPreferences(useExpressiveMotion = true)) {
                ExpressiveSegmentedPillGroup(
                    options = listOf("System", "Light", "Dark"),
                    selected = selected,
                    onSelected = { selected = it },
                    label = { it },
                )
            }
        }

        composeTestRule.onNodeWithText("Light").performClick()
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.runOnIdle { assertEquals("Light", selected) }

        composeTestRule.onNodeWithText("Dark").performClick()
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.runOnIdle { assertEquals("Dark", selected) }
    }
}
