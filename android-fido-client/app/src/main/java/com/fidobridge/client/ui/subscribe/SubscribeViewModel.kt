package com.fidobridge.client.ui.subscribe

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fidobridge.client.billing.Entitlement
import com.fidobridge.client.billing.EntitlementStatus
import com.fidobridge.client.billing.SubscriptionProduct
import com.fidobridge.client.billing.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SubscribeUiState(
    val products: List<SubscriptionProduct> = emptyList(),
    val loading: Boolean = true,
    val billingUnavailable: Boolean = false,
    val error: Boolean = false
)

@HiltViewModel
class SubscribeViewModel @Inject constructor(
    private val repository: SubscriptionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscribeUiState())
    val uiState: StateFlow<SubscribeUiState> = _uiState.asStateFlow()

    val entitlement: StateFlow<Entitlement> = repository.entitlement

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
            repository.launchPurchase(activity, productId)
        }
    }

    fun restore() {
        viewModelScope.launch {
            repository.restorePurchases()
            refresh()
        }
    }

    fun openPlaySubscriptions() = Unit
}
