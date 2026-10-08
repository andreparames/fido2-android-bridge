package com.fidobridge.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fidobridge.client.billing.SubscriptionRepository
import com.fidobridge.client.networking.DiagnosticLogSink
import com.fidobridge.client.networking.FidoBridgeService
import com.fidobridge.client.pairing.PairingRepository
import com.fidobridge.client.pairing.PairingUriDispatcher
import com.fidobridge.client.security.BiometricPromptCoordinator
import com.fidobridge.client.security.OperationDeniedException
import com.fidobridge.client.security.isUserCancelErrorCode
import com.fidobridge.client.ui.FidoBridgeApp
import com.fidobridge.client.ui.UserMessageBus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val TAG = "FidoBridge"
    private val instanceId = ++instanceCounter

    companion object {
        private var instanceCounter = 0
    }

    @Inject
    lateinit var coordinator: BiometricPromptCoordinator

    @Inject
    lateinit var pairingRepository: PairingRepository

    @Inject
    lateinit var pairingUriDispatcher: PairingUriDispatcher

    @Inject
    lateinit var logSink: DiagnosticLogSink

    @Inject
    lateinit var userMessageBus: UserMessageBus

    @Inject
    lateinit var subscriptionRepository: SubscriptionRepository

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logSink.log("MainActivity#$instanceId onCreate saved=${savedInstanceState != null}")
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handlePairingIntent(intent)
        setContent { FidoBridgeApp() }
        collectSigningRequests()
        observeEntitlement()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    private fun handlePairingIntent(intent: Intent?) {
        val uri = intent?.data?.toString()
        if (uri?.startsWith("fidobridge://pair") == true) {
            pairingUriDispatcher.submit(uri)
        }
    }

    override fun onStart() {
        super.onStart()
        logSink.log("MainActivity#$instanceId onStart")
    }

    override fun onResume() {
        super.onResume()
        logSink.log("MainActivity#$instanceId onResume")
    }

    /**
     * Starts the bridge foreground service once a pairing exists and the current
     * entitlement is active. Observing the flow (rather than checking only in
     * onResume) covers the managed/Play case where entitlement resolves
     * asynchronously after the activity reaches STARTED.
     */
    private fun observeEntitlement() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                subscriptionRepository.entitlement.collect { entitlement ->
                    if (pairingRepository.isPaired && entitlement.isEntitled) {
                        startForegroundService(Intent(this@MainActivity, FidoBridgeService::class.java))
                    }
                }
            }
        }
    }

    override fun onPause() {
        logSink.log("MainActivity#$instanceId onPause")
        super.onPause()
    }

    override fun onStop() {
        logSink.log("MainActivity#$instanceId onStop")
        super.onStop()
    }

    override fun onDestroy() {
        logSink.log("MainActivity#$instanceId onDestroy changingConfig=${isChangingConfigurations}")
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Collects unclaimed signing requests in the activity lifecycle and presents biometric prompts.
     * Delivers success or an [OperationDeniedException] failure through each request's callback.
     * Requeues the request if collection is canceled; cancellation codes suppress the error dialog.
     */
    private fun collectSigningRequests() {
        val executor = ContextCompat.getMainExecutor(this)
        lifecycleScope.launch {
            coordinator.requests.collect { request ->
                if (request.claimed) return@collect
                request.claimed = true
                Log.i(TAG, "signing request: title=${request.title} subtitle=${request.subtitle}")
                logSink.log("signing request rpId=${request.subtitle} state=${lifecycle.currentState}")
                try {
                    val result = suspendCancellableCoroutine<Result<BiometricPrompt.CryptoObject?>> { cont ->
                        val prompt = BiometricPrompt(
                            this@MainActivity,
                            executor,
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    Log.i(TAG, "biometric authentication succeeded")
                                    logSink.log("biometric authentication succeeded")
                                    cont.resume(Result.success(result.cryptoObject))
                                }

                                /** Rejects the request, posting a user message only for non-cancellation errors. */
                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                    val msg = "biometric error $errorCode: $errString"
                                    Log.w(TAG, msg)
                                    logSink.log(msg)
                                    if (!isUserCancelErrorCode(errorCode)) {
                                        userMessageBus.post(msg)
                                    }
                                    cont.resume(Result.failure(OperationDeniedException(errString.toString())))
                                }

                                override fun onAuthenticationFailed() {
                                    Log.w(TAG, "biometric authentication failed")
                                    logSink.log("biometric authentication failed")
                                }
                            }
                        )
                        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                            .setTitle(request.title)
                            .setSubtitle(request.subtitle)
                            .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            )
                            .build()
                        logSink.log("calling BiometricPrompt.authenticate")
                        val crypto = request.crypto
                        if (crypto != null) {
                            prompt.authenticate(promptInfo, crypto)
                        } else {
                            prompt.authenticate(promptInfo)
                        }
                    }
                    request.onResult(result)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "activity cancelled during prompt (${lifecycle.currentState}); requeueing request")
                    logSink.log("activity cancelled during prompt state=${lifecycle.currentState}; requeueing")
                    coordinator.requeue(request)
                    throw e
                } catch (e: Exception) {
                    val detail = "${e::class.simpleName}: ${e.message}"
                    Log.e(TAG, "biometric prompt failed: $detail state=${lifecycle.currentState}")
                    logSink.log("prompt failed: $detail state=${lifecycle.currentState}")
                    userMessageBus.post("prompt failed: $detail\nactivity state: ${lifecycle.currentState}")
                    request.onResult(Result.failure(OperationDeniedException(e.message ?: "biometric prompt failed")))
                }
            }
        }
    }
}