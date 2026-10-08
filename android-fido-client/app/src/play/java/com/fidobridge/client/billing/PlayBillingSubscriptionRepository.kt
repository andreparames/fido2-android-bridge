package com.fidobridge.client.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PlayBillingSubscriptionRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SubscriptionRepository, PurchasesUpdatedListener {

    private val _entitlement = MutableStateFlow(Entitlement.Loading)
    override val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    @Volatile
    private var productDetails: List<ProductDetails> = emptyList()

    private val connectionMutex = Mutex()

    /** Repository-owned scope for listener callbacks (which are not suspend). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Created lazily on first use so app cold start does not pay for BillingClient
     * construction (and Play services binding) unless the user reaches a surface
     * that queries entitlement/products.
     */
    private val billingClient: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .enableAutoServiceReconnection()
            .build()
    }

    private suspend fun ensureConnected(): BillingResult = connectionMutex.withLock {
        if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTED) {
            return@withLock BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.OK)
                .build()
        }
        suspendCancellableCoroutine { cont ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) {
                        cont.resume(
                            BillingResult.newBuilder()
                                .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
                                .build()
                        )
                    }
                }
            })
        }
    }

    override suspend fun refresh() {
        val connected = ensureConnected()
        if (connected.responseCode != BillingClient.BillingResponseCode.OK) {
            _entitlement.value =
                if (connected.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ||
                    connected.responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                ) {
                    Entitlement.BillingUnavailable
                } else {
                    Entitlement.Error
                }
            return
        }

        val detailsResult = queryProductDetails()
        if (detailsResult.isSuccess) {
            productDetails = detailsResult.getOrDefault(emptyList())
        }

        val purchasesResult = queryPurchases()
        if (purchasesResult.isFailure) {
            _entitlement.value = Entitlement.Error
            return
        }

        val active = purchasesResult.getOrDefault(emptyList())
            .firstOrNull {
                it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    ProductIds.isLegal(it.products.firstOrNull() ?: "")
            }
        if (active != null) {
            acknowledgeIfRequired()
            _entitlement.value = entitlementFor(active)
        } else {
            _entitlement.value = Entitlement.NotEntitled
        }
    }

    private fun entitlementFor(purchase: Purchase): Entitlement {
        val productId = purchase.products.firstOrNull()
        return Entitlement(
            status = EntitlementStatus.ENTITLED,
            productId = productId,
            isTrial = isInTrial(purchase, productId),
            purchaseToken = purchase.purchaseToken
        )
    }

    /**
     * Best-effort client-side trial window: the product has a free (zero-price)
     * [ProductIds.TRIAL_PERIOD] phase and the purchase is still inside it. The
     * verified server session (`/v1/play/session`) remains authoritative and
     * overrides this via [applyServerTrial].
     */
    private fun isInTrial(purchase: Purchase, productId: String?): Boolean {
        if (productId == null) return false
        val details = productDetails.firstOrNull { it.productId == productId } ?: return false
        val trialPhase = details.subscriptionOfferDetails
            ?.asSequence()
            ?.flatMap { it.pricingPhases.pricingPhaseList.asSequence() }
            ?.firstOrNull {
                it.billingPeriod == ProductIds.TRIAL_PERIOD && it.priceAmountMicros == 0L
            }
            ?: return false
        val endsAt = Instant.ofEpochMilli(purchase.purchaseTime)
            .atZone(ZoneOffset.UTC)
            .plus(Period.parse(trialPhase.billingPeriod))
            .toInstant()
        return Instant.now().isBefore(endsAt)
    }

    /**
     * Applies the server-authoritative trial flag from a verified Play session
     * without altering the rest of the (already-entitled) state.
     */
    fun applyServerTrial(isTrial: Boolean) {
        val current = _entitlement.value
        if (current.isEntitled && current.isTrial != isTrial) {
            _entitlement.value = current.copy(isTrial = isTrial)
        }
    }

    private suspend fun queryProductDetails(): Result<List<ProductDetails>> {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ProductIds.ALL.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
            )
            .build()
        val result = billingClient.queryProductDetails(params)
        return if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Result.success(result.productDetailsList ?: emptyList())
        } else {
            Result.failure(
                IllegalStateException("product details failed: ${result.billingResult.responseCode}")
            )
        }
    }

    private suspend fun queryPurchases(): Result<List<Purchase>> {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        return if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Result.success(result.purchasesList ?: emptyList())
        } else {
            Result.failure(
                IllegalStateException("purchases query failed: ${result.billingResult.responseCode}")
            )
        }
    }

    override suspend fun queryProducts(): Result<List<SubscriptionProduct>> {
        if (productDetails.isEmpty()) {
            refresh()
        }
        val mapped = productDetails.mapNotNull { details ->
            val offer = details.subscriptionOfferDetails
                ?.firstOrNull { it.basePlanId != null }
                ?.let { offer ->
                    val phases = offer.pricingPhases.pricingPhaseList
                    val trial = phases.firstOrNull { it.billingPeriod == ProductIds.TRIAL_PERIOD }
                    SubscriptionProduct(
                        productId = details.productId,
                        title = details.name,
                        formattedPrice = phases.firstOrNull { it.billingPeriod != ProductIds.TRIAL_PERIOD }
                            ?.formattedPrice
                            ?: phases.firstOrNull()?.formattedPrice
                            ?: "",
                        billingPeriod = offer.basePlanId,
                        freeTrialPeriod = trial?.billingPeriod,
                        offerToken = offer.offerToken
                    )
                }
            offer
        }
        return Result.success(mapped)
    }

    override suspend fun launchPurchase(activity: Activity, productId: String): Result<Unit> {
        val details = productDetails.firstOrNull { it.productId == productId }
            ?: return Result.failure(
                IllegalStateException("no product details for $productId")
            )
        val offer = details.subscriptionOfferDetails
            ?.firstOrNull { offer ->
                offer.pricingPhases.pricingPhaseList.any { it.billingPeriod == ProductIds.TRIAL_PERIOD }
            }
            ?: details.subscriptionOfferDetails?.firstOrNull()
            ?: return Result.failure(
                IllegalStateException("no subscription offer for $productId")
            )
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offer.offerToken)
                        .build()
                )
            )
            .build()
        // launchBillingFlow synchronously reports the launch request only; the
        // purchase outcome arrives later via onPurchasesUpdated.
        val result = billingClient.launchBillingFlow(activity, params)
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "billing launch failed: ${result.responseCode} ${result.debugMessage}"
                )
            )
        }
    }

    override suspend fun restorePurchases() {
        refresh()
    }

    /** Play-specific: acknowledges unacknowledged purchases (Play policy). */
    suspend fun acknowledgeIfRequired() {
        val purchases = queryPurchases().getOrDefault(emptyList())
        purchases.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                val ack = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(ack)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchased = purchases?.firstOrNull {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        ProductIds.isLegal(it.products.firstOrNull() ?: "")
                }
                if (purchased != null) {
                    _entitlement.value = entitlementFor(purchased)
                    if (!purchased.isAcknowledged) {
                        val ack = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchased.purchaseToken)
                            .build()
                        scope.launch { billingClient.acknowledgePurchase(ack) }
                    }
                }
                // A PENDING purchase, or an empty/null list, must not revoke an
                // existing entitlement; leave the current state as-is.
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> Unit

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Resolve from the real purchase so productId/purchaseToken are
                // populated for managed channel activation.
                scope.launch { refresh() }
            }

            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                _entitlement.value = Entitlement.BillingUnavailable
            }

            else -> {
                _entitlement.value = Entitlement.Error
            }
        }
    }
}