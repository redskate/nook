package solutions.semweb.nook.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R
import solutions.semweb.nook.sms.SMSSender
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a queue of SMS messages to prevent duplicate rapid sending
 * Implements a circular buffer of sent messages for duplicate detection
 */
object SMSQueueManager {
    private const val TAG = "SMSQueueManager"
    private const val QUEUE_SIZE = 100
    private const val DUPLICATE_WINDOW_MS = 30000L // 30 seconds default

    // Queue for pending messages
    private val pendingQueue: Queue<PendingSMS> = LinkedList()

    // Circular buffer for recently sent messages (ring buffer)
    private val sentMessages = Array<SentSMS?>(QUEUE_SIZE) { null }
    private var sentIndex = 0

    // Processing state
    private val isProcessing = AtomicBoolean(false)
    private val processingHandler = Handler(Looper.getMainLooper())
    private var processingRunnable: Runnable? = null

    // Duplicate window (configurable)
    var duplicateWindowMs = DUPLICATE_WINDOW_MS

    data class PendingSMS(
        val context: Context,
        val phoneNumber: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class SentSMS(
        val phoneNumber: String,
        val message: String,
        val timestamp: Long
    ) {
        // Check if this message is a duplicate of another within the time window
        fun isDuplicate(otherPhone: String, otherMessage: String, windowMs: Long): Boolean {
            return phoneNumber == otherPhone &&
                    message == otherMessage &&
                    System.currentTimeMillis() - timestamp < windowMs
        }
    }

    /**
     * Queue an SMS for sending with duplicate detection
     * Returns true if queued, false if duplicate and suppressed
     */
    fun queueSms(context: Context, phoneNumber: String, message: String): Boolean {
        // Check for duplicates in recent sent messages
        if (isDuplicate(phoneNumber, message)) {
            LogUtils.d(context, TAG, "⚠️ Duplicate SMS detected - suppressing: $phoneNumber")

            // Show toast about suppression
            Handler(Looper.getMainLooper()).post {
                MainActivity.showToast(
                    context.getString(R.string.sms_duplicate_suppressed, duplicateWindowMs / 1000)
                )
            }
            return false
        }

        // Check for duplicates in pending queue (don't queue same message twice)
        synchronized(pendingQueue) {
            val pendingDuplicate = pendingQueue.any {
                it.phoneNumber == phoneNumber && it.message == message
            }

            if (pendingDuplicate) {
                LogUtils.d(context, TAG, "⚠️ Message already pending - suppressing duplicate queue: $phoneNumber")
                Handler(Looper.getMainLooper()).post {
                    MainActivity.showToast(context.getString(R.string.sms_already_pending))
                }
                return false
            }

            // Add to pending queue
            pendingQueue.add(PendingSMS(context, phoneNumber, message))
            LogUtils.d(context, TAG, "📨 SMS queued: $phoneNumber (queue size: ${pendingQueue.size})")
        }

        // Start processing if not already running
        startProcessing()

        return true
    }

    /**
     * Check if this message is a duplicate of recently sent messages
     */
    private fun isDuplicate(phoneNumber: String, message: String): Boolean {
        synchronized(sentMessages) {
            for (i in 0 until QUEUE_SIZE) {
                val sent = sentMessages[i] ?: continue
                if (sent.isDuplicate(phoneNumber, message, duplicateWindowMs)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Start the queue processor if not already running
     */
    private fun startProcessing() {
        if (isProcessing.compareAndSet(false, true)) {
            processingRunnable = object : Runnable {
                override fun run() {
                    processNextSms()

                    // Schedule next check if queue not empty
                    synchronized(pendingQueue) {
                        if (pendingQueue.isNotEmpty()) {
                            processingHandler.postDelayed(this, 1000) // Check every second
                        } else {
                            isProcessing.set(false)
                            LogUtils.d(null, TAG, "🛑 SMS queue processor stopped")
                        }
                    }
                }
            }

            LogUtils.d(null, TAG, "▶️ SMS queue processor started")
            processingHandler.post(processingRunnable!!)
        }
    }

    /**
     * Process the next SMS in the queue
     */
    private fun processNextSms() {
        val pending: PendingSMS?

        synchronized(pendingQueue) {
            pending = pendingQueue.poll()
        }

        pending?.let { sms ->
            LogUtils.d(sms.context, TAG, "📤 Processing queued SMS to: ${sms.phoneNumber}")

            // Actually send the SMS
            SMSSender.sendSmsDirect(sms.context, sms.phoneNumber, sms.message)

            // Add to sent messages ring buffer
            addToSentMessages(sms.phoneNumber, sms.message)
        }
    }

    /**
     * Add a sent message to the circular buffer
     */
    private fun addToSentMessages(phoneNumber: String, message: String) {
        synchronized(sentMessages) {
            sentMessages[sentIndex] = SentSMS(phoneNumber, message, System.currentTimeMillis())
            sentIndex = (sentIndex + 1) % QUEUE_SIZE
        }
    }

    /**
     * Get current queue size
     */
    fun getQueueSize(): Int {
        synchronized(pendingQueue) {
            return pendingQueue.size
        }
    }

    /**
     * Clear all pending messages
     */
    fun clearPendingQueue() {
        synchronized(pendingQueue) {
            pendingQueue.clear()
            LogUtils.d(null, TAG, "🧹 Pending queue cleared")
        }
    }

    /**
     * Set duplicate detection window in seconds
     */
    fun setDuplicateWindowSeconds(seconds: Int) {
        duplicateWindowMs = seconds * 1000L
        LogUtils.d(null, TAG, "⏱️ Duplicate window set to $seconds seconds")
    }
}