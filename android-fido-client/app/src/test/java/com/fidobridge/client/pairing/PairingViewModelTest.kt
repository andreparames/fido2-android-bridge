package com.fidobridge.client.pairing

import com.fidobridge.client.crypto.SessionKey
import com.fidobridge.client.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingViewModelTest {

    private val channel = "0123456789abcdef0123456789abcdef"
    private val keyEncoded = Base64.encodeUrl(SessionKey.fromBytes(ByteArray(32) { it.toByte() }).bytes)

    private fun validUri() = "fidobridge://pair?channel=$channel&key=$keyEncoded"

    @Test
    fun `initial state is scanning`() {
        val vm = PairingViewModel(PairingRepository(FakeSessionKeyStore()))

        assertEquals(PairingUiState.Scanning, vm.uiState.value)
    }

    @Test
    fun `valid qr result becomes paired and stores key`() {
        val store = FakeSessionKeyStore()
        val vm = PairingViewModel(PairingRepository(store))

        vm.onQrResult(validUri())

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadKey())
    }

    @Test
    fun `invalid qr result becomes error`() {
        val vm = PairingViewModel(PairingRepository(FakeSessionKeyStore()))

        vm.onQrResult("fidobridge://pair?channel=zz&key=bad")

        assertTrue(vm.uiState.value is PairingUiState.Error)
    }

    @Test
    fun `manual input behaves identically to qr`() {
        val store = FakeSessionKeyStore()
        val vm = PairingViewModel(PairingRepository(store))

        vm.onManualSubmit(validUri())

        assertEquals(PairingUiState.Paired, vm.uiState.value)
        assertNotNull(store.loadKey())
    }
}
