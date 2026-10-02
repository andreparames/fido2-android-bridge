package com.fidobridge.client.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single transient message shown to the user (e.g. a biometric error) rendered
 * as a Material 3 alert dialog by the app's root composable.
 */
@Singleton
class UserMessageBus @Inject constructor() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun post(message: String) {
        _message.value = message
    }

    fun clear() {
        _message.value = null
    }
}