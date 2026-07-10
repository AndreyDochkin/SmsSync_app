package com.smstotelegram

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BroadcastReceiver that listens for incoming SMS messages and forwards them
 * to Telegram via TelegramSender. If the send fails (network error, API down),
 * the message is queued in MessageRetryQueue for automatic retry with exponential backoff.
 *
 * Checks ForwardingManager for:
 * - Global forwarding toggle (pause/resume)
 * 
 * Detects which SIM slot received the SMS using a multi-layered approach:
 *   1. Intent extras (fast, works on most stock Android)
 *   2. SMS ContentProvider query (reliable fallback, reads subscription_id from SMS db)
 *
 * Uses goAsync() + PARTIAL_WAKE_LOCK (10s timeout) for reliable delivery
 * even when the device is in Doze mode.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val WAKE_LOCK_TAG = "SmsSync:WakeLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

        /**
         * All known intent extra keys that OEMs may use to pass SIM/subscription
         * information in the SMS_RECEIVED broadcast.
         *
         * Each entry: Pair(key, isZeroBased)
         *   true  = value is a 0-based slot index (0 = SIM 1, 1 = SIM 2)
         *   false = value is a 1-based subscription ID (1 = SIM 1, 2 = SIM 2)
         *   null  = ambiguous (apply heuristic)
         */
        private val SIM_EXTRA_KEYS = listOf(
            // 0-based slot index keys — used by Xiaomi, Huawei, Oppo, MediaTek
            Pair("slot", true),
            Pair("slot_id", true),
            Pair("simId", true),
            Pair("simid", true),
            Pair("sim_slot", true),
            Pair("simSlot", true),
            Pair("simSlotIndex", true),
            Pair("phoneId", true),
            Pair("phone", true),
            Pair("phoneSlot", true),
            Pair("sim_idx", true),
            // 1-based subscription ID keys — official Android API
            Pair("android.telephony.extra.SUBSCRIPTION_ID", false),
            Pair("sub_id", false),
            Pair("subid", false),
            // Ambiguous — OEM-dependent
            Pair("subscription", null),
        )

        /**
         * Column names for subscription ID in the SMS ContentProvider,
         * varying by OEM and Android version.
         */
        private val PROVIDER_SUB_COLUMNS = listOf(
            "subscription_id",  // AOSP 5.1+
            "sub_id",           // Samsung, older Android
            "sim_id",           // Xiaomi, Huawei
            "sim_slot"          // MediaTek
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val retryQueue = MessageRetryQueue.getInstance()

    /**
     * Main SIM detection entry point. Tries multiple strategies in order.
     *
     * @return 0-based slot index (0 = SIM 1, 1 = SIM 2) or null if undetectable.
     */
    private fun detectSimSlot(context: Context, intent: Intent, senderAddress: String): Int? {
        // Strategy 1: Read all known intent extra keys
        detectSimSlotFromIntentExtras(intent)?.let { slot ->
            Log.d(TAG, "SIM detected from intent extras: slot $slot")
            return slot
        }

        // Strategy 2: Query SMS ContentProvider
        // The SMS database stores a subscription_id column that records
        // which SIM received each message. This is very reliable but may
        // not be immediately available at broadcast time (SMS not yet stored).
        detectSimSlotFromContentProvider(context, senderAddress)?.let { slot ->
            Log.d(TAG, "SIM detected from ContentProvider: slot $slot")
            return slot
        }

        Log.w(TAG, "Could not detect SIM slot via any method")
        return null
    }

    /**
     * Strategy 1: Read all known intent extra keys from the SMS_RECEIVED broadcast.
     *
     * OEM mappings:
     *   AOSP/Google    → subscription (1-based), SUBSCRIPTION_ID (1-based)
     *   Samsung        → subscription (1-based), simSlotIndex (0-based on OneUI 3+)
     *   Xiaomi/Redmi   → simId, simid, simSlot (0-based)
     *   Huawei         → sim_slot, phoneId (0-based)
     *   Oppo/Realme    → slot (0-based), subscription (1-based)
     *   MediaTek (MTK) → phoneId, sim_slot (0-based), subscription (1-based)
     *   LG             → phone (0-based), subscription (1-based)
     *   Motorola       → slot (0-based), subscription (1-based)
     */
    private fun detectSimSlotFromIntentExtras(intent: Intent): Int? {
        for ((key, isZeroBased) in SIM_EXTRA_KEYS) {
            val rawValue = intent.getIntExtra(key, -1)
            if (rawValue == -1) continue

            Log.d(TAG, "Intent extra '$key' = $rawValue (isZeroBased=$isZeroBased)")

            return when (isZeroBased) {
                true -> rawValue.coerceIn(0, 1)                     // 0-based → use directly
                false -> (rawValue - 1).coerceIn(0, 1)              // 1-based → subtract 1
                null -> convertAmbiguousSubId(rawValue)             // ambiguous → heuristic
            }
        }
        return null
    }

    /**
     * Heuristic for the ambiguous "subscription" key.
     *
     * On AOSP (stock Android): 1-based (1=SIM1, 2=SIM2).
     * On some Chinese OEMs: 0-based slot index (0=SIM1).
     *
     *   value 0 → slot 0 (SIM 1) — likely 0-based
     *   value 1 → slot 0 (SIM 1) — ambiguous but most common case (subId 1)
     *   value 2+ → 1-based, convert by subtracting 1
     */
    private fun convertAmbiguousSubId(value: Int): Int {
        return when (value) {
            0 -> 0
            1 -> 0
            else -> (value - 1).coerceIn(0, 1)
        }
    }

    /**
     * Strategy 2: Query the SMS ContentProvider's Inbox for the most recent
     * message matching the sender, and extract the subscription column.
     *
     * This is the most reliable method because the SMS database always records
     * which subscription received each message.
     *
     * However, timing matters: the SMS may not yet be stored when this broadcast
     * fires (SMS_RECEIVED fires *before* the message is written to the database
     * on some devices). We add a small polling loop to handle this.
     */
    @SuppressLint("Range")
    private fun detectSimSlotFromContentProvider(context: Context, senderAddress: String): Int? {
        // Ensure we have READ_SMS permission (required for ContentProvider query)
        if (!hasReadSmsPermission(context)) {
            Log.d(TAG, "No READ_SMS permission, skipping ContentProvider query")
            return null
        }

        try {
            val contentUri = Telephony.Sms.Inbox.CONTENT_URI

            // Try querying up to 3 times with a small delay, because the SMS
            // might not be written to the database yet at broadcast time.
            for (attempt in 1..3) {
                if (attempt > 1) {
                    Thread.sleep(500) // 500ms delay between retries
                }

                for (column in PROVIDER_SUB_COLUMNS) {
                    try {
                        val cursor = context.contentResolver.query(
                            contentUri,
                            arrayOf(column, "date"),
                            "address = ?",
                            arrayOf(senderAddress),
                            "date DESC"
                        )
                        cursor?.use { c ->
                            if (c.moveToFirst()) {
                                val subVal = c.getInt(c.getColumnIndex(column))
                                Log.d(TAG, "ContentProvider attempt $attempt: $column = $subVal")

                                // subscription_id, sub_id: 1-based → subtract 1
                                // sim_id, sim_slot: 0-based → use directly
                                val slot = when (column) {
                                    "sim_slot", "sim_id" -> subVal.coerceIn(0, 1)
                                    else -> (subVal - 1).coerceIn(0, 1)
                                }
                                Log.d(TAG, "ContentProvider result: column=$column value=$subVal → slot=$slot")
                                return slot
                            }
                        }
                    } catch (_: Exception) {
                        // Column not supported on this device — try next
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ContentProvider query failed", e)
        }
        return null
    }

    private fun hasReadSmsPermission(context: Context): Boolean {
        return try {
            android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_SMS
                )
        } catch (e: Exception) {
            false
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: "Unknown"
        val messageBody = messages.joinToString("") { it.messageBody ?: "" }

        // Detect SIM slot using multi-layered approach
        val subscriptionId = detectSimSlot(context, intent, sender)

        Log.i(TAG, "SMS received from: $sender on SIM slot ${subscriptionId ?: "?"}")

        // Check if forwarding is paused
        if (!ForwardingManager.isEnabled()) {
            Log.d(TAG, "Forwarding paused, ignoring SMS from $sender")
            return
        }

        // Use goAsync() to allow asynchronous processing after onReceive returns
        val pendingResult = goAsync()

        // Acquire wake lock to keep CPU alive in Doze mode
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        )
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val success = TelegramSender.sendSmsToTelegram(sender, messageBody, subscriptionId)
                    if (success) {
                        ForwardingManager.incrementCount()
                        Log.i(TAG, "Message forwarded successfully (daily count: ${ForwardingManager.getDailyCount()})")
                    } else {
                        Log.w(TAG, "Initial send failed, queuing for retry")
                        retryQueue.enqueue(sender, messageBody)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS, queuing for retry", e)
                retryQueue.enqueue(sender, messageBody)
            } finally {
                // Finish the pending broadcast and release wake lock
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}