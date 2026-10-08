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

    override suspend fun refresh() = Unit

    override suspend fun queryProducts(): Result<List<SubscriptionProduct>> =
        Result.success(emptyList())

    override suspend fun launchPurchase(activity: Activity, productId: String) = Unit

    override suspend fun restorePurchases() = Unit

    override suspend fun acknowledgeIfRequired() = Unit
}