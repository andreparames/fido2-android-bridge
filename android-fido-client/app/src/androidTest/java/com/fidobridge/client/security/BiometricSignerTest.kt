package com.fidobridge.client.security

import androidx.biometric.BiometricPrompt
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiometricSignerTest {

    private val alias = "test-signer-key-${System.nanoTime()}"

    @Test
    fun authFailure_returnsOperationDenied_andDoesNotSign() {
        val manager = KeystoreManager().apply { getOrCreateSigningKey(alias) }
        val signer = BiometricSigner(manager, FakeBiometricAuthenticator(fail = true))

        var result: Result<ByteArray>? = null
        signer.sign("example.com", "hello".toByteArray(), alias) { result = it }

        assertTrue(result!!.isFailure)
        assertTrue(result!!.exceptionOrNull() is OperationDeniedException)
    }

    @Test
    fun promptPayload_containsTheTargetRpId() {
        val manager = KeystoreManager().apply { getOrCreateSigningKey(alias) }
        val authenticator = FakeBiometricAuthenticator(fail = true)
        val signer = BiometricSigner(manager, authenticator)

        signer.sign("example.com", "hello".toByteArray(), alias) {}

        assertTrue(authenticator.lastSubtitle!!.contains("example.com"))
    }

    @Test
    fun dummyRpProbe_usesGenericSubtitle_andNeverLeaksTheDummyRpId() {
        val manager = KeystoreManager().apply { getOrCreateSigningKey(alias) }
        val authenticator = FakeBiometricAuthenticator(fail = true)
        val signer = BiometricSigner(manager, authenticator)

        signer.sign(".dummy", "hello".toByteArray(), alias) {}

        assertTrue(authenticator.lastSubtitle!!.contains("website"))
        assertFalse(authenticator.lastSubtitle!!.contains(".dummy"))
    }

    private class FakeBiometricAuthenticator(
        private val fail: Boolean
    ) : BiometricAuthenticator {

        var lastSubtitle: String? = null
            private set

        override fun authenticate(
            crypto: BiometricPrompt.CryptoObject?,
            title: String,
            subtitle: String,
            onResult: (Result<BiometricPrompt.CryptoObject?>) -> Unit
        ) {
            lastSubtitle = subtitle
            if (fail) {
                onResult(Result.failure(OperationDeniedException()))
            } else {
                onResult(Result.success(crypto))
            }
        }
    }
}
