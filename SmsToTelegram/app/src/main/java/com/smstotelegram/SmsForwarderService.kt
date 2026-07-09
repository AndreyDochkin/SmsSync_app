package com.smstotelegram

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the SMS forwarding process alive.
 *
 * Uses a dual keep-alive strategy for maximum OEM compatibility:
 * 1. [AlarmManager] — Low-latency, fires every 5 minutes for timely re-shows
 * 2. [WorkManager] via [KeepAliveWorker] — OEM-resistant, fires every 15 minutes
 *
 * On Chinese/Indian OEMs (Xiaomi, Huawei, Oppo, Realme, Vivo), AlarmManager
 * is aggressive deferred or killed. WorkManager is more resistant to these
 * restrictions because it uses JobScheduler or AlarmManager + BOOT_COMPLETED
 * persistence internally.
 */
class SmsForwarderService : Service() {

    companion object {
        private const val TAG = "SmsForwarderService"
        private const val NOTIFICATION_CHANNEL_ID = "sms_forwarder_channel"
        private const val NOTIFICATION_ID = 1
        private const val KEEP_ALIVE_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        fun start(context: Context) {
            val intent = Intent(context, SmsForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun scheduleKeepAlive(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SmsForwarderService::class.java).apply {
                action = ACTION_KEEP_ALIVE
            }
            val pendingIntent = PendingIntent.getService(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + KEEP_ALIVE_INTERVAL_MS,
                KEEP_ALIVE_INTERVAL_MS,
                pendingIntent
            )
            Log.d(TAG, "AlarmManager keep-alive scheduled every ${KEEP_ALIVE_INTERVAL_MS / 60000} minutes")
        }

        private const val ACTION_KEEP_ALIVE = "com.smstotelegram.KEEP_ALIVE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "SmsForwarderService started in foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle keep-alive alarm
        if (intent?.action == ACTION_KEEP_ALIVE) {
            Log.d(TAG, "Keep-alive ping received, service is alive")
            // Acquire a temporary wake lock to ensure we complete processing
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmsSync:KeepAlive"
            )
            wakeLock.acquire(3000L)
            try {
                // Re-show notification to prevent OEMs from hiding it
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, createNotification())
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
            return START_STICKY
        }

        // Schedule periodic keep-alives on service start

        // 1. AlarmManager-based keep-alive (low-latency, every 5 min)
        scheduleKeepAlive(this)

        // 2. WorkManager-based keep-alive (OEM-resistant, every 15 min)
        //    This is called on every start to ensure the worker is scheduled
        KeepAliveWorker.schedule(this)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "SmsForwarderService destroyed — restarting")
        // Self-restart on destruction
        startService(Intent(this, SmsForwarderService::class.java))
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