package com.fidobridge.client.security

import androidx.biometric.BiometricPrompt

/**
 * Codes from [androidx.biometric.BiometricPrompt] that mean the user
 * deliberately dismissed the prompt rather than a genuine failure. These are a
 * decision (the request is rejected), not an error worth surfacing to the user.
 */
fun isUserCancelErrorCode(errorCode: Int): Boolean = when (errorCode) {
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> true
    else -> false
}