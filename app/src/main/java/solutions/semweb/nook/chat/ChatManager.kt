package solutions.semweb.nook.chat


import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.crypto.AppCryptoManager
import solutions.semweb.nook.crypto.BaseXXXUtils
import solutions.semweb.nook.crypto.CryptoManager
import solutions.semweb.nook.crypto.DecryptionFailureMonitor
import solutions.semweb.nook.crypto.EncryptionMapper
import solutions.semweb.nook.crypto.EncryptionVerifier
import solutions.semweb.nook.data.database.ChatMessageEntity
import solutions.semweb.nook.data.database.DatabaseActor
import solutions.semweb.nook.data.database.DatabaseManager
import solutions.semweb.nook.sms.SMSSender
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ChatManager(val context: Context) {

    private val databaseActor by lazy {
        LogUtils.d(context, "ChatManager", "🔍 LAZY creation DatabaseActor")
        DatabaseActor.getInstance(context)
    }

    private val messageLock = Any()
    private val isHandlingIncoming = AtomicBoolean(false)

    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs = solutions.semweb.nook.SharedPreferencesManager.getInstance(context)

    private val chatEncryptionSchemes = mutableMapOf<String, String>()

    private var lastBroadcastTime = 0L
    private val BROADCAST_COOLDOWN = 500L // 0.5 secondi

    private val tempIdToRealIdMap = mutableMapOf<Long, Long>()
    private val mapLock = Any()

    /**
     * Elimina un messaggio dal database
     */
    fun deleteMessage(messageId: Long): Boolean {
        LogUtils.d(context, "ChatManager", "🗑️ Deleting message ID: $messageId")

        return try {
            val success = runBlocking {
                databaseActor.deleteMessageById(messageId)
            }

            if (success) {
                // 🔥 Broadcast mirato con ID Long
                val intent = Intent("${Constants.mainpackage}.MESSAGE_DELETED")
                intent.putExtra("message_id", messageId)
                context.sendBroadcast(intent)

                LogUtils.d(context, "ChatManager", "✅ Message $messageId deleted")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error deleting message", e)
            false
        }
    }


    /**
     * Add a message to a conversation and return the real database ID
     * @return The real database ID, or -1 if failed
     */
    fun addMessageInChat(message: ChatMessage, conversation: ChatConversation, saveConversation: Boolean = true): Long {
        synchronized(messageLock) {
            try {
                LogUtils.d(context, "ChatManager", "📝 Adding message to chat: ${conversation.phoneNumber}")
                LogUtils.d(context, "ChatManager", "   Message temp ID: ${message.id}")
                LogUtils.d(context, "ChatManager", "   Message text: ${message.text.take(30)}...")

                val shouldIncrementUnread = when {
                    message.isOutgoing -> false
                    else -> true
                }

                val newUnreadCount = if (shouldIncrementUnread) {
                    conversation.unreadCount + 1
                } else {
                    conversation.unreadCount
                }

                val updatedConversation = conversation.copy(
                    lastMessage = message.text,
                    lastTimestamp = maxOf(conversation.lastTimestamp, message.timestamp),
                    unreadCount = newUnreadCount
                )

                if (saveConversation) {
                    runBlocking {
                        databaseActor.saveChatConversation(updatedConversation)
                    }
                }

                // Capture the generated ID from the database
                val generatedId = runBlocking {
                    databaseActor.addMessageToConversation(conversation.phoneNumber, message)
                }

                if (generatedId > 0) {
                    LogUtils.d(context, "ChatManager",
                        "✅ [SYNC] Message saved in database with ID: $generatedId (temp ID: ${message.id})")

                    // Store the mapping between temporary ID and real ID
                    storeIdMapping(message.id, generatedId)

                    LogUtils.d(context, "ChatManager",
                        "  Chat: ${conversation.phoneNumber}")
                    LogUtils.d(context, "ChatManager",
                        "  New unreadCount: ${updatedConversation.unreadCount}")

                    // Send broadcast to update UI
                    sendChatUpdateBroadcast()

                    return generatedId  // Return the real database ID
                } else {
                    LogUtils.e(context, "ChatManager", "❌ [SYNC] Database message save failed")
                    return -1L
                }
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ [SYNC] Error saving message", e)
                return -1L
            }
        }
    }

    fun getOlderMessagesByConversationId(conversationId: Long, limit: Int, offset: Int): List<ChatMessage> {
        return try {
            LogUtils.d(context, "ChatManager",
                "📜 getOlderMessagesByConversationId: ID=$conversationId, limit=$limit, offset=$offset")

            // ⚡ DIRETTAMENTE AL DATABASE CON L'ID
            val messages = runBlocking {
                databaseActor.getOlderMessagesByConversationId(conversationId, limit, offset)
            }

            LogUtils.d(context, "ChatManager",
                "✅ Older messages: ${messages.size} for conversation ID: $conversationId")

            messages

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error getOlderMessagesByConversationId", e)
            emptyList()
        }
    }

    fun getLatestMessagesByConversationId(conversationId: Long, limit: Int): List<ChatMessage> {
        return try {
            LogUtils.d(context, "ChatManager",
                "📥 getLatestMessagesByConversationId: ID=$conversationId, limit=$limit")

            val messages = runBlocking {
                databaseActor.getLatestMessagesByConversationId(conversationId, limit)
            }

            LogUtils.d(context, "ChatManager",
                "✅ Lastmessages: ${messages.size} for conversation ID: $conversationId")

            messages

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error getLatestMessagesByConversationId", e)
            emptyList()
        }
    }

    fun getTotalMessageCountByConversationId(conversationId: Long): Int {
        return try {
            runBlocking {
                databaseActor.countMessagesForConversationId(conversationId)
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error getTotalMessageCountByConversationId", e)
            0
        }
    }

    fun resetUnreadCount(phoneNumber: String) {
        executor.execute {
            try {
                LogUtils.d(context, "ChatManager", "🔄 resetUnreadCount for: $phoneNumber")

                // Usa il nuovo metodo del DatabaseActor
                val success = runBlocking {
                    databaseActor.resetUnreadCount(phoneNumber)
                }

                if (success) {
                    LogUtils.d(context, "ChatManager", "✅ UnreadCount reset")

                    val now = System.currentTimeMillis()
                    if (now - lastBroadcastTime > BROADCAST_COOLDOWN) {
                        sendChatUpdateBroadcast()
                    }
                } else {
                    LogUtils.d(context, "ChatManager", "⚠️ Reset unread failed, no conversation found")
                }

            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error resetUnreadCount", e)
            }
        }
    }


    private fun createNewConversation(phoneNumber: String, message: ChatMessage): ChatConversation {
        val messagesList = mutableListOf<ChatMessage>()

        when (Constants.MSG_SEQ) {
            1 -> {
                messagesList.add(message)
            }
            else -> {
                messagesList.add(message)
            }

        }

        val conversation = ChatConversation(
            id = 0, // room! - always 0 the new record
            phoneNumber = phoneNumber,
            contactName = getContactNameFromPhone(phoneNumber),
            lastMessage = message.text,
            lastTimestamp = message.timestamp,
            messages = messagesList,
            unreadCount = if (!message.isOutgoing && !message.isYMessage) 1 else 0,
            isYChat = message.isYMessage,
            encryptionScheme = ""
        )

        try {
            runBlocking {
                databaseActor.saveChatConversation(conversation)
            }
            LogUtils.d(context, "ChatManager", "  ✅ Created and saved new chat for: $phoneNumber")
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error saving new conversation", e)
        }

        return conversation
    }

    private fun sendChatUpdateBroadcast() {
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastBroadcastTime

        LogUtils.d(context, "ChatManager", "📡 sendChatUpdateBroadcast - timeSinceLast=$timeSinceLast ms, cooldown=$BROADCAST_COOLDOWN ms")

        if (timeSinceLast < BROADCAST_COOLDOWN) {
            LogUtils.d(context, "ChatManager", "⏱️ Broadcast SKIPPED - too soon (${timeSinceLast}ms < ${BROADCAST_COOLDOWN}ms)")
            return // Too early, skip
        }

        lastBroadcastTime = now
        runOnUiThreadSafe {
            try {
                val intent = Intent(Constants.mainpackage+".CHAT_UPDATED")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
                LogUtils.d(context, "ChatManager", "📡 Broadcast SENT successfully")
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error sending broadcast", e)
            }
        }
    }

    private fun runOnUiThreadSafe(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            try {
                action()
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error in UI thread", e)
            }
        }
    }

    fun getDBMessagesInTimeRange(phoneNumber: String, startTime: Long, endTime: Long): List<ChatMessage> {
        LogUtils.d(context, "ChatManager", "📅 Getting messages in range: ${Date(startTime)} - ${Date(endTime)}")

        return try {
            runBlocking {
                databaseActor.getMessagesInTimeRange(phoneNumber, startTime, endTime)
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error query messages in range", e)
            emptyList()
        }
    }

    fun encEncodeMessage(context: Context, text: String, scheme: String, encoding: String, encodingPassword: String, phoneNumber: String): String {
        LogUtils.d(context, "ChatManager",
            "🔐 Encrypting/coding message with schema: $scheme per: $phoneNumber")

        LogUtils.d(context, "ChatManager",
            "📊 Encoding used for chat: $encoding (default: ${Constants.DEFAULT_encoding})")

        val encryptedMessage = CryptoManager.encryptEncodeMessage(
            context,
            text,
            scheme,
            encoding,
            encodingPassword,
            phoneNumber
        )

        LogUtils.d(context, "ChatManager",
            "📤 Encrypted message: '${encryptedMessage.take(30)}...' (${encryptedMessage.length} chars)")

        return if (encryptedMessage.startsWith(Constants.SMS_OBF_PREFIX)) {
            val withTimestamp = encodeTimestampInPrefix(scheme, encryptedMessage, EncryptionMapper.extractEncodingBase(encoding))
            LogUtils.d(context, "ChatManager",
                "⏱️ With timestamp: '${withTimestamp.take(30)}...'")
            withTimestamp
        } else {
            LogUtils.d(context, "ChatManager",
                "⚠️ No timestamp added (do not begin with ${Constants.SMS_OBF_PREFIX})")
            encryptedMessage
        }
    }

    fun encodeOlnyMessage(context: Context, text: String, encoding: String, encodingPassword: String, phoneNumber: String): String {
        LogUtils.d(context, "ChatManager",
            "🔐 Coding message with: $encoding per: $phoneNumber")

        val base = EncryptionMapper.extractEncodingBase(encoding)
        val encodedMessage = BaseXXXUtils.encode(text.toByteArray(), base, encodingPassword)
        LogUtils.d(context, "ChatManager",
            "📤 Message encoded $encoding: '${encodedMessage.take(30)}...' (${encodedMessage.length} chars)")

        val withTimestamp = encodeTimestampInPrefix(EncryptionMapper.ENCRYPTION_SCHEME_TEXT, encodedMessage, base)
        LogUtils.d(context, "ChatManager",
            "⏱️Withn timestamp: '${withTimestamp.take(30)}...'")
        return withTimestamp
    }

    private fun encodeTimestampInPrefix(scheme: String, encryptedMessage: String, encodingBase: Int = 32): String {

        val timestampditransmissione = System.currentTimeMillis()
        val timestampWidth = BaseXXXUtils.SecondTimestamp.getTimestampWidthForSeconds(encodingBase)
        val decisecondTimestamp = BaseXXXUtils.SecondTimestamp.encodeLong(timestampditransmissione, timestampWidth, encodingBase)

        if ( scheme==EncryptionMapper.ENCRYPTION_SISA) {
            val scheme4msg = Constants.SMS_OBF_PREFIX + EncryptionMapper.SISA_ENCR_PREFIX
            val encodedMessageWithoutCode = encryptedMessage.substring(4) // skip first 4 encoding chars
            return "$scheme4msg$decisecondTimestamp${EncryptionMapper.techSign}$encodedMessageWithoutCode"
        }
        else {
            // Only encoding
            val encodedMessageWithoutCode = encryptedMessage
            val scheme4msg = Constants.SMS_OBF_PREFIX
            // Construct message with right prefix: #e<timestamp>#...
            return "$scheme4msg$decisecondTimestamp${EncryptionMapper.techSign}$encodedMessageWithoutCode"
        }
    }

    /**
     * Build the display text for a message (without any encryption indicator prefix)
     * @return The clean message text without indicators
     */
    fun buildDisplayText(
        schemeAbbr: String?,
        schemeToUse: String?,
        shortEncoding: String,
        hasEncodingPassword: Boolean,
        text: String,
        encoding: String,
        multiPartSize: Int = 1
    ): String {
        // Just return the original text - no prefix
        return text
    }

    /**
     * Build the encryption/encoding indicator to show in the UI
     * Returns something like "[sisa@b256p]" or "[sisa@b32]" or "[3-part]" or "[@b256]"
     * New simplified expressions: "s@b3p" or "s@b1p" or "@b1" or "@b1p"
     */
    fun buildEncryptionIndicator(
        schemeAbbr: String?,
        schemeToUse: String?,
        shortEncoding: String,
        hasEncodingPassword: Boolean,
        encoding: String,
        multiPartSize: Int = 1
    ): String {
        val multipartindicator = if (multiPartSize > 1) ":$multiPartSize" else ""
        val sEncoding = if (hasEncodingPassword) shortEncoding + 'p' else shortEncoding

        return when {
            // Has encryption scheme (not plain text)
            schemeAbbr?.isNotEmpty() == true && schemeToUse != EncryptionMapper.ENCRYPTION_TEXT -> {
                "$schemeAbbr@$sEncoding$multipartindicator"
            }
            // Has encoding but no encryption
            encoding.isNotEmpty() && encoding != EncryptionMapper.ENCRYPTION_SCHEME_TEXT -> {
                "@$sEncoding$multipartindicator"
            }
            // Only multipart indicator
            multiPartSize > 1 -> {
                "$multipartindicator"
            }
            // No encryption, no encoding, no multipart
            else -> {
                ""
            }
        }
    }


    fun createNormalChat(phoneNumber: String, contactName: String?, encoding: String = Constants.DEFAULT_encoding) {
        try {
            // Crea la conversazione
            val conversation = ChatConversation(
                phoneNumber = phoneNumber,
                contactName = contactName,
                lastMessage = "",
                lastTimestamp = System.currentTimeMillis(),
                unreadCount = 0,
                isYChat = false,
                encryptionScheme = "",
                encoding = encoding
            )

            runBlocking {
                databaseActor.saveChatConversation(conversation)
            }

            prefs.updateChatName(phoneNumber, contactName)

            LogUtils.d(null,"ChatManager", "✅ Chat created: $phoneNumber")
        } catch (e: Exception) {
            LogUtils.e(null,"ChatManager", "❌ Error creating chat", e)
        }
    }

    /**
     * Handle incoming message with full receipt support
     */
    fun handleIncomingMessage(
        messageText0: String,
        isDecoded: Boolean = true,
        conversation: ChatConversation,
        transTimestamp: Long, // transmission timestamp coming from NooK sender, not SMS might match
        timestamp: Long,
        usedScheme: String = "",
        usedEncoding: String = "",
        usedEncodingPassword: String,
        multiPartSize: Int = 1,
        decryptionNotes: String = ""
    ): Boolean {

        // Prevent concurrent handling of incoming messages
        if (isHandlingIncoming.get()) {
            LogUtils.w(context, "ChatManager", "⚠️ Already handling incoming message, queueing...")
            // Small delay and retry logic could be added here
            Thread.sleep(100)
        }

        var messageText = messageText0

        synchronized(isHandlingIncoming) {
            try {

                val senderName = conversation.contactName ?: conversation.phoneNumber
                isHandlingIncoming.set(true)

                LogUtils.d(context, "ChatManager", "📱 SMS coming from: $senderName")
                LogUtils.d(context, "ChatManager", "  Text: '${messageText0.take(50)}...'")
                LogUtils.d(context, "ChatManager", "  isDecoded: $isDecoded")

                /////// not decoded - send a DEFAULT replay with nok ad mid -1 (last one)
                if (!isDecoded // but should
                    && (usedScheme == EncryptionMapper.ENCRYPTION_SISA || usedEncoding != "")) {

                    runBlocking {
                        // Use the original chat ID and message ID from the receipt request
                        sendDecryptionReceipt(
                            targetMessageId = -1, // target for receipt = last one
                            receivedMessage = null,
                            localConversation = conversation,
                            decryptionNotes = decryptionNotes
                        )
                    }
                    //Correct message since not readable
                    messageText = context.getString(R.string.MessageNotDecrypted)
                }


                // DEFENSIVE CHECK: Verify this sender should have a chat
                val prefs = SharedPreferencesManager.getInstance(context)
                val shouldHaveChat = prefs.useAllContacts ||
                        isTrustedNumber(context, conversation.phoneNumber, prefs.getActiveTrustedNumbers())

                if (!shouldHaveChat) {
                    LogUtils.w(context, "ChatManager",
                        "⚠️ Attempted to create chat for untrusted sender: ${conversation.phoneNumber} - BLOCKED")
                    return false
                }

                LogUtils.d(context, "ChatManager",
                    "📊 Encoding: chat=$usedEncoding, message=$usedEncoding, usando=$usedEncoding")

                val isDefaultChatEncConfig = detectDefaultChatEncConfiguration(conversation)
                val isDecoded = isDecoded && (isDefaultChatEncConfig || conversation.encryptionScheme == EncryptionMapper.ENCRYPTION_SCHEME_SISA)

                val isPlaintextReceived = !isDecoded

                val schemeAbbr = EncryptionMapper.extractShortForEncrScheme(usedScheme)
                val shortEncoding = EncryptionMapper.extractShortForEncoding(usedEncoding)
                val hasEncodingPassword = usedEncodingPassword.isNotEmpty()

                val encryptionIndicator = buildEncryptionIndicator(
                    schemeAbbr,
                    usedScheme,
                    shortEncoding,
                    hasEncodingPassword,
                    usedEncoding,
                    multiPartSize
                )

                val metadata = mutableMapOf<String, String>()
                if (encryptionIndicator.isNotEmpty()) {
                    metadata["e_ind"] = encryptionIndicator
                }


                // useful only for the default case:
                val message = ChatMessage(
                    conversationId = conversation.id,
                    text = messageText,
                    sender = conversation.phoneNumber,
                    senderName = senderName,
                    timestamp = timestamp,
                    trans_timestamp = transTimestamp,
                    isDecoded = isDecoded,
                    isOutgoing = false,
                    metadata = metadata
                )

                if (isDecoded) {
                    ////////////////////////////////////////////////////////
                    // Check if this is just a receipt response (highest priority)
                    ////////////////////////////////////////////////////////
                    if (isReceiptResponse(messageText)) {
                        LogUtils.d(context, "ChatManager", "📋 Received receipt message")

                        val receiptInfo = parseReceiptResponse(messageText0)
                        if (receiptInfo != null) {
                            val (originalMessageId, decrTimestampStr, response) = receiptInfo
                            val decrTimestamp = decrTimestampStr.toLongOrNull() ?: timestamp

                            LogUtils.d(context, "ChatManager",
                                "📋 Processing receipt: message=$originalMessageId, decrTimestamp=$decrTimestamp, response=$response")

                            // Process the receipt - this will update the original message with receipt metadata
                            updateMessageWithReceiptStatus(originalMessageId, response, decrTimestamp)

                            // Don't create a system message - we'll just update the original message
                            LogUtils.d(context, "ChatManager", "✅ Receipt processed successfully for message $originalMessageId")
                        } else {
                            LogUtils.w(context, "ChatManager", "⚠️ Malformed receipt message, ignoring")
                        }

                        // Return true even if malformed - we don't want to show receipt messages in chat
                        return true
                    }

                    ////////////////////////////////////////////////////////
                    // Check if this message is requesting a receipt
                    ////////////////////////////////////////////////////////
                    else if (isReceiptRequested(messageText)) {
                        LogUtils.d(context, "ChatManager", "📋 Message requests decryption receipt")

                        // MODIFICATION: Parse the receipt request to get chat ID and message ID
                        val receiptRequestInfo = parseReceiptRequest(messageText0)
                        val transmittingMessageId = receiptRequestInfo?.first ?: conversation.id

                        // Remove the receipt request marker from display text
                        message.text = if (receiptRequestInfo != null) {
                            messageText0.take(messageText0.length - receiptRequestInfo.second.length - 1) // remove marker+params
                        } else {
                            messageText0.dropLast(1) // just remove marker
                        }

                        // Store receipt request info in metadata
                        val metadata = message.metadata?.toMutableMap() ?: mutableMapOf()
                        metadata["rr"] = "true" // requested receipt
                        val updatedMessage = message.copy(metadata = metadata)
                        val databaseManager = DatabaseManager.getInstance(context)

                        // DUMP VOR
                        // databaseManager.dumpLastMessages(context, 2)

                        // Add the received message
                        addMessageInChat(updatedMessage, conversation)

                        // Thread.sleep(2000)
                        // DUMP AFTER
                        // databaseManager.dumpLastMessages(context, 2)

                        if (transmittingMessageId > 0) {
                            // Create a copy of the message with the correct ID
                            val transittedMessageId = updatedMessage.copy(id = transmittingMessageId)

                            // Then send receipt (successful decryption) with the correct IDs
                            runBlocking {
                                // Use the original chat ID and message ID from the receipt request
                                sendDecryptionReceipt(
                                    targetMessageId = transmittingMessageId, // target for receipt
                                    receivedMessage = updatedMessage, // to store inside that we sent a receipt
                                    decryptionNotes = decryptionNotes,
                                    localConversation = conversation
                                )
                            }
                        }

                        return true
                    }
                }
                ////////////////////////////////////////////////////////
                // Regular message (no receipt stuff)
                ////////////////////////////////////////////////////////

                // Just add the message normally
                // in any case: eat possible present request for receipt if not processed
                message.text = cleanupRequestForRequestIfPresent(message.text)

                addMessageInChat(message, conversation)

                LogUtils.d(context, "ChatManager",
                    "✅ Message saved: plaintext=$isPlaintextReceived, " +
                            "isDecoded=${message.isDecoded}, " +
                            "encoding=$usedEncoding, " +
                            "text='${message.text.take(30)}...'")

                return true


            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error in handleIncomingMessage", e)
                return false
            } finally {
                isHandlingIncoming.set(false)
            }
        }
        return true
    }

    private fun cleanupRequestForRequestIfPresent(messageText: String):String {
        var text = messageText
        if (messageText.contains(EncryptionMapper.RECEIPT_REQUEST_MARKER))
            text = messageText.substringBefore(EncryptionMapper.RECEIPT_REQUEST_MARKER)
        return text
    }

    private fun parseReceiptRequest(messageText: String): Pair<Long,String>? {
        try {
            val marker = EncryptionMapper.RECEIPT_REQUEST_MARKER
            val markerIndex = messageText.lastIndexOf(marker)

            if (markerIndex == -1) return null

            val paramsPart = messageText.substring(markerIndex + marker.length)

            // Parse just the message ID (no chat-id anymore)
            val messageId = paramsPart.toLongOrNull() ?: return null

            return Pair(messageId, paramsPart)
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "Error parsing receipt request", e)
            return null
        }
    }

    /**
     * Update a message with receipt status when we receive a receipt response
     */
    private fun updateMessageWithReceiptStatus(
        originalMessageId: Long,
        response: String,
        receiptTimestamp: Long
    ) {
        try {
            LogUtils.d(context, "ChatManager",
                "📨 Processing receipt for message $originalMessageId: $response at $receiptTimestamp")

            val databaseManager = DatabaseManager.getInstance(context)

            // DUMP BEFORE
            // databaseManager.dumpLastMessages(context, 2)

            val messageEntity = if (originalMessageId == -1L) {
                databaseManager.database.chatMessageDao().findLast()
            } else {
                databaseManager.database.chatMessageDao().findById(originalMessageId)
            }
            if (messageEntity == null) {
                LogUtils.e(context, "ChatManager", "❌ Message NOT FOUND in DB for ID: $originalMessageId")
                return
            }

            LogUtils.d(context, "ChatManager", "✅ Found message in DB: ${messageEntity.id}")

            // Create metadata
            val message = messageEntity.toDomain(context)
            val existingMetadata = message.metadata?.toMutableMap() ?: mutableMapOf()
            existingMetadata["rr"] = "true"
            existingMetadata["rres"] = response
            existingMetadata["rrt"] = receiptTimestamp.toString()
            existingMetadata["type"] = "receipt"  // Add type for metadata_type column

            // Convert metadata to JSON and encrypt
            val metadataJson = com.google.gson.Gson().toJson(existingMetadata)
            val encryptedMetadataJson = EncryptionVerifier.encryptAndVerify(
                metadataJson, "metadataJson", "ChatMessage", context,
                conversationId = messageEntity.conversationId
            )

            // DIRECT SQL UPDATE VIA DAO METHOD
            runBlocking {
                try {
                    val rowsAffected = databaseManager.database.chatMessageDao().updateMetadataDirectly(
                        messageId = originalMessageId,
                        metadataJson = encryptedMetadataJson,
                        metadataType = "receipt",
                        updatedAt = System.currentTimeMillis()
                    )

                    LogUtils.d(context, "ChatManager", "📊 Direct update result: $rowsAffected rows affected")

                    if (rowsAffected > 0) {
                        LogUtils.d(context, "ChatManager", "✅ Message $originalMessageId updated via direct query")
                    } else {
                        LogUtils.w(context, "ChatManager", "⚠️ Direct update affected 0 rows, trying raw SQL")

                        // FALLBACK: Raw SQL
                        val db = databaseManager.database.openHelper.writableDatabase
                        val sql = """
                        UPDATE chat_messages 
                        SET metadata_json = ?, 
                            metadata_type = ?,
                            updated_at = ?
                        WHERE id = ?
                    """

                        db.execSQL(sql, arrayOf(
                            encryptedMetadataJson,
                            "receipt",
                            System.currentTimeMillis(),
                            originalMessageId
                        ))

                        LogUtils.d(context, "ChatManager", "✅ Message $originalMessageId updated via raw SQL")
                    }
                } catch (e: Exception) {
                    LogUtils.e(context, "ChatManager", "❌ Error during update", e)
                }
            }

            // Wait a moment and verify
            Thread.sleep(1000)

            // Verify the update
            val updatedEntity = databaseManager.database.chatMessageDao().findById(originalMessageId)
            if (updatedEntity != null) {
                val updatedMessage = updatedEntity.toDomain(context)
                LogUtils.d(context, "ChatManager", "🔍 Verification after update:")
                LogUtils.d(context, "ChatManager", "   Metadata contains 'rr': ${updatedMessage.metadata?.get("rr")}")
                LogUtils.d(context, "ChatManager", "   Metadata contains 'rres': ${updatedMessage.metadata?.get("rres")}")
            }

            // DUMP AFTER
            // databaseManager.dumpLastMessages(context, 2)

            LogUtils.d(context, "ChatManager", "✅ Updated message $originalMessageId with receipt")
            sendChatUpdateBroadcast()

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error updating message with receipt status", e)
        }
    }


    // Helper method to check trusted numbers
    private fun isTrustedNumber(context: Context, number: String,
                                trustedNumbers: Set<String>): Boolean {
        if (number.isBlank()) return false
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(number)

        return trustedNumbers.any { trusted ->
            val normalizedTrusted = PhoneUtils.normalizePhoneNumber(trusted)
            normalizedNumber == normalizedTrusted ||
                    normalizedNumber.endsWith(normalizedTrusted) ||
                    normalizedTrusted.endsWith(normalizedNumber)
        }
    }

    fun updateChatEncryptionScheme(phoneNumber: String, encryptionScheme: String, encoding: String): Boolean {
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)

        LogUtils.d(context, "ChatManager",
            "🔄 updateChatEncryptionScheme: '$phoneNumber' -> '$encryptionScheme'")

        return try {
            val success = runBlocking {
                databaseActor.updateChatEncryptionScheme(normalizedNumber, encryptionScheme)
            }

            if (success) {
                sendChatUpdateBroadcast()
                LogUtils.d(context, "ChatManager", "✅ Encryption scheme updated and broadcast sent")
            }

            success
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error updating encryption scheme", e)
            false
        }
    }


    fun generateMessageId(): Long {
        return System.currentTimeMillis()
    }

    fun getAllMessagesForConversation(phoneNumber: String): List<ChatMessage> {
        LogUtils.w(context, "ChatManager", "⚠️ getAllMessagesForConversation - USE WITH CARE!")

        return try {
            runBlocking {
                val allMessages = databaseActor.getAllMessagesForConversation(phoneNumber)

                LogUtils.d(context, "ChatManager",
                    "📊 getAllMessagesForConversation: ${allMessages.size} messages for: $phoneNumber")

                if (BuildConfig.DEBUG && allMessages.isNotEmpty()) {
                    allMessages.take(3).forEachIndexed { index, message ->
                        LogUtils.d(context, "ChatManager",
                            "  [$index] ID: ${message.id} " +
                                    "Text: '${message.text.take(20)}...', " +
                                    "Timestamp: ${Date(message.timestamp)}")
                    }
                }

                allMessages.sortedBy { it.timestamp }
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Critical error in getAllMessagesForConversation", e)
            emptyList()
        }
    }


    fun getConversation(phoneNumber: String): ChatConversation? {
        return try {
            LogUtils.d(context, "ChatManager", "🔍🔍🔐 DEBUG getConversation START")
            LogUtils.d(context, "ChatManager", "📱 Input phoneNumber: '$phoneNumber'")

            val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
            val encryptedPhoneValue = AppCryptoManager.encrypt64Value(normalizedNumber)
            val encryptedPhoneKey = AppCryptoManager.encrypt64Key(normalizedNumber)

            LogUtils.d(context, "ChatManager", "🔄 Search using hash...")

            // Try first with phone_hash
            val entityByHash = runBlocking(Dispatchers.IO) {
                DatabaseManager.getInstance(context).database.chatConversationDao()
                    .findByPhoneHash(encryptedPhoneKey)
            }

            if (entityByHash != null) {
                LogUtils.d(context, "ChatManager", "✅ Conversation found via phone_hash")
                return entityByHash.toDomain(context)
            }

            LogUtils.d(context, "ChatManager", "🔄 Hash not found, search using trusted phone_number...")
            val entityByPhone = runBlocking(Dispatchers.IO) {
                DatabaseManager.getInstance(context).database.chatConversationDao()
                    .findByPhoneNumber(encryptedPhoneValue)
            }

            if (entityByPhone != null) {
                LogUtils.d(context, "ChatManager", "✅ Conversation found via trusted phone_number")
                return entityByPhone.toDomain(context)
            }

            LogUtils.e(context, "ChatManager", "❌ Conversation not found")
            null
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "💥 CRITICAL ERROR in getConversation", e)
            null
        }
    }

    fun getAllConversations(): List<ChatConversation> {
        return try {
            runBlocking {
                databaseActor.getChatConversations()
            }
        } catch (e: Exception) {
            LogUtils.e(null,"ChatManager", "❌ Error reading conversations", e)
            emptyList()
        }
    }


    fun markAsRead(phoneNumber: String) {
        Thread {
            try {
                LogUtils.d(context, "ChatManager", "📱 markAsRead for: $phoneNumber")

                // Ottieni la conversazione
                val conversation = getConversation(phoneNumber)

                if (conversation != null) {
                    LogUtils.d(context, "ChatManager", "  Conversation found, current unreadCount: ${conversation.unreadCount}")

                    if (conversation.unreadCount > 0) {
                        // Crea copia con unreadCount = 0
                        val updatedConversation = conversation.copy(unreadCount = 0)

                        // Salva nel database
                        runBlocking {
                            databaseActor.saveChatConversation(updatedConversation)
                        }

                        LogUtils.d(context, "ChatManager", "✅ UnreadCount reset for: $phoneNumber")

                        sendChatUpdateBroadcast()
                    } else {
                        LogUtils.d(context, "ChatManager", "ℹ️ No unread message to mark")
                    }
                } else {
                    LogUtils.w(context, "ChatManager", "⚠️ Conversation not found for: $phoneNumber")
                }

            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error markAsRead", e)
            }
        }.start()
    }


    /**
     * Simulates receiving an SMS for loopback testing
     * Follows the exact same path as SMSReceiver.processEncryptedMessage()
     */
    fun simulateSmsReception(
        encodedText: String,
        conversation: ChatConversation,
        timestamp: Long
    ): Boolean {
        LogUtils.d(context, "ChatManager", "🔄 LOOPBACK: Simulating SMS reception for ${conversation.phoneNumber}")

        // simulate latency time 2 sec
        Thread.sleep(2000)

        try {
            // STEP 1: Extract timestamp from prefix
            val (messageWithoutTimestamp, transTimestamp) = extractTimestampFromPrefix(encodedText, conversation.encoding)

            // STEP 2: Decrypt the message
            val result = runBlocking(Dispatchers.IO) {
                CryptoManager.decodeMessage(
                    context,
                    messageWithoutTimestamp,
                    conversation.encryptionScheme,
                    conversation.encoding,
                    conversation.encodingPassword,
                    conversation.phoneNumber,
                    transTimestamp ?: timestamp
                )
            }

            if (result.success) {
                LogUtils.d(context, "ChatManager",
                    "  Step 2 - Decryption successful: '${result.decoded.take(50)}...'")

                // Handle/Add the decrypted message
                // DO NOT add any additional messages here
                val handled = handleIncomingMessage(
                    messageText0 = result.decoded,
                    conversation = conversation,
                    transTimestamp = transTimestamp ?: -1,
                    timestamp = timestamp,
                    usedScheme = conversation.encryptionScheme,
                    usedEncoding = conversation.encoding,
                    usedEncodingPassword = conversation.encodingPassword,
                )

                if (handled) {
                    LogUtils.d(context, "ChatManager",
                        "✅ LOOPBACK: Message processed through all levels successfully")
                }

                return handled
            } else {
                LogUtils.e(context, "ChatManager",
                    "❌ LOOPBACK: Decryption failed for message")
                return false
            }

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager",
                "❌ LOOPBACK: Error simulating SMS reception (loopback)", e)
            return false
        }
    }

    /**
     * Helper method - copies the timestamp extraction logic from SMSReceiver
     */
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
            LogUtils.d(context, "ChatManager", "⚠️ Timestamp extraction failed: ${e.message}")
            Pair(encodedMessage, null)
        }
    }

    /**
     * Send a message to a contact
     * @param context The context
     * @param conversation The conversation to send the message to
     * @param text The message text to send
     * @param messageid The temporary message ID (usually timestamp)
     * @return The ChatMessage object that was created (with real ID if available)
     */
    fun sendMessage(context: Context, conversation: ChatConversation, text: String, messageid: Long): ChatMessage {
        var messageToReturn: ChatMessage? = null

        return try {
            LogUtils.d(context, "ChatManager", "✈️ sendMessage to: ${conversation.phoneNumber}")
            LogUtils.d(context, "ChatManager", "   Text: '${text.take(30)}...'")
            LogUtils.d(context, "ChatManager", "   Temp ID: $messageid")

            // Get encryption/encoding parameters
            val scheme = conversation.encryptionScheme
            val schemeToUse = scheme.ifEmpty { getGlobalDecodingScheme() }
            val schemeAbbr = EncryptionMapper.extractShortForEncrScheme(schemeToUse)
            val encoding = conversation.encoding
            val encodingPassword = conversation.encodingPassword

            val shortEncoding = if (encoding == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) "" else
                EncryptionMapper.extractShortForEncoding(encoding)

            val hasEncodingPassword = encodingPassword.isNotEmpty()
            val multiPartSize = 1 // Default, can be adjusted for multipart messages

            // Check if this is a default encryption chat
            val isDefaultChatEncConfig = detectDefaultChatEncConfiguration(conversation)

            // For default encryption, set isDecoded = false (plaintext) even though it's encoded
            // This ensures it uses the plaintext layouts in the adapter
            val isDecoded = isDefaultChatEncConfig || conversation.encryptionScheme == EncryptionMapper.ENCRYPTION_SCHEME_SISA

            // Build encryption indicator for UI
            val encryptionIndicator = buildEncryptionIndicator(
                schemeAbbr,
                schemeToUse,
                shortEncoding,
                hasEncodingPassword,
                encoding,
                multiPartSize
            )

            // Create metadata with encryption indicator
            val metadata = mutableMapOf<String, String>()
            if (encryptionIndicator.isNotEmpty()) {
                metadata["e_ind"] = encryptionIndicator
            }

            // Create the message object with temporary ID
            val newMessage = ChatMessage(
                id = messageid, // Temporary ID
                conversationId = conversation.id,
                text = text,
                sender = conversation.phoneNumber,
                senderName = null,
                timestamp = System.currentTimeMillis(),
                isDecoded = isDecoded,
                isOutgoing = true,
                isSent = false,
                isYMessage = false,
                metadata = metadata
            )

            // Add message to database IMMEDIATELY and get the real database ID
            val realMessageId = runBlocking {
                databaseActor.addMessageToConversation(conversation.phoneNumber, newMessage)
            }

            //  broadcast to show asap "sent" local message
            sendChatUpdateBroadcast()

            if (realMessageId <= 0) {
                LogUtils.e(context, "ChatManager", "❌ Failed to save message to database")
                // Return the temporary message as fallback
                return newMessage
            }

            LogUtils.d(context, "ChatManager", "✅ Message saved to database with real ID: $realMessageId (temp ID: $messageid)")

            // Create the message with real ID for UI and return value
            messageToReturn = newMessage.copy(
                id = realMessageId,
                metadata = metadata
            )

            // Store ID mapping for potential receipt handling
            storeIdMapping(messageid, realMessageId)

            // Send SMS in background thread
            Thread {
                try {
                    val shouldRequestReceipt = prefs.getRequestReceipts()

                    // Build the text to send with receipt request if enabled
                    val textToSend = if (shouldRequestReceipt) {
                        // Format: original_text + RECEIPT_REQUEST_MARKER + realMessageId
                        "$text${EncryptionMapper.RECEIPT_REQUEST_MARKER}$realMessageId"
                    } else {
                        text
                    }

                    // Encode/encrypt the message for transmission
                    val encodedText = if (schemeToUse == EncryptionMapper.ENCRYPTION_TEXT
                        && encoding == EncryptionMapper.ENCRYPTION_TEXT) {
                        // Plain text - no encoding
                        textToSend
                    } else {
                        if (schemeToUse.isNotEmpty() && schemeToUse != EncryptionMapper.ENCRYPTION_TEXT) {
                            // Encrypt then encode
                            encEncodeMessage(context, textToSend, schemeToUse, encoding, encodingPassword, conversation.phoneNumber)
                        } else {
                            // Just encode
                            encodeOlnyMessage(context, textToSend, encoding, encodingPassword, conversation.phoneNumber)
                        }
                    }

                    // Check loopback mode for testing
                    val isLoopbackMode = prefs.isSmsLoopbackMode() && BuildConfig.DEBUG

                    if (isLoopbackMode) {
                        // LOOPBACK MODE: Simulate reception
                        LogUtils.d(context, "ChatManager", "🔁 LOOPBACK MODE: Simulating reception")

                        Thread {
                            try {
                                Thread.sleep(Constants.SMS_LOOPBACK_DELAY)

                                // Get fresh conversation
                                val updatedConversation = getConversation(conversation.phoneNumber) ?: conversation

                                // Simulate reception - this will create a NEW incoming message
                                val success = simulateSmsReception(
                                    encodedText = encodedText,
                                    conversation = updatedConversation,
                                    timestamp = System.currentTimeMillis()
                                )

                                if (success) {
                                    LogUtils.d(context, "ChatManager", "✅ LOOPBACK: Reception simulated")

                                    // Mark the ORIGINAL outgoing message as sent using the REAL database ID
                                    runBlocking {
                                        databaseActor.markMessageAsSent(realMessageId)
                                    }
                                }
                            } catch (e: Exception) {
                                LogUtils.e(context, "ChatManager", "❌ LOOPBACK error", e)
                            }
                        }.start()

                    } else {
                        // Normal mode: send SMS
                        SMSSender.sendSms(context, conversation.phoneNumber, encodedText)

                        // Update message status in database using the REAL database ID
                        runBlocking {
                            databaseActor.markMessageAsSent(realMessageId)
                        }
                    }

                    LogUtils.d(context, "ChatManager", "✅ SMS sent successfully and marked as sent in database")

                } catch (e: Exception) {
                    LogUtils.e(context, "ChatManager", "❌ Error sending SMS", e)
                }
            }.start()

            // Return the message with REAL database ID (UI will use this)
            messageToReturn ?: newMessage

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error in sendMessage", e)

            // Return fallback message
            ChatMessage(
                id = generateMessageId(),
                text = text,
                sender = conversation.phoneNumber,
                timestamp = System.currentTimeMillis(),
                isDecoded = true,
                isOutgoing = true,
                isSent = false
            )
        }
    }

    private fun detectDefaultChatEncConfiguration(conversation: ChatConversation): Boolean =
        (conversation.encryptionScheme == EncryptionMapper.ENCRYPTION_TEXT
                || conversation.encryptionScheme.isEmpty()) &&
                conversation.encoding == EncryptionMapper.ENCODING_BASE256 &&
                conversation.encodingPassword.isNullOrEmpty()

    /**
     * Overloaded method to update message status using the message object
     */
    private fun updateSmsMessageStatus(phoneNumber: String, message: ChatMessage, isSent: Boolean) {
        Thread {
            try {
                LogUtils.d(context, "ChatManager",
                    "📝 Updating message status: ID=${message.id}, isSent=$isSent")

                val conversation = getConversation(phoneNumber)
                conversation?.let { conv ->

                    // Try to find the message by ID first (if it's a real ID)
                    val messageToUpdate = if (message.id > 0 && message.id < 1000000000000) { // Real ID is usually smaller than timestamps
                        // This is likely a real database ID
                        runBlocking {
                            databaseActor.findMessageById(message.id)
                        }
                    } else {
                        // Fall back to finding by text and timestamp
                        conv.messages.lastOrNull {
                            it.isOutgoing &&
                                    it.text == message.text &&
                                    Math.abs(it.timestamp - message.timestamp) < 5000 && // Within 5 seconds
                                    !it.isYMessage
                        }
                    }

                    if (messageToUpdate != null) {
                        val updatedMessage = messageToUpdate.copy(
                            isSent = isSent,
                            timestamp = System.currentTimeMillis()
                        )

                        // Use the real ID for the update
                        val success = runBlocking {
                            // We need to update the message in the database
                            // This assumes you have a method to update a message by ID
                            databaseActor.addMessageToConversation(phoneNumber, updatedMessage) > 0
                        }

                        if (success) {
                            LogUtils.d(context, "ChatManager",
                                "✅ SMS state updated for message ${messageToUpdate.id}: isSent=$isSent")

                            // Store the mapping if we have both temp and real IDs
                            if (message.id != messageToUpdate.id) {
                                storeIdMapping(message.id, messageToUpdate.id)
                            }

                            sendChatUpdateBroadcast()
                        } else {
                            LogUtils.e(context, "ChatManager", "❌ Failed to update message status")
                        }
                    } else {
                        LogUtils.w(context, "ChatManager",
                            "⚠️ Message not found for status update. ID: ${message.id}, Text: ${message.text.take(20)}...")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error updating SMS state", e)
            }
        }.start()
    }


    /**
     * Original method for backward compatibility
     */
    private fun updateSmsMessageStatus(phoneNumber: String, text: String, isSent: Boolean) {
        Thread {
            try {
                val conversation = getConversation(phoneNumber)
                conversation?.let { conv ->
                    val message = conv.messages.lastOrNull {
                        it.isOutgoing && it.text == text && !it.isYMessage
                    }

                    if (message != null) {
                        updateSmsMessageStatus(phoneNumber, message, isSent)
                    } else {
                        LogUtils.w(context, "ChatManager", "⚠️ SMS Message not found for state update")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error updating SMS state", e)
            }
        }.start()
    }

    /**
     * Get encryption scheme for a specific chat (with normalized number)
     */
    fun getEncryptionSchemeForChat(phoneNumber: String): String {
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
        return getAllConversations()
            .find { it.phoneNumber == normalizedNumber }
            ?.encryptionScheme ?: ""
    }


    fun setEncryptionSchemeForChat(phoneNumber: String, encryptionScheme: String) {
        runBlocking {
            try {
                val databaseActor = DatabaseActor.getInstance(context)
                databaseActor.updateChatEncryptionScheme(phoneNumber, encryptionScheme)
                LogUtils.d(context, "ChatManager", "✅ Encryption scheme saved in DB: $encryptionScheme")
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Errore saving encryption scheme", e)
            }
        }
    }

    fun setEncodingSchemeAndPasswordForChat(conversation: ChatConversation, encodingScheme: String, encodingPassword: String) {
        runBlocking {
            try {
                val databaseActor = DatabaseActor.getInstance(context)
                val success = databaseActor.updateChatEncodingScheme(conversation.phoneNumber, encodingScheme, encodingPassword)

                if (success) {
                    LogUtils.d(context, "ChatManager",
                        "✅ Encoding scheme/pw saved in DB: $encodingScheme")

                    //Update also memory values
                    conversation.let { conv ->
                        val updatedConv = conv.copy(
                            encoding = encodingScheme,
                            encodingPassword = encodingPassword
                        )
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error saving Encoding scheme/pw", e)
            }
        }
    }



    fun getContactNameFromPhone(phoneNumber: String): String? {
        return try {
            val conversation = getConversation(phoneNumber)
            if (conversation?.contactName != null && conversation.contactName != phoneNumber) {
                return conversation.contactName
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {

                    val cursor = context.contentResolver.query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                        "${android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER} = ?",
                        arrayOf(phoneNumber),
                        null
                    )

                    cursor?.use {
                        if (it.moveToFirst()) {
                            val name = it.getString(0)
                            if (!name.isNullOrEmpty()) {
                                return name
                            }
                        }
                    }
                }
            }

            null

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Errore ottenimento nome contatto", e)
            null
        }
    }

    fun saveConversations(conversations: List<ChatConversation>) {
        Thread {
            try {
                conversations.forEach { conversation ->
                    runBlocking {
                        databaseActor.saveChatConversation(conversation)
                    }
                }
                LogUtils.d(context, "ChatManager", "✅ ${conversations.size} saved conversations")
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error saving conversations", e)
            }
        }.start()
    }

    // =============================================
    // DATABASE SETTING MANAGEMENT
    // =============================================

    private fun getGlobalDecodingScheme(): String {
        return try {
            val scheme = runBlocking {
                databaseActor.getSetting("decoding_scheme", EncryptionMapper.ENCODING_BASE256)
            }
            LogUtils.d(context, "ChatManager",
                "🌍 Global scheme from DB: $scheme")
            scheme
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error reading decoding scheme", e)
            EncryptionMapper.ENCODING_BASE256
        }
    }


    fun updateChatName(phoneNumber: String, newName: String) {
        Thread {
            try {
                val conversation = getConversation(phoneNumber)
                conversation?.let {
                    val updated = it.copy(contactName = newName)
                    runBlocking {
                        databaseActor.saveChatConversation(updated)
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Errore aggiornamento nome chat", e)
            }
        }.start()
    }


    // Add these methods to your ChatManager class



    /**
     * Extract part count from multipart progress message
     */
    private fun extractPartCountFromMessage(messageText: String): Int {
        return try {
            val regex = "(\\d+)-part".toRegex()
            val matchResult = regex.find(messageText)
            matchResult?.groupValues?.get(1)?.toInt() ?: 1
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Find a message by its custom ID stored in metadata
     */
    fun findMessageById(messageId: String): ChatMessage? {
        return try {
            LogUtils.d(context, "ChatManager", "🔍 Looking for message with ID: $messageId")

            // Search through all conversations
            val allConversations = getAllConversations()

            for (conversation in allConversations) {
                val messages = getAllMessagesForConversation(conversation.phoneNumber)

                val foundMessage = messages.find { message ->
                    // Check if message has metadata with this dummy_id
                    message.metadata?.let { metadata ->
                        metadata["dummy_id"] == messageId ||
                                (metadata["type"] == "multipart_progress" &&
                                        metadata["dummy_id"] == messageId)
                    } ?: false
                }

                if (foundMessage != null) {
                    LogUtils.d(context, "ChatManager", "✅ Found message: ${foundMessage.id}")
                    return foundMessage
                }
            }

            LogUtils.d(context, "ChatManager", "❌ Message not found: $messageId")
            null

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error finding message by ID", e)
            null
        }
    }

    /**
     * Update conversation last message when adding system messages
     */
    private fun updateConversationLastMessage(phoneNumber: String, message: ChatMessage) {
        try {
            val conversation = getConversation(phoneNumber)

            if (conversation != null) {
                val updatedConversation = conversation.copy(
                    lastMessage = message.text,
                    lastTimestamp = message.timestamp
                    // Don't increment unread count for system messages
                )

                runBlocking {
                    databaseActor.saveChatConversation(updatedConversation)
                }
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error updating conversation last message", e)
        }
    }


    // In ChatManager.kt, add this method

    fun diagnoseMessageDecryption(messageId: Long) {
        Thread {
            try {
                val databaseManager = DatabaseManager.getInstance(context)
                val entity = databaseManager.database.chatMessageDao().findById(messageId)

                if (entity == null) {
                    LogUtils.e("DIAGNOSE", "❌ Message $messageId not found in database")
                    return@Thread
                }

                LogUtils.d("DIAGNOSE", "=== MESSAGE DIAGNOSTICS ===")
                LogUtils.d("DIAGNOSE", "ID: ${entity.id}")
                LogUtils.d("DIAGNOSE", "Conversation ID: ${entity.conversationId}")
                LogUtils.d("DIAGNOSE", "Encrypted text (first 50): ${entity.text.take(50)}")
                LogUtils.d("DIAGNOSE", "Encrypted text length: ${entity.text.length}")

                // Try to decrypt and see what happens
                try {
                    val decrypted = AppCryptoManager.decrypt64Value(entity.text)
                    LogUtils.d("DIAGNOSE", "✅ Decryption successful")
                    LogUtils.d("DIAGNOSE", "Decrypted (first 50): ${decrypted.take(50)}")
                    LogUtils.d("DIAGNOSE", "Is valid: ${DecryptionFailureMonitor.isValidDecryption(decrypted, "text")}")
                } catch (e: Exception) {
                    LogUtils.e("DIAGNOSE", "❌ Decryption failed", e)
                }

            } catch (e: Exception) {
                LogUtils.e("DIAGNOSE", "Diagnostic error", e)
            }
        }.start()
    }


    /**
     * Check if a message is requesting a receipt
     */
    private fun isReceiptRequested(messageText: String): Boolean {
        // Don't request receipts for receipt messages themselves (prevents loops)
        if (isReceiptResponse(messageText)) return false

        val prefs = SharedPreferencesManager.getInstance(context)

        // DEBUG: Log the actual preference value
        val requestReceipts = prefs.getRequestReceipts()
        LogUtils.d(context, "ChatManager",
            "🔍 isReceiptRequested - prefs.getRequestReceipts() = $requestReceipts")

        // Check for the marker followed by comma-separated numbers (chatId,messageId)
        val marker = EncryptionMapper.RECEIPT_REQUEST_MARKER
        val markerIndex = messageText.lastIndexOf(marker)

        if (markerIndex != -1 && markerIndex + marker.length < messageText.length) {
            val afterMarker = messageText.substring(markerIndex + marker.length)
            // Check if it looks like "456" (messageid)
            if (afterMarker.matches(Regex("\\d+"))) {
                LogUtils.d(context, "ChatManager",
                    "🔍 Found receipt marker with ID: $afterMarker, returning $requestReceipts")
                return requestReceipts
            }
        }

        // Fall back to simple marker at end for backward compatibility
        val result = messageText.endsWith(marker) && requestReceipts
        LogUtils.d(context, "ChatManager", "🔍 No marker found, returning $result")
        return result
    }

    /**
     * Check if we've already sent a receipt for this message
     */
    private suspend fun hasReceiptAlreadySent(messageId: Long): Boolean {
        return try {
            // Check in database if this message already has receipt_sent flag
            val databaseManager = DatabaseManager.getInstance(context)
            val entity = databaseManager.database.chatMessageDao().findById(messageId)

            if (entity != null) {
                val message = entity.toDomain(context)
                message.metadata?.get("receipt_sent") == "true"
            } else {
                false
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "Error checking receipt sent status", e)
            false
        }
    }



    /**
     * Check if a message is a receipt response (to avoid processing receipts as regular messages)
     */
    private fun isReceiptResponse(messageText: String): Boolean {
        return messageText.contains(EncryptionMapper.RECEIPT_RESPONSE_PREFIX)
    }

    /**
     * Parse receipt response to extract original message info
     */
    private fun parseReceiptResponse(messageText: String): Triple< Long, String, String>? {
        try {
            // Format: receiptprefix<chat-id>,<message-id>,<timestamp>,<result>
            val content = messageText.substring(EncryptionMapper.RECEIPT_RESPONSE_PREFIX.length) // skip control char
            val parts = content.split(",", limit=4)
            if (parts.size != 3) return null

            // val originalChatId = parts[0].toLongOrNull() ?: return null
            val originalMessageId = parts[0].toLongOrNull() ?: return null
            val timestamp = parts[1] // timestamp where decrypted/shown
            val result = parts[2] // "OK" or "NOK"

            return Triple(originalMessageId, timestamp, result)
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "Error parsing receipt response", e)
            return null
        }
    }

    /**
     * Get a conversation by its database ID
     */
    suspend fun getConversationById(conversationId: Long): ChatConversation? {
        return try {
            LogUtils.d(context, "ChatManager", "🔍 Getting conversation by ID: $conversationId")

            val databaseManager = DatabaseManager.getInstance(context)
            val entity = databaseManager.database.chatConversationDao()
                .findById(conversationId)

            entity?.toDomain(context)
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error getting conversation by ID", e)
            null
        }
    }


    /**
     * Send a decryption receipt back to the sender
     * USE ALWAYS DEFAULT encoding for higher compatibility
     * @param success Whether decryption was successful
     * @param targetChatId The ID of the original chat that requested the receipt
     * @param targetMessageId The ID of the original message that requested the receipt
     */
    private suspend fun sendDecryptionReceipt(
        targetMessageId: Long,
        receivedMessage: ChatMessage?,
        localConversation: ChatConversation,
        decryptionNotes: String = ""
    ) {
        try {
            val prefs = SharedPreferencesManager.getInstance(context)

            // CHECK 1: Is sending receipts enabled?
            if (!prefs.getAllowSendingReceipts()) {
                LogUtils.d(context, "ChatManager", "⏭️ Sending receipts disabled, skipping")
                return
            }

            // CHECK 2: Have we already sent a receipt for this message?
            if (hasReceiptAlreadySent(targetMessageId)) {
                LogUtils.d(context, "ChatManager", "⏭️ Receipt already sent for message $targetMessageId, skipping")
                return
            }

            // Get the original message to pass to markReceiptSent
            LogUtils.d(context, "ChatManager",
                "📨 Sending decryption receipt for message ${targetMessageId} in chat ${localConversation.id}")

            // Format: #receipt#<chat-id>,<message-id>,<timestamp>,<response>
            // response Ok if sisa set and sisa decrypted
            //val response = if (success) "OK" else "NOK"

            val response = when { localConversation.encryptionScheme == EncryptionMapper.ENCRYPTION_SISA ->
                                            { if (decryptionNotes.contains("DEFAULT")) "NOK" else "OK" }
                                else -> "NOK" }
            val timestamp = System.currentTimeMillis()
            val receiptText = "${EncryptionMapper.RECEIPT_RESPONSE_PREFIX}${targetMessageId},$timestamp,$response"

            /* // Use the same encryption/encoding as the conversation
            val encodedDecryptionReceiptText = if (conversation.encryptionScheme.isNotEmpty() &&
                conversation.encryptionScheme != EncryptionMapper.ENCRYPTION_TEXT) {
                encEncodeMessage(context, receiptText, conversation.encryptionScheme,
                    conversation.encoding, conversation.encodingPassword, conversation.phoneNumber)
            } else if (conversation.encoding != EncryptionMapper.ENCRYPTION_SCHEME_TEXT) {
                encodeOlnyMessage(context, receiptText, conversation.encoding,
                    conversation.encodingPassword, conversation.phoneNumber)
            } else {
                receiptText
            } */

            // use always DEFAULT
            val encodedDecryptionReceiptText =
                encodeOlnyMessage(context, receiptText, EncryptionMapper.ENCODING_BASE256,
                    "", localConversation.phoneNumber)


            // Send the receipt SMS
            val isLoopbackMode = prefs.isSmsLoopbackMode() && BuildConfig.DEBUG

            if (isLoopbackMode) {
                Thread {
                    try {
                        Thread.sleep(Constants.SMS_LOOPBACK_DELAY)
                        simulateSmsReception(
                            encodedText = encodedDecryptionReceiptText,
                            conversation = localConversation,
                            timestamp = System.currentTimeMillis()
                        )
                    } catch (e: Exception) {
                        LogUtils.e(context, "ChatManager", "❌ LOOPBACK: Error simulating receipt", e)
                    }
                }.start()
            } else {
                SMSSender.sendSms(context, localConversation.phoneNumber, encodedDecryptionReceiptText)
            }

            // Mark in the original message that we've sent a receipt - PASS THE MESSAGE OBJECT
            if (receivedMessage != null)
                markReceiptSent(receivedMessage, localConversation)

            LogUtils.d(context, "ChatManager", "✅ Decryption receipt sent for transmitting message ${targetMessageId}")

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error sending decryption receipt", e)
        }
    }

    /**
     * Mark in the original message that a receipt has been sent
     */
    private suspend fun markReceiptSent(message: ChatMessage, conversation: ChatConversation) {
        try {
            LogUtils.d(context, "ChatManager", "📝 Marking receipt as sent for message ${message.id}")

            // Get the existing entity
            val databaseManager = DatabaseManager.getInstance(context)

            // DUMP DB
            // databaseManager.dumpLast4Messages(context)

            val existingEntity = databaseManager.database.chatMessageDao().findById(message.id)

            if (existingEntity != null) {
                // Update metadata
                val existingMetadata = message.metadata?.toMutableMap() ?: mutableMapOf()
                existingMetadata["rs"] = "true"
                existingMetadata["rst"] = System.currentTimeMillis().toString()

                // Create updated message with same ID
                val updatedMessage = message.copy(metadata = existingMetadata)

                // Create updated entity
                val updatedEntity = ChatMessageEntity.fromDomain(updatedMessage, existingEntity.conversationId, context)

                databaseManager.database.chatMessageDao().update(updatedEntity)

                // DUMP AFTER
                // delay(1000) // small delay to ensure write completes
                // databaseManager.dumpLast4Messages(context)

                LogUtils.d(context, "ChatManager", "✅ Message ${message.id} updated with receipt sent status")
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error marking receipt as sent", e)
        }
    }



    /**
     * Store mapping between temporary ID and real database ID
     */
    private fun storeIdMapping(tempId: Long, realId: Long) {
        synchronized(mapLock) {
            tempIdToRealIdMap[tempId] = realId

            // Clean up old mappings (keep last 100 or older than 5 minutes)
            val cutoffTime = System.currentTimeMillis() - 5 * 60 * 1000
            tempIdToRealIdMap.entries.removeAll {
                it.key < cutoffTime
            }
            // Keep only last 100 entries if still too many
            if (tempIdToRealIdMap.size > 100) {
                val entriesToKeep = tempIdToRealIdMap.entries
                    .sortedByDescending { it.key }
                    .take(100)
                tempIdToRealIdMap.clear()
                entriesToKeep.forEach { (tempId, realId) ->
                    tempIdToRealIdMap[tempId] = realId
                }
            }
        }
    }

    /**
     * Get real database ID from temporary ID
     */
    fun getRealMessageId(tempId: Long): Long? {
        synchronized(mapLock) {
            return tempIdToRealIdMap[tempId]
        }
    }

    /**
     * Get temporary ID from real database ID (reverse lookup)
     */
    fun getTempMessageId(realId: Long): Long? {
        synchronized(mapLock) {
            return tempIdToRealIdMap.entries.find { it.value == realId }?.key
        }
    }

    /**
     * Clear ID mappings for a specific message
     */
    fun clearIdMapping(tempId: Long) {
        synchronized(mapLock) {
            tempIdToRealIdMap.remove(tempId)
        }
    }

    /**
     * Check if a message has a receipt and get its status
     */
    fun getMessageReceiptStatus(messageId: Long): Triple<String?, Long?, String?>? {
        return try {
            val databaseManager = DatabaseManager.getInstance(context)
            val messageEntity = databaseManager.database.chatMessageDao().findById(messageId)

            if (messageEntity != null) {
                val message = messageEntity.toDomain(context)
                val metadata = message.metadata

                // Use the same keys as in updateMessageWithReceiptStatus
                if (metadata != null && metadata["rr"] == "true") {
                    val status = metadata["rres"]
                    val timestamp = metadata["rrt"]?.toLongOrNull()
                    Triple(status, timestamp, message.text)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "Error getting receipt status", e)
            null
        }
    }



}

fun cleanupIndicator(message: String): String {
    val prologEndIndex = message.indexOf("] ")
    return if (prologEndIndex != -1) {
        message.substring(prologEndIndex + 2)
    } else {
        message // Return original if no prolog found
    }
}

fun encIndicatorWithText(
    schemeAbbr: String?,
    schemeToUse: String?,
    shortEncoding: String,
    hasEncodingPassword: Boolean,
    text: String,
    encoding: String,
    multiPartSize: Int = 1
): String {

    // show password for encoding if set
    var sEncoding = if (hasEncodingPassword) shortEncoding+'p' else shortEncoding
    val msgDisplayText =
        if (schemeAbbr?.isNotEmpty() == true && schemeToUse != EncryptionMapper.ENCRYPTION_TEXT)
            "[${schemeAbbr.take(1)}@$sEncoding] $text"
        else { // no encryption
            "[@${sEncoding}] $text"
        }
    return msgDisplayText
}

