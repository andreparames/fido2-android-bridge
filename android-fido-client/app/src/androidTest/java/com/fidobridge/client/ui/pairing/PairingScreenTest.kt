package com.fidobridge.client.ui.pairing

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.fidobridge.client.pairing.PairingUiState
import com.fidobridge.client.ui.theme.FidoBridgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PairingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val isValidUri: (String) -> Boolean = { it.startsWith("fidobridge://pair") }

    private fun content(
        state: PairingUiState = PairingUiState.Scanning,
        validateUri: (String) -> Boolean = isValidUri,
        onSubmit: (String) -> Unit = {}
    ) {
        composeRule.setContent {
            FidoBridgeTheme {
                PairingContent(
                    state = state,
                    validateUri = validateUri,
                    onQrResult = {},
                    onManualChange = {},
                    onManualSubmit = onSubmit
                )
            }
        }
    }

    @Test
    fun cameraRationaleAndManualFieldArePresent() {
        content()

        composeRule.onNodeWithTag(PairingTags.RATIONALE).assertExists()
        composeRule.onNodeWithTag(PairingTags.MANUAL_FIELD).assertExists()
        composeRule.onNodeWithTag(PairingTags.ALLOW_CAMERA).assertExists()
    }

    @Test
    fun pairButtonStartsDisabled() {
        content()

        composeRule.onNodeWithTag(PairingTags.PAIR_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun invalidManualInputShowsInlineError() {
        content(validateUri = isValidUri)

        composeRule.onNodeWithTag(PairingTags.MANUAL_FIELD).performTextInput("nope")
        composeRule.onNodeWithText("That doesn't look like a valid pairing URI.").assertExists()
    }

    @Test
    fun validManualInputEnablesPairAndSubmits() {
        var submitted: String? = null
        content(onSubmit = { submitted = it })

        composeRule.onNodeWithTag(PairingTags.MANUAL_FIELD)
            .performTextInput("fidobridge://pair?channel=abc&key=xyz")
        composeRule.onNodeWithTag(PairingTags.PAIR_BUTTON).performClick()

        assertTrue(submitted == "fidobridge://pair?channel=abc&key=xyz")
    }

    @Test
    fun errorStateShowsStatusMessage() {
        content(state = PairingUiState.Error("Pairing failed"))

        composeRule.onNodeWithTag(PairingTags.ERROR).assertExists()
        composeRule.onNodeWithText("Pairing failed").assertExists()
    }
}