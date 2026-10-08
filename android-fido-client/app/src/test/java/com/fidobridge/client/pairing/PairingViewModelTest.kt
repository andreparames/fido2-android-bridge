package com.fidobridge.client.pairing

import com.fidobridge.client.billing.Entitlement
import com.fidobridge.client.billing.EntitlementStatus
import com.fidobridge.client.billing.FakeEntitlementBackend
import com.fidobridge.client.billing.FakeSubscriptionRepository
import com.fidobridge.client.crypto.NoiseSession
import com.fidobridge.client.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val channel = "0123456789abcdef0123456789abcdef"
    private val daemonPublic = NoiseSession.staticPublicKey(ByteArray(32) { (it + 1).toByte() })
    private val pubkeyEncoded = Base64.encodeUrl(daemonPublic)

    private fun validUri() = "fidobridge://pair?channel=$channel&pubkey=$pubkeyEncoded"

    private fun viewModel(
        store: FakeIdentityStore = FakeIdentityStore(),
        dispatcher: PairingUriDispatcher = PairingUriDispatcher(),
        gate: ManagedPairingGate = classicGate()
    ): PairingViewModel =
        PairingViewModel(PairingRepository(store), gate, dispatcher)

    private fun classicGate() = ManagedPairingGate(
        relayUrl = "ws://localhost:9000/connection/websocket",
        subscriptionRepository = com.fidobridge.client.billing.AlwaysEntitledSubscriptionRepository(),
        entitlementBackend = com.fidobridge.client.billing.FakeEntitlementBackend()
    )

    private fun awaitTerminalState(vm: PairingViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.uiState.value is PairingUiState.Pairing) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("pairing did not reach a terminal state")
            Thread.sleep(5)
        }
    }

    @Test
    fun `initial state is scanning`() {
        val vm = viewModel()

        assertEquals(PairingUiState.Scanning, vm.uiState.value)
    }

    @Test
    fun `valid qr result becomes paired and stores identity`() {
        val store = FakeIdentityStore()
        val vm = viewModel(store)

        vm.onQrResult(validUri())
        awaitTerminalState(vm)

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadDaemonStaticPublic())
        assertNotNull(store.loadPhoneStaticPrivate())
    }

    @Test
    fun `invalid qr result becomes error`() {
        val vm = viewModel()

        vm.onQrResult("fidobridge://pair?channel=zz&pubkey=bad")
        awaitTerminalState(vm)

        assertTrue(vm.uiState.value is PairingUiState.Error)
    }

    @Test
    fun `manual input behaves identically to qr`() {
        val store = FakeIdentityStore()
        val vm = viewModel(store)

        vm.onManualSubmit(validUri())
        awaitTerminalState(vm)

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadDaemonStaticPublic())
    }

    @Test
    fun `pending deep link uri is consumed and pairs`() {
        val store = FakeIdentityStore()
        val dispatcher = PairingUriDispatcher()
        dispatcher.submit(validUri())

        val vm = viewModel(store, dispatcher)
        awaitTerminalState(vm)

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadDaemonStaticPublic())
    }

    @Test
    fun `deep link uri submitted after viewmodel creation is still consumed`() {
        val store = FakeIdentityStore()
        val dispatcher = PairingUriDispatcher()
        val vm = viewModel(store, dispatcher)

        dispatcher.submit(validUri())
        awaitTerminalState(vm)

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadDaemonStaticPublic())
    }

    @Test
    fun `valid uri passes validation`() {
        val vm = viewModel()

        assertTrue(vm.validateUri(validUri()))
    }

    @Test
    fun `invalid uri fails validation`() {
        val vm = viewModel()

        assertFalse(vm.validateUri("not-a-uri"))
    }

    @Test
    fun `error state clears back to scanning`() {
        val vm = viewModel()
        vm.onQrResult("fidobridge://pair?channel=zz&pubkey=bad")
        awaitTerminalState(vm)
        assertTrue(vm.uiState.value is PairingUiState.Error)

        vm.clearError()

        assertEquals(PairingUiState.Scanning, vm.uiState.value)
    }

    @Test
    fun `managed pairing activates the channel once before storing identity`() {
        val store = FakeIdentityStore()
        val backend = FakeEntitlementBackend()
        val gate = ManagedPairingGate(
            relayUrl = "wss://relay.gatebridge.app/connection/websocket",
            subscriptionRepository = FakeSubscriptionRepository(
                Entitlement(
                    status = EntitlementStatus.ENTITLED,
                    productId = "gatebridge_individual_monthly",
                    purchaseToken = "tok"
                )
            ),
            entitlementBackend = backend
        )
        val vm = viewModel(store, gate = gate)

        vm.onQrResult(validUri())
        awaitTerminalState(vm)

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertEquals(listOf("3eb1bd439947eb762998e566ccc2e099"), backend.calls)
        assertNotNull(store.loadChannelId())
    }

    @Test
    fun `managed pairing without entitlement shows error and stores nothing`() {
        val store = FakeIdentityStore()
        val backend = FakeEntitlementBackend()
        val gate = ManagedPairingGate(
            relayUrl = "wss://relay.gatebridge.app/connection/websocket",
            subscriptionRepository = FakeSubscriptionRepository(Entitlement.NotEntitled),
            entitlementBackend = backend
        )
        val vm = viewModel(store, gate = gate)

        vm.onQrResult(validUri())
        awaitTerminalState(vm)

        assertTrue(vm.uiState.value is PairingUiState.Error)
        assertTrue(backend.calls.isEmpty())
        assertEquals(null, store.loadChannelId())
    }
}