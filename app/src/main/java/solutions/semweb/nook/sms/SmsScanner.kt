package solutions.semweb.nook.sms

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.Constants
import solutions.semweb.nook.Constants.MULTIPART_DELAY
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MultipartInfo
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.chat.ChatManager
import solutions.semweb.nook.crypto.CryptoManager
import solutions.semweb.nook.crypto.EncryptionMapper
import solutions.semweb.nook.data.database.DatabaseActor
import java.util.Collections
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Scanner to recover SMS not received and possibly to decrypt
 * Same methods as in SMSReceiver.kt
 * NB: To scan and import an SMS and decrypt it properly
 * the user understands that the Encoding/Encrypting
 * parameters in the same chat should not have changed,
 * otherwise no successful decryption/decoding might happen.
 *
 * NB: Multipart SMS are scrambled by carrier-specific quirks and Android quirks;
 * they need to be read only after they are reassembled in Android database
 * so, on multipart SMS messages, we wait 1 minute and then delegate rescanning in order
 * to let Android do the work which will fit 100% for all androids OS and carriers.
 */

class SmsScanner(private val context: Context) {

    private val prefs = SharedPreferencesManager.getInstance(context)
    private val chatManager = ChatManager(context)

    companion object {
        private val scanningLock = Any()
        private val isScanning = AtomicBoolean(false)
    }

    data class ScanResult(
        var processed: Int = 0,
        var decrypted: Int = 0,
        var plaintext: Int = 0,
        var errors: Int = 0,
        var alreadyExist: Int = 0,
        var skippedUntrusted: Int = 0,
        var multipartRecovered: Int = 0,
        var totalScanned: Int = 0,
        var error: String? = null,
        var existingMessages: List<ChatMessage> = emptyList()
    )

