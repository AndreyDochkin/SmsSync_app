package com.smstotelegram

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory retry queue for messages that fail to send to Telegram.
 *
 * Messages are retried with exponential backoff:
 * - Retry 1: 30 seconds
 * - Retry 2: 2 minutes
 * - Retry 3: 10 minutes
 * - Retry 4: 30 minutes
 * - After 4 failed retries, the message is dropped (max 5 total attempts)
 *
 * This prevents permanent message loss when:
 * - The device has no internet connectivity
 * - Telegram API is temporarily down or rate-limiting
 * - DNS resolution fails temporarily
 */
class MessageRetryQueue {

    private val retryQueue = ConcurrentLinkedQueue<QueuedMessage>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val backoffDelays = listOf(
        30_000L,      // 30 seconds
        120_000L,     // 2 minutes
        600_000L,     // 10 minutes
        1_800_000L    // 30 minutes
    )

    // Use AtomicBoolean for thread-safe processing flag
    private val isProcessing = AtomicBoolean(false)

    data class QueuedMessage(
        val sender: String,
        val body: String,
        val timestamp: Long = System.currentTimeMillis(),
        var retryCount: Int = 0
    )

    /**
     * Add a message to the retry queue for later delivery.
     */
    fun enqueue(sender: String, body: String) {
        retryQueue.add(QueuedMessage(sender, body))
        Log.d(TAG, "Message queued for retry (queue size: ${retryQueue.size})")
        processQueue()
    }

    /**
     * Current number of messages waiting for retry.
     */
    val pendingCount: Int
        get() = retryQueue.size

    /**
     * Process the queue by retrying failed messages with exponential backoff.
     * Uses AtomicBoolean to prevent concurrent processing.
     */
    private fun processQueue() {
        // Atomically check and set the processing flag
        if (!isProcessing.compareAndSet(false, true)) {
            Log.d(TAG, "Retry queue already processing, skipping")
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "Starting retry queue processing (${retryQueue.size} messages)")
                while (retryQueue.isNotEmpty()) {
                    val message = retryQueue.peek() ?: break

                    // Check if forwarding is still enabled before retrying
                    if (!ForwardingManager.isEnabled()) {
                        Log.d(TAG, "Forwarding paused, deferring retry queue")
                        break
                    }

                    val success = TelegramSender.sendSmsToTelegram(message.sender, message.body)

                    if (success) {
                        retryQueue.poll() // Remove from queue
                        Log.i(TAG, "Queued message sent successfully (${retryQueue.size} remaining)")
                    } else {
                        message.retryCount++
                        if (message.retryCount > backoffDelays.size) {
                            retryQueue.poll() // Give up after max retries
                            Log.w(TAG, "Dropped message after ${message.retryCount} retries (sender: ${message.sender})")
                        } else {
                            val delayMs = backoffDelays[message.retryCount - 1]
                            Log.d(TAG, "Retry ${message.retryCount}/${backoffDelays.size} in ${delayMs / 1000}s")
                            delay(delayMs)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Retry queue processing error", e)
            } finally {
                isProcessing.set(false)
                Log.d(TAG, "Retry queue processing complete")
            }
        }
    }

    companion object {
        private const val TAG = "MessageRetryQueue"

        @Volatile
        private var instance: MessageRetryQueue? = null

        /**
         * Thread-safe singleton accessor.
         */
        fun getInstance(): MessageRetryQueue {
            return instance ?: synchronized(this) {
                instance ?: MessageRetryQueue().also { instance = it }
            }
        }
    }
}