package com.fidobridge.client.di

import android.content.Context
import com.fidobridge.client.BuildConfig
import com.fidobridge.client.bridge.BridgePipeline
import com.fidobridge.client.ctap.Ctap2Processor
import com.fidobridge.client.ctap.CredentialStore
import com.fidobridge.client.ctap.KeyGenerator
import com.fidobridge.client.ctap.PersistentCredentialStore
import com.fidobridge.client.networking.CentrifugoTransport
import com.fidobridge.client.networking.DiagnosticLogSink
import com.fidobridge.client.networking.RelayTransport
import com.fidobridge.client.notifications.ForegroundStateProvider
import com.fidobridge.client.notifications.ProcessForegroundStateProvider
import com.fidobridge.client.pairing.AppResetManager
import com.fidobridge.client.pairing.EncryptedIdentityStore
import com.fidobridge.client.pairing.IdentityStore
import com.fidobridge.client.pairing.PairingRepository
import com.fidobridge.client.security.BiometricPromptCoordinator
import com.fidobridge.client.security.BiometricSigner
import com.fidobridge.client.security.BiometricSignerAdapter
import com.fidobridge.client.security.CoordinatorBiometricAuthenticator
import com.fidobridge.client.security.KeystoreKeyGenerator
import com.fidobridge.client.security.KeystoreManager
import com.fidobridge.client.ui.model.InMemoryRequestLog
import com.fidobridge.client.ui.model.RequestLog
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
    fun provideIdentityStore(@ApplicationContext context: Context): IdentityStore =
        EncryptedIdentityStore(context)

    @Provides
    @Singleton
    fun providePairingRepository(identityStore: IdentityStore): PairingRepository =
        PairingRepository(identityStore)

    @Provides
    @Singleton
    fun provideCredentialStore(@ApplicationContext context: Context): CredentialStore {
        val prefs = context.getSharedPreferences("fido_credentials", Context.MODE_PRIVATE)
        return PersistentCredentialStore(prefs)
    }

    @Provides
    @Singleton
    fun provideKeystoreManager(): KeystoreManager = KeystoreManager()

    @Provides
    @Singleton
    fun provideKeyGenerator(keystoreManager: KeystoreManager): KeyGenerator =
        KeystoreKeyGenerator(keystoreManager)

    @Provides
    @Singleton
    fun provideSigner(
        keystoreManager: KeystoreManager,
        coordinator: BiometricPromptCoordinator
    ): com.fidobridge.client.ctap.Signer =
        BiometricSignerAdapter(
            BiometricSigner(keystoreManager, CoordinatorBiometricAuthenticator(coordinator))
        )

    @Provides
    @Singleton
    fun provideAppResetManager(
        identityStore: IdentityStore,
        credentialStore: CredentialStore,
        keystoreManager: KeystoreManager,
        requestLog: RequestLog
    ): AppResetManager = AppResetManager(identityStore, credentialStore, keystoreManager, requestLog)

    @Provides
    @Singleton
    fun provideRequestLog(): RequestLog = InMemoryRequestLog()

    @Provides
    @Singleton
    fun provideForegroundStateProvider(): ForegroundStateProvider = ProcessForegroundStateProvider()

    @Provides
    @Singleton
    fun provideCtap2Processor(
        credentialStore: CredentialStore,
        keyGenerator: KeyGenerator,
        signer: com.fidobridge.client.ctap.Signer,
        requestLog: RequestLog
    ): Ctap2Processor = Ctap2Processor(credentialStore, keyGenerator, signer, requestLog)

    @Provides
    @Singleton
    fun provideBridgePipeline(
        identityStore: IdentityStore,
        processor: Ctap2Processor,
        logSink: DiagnosticLogSink
    ): BridgePipeline = BridgePipeline(
        identityStore = identityStore,
        relayUrl = BuildConfig.RELAY_URL,
        processor = processor,
        defaultRelayToken = BuildConfig.RELAY_TOKEN,
        transportFactory = { endpoint, channel, relayToken -> CentrifugoTransport(endpoint, channel, relayToken) as RelayTransport },
        logSink = logSink
    )
}