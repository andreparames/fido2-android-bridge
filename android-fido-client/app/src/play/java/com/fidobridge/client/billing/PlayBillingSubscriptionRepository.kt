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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            _entitlement.value = Entitlement(
                status = EntitlementStatus.ENTITLED,
                productId = active.products.firstOrNull(),
                isTrial = false,
                purchaseToken = active.purchaseToken
            )
        } else {
            _entitlement.value = Entitlement.NotEntitled
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
        return suspendCancellableCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { result, details ->
                if (cont.isActive) {
                    cont.resume(
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            Result.success(details.productDetailsList)
                        } else {
                            Result.failure(
                                IllegalStateException("product details failed: ${result.responseCode}")
                            )
                        }
                    )
                }
            }
        }
    }

    private suspend fun queryPurchases(): Result<List<Purchase>> {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        return suspendCancellableCoroutine { cont ->
            billingClient.queryPurchasesAsync(params) { result, purchases ->
                if (cont.isActive) {
                    cont.resume(
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            Result.success(purchases)
                        } else {
                            Result.failure(
                                IllegalStateException("purchases query failed: ${result.responseCode}")
                            )
                        }
                    )
                }
            }
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

    override suspend fun launchPurchase(activity: Activity, productId: String) {
        val details = productDetails.firstOrNull { it.productId == productId } ?: return
        val offer = details.subscriptionOfferDetails
            ?.firstOrNull { offer ->
                offer.pricingPhases.pricingPhaseList.any { it.billingPeriod == ProductIds.TRIAL_PERIOD }
            }
            ?: details.subscriptionOfferDetails?.firstOrNull()
            ?: return
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
        billingClient.launchBillingFlow(activity, params)
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
                billingClient.acknowledgePurchase(ack) { }
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
                    _entitlement.value = Entitlement(
                        status = EntitlementStatus.ENTITLED,
                        productId = purchased.products.firstOrNull(),
                        purchaseToken = purchased.purchaseToken
                    )
                    if (!purchased.isAcknowledged) {
                        val ack = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchased.purchaseToken)
                            .build()
                        billingClient.acknowledgePurchase(ack) { }
                    }
                } else {
                    _entitlement.value = Entitlement.NotEntitled
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> Unit

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _entitlement.value = Entitlement.Entitled
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