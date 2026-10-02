package com.fidobridge.client.security

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricErrorCodesTest {

    @Test
    fun `user cancel codes are treated as cancellation`() {
        assertTrue(isUserCancelErrorCode(BiometricPrompt.ERROR_CANCELED))
        assertTrue(isUserCancelErrorCode(BiometricPrompt.ERROR_USER_CANCELED))
        assertTrue(isUserCancelErrorCode(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
    }

    @Test
    fun `genuine errors are not treated as cancellation`() {
        assertFalse(isUserCancelErrorCode(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        assertFalse(isUserCancelErrorCode(BiometricPrompt.ERROR_TIMEOUT))
        assertFalse(isUserCancelErrorCode(BiometricPrompt.ERROR_VENDOR))
    }
}