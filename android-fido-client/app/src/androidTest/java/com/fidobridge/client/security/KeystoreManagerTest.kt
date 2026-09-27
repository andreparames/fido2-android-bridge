package com.fidobridge.client.security

import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreManagerTest {

    private val alias = "test-signing-key-${System.nanoTime()}"

    @Test
    fun `generated key is inside secure hardware`() {
        val manager = KeystoreManager()
        manager.getOrCreateSigningKey(alias)

        val keyInfo = manager.getKeyInfo(alias)

        assertTrue(keyInfo.isInsideSecureHardware)
    }

    @Test
    fun `key purpose is sign only with EC P-256 and SHA256`() {
        val manager = KeystoreManager()
        val keyPair = manager.getOrCreateSigningKey(alias)
        val keyInfo = manager.getKeyInfo(alias)

        assertEquals(KeyProperties.KEY_ALGORITHM_EC, keyPair.private.algorithm)
        assertEquals(KeyProperties.PURPOSE_SIGN, keyInfo.purposes)
        assertTrue(keyInfo.digests.contains(KeyProperties.DIGEST_SHA256))
    }

    @Test
    fun `user authentication is required`() {
        val manager = KeystoreManager()
        manager.getOrCreateSigningKey(alias)

        val keyInfo = manager.getKeyInfo(alias)

        assertTrue(keyInfo.isUserAuthenticationRequired)
    }

    @Test
    fun `signing without biometric auth throws UserNotAuthenticatedException`() {
        val manager = KeystoreManager()
        manager.getOrCreateSigningKey(alias)
        val signature = manager.createSignature(alias)

        signature.update("hello".toByteArray())

        assertThrows(UserNotAuthenticatedException::class.java) {
            signature.sign()
        }
    }

    @Test
    fun `key is stable across calls`() {
        val manager = KeystoreManager()
        val first = manager.getOrCreateSigningKey(alias)
        val second = manager.getOrCreateSigningKey(alias)

        assertNotNull(first)
        assertArrayEquals(first.public.encoded, second.public.encoded)
    }
}
