package com.fidobridge.client.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fidobridge.client.MainActivity
import com.fidobridge.client.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a heads-up notification when a signing request arrives while the app is
 * backgrounded, so the user knows to open FIDO Bridge and approve it. The
 * WebSocket already delivered the request; this only surfaces the app because
 * [androidx.biometric.BiometricPrompt] needs a foreground activity.
 */
@Singleton
class RequestNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundState: ForegroundStateProvider
) {

    fun notifySigningRequest(detail: String) {
        if (foregroundState.isForegrounded()) return
        if (!notificationsAllowed()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Approve sign-in")
            .setContentText("Sign-in requested by $detail — open FIDO Bridge to approve")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sign-in approvals",
            NotificationManager.IMPORTANCE_HIGH
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "fidobridge_approval"
        private const val NOTIFICATION_ID = 2
    }
}