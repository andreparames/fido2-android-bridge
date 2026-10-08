package com.fidobridge.client.di

import com.fidobridge.client.billing.AlwaysEntitledSubscriptionRepository
import com.fidobridge.client.billing.EntitlementBackend
import com.fidobridge.client.billing.FakeEntitlementBackend
import com.fidobridge.client.billing.SubscriptionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OssBillingModule {

    @Provides
    @Singleton
    fun provideSubscriptionRepository(): SubscriptionRepository =
        AlwaysEntitledSubscriptionRepository()

    @Provides
    @Singleton
    fun provideEntitlementBackend(): EntitlementBackend = FakeEntitlementBackend()
}
