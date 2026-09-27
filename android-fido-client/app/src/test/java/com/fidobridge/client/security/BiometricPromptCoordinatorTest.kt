package com.fidobridge.client.security

import androidx.biometric.BiometricPrompt
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricPromptCoordinatorTest {

    @Test
    fun `request is emitted and result is delivered back`() = runTest {
        val coordinator = BiometricPromptCoordinator()

        coordinator.requests.test {
            var delivered: Result<BiometricPrompt.CryptoObject?>? = null

            coordinator.request(null, "WebAuthn sign-in", "example.com") { delivered = it }

            val request = awaitItem()
            assertEquals("WebAuthn sign-in", request.title)
            assertEquals("example.com", request.subtitle)
            assertNull(request.crypto)

            request.onResult(Result.success(null))
            assertTrue(delivered?.isSuccess == true)
            assertNull(delivered?.getOrNull())
        }
    }

    @Test
    fun `multiple requests are queued in order`() = runTest {
        val coordinator = BiometricPromptCoordinator()

        coordinator.requests.test {
            coordinator.request(null, "t1", "rp1") { }
            coordinator.request(null, "t2", "rp2") { }

            assertEquals("t1", awaitItem().title)
            assertEquals("t2", awaitItem().title)
        }
    }
}