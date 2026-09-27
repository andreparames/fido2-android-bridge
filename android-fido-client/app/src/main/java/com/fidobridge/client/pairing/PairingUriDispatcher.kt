package com.fidobridge.client.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingUriDispatcher @Inject constructor() {

    private val _uri = MutableStateFlow<String?>(null)
    val uri: StateFlow<String?> = _uri.asStateFlow()

    fun submit(uri: String) {
        _uri.value = uri
    }

    fun consume() {
        _uri.value = null
    }
}