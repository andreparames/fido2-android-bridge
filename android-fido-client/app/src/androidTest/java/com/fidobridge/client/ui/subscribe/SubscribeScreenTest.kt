package com.fidobridge.client.ui.subscribe

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fidobridge.client.billing.SubscriptionProduct
import com.fidobridge.client.billing.ProductIds
import com.fidobridge.client.ui.theme.FidoBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SubscribeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun product(
        id: String = ProductIds.MONTHLY,
        title: String = "Gatebridge Individual Monthly",
        price: String = "$4.99",
        trial: Boolean = true
    ) = SubscriptionProduct(
        productId = id,
        title = title,
        formattedPrice = price,
        billingPeriod = "P1M",
        freeTrialPeriod = if (trial) "P1M" else null,
        offerToken = "offer"
    )

    private fun content(
        uiState: SubscribeUiState,
        inviteDialogVisible: Boolean = false,
        inviteError: InviteError? = null,
        inviteSubmitting: Boolean = false,
        onRetry: () -> Unit = {},
        onBuy: (String) -> Unit = {},
        onRestore: () -> Unit = {},
        onShowInvite: () -> Unit = {},
        onInviteCodeChange: (String) -> Unit = {},
        onSubmitInvite: () -> Unit = {},
        onHideInvite: () -> Unit = {}
    ) {
        composeRule.setContent {
            FidoBridgeTheme {
                SubscribeScreenContent(
                    uiState = uiState,
                    inviteDialogVisible = inviteDialogVisible,
                    inviteCodeInput = "",
                    inviteError = inviteError,
                    inviteSubmitting = inviteSubmitting,
                    onRetry = onRetry,
                    onBuy = onBuy,
                    onRestore = onRestore,
                    onShowInvite = onShowInvite,
                    onInviteCodeChange = onInviteCodeChange,
                    onSubmitInvite = onSubmitInvite,
                    onHideInvite = onHideInvite
                )
            }
        }
    }

    @Test
    fun loadingStateShowsAccessibleProgressIndicator() {
        content(uiState = SubscribeUiState(loading = true))

        composeRule.onNodeWithTag(SubscribeTags.LOADING).assertExists()
    }

    @Test
    fun productsRenderABuyButtonPerProduct() {
        content(
            uiState = SubscribeUiState(
                loading = false,
                products = listOf(product(), product(id = ProductIds.YEARLY))
            )
        )

        composeRule.onNodeWithTag(SubscribeTags.buy(ProductIds.MONTHLY)).assertExists()
        composeRule.onNodeWithTag(SubscribeTags.buy(ProductIds.YEARLY)).assertExists()
        composeRule.onNodeWithText("Start free trial").assertExists()
        composeRule.onNodeWithText("Subscribe").assertExists()
    }

    @Test
    fun buyButtonInvokesCallbackWithProductId() {
        var boughtId: String? = null
        content(
            uiState = SubscribeUiState(loading = false, products = listOf(product())),
            onBuy = { boughtId = it }
        )

        composeRule.onNodeWithTag(SubscribeTags.buy(ProductIds.MONTHLY)).performClick()

        assertEquals(ProductIds.MONTHLY, boughtId)
    }

    @Test
    fun billingUnavailableShowsStatusAndRetries() {
        var retried = false
        content(
            uiState = SubscribeUiState(loading = false, billingUnavailable = true),
            onRetry = { retried = true }
        )

        composeRule.onNodeWithTag("subscribe_status_billing").assertExists()
        composeRule.onNodeWithText("Try again").performClick()

        assertTrue(retried)
    }

    @Test
    fun errorStateShowsStatusAndRetry() {
        var retried = false
        content(
            uiState = SubscribeUiState(loading = false, error = true),
            onRetry = { retried = true }
        )

        composeRule.onNodeWithTag("subscribe_status_error").assertExists()
        composeRule.onNodeWithText("Try again").performClick()

        assertTrue(retried)
    }

    @Test
    fun inviteDialogSurfacesValidationError() {
        content(
            uiState = SubscribeUiState(loading = false, products = listOf(product())),
            inviteDialogVisible = true,
            inviteError = InviteError.INVALID
        )

        composeRule.onNodeWithTag(SubscribeTags.INVITE_FIELD).assertExists()
        composeRule.onNodeWithTag(SubscribeTags.INVITE_SUBMIT).assertExists()
        composeRule.onNodeWithText("Enter exactly 8 digits.").assertExists()
    }
}