package solutions.semweb.nook.data.database

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.TrustedContact
import solutions.semweb.nook.chat.ChatManager
import solutions.semweb.nook.crypto.AppCryptoManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actor pattern for database ops on dedicated thread
 * Keeps compatibility with existing API
 */
class DatabaseActor private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val databaseManager by lazy {
        DatabaseManager.getInstance(appContext)
    }

    // Thread dedicated to database
    private val actorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestChannel = Channel<DatabaseRequest>(Channel.UNLIMITED)
    private val isRunning = AtomicBoolean(false)
    private var isTested = AtomicBoolean(false)
    private var testResult = AtomicBoolean(false)
    private val gson = Gson()
    private val readyListeners = mutableListOf<() -> Unit>()

    // ========== SIMPLE READY FLAG ==========
    @Volatile
    var isReady = false
        private set

    // Callback for when database is ready
    var onDatabaseReady: (() -> Unit)? = null

    // ==================== REQUEST TYPES ====================
    sealed class DatabaseRequest {

        data class FindMessageByMetadata(
            val metadataKey: String,
            val metadataValue: String,
            val reply: CompletableDeferred<ChatMessage?>
        ) : DatabaseRequest()

        data class MarkMessageAsReplaced(
            val messageId: Long,
            val reply: CompletableDeferred<Boolean>
        ) : DatabaseRequest()

        data class GetSystemMessagesByType(
            val conversationId: Long,
            val metadataType: String,
            val reply: CompletableDeferred<List<ChatMessage>>
        ) : DatabaseRequest()

        data class DeleteChatConversationByContactName(
            val contactName: String,
            val reply: CompletableDeferred<Boolean>
        ) : DatabaseRequest()
        data class UpdateChatName(
            val phoneNumber: String,
            val newName: String,
            val reply: CompletableDeferred<Boolean>
        ) : DatabaseRequest()
        // Trusted Contacts
        data class GetTrustedContacts(val reply: CompletableDeferred<List<TrustedContact>>) : DatabaseRequest()
        data class SaveTrustedContact(val contact: TrustedContact, val reply: CompletableDeferred<Unit>) : DatabaseRequest()
        data class RemoveTrustedContact(val contactId: String, val reply: CompletableDeferred<Unit>) : DatabaseRequest()

        // Chat Conversations
        data class GetChatConversations(val reply: CompletableDeferred<List<ChatConversation>>) : DatabaseRequest()
        data class GetChatConversation(val phoneNumber: String, val reply: CompletableDeferred<ChatConversation?>) : DatabaseRequest()
        data class SaveChatConversation(val conversation: ChatConversation, val reply: CompletableDeferred<Unit>) : DatabaseRequest()
        data class UpdateChatConversation(val conversation: ChatConversation, val reply: CompletableDeferred<Unit>) : DatabaseRequest()
        data class DeleteChatConversation(val phoneNumber: String, val reply: CompletableDeferred<Boolean>) : DatabaseRequest()

        // App Settings
        data class GetSetting(val key: String, val defaultValue: String, val reply: CompletableDeferred<String>) : DatabaseRequest()
        data class GetBooleanSetting(val key: String, val defaultValue: Boolean, val reply: CompletableDeferred<Boolean>) : DatabaseRequest()
        data class SaveSetting(val key: String, val value: String, val valueType: String, val reply: CompletableDeferred<Unit>) : DatabaseRequest()
        data class SaveBooleanSetting(val key: String, val value: Boolean, val reply: CompletableDeferred<Unit>) : DatabaseRequest()

        data class UpdateChatEncryptionScheme(
            val phoneNumber: String,
            val encryptionScheme: String,
            val reply: CompletableDeferred<Boolean>
        ) : DatabaseRequest()

        data class UpdateChatEncryptionSchemeWidthPassword(
            val phoneNumber: String,
            val encryptionScheme: String,
            val encryptionPassword: String,
            val reply: CompletableDeferred<Boolean>
        ) : DatabaseRequest()

        data class UpdateChatEncodingScheme(
            val phoneNumber: String,
            val encodingScheme: String,
            val encodingPassword: String,
            val reply: CompletableDeferred<Boolean>
        ) : DatabaseRequest()

        data class AddMessageForConversation(
            val phoneNumber: String,
            val message: ChatMessage,
            val reply: CompletableDeferred<Boolean>
        ) : DatabaseRequest()

        // Delete Message
        data class DeleteMessageById(
            val messageId: Long,  // ERA String, ORA Long
            val reply: CompletableDeferred<Boolean>
        ) : DatabaseRequest()

        // Delete Message By Metadata
        data class DeleteMessageByMetadata(
            val metadataKey: String,
            val metadataValue: String,
            val reply: CompletableDeferred<Boolean>
        ) : DatabaseRequest()

        // Decoded Messages
        data class SaveDecodedMessage(
            val originalMessage: String,
            val decodedMessage: String,
            val sender: String,
            val senderName: String?,
            val timestamp: Long,
            val success: Boolean,
            val decodingScheme: String,
            val messageType: String,
            val additionalInfo: Map<String, Any>?,
            val reply: CompletableDeferred<Unit>
        ) : DatabaseRequest()

        data class GetDecodedMessages(val limit: Int, val reply: CompletableDeferred<List<DecodedMessageEntity.DecodedMessageDomain>>) : DatabaseRequest()

        // Test Database
        data class TestDatabase(val reply: CompletableDeferred<Boolean>) : DatabaseRequest()

        // Clear
        data class ClearDatabase(val reply: CompletableDeferred<Unit>) : DatabaseRequest()
    }

    private fun runInitialTest(): Boolean {
        if (isTested.get()) {
            return testResult.get()
        }

        LogUtils.d(null,"DatabaseActor", "🧪 Test RUN ONCE...")

        return try {
            val testPassed = databaseManager.testDatabaseOperations(appContext)

            testResult.set(testPassed)
            isTested.set(true)

            if (testPassed) {
                LogUtils.d(null,"DatabaseActor", "✅✅✅ DATABASE TEST PASSED!")
            } else {
                LogUtils.e("DatabaseActor", "❌❌❌ DATABASE TEST FAILED!")
            }

            testPassed

        } catch (e: Exception) {
            LogUtils.e("DatabaseActor", "❌❌❌ ERRORE DURING DATABASE TEST", e)
            testResult.set(false)
            isTested.set(true)
            false
        }
    }

    // ==================== ACTOR LOOP ====================
    private fun startActor() {
        if (isRunning.getAndSet(true)) {
            LogUtils.d(null,"DatabaseActor", "⚠️ Actor already in execution")
            return
        }

        actorScope.launch {
            LogUtils.d(null,"DatabaseActor", "🚀 Actor started on thread: ${Thread.currentThread().name}")

            for (request in requestChannel) {
                try {
                    processRequest(request)
                } catch (e: Exception) {
                    LogUtils.e("DatabaseActor", "❌ Error processing request", e)
                    completeRequestWithError(request, e)
                }
            }

            LogUtils.d(null,"DatabaseActor", "🛑 Actor stopped")
            isRunning.set(false)
        }
    }

    private suspend fun processRequest(request: DatabaseRequest) {
        when (request) {

            is DatabaseRequest.FindMessageByMetadata -> {
                val result = findMessageByMetadataInternal(request.metadataKey, request.metadataValue)
                request.reply.complete(result)
            }
            is DatabaseRequest.MarkMessageAsReplaced -> {
                val result = markMessageAsReplacedInternal(request.messageId)
                request.reply.complete(result)
            }
            is DatabaseRequest.GetSystemMessagesByType -> {
                val result = getSystemMessagesByTypeInternal(request.conversationId, request.metadataType)
                request.reply.complete(result)
            }
            is DatabaseRequest.DeleteMessageByMetadata -> {
                val result = deleteMessageByMetadataInternal(request.metadataKey, request.metadataValue)
                request.reply.complete(result)
            }

            is DatabaseRequest.UpdateChatName -> {
                val result = updateChatNameInternal(request.phoneNumber, request.newName)
                request.reply.complete(result)
            }

            // Trusted Contacts
            is DatabaseRequest.GetTrustedContacts -> {
                val result = databaseManager.getTrustedContacts(appContext)
                request.reply.complete(result)
            }
            is DatabaseRequest.SaveTrustedContact -> {
                databaseManager.saveTrustedContact(request.contact, appContext)
                request.reply.complete(Unit)
            }
            is DatabaseRequest.RemoveTrustedContact -> {
                databaseManager.removeTrustedContact(request.contactId)
                request.reply.complete(Unit)
            }

            // Chat Conversations
            is DatabaseRequest.GetChatConversations -> {
                val result = databaseManager.getChatConversations(appContext)
                request.reply.complete(result)
            }
            is DatabaseRequest.GetChatConversation -> {
                val result = databaseManager.getChatConversation(request.phoneNumber, appContext)
                request.reply.complete(result)
            }
            is DatabaseRequest.SaveChatConversation -> {
                databaseManager.saveChatConversation(request.conversation, appContext)
                request.reply.complete(Unit)
            }
            is DatabaseRequest.UpdateChatConversation -> {
                databaseManager.updateChatConversation(request.conversation, appContext)
                request.reply.complete(Unit)
            }
            is DatabaseRequest.DeleteChatConversation -> {
                val result = deleteChatConversationInternal(request.phoneNumber)
                request.reply.complete(result)
            }
            is DatabaseRequest.DeleteChatConversationByContactName -> {
                val result = deleteChatConversationByContactNameInternal(request.contactName)
                request.reply.complete(result)
            }
            // App Settings
            is DatabaseRequest.GetSetting -> {
                val result = databaseManager.getSetting(request.key, request.defaultValue, appContext)
                request.reply.complete(result)
            }
            is DatabaseRequest.GetBooleanSetting -> {
                val result = databaseManager.getBooleanSetting(request.key, request.defaultValue, appContext)
                request.reply.complete(result)
            }
            is DatabaseRequest.SaveSetting -> {
                databaseManager.saveSetting(request.key, request.value, request.valueType, appContext)
                request.reply.complete(Unit)
            }
            is DatabaseRequest.SaveBooleanSetting -> {
                databaseManager.saveBooleanSetting(request.key, request.value, appContext)
                request.reply.complete(Unit)
            }
            is DatabaseRequest.AddMessageForConversation -> {
                val result = addMessageToConversationInternal(request.phoneNumber, request.message)
                request.reply.complete(result)
            }
            is DatabaseRequest.DeleteMessageById -> {
                val result = deleteMessageByIdUsingDatabaseManager(request.messageId)
                request.reply.complete(result)
            }
            is DatabaseRequest.UpdateChatEncodingScheme -> {
                val result = databaseManager.updateEncodingAndPassword(
                    request.phoneNumber,
                    request.encodingScheme,
                    request.encodingPassword,
                    appContext
                )
                request.reply.complete(result)
            }
            is DatabaseRequest.UpdateChatEncryptionScheme -> {
                val result = databaseManager.updateChatEncryptionScheme(request.phoneNumber, request.encryptionScheme, appContext)
                request.reply.complete(result)
            }
            // Decoded Messages
            is DatabaseRequest.SaveDecodedMessage -> {
                databaseManager.saveDecodedMessage(
                    request.originalMessage,
                    request.decodedMessage,
                    request.sender,
                    request.senderName,
                    request.timestamp,
                    request.success,
                    request.decodingScheme,
                    request.messageType,
                    request.additionalInfo,
                    appContext
                )
                request.reply.complete(Unit)
            }
            is DatabaseRequest.GetDecodedMessages -> {
                val result = databaseManager.getDecodedMessages(appContext, request.limit)
                request.reply.complete(result)
            }

            // Test Database
            is DatabaseRequest.TestDatabase -> {
                val result = databaseManager.testDatabaseOperations(appContext)
                request.reply.complete(result)
            }

            // Clear
            is DatabaseRequest.ClearDatabase -> {
                databaseManager.clearDatabase()
                request.reply.complete(Unit)
            }
            else -> {

            }
        }
    }

    /**
     * Deletes a message in database using long ID
     */
    suspend fun deleteMessageByIdUsingDatabaseManager(messageId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(null, "DatabaseActor", "🗑️ Deleting message ID Long: $messageId")

                val messageDao = databaseManager.database.chatMessageDao()
                val rowsDeleted = messageDao.deleteById(messageId)

                if (rowsDeleted > 0) {
                    LogUtils.d(null, "DatabaseActor", "✅ Message $messageId deleted")
                    true
                } else {
                    // Fallback: search for ID
                    val messageEntity = messageDao.findById(messageId)
                    if (messageEntity != null) {
                        messageDao.delete(messageEntity)
                        LogUtils.d(null, "DatabaseActor", "✅ Message $messageId deleted (via find)")
                        true
                    } else {
                        LogUtils.e("DatabaseActor", "❌ Message $messageId not found")
                        false
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("DatabaseActor", "❌ Error deleting message Long", e)
                false
            }
        }
    }
    /**
     * Delete a message by finding it through metadata
     */
    private suspend fun deleteMessageByMetadataInternal(metadataKey: String, metadataValue: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(appContext, "DatabaseActor",
                    "🗑️ Looking for message to delete with $metadataKey = $metadataValue")

                // Get all messages with metadata
                val allMessages = databaseManager.database.chatMessageDao().getAllMessagesWithMetadata()

                var messageFound: ChatMessageEntity? = null

                // Find the message with matching metadata
                for (entity in allMessages) {
                    try {
                        if (entity.metadataJson != null) {
                            val decryptedJson = AppCryptoManager.decrypt64Value(entity.metadataJson)
                            val metadata = gson.fromJson(decryptedJson, Map::class.java) as Map<String, String>

                            if (metadata[metadataKey] == metadataValue) {
                                messageFound = entity
                                LogUtils.d(appContext, "DatabaseActor",
                                    "✅ Found message with metadata: ${entity.id}")
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // Skip if metadata can't be decrypted
                    }
                }

                if (messageFound != null) {
                    // Delete the message
                    val rowsDeleted = databaseManager.database.chatMessageDao()
                        .deleteById(messageFound.id)

                    if (rowsDeleted > 0) {
                        LogUtils.d(appContext, "DatabaseActor",
                            "✅ Deleted message: ${messageFound.id}")
                        true
                    } else {
                        false
                    }
                } else {
                    LogUtils.d(appContext, "DatabaseActor",
                        "❌ No message found with $metadataKey = $metadataValue")
                    false
                }

            } catch (e: Exception) {
                LogUtils.e(appContext, "DatabaseActor",
                    "❌ Error deleting message by metadata", e)
                false
            }
        }
    }

    /**
     * Find a message by ID (added for debugging)
     */
    suspend fun findMessageById(messageId: Long): ChatMessage? {
        return withContext(Dispatchers.IO) {
            try {
                val entity = databaseManager.database.chatMessageDao().findById(messageId)
                entity?.toDomain(appContext)
            } catch (e: Exception) {
                LogUtils.e(appContext, "DatabaseActor", "Error finding message by ID", e)
                null
            }
        }
    }



    private fun addMessageToConversationInternal(phoneNumber: String, message: ChatMessage): Boolean {
        return try {
            LogUtils.d(null,"DatabaseActor", "🔍 ====== SEARCH FOR CONVERSATION ======")
            LogUtils.d(null,"DatabaseActor", "📱 Original number: $phoneNumber")

            val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
            LogUtils.d(null,"DatabaseActor", "📱 Normalized number: $normalizedNumber")

            LogUtils.d(null,"DatabaseActor", "💬 Message text: '${message.text.take(30)}...'")
            LogUtils.d(null,"DatabaseActor", "📝 Message ID: ${message.id}")

            val encryptedPhoneValue = AppCryptoManager.encrypt64Value(normalizedNumber)
            val encryptedPhoneKey = AppCryptoManager.encrypt64Key(normalizedNumber)

            LogUtils.d(null,"DatabaseActor", "🔑 Generated values:")
            LogUtils.d(null,"DatabaseActor", "  encryptValue: ${encryptedPhoneValue.take(30)}...")
            LogUtils.d(null,"DatabaseActor", "  encryptKey: ${encryptedPhoneKey.take(30)}...")

            LogUtils.d(null,"DatabaseActor", "📊 Conversation in database:")
            val allConversations = databaseManager.database.chatConversationDao().getAll()

            if (allConversations.isEmpty()) {
                LogUtils.d(null,"DatabaseActor", "  ❌ Empty Database!")
                return createNewConversationForMessage(normalizedNumber, message)
            }

            LogUtils.d(null,"DatabaseActor", "🔍 Manual search among ${allConversations.size} conversations...")

            var foundEntity: ChatConversationEntity? = null

            for (conv in allConversations) {
                try {
                    // Decrypt number from db
                    val decryptedPhone = AppCryptoManager.decrypt64Value(conv.phoneNumber)
                    val normalizedDecryptedPhone = PhoneUtils.normalizePhoneNumber(decryptedPhone)

                    LogUtils.d(null,"DatabaseActor", "  Comparison:")
                    LogUtils.d(null,"DatabaseActor", "    DB: '$decryptedPhone' -> '$normalizedDecryptedPhone'")
                    LogUtils.d(null,"DatabaseActor", "    Search: '$phoneNumber' -> '$normalizedNumber'")

                    if (normalizedDecryptedPhone == normalizedNumber) {
                        foundEntity = conv
                        LogUtils.d(null,"DatabaseActor", "    ✅✅✅ MATCHING NUMBER FOUND!")
                        LogUtils.d(null,"DatabaseActor", "      ID: ${conv.id}")
                        LogUtils.d(null,"DatabaseActor", "      phone_hash in DB: ${conv.phoneHash?.take(30)}...")
                        break
                    } else {
                        LogUtils.d(null,"DatabaseActor", "    ❌ Does not match")
                    }

                } catch (e: Exception) {
                    LogUtils.d(null,"DatabaseActor", "  ❌ Error decrypting conversation")
                }
            }

            if (foundEntity != null) {
                LogUtils.d(null,"DatabaseActor", "🎯 CONVERSATION FOUND! ID: ${foundEntity.id}")
                return saveMessageToEntity(foundEntity, message)
            } else {
                LogUtils.d(null,"DatabaseActor", "❌ Conversation not found")
                LogUtils.d(null,"DatabaseActor", "🔨 Create new conversation...")
                return createNewConversationForMessage(normalizedNumber, message)
            }

        } catch (e: Exception) {
            LogUtils.e("DatabaseActor", "💥 CRITICAL ERROR", e)
            return false
        }
    }

    private fun saveMessageToEntity(entity: ChatConversationEntity, message: ChatMessage): Boolean {
        return try {
            val messageEntity = ChatMessageEntity.fromDomain(message, entity.id, appContext)

            val existingMessage = databaseManager.database.chatMessageDao()
                .findById(messageEntity.id)

            if (existingMessage == null) {
                val newId = databaseManager.database.chatMessageDao().insert(messageEntity)
                LogUtils.d(null,"DatabaseActor", "💾 Message saved in db with ID: $newId")

                entity.lastMessage = AppCryptoManager.encrypt64Value(message.text)
                entity.lastTimestamp = message.timestamp
                if (!message.isOutgoing) {
                    entity.unreadCount += 1
                }
                databaseManager.database.chatConversationDao().update(entity)

                LogUtils.d(null,"DatabaseActor", "✅✅✅ MESSAGE SAVED WITH SUCCESS!")
                true
            } else {
                Log.w("DatabaseActor", "⚠️ Message already existing (ID: ${messageEntity.id})")
                true
            }
        } catch (e: Exception) {
            LogUtils.e("DatabaseActor", "❌ Error saving message", e)
            false
        }
    }

    private fun createNewConversationForMessage(phoneNumber: String, message: ChatMessage): Boolean {
        try {
            LogUtils.d(null,"DatabaseActor", "🔨 Create new conversation for: $phoneNumber")

            val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
            val formattedNumber = formatPhoneNumberForDisplay(normalizedNumber)

            val chatManager = ChatManager(appContext)
            val contactName = chatManager.getContactNameFromPhone(normalizedNumber) ?: formattedNumber

            val newConversation = ChatConversation(
                phoneNumber = normalizedNumber,
                contactName = contactName,
                lastMessage = message.text,
                lastTimestamp = System.currentTimeMillis(),
                messages = mutableListOf(message),
                unreadCount = if (!message.isOutgoing) 1 else 0,
                isYChat = false,
                encryptionScheme = ""
            )

            // Save conversation
            runBlocking {
                this@DatabaseActor.saveChatConversation(newConversation)
            }

            LogUtils.d(null,"DatabaseActor", "✅ New conversation created with number: $normalizedNumber")

            return addMessageToConversationInternal(normalizedNumber, message)

        } catch (e: Exception) {
            LogUtils.e("DatabaseActor", "❌ Error creating new conversation", e)
            return false
        }
    }
    private fun deleteChatConversationByContactNameInternal(contactName: String): Boolean {
        return try {
            LogUtils.d(null, "DatabaseActor", "🗑️ Deleting chat for contactName (decrypted): $contactName")
            // Get all conversation to be sure to find the right one
            val allConversations = databaseManager.database.chatConversationDao().getAll()
            LogUtils.d(null, "DatabaseActor", "📊 Total chats in DB: ${allConversations.size}")

            var foundEntity: ChatConversationEntity? = null

            // Scan and compare every contact_name for comparison
            for (conv in allConversations) {
                try {
                    val decryptedContactName = conv.contactName?.let {
                        AppCryptoManager.decrypt64Value(it)
                    }
                    if (decryptedContactName == contactName) {
                        foundEntity = conv
                        LogUtils.d(null, "DatabaseActor", "  ✅✅✅ FOUND! ID: ${conv.id}")
                        break
                    }
                } catch (e: Exception) {
                    // Ignora errori di decifratura
                    LogUtils.d(null, "DatabaseActor", "  ❌ Error Decryptingconversation ${conv.id}")
                }
            }

            // if found, delete
            foundEntity?.let { entity ->
                // First delete the messages
                databaseManager.database.chatMessageDao().deleteByConversation(entity.id)

                // Then the conversation itsself
                if (!entity.phoneHash.isNullOrEmpty()) {
                    databaseManager.database.chatConversationDao().deleteByPhoneHash(entity.phoneHash)
                    LogUtils.d(null, "DatabaseActor", "✅ Chat deleted via phone_hash: ${entity.phoneHash}")
                } else {
                    // Fallback: delete per ID
                    databaseManager.database.chatConversationDao().delete(entity)
                    LogUtils.d(null, "DatabaseActor", "✅ Chat deleted via entity delete")
                }

                return true
            }

            LogUtils.d(null, "DatabaseActor", "❌ No conversation found with contactName: $contactName")
            false

        } catch (e: Exception) {
            LogUtils.e("DatabaseActor", "❌ Error deletingconversation via contactName", e)
            false
        }
    }
    private fun deleteChatConversationInternal(phoneNumber: String): Boolean {
        return try {
            val phoneHash = AppCryptoManager.encrypt64Key(phoneNumber)

            // Search for conversation
            val conversationEntity = databaseManager.database.chatConversationDao()
                .findByPhoneHash(phoneHash)

            conversationEntity?.let { entity ->
                // First delete its messages
                databaseManager.database.chatMessageDao().deleteByConversation(entity.id)

                // Then the conversation itself
                databaseManager.database.chatConversationDao().deleteByPhoneHash(phoneHash)

                LogUtils.d(null,"DatabaseActor", "✅ Chat deleted: $phoneNumber")
                true
            } ?: run {
                // Fallback: try with old method
                val encryptedPhone = AppCryptoManager.encrypt64Value(phoneNumber)
                val entityByPhone = databaseManager.database.chatConversationDao()
                    .findByPhoneNumber(encryptedPhone)

                entityByPhone?.let { entity ->
                    databaseManager.database.chatMessageDao().deleteByConversation(entity.id)
                    databaseManager.database.chatConversationDao().deleteByPhoneNumber(encryptedPhone)

                    LogUtils.d(null,"DatabaseActor", "✅ Chat deleted with fallback: $phoneNumber")
                    true
                } ?: run {
                    LogUtils.e("DatabaseActor", "❌ Conversation not found for: $phoneNumber")
                    false
                }
            }
        } catch (e: Exception) {
            LogUtils.e("DatabaseActor", "❌ Error deleting chat", e)
            false
        }
    }

    private fun completeRequestWithError(request: DatabaseRequest, error: Exception) {
        when (request) {
            is DatabaseRequest.GetTrustedContacts -> request.reply.completeExceptionally(error)
            is DatabaseRequest.SaveTrustedContact -> request.reply.completeExceptionally(error)
            is DatabaseRequest.RemoveTrustedContact -> request.reply.completeExceptionally(error)
            is DatabaseRequest.GetChatConversations -> request.reply.completeExceptionally(error)
            is DatabaseRequest.GetChatConversation -> request.reply.completeExceptionally(error)
            is DatabaseRequest.SaveChatConversation -> request.reply.completeExceptionally(error)
            is DatabaseRequest.UpdateChatConversation -> request.reply.completeExceptionally(error)
            is DatabaseRequest.DeleteChatConversation -> request.reply.completeExceptionally(error)
            is DatabaseRequest.GetSetting -> request.reply.completeExceptionally(error)
            is DatabaseRequest.GetBooleanSetting -> request.reply.completeExceptionally(error)
            is DatabaseRequest.SaveSetting -> request.reply.completeExceptionally(error)
            is DatabaseRequest.SaveBooleanSetting -> request.reply.completeExceptionally(error)
            is DatabaseRequest.SaveDecodedMessage -> request.reply.completeExceptionally(error)
            is DatabaseRequest.GetDecodedMessages -> request.reply.completeExceptionally(error)
            is DatabaseRequest.TestDatabase -> request.reply.completeExceptionally(error)
            is DatabaseRequest.ClearDatabase -> request.reply.completeExceptionally(error)
            is DatabaseRequest.AddMessageForConversation -> request.reply.completeExceptionally(error)
            is DatabaseRequest.DeleteMessageById -> request.reply.completeExceptionally(error)
            is DatabaseRequest.DeleteMessageByMetadata -> request.reply.completeExceptionally(error)
            else -> {}
        }
    }

    // ==================== PUBLIC API (SUSPEND) ====================

    // Add these methods to the DatabaseActor class

    /**
     * Find a message by its metadata
     */
    suspend fun findMessageByMetadata(metadataKey: String, metadataValue: String): ChatMessage? {
        waitForInitialization()
        val reply = CompletableDeferred<ChatMessage?>()
        requestChannel.send(DatabaseRequest.FindMessageByMetadata(metadataKey, metadataValue, reply))
        return reply.await()
    }

    /**
     * Mark a message as replaced (soft delete)
     */
    suspend fun markMessageAsReplaced(messageId: Long): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.MarkMessageAsReplaced(messageId, reply))
        return reply.await()
    }

    /**
     * Get system messages of a specific type for a conversation
     */
    suspend fun getSystemMessagesByType(conversationId: Long, metadataType: String): List<ChatMessage> {
        waitForInitialization()
        val reply = CompletableDeferred<List<ChatMessage>>()
        requestChannel.send(DatabaseRequest.GetSystemMessagesByType(conversationId, metadataType, reply))
        return reply.await()
    }

    /**
     * Delete a dummy/progress message by finding it through metadata
     */
    suspend fun deleteDummyMessageByMetadata(metadataKey: String, metadataValue: String): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.DeleteMessageByMetadata(metadataKey, metadataValue, reply))
        return reply.await()
    }

    /**
     * Delete a dummy/progress message
     */
    suspend fun deleteDummyMessage(messageId: Long): Boolean {
        waitForInitialization()
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(appContext, "DatabaseActor", "🗑️ Deleting dummy message: $messageId")

                val rowsDeleted = databaseManager.database.chatMessageDao().deleteById(messageId)

                if (rowsDeleted > 0) {
                    LogUtils.d(appContext, "DatabaseActor", "✅ Dummy message deleted")
                    true
                } else {
                    LogUtils.w(appContext, "DatabaseActor", "⚠️ Dummy message not found")
                    false
                }

            } catch (e: Exception) {
                LogUtils.e(appContext, "DatabaseActor", "❌ Error deleting dummy message", e)
                false
            }
        }
    }


    suspend fun updateChatName(phoneNumber: String, newName: String): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.UpdateChatName(phoneNumber, newName, reply))
        return reply.await()
    }

    suspend fun updateChatEncryptionScheme(phoneNumber: String, encryptionScheme: String): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.UpdateChatEncryptionScheme(phoneNumber, encryptionScheme, reply))
        return reply.await()
    }

    suspend fun updateChatEncodingScheme(phoneNumber: String, encodingScheme: String, encodingPassword: String): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.UpdateChatEncodingScheme(phoneNumber, encodingScheme, encodingPassword, reply))
        return reply.await()
    }

    suspend fun getTrustedContacts(): List<TrustedContact> {
        waitForInitialization()
        val reply = CompletableDeferred<List<TrustedContact>>()
        requestChannel.send(DatabaseRequest.GetTrustedContacts(reply))
        return reply.await()
    }

    suspend fun saveTrustedContact(contact: TrustedContact) {
        waitForInitialization()
        val reply = CompletableDeferred<Unit>()
        requestChannel.send(DatabaseRequest.SaveTrustedContact(contact, reply))
        reply.await()
    }

    suspend fun removeTrustedContact(contactId: String) {
        waitForInitialization()
        val reply = CompletableDeferred<Unit>()
        requestChannel.send(DatabaseRequest.RemoveTrustedContact(contactId, reply))
        reply.await()
    }

    suspend fun getChatConversations(): List<ChatConversation> {
        waitForInitialization()
        val reply = CompletableDeferred<List<ChatConversation>>()
        requestChannel.send(DatabaseRequest.GetChatConversations(reply))
        return reply.await()
    }

    suspend fun getChatConversation(phoneNumber: String): ChatConversation? {
        waitForInitialization()
        val reply = CompletableDeferred<ChatConversation?>()
        requestChannel.send(DatabaseRequest.GetChatConversation(phoneNumber, reply))
        return reply.await()
    }

    suspend fun saveChatConversation(conversation: ChatConversation) {
        waitForInitialization()
        val reply = CompletableDeferred<Unit>()
        requestChannel.send(DatabaseRequest.SaveChatConversation(conversation, reply))
        reply.await()
    }

    suspend fun updateChatConversation(conversation: ChatConversation) {
        waitForInitialization()
        val reply = CompletableDeferred<Unit>()
        requestChannel.send(DatabaseRequest.UpdateChatConversation(conversation, reply))
        reply.await()
    }

    suspend fun deleteChatConversation(phoneNumber: String): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.DeleteChatConversation(phoneNumber, reply))
        return reply.await()
    }

    suspend fun deleteChatConversationByContactName(contactName: String): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.DeleteChatConversationByContactName(contactName, reply))
        return reply.await()
    }
    suspend fun getSetting(key: String, defaultValue: String = ""): String {
        waitForInitialization()
        val reply = CompletableDeferred<String>()
        requestChannel.send(DatabaseRequest.GetSetting(key, defaultValue, reply))
        return reply.await()
    }

    suspend fun getBooleanSetting(key: String, defaultValue: Boolean = false): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.GetBooleanSetting(key, defaultValue, reply))
        return reply.await()
    }

    suspend fun saveSetting(key: String, value: String, valueType: String = "string") {
        waitForInitialization()
        val reply = CompletableDeferred<Unit>()
        requestChannel.send(DatabaseRequest.SaveSetting(key, value, valueType, reply))
        reply.await()
    }

    suspend fun saveBooleanSetting(key: String, value: Boolean) {
        waitForInitialization()
        val reply = CompletableDeferred<Unit>()
        requestChannel.send(DatabaseRequest.SaveBooleanSetting(key, value, reply))
        reply.await()
    }

    suspend fun deleteMessageById(messageId: Long): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.DeleteMessageById(messageId, reply))
        return reply.await()
    }



    private fun findConversationForMessages(phoneNumber: String): ChatConversationEntity? {
        return try {
            val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
            val phoneHash = AppCryptoManager.encrypt64Key(normalizedNumber)

            // Prima prova con phone_hash
            var entity = databaseManager.database.chatConversationDao()
                .findByPhoneHash(phoneHash)

            if (entity == null) {
                // Fallback con numero cifrato
                val encryptedPhone = AppCryptoManager.encrypt64Value(normalizedNumber)
                entity = databaseManager.database.chatConversationDao()
                    .findByPhoneNumber(encryptedPhone)
            }

            entity
        } catch (e: Exception) {
            LogUtils.e("DatabaseActor", "❌ Errore ricerca conversazione", e)
            null
        }
    }

    /**
     * Counts total messages for a conversation using ID
     */
    suspend fun countMessagesForConversationId(conversationId: Long): Int {
        waitForInitialization()
        return withContext(Dispatchers.IO) {
            try {
                val count = databaseManager.database.chatMessageDao()
                    .countByConversation(conversationId)
                LogUtils.d(appContext, "DatabaseActor",
                    "📊 countMessagesForConversationId: ID=$conversationId, count=$count")
                count
            } catch (e: Exception) {
                LogUtils.e("DatabaseActor", "❌ Error countMessagesForConversationId", e)
                0
            }
        }
    }

    /**
     * More efficient - get last n messages (opening a chat)
     */
    suspend fun getLatestMessagesByConversationId(conversationId: Long, limit: Int): List<ChatMessage> {
        waitForInitialization()
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities = databaseManager.database.chatMessageDao()
                    .getLatestMessages(conversationId, limit)

                // Decrypt
                val messages = messageEntities.mapNotNull { entity ->
                    try {
                        entity.toDomain(appContext)
                    } catch (e: Exception) {
                        null
                    }
                }.reversed()

                messages

            } catch (e: Exception) {
                LogUtils.e("DatabaseActor", "❌ Error getLatestMessagesByConversationId", e)
                emptyList()
            }
        }
    }

    /**
     * Gets older messages using limit and offset
     */
    suspend fun getOlderMessagesByConversationId(conversationId: Long, limit: Int, offset: Int): List<ChatMessage> {
        waitForInitialization()
        return withContext(Dispatchers.IO) {
            try {
                val messageEntities = databaseManager.database.chatMessageDao()
                    .getOlderMessages(conversationId, limit, offset)

                // Decrypt
                val messages = messageEntities.mapNotNull { entity ->
                    try {
                        entity.toDomain(appContext)
                    } catch (e: Exception) {
                        null
                    }
                }

                messages

            } catch (e: Exception) {
                LogUtils.e("DatabaseActor", "❌ Error getOlderMessagesByConversationId", e)
                emptyList()
            }
        }
    }


    /**
     * Gets all messages for a certain conv. Mainly for chat import/export
     */
    suspend fun getAllMessagesForConversation(phoneNumber: String): List<ChatMessage> {
        waitForInitialization()
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(appContext, "DatabaseActor", "🔍 getAllMessagesForConversation for: $phoneNumber")

                // Find the conversation
                val conversation = findConversationForMessages(phoneNumber)

                if (conversation != null) {
                    LogUtils.d(appContext, "DatabaseActor", "✅ Conversation found, ID: ${conversation.id}")

                    // Get all messages
                    val messageEntities = databaseManager.database.chatMessageDao()
                        .getByConversation(conversation.id)

                    LogUtils.d(appContext, "DatabaseActor", "📊 Total messages in DB: ${messageEntities.size}")

                    // Decrypt and convert
                    val messages = mutableListOf<ChatMessage>()

                    for (messageEntity in messageEntities) {
                        try {
                            val message = messageEntity.toDomain(appContext)
                            messages.add(message)
                        } catch (e: Exception) {
                            LogUtils.e("DatabaseActor", "❌ ERROR DECRYPTING MESSAGE", e)
                            // Add an error message for traceability
                            messages.add(
                                ChatMessage(
                                    id = -1, // ID di errore
                                    text = "[CORRUPTED MESSAGE - DECRYPTION ERROR]",
                                    sender = "system",
                                    timestamp = messageEntity.timestamp,
                                    isDecoded = false,
                                    isOutgoing = false,
                                    isYMessage = false,
                                    isSent = true
                                )
                            )
                        }
                    }

                    LogUtils.d(appContext, "DatabaseActor", "✅ Decrypted messages: ${messages.size}")
                    messages

                } else {
                    LogUtils.w(appContext, "DatabaseActor", "⚠️ Conversation not found for: $phoneNumber")
                    emptyList()
                }

            } catch (e: Exception) {
                LogUtils.e("DatabaseActor", "❌ Error getAllMessagesForConversation", e)
                emptyList()
            }
        }
    }

    /**
     * Get messages inside a specific time range
     */
    suspend fun getMessagesInTimeRange(phoneNumber: String, startTime: Long, endTime: Long): List<ChatMessage> {
        waitForInitialization()
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(appContext, "DatabaseActor", "📅 Getting messages in range for: $phoneNumber")

                val conversation = findConversationForMessages(phoneNumber)

                if (conversation != null) {
                    LogUtils.d(appContext, "DatabaseActor", "✅ Conversation found, ID: ${conversation.id}")
                    getMessagesForConversationById(conversation.id, startTime, endTime)
                } else {
                    LogUtils.w(appContext, "DatabaseActor", "⚠️ Conversation not found for: $phoneNumber")
                    emptyList()
                }

            } catch (e: Exception) {
                LogUtils.e("DatabaseActor", "❌ Critical error getMessagesInTimeRange", e)
                emptyList()
            }
        }
    }

    /**
     * Helper to get messages of a chat inside a time range
     */
    private suspend fun getMessagesForConversationById(
        conversationId: Long,
        startTime: Long,
        endTime: Long
    ): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(appContext, "DatabaseActor", "📊 Query messages for conv ID: $conversationId, range: $startTime-$endTime")

                val messageEntities = databaseManager.database.chatMessageDao()
                    .getMessagesInTimeRange(conversationId, startTime, endTime)

                LogUtils.d(appContext, "DatabaseActor", "✅ Messages found in range: ${messageEntities.size}")

                // Decrypt and convert
                val messages = mutableListOf<ChatMessage>()

                for (messageEntity in messageEntities) {
                    try {
                        val message = messageEntity.toDomain(appContext)
                        messages.add(message)
                    } catch (e: Exception) {
                        LogUtils.e("DatabaseActor", "❌ Error decrypting message", e)
                        // Add error message for traceability
                        messages.add(
                            ChatMessage(
                                id = -1,
                                text = "[CORRUPTED MESSAGE]",
                                sender = "system",
                                timestamp = messageEntity.timestamp,
                                isDecoded = false,
                                isOutgoing = false,
                                isYMessage = false,
                                isSent = true
                            )
                        )
                    }
                }

                LogUtils.d(appContext, "DatabaseActor", "✅ Decrypted messages: ${messages.size}")
                messages

            } catch (e: Exception) {
                LogUtils.e("DatabaseActor", "❌ Error getMessagesForConversationById", e)
                emptyList()
            }
        }
    }

    suspend fun saveDecodedMessage(
        originalMessage: String,
        decodedMessage: String,
        sender: String,
        senderName: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        success: Boolean = true,
        decodingScheme: String = "unknown",
        messageType: String = "sms",
        additionalInfo: Map<String, Any>? = null
    ) {
        waitForInitialization()
        val reply = CompletableDeferred<Unit>()
        requestChannel.send(DatabaseRequest.SaveDecodedMessage(
            originalMessage, decodedMessage, sender, senderName,
            timestamp, success, decodingScheme, messageType,
            additionalInfo, reply
        ))
        reply.await()
    }

    suspend fun getDecodedMessages(limit: Int = 100): List<DecodedMessageEntity.DecodedMessageDomain> {
        waitForInitialization()
        val reply = CompletableDeferred<List<DecodedMessageEntity.DecodedMessageDomain>>()
        requestChannel.send(DatabaseRequest.GetDecodedMessages(limit, reply))
        return reply.await()
    }

    suspend fun testDatabase(): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.TestDatabase(reply))
        return reply.await()
    }

    suspend fun clearDatabase() {
        waitForInitialization()
        val reply = CompletableDeferred<Unit>()
        requestChannel.send(DatabaseRequest.ClearDatabase(reply))
        reply.await()
    }

    suspend fun addMessageToConversation(phoneNumber: String, message: ChatMessage): Boolean {
        waitForInitialization()
        val reply = CompletableDeferred<Boolean>()
        requestChannel.send(DatabaseRequest.AddMessageForConversation(phoneNumber, message, reply))
        return reply.await()
    }

    // Add this helper function to wait for initialization
    private suspend fun waitForInitialization() {
        if (!isReady) {
            LogUtils.d(null, "DatabaseActor", "⏳ Waiting for database initialization...")
            // Simple loop - check every 100ms for up to 30 seconds
            var attempts = 0
            while (!isReady && attempts < 300) { // 300 * 100ms = 30 seconds
                delay(100)
                attempts++
            }
            if (!isReady) {
                LogUtils.e("DatabaseActor", "⚠️ Database still not ready after waiting, proceeding anyway")
            } else {
                LogUtils.d(null, "DatabaseActor", "✅ Database ready, continuing")
            }
        }
    }

    // Add delay function
    private suspend fun delay(time: Long) {
        kotlinx.coroutines.delay(time)
    }

    // ==================== CALLBACK METHODS ====================
    fun deleteChatConversationWithCallback(
        phoneNumber: String,
        contactName: String?,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        actorScope.launch {
            try {
                if (!isReady) {
                    withContext(Dispatchers.Main) {
                        onError("Database not ready yet")
                    }
                    return@launch
                }

                val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
                var result = deleteChatConversation(normalizedNumber)

                if (result) {
                    withContext(Dispatchers.Main) { onSuccess() }
                } else if (!contactName.isNullOrBlank()) {
                    // 🔥 Try with contactName
                    LogUtils.d(null, "DatabaseActor", "🔄 Deletion Try via contactName: $contactName")
                    result = deleteChatConversationByContactName(contactName)

                    if (result) {
                        withContext(Dispatchers.Main) { onSuccess() }
                    } else {
                        withContext(Dispatchers.Main) {
                            onError("Conversation not found")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError("Conversation not found")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("DatabaseActor", "❌ Error deleting chat", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    // ==================== COMPANION (SINGLETON) ====================
    companion object {
        @Volatile
        private var INSTANCE: DatabaseActor? = null

        var onDatabaseTestFailed: ((String) -> Unit)? = null

        fun getInstance(context: Context): DatabaseActor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseActor(context.applicationContext).also {
                    INSTANCE = it
                    LogUtils.d(null,"DatabaseActor", "✅ Singleton created")
                }
            }
        }

        fun reset() {
            INSTANCE = null
            onDatabaseTestFailed = null
        }
    }

    init {
        startActor()

        // RUN THE TEST JUST ONCE IN BACKGROUND
        CoroutineScope(Dispatchers.IO).launch {
            val testPassed = runInitialTest()

            if (!testPassed) {
                val reason = "Database test failed at start"
                onDatabaseTestFailed?.invoke(reason)
                LogUtils.e("DatabaseActor", "💥💥💥 $reason - APP IN DANGER")
            } else {
                // ========== SET READY FLAG ==========
                isReady = true
                onDatabaseReady?.invoke()

                // Notify all listeners
                withContext(Dispatchers.Main) {
                    readyListeners.forEach { it.invoke() }
                    readyListeners.clear()
                }

                LogUtils.d(null, "DatabaseActor", "🚦 Database READY - testDatabaseOperations completed")
            }
        }
    }

    /**
     * Clear counter unread of a chat
     */
    suspend fun resetUnreadCount(phoneNumber: String): Boolean {
        waitForInitialization()
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(null, "DatabaseActor", "🔄 resetUnreadCount for: $phoneNumber")

                val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
                LogUtils.d(null, "DatabaseActor", "📱 Normalized number: $normalizedNumber")

                val phoneHash = AppCryptoManager.encrypt64Key(normalizedNumber)
                LogUtils.d(null, "DatabaseActor", "🔑 Generated phone hash: ${phoneHash.take(30)}...")

                // Use DAO to update
                val dao = databaseManager.database.chatConversationDao()

                // First try: per phone_hash
                var rowsUpdated = dao.resetUnreadCountByHash(phoneHash)
                LogUtils.d(null, "DatabaseActor", "📍 Update via phone_hash: $rowsUpdated righe")

                if (rowsUpdated == 0) {
                    // Fallback: try via encrypted phone_number
                    val encryptedPhone = AppCryptoManager.encrypt64Value(normalizedNumber)
                    rowsUpdated = dao.resetUnreadCountByPhone(encryptedPhone)
                    LogUtils.d(null, "DatabaseActor", "📍 Update via phone_number: $rowsUpdated row(s)")

                    if (rowsUpdated > 0) {
                        // when found via phone_number, update also phone_hash
                        val entity = dao.findByPhoneNumber(encryptedPhone)
                        entity?.let {
                            if (it.phoneHash.isNullOrEmpty()) {
                                it.phoneHash = phoneHash
                                dao.update(it)
                                LogUtils.d(null, "DatabaseActor", "✅ Phone_hash added")
                            }
                        }
                    }
                }

                if (rowsUpdated > 0) {
                    LogUtils.d(null, "DatabaseActor", "✅ UnreadCount cleared for: $phoneNumber")
                    true
                } else {
                    Log.w("DatabaseActor", "⚠️ No conversation found for: $phoneNumber")
                    false
                }

            } catch (e: Exception) {
                LogUtils.e("DatabaseActor", "❌ Error resetUnreadCount", e)
                false
            }
        }
    }


    private fun updateChatNameInternal(phoneNumber: String, newName: String): Boolean {
        return try {
            LogUtils.d(null, "DatabaseActor", "📝 Updating chat name for: $phoneNumber -> $newName")

            val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
            val phoneHash = AppCryptoManager.encrypt64Key(normalizedNumber)

            val dao = databaseManager.database.chatConversationDao()
            val entity = dao.findByPhoneHash(phoneHash)

            if (entity != null) {
                // Create new name
                val encryptedNewName = AppCryptoManager.encrypt64Value(newName)

                // Create an updated pair of entity
                val updatedEntity = entity.copy(
                    contactName = encryptedNewName,
                    updatedAt = System.currentTimeMillis()
                )

                // Uupdate in database
                dao.update(updatedEntity)

                LogUtils.d(null, "DatabaseActor", "✅ Chat name updated for: $phoneNumber")
                true
            } else {
                Log.w("DatabaseActor", "⚠️ Conversation not found for: $phoneNumber")
                false
            }

        } catch (e: Exception) {
            LogUtils.e("DatabaseActor", "❌ Error updating chat name", e)
            false
        }
    }


    /**
     * Find a message by metadata key-value pair
     */
    private suspend fun findMessageByMetadataInternal(metadataKey: String, metadataValue: String): ChatMessage? {
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(appContext, "DatabaseActor", "🔍 Finding message by metadata: $metadataKey = $metadataValue")

                // Get all messages with metadata (this is a simplification - in production you'd want a better query)
                val allMessages = databaseManager.database.chatMessageDao().getAllMessagesWithMetadata()

                for (entity in allMessages) {
                    try {
                        if (entity.metadataJson != null) {
                            val decryptedJson = AppCryptoManager.decrypt64Value(entity.metadataJson)
                            val metadata = gson.fromJson(decryptedJson, Map::class.java) as Map<String, String>

                            if (metadata[metadataKey] == metadataValue) {
                                LogUtils.d(appContext, "DatabaseActor", "✅ Found message with metadata: ${entity.id}")
                                return@withContext entity.toDomain(appContext)
                            }
                        }
                    } catch (e: Exception) {
                        // Skip this message if decryption fails
                        LogUtils.e(appContext, "DatabaseActor", "❌ Error checking message metadata", e)
                    }
                }

                LogUtils.d(appContext, "DatabaseActor", "❌ No message found with metadata: $metadataKey = $metadataValue")
                null

            } catch (e: Exception) {
                LogUtils.e(appContext, "DatabaseActor", "❌ Error finding message by metadata", e)
                null
            }
        }
    }

    /**
     * Mark a message as replaced (soft delete)
     */
    private suspend fun markMessageAsReplacedInternal(messageId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(appContext, "DatabaseActor", "🗑️ Marking message as replaced: $messageId")

                val rowsUpdated = databaseManager.database.chatMessageDao()
                    .markAsReplaced(messageId)

                if (rowsUpdated > 0) {
                    LogUtils.d(appContext, "DatabaseActor", "✅ Message $messageId marked as replaced")
                    true
                } else {
                    LogUtils.w(appContext, "DatabaseActor", "⚠️ Message $messageId not found for replacement")
                    false
                }

            } catch (e: Exception) {
                LogUtils.e(appContext, "DatabaseActor", "❌ Error marking message as replaced", e)
                false
            }
        }
    }

    /**
     * Get all system messages of a specific type for a conversation
     */
    private suspend fun getSystemMessagesByTypeInternal(conversationId: Long, metadataType: String): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            try {
                LogUtils.d(appContext, "DatabaseActor",
                    "🔍 Getting system messages of type '$metadataType' for conversation $conversationId")

                val entities = databaseManager.database.chatMessageDao()
                    .getSystemMessagesByType(conversationId, metadataType)

                val messages = entities.mapNotNull { entity ->
                    try {
                        entity.toDomain(appContext)
                    } catch (e: Exception) {
                        null
                    }
                }

                LogUtils.d(appContext, "DatabaseActor", "✅ Found ${messages.size} system messages")
                messages

            } catch (e: Exception) {
                LogUtils.e(appContext, "DatabaseActor", "❌ Error getting system messages", e)
                emptyList()
            }
        }
    }



    // ==================== CLEANUP ====================
    fun cleanup() {
        actorScope.cancel("DatabaseActor cleanup")
        requestChannel.close()
        isRunning.set(false)
        LogUtils.d(null,"DatabaseActor", "🧹 DatabaseActor cleanup completed")
    }


    fun addReadyListener(listener: () -> Unit) {
        if (isReady) {
            // If already ready, call immediately
            listener()
        } else {
            // Otherwise add to queue
            readyListeners.add(listener)
        }
    }


    // ==================== HELPER FUNCTIONS ====================
    private fun formatPhoneNumberForDisplay(phoneNumber: String): String {
        return when {
            phoneNumber.startsWith("+39") && phoneNumber.length == 13 ->
                "+39 ${phoneNumber.substring(3, 6)} ${phoneNumber.substring(6, 9)} ${phoneNumber.substring(9)}"
            phoneNumber.length == 10 ->
                "${phoneNumber.substring(0, 3)} ${phoneNumber.substring(3, 6)} ${phoneNumber.substring(6)}"
            else -> phoneNumber
        }
    }
}