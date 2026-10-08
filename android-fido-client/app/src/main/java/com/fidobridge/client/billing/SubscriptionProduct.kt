package com.fidobridge.client.billing

data class SubscriptionProduct(
    val productId: String,
    val title: String,
    val formattedPrice: String,
    val billingPeriod: String,
    val freeTrialPeriod: String?,
    val offerToken: String
)
