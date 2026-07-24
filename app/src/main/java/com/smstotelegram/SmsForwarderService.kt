package com.smstotelegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the SMS forwarding process alive.
 *
 * Uses [WorkManager] via [KeepAliveWorker] as the sole keep-alive mechanism.
 * WorkManager is OEM-resistant (uses JobScheduler internally) and respects
 * Doze mode by batching work with other system jobs, minimizing wake-ups.
 *
 * Previously used a dual AlarmManager + WorkManager strategy, but AlarmManager
 * with ELAPSED_REALTIME_WAKEUP was waking the device unnecessarily every 5
 * minutes even when no SMS was received. WorkManager alone at a longer interval
 * provides sufficient keep-alive with significantly lower battery impact.
 */
class SmsForwarderService : Service() {

    companion object {
        private const val TAG = "SmsForwarderService"
        private const val NOTIFICATION_CHANNEL_ID = "sms_forwarder_channel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, SmsForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "SmsForwarderService started in foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Schedule WorkManager-based keep-alive (OEM-resistant, every 25 min)
        // Called on every start to ensure the worker is scheduled
        KeepAliveWorker.schedule(this)

        // Schedule daily heartbeat if enabled
        if (ForwardingManager.isHeartbeatEnabled()) {
            HeartbeatWorker.schedule(this)
        }

        // Check battery level and send low battery alert if needed
        ForwardingManager.checkAndSendLowBatteryAlert(this)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "SmsForwarderService destroyed — restarting")
        // Self-restart on destruction using the correct API for the SDK level.
        // Using startService() on Android 8+ would crash with IllegalStateException
        // because background apps cannot start services directly.
        val intent = Intent(this, SmsForwarderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SMS Forwarder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SMS forwarding active in the background"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // Build an intent to open the app when notification is tapped
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SMS Forwarding Active")
            .setContentText("SMS forwarding is active in the background")
            .setSmallIcon(R.drawable.ic_sms_bot)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPendingIntent)
            .build()
    }
}
