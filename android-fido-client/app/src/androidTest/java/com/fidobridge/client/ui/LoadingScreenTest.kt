package com.fidobridge.client.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.fidobridge.client.ui.theme.FidoBridgeTheme
import org.junit.Rule
import org.junit.Test

class LoadingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingIndicatorIsDescriptiveAndVisible() {
        composeRule.setContent {
            FidoBridgeTheme {
                LoadingScreen()
            }
        }

        composeRule.onNodeWithTag(LoadingTags.ROOT).assertExists()
        composeRule.onNodeWithText("Checking subscription…").assertExists()
        composeRule.onNodeWithContentDescription("Checking subscription…").assertExists()
    }
}