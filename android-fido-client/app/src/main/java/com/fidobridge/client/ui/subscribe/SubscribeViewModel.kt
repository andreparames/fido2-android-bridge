package com.fidobridge.client.ui.subscribe

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fidobridge.client.billing.Entitlement
import com.fidobridge.client.billing.EntitlementStatus
import com.fidobridge.client.billing.InviteCodeClient
import com.fidobridge.client.billing.InviteCodeValidator
import com.fidobridge.client.billing.SubscriptionProduct
import com.fidobridge.client.billing.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class InviteError { INVALID, FAILED }

data class SubscribeUiState(
    val products: List<SubscriptionProduct> = emptyList(),
    val loading: Boolean = true,
    val billingUnavailable: Boolean = false,
    val error: Boolean = false,
    val purchaseFailed: Boolean = false
)

@HiltViewModel
class SubscribeViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
    private val inviteCodeClient: InviteCodeClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscribeUiState())
    val uiState: StateFlow<SubscribeUiState> = _uiState.asStateFlow()

    val entitlement: StateFlow<Entitlement> = repository.entitlement

    private val _inviteDialogVisible = MutableStateFlow(false)
    val inviteDialogVisible: StateFlow<Boolean> = _inviteDialogVisible.asStateFlow()

    private val _inviteCodeInput = MutableStateFlow("")
    val inviteCodeInput: StateFlow<String> = _inviteCodeInput.asStateFlow()

    private val _inviteError = MutableStateFlow<InviteError?>(null)
    val inviteError: StateFlow<InviteError?> = _inviteError.asStateFlow()

    private val _inviteSubmitting = MutableStateFlow(false)
    val inviteSubmitting: StateFlow<Boolean> = _inviteSubmitting.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = false, billingUnavailable = false)
            repository.refresh()
            val products = repository.queryProducts()
            products.fold(
                onSuccess = { list ->
                    _uiState.value = SubscribeUiState(
                        products = list,
                        loading = false,
                        billingUnavailable = repository.entitlement.value.status == EntitlementStatus.BILLING_UNAVAILABLE
                    )
                },
                onFailure = {
                    _uiState.value = SubscribeUiState(
                        loading = false,
                        billingUnavailable = repository.entitlement.value.status == EntitlementStatus.BILLING_UNAVAILABLE,
                        error = repository.entitlement.value.status != EntitlementStatus.BILLING_UNAVAILABLE
                    )
                }
            )
        }
    }

    fun buy(activity: Activity, productId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(purchaseFailed = false)
            repository.launchPurchase(activity, productId).onFailure {
                _uiState.value = _uiState.value.copy(purchaseFailed = true)
            }
        }
    }

    fun restore() {
        viewModelScope.launch {
            repository.restorePurchases()
            refresh()
        }
    }

    fun showInviteDialog() {
        _inviteError.value = null
        _inviteDialogVisible.value = true
    }

    fun hideInviteDialog() {
        _inviteDialogVisible.value = false
        _inviteCodeInput.value = ""
        _inviteError.value = null
    }

    fun onInviteCodeChange(raw: String) {
        _inviteCodeInput.value = raw.filter { it.isDigit() }.take(8)
        _inviteError.value = null
    }

    fun submitInviteCode() {
        val code = _inviteCodeInput.value
        if (!InviteCodeValidator.isValid(code)) {
            _inviteError.value = InviteError.INVALID
            return
        }
        viewModelScope.launch {
            _inviteSubmitting.value = true
            val result = inviteCodeClient.submitInviteCode(code)
            _inviteSubmitting.value = false
            if (result.isSuccess) {
                hideInviteDialog()
            } else {
                _inviteError.value = InviteError.FAILED
            }
        }
    }

    fun openPlaySubscriptions() = Unit
}
