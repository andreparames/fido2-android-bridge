package com.fidobridge.client.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSubscriptionRepository(
    entitlement: Entitlement = Entitlement.Entitled
) : SubscriptionRepository {

    private val _entitlement = MutableStateFlow(entitlement)
    override val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    var products: Result<List<SubscriptionProduct>> = Result.success(emptyList())
    var launchResult: Result<Unit> = Result.success(Unit)
    var restoreCount: Int = 0

    fun setEntitlement(value: Entitlement) {
        _entitlement.value = value
    }

    override suspend fun refresh() = Unit

    override suspend fun queryProducts(): Result<List<SubscriptionProduct>> = products

    override suspend fun launchPurchase(activity: Activity, productId: String): Result<Unit> =
        launchResult

    override suspend fun restorePurchases() {
        restoreCount++
    }
}
