package com.fidobridge.client.billing

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LatchingSubscriptionRepositoryTest {

    @Test
    fun `once entitled it never downgrades for the process lifetime`() = runBlocking {
        val delegate = FakeSubscriptionRepository(Entitlement.Loading)
        val latch = LatchingSubscriptionRepository(delegate)

        delegate.setEntitlement(Entitlement.Entitled)
        awaitStatus(latch, EntitlementStatus.ENTITLED)

        delegate.setEntitlement(Entitlement.NotEntitled)
        delegate.setEntitlement(Entitlement.BillingUnavailable)
        delegate.setEntitlement(Entitlement.Error)
        delay(50)

        assertEquals(EntitlementStatus.ENTITLED, latch.entitlement.value.status)
    }

    @Test
    fun `upgrades before the first entitlement are observed`() = runBlocking {
        val delegate = FakeSubscriptionRepository(Entitlement.Loading)
        val latch = LatchingSubscriptionRepository(delegate)

        assertEquals(EntitlementStatus.LOADING, latch.entitlement.value.status)

        delegate.setEntitlement(Entitlement.NotEntitled)
        awaitStatus(latch, EntitlementStatus.NOT_ENTITLED)

        delegate.setEntitlement(Entitlement.Entitled)
        awaitStatus(latch, EntitlementStatus.ENTITLED)
    }

    private suspend fun awaitStatus(
        repository: LatchingSubscriptionRepository,
        status: EntitlementStatus
    ) {
        val deadline = System.currentTimeMillis() + 2_000
        while (repository.entitlement.value.status != status &&
            System.currentTimeMillis() < deadline
        ) {
            delay(10)
        }
        assertEquals(status, repository.entitlement.value.status)
    }
}
