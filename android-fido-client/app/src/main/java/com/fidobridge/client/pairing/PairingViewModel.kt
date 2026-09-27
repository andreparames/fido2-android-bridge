package com.fidobridge.client.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
    dispatcher: PairingUriDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Scanning)
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dispatcher.uri.collect { uri ->
                if (uri != null) {
                    dispatcher.consume()
                    submitUri(uri)
                }
            }
        }
    }

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
