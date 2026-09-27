package com.fidobridge.client.pairing

sealed interface PairingUiState {
    data object Scanning : PairingUiState
    data object Paired : PairingUiState
    data class Error(val message: String) : PairingUiState
}
