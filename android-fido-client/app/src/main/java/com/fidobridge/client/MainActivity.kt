package com.fidobridge.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.fidobridge.client.networking.FidoBridgeService
import com.fidobridge.client.pairing.PairingRepository
import com.fidobridge.client.pairing.PairingUriDispatcher
import com.fidobridge.client.security.BiometricPromptCoordinator
import com.fidobridge.client.security.OperationDeniedException
import com.fidobridge.client.ui.FidoBridgeApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var coordinator: BiometricPromptCoordinator

    @Inject
    lateinit var pairingRepository: PairingRepository

    @Inject
    lateinit var pairingUriDispatcher: PairingUriDispatcher

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestCameraPermissionIfNeeded()
        requestNotificationPermissionIfNeeded()
        handlePairingIntent(intent)
        setContent { FidoBridgeApp() }
        collectSigningRequests()
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

    override fun onResume() {
        super.onResume()
        if (pairingRepository.isPaired) {
            startForegroundService(Intent(this, FidoBridgeService::class.java))
        }
    }

    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun collectSigningRequests() {
        val executor = ContextCompat.getMainExecutor(this)
        lifecycleScope.launch {
            coordinator.requests.collect { request ->
                val result = suspendCancellableCoroutine<Result<BiometricPrompt.CryptoObject?>> { cont ->
                    val prompt = BiometricPrompt(
                        this@MainActivity,
                        executor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                cont.resume(Result.success(result.cryptoObject))
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                cont.resume(Result.failure(OperationDeniedException(errString.toString())))
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
                    val crypto = request.crypto
                    if (crypto != null) {
                        prompt.authenticate(promptInfo, crypto)
                    } else {
                        prompt.authenticate(promptInfo)
                    }
                }
                request.onResult(result)
            }
        }
    }
}