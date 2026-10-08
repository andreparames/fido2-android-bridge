package com.fidobridge.client.billing

import android.app.Activity
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysEntitledSubscriptionRepositoryTest {

    @Test
    fun `always entitled and purchase apis are no-ops`() = runBlocking {
        val repository = AlwaysEntitledSubscriptionRepository()

        assertEquals(EntitlementStatus.ENTITLED, repository.entitlement.value.status)
        assertTrue(repository.queryProducts().getOrThrow().isEmpty())

        repository.refresh()
        assertTrue(
            repository.launchPurchase(mockk<Activity>(relaxed = true), ProductIds.MONTHLY).isSuccess
        )
        repository.restorePurchases()
    }
}
