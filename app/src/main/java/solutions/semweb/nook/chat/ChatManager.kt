package solutions.semweb.nook.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.crypto.AppCryptoManager
import solutions.semweb.nook.crypto.BaseXXXUtils
import solutions.semweb.nook.crypto.CryptoManager
import solutions.semweb.nook.crypto.EncryptionMapper
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


    fun addSystemMessageAndGetId(
        conversationId: Long,
        text: String,
        metadataType: String,
        metadata: Map<String, String>? = null
    ): Long {
        return try {
            val message = ChatMessage(
                id = System.currentTimeMillis(), // This will be IGNORED by Room!
                text = text,
                sender = "system",
                timestamp = System.currentTimeMillis(),
                isDecoded = true,
                isOutgoing = false,
                isSystemMessage = true,
                metadata = metadata ?: mapOf("type" to metadataType)
            )

            val databaseManager = DatabaseManager.getInstance(context)
            val entity = ChatMessageEntity.fromDomain(message, conversationId, context)

            // The insert returns the ACTUAL Room-generated ID!
            val actualId = databaseManager.database.chatMessageDao().insert(entity)

            LogUtils.d(context, "ChatManager",
                "📝 System message added: requested ID=${message.id}, actual DB ID=$actualId")

            actualId // Return the REAL database ID, not the one we passed in
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error adding system message", e)
            -1L
        }
    }

    /**
     * Add a system/info message to the chat and return its ID
     */
    // Make it a suspend function
    suspend fun addSystemMessageAndGetId(
        messageText: String,
        conversationId: Long,
        partCount: Int
    ): Long {
        return withContext(Dispatchers.IO) {
            try {
                val message = ChatMessage(
                    id = 2, // IGNORED by Room!
                    text = messageText,
                    sender = "system",
                    timestamp = System.currentTimeMillis(),
                    isDecoded = true,
                    isOutgoing = false,
                    isSystemMessage = true,
                    metadata = mapOf(
                        "part_count" to partCount.toString(),
                        "expected_parts" to partCount.toString(),
                        "type" to "multipart_progress"
                    )
                )

                val databaseManager = DatabaseManager.getInstance(context)
                val entity = ChatMessageEntity.fromDomain(message, conversationId, context)

                // The insert returns the ACTUAL Room-generated ID!
                val actualId = databaseManager.database.chatMessageDao().insert(entity)

                LogUtils.d(context, "ChatManager",
                    "📝 System message added: requested ID=${message.id}, actual DB ID=$actualId")

                return@withContext actualId // Return the REAL database ID
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ Error adding system message", e)
                return@withContext -1L
            }
        }
    }

    fun addMessageInChat(message: ChatMessage, conversation: ChatConversation, saveConversation: Boolean = true) {
        synchronized(messageLock) {
            try {

                val shouldIncrementUnread = when {
                    message.isOutgoing -> false
                    else -> true
                }

                val newUnreadCount = if (shouldIncrementUnread) {
                    conversation.unreadCount + 1
                } else {
                    conversation.unreadCount
                }

                // TODO: Update last stuff (only if message is last in chat)
                val updatedConversation = conversation.copy(
                    lastMessage = message.text,
                    lastTimestamp = maxOf(conversation.lastTimestamp, message.timestamp),
                    unreadCount = newUnreadCount
                )

                if (saveConversation)
                    runBlocking {
                        databaseActor.saveChatConversation(updatedConversation)
                    }

                val success = runBlocking {
                    databaseActor.addMessageToConversation(conversation.phoneNumber, message)
                }

                if (success) {
                    LogUtils.d(context, "ChatManager", "✅ [SYNC] Message saved in database")
                    LogUtils.d(context, "ChatManager", "  Chat: ${conversation.phoneNumber}")
                    LogUtils.d(context, "ChatManager", "  New unreadCount: ${updatedConversation.unreadCount}")
                    LogUtils.d(context, "ChatManager", "  Incremented unread: $shouldIncrementUnread")

                    // 6. UI - Invia broadcast per aggiornare
                    sendChatUpdateBroadcast()
                } else {
                    LogUtils.e(context, "ChatManager", "❌ [SYNC] Database message save failed")
                }
            } catch (e: Exception) {
                LogUtils.e(context, "ChatManager", "❌ [SYNC] Error saving message", e)
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
        if (now - lastBroadcastTime < BROADCAST_COOLDOWN) {
            return // Troppo presto, skip
        }

        lastBroadcastTime = now
        runOnUiThreadSafe {
            try {
                val intent = Intent(Constants.mainpackage+".CHAT_UPDATED")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
                LogUtils.d(context, "ChatManager", "📡 Broadcast sent")
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
            return "$scheme4msg$decisecondTimestamp#$encodedMessageWithoutCode"
        }
        else {
            // Only encoding
            val encodedMessageWithoutCode = encryptedMessage
            val scheme4msg = Constants.SMS_OBF_PREFIX
            // Construct message with right prefix: #e<timestamp>#...
            return "$scheme4msg$decisecondTimestamp#$encodedMessageWithoutCode"
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

    fun handleIncomingMessage(
        messageText: String,
        isDecoded: Boolean = true,
        conversation: ChatConversation,
        transTimestamp: Long, // timestamp coming from NooK, not SMS
        timestamp: Long,
        usedScheme: String = "",
        usedEncoding: String = "",
        multiPartSize: Int = 1
    ): Boolean {

        // Prevent concurrent handling of incoming messages
        if (isHandlingIncoming.get()) {
            LogUtils.w(context, "ChatManager", "⚠️ Already handling incoming message, queueing...")
            // Small delay and retry logic could be added here
            Thread.sleep(100)
        }

        synchronized(isHandlingIncoming) {
            try {

                val senderName = conversation.contactName ?: conversation.phoneNumber
                isHandlingIncoming.set(true)

                LogUtils.d(context, "ChatManager", "📱 Gestione SMS in arrivo da: $senderName")
                LogUtils.d(context, "ChatManager", "  Testo: '${messageText.take(50)}...'")
                LogUtils.d(context, "ChatManager", "  isDecoded: $isDecoded")

                // DEFENSIVE CHECK: Verify this sender should have a chat
                val prefs = SharedPreferencesManager.getInstance(context)
                val shouldHaveChat = prefs.useAllContacts ||
                        isTrustedNumber(context, conversation.phoneNumber, prefs.getActiveTrustedNumbers())

                if (!shouldHaveChat) {
                    LogUtils.w(context, "ChatManager",
                        "⚠️ Attempted to create chat for untrusted sender: ${conversation.phoneNumber} - BLOCKED")
                    return false
                }

                val encodingPassword = conversation?.encodingPassword ?: ""

                LogUtils.d(context, "ChatManager",
                    "📊 Encoding: chat=$usedEncoding, message=$usedEncoding, usando=$usedEncoding")

                val isPlaintextReceived = when {
                    usedScheme == EncryptionMapper.ENCRYPTION_TEXT && usedEncoding == EncryptionMapper.ENCRYPTION_TEXT -> true
                    !isDecoded &&
                            !messageText.trim().startsWith("#e") &&
                            !CryptoManager.hasEncryptionIndicators(messageText) -> true
                    else -> false
                }

                val schemeAbbr = EncryptionMapper.extractShortForEncrScheme(usedScheme)
                val shortEncoding = EncryptionMapper.extractShortForEncoding(usedEncoding)
                val hasEncodingPassword = encodingPassword.isNotEmpty()

                val displayText = encIndicatorWithText(
                    schemeAbbr,
                    usedScheme,
                    shortEncoding,
                    hasEncodingPassword,
                    messageText,
                    usedEncoding,
                    multiPartSize
                )

                val message = ChatMessage(
                    text = displayText,
                    sender = conversation.phoneNumber,
                    senderName = senderName,
                    timestamp = timestamp,
                    trans_timestamp = transTimestamp,
                    isDecoded = !isPlaintextReceived,
                    isOutgoing = false,
                    isYMessage = false
                )

                addMessageInChat(message, conversation)

                // Log dettagliato
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


    fun reloadConversation(phoneNumber: String): ChatConversation? {
        return try {
            // Forza la ricarica dal database
            val newConversation = runBlocking {
                databaseActor.getChatConversation(phoneNumber)
            }

            LogUtils.d(context, "ChatManager", "🔄 Conversation reloaded: ${newConversation?.phoneNumber}")
            newConversation
        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error reloading conversation", e)
            null
        }
    }

    fun sendMessage(context: Context, conversation: ChatConversation, text: String): ChatMessage {

        var messageToReturn: ChatMessage? = null

        return try {
            val pendingMessage = conversation.messages.lastOrNull {
                it.isOutgoing && it.text == text && !it.isSent
            }
            val encoding = conversation.encoding
            val encodingPassword = conversation.encodingPassword
            val hasEncodingPassword = encodingPassword.isNotEmpty()

            if (pendingMessage != null) {
                messageToReturn = pendingMessage
                LogUtils.d(context, "ChatManager", "✅ Pending message found: ${pendingMessage.id}")
            } else {
                LogUtils.w(context, "ChatManager", "⚠️ No pending message found. Create backup.")

                val scheme = conversation.encryptionScheme
                val schemeToUse = scheme.ifEmpty { getGlobalDecodingScheme() }
                val schemeAbbr = EncryptionMapper.extractShortForEncrScheme(schemeToUse)

                val short_encoding = if (encoding == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) "" else
                    EncryptionMapper.extractShortForEncoding(encoding)
                val msgDisplayText = encIndicatorWithText(
                    schemeAbbr,
                    schemeToUse,
                    short_encoding,
                    hasEncodingPassword,
                    text,
                    encoding
                )

                val fallbackMessage = ChatMessage(
                    id = generateMessageId(),
                    text = msgDisplayText,
                    sender = conversation.phoneNumber,
                    senderName = null,
                    timestamp = System.currentTimeMillis(),
                    isDecoded = true,
                    isOutgoing = true,
                    isSent = false,
                    isYMessage = false
                )

                addMessageInChat(fallbackMessage, conversation)
                messageToReturn = fallbackMessage
            }

            // Send SMS in background
            Thread {
                try {
                    val scheme = conversation.encryptionScheme

                    LogUtils.d(context, "ChatManager",
                        "✈️ Send SMS to: ${conversation.phoneNumber}  with scheme: $scheme " +
                                "(chat: $scheme, global: ${getGlobalDecodingScheme()})")

                    val encodedText = if ( scheme == EncryptionMapper.ENCRYPTION_TEXT
                        && encoding == EncryptionMapper.ENCRYPTION_TEXT)  {
                        text
                    } else {

                        if ( scheme.isNotEmpty() && scheme != EncryptionMapper.ENCRYPTION_TEXT )
                            encEncodeMessage(context, text, scheme, encoding, encodingPassword, conversation.phoneNumber)
                        else { // just encoding
                            encodeOlnyMessage(context, text, encoding, encodingPassword, conversation.phoneNumber)
                        }
                    }

                    LogUtils.d(context, "ChatManager",
                        "📤 Text encoded (${encodedText.length} chars): '${encodedText.take(30)}...'")

                    SMSSender.sendSms(context, conversation.phoneNumber, encodedText)

                    updateSmsMessageStatus(conversation.phoneNumber, text, true)
                    LogUtils.d(context, "ChatManager", "✅ SMS sent successfully with scheme: $scheme")

                } catch (e: Exception) {
                    LogUtils.e(context, "ChatManager", "❌ Error sending SMS", e)
                    updateSmsMessageStatus(conversation.phoneNumber, text, false)
                }
            }.start()

            messageToReturn

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error in sendMessage", e)
            // Fallback completo
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

    private fun updateSmsMessageStatus(phoneNumber: String, text: String, isSent: Boolean) {
        Thread {
            try {
                // Find most recent message with this outgoing text
                val conversation = getConversation(phoneNumber)
                conversation?.let { conv ->

                    val message = conv.messages.lastOrNull {
                        it.isOutgoing && it.text == text && !it.isYMessage
                    }

                    if (message != null) {
                        val updatedMessage = message.copy(
                            isSent = isSent,
                            timestamp = System.currentTimeMillis()
                        )

                        val success = runBlocking {
                            databaseActor.addMessageToConversation(phoneNumber, updatedMessage)
                        }

                        if (success) {
                            LogUtils.d(context, "ChatManager", "✅ SMS state updated: isSent=$isSent")
                            sendChatUpdateBroadcast()
                        }
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

    fun setEncodingSchemeAndPasswordForChat(phoneNumber: String, encodingScheme: String, encodingPassword: String) {
        runBlocking {
            try {
                val databaseActor = DatabaseActor.getInstance(context)
                val success = databaseActor.updateChatEncodingScheme(phoneNumber, encodingScheme, encodingPassword)

                if (success) {
                    LogUtils.d(context, "ChatManager",
                        "✅ Encoding scheme/pw saved in DB: $encodingScheme")

                    val conversation = getConversation(phoneNumber)
                    conversation?.let { conv ->
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
     * Add a system/info message to the chat
     * These messages are visually distinct and can be replaced later
     */
    fun addSystemMessage(
        sender: String,
        messageText: String,
        timestamp: Long,
        senderName: String?,
        messageId: String
    ) {
        try {
            LogUtils.d(context, "ChatManager", "📱 Adding system message: $messageId")

            // Create metadata for the system message
            val metadata = mapOf(
                "type" to "multipart_progress",
                "dummy_id" to messageId,
                "part_count" to extractPartCountFromMessage(messageText).toString()
            )

            // Convert metadata to JSON string for storage
            val metadataJson = gson.toJson(metadata)

            // Create a system message with special indicator
            val systemMessage = ChatMessage(
                id = generateMessageId(),
                text = "🔄 $messageText",  // Add a visual indicator
                sender = sender,
                senderName = senderName,
                timestamp = timestamp,
                trans_timestamp = -1,
                isDecoded = false,
                isOutgoing = false,
                isYMessage = false,
                metadata = metadata  // Store metadata for later replacement
            )

            // Add to database
            val success = runBlocking {
                databaseActor.addMessageToConversation(sender, systemMessage)
            }

            if (success) {
                LogUtils.d(context, "ChatManager", "✅ System message added: $messageId")

                // Update conversation last message
                updateConversationLastMessage(sender, systemMessage)

                // Send broadcast to update UI
                sendChatUpdateBroadcast()
            } else {
                LogUtils.e(context, "ChatManager", "❌ Failed to add system message")
            }

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error adding system message", e)
        }
    }

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
     * Mark a message as replaced (soft delete or hide)
     */
    fun markMessageAsReplaced(messageId: String) {
        try {
            LogUtils.d(context, "ChatManager", "🔄 Marking message as replaced: $messageId")

            // Find the message first
            val message = findMessageById(messageId)

            if (message != null && message.id != null && message.id!! > 0) {
                // Use the databaseActor to mark as replaced
                val success = runBlocking {
                    databaseActor.markMessageAsReplaced(message.id!!)
                }

                if (success) {
                    LogUtils.d(context, "ChatManager", "✅ Message marked as replaced")

                    // Refresh UI
                    sendChatUpdateBroadcast()
                }
            } else {
                LogUtils.w(context, "ChatManager", "⚠️ Message not found for replacement: $messageId")
            }

        } catch (e: Exception) {
            LogUtils.e(context, "ChatManager", "❌ Error marking message as replaced", e)
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

        val multipartindicator = if (multiPartSize > 1) ":$multiPartSize" else ""
        // show password for encoding if set
        val sEncoding = if (hasEncodingPassword) shortEncoding+'p' else shortEncoding
        val msgDisplayText =
            if (schemeAbbr?.isNotEmpty() == true && schemeToUse != EncryptionMapper.ENCRYPTION_TEXT)
                "[$schemeAbbr@$sEncoding$multipartindicator] $text"
            else { // no encryption
                if (encoding.isEmpty() || encoding == EncryptionMapper.ENCRYPTION_SCHEME_TEXT)
                    if (multiPartSize>1) "[$multipartindicator] $text" else text
                else // but encoding
                    "[@$sEncoding$multipartindicator] $text"
            }
        return msgDisplayText
    }

