package com.fidobridge.client.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
    private val pairingGate: ManagedPairingGate,
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

    fun validateUri(uri: String): Boolean = repository.parseUri(uri).isSuccess

    fun clearError() {
        if (_uiState.value is PairingUiState.Error) {
            _uiState.value = PairingUiState.Scanning
        }
    }

    private fun submitUri(uri: String) {
        _uiState.value = PairingUiState.Pairing
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val info = repository.parseUri(uri).getOrThrow()
                    pairingGate.authorize(info).getOrThrow()
                    repository.pair(info).getOrThrow()
                    Result.success(Unit)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            _uiState.value = result.fold(
                onSuccess = { PairingUiState.Paired },
                onFailure = { e -> PairingUiState.Error(e.message ?: "Pairing failed") }
            )
        }
    }
}
