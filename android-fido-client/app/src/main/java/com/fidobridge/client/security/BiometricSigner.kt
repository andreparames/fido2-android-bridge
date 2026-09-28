package com.fidobridge.client.security

import androidx.biometric.BiometricPrompt
import com.fidobridge.client.ctap.Ctap2Processor
import java.security.Signature

class BiometricSigner(
    private val keystoreManager: KeystoreManager,
    private val authenticator: BiometricAuthenticator
) {

    fun sign(
        rpId: String,
        message: ByteArray,
        keyAlias: String,
        onResult: (Result<ByteArray>) -> Unit
    ) {
        val signature = keystoreManager.createSignature(keyAlias)
        val crypto = BiometricPrompt.CryptoObject(signature)

        authenticator.authenticate(
            crypto = crypto,
            title = PROMPT_TITLE,
            subtitle = promptSubtitle(rpId),
            onResult = { authResult ->
                authResult.fold(
                    onSuccess = { authorized ->
                        onResult(produceSignature(authorized, signature, message))
                    },
                    onFailure = {
                        onResult(Result.failure(OperationDeniedException()))
                    }
                )
            }
        )
    }

    private fun promptSubtitle(rpId: String): String =
        if (rpId == Ctap2Processor.DUMMY_RP_ID) DUMMY_PROBE_SUBTITLE else rpId

    private fun produceSignature(
        authorized: BiometricPrompt.CryptoObject?,
        signature: Signature,
        message: ByteArray
    ): Result<ByteArray> {
        return try {
            val sig = authorized?.signature ?: signature
            sig.update(message)
            Result.success(sig.sign())
        } catch (e: Exception) {
            Result.failure(OperationDeniedException())
        }
    }

    companion object {
        private const val PROMPT_TITLE = "WebAuthn sign-in"
        private const val DUMMY_PROBE_SUBTITLE = "Allow a website to use this authenticator?"
    }
}
