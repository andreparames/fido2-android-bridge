package com.fidobridge.client.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single transient message shown to the user (e.g. a biometric error) rendered
 * by the app's root composable (as a snackbar).
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

    /**
     * Atomically clears the bus only if the current message is [expected].
     * Lets whoever finished displaying a message avoid wiping out a newer one.
     */
    fun clearIf(expected: String) {
        _message.update { current -> if (current == expected) null else current }
    }
}