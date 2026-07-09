package com.smstotelegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that listens for device boot completion and restarts
 * all keep-alive mechanisms and the foreground service.
 *
 * Without this, if the device reboots:
 * - AlarmManager alarms are not persisted and are lost
 * - WorkManager periodic tasks are rescheduled automatically by WorkManager,
 *   but we explicitly reschedule here for immediate effect
 * - The foreground service will not be running
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Device booted — rescheduling keep-alive and starting service")

        // Reschedule AlarmManager-based keep-alive (not persisted across reboots)
        SmsForwarderService.scheduleKeepAlive(context)

        // Schedule WorkManager-based keep-alive (WorkManager handles persistence,
        // but we schedule explicitly to ensure it's active immediately)
        KeepAliveWorker.schedule(context)

        // Start the foreground service
        SmsForwarderService.start(context)

        Log.i(TAG, "Boot completed: service started, keep-alive rescheduled")
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}