package com.fidobridge.client.security

import com.fidobridge.client.ctap.Signer

class BiometricSignerAdapter(
    private val signer: BiometricSigner
) : Signer {

    override fun sign(
        rpId: String,
        keyAlias: String,
        data: ByteArray,
        onResult: (Result<ByteArray>) -> Unit
    ) {
        signer.sign(rpId, data, keyAlias, onResult)
    }
}