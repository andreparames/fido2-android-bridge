package com.fidobridge.client.billing

import android.app.Activity
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** OSS / self-host path: no Play Billing; always entitled client-side. */
class AlwaysEntitledSubscriptionRepository @Inject constructor() : SubscriptionRepository {

    private val _entitlement = MutableStateFlow(Entitlement.Entitled)
    override val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    override suspend fun refresh() = Unit

    override suspend fun queryProducts(): Result<List<SubscriptionProduct>> =
        Result.success(emptyList())

    override suspend fun launchPurchase(activity: Activity, productId: String) = Unit

    override suspend fun restorePurchases() = Unit
}
