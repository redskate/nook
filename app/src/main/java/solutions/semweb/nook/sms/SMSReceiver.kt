package solutions.semweb.nook.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.chat.ChatManager
import solutions.semweb.nook.crypto.BaseXXXUtils
import solutions.semweb.nook.crypto.CryptoManager
import solutions.semweb.nook.crypto.EncryptionMapper
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SMSReceiver with queue processing - handles bursts without dropping messages
 * NOTE: Android is here fully responsible of delivering correctly reassembled long SMS!
 * If a carrier generates an SMS burst, this receiver will process one at a time
 * and delay the processing according with decryption time.
 */
class SMSReceiver : BroadcastReceiver() {

    companion object {
        private val processingQueue = ConcurrentLinkedQueue<SmsMessageData>()
        private val isProcessing = AtomicBoolean(false)
        private val queueSize = AtomicInteger(0)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Statistics for logging
        private var totalProcessed = 0
        private var totalQueued = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val message = extractSmsMessage(context, intent) ?: return

        // Always add to queue - never drop messages
        queueSize.incrementAndGet()
        totalQueued++
        processingQueue.offer(message)

        LogUtils.d(context, Constants.SMSTAG,
            "📨 Queued SMS from ${message.sender} (Queue size: ${queueSize.get()}, Total queued: $totalQueued)")

        // Start processing if not already running
        startQueueProcessing(context)
    }

    private fun startQueueProcessing(context: Context) {
        if (isProcessing.compareAndSet(false, true)) {
            scope.launch {
                processQueue(context)
            }
        }
    }

    private suspend fun processQueue(context: Context) {
        LogUtils.d(context, Constants.SMSTAG, "🔄 Queue processor started")

        while (true) {
            // Get next message from queue
            val message = processingQueue.poll() ?: break

            try {
                // Process the message (this may be slow due to decryption)
                processSingleMessage(context, message)

                // Update statistics
                queueSize.decrementAndGet()
                totalProcessed++

                LogUtils.d(context, Constants.SMSTAG,
                    "✅ Processed SMS from ${message.sender} (Queue remaining: ${queueSize.get()}, Total processed: $totalProcessed)")

                // Small delay between messages to prevent overwhelming the system
                // but still process quickly
                delay(50) // 50ms between messages

            } catch (e: Exception) {
                LogUtils.e(context, Constants.SMSTAG, "❌ Error processing queued SMS", e)
                queueSize.decrementAndGet()
            }
        }

        // Queue is empty, stop processing
        isProcessing.set(false)
        LogUtils.d(context, Constants.SMSTAG, "🔄 Queue processor stopped")
    }

    private fun extractSmsMessage(context: Context, intent: Intent): SmsMessageData? {
        val pdus = intent.extras?.get("pdus") as? Array<*> ?: return null
        val format = intent.extras?.getString("format")

        LogUtils.d(context, Constants.SMSTAG, "📡 Extracting from ${pdus.size} PDU(s)")

        val messages = mutableListOf<SmsMessage>()

        for (pdu in pdus) {
            if (pdu is ByteArray) {
                try {
                    val smsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        SmsMessage.createFromPdu(pdu, format)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsMessage.createFromPdu(pdu)
                    }
                    messages.add(smsMessage)
                } catch (e: Exception) {
                    LogUtils.e(context, Constants.SMSTAG, "❌ Error extracting PDU", e)
                }
            }
        }

        if (messages.isEmpty()) return null

        // Combine all parts (Android already reassembles, but we combine to be safe)
        val completeBody = messages.joinToString("") { it.messageBody ?: "" }
        val firstMessage = messages.first()

