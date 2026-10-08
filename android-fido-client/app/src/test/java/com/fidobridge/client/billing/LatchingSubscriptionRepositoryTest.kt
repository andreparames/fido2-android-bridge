package com.fidobridge.client.billing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatchingSubscriptionRepositoryTest {

    @Test
    fun `once entitled it never downgrades for the process lifetime`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val delegate = FakeSubscriptionRepository(Entitlement.Loading)
        val latch = LatchingSubscriptionRepository(delegate, scope)

        delegate.setEntitlement(Entitlement.Entitled)
        assertEquals(EntitlementStatus.ENTITLED, latch.entitlement.value.status)

        delegate.setEntitlement(Entitlement.NotEntitled)
        delegate.setEntitlement(Entitlement.BillingUnavailable)
        delegate.setEntitlement(Entitlement.Error)

        assertEquals(EntitlementStatus.ENTITLED, latch.entitlement.value.status)
        scope.cancel()
    }

    @Test
    fun `upgrades before the first entitlement are observed`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val delegate = FakeSubscriptionRepository(Entitlement.Loading)
        val latch = LatchingSubscriptionRepository(delegate, scope)

        assertEquals(EntitlementStatus.LOADING, latch.entitlement.value.status)

        delegate.setEntitlement(Entitlement.NotEntitled)
        assertEquals(EntitlementStatus.NOT_ENTITLED, latch.entitlement.value.status)

        delegate.setEntitlement(Entitlement.Entitled)
        assertEquals(EntitlementStatus.ENTITLED, latch.entitlement.value.status)
        scope.cancel()
    }

    @Test
    fun `refresh publishes the resolved delegate state before returning`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val delegate = FakeSubscriptionRepository(Entitlement.Loading).apply {
            onRefresh = { setEntitlement(Entitlement.Entitled) }
        }
        val latch = LatchingSubscriptionRepository(delegate, scope)

        latch.refresh()

        // The async collector is still queued on the test dispatcher; the
        // resolved state must already be visible without advancing it.
        assertEquals(EntitlementStatus.ENTITLED, latch.entitlement.value.status)
        scope.cancel()
    }
}
