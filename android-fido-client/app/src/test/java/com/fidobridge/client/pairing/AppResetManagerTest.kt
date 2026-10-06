package com.fidobridge.client.pairing

import com.fidobridge.client.ctap.CredentialStore
import com.fidobridge.client.ctap.StoredCredential
import com.fidobridge.client.harness.FakeCredentialStore
import com.fidobridge.client.networking.DiagnosticLogEntry
import com.fidobridge.client.networking.DiagnosticLogStore
import com.fidobridge.client.security.KeystoreManager
import com.fidobridge.client.ui.model.InMemoryRequestLog
import com.fidobridge.client.ui.model.RequestType
import io.mockk.mockk
import io.mockk.verify
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResetManagerTest {

    private class RecordingLogStore : DiagnosticLogStore {
        var cleared = false
        override fun append(entry: DiagnosticLogEntry) = Unit
        override fun readAll(): String = ""
        override fun exportZipTo(output: OutputStream) = Unit
        override fun clear(): Boolean {
            cleared = true
            return true
        }
        override fun sizeBytes(): Long = 0
    }

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
        val logStore = RecordingLogStore()
        val manager = AppResetManager(identityStore, credentialStore, keystoreManager, requestLog, logStore)

        assertEquals(1, credentialStore.findForRpId("example.com").size)
        assertTrue(identityStore.isPaired)
        assertTrue(requestLog.records.value.isNotEmpty())

        assertTrue(manager.reset())

        assertFalse(identityStore.isPaired)
        assertTrue(credentialStore.findForRpId("example.com").isEmpty())
        assertTrue(requestLog.records.value.isEmpty())
        assertTrue(logStore.cleared)
        verify { keystoreManager.deleteAllSigningKeys() }
    }

    @Test
    fun `reset returns false when a store clear fails`() {
        val identityStore = FakeIdentityStore(
            storedPhonePrivate = ByteArray(32),
            storedDaemonPublic = ByteArray(32),
            storedChannelId = "a".repeat(32)
        )
        val manager = AppResetManager(
            identityStore,
            FailingCredentialStore(),
            mockk<KeystoreManager>(relaxed = true),
            InMemoryRequestLog(),
            RecordingLogStore()
        )

        assertFalse(manager.reset())

        assertFalse(identityStore.isPaired)
    }

    private class FailingCredentialStore : CredentialStore {
        override fun findForRpId(rpId: String) = emptyList<StoredCredential>()
        override fun findByCredentialId(rpId: String, credentialId: ByteArray) = null
        override fun add(credential: StoredCredential) = Unit
        override fun clear() = false
    }
}