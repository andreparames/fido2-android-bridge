package com.fidobridge.client.pairing

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Scanning)
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    fun onQrResult(uri: String) = submitUri(uri)

    fun onManualSubmit(uri: String) = submitUri(uri)

    private fun submitUri(uri: String) {
        repository.parseUri(uri)
            .onSuccess { info -> repository.pair(info) }
            .fold(
                onSuccess = { _uiState.value = PairingUiState.Paired },
                onFailure = { e -> _uiState.value = PairingUiState.Error(e.message ?: "Pairing failed") }
            )
    }
}
