package com.fidobridge.client.pairing

import com.fidobridge.client.billing.Entitlement
import com.fidobridge.client.billing.EntitlementStatus
import com.fidobridge.client.billing.FakeEntitlementBackend
import com.fidobridge.client.billing.FakeSubscriptionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedPairingGateTest {

    private val channel = "0123456789abcdef0123456789abcdef"
    private val channelId = "3eb1bd439947eb762998e566ccc2e099"

    private val managedInfo = PairingInfo(
        channel = channel,
        channelId = channelId,
        daemonStaticPublic = ByteArray(32) { it.toByte() }
    )

    private fun classicGate(
        repository: FakeSubscriptionRepository = FakeSubscriptionRepository(),
        backend: FakeEntitlementBackend = FakeEntitlementBackend()
    ) = ManagedPairingGate(
        relayUrl = "ws://localhost:9000/connection/websocket",
        subscriptionRepository = repository,
        entitlementBackend = backend
    )

    private fun managedGate(
        repository: FakeSubscriptionRepository = FakeSubscriptionRepository(),
        backend: FakeEntitlementBackend = FakeEntitlementBackend()
    ) = ManagedPairingGate(
        relayUrl = "wss://relay.gatebridge.app/connection/websocket",
        subscriptionRepository = repository,
        entitlementBackend = backend
    )

    @Test
    fun `classic pairing authorizes without touching the backend`() = runTest {
        val backend = FakeEntitlementBackend()
        val gate = classicGate(backend = backend)

        assertTrue(!gate.isManaged)
        assertTrue(gate.authorize(managedInfo).isSuccess)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `managed pairing without entitlement is blocked`() = runTest {
        val backend = FakeEntitlementBackend()
        val gate = managedGate(
            repository = FakeSubscriptionRepository(Entitlement.NotEntitled),
            backend = backend
        )

        assertTrue(gate.isManaged)
        val result = gate.authorize(managedInfo)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionRequiredException)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `managed pairing with entitlement activates the subscription channel`() = runTest {
        val backend = FakeEntitlementBackend()
        val gate = managedGate(
            repository = FakeSubscriptionRepository(
                Entitlement(status = EntitlementStatus.ENTITLED, productId = "gatebridge_individual_monthly", purchaseToken = "tok")
            ),
            backend = backend
        )

        val result = gate.authorize(managedInfo)

        assertTrue(result.isSuccess)
        assertEquals(listOf(channelId), backend.calls)
    }

    @Test
    fun `managed activation failure blocks pairing`() = runTest {
        val backend = FakeEntitlementBackend(
            activateResult = Result.failure(IllegalStateException("backend unavailable"))
        )
        val gate = managedGate(backend = backend)

        val result = gate.authorize(managedInfo)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ChannelActivationException)
        assertEquals(listOf(channelId), backend.calls)
    }
}