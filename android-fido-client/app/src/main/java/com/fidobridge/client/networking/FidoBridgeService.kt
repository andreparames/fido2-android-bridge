package com.fidobridge.client.networking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.fidobridge.client.billing.SubscriptionRepository
import com.fidobridge.client.bridge.BridgePipeline
import com.fidobridge.client.bridge.BridgeState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FidoBridgeService : Service() {

    @Inject
    lateinit var pipeline: BridgePipeline

    @Inject
    lateinit var subscriptionRepository: SubscriptionRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defense in depth (billing.md §6.2): do not run the foreground relay for
        // a user without an active entitlement, even if the UI/start path missed
        // the gate. The pipeline also fails closed, but never go foreground first.
        if (!subscriptionRepository.entitlement.value.isEntitled) {
            Log.w(TAG, "not entitled; refusing to start foreground service")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        createChannel()
        val notification = buildNotification(bridgeNotificationText(BridgeState.Connecting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        pipeline.start()
        observeBridgeState()
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        pipeline.stop()
        super.onDestroy()
    }

    private fun observeBridgeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            pipeline.state.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(bridgeNotificationText(state)))
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FIDO Bridge relay",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("FIDO Bridge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "fidobridge_relay"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "FidoBridge"
    }
}

internal fun bridgeNotificationText(state: BridgeState): String = when (state) {
    BridgeState.Connected -> "Waiting for WebAuthn requests"
    BridgeState.Connecting -> "Connecting…"
    BridgeState.Disconnected -> "Not connected"
    BridgeState.SecurityAlert -> "Security alert — approvals paused"
    is BridgeState.Error -> state.message
}