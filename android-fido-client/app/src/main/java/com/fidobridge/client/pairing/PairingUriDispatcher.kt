package com.fidobridge.client.pairing

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingUriDispatcher @Inject constructor() {

    private var pendingUri: String? = null

    fun submit(uri: String) {
        pendingUri = uri
    }

    fun consume(): String? {
        val uri = pendingUri
        pendingUri = null
        return uri
    }
}