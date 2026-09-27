package com.fidobridge.client.security

import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

class SigningRequest(
    val crypto: BiometricPrompt.CryptoObject?,
    val title: String,
    val subtitle: String,
    val onResult: (Result<BiometricPrompt.CryptoObject?>) -> Unit
)

@Singleton
class BiometricPromptCoordinator @Inject constructor() {

    private val channel = Channel<SigningRequest>(Channel.BUFFERED)
    val requests: Flow<SigningRequest> = channel.receiveAsFlow()

    fun request(
        crypto: BiometricPrompt.CryptoObject?,
        title: String,
        subtitle: String,
        onResult: (Result<BiometricPrompt.CryptoObject?>) -> Unit
    ) {
        channel.trySend(SigningRequest(crypto, title, subtitle, onResult))
    }
}