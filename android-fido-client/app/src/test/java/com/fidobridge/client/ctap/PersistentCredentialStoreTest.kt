package com.fidobridge.client.ctap

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentCredentialStoreTest {

    private val credential = StoredCredential(
        rpId = "example.com",
        alias = "alias-1",
        credentialId = "cred-1".toByteArray(),
        publicKey = PublicKeyCoords(
            x = ByteArray(32) { (it + 1).toByte() },
            y = ByteArray(32) { (it + 33).toByte() }
        ),
        userHandle = "user-1".toByteArray()
    )

    private class Harness {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>()

        init {
            every { prefs.edit() } returns editor
            every { prefs.getString("credentials", null) } returns null
        }

        fun store() = PersistentCredentialStore(prefs)

        fun savedJson(): String {
            val saved = slot<String>()
            verify { prefs.edit() }
            verify { editor.putString("credentials", capture(saved)) }
            return saved.captured
        }
    }

    @Test
    fun `add persists and findForRpId returns it`() {
        val h = Harness()
        val store = h.store()

        store.add(credential)

        val json = h.savedJson()
        every { h.prefs.getString("credentials", null) } returns json
        val found = store.findForRpId("example.com")
        assertEquals(1, found.size)
        assertArrayEquals("cred-1".toByteArray(), found[0].credentialId)
        assertEquals("example.com", found[0].rpId)
        assertArrayEquals("user-1".toByteArray(), found[0].userHandle)
    }

    @Test
    fun `findByCredentialId matches rpId and id`() {
        val h = Harness()
        val store = h.store()

        store.add(credential)

        every { h.prefs.getString("credentials", null) } returns h.savedJson()
        assertNotNull(store.findByCredentialId("example.com", "cred-1".toByteArray()))
        assertNull(store.findByCredentialId("other.com", "cred-1".toByteArray()))
        assertNull(store.findByCredentialId("example.com", "nope".toByteArray()))
    }

    @Test
    fun `corrupt stored json returns empty list`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString("credentials", null) } returns "not-json"

        val store = PersistentCredentialStore(prefs)
        assertEquals(0, store.findForRpId("example.com").size)
    }

    @Test
    fun `clear removes the persisted credentials key and commits`() {
        val h = Harness()
        val store = h.store()

        store.add(credential)
        every { h.editor.remove("credentials") } returns h.editor
        every { h.editor.commit() } returns true

        assertTrue(store.clear())

        verify { h.editor.remove("credentials") }
    }
}