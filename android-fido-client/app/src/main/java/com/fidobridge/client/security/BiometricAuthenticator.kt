package com.fidobridge.client.security

import androidx.biometric.BiometricPrompt

interface BiometricAuthenticator {
    fun authenticate(
        crypto: BiometricPrompt.CryptoObject?,
        title: String,
        subtitle: String,
        onResult: (Result<BiometricPrompt.CryptoObject?>) -> Unit
    )
}
