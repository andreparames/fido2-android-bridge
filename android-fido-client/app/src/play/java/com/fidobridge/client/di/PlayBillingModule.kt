package com.fidobridge.client.di

import com.fidobridge.client.billing.BaseSubscriptionRepository
import com.fidobridge.client.billing.EntitlementBackend
import com.fidobridge.client.billing.InviteCodeClient
import com.fidobridge.client.billing.PlayBillingSubscriptionRepository
import com.fidobridge.client.billing.PlayEntitlementBackend
import com.fidobridge.client.billing.PlayInviteCodeClient
import com.fidobridge.client.billing.SubscriptionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayBillingModule {

    @Binds
    @Singleton
    @BaseSubscriptionRepository
    abstract fun bindSubscriptionRepository(
        impl: PlayBillingSubscriptionRepository
    ): SubscriptionRepository

    @Binds
    @Singleton
    abstract fun bindEntitlementBackend(
        impl: PlayEntitlementBackend
    ): EntitlementBackend

    @Binds
    @Singleton
    abstract fun bindInviteCodeClient(
        impl: PlayInviteCodeClient
    ): InviteCodeClient
}
