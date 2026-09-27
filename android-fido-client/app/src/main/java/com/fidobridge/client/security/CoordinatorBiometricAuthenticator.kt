package com.fidobridge.client.security

import androidx.biometric.BiometricPrompt

class CoordinatorBiometricAuthenticator(
    private val coordinator: BiometricPromptCoordinator
) : BiometricAuthenticator {

    override fun authenticate(
        crypto: BiometricPrompt.CryptoObject?,
        title: String,
        subtitle: String,
        onResult: (Result<BiometricPrompt.CryptoObject?>) -> Unit
    ) {
        coordinator.request(crypto, title, subtitle, onResult)
    }
}