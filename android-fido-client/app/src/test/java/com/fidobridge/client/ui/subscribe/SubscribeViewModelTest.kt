package com.fidobridge.client.ui.subscribe

import android.app.Activity
import com.fidobridge.client.billing.Entitlement
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

    @Test
    fun `loads products on init`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository().apply {
            products = Result.success(listOf(product()))
        }

        val viewModel = SubscribeViewModel(repository)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.products.size)
        assertFalse(viewModel.uiState.value.loading)
        assertFalse(viewModel.uiState.value.error)
    }

    @Test
    fun `product load failure surfaces error`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository().apply {
            products = Result.failure(IllegalStateException("boom"))
        }

        val viewModel = SubscribeViewModel(repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `billing unavailable is surfaced without a generic error`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository(Entitlement.BillingUnavailable).apply {
            products = Result.failure(IllegalStateException("boom"))
        }

        val viewModel = SubscribeViewModel(repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.billingUnavailable)
        assertFalse(viewModel.uiState.value.error)
    }

    @Test
    fun `buy failure sets purchaseFailed and a later success clears it`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository().apply {
            products = Result.success(listOf(product()))
        }
        val viewModel = SubscribeViewModel(repository)
        advanceUntilIdle()

        repository.launchResult = Result.failure(IllegalStateException("boom"))
        viewModel.buy(activity, "gatebridge_individual_monthly")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.purchaseFailed)

        repository.launchResult = Result.success(Unit)
        viewModel.buy(activity, "gatebridge_individual_monthly")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.purchaseFailed)
    }

    @Test
    fun `restore delegates to the repository`() = runTest(dispatcher) {
        val repository = FakeSubscriptionRepository().apply {
            products = Result.success(listOf(product()))
        }
        val viewModel = SubscribeViewModel(repository)
        advanceUntilIdle()

        viewModel.restore()
        advanceUntilIdle()

        assertEquals(1, repository.restoreCount)
    }
}
