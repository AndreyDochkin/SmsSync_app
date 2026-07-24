package com.smstotelegram

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based daily heartbeat worker that sends a periodic "I'm alive"
 * message to the configured Telegram chat.
 *
 * This provides a remote health check — if the heartbeat stops arriving,
 * the user knows the app has been killed or the phone is off.
 *
 * Runs once per day at a configurable time. Uses WorkManager's
 * PeriodicWorkRequest which respects Doze mode and batches with
 * other system jobs for minimal battery impact.
 */
class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Heartbeat worker executing")

            // Ensure singletons are initialized
            TelegramSender.init(applicationContext)
            ForwardingManager.init(applicationContext)

            // Check if heartbeat is still enabled
            if (!ForwardingManager.isHeartbeatEnabled()) {
                Log.d(TAG, "Heartbeat disabled, skipping")
                return Result.success()
            }

            // Check if credentials are configured
            if (!TelegramSender.hasCredentials()) {
                Log.w(TAG, "Heartbeat skipped: credentials not configured")
                return Result.success()
            }

            // Build heartbeat message with stats
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val dailyCount = ForwardingManager.getDailyCount()
            val batteryLevel = ForwardingManager.getBatteryLevel(applicationContext)
            val forwardingEnabled = ForwardingManager.isEnabled()
            val retryPending = MessageRetryQueue.getInstance().pendingCount

            val statusEmoji = if (forwardingEnabled) "\u2705" else "\u23F8"
            val message = buildString {
                append("\uD83D\uDC93 <b>Daily Heartbeat</b>\n")
                append("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\n")
                append("$statusEmoji Status: ${if (forwardingEnabled) "Active" else "Paused"}\n")
                append("\uD83D\uDCF1 Forwarded today: $dailyCount\n")
                append("\uD83D\uDD0B Battery: $batteryLevel\n")
                append("\u23F3 Pending retries: $retryPending\n")
                append("\uD83D\uDD52 $now")
            }

            val success = TelegramSender.sendRawMessage(message)
            if (success) {
                ForwardingManager.setLastHeartbeatTime(System.currentTimeMillis())
                Log.i(TAG, "Heartbeat sent successfully")
                Result.success()
            } else {
                Log.w(TAG, "Heartbeat send failed, will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val WORK_NAME = "daily_heartbeat"
        // Default interval: 24 hours. WorkManager's minimum is 15 minutes.
        private const val INTERVAL_HOURS = 24L

        /**
         * Schedule the daily heartbeat worker.
         * Uses KEEP policy so existing schedules are not duplicated.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Send even on low battery
                .build()

            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
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

            Log.i(TAG, "Heartbeat scheduled every $INTERVAL_HOURS hours")
        }

        /**
         * Cancel the heartbeat worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Heartbeat worker cancelled")
        }

        /**
         * Reschedule the heartbeat (call when enabled/disabled state changes).
         */
        fun reschedule(context: Context) {
            cancel(context)
            if (ForwardingManager.isHeartbeatEnabled()) {
                schedule(context)
            }
        }
    }
}