        return SmsMessageData(
            sender = firstMessage.originatingAddress ?: "unknown",
            body = completeBody,
            timestamp = firstMessage.timestampMillis,
            contextRef = context // Hold weak reference? No, but we pass context separately
        )
    }

    /**
     * When an SMS is received
     * 1. check for sender in trusted contacts
     * 2. if useAllContacts toggle is ON, check sender is in contacts
     * 3. if should process - add message in conversation
     * 4. if conversation not exists, create it.
     */
    private suspend fun processSingleMessage(
        context: Context,
        message: SmsMessageData
    ) {
        withContext(Dispatchers.IO) { // Move heavy operations to IO thread
            val (sender, body, timestamp) = message

            LogUtils.d(context, Constants.SMSTAG, "📨 Processing message from $sender")

            val prefs = SharedPreferencesManager.getInstance(context)
            val chatManager = ChatManager(context)

            // Check if this sender is allowed
            val shouldProcess = if (prefs.useAllContacts) {
                isInContacts(context, sender)
            } else {
                isTrustedNumber(context, sender, prefs.getActiveTrustedNumbers())
            }

            if (!shouldProcess) {
                LogUtils.d(context, Constants.SMSTAG,
                    "⛔ Ignoring untrusted sender: $sender")
                return@withContext
            }

            // Get sender name for display
            val senderName = getContactName(context, sender)

            // Check if conversation exists, if not create it
            var conversation = chatManager.getConversation(sender)
            if (conversation == null) {
                LogUtils.d(context, Constants.SMSTAG,
                    "🆕 Creating new conversation for allowed sender: $sender")

                chatManager.createNormalChat(
                    phoneNumber = sender,
                    contactName = senderName,
                    encoding = Constants.DEFAULT_encoding
                )

                // Get the newly created conversation
                conversation = chatManager.getConversation(sender)

                if (conversation == null) {
                    LogUtils.e(context, Constants.SMSTAG,
                        "❌ Failed to create conversation for $sender")
                    return@withContext
                }
            }

            // Process the message
            if (body.startsWith(Constants.SMS_OBF_PREFIX)) {
                // Encrypted message - may take time to decrypt
                processEncryptedMessage(context, message, conversation, senderName)
            } else {
                // Plaintext message - fast path
                chatManager.handleIncomingMessage(
                    sender = sender,
                    messageText = body,
                    timestamp = timestamp,
                    transTimestamp = -1,
                    isDecoded = false,
                    senderName = senderName,
                    usedScheme = EncryptionMapper.ENCRYPTION_TEXT,
                    multiPartSize = 1
                )
                LogUtils.d(context, Constants.SMSTAG, "✅ Saved plaintext message from $sender")
            }
        }
    }

    private fun isInContacts(context: Context, phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(uri,
                arrayOf(ContactsContract.Contacts._ID),
                null, null, null)?.use { cursor ->
                cursor.count > 0
            } ?: false
        } catch (e: Exception) {
            LogUtils.e(context, Constants.SMSTAG, "Error checking contacts", e)
            false
        }
    }

    private suspend fun processEncryptedMessage(
        context: Context,
        message: SmsMessageData,
        conversation: ChatConversation,
        senderName: String?
    ) {
        val (sender, body, timestamp) = message
        val chatManager = ChatManager(context)

        // Extract timestamp (fast)
        val (cleanMessage, transTimestamp) = extractTimestampFromPrefix(body, conversation.encoding)

        // Decryption (may be slow - run in IO context)
        val result = withContext(Dispatchers.IO) {
            CryptoManager.decodeMessage(
                context,
                cleanMessage,
                conversation.encryptionScheme,
                conversation.encoding,
                conversation.encodingPassword,
                sender,
                transTimestamp ?: timestamp
            )
        }

        if (result.success) {
            chatManager.handleIncomingMessage(
                sender = sender,
                messageText = result.decoded,
                timestamp = timestamp,
                transTimestamp = transTimestamp ?: -1,
                isDecoded = true,
                senderName = senderName,
                usedScheme = conversation.encryptionScheme,
                usedEncoding = conversation.encoding,
                multiPartSize = 1
            )
            LogUtils.d(context, Constants.SMSTAG, "✅ Decrypted and saved message from $sender")
        } else {
            LogUtils.w(context, Constants.SMSTAG, "❌ Decryption failed for message from $sender")
        }
    }

    private fun isTrustedNumber(context: Context, number: String, trustedNumbers: Set<String>): Boolean {
        if (number.isBlank()) return false
        val normalized = PhoneUtils.normalizePhoneNumber(number)
        return trustedNumbers.any {
            PhoneUtils.normalizePhoneNumber(it) == normalized
        }
    }

    private fun getContactName(context: Context, phoneNumber: String): String? = try {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        context.contentResolver.query(uri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else null
        }
    } catch (e: Exception) {
        LogUtils.e(context, Constants.SMSTAG, "Error getting contact name", e)
        null
    }

    private fun extractTimestampFromPrefix(encodedMessage: String, usedEncoding: String): Pair<String, Long?> {
        if (!encodedMessage.startsWith(Constants.SMS_OBF_PREFIX)) {
            return Pair(encodedMessage, null)
        }

        val encodingBase = EncryptionMapper.extractEncodingBase(usedEncoding)
        val timestampWidth = BaseXXXUtils.SecondTimestamp.getTimestampWidthForSeconds(encodingBase)

        val isEncrypted = encodedMessage.length > 2 &&
                encodedMessage.substring(2).startsWith(EncryptionMapper.SISA_ENCR_PREFIX)
        val prefixLength = if (isEncrypted) {
            Constants.SMS_OBF_PREFIX.length + EncryptionMapper.SISA_ENCR_PREFIX.length
        } else {
            Constants.SMS_OBF_PREFIX.length
        }

        if (encodedMessage.length < prefixLength + timestampWidth + 1) {
            return Pair(encodedMessage, null)
        }

        val afterPrefix = encodedMessage.substring(prefixLength)
        val timestampPart = afterPrefix.take(timestampWidth)

        return try {
            val timestamp = BaseXXXUtils.SecondTimestamp.decodeToLong(timestampPart, timestampWidth, encodingBase)
            val remainingMessage = encodedMessage.substring(0, prefixLength) +
                    afterPrefix.substring(timestampWidth + 1)
            Pair(remainingMessage, timestamp)
        } catch (e: Exception) {
            LogUtils.d(null, Constants.SMSTAG, "⚠️ Timestamp extraction failed: ${e.message}")
            Pair(encodedMessage, null)
        }
    }

    /**
     * Clean up when receiver is destroyed
     */
    fun shutdown() {
        scope.cancel()
    }

    data class SmsMessageData(
        val sender: String,
        val body: String,
        val timestamp: Long,
        val contextRef: Context
        // No context stored here - we pass it separately
    )
}