package com.fidobridge.client.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseListenerMappingTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository() = PlayBillingSubscriptionRepository(mockk(relaxed = true))

    private fun result(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()

    private fun purchase(
        product: String = ProductIds.MONTHLY,
        state: Int = Purchase.PurchaseState.PURCHASED,
        acknowledged: Boolean = true
    ): Purchase = mockk(relaxed = true) {
        every { purchaseState } returns state
        every { products } returns listOf(product)
        every { purchaseToken } returns "tok"
        every { isAcknowledged } returns acknowledged
    }

    @Test
    fun `purchased item maps to entitled with product and token`() {
        val repository = repository()

        repository.onPurchasesUpdated(
            result(BillingClient.BillingResponseCode.OK),
            mutableListOf(purchase())
        )

        val entitlement = repository.entitlement.value
        assertEquals(EntitlementStatus.ENTITLED, entitlement.status)
        assertEquals(ProductIds.MONTHLY, entitlement.productId)
        assertEquals("tok", entitlement.purchaseToken)
    }

    @Test
    fun `pending purchase does not revoke an existing entitlement`() {
        val repository = repository()
        repository.onPurchasesUpdated(
            result(BillingClient.BillingResponseCode.OK),
            mutableListOf(purchase())
        )

        repository.onPurchasesUpdated(
            result(BillingClient.BillingResponseCode.OK),
            mutableListOf(purchase(state = Purchase.PurchaseState.PENDING))
        )

        assertEquals(EntitlementStatus.ENTITLED, repository.entitlement.value.status)
    }

    @Test
    fun `empty or null purchase list does not revoke`() {
        val repository = repository()
        repository.onPurchasesUpdated(
            result(BillingClient.BillingResponseCode.OK),
            mutableListOf(purchase())
        )

        repository.onPurchasesUpdated(result(BillingClient.BillingResponseCode.OK), mutableListOf())
        repository.onPurchasesUpdated(result(BillingClient.BillingResponseCode.OK), null)

        assertEquals(EntitlementStatus.ENTITLED, repository.entitlement.value.status)
    }

    @Test
    fun `non allow-listed product is not entitled`() {
        val repository = repository()

        repository.onPurchasesUpdated(
            result(BillingClient.BillingResponseCode.OK),
            mutableListOf(purchase(product = "not_a_gatebridge_product"))
        )

        assertEquals(EntitlementStatus.LOADING, repository.entitlement.value.status)
    }

    @Test
    fun `user canceled keeps the current state`() {
        val repository = repository()
        repository.onPurchasesUpdated(
            result(BillingClient.BillingResponseCode.OK),
            mutableListOf(purchase())
        )

        repository.onPurchasesUpdated(
            result(BillingClient.BillingResponseCode.USER_CANCELED),
            null
        )

        assertEquals(EntitlementStatus.ENTITLED, repository.entitlement.value.status)
    }

    @Test
    fun `billing unavailable maps to unavailable`() {
        val repository = repository()

        repository.onPurchasesUpdated(
            result(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE),
            null
        )

        assertEquals(EntitlementStatus.BILLING_UNAVAILABLE, repository.entitlement.value.status)
    }
}
