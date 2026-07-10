package com.smstotelegram

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based keep-alive worker that ensures the foreground service
 * remains running on OEMs that aggressively kill background services.
 *
 * Runs every 25 minutes (respects Doze mode via JobScheduler batching).
 *
 * Battery-aware design:
 * - No notification re-show (the foreground service notification stays visible)
 * - No wake lock acquisition
 * - Longer interval (25 min vs previous 15 min) reduces wake-ups by 40%
 * - WorkManager batches with other system jobs, respecting Doze mode windows
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Keep-alive worker executing")

            // Ensure the foreground service is still running.
            // No notification re-show needed — the foreground service's
            // notification stays active as long as startForeground() was called.
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

    companion object {
        private const val TAG = "KeepAliveWorker"
        private const val WORK_NAME = "keep_alive"
        private const val INTERVAL_MINUTES = 25L

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
