package com.fidobridge.client.ui.subscribe

import android.app.Activity
import com.fidobridge.client.billing.Entitlement
import com.fidobridge.client.billing.FakeInviteCodeClient
import com.fidobridge.client.billing.FakeSubscriptionRepository
import com.fidobridge.client.billing.SubscriptionProduct
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscribeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val activity: Activity = mockk(relaxed = true)
    private val inviteClient = FakeInviteCodeClient()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun product(id: String = "gatebridge_individual_monthly") = SubscriptionProduct(
        productId = id,
        title = "Monthly",
        formattedPrice = "$2.00",
        billingPeriod = "P1M",
        freeTrialPeriod = "P1M",
        offerToken = "offer"
    )

    private fun viewModel(repository: FakeSubscriptionRepository) =
        SubscribeViewModel(repository, inviteClient)

    @Test
    fun `loads products on init`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository().apply {
            products = Result.success(listOf(product()))
        }

        val subscribeViewModel = viewModel(repository)
        advanceUntilIdle()

        assertEquals(1, subscribeViewModel.uiState.value.products.size)
        assertFalse(subscribeViewModel.uiState.value.loading)
        assertFalse(subscribeViewModel.uiState.value.error)
    }

    @Test
    fun `product load failure surfaces error`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository().apply {
            products = Result.failure(IllegalStateException("boom"))
        }

        val subscribeViewModel = viewModel(repository)
        advanceUntilIdle()

        assertTrue(subscribeViewModel.uiState.value.error)
        assertFalse(subscribeViewModel.uiState.value.loading)
    }

    @Test
    fun `billing unavailable is surfaced without a generic error`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository(Entitlement.BillingUnavailable).apply {
            products = Result.failure(IllegalStateException("boom"))
        }

        val subscribeViewModel = viewModel(repository)
        advanceUntilIdle()

        assertTrue(subscribeViewModel.uiState.value.billingUnavailable)
        assertFalse(subscribeViewModel.uiState.value.error)
    }

    @Test
    fun `buy failure sets purchaseFailed and a later success clears it`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository().apply {
            products = Result.success(listOf(product()))
        }
        val subscribeViewModel = viewModel(repository)
        advanceUntilIdle()

        repository.launchResult = Result.failure(IllegalStateException("boom"))
        subscribeViewModel.buy(activity, "gatebridge_individual_monthly")
        advanceUntilIdle()
        assertTrue(subscribeViewModel.uiState.value.purchaseFailed)

        repository.launchResult = Result.success(Unit)
        subscribeViewModel.buy(activity, "gatebridge_individual_monthly")
        advanceUntilIdle()
        assertFalse(subscribeViewModel.uiState.value.purchaseFailed)
    }

    @Test
    fun `restore delegates to the repository`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository().apply {
            products = Result.success(listOf(product()))
        }
        val subscribeViewModel = viewModel(repository)
        advanceUntilIdle()

        subscribeViewModel.restore()
        advanceUntilIdle()

        assertEquals(1, repository.restoreCount)
    }

    @Test
    fun `invite input keeps only digits and caps at eight`() = runTest(dispatcher) {
        val subscribeViewModel = viewModel(FakeSubscriptionRepository())

        subscribeViewModel.onInviteCodeChange("12a34-5678901")

        assertEquals("12345678", subscribeViewModel.inviteCodeInput.value)
    }

    @Test
    fun `invalid invite code is rejected without calling the client`() = runTest(dispatcher) {
        val subscribeViewModel = viewModel(FakeSubscriptionRepository())
        subscribeViewModel.showInviteDialog()
        subscribeViewModel.onInviteCodeChange("123")

        subscribeViewModel.submitInviteCode()
        advanceUntilIdle()

        assertEquals(InviteError.INVALID, subscribeViewModel.inviteError.value)
        assertTrue(inviteClient.codes.isEmpty())
        assertTrue(subscribeViewModel.inviteDialogVisible.value)
    }

    @Test
    fun `successful invite submission closes the dialog`() = runTest(dispatcher) {
        val subscribeViewModel = viewModel(FakeSubscriptionRepository())
        subscribeViewModel.showInviteDialog()
        subscribeViewModel.onInviteCodeChange("12345678")

        subscribeViewModel.submitInviteCode()
        advanceUntilIdle()

        assertEquals(listOf("12345678"), inviteClient.codes)
        assertFalse(subscribeViewModel.inviteDialogVisible.value)
        assertEquals("", subscribeViewModel.inviteCodeInput.value)
        assertFalse(subscribeViewModel.inviteSubmitting.value)
    }

    @Test
    fun `failed invite submission surfaces an error`() = runTest(dispatcher) {
        inviteClient.result = Result.failure(IllegalStateException("denied"))
        val subscribeViewModel = viewModel(FakeSubscriptionRepository())
        subscribeViewModel.showInviteDialog()
        subscribeViewModel.onInviteCodeChange("12345678")

        subscribeViewModel.submitInviteCode()
        advanceUntilIdle()

        assertEquals(InviteError.FAILED, subscribeViewModel.inviteError.value)
        assertTrue(subscribeViewModel.inviteDialogVisible.value)
    }
}
