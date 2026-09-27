package com.fidobridge.client.di

import android.content.Context
import com.fidobridge.client.pairing.EncryptedSessionKeyStore
import com.fidobridge.client.pairing.PairingRepository
import com.fidobridge.client.pairing.SessionKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSessionKeyStore(@ApplicationContext context: Context): SessionKeyStore =
        EncryptedSessionKeyStore(context)

    @Provides
    @Singleton
    fun providePairingRepository(sessionKeyStore: SessionKeyStore): PairingRepository =
        PairingRepository(sessionKeyStore)
}
