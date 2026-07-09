package com.smstotelegram

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based keep-alive worker that supplements the AlarmManager keep-alive.
 *
 * WorkManager is more resistant to OEM power-saving restrictions on Chinese/Indian
 * devices (Xiaomi, Huawei, Oppo, Realme, Vivo) where AlarmManager is aggressively
 * deferred or killed for non-whitelisted apps.
 *
 * This runs every 15 minutes (WorkManager minimum) and ensures the foreground
 * service is still running, re-showing the notification so OEMs don't hide it.
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Keep-alive worker executing")

            // Re-show notification to prevent OEMs from hiding it
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(applicationContext))

            // Ensure the foreground service is still running
            val serviceIntent = Intent(applicationContext, SmsForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            Log.i(TAG, "Keep-alive worker completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Keep-alive worker failed", e)
            Result.retry()
        }
    }

    private fun createNotification(context: Context): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SMS Forwarding Active")
            .setContentText("SMS forwarding is active in the background")
            .setSmallIcon(R.drawable.ic_sms_bot)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveWorker"
        private const val NOTIFICATION_CHANNEL_ID = "sms_forwarder_channel"
        private const val NOTIFICATION_ID = 1
        private const val WORK_NAME = "keep_alive"
        private const val INTERVAL_MINUTES = 15L

        /**
         * Schedule the periodic keep-alive worker.
         * Uses KEEP policy so existing schedules are not duplicated.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Keep-alive worker scheduled every $INTERVAL_MINUTES minutes")
        }

        /**
         * Cancel the keep-alive worker (e.g., when service is intentionally stopped).
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Keep-alive worker cancelled")
        }
    }
}