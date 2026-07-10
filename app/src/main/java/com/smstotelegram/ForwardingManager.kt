package com.smstotelegram

import android.content.Context
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Manages forwarding state, SIM card detection, and daily statistics.
 *
 * Features:
 * - Global forwarding toggle (pause/resume without losing credentials)
 * - Forwarding statistics (daily count, last forwarded time)
 * - Carrier detection per SIM (shows operator name like "T-Mobile" or "Vodafone")
 * - Custom SIM naming for message templates
 * - Battery level reading for {battery} placeholder
 */
object ForwardingManager {

    private const val TAG = "ForwardingManager"
    private const val PREFS_NAME = "forwarding_config"
    private const val KEY_ENABLED = "forwarding_enabled"
    private const val KEY_DAILY_COUNT = "daily_forward_count"
    private const val KEY_LAST_FORWARD_TIME = "last_forward_time"
    private const val KEY_LAST_DATE = "last_date"
    private const val KEY_SIM1_NAME = "sim1_name"
    private const val KEY_SIM2_NAME = "sim2_name"
    private const val KEY_SIM1_CARRIER = "sim1_carrier"
    private const val KEY_SIM2_CARRIER = "sim2_carrier"
    private const val KEY_SIM1_CUSTOM_NAME = "sim1_custom_name"
    private const val KEY_SIM2_CUSTOM_NAME = "sim2_custom_name"
    private const val KEY_CONFIG_VERSION = "config_version"
    private const val CURRENT_CONFIG_VERSION = 2

    private var prefs: SharedPreferences? = null
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ── Migration: fix stale SIM carrier data from v1 bug ──
        // Old buggy code assigned the default SIM's operator name to BOTH SIM 1 and SIM 2.
        // If both carriers are identical, clear SIM 2 carrier so auto-detection can
        // properly re-detect it via SubscriptionManager.
        val configVersion = prefs?.getInt(KEY_CONFIG_VERSION, 0) ?: 0
        if (configVersion < CURRENT_CONFIG_VERSION) {
            Log.i(TAG, "Running config migration v$configVersion → v$CURRENT_CONFIG_VERSION")
            migrateConfig()
            prefs?.edit()?.putInt(KEY_CONFIG_VERSION, CURRENT_CONFIG_VERSION)?.apply()
        }

