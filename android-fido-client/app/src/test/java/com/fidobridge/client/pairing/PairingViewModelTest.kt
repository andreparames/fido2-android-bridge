package com.fidobridge.client.pairing

import com.fidobridge.client.crypto.SessionKey
import com.fidobridge.client.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val keyEncoded = Base64.encodeUrl(SessionKey.fromBytes(ByteArray(32) { it.toByte() }).bytes)

    private fun validUri() = "fidobridge://pair?channel=$channel&key=$keyEncoded"

    private fun viewModel(store: FakeSessionKeyStore = FakeSessionKeyStore(), dispatcher: PairingUriDispatcher = PairingUriDispatcher()): PairingViewModel =
        PairingViewModel(PairingRepository(store), dispatcher)

    @Test
    fun `initial state is scanning`() {
        val vm = viewModel()

        assertEquals(PairingUiState.Scanning, vm.uiState.value)
    }

    @Test
    fun `valid qr result becomes paired and stores key`() {
        val store = FakeSessionKeyStore()
        val vm = viewModel(store)

        vm.onQrResult(validUri())

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadKey())
    }

    @Test
    fun `invalid qr result becomes error`() {
        val vm = viewModel()

        vm.onQrResult("fidobridge://pair?channel=zz&key=bad")

        assertTrue(vm.uiState.value is PairingUiState.Error)
    }

    @Test
    fun `manual input behaves identically to qr`() {
        val store = FakeSessionKeyStore()
        val vm = viewModel(store)

        vm.onManualSubmit(validUri())

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadKey())
    }

    @Test
    fun `pending deep link uri is consumed and pairs`() {
        val store = FakeSessionKeyStore()
        val dispatcher = PairingUriDispatcher()
        dispatcher.submit(validUri())

        val vm = viewModel(store, dispatcher)

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadKey())
    }

    @Test
    fun `deep link uri submitted after viewmodel creation is still consumed`() {
        val store = FakeSessionKeyStore()
        val dispatcher = PairingUriDispatcher()
        val vm = viewModel(store, dispatcher)

        dispatcher.submit(validUri())

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadKey())
    }
}