    private fun querySmsSince(sinceTime: Long): Cursor? {
        return try {
            val contentResolver: ContentResolver = context.contentResolver
            val uri = Telephony.Sms.CONTENT_URI

            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.SEEN,
                Telephony.Sms.READ
            )

            // Just SMS received after the specified dates for the trusted contacts.
            val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} = 1"
            val selectionArgs = arrayOf(sinceTime.toString())
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)

        } catch (e: SecurityException) {
            LogUtils.e(context, "SmsScanner", "❌ SMS permissions insufficient", e)
            null
        } catch (e: Exception) {
            LogUtils.e(context, "SmsScanner", "❌ Error SMS query", e)
            null
        }
    }

    private fun extractSmsFromCursor(cursor: Cursor): SmsData {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
        val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
        val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
        val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
        val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
        val seen = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.SEEN)) == 1
        val read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1

        return SmsData(id, address, body, date, type, seen, read)
    }


    private fun markSmsAsProcessed(smsId: Long) {
        val processedIds = prefs.getStringSet("processed_sms_ids", emptySet()).toMutableSet()
        processedIds.add(smsId.toString())
        prefs.putStringSet("processed_sms_ids", processedIds)
    }

    private fun isTrustedNumber(number: String, trustedNumbers: Set<String>): Boolean {
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(number)
        return trustedNumbers.any { trusted ->
            val normalizedTrusted = PhoneUtils.normalizePhoneNumber(trusted)
            normalizedNumber == normalizedTrusted ||
                    normalizedNumber.endsWith(normalizedTrusted) ||
                    normalizedTrusted.endsWith(normalizedNumber)
        }
    }

    /**
     * Check if sender should be processed (same logic as SMSReceiver)
     */
    private fun shouldProcessSender(sender: String): Boolean {
        return prefs.useAllContacts ||
                isTrustedNumber(sender, prefs.getActiveTrustedNumbers())
    }

    fun scanMissingSmsForContact(
        phoneNumber: String,
        hoursBack: Int = 24
    ): ScanResult {
        LogUtils.d(context, Constants.SCANNER_TAG,
            "🔍 Scanning SMS for: $phoneNumber (last $hoursBack hours)")

        synchronized(scanningLock) {
            if (isScanning.get()) {
                return ScanResult(error = "Scan already in progress")
            }

            isScanning.set(true)
            val result = ScanResult()

            try {
                val cutoffTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L)
                val cursor = querySmsSince(cutoffTime)
                val existingMessages = getMessagesInTimeRange(phoneNumber, cutoffTime, System.currentTimeMillis())
                result.existingMessages = existingMessages

                LogUtils.d(context, Constants.SCANNER_TAG,
                    "📊 Messages already in DB in range: ${existingMessages.size}")

                val smsList = Collections.synchronizedList(mutableListOf<SmsData>())

                cursor?.use { c ->
                    while (c.moveToNext()) {
                        try {
                            smsList.add(extractSmsFromCursor(c))
                        } catch (e: Exception) {
                            result.errors++
                        }
                    }
                }

                val sortedSmsList = smsList.sortedBy { it.date }

                for (sms in sortedSmsList) {
                    try {
                        if (isSmsFromTargetContact(sms, phoneNumber)) {
                            result.totalScanned++ // track # of results from target
                            val processed = processSmsLikeReceiver(
                                sms,
                                scanTimeWindow = hoursBack,
                                result,
                                existingMessages
                            )
                        }
                    } catch (e: Exception) {
                        result.errors++
                        LogUtils.e(context, Constants.SCANNER_TAG, "Error processing SMS", e)
                    }
                }

            } catch (e: Exception) {
                result.error = e.message ?: "Unknown error"
                LogUtils.e(context, Constants.SCANNER_TAG, "❌ Error scanning SMS", e)
            } finally {
                isScanning.set(false)
            }

            return result
        }
    }

    /**
     * Check whether SMS is from target contact
     */
    private fun isSmsFromTargetContact(sms: SmsData, targetPhoneNumber: String): Boolean {
        val normalizedSmsNumber = PhoneUtils.normalizePhoneNumber(sms.address)
        val normalizedTargetNumber = PhoneUtils.normalizePhoneNumber(targetPhoneNumber)

        return normalizedSmsNumber == normalizedTargetNumber ||
                normalizedSmsNumber.endsWith(normalizedTargetNumber) ||
                normalizedTargetNumber.endsWith(normalizedSmsNumber)
    }

    private fun processSmsLikeReceiver(
        sms: SmsData,
        scanTimeWindow: Int,
        result: ScanResult, //structured passed for every sms
        existingMessages: List<ChatMessage>
    ): Boolean {
        var dummyDeleted = false

        try {
            // Check if we should process this sender AT ALL
            if (!shouldProcessSender(sms.address)) {
                LogUtils.d(context, "SmsScanner", "⛔ IGNORING SMS from untrusted sender: ${sms.address}")
                result.skippedUntrusted++
                markSmsAsProcessed(sms.id)
                return false
            }

            val conversation = chatManager.getConversation(sms.address)

            if (conversation != null) {
                // Check if this SMS is already in database (but skip dummy/progress messages)
                val smsIsProcessed = isSmsAlreadyProcessed(sms, existingMessages)

                if (!smsIsProcessed)
                {
                    LogUtils.d(context, "SmsScanner", "📨 Processing SMS from: ${sms.address}")
                    LogUtils.d(context, "SmsScanner", "  Text: '${sms.body.take(50)}...'")
                    LogUtils.d(context, "SmsScanner", "  Date SMS: ${sms.date} (${Date(sms.date)})")

                    if (!CryptoManager.isLikelyEncrypted(sms.body)) {
                        LogUtils.d(context, "SmsScanner", "  📝 SMS PLAINTEXT")

                        val isPlaintext = !sms.body.trim().startsWith(EncryptionMapper.techSign+"e") && // e.g. #e
                                !CryptoManager.hasEncryptionIndicators(sms.body)

                        LogUtils.d(context, "SmsScanner", "  📝 IsPlaintext: $isPlaintext")

                        // Save unencr/unenc message
                        chatManager.handleIncomingMessage(
                            messageText0 = sms.body,
                            isDecoded = false,
                            conversation = conversation,
                            transTimestamp = -1,
                            timestamp = sms.date,
                            usedScheme = if (isPlaintext) EncryptionMapper.ENCRYPTION_TEXT else "",
                            usedEncodingPassword = conversation.encodingPassword,
                        )

                        result.plaintext++
                        LogUtils.d(context, "SmsScanner", "  ✅ Saved SMS plaintext (timestamp: ${sms.date})")

                    } else {
                        // Encrypted SMS
                        LogUtils.d(context, "SmsScanner", "  🔐 Encrypted/Encoded SMS")

                        val conversation = chatManager.getConversation(sms.address)

                        // CRITICAL: If no conversation exists for encrypted message, skip
                        if (conversation == null) {
                            LogUtils.w(context, "SmsScanner",
                                "⚠️ No conversation found for encrypted SMS from ${sms.address} - skipping")
                            result.skippedUntrusted++
                            return false
                        }

                        val schemeToUse = conversation.encryptionScheme
                        val encodingToUse = conversation.encoding
                        val encodingPassword = conversation.encodingPassword

                        // Extract timestamp
                        val extractionResult = TimestampUtils.extractTimestampFromPrefix(sms.body, encodingToUse)
                        val message = extractionResult.first
                        val transmTimestamp = extractionResult.second

                        val timestamp = transmTimestamp ?: 0

                        val resultDecode = CryptoManager.decodeMessage(
                            context,
                            message,
                            schemeToUse,
                            encodingToUse,
                            encodingPassword,
                            sms.address,
                            timestamp
                        )

                        if (resultDecode.success) {
                            LogUtils.d(context, "SmsScanner", "  ✅ DECRYPTION SUCCESS ($schemeToUse)")

                            // Save in SharedPreferences
                            prefs.saveDecodedMessage(
                                SharedPreferencesManager.DecodedMessage(
                                    decodedMessage = resultDecode.decoded,
                                    sender = sms.address,
                                    timestamp = sms.date,
                                    trans_timestamp = -1,
                                    success = true,
                                    senderName = conversation.contactName?: conversation.phoneNumber
                                )
                            )

                            val partCount = 1

                            // Add the real message WITH the part count in the prefix
                            chatManager.handleIncomingMessage(
                                messageText0 = resultDecode.decoded,
                                conversation = conversation,
                                transTimestamp = -1,
                                timestamp = sms.date,
                                usedScheme = schemeToUse,
                                usedEncoding = encodingToUse,
                                usedEncodingPassword = conversation.encodingPassword,
                                multiPartSize = partCount,
                            )

                            result.decrypted++
                            LogUtils.d(context, "SmsScanner", "  ✅ Decrypted SMS saved with part count: $partCount")

                            // Force UI update
                            runBlocking {
                                delay(200) // Give time for database operations
                                sendChatUpdateBroadcast(sms.address, true)
                            }

                        } else {
                            LogUtils.w(context, "SmsScanner", "  ❌ Decryption failed for SMS ${sms.id}")
                            result.errors++
                        }
                    }

                    // Mark Sms as processed
                    markSmsAsProcessed(sms.id)
                    result.processed++

                    LogUtils.d(context, "SmsScanner",
                        "✅ SMS processed ${sms.id} from ${sms.address} with timestamp: ${sms.date}")
                }
            }
        } catch (e: Exception) {
            LogUtils.e(context, "SmsScanner", "❌ Error processing SMS ${sms.id}", e)
            result.errors++
        }

        return dummyDeleted
    }

    // Add this to SmsScanner.kt or create a separate debug helper class
    private suspend fun dumpLastNMessages(sender: String, n: Int = 5) {
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(context, "🔍 SmsScanner", "=".repeat(100))
                LogUtils.d(context, "🔍 SmsScanner", "DUMPING LAST $n MESSAGES FOR: $sender")
                LogUtils.d(context, "🔍 SmsScanner", "=".repeat(100))

                // Get database actor
                val databaseActor = DatabaseActor.getInstance(context)

                // First, find the conversation to get its ID
                val conversation = databaseActor.getChatConversation(sender)

                if (conversation == null) {
                    LogUtils.d(context, "🔍 SmsScanner", "❌ No conversation found for: $sender")
                    return@withContext
                }

                LogUtils.d(context, "🔍 SmsScanner", "📱 Conversation ID: ${conversation.id} (Type: ${conversation.id?.javaClass?.simpleName})")
                LogUtils.d(context, "🔍 SmsScanner", "📱 Phone: ${conversation.phoneNumber}")
                LogUtils.d(context, "🔍 SmsScanner", "📱 Contact: ${conversation.contactName}")
                LogUtils.d(context, "🔍 SmsScanner", "📱 Encryption: ${conversation.encryptionScheme}")
                LogUtils.d(context, "🔍 SmsScanner", "📱 Encoding: ${conversation.encoding}")
                LogUtils.d(context, "🔍 SmsScanner", "📱 Unread: ${conversation.unreadCount}")

                // Get all messages for this conversation
                val allMessages = databaseActor.getAllMessagesForConversation(sender)

                LogUtils.d(context, "🔍 DEBUG", "📊 Total messages in conversation: ${allMessages.size}")

                // Get the last N messages (by timestamp)
                val lastMessages = allMessages.sortedByDescending { it.timestamp }.take(n)

                if (lastMessages.isEmpty()) {
                    LogUtils.d(context, "🔍 SmsScanner", "❌ No messages found")
                    return@withContext
                }

                LogUtils.d(context, "🔍 SmsScanner", "\n📋 LAST ${lastMessages.size} MESSAGES:")

                lastMessages.forEachIndexed { index, msg ->
                    LogUtils.d(context, "🔍 SmsScanner", "\n" + "-".repeat(80))
                    LogUtils.d(context, "🔍 SmsScanner", "MESSAGE #${index + 1} - Position: ${if (index == 0) "MOST RECENT" else "${index} messages old"}")
                    LogUtils.d(context, "🔍 SmsScanner", "-".repeat(80))

                    // Basic message info
                    LogUtils.d(context, "🔍 SmsScanner", "ID: ${msg.id} (Type: ${msg.id?.javaClass?.simpleName})")
                    LogUtils.d(context, "🔍 SmsScanner", "Timestamp: ${msg.timestamp} (${java.util.Date(msg.timestamp)})")
                    LogUtils.d(context, "🔍 SmsScanner", "Sender: '${msg.sender}'")
                    LogUtils.d(context, "🔍 SmsScanner", "SenderName: '${msg.senderName}'")
                    LogUtils.d(context, "🔍 SmsScanner", "Text: '${msg.text}'")

                    // Boolean flags
                    LogUtils.d(context, "🔍 SmsScanner", "isSystemMessage: ${msg.isSystemMessage}")
                    LogUtils.d(context, "🔍 SmsScanner", "isReplaced: ${msg.isReplaced}")
                    LogUtils.d(context, "🔍 SmsScanner", "isDecoded: ${msg.isDecoded}")
                    LogUtils.d(context, "🔍 SmsScanner", "isOutgoing: ${msg.isOutgoing}")
                    LogUtils.d(context, "🔍 SmsScanner", "isSent: ${msg.isSent}")
                    LogUtils.d(context, "🔍 SmsScanner", "isYMessage: ${msg.isYMessage}")

                    // Metadata (most important for dummy messages)
                    LogUtils.d(context, "🔍 SmsScanner", "\n📦 METADATA:")
                    if (msg.metadata != null) {
                        msg.metadata.forEach { (key, value) ->
                            LogUtils.d(context, "🔍 SmsScanner", "  $key: '$value'")
                        }
                    } else {
                        LogUtils.d(context, "🔍 SmsScanner", "  No metadata")
                    }

                    // Check if this looks like a dummy message
                    val isDummy = msg.isSystemMessage &&
                            (msg.metadata?.get("type") == "multipart_progress" ||
                                    msg.text.contains("multipart", ignoreCase = true) ||
                                    msg.text.contains("Receiving", ignoreCase = true) ||
                                    msg.text.contains("part", ignoreCase = true))

                    if (isDummy) {
                        LogUtils.d(context, "🔍 SmsScanner", "\n⚠️ THIS APPEARS TO BE A DUMMY MESSAGE!")

                        // Extract dummy ID if present
                        val dummyId = msg.metadata?.get("dummy_id")
                        if (dummyId != null) {
                            LogUtils.d(context, "🔍 SmsScanner", "   dummy_id: '$dummyId'")
                        }

                        // Check part count
                        val partCount = msg.metadata?.get("part_count") ?: msg.metadata?.get("expected_parts")
                        if (partCount != null) {
                            LogUtils.d(context, "🔍 SmsScanner", "   part_count: $partCount")
                        }
                    }

                    // For comparison, also dump the raw message if available
                    LogUtils.d(context, "🔍 SmsScanner", "\n📄 RAW MESSAGE DATA:")
                    LogUtils.d(context, "🔍 SmsScanner", "  toString(): ${msg}")
                }

                // Summary statistics
                LogUtils.d(context, "🔍 SmsScanner", "\n" + "=".repeat(80))
                LogUtils.d(context, "🔍 SmsScanner", "📊 SUMMARY STATISTICS:")

                val systemMessages = lastMessages.count { it.isSystemMessage }
                val dummyMessages = lastMessages.count {
                    it.isSystemMessage && it.metadata?.get("type") == "multipart_progress"
                }
                val replacedMessages = lastMessages.count { it.isReplaced }

                LogUtils.d(context, "🔍 SmsScanner", "  System messages: $systemMessages")
                LogUtils.d(context, "🔍 SmsScanner", "  Dummy/progress messages: $dummyMessages")
                LogUtils.d(context, "🔍 SmsScanner", "  Replaced messages: $replacedMessages")
                LogUtils.d(context, "🔍 SmsScanner", "=".repeat(80))

            } catch (e: Exception) {
                LogUtils.e(context, "🔍 SmsScanner", "❌ Error dumping messages", e)
            }
        }
    }

    //
    private suspend fun deleteDummyMessageById(messageId: Long, sender: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(context, "SmsScanner", "🗑️ Attempting to delete dummy message by ID: $messageId")

                // DUMP THE LAST 5 MESSAGES BEFORE DELETION ATTEMPT
                // dumpLastNMessages(sender, 3) // useful for debug

                // FIRST - Check all messages for this conversation
                val chatManager = ChatManager(context)
                val allMessages = chatManager.getAllMessagesForConversation(sender)

                LogUtils.d(context, "SmsScanner", "📋 All messages in conversation ($sender): ${allMessages.size}")

                // Look for the dummy message
                val dummyMessage = allMessages.find { msg ->
                    msg.id == messageId ||
                            (msg.isSystemMessage && msg.metadata?.get("type") == "multipart_progress")
                }

                if (dummyMessage == null) {
                    LogUtils.w(context, "SmsScanner", "⚠️ No dummy message found in conversation!")
                    // List all system messages to see what's there
                    allMessages.filter { it.isSystemMessage }.forEach { msg ->
                        LogUtils.d(context, "SmsScanner", "  System msg: ID=${msg.id}, type=${msg.metadata?.get("type")}")
                    }
                    return@withContext false
                }

                LogUtils.d(context, "SmsScanner", "✅ Found dummy message: ID=${dummyMessage.id}, text='${dummyMessage.text}'")

                // Now try to delete
                val databaseActor = DatabaseActor.getInstance(context)
                val deleted = databaseActor.deleteMessageById(dummyMessage.id) // Use the ID we found

                if (deleted) {
                    LogUtils.d(context, "SmsScanner", "✅ Dummy message deleted successfully")
                } else {
                    LogUtils.e(context, "SmsScanner", "❌ Failed to delete dummy message")
                }

                deleted
            } catch (e: Exception) {
                LogUtils.e(context, "SmsScanner", "❌ Error", e)
                false
            }
        }
    }


    /**
     * Helper function to delete dummy message and ensure UI updates
     */
    private suspend fun deleteDummyMessageAndUpdate(dummyId: String, sender: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(context, "SmsScanner", "🗑️ Attempting to delete dummy message: $dummyId")

                val databaseActor = DatabaseActor.getInstance(context)

                // Method 1: Try to find and delete by metadata
                var deleted = databaseActor.deleteDummyMessageByMetadata("dummy_id", dummyId)

                if (!deleted) {
                    // Method 2: Try to find by ID if stored elsewhere
                    LogUtils.d(context, "SmsScanner", "🔄 Trying alternative deletion method")

                    // Get all messages for this sender and look for the dummy
                    val messages = chatManager.getAllMessagesForConversation(sender)
                    val dummyMessage = messages.find { msg ->
                        msg.metadata?.get("dummy_id") == dummyId ||
                                msg.metadata?.get("type") == "multipart_progress"
                    }

                    if (dummyMessage != null && dummyMessage.id != null && dummyMessage.id!! > 0) {
                        deleted = databaseActor.deleteMessageById(dummyMessage.id!!)
                        LogUtils.d(context, "SmsScanner",
                            "✅ Found and deleted dummy message by ID: ${dummyMessage.id}")
                    }
                }

                if (deleted) {
                    // Force a small delay to ensure database operation is complete
                    delay(100)

                    // Send broadcast to update UI
                    withContext(Dispatchers.Main) {
                        sendChatUpdateBroadcast(sender)
                    }

                    LogUtils.d(context, "SmsScanner", "✅ Dummy message successfully deleted")
                } else {
                    LogUtils.w(context, "SmsScanner", "⚠️ Could not delete dummy message: $dummyId")
                }

                deleted

            } catch (e: Exception) {
                LogUtils.e(context, "SmsScanner", "❌ Error in deleteDummyMessageAndUpdate", e)
                false
            }
        }
    }

    /**
     * Enhanced sendChatUpdateBroadcast with force refresh
     */
    private fun sendChatUpdateBroadcast(sender: String, forceRefresh: Boolean = true) {
        try {
            val updateIntent = Intent("${Constants.mainpackage}.CHAT_UPDATED").apply {
                putExtra("sender", sender)
                putExtra("force_refresh", forceRefresh)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPackage(context.packageName)
                }
            }
            context.sendBroadcast(updateIntent)
            LogUtils.d(context, "SmsScanner", "📡 Broadcast sent for: $sender (forceRefresh=$forceRefresh)")
        } catch (e: Exception) {
            LogUtils.e(context, "SmsScanner", "❌ Error send broadcast", e)
        }
    }

    /**
     * Check if this SMS matches any stored multipart info
     */
    private fun checkForMultipartInfo(sms: SmsData, timestamp: Long): MultipartInfo? {
        return try {
            val allMultipartInfo = prefs.getAllMultipartInfo()

            // Look for info matching this sender and close timestamp
            val matching = allMultipartInfo.find { info ->
                info.sender == sms.address &&
                        abs(info.firstTimestamp - timestamp) < MULTIPART_DELAY // get close to timestamp inside delay
            }

            if (matching != null) {
                LogUtils.d(context, "SmsScanner",
                    "📦 Found matching multipart info: ${matching.partCount} parts, dummyId=${matching.dummyId}")
            }

            matching
        } catch (e: Exception) {
            LogUtils.e(context, "SmsScanner", "❌ Error checking multipart info", e)
            null
        }
    }

    /**
     * Check whether SMS message text already occurs in existingMessages
     */
    private fun isSmsAlreadyProcessed(sms: SmsData, existingMessages: List<ChatMessage>): Boolean {
        return try {
            val timeWindowMillis = 10 * 1000L // ±10 seconds
            val startTime = sms.date - timeWindowMillis
            val endTime = sms.date

            LogUtils.d(context, "SmsScanner",
                "🔍 Checking for duplicates SMS: ${sms.id}, timestamp: ${sms.date}")

            val normalizedSmsBody = sms.body.trim()

            val existing = existingMessages.find { message ->
                // Skip dummy/progress messages when checking for duplicates
                // Check using metadata instead of ID prefix
                if (message.isSystemMessage &&
                    message.metadata?.get("type") == "multipart_progress") {
                    LogUtils.d(context, "SmsScanner",
                        "  Skipping dummy/progress message: ${message.id}")
                    return@find false
                }

                // 1. Check timestamp (within ±10 seconds)
                val timeDiff = abs(message.timestamp - sms.date)
                val isSimilarTime = timeDiff < 10000

                if (isSimilarTime) {
                    LogUtils.d(context, "SmsScanner",
                        "  Found message with similar timestamp: ${message.id}, diff=${timeDiff}ms")
                    return@find true
                }

                // 2. Check content
                val messageText = message.text
                val normalizedMessageText = messageText.trim()

                // For progress messages, also check if they contain the multipart prefix
                val isMultipartProgressMessage = messageText.contains("multipart") ||
                        messageText.contains("Receiving") ||
                        messageText.contains("part")

                if (isMultipartProgressMessage) {
                    return@find false
                }

                // Exact match
                val isExactMatch = normalizedMessageText == normalizedSmsBody

                // Contains match
                val isContainedMatch = normalizedSmsBody.contains(normalizedMessageText) ||
                        normalizedMessageText.contains(normalizedSmsBody)

                // For long messages, check first 50 chars
                val isPartialMatch = if (normalizedSmsBody.length > 50 && normalizedMessageText.length > 50) {
                    val smsStart = normalizedSmsBody.substring(0, 50)
                    val msgStart = normalizedMessageText.substring(0, 50)
                    smsStart == msgStart
                } else {
                    false
                }

                val isMatch = isExactMatch || isContainedMatch || isPartialMatch

                if (isMatch) {
                    LogUtils.d(context, "SmsScanner",
                        "  Found matching content: ${message.id}")
                }

                isMatch
            }

            val exists = existing != null
            if (exists) {
                LogUtils.d(context, "SmsScanner",
                    "📋 Duplicate found: ID ${existing?.id}, " +
                            "timestamp ${existing?.timestamp}, " +
                            "text: '${existing?.text?.take(30)}...'")
            } else {
                LogUtils.d(context, "SmsScanner", "  ✅ No duplicate found")
            }

            exists

        } catch (e: Exception) {
            LogUtils.e(context, "SmsScanner", "❌ Error checking database for SMS ${sms.id}", e)
            false
        }
    }

    /**
     * Get messages in range
     */
    private fun getMessagesInTimeRange(phoneNumber: String, startTime: Long, endTime: Long): List<ChatMessage> {
        return try {
            val chatManager = ChatManager(context)

            val messagesInRange = chatManager.getDBMessagesInTimeRange(phoneNumber, startTime, endTime)

            LogUtils.d(context, "SmsScanner",
                "📅 Query range ${Date(startTime)}-${Date(endTime)}: ${messagesInRange.size} messages")

            messagesInRange

        } catch (e: Exception) {
            LogUtils.e(context, "SmsScanner", "❌ Errore getting messages in range", e)
            emptyList()
        }
    }

    /**
     * Get contact name
     */
    private fun getContactName(context: Context, phoneNumber: String): String? = try {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME)

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        }
    } catch (e: Exception) {
        LogUtils.e(context, "SmsScanner", "Error search contact name", e)
        null
    }

    /**
     * Send broadcast to update UI
     */
    private fun sendChatUpdateBroadcast(sender: String) {
        try {
            val updateIntent = Intent("${Constants.mainpackage}.CHAT_UPDATED").apply {
                putExtra("sender", sender)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPackage(context.packageName)
                }
            }
            context.sendBroadcast(updateIntent)
            LogUtils.d(context, "SmsScanner", "📡 Broadcast sent for: $sender")
        } catch (e: Exception) {
            LogUtils.e(context, "SmsScanner", "❌ Error send broadcast", e)
        }
    }

    /**
     * Extension per SharedPreferences
     */
    private fun SharedPreferencesManager.getStringSet(key: String, default: Set<String>): Set<String> {
        return prefs.getStringSet(key, default) ?: default
    }

    private fun SharedPreferencesManager.putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }

    /**
     * SMS data
     */
    data class SmsData(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,
        val seen: Boolean,
        val read: Boolean
    )
}