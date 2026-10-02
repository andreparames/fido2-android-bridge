package com.fidobridge.client.security

import androidx.biometric.BiometricPrompt

/**
 * Returns whether [errorCode] is [BiometricPrompt.ERROR_CANCELED],
 * [BiometricPrompt.ERROR_USER_CANCELED], or [BiometricPrompt.ERROR_NEGATIVE_BUTTON].
 * These codes suppress the error dialog while still rejecting the request.
 */
fun isUserCancelErrorCode(errorCode: Int): Boolean = when (errorCode) {
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> true
    else -> false
}