        // Reset daily counter if date changed
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val lastDate = prefs?.getString(KEY_LAST_DATE, "")
        if (lastDate != today) {
            prefs?.edit()
                ?.putInt(KEY_DAILY_COUNT, 0)
                ?.putString(KEY_LAST_DATE, today)
                ?.apply()
        }
        // Auto-detect SIM info
        detectSimInfo(context)
        Log.i(TAG, "ForwardingManager initialized")
    }

    /**
     * Migrate config from old versions. Clears stale SIM carrier data
     * that was corrupted by the v1 bug (both SIMs showing same operator).
     */
    private fun migrateConfig() {
        val sim1Carrier = prefs?.getString(KEY_SIM1_CARRIER, null)
        val sim2Carrier = prefs?.getString(KEY_SIM2_CARRIER, null)

        // If both carriers are identical (the v1 bug), clear SIM 2 so it gets re-detected
        if (sim1Carrier != null && sim1Carrier == sim2Carrier) {
            Log.w(TAG, "Migration: detected stale SIM 2 carrier ('$sim2Carrier' == SIM 1), clearing")
            prefs?.edit()?.remove(KEY_SIM2_CARRIER)?.apply()
        }
    }

    /**
     * Detect SIM display name and carrier/operator name for each SIM slot.
     *
     * Uses SubscriptionInfo for the user-facing label (e.g. "SIM 1", "Vodafone")
     * and simCarrierId/operatorName for the carrier name (e.g. "T-Mobile", "Vodafone ES").
     * Falls back to TelephonyManager for multi-SIM on some devices.
     */
    private fun detectSimInfo(ctx: Context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                val subs = subscriptionManager.activeSubscriptionInfoList
                if (subs != null) {
                    for (sub in subs) {
                        val slotIndex = sub.simSlotIndex
                        val displayName = sub.displayName?.toString() ?: "SIM ${slotIndex + 1}"

                        // Get carrier/operator name (most reliable identifier)
                        val carrierName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            // Android 10+ has carrier ID and name
                            sub.carrierName?.toString()?.ifBlank { null }
                        } else {
                            // Fallback: use TelephonyManager for older APIs
                            try {
                                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    val slotTm = tm.createForSubscriptionId(sub.subscriptionId)
                                    slotTm.simOperatorName?.ifBlank { null }
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }

                        if (slotIndex == 0) {
                            prefs?.edit()
                                ?.putString(KEY_SIM1_NAME, displayName)
                                ?.putString(KEY_SIM1_CARRIER, carrierName)
                                ?.apply()
                            Log.d(TAG, "SIM 1: name=$displayName, carrier=$carrierName")
                        } else if (slotIndex == 1) {
                            prefs?.edit()
                                ?.putString(KEY_SIM2_NAME, displayName)
                                ?.putString(KEY_SIM2_CARRIER, carrierName)
                                ?.apply()
                            Log.d(TAG, "SIM 2: name=$displayName, carrier=$carrierName")
                        }
                    }
                }
            }

            // If carrier info wasn't found for EITHER SIM, try direct TelephonyManager APIs.
            // IMPORTANT: tm.simOperatorName returns ONLY the default SIM's operator — do NOT
            // assign it to both SIMs. Instead, try createForSubscriptionId() for each slot.
            if (getSim1Carrier() == null || getSim2Carrier() == null) {
                Log.d(TAG, "Running fallback detection for missing carriers (SIM1=${getSim1Carrier()}, SIM2=${getSim2Carrier()})")
                try {
                    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        val subscriptionManager = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
                        val activeSubs = subscriptionManager?.activeSubscriptionInfoList

                        if (!activeSubs.isNullOrEmpty()) {
                            // Use SubscriptionManager to get per-subscription info
                            for (sub in activeSubs) {
                                val slotIdx = sub.simSlotIndex
                                try {
                                    val slotTm = tm.createForSubscriptionId(sub.subscriptionId)
                                    val carrier = slotTm.simOperatorName?.ifBlank { null }
                                    if (carrier != null) {
                                        if (slotIdx == 0 && getSim1Carrier() == null) {
                                            prefs?.edit()?.putString(KEY_SIM1_CARRIER, carrier)?.apply()
                                            Log.d(TAG, "SIM 1 carrier (per-sub fallback): $carrier")
                                        } else if (slotIdx == 1 && getSim2Carrier() == null) {
                                            prefs?.edit()?.putString(KEY_SIM2_CARRIER, carrier)?.apply()
                                            Log.d(TAG, "SIM 2 carrier (per-sub fallback): $carrier")
                                        }
                                    }
                                } catch (_: Exception) {
                                    Log.w(TAG, "Failed to get carrier for sub ${sub.subscriptionId} slot $slotIdx")
                                }
                            }
                        } else {
                            // SubscriptionManager didn't return active subs.
                            // Try direct subscription IDs 1 and 2 as a fallback.
                            // On many devices, subscription IDs are 1-based (1 = SIM 1, 2 = SIM 2).
                            Log.d(TAG, "No active subs from SubscriptionManager, trying direct sub IDs")
                            val subIdsToTry = listOf(1, 2)
                            for (subId in subIdsToTry) {
                                try {
                                    val slotTm = tm.createForSubscriptionId(subId)
                                    val carrier = slotTm.simOperatorName?.ifBlank { null }
                                    if (carrier != null) {
                                        val slotIdx = subId - 1  // 1-based → 0-based slot
                                        if (slotIdx == 0 && getSim1Carrier() == null) {
                                            prefs?.edit()?.putString(KEY_SIM1_CARRIER, carrier)?.apply()
                                            Log.d(TAG, "SIM 1 carrier (direct subId $subId): $carrier")
                                        } else if (slotIdx == 1 && getSim2Carrier() == null) {
                                            prefs?.edit()?.putString(KEY_SIM2_CARRIER, carrier)?.apply()
                                            Log.d(TAG, "SIM 2 carrier (direct subId $subId): $carrier")
                                        }
                                    }
                                } catch (_: Exception) {
                                    Log.d(TAG, "No carrier info for subId $subId (expected if slot empty)")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback carrier detection failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect SIM info", e)
        }
    }

    /** Refresh SIM info from system (call after SIM state changes) */
    fun refreshSimInfo() {
        context?.let { detectSimInfo(it) }
    }

    /** Is forwarding currently enabled? */
    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, true) ?: true

    /** Set forwarding enabled/disabled */
    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        Log.i(TAG, "Forwarding ${if (enabled) "enabled" else "disabled"}")
    }

    /** Toggle forwarding state */
    fun toggle(): Boolean {
        val newState = !isEnabled()
        setEnabled(newState)
        return newState
    }

    /** Get today's forwarded message count */
    fun getDailyCount(): Int = prefs?.getInt(KEY_DAILY_COUNT, 0) ?: 0

    /** Increment today's forwarded count */
    fun incrementCount() {
        val count = getDailyCount() + 1
        val now = System.currentTimeMillis()
        prefs?.edit()
            ?.putInt(KEY_DAILY_COUNT, count)
            ?.putLong(KEY_LAST_FORWARD_TIME, now)
            ?.apply()
    }

    /** Get timestamp of last forwarded message */
    fun getLastForwardTime(): Long = prefs?.getLong(KEY_LAST_FORWARD_TIME, 0L) ?: 0L

    // ---- SIM Detection (for {sim} placeholder) ----

    /** Get the display name of SIM 1 */
    fun getSim1Name(): String = prefs?.getString(KEY_SIM1_NAME, "SIM 1") ?: "SIM 1"

    /** Get the display name of SIM 2 */
    fun getSim2Name(): String = prefs?.getString(KEY_SIM2_NAME, "SIM 2") ?: "SIM 2"

    /** Get the carrier/operator name of SIM 1 (e.g. "Vodafone") */
    fun getSim1Carrier(): String? = prefs?.getString(KEY_SIM1_CARRIER, null)

    /** Get the carrier/operator name of SIM 2 (e.g. "T-Mobile") */
    fun getSim2Carrier(): String? = prefs?.getString(KEY_SIM2_CARRIER, null)

    // ---- Custom SIM naming ----

    /** Get user-defined custom name for SIM 1 (e.g. "Personal", "Business") */
    fun getSim1CustomName(): String? = prefs?.getString(KEY_SIM1_CUSTOM_NAME, null)?.ifBlank { null }

    /** Get user-defined custom name for SIM 2 */
    fun getSim2CustomName(): String? = prefs?.getString(KEY_SIM2_CUSTOM_NAME, null)?.ifBlank { null }

    /** Save user-defined custom name for SIM 1 */
    fun saveSim1CustomName(name: String) {
        prefs?.edit()?.putString(KEY_SIM1_CUSTOM_NAME, name.trim())?.apply()
        Log.d(TAG, "SIM 1 custom name saved: $name")
    }

    /** Save user-defined custom name for SIM 2 */
    fun saveSim2CustomName(name: String) {
        prefs?.edit()?.putString(KEY_SIM2_CUSTOM_NAME, name.trim())?.apply()
        Log.d(TAG, "SIM 2 custom name saved: $name")
    }

    /**
     * Get display string for a given SIM slot (0-based index).
     *
     * Callers (e.g. SmsReceiver) must pass 0-based slot indices:
     *   0 = SIM 1, 1 = SIM 2
     *
     * Format: "Vodafone" if carrier available, or "SIM 1" / "SIM 2" as fallback.
     */
    fun getSimDisplay(slotIndex: Int?): String {
        if (slotIndex == null) return "Unknown"
        return when (slotIndex) {
            0 -> getSim1Display()   // SIM 1
            1 -> getSim2Display()   // SIM 2
            else -> "Unknown"
        }
    }

    /** Combined display for SIM 1: custom name > carrier name > "SIM 1" fallback */
    fun getSim1Display(): String {
        val custom = getSim1CustomName()
        if (custom != null) return custom
        val carrier = getSim1Carrier()
        val sim2Carrier = getSim2Carrier()
        // Only show carrier name if it's different from SIM 2's carrier,
        // otherwise fall back to "SIM 1" so both slots are distinguishable.
        if (carrier != null && carrier != sim2Carrier) return carrier
        return getSim1Name()
    }

    /** Combined display for SIM 2: custom name > carrier name > "SIM 2" fallback */
    fun getSim2Display(): String {
        val custom = getSim2CustomName()
        if (custom != null) return custom
        val carrier = getSim2Carrier()
        val sim1Carrier = getSim1Carrier()
        // Only show carrier name if it's different from SIM 1's carrier,
        // otherwise fall back to "SIM 2" so both slots are distinguishable.
        if (carrier != null && carrier != sim1Carrier) return carrier
        return getSim2Name()
    }

    // ---- Battery level ----

    /**
     * Get the current battery charge level as a formatted string.
     *
     * Uses the sticky ACTION_BATTERY_CHANGED intent which requires no
     * permanent receiver registration.
     *
     * @return "85%", "Charging 92%", or "N/A" if unavailable.
     */
    fun getBatteryLevel(ctx: Context): String {
        try {
            val intent = ctx.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1

            if (level == -1 || scale == -1) return "N/A"

            val percentage = (level * 100.0 / scale).toInt()
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL

            return if (charging) "Charging $percentage%" else "$percentage%"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery level", e)
            return "N/A"
        }
    }
}
