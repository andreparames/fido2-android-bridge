package com.fidobridge.client.pairing

import com.fidobridge.client.harness.FakeCredentialStore
import com.fidobridge.client.security.KeystoreManager
import com.fidobridge.client.ui.model.InMemoryRequestLog
import com.fidobridge.client.ui.model.RequestType
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResetManagerTest {

    @Test
    fun `reset deletes signing keys and clears identity, credentials and request log`() {
        val identityStore = FakeIdentityStore(
            storedPhonePrivate = ByteArray(32),
            storedDaemonPublic = ByteArray(32),
            storedChannelId = "a".repeat(32),
            storedRelayToken = "token"
        )
        val credentialStore = FakeCredentialStore()
        val keystoreManager = mockk<KeystoreManager>(relaxed = true)
        val requestLog = InMemoryRequestLog().apply {
            record("id-1", RequestType.SIGN_IN, "example.com")
        }
        val manager = AppResetManager(identityStore, credentialStore, keystoreManager, requestLog)

        assertEquals(1, credentialStore.findForRpId("example.com").size)
        assertTrue(identityStore.isPaired)
        assertTrue(requestLog.records.value.isNotEmpty())

        manager.reset()

        assertFalse(identityStore.isPaired)
        assertTrue(credentialStore.findForRpId("example.com").isEmpty())
        assertTrue(requestLog.records.value.isEmpty())
        verify { keystoreManager.deleteAllSigningKeys() }
    }
}