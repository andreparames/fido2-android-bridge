package com.fidobridge.client.ui

import com.fidobridge.client.billing.EntitlementStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationRoutingTest {

    @Test
    fun `oss never shows the billing surfaces`() {
        EntitlementStatus.entries.forEach { status ->
            assertEquals(AppSurface.MAIN, surfaceFor(playBillingRequired = false, status = status))
        }
    }

    @Test
    fun `play routes by entitlement status`() {
        assertEquals(AppSurface.LOADING, surfaceFor(true, EntitlementStatus.LOADING))
        assertEquals(AppSurface.MAIN, surfaceFor(true, EntitlementStatus.ENTITLED))
        assertEquals(AppSurface.SUBSCRIBE, surfaceFor(true, EntitlementStatus.NOT_ENTITLED))
        assertEquals(AppSurface.SUBSCRIBE, surfaceFor(true, EntitlementStatus.BILLING_UNAVAILABLE))
        assertEquals(AppSurface.SUBSCRIBE, surfaceFor(true, EntitlementStatus.ERROR))
    }
}
