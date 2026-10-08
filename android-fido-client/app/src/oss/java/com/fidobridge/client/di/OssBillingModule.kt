package com.fidobridge.client.di

import com.fidobridge.client.billing.AlwaysEntitledSubscriptionRepository
import com.fidobridge.client.billing.EntitlementBackend
import com.fidobridge.client.billing.NoOpEntitlementBackend
import com.fidobridge.client.billing.SubscriptionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OssBillingModule {

    @Binds
    @Singleton
    abstract fun bindSubscriptionRepository(
        impl: AlwaysEntitledSubscriptionRepository
    ): SubscriptionRepository

    @Binds
    @Singleton
    abstract fun bindEntitlementBackend(
        impl: NoOpEntitlementBackend
    ): EntitlementBackend
}
