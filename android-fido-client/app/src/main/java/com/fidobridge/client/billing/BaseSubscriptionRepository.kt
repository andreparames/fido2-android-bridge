package com.fidobridge.client.billing

import javax.inject.Qualifier

/**
 * The raw, un-latched repository bound per distribution flavor. The app-facing
 * [SubscriptionRepository] is the process-lifetime-latched wrapper (see
 * [LatchingSubscriptionRepository]); this qualifier disambiguates the delegate.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseSubscriptionRepository
