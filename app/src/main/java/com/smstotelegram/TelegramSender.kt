package com.smstotelegram

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TelegramSender {

    private const val TAG = "TelegramSender"
    private const val PREFS_NAME = "telegram_secure_prefs"
    private const val KEY_BOT_TOKEN = "bot_token"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_MESSAGE_TEMPLATE = "message_template"
    private const val DEFAULT_TEMPLATE = "📩 <b>New SMS</b>\n━━━━━━━━━━━━━━\n👤 {sender}\n💬 {message}\n📱 {sim}\n🔋 {battery}\n🕐 {time}"

    private const val TELEGRAM_API_BASE = "https://api.telegram.org/bot"
    private const val SEND_MESSAGE_ENDPOINT = "/sendMessage"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (prefs != null) return
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            Log.i(TAG, "EncryptedSharedPreferences initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences", e)
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun hasCredentials(): Boolean {
        val token = prefs?.getString(KEY_BOT_TOKEN, null)
        val chatId = prefs?.getString(KEY_CHAT_ID, null)
        return !token.isNullOrEmpty() && !chatId.isNullOrEmpty()
    }

    fun getBotToken(): String? = prefs?.getString(KEY_BOT_TOKEN, null)

    fun getChatId(): String? = prefs?.getString(KEY_CHAT_ID, null)

    fun getMessageTemplate(): String = prefs?.getString(KEY_MESSAGE_TEMPLATE, DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE

    /** Returns the app's default message template string */
    fun getDefaultTemplate(): String = DEFAULT_TEMPLATE

    fun saveCredentials(context: Context, token: String, chatId: String) {
        init(context)
        prefs?.edit()
            ?.putString(KEY_BOT_TOKEN, token.trim())
            ?.putString(KEY_CHAT_ID, chatId.trim())
            ?.apply()
        Log.i(TAG, "Credentials saved")
    }

    fun saveMessageTemplate(context: Context, template: String) {
        init(context)
        val cleaned = template.trim().ifEmpty { DEFAULT_TEMPLATE }
        prefs?.edit()
            ?.putString(KEY_MESSAGE_TEMPLATE, cleaned)
            ?.apply()
        Log.i(TAG, "Message template saved: $cleaned")
    }

    /**
     * Format a message using the configured template.
     * Supported placeholders:
     *   {sender}  - sender phone number
     *   {message} - SMS message body
     *   {sim}     - SIM card name (carrier or SIM 1)
     *   {battery} - battery charge level (e.g. "85%" or "Charging 92%")
     *   {time}    - current time (HH:mm)
     *   {date}    - current date (yyyy-MM-dd)
     */
    private fun formatMessage(template: String, sender: String, messageBody: String, simName: String): String {
        val now = Date()
        val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(now)
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

        val safeSender = sender.replace("<", "<").replace(">", ">")
        val safeMessage = messageBody.replace("<", "<").replace(">", ">")
        val safeSim = simName.replace("<", "<").replace(">", ">").ifEmpty { "Unknown" }

        val batteryLevel = appContext?.let { ForwardingManager.getBatteryLevel(it) } ?: "N/A"
        val safeBattery = batteryLevel.replace("<", "<").replace(">", ">")

        return template
            .replace("{sender}", safeSender)
            .replace("{message}", safeMessage)
            .replace("{sim}", safeSim)
            .replace("{battery}", safeBattery)
            .replace("{time}", timeStr)
            .replace("{date}", dateStr)
    }

    /**
     * Formats template and sends via Telegram.
     *
     * @param subscriptionId Optional SIM subscription ID for SIM name lookup
     */
    suspend fun sendSmsToTelegram(sender: String, messageBody: String, subscriptionId: Int? = null): Boolean = withContext(Dispatchers.IO) {
        val token = getBotToken() ?: return@withContext false
        val chatId = getChatId() ?: return@withContext false
        val template = getMessageTemplate()

        val simName = ForwardingManager.getSimDisplay(subscriptionId)
        val formattedText = formatMessage(template, sender, messageBody, simName)

        try {
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", formattedText)
                put("parse_mode", "HTML")
                put("disable_web_page_preview", true)
            }
            val url = "$TELEGRAM_API_BASE$token$SEND_MESSAGE_ENDPOINT"
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                val errorBody = response.body?.string() ?: "no body"
                Log.w(TAG, "Telegram API error: $errorBody")
            }
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to Telegram", e)
            false
        }
    }

    /**
     * Emulates the full SMS forwarding pipeline as if a real SMS was received.
     */
    data class EmulationResult(
        val success: Boolean,
        val skipped: Boolean = false,
        val reason: String = ""
    )

    suspend fun sendEmulatedSms(sender: String, messageBody: String, subscriptionId: Int? = null): EmulationResult = withContext(Dispatchers.IO) {
        if (!ForwardingManager.isEnabled()) {
            return@withContext EmulationResult(success = false, skipped = true, reason = "Forwarding is paused")
        }

        val token = getBotToken() ?: return@withContext EmulationResult(success = false, reason = "Bot token not configured")
        val chatId = getChatId() ?: return@withContext EmulationResult(success = false, reason = "Chat ID not configured")
        val template = getMessageTemplate()
        val formattedText = formatMessage(template, sender, messageBody, ForwardingManager.getSimDisplay(subscriptionId))

        try {
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", formattedText)
                put("parse_mode", "HTML")
                put("disable_web_page_preview", true)
            }
            val url = "$TELEGRAM_API_BASE$token$SEND_MESSAGE_ENDPOINT"
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()

            if (success) {
                ForwardingManager.incrementCount()
                EmulationResult(success = true)
            } else {
                MessageRetryQueue.getInstance().enqueue(sender, messageBody)
                EmulationResult(success = false, reason = "Send failed, queued for retry")
            }
        } catch (e: Exception) {
            MessageRetryQueue.getInstance().enqueue(sender, messageBody)
            EmulationResult(success = false, reason = "Error: ${e.localizedMessage ?: "Unknown error"}, queued for retry")
        }
    }

    suspend fun sendTestMessage(): Boolean = withContext(Dispatchers.IO) {
        val token = getBotToken() ?: return@withContext false
        val chatId = getChatId() ?: return@withContext false

        try {
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", "<b>Test message</b> - SmsSync is working correctly!")
                put("parse_mode", "HTML")
                put("disable_web_page_preview", true)
            }
            val url = "$TELEGRAM_API_BASE$token$SEND_MESSAGE_ENDPOINT"
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Test message failed", e)
            false
        }
    }
}