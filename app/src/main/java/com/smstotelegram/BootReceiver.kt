package com.smstotelegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that listens for device boot completion and restarts
 * the foreground service and WorkManager keep-alive.
 *
 * WorkManager periodic tasks are persisted across reboots and automatically
 * rescheduled by WorkManager itself. We also schedule explicitly here for
 * immediate effect rather than waiting for the next WorkManager cycle.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Device booted — rescheduling keep-alive, heartbeat, and starting service")

        // Schedule WorkManager-based keep-alive (WorkManager handles persistence
        // across reboots, but we schedule explicitly to ensure immediate activation)
        KeepAliveWorker.schedule(context)

        // Schedule daily heartbeat if enabled
        if (ForwardingManager.isHeartbeatEnabled()) {
            HeartbeatWorker.schedule(context)
        }

        // Start the foreground service
        SmsForwarderService.start(context)

        // Check battery level and send low battery alert if needed
        ForwardingManager.checkAndSendLowBatteryAlert(context)

        Log.i(TAG, "Boot completed: service started, keep-alive and heartbeat rescheduled")
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
