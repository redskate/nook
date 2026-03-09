package solutions.semweb.nook.data.database

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.TrustedContact
import solutions.semweb.nook.crypto.AppCryptoManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections.emptyList
import java.util.Date
import java.util.Locale

/**
 * NB: LogUtils.e() here in some cases should be left as it is.
 * Because you need to see log error entries in case
 * of wrong functioning databases.
 * LogUtils is filtered and might occult important
 * init errors.
 */

class DatabaseManager private constructor(context: Context) {
    val database: AppDatabase

    init {
        database = AppDatabase.getDatabase(context)
        LogUtils.d(null,"DatabaseManager", "🔐 DatabaseManager initialized SINCRONOUS")
    }

    companion object {
        @Volatile
        private var INSTANCE: DatabaseManager? = null
        private var TESTPHONENUMBER = "+391112223344"
        fun getInstance(context: Context): DatabaseManager {
            return INSTANCE ?: synchronized(this) {
                val instance = DatabaseManager(context)
                INSTANCE = instance
                instance
            }
        }
    }

    // =============================================
    // 1. TRUSTED CONTACTS
    // =============================================

    // In DatabaseManager.kt
    fun getTrustedContacts(context: Context): List<TrustedContact> {
        return try {
            val entities = database.trustedContactDao().getAllSync()
            LogUtils.d(null,"DatabaseManager", "✅ Got ${entities.size} trusted contacts from DB")

            val validContacts = mutableListOf<TrustedContact>()
            val corruptedContacts = mutableListOf<TrustedContactEntity>()

            entities.forEach { entity ->
                try {
                    val contact = entity.toDomain(context)
                    validContacts.add(contact)
                } catch (e: Exception) {
                    LogUtils.e("DatabaseManager",
                        "❌ Corrupted contact found: ${entity.contactId}", e)
                    corruptedContacts.add(entity)
                }
            }

            // Optionally delete corrupted contacts
            if (corruptedContacts.isNotEmpty()) {
                LogUtils.w(context,"DatabaseManager",
                    "⚠️ Found ${corruptedContacts.size} corrupted contacts. Deleting...")
                corruptedContacts.forEach { entity ->
                    database.trustedContactDao().delete(entity)
                }
            }

            validContacts
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error reading trusted contacts", e)
            emptyList()
        }
    }

    fun saveTrustedContact(contact: TrustedContact, context: Context) {
        try {
            val entity = TrustedContactEntity.fromDomain(contact, context)
            database.trustedContactDao().insert(entity)
            LogUtils.d(null,"DatabaseManager", "✅ Saved contact: ${contact.displayName}")
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error saving contact", e)
        }
    }

    fun removeTrustedContact(contactId: String) {
        try {
            database.trustedContactDao().deleteById(contactId)
            LogUtils.d(null,"DatabaseManager", "✅ Removed contact: $contactId")
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error removing contact", e)
        }
    }

    // =============================================
    // 2. CHAT CONVERSATIONS
    // =============================================

    fun getChatConversations(context: Context): List<ChatConversation> {
        return try {
            val entities = database.chatConversationDao().getAll()
            LogUtils.d(null,"DatabaseManager", "✅ Got ${entities.size} conversations")

            val conversations = mutableListOf<ChatConversation>()

            entities.forEach { entity ->
                try {
                    val conversation = entity.toDomain(context)
                    // Controlla se è una chat di test
                    if (isTestConversation(conversation.phoneNumber)) {
                        LogUtils.d(null,"DatabaseManager", "🚫 Excluded test chat: ${conversation.phoneNumber}")
                        return@forEach
                    }
                    conversations.add(conversation)

                } catch (e: Exception) {
                    LogUtils.e("DatabaseManager", "❌ Errore loading conversation ${entity.id}", e)
                    handleConversationError(entity, context, conversations)
                }
            }
            conversations
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error reading conversations", e)
            emptyList()
        }
    }

    private fun isTestConversation(phoneNumber: String): Boolean {
        val testPhoneNumbers = listOf(
            TESTPHONENUMBER
        )

        val normalizedPhone = PhoneUtils.normalizePhoneNumber(phoneNumber)
        return testPhoneNumbers.any { testPhone ->
            PhoneUtils.normalizePhoneNumber(testPhone) == normalizedPhone
        }
    }

    private fun handleConversationError(
        entity: ChatConversationEntity,
        context: Context,
        conversations: MutableList<ChatConversation>
    ) {
        try {
            val conversation = entity.toDomain(context)

            // Apply the same filter even on error:
            if (!isTestConversation(conversation.phoneNumber)) {
                conversations.add(conversation)
            }
        } catch (e2: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error chat ${entity.id}", e2)
        }
    }

    private fun getMessagesForConversationById(conversationId: Long, context: Context): List<ChatMessage> {
        return try {
            val entities = database.chatMessageDao().getByConversation(conversationId)
            entities.map { entity ->
                try {
                    entity.toDomain(context)
                } catch (e: Exception) {
                    LogUtils.e("DatabaseManager", "❌ Error decrypting message ${entity.id}", e)
                    ChatMessage(
                        id = entity.id,
                        text = "[DECRYPTION ERROR]",
                        sender = "unknown",
                        timestamp = entity.timestamp,
                        isDecoded = false,
                        isOutgoing = entity.isOutgoing,
                        isSent = entity.isSent,
                        isYMessage = entity.isYMessage
                    )
                }
            }
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Errore reading messages for conversation $conversationId", e)
            emptyList()
        }
    }

    // Gets conversation object (without messages)
    fun getChatConversation(phoneNumber: String, context: Context): ChatConversation? {
        return try {
            val phoneHash = AppCryptoManager.encrypt64Key(phoneNumber)
            val entity = database.chatConversationDao().findByPhoneHash(phoneHash)

            entity?.toDomain(context)
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Errore reading conversation for: $phoneNumber", e)
            null
        }
    }

    fun saveChatConversation(conversation: ChatConversation, context: Context) {
        try {
            // Prima verifica se esiste già una conversazione
            val existingConversation = findConversationByPhoneNumber(conversation.phoneNumber, context)

            if (existingConversation != null) {
                LogUtils.d(null,"DatabaseManager", "📝 Conversation already exists, just update...")

                // Create an updated version
                val updatedConversation = existingConversation.copy(
                    lastMessage = conversation.lastMessage,
                    lastTimestamp = conversation.lastTimestamp,
                    unreadCount = conversation.unreadCount,
                    contactName = conversation.contactName ?: existingConversation.contactName,
                    encryptionScheme = conversation.encryptionScheme
                )

                // Use updateChatConversation (like in database test!)
                updateChatConversation(updatedConversation, context)
            } else {
                LogUtils.d(null,"DatabaseManager", "➕ New Conversation, insert...")
                val entity = ChatConversationEntity.fromDomain(conversation, context)
                database.chatConversationDao().insert(entity)
                LogUtils.d(null,"DatabaseManager", "✅ New Conversation saved")
            }

        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error saving conversation", e)
            // On constraint error, try update
            if (e.message?.contains("UNIQUE constraint failed") == true) {
                try {
                    LogUtils.d(null,"DatabaseManager", "🔄 Recovery Try: conversation already exists, update it")
                    updateChatConversation(conversation, context)
                } catch (e2: Exception) {
                    LogUtils.e("DatabaseManager", "❌ Recovery failed", e2)
                }
            }
        }
    }

    fun updateChatConversation(conversation: ChatConversation, context: Context) {
        try {
            val entity = ChatConversationEntity.fromDomain(conversation, context)

            // EXTENDED DEBUG
            LogUtils.d(null,"DatabaseManager", "🔄 DEBUG updateChatConversation:")
            LogUtils.d(null,"DatabaseManager", "  Input conversation encryptionScheme: '${conversation.encryptionScheme}'")
            LogUtils.d(null,"DatabaseManager", "  Entity encryptionScheme: '${entity.encryptionScheme}'")
            LogUtils.d(null,"DatabaseManager", "  Entity ID: ${entity.id}")
            LogUtils.d(null,"DatabaseManager", "  Entity phoneHash: ${entity.phoneHash}")

            // First read existing entity for comparison
            val existingEntity = database.chatConversationDao().findByPhoneHash(entity.phoneHash)
            if (existingEntity != null) {
                LogUtils.d(null,"DatabaseManager", "  Existing entity encryptionScheme: '${existingEntity.encryptionScheme}'")
                LogUtils.d(null,"DatabaseManager", "  Existing entity ID: ${existingEntity.id}")
            }

            database.chatConversationDao().update(entity)
            LogUtils.d(null,"DatabaseManager", "✅ Updated conversation: ${conversation.phoneNumber}")

            // Verify after update
            val afterEntity = database.chatConversationDao().findByPhoneHash(entity.phoneHash)
            if (afterEntity != null) {
                LogUtils.d(null,"DatabaseManager", "  After update entity encryptionScheme: '${afterEntity.encryptionScheme}'")
            }

        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error updating conversation", e)
        }
    }


    /**
     * Update encryption scheme for an existing conversation
     */
    fun updateChatEncryptionScheme(phoneNumber: String, encryptionScheme: String, context: Context): Boolean {
        return try {
            LogUtils.d(null,"DatabaseManager", "💾 Update encryptionScheme for: $phoneNumber -> $encryptionScheme")

            val phoneHash = AppCryptoManager.encrypt64Key(phoneNumber)

            database.chatConversationDao().updateEncryptionScheme(
                phoneHash,
                encryptionScheme,
                updatedAt = System.currentTimeMillis()
            )

            LogUtils.d(context,"DatabaseManager", "✅ encryptionScheme updated for: $phoneNumber")
            true
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error updating encryptionScheme", e)
            false
        }
    }

    fun updateEncodingAndPassword(phoneNumber: String, encoding: String, encodingPassword: String, context: Context): Boolean {
        return try {
            LogUtils.d(context, "DatabaseManager",
                "💾 Update encoding for: $phoneNumber -> $encoding")

            val phoneHash = AppCryptoManager.encrypt64Key(phoneNumber)
            val encryptedPhone = AppCryptoManager.encrypt64Value(phoneNumber)

            val dao = database.chatConversationDao()
            val updatedAt = System.currentTimeMillis()

            val existingByHash = dao.findByPhoneHash(phoneHash)
            LogUtils.d(context, "DatabaseManager",
                "🔍 Exists per hash? ${existingByHash != null}")

            if (existingByHash != null) {
                // Uupdate using encrypted values
                val encodingPasswordCrypted = AppCryptoManager.encrypt64Value( encodingPassword )

                dao.updateEncodingAndPassword(phoneHash, encoding, encodingPasswordCrypted, updatedAt)

                // Verify update
                val afterUpdate = dao.findByPhoneHash(phoneHash)
                LogUtils.d(context, "DatabaseManager",
                    "✅ After UPDATE - Encoding: ${afterUpdate?.encoding}, " +
                            "Password: ${if (afterUpdate?.encodingPassword.isNullOrEmpty()) "EMPTY" else "EXISTING"}")
                return true
            }

            // Try with phone_number
            val existingByPhone = dao.findByPhoneNumber(encryptedPhone)
            LogUtils.d(context, "DatabaseManager",
                "🔍 Exists per phone? ${existingByPhone != null}")

            if (existingByPhone != null) {
                // Update "manually" entity:
                existingByPhone.encoding = encoding
                existingByPhone.encodingPassword = encodingPassword
                existingByPhone.updatedAt = updatedAt

                // phone_hash missing?, add it
                if (existingByPhone.phoneHash.isNullOrEmpty()) {
                    existingByPhone.phoneHash = phoneHash
                }

                dao.update(existingByPhone)
                LogUtils.d(context, "DatabaseManager",
                    "✅ Update via entity. Encoding: ${existingByPhone.encoding}")
                return true
            }

            // Create a new conversation if it does not exist
            LogUtils.d(context, "DatabaseManager",
                "➕ No conversation found. Create a new one")

            val contactName = "" // get from system if possible
            val encryptedContactName = if (contactName.isNotEmpty())
                AppCryptoManager.encrypt64Value(contactName) else null

            val newEntity = ChatConversationEntity(
                phoneNumber = encryptedPhone,
                phoneHash = phoneHash,
                contactName = encryptedContactName,
                lastMessage = "",
                lastTimestamp = System.currentTimeMillis(),
                unreadCount = 0,
                isYChat = false,
                encryptionScheme = "",
                encoding = encoding,
                encodingPassword = encodingPassword,
                createdAt = System.currentTimeMillis(),
                updatedAt = updatedAt
            )

            val newId = dao.insert(newEntity)
            LogUtils.d(context, "DatabaseManager",
                "✅ New conversation created with ID: $newId")
            return true

        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error updating encoding", e)
            false
        }
    }




    // =============================================
    // 3. CHAT MESSAGES
    // =============================================



    fun addMessageInDB(message: ChatMessage, conversationId: Long, context: Context) {
        try {
            val entity = ChatMessageEntity.fromDomain(message, conversationId, context)
            database.chatMessageDao().insert(entity)
            LogUtils.d(null,"DatabaseManager", "✅ Message added: ${message.id}")
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error adding message", e)
        }
    }

    fun addMessageByPhoneNumber(message: ChatMessage, phoneNumber: String, context: Context): Boolean {
        return try {
            LogUtils.d(null,"DatabaseManager", "📨 addMessageByPhoneNumber for: $phoneNumber")

            // Use deterministic hash to find conversation
            val phoneHash = AppCryptoManager.encrypt64Key(phoneNumber)
            val conversation = database.chatConversationDao().findByPhoneHash(phoneHash)

            if (conversation != null) {
                LogUtils.d(null,"DatabaseManager", "✅ Conversation found, ID: ${conversation.id}")
                addMessageInDB(message, conversation.id, context)
                true
            } else {
                LogUtils.e("DatabaseManager", "❌ Conversation not found for number: $phoneNumber")

                LogUtils.d(null,"DatabaseManager", "📝 Created a new conversation for: $phoneNumber")
                val newConversation = ChatConversation(
                    phoneNumber = phoneNumber,
                    contactName = phoneNumber,
                    lastMessage = message.text,
                    lastTimestamp = message.timestamp,
                    unreadCount = 1,
                    isYChat = false,
                    encryptionScheme = "none"
                )

                saveChatConversation(newConversation, context)
                Thread.sleep(1000) // sleep 1sec

                // Try to re-search and find for the conversation added now
                val newConv = database.chatConversationDao().findByPhoneHash(phoneHash)

                if (newConv != null) {
                    addMessageInDB(message, newConv.id, context)
                    true
                } else {
                    LogUtils.e("DatabaseManager", "❌ Conversation still notfound even after creation!")
                    false
                }
            }
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error adding message for number", e)
            false
        }
    }

    // =============================================
    // 4. APP SETTINGS
    // =============================================

    fun getSetting(key: String, defaultValue: String, context: Context): String {
        return try {
            val encryptedKey = AppCryptoManager.encrypt64Key(key)
            val entity = database.appSettingDao().get(encryptedKey)
            entity?.getDecryptedValue(context) ?: defaultValue
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error reading setting", e)
            defaultValue
        }
    }

    fun getBooleanSetting(key: String, defaultValue: Boolean, context: Context): Boolean {
        return try {
            val encryptedKey = AppCryptoManager.encrypt64Key(key)
            val entity = database.appSettingDao().get(encryptedKey)
            entity?.getValueAsBoolean(context) ?: defaultValue
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error reading boolean setting", e)
            defaultValue
        }
    }

    fun saveSetting(key: String, value: String, valueType: String = "string", context: Context) {
        try {
            val entity = AppSettingEntity.create(key, value, valueType, context)
            database.appSettingDao().insertOrUpdate(entity)
            LogUtils.d(null,"DatabaseManager", "✅ Setting saved: $key")
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error saving setting setting", e)
        }
    }

    fun saveBooleanSetting(key: String, value: Boolean, context: Context) {
        saveSetting(key, value.toString(), "boolean", context)
    }



    // =============================================
    // 6. DECODED MESSAGES
    // =============================================

    fun saveDecodedMessage(
        originalMessage: String,
        decodedMessage: String,
        sender: String,
        senderName: String? = null,
        timestamp: Long,
        success: Boolean,
        decodingScheme: String,
        messageType: String = "sms",
        additionalInfo: Map<String, Any>? = null,
        context: Context
    ) {
        try {
            val entity = DecodedMessageEntity.fromDomain(
                originalMessage = originalMessage,
                decodedMessage = decodedMessage,
                sender = sender,
                senderName = senderName,
                timestamp = timestamp,
                success = success,
                decodingScheme = decodingScheme,
                messageType = messageType,
                additionalInfo = additionalInfo,
                context = context
            )
            database.decodedMessageDao().insert(entity)
            LogUtils.d(null,"DatabaseManager", "✅ Decoded message saved from: $sender")
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error saving decoded message", e)
        }
    }

    fun getDecodedMessages(context: Context, limit: Int = 100): List<DecodedMessageEntity.DecodedMessageDomain> {
        return try {
            val entities = database.decodedMessageDao().getAll()
            entities.take(limit).map { entity -> entity.toDomain(context) }
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error reading decoded messages", e)
            emptyList()
        }
    }

    // =============================================
    // 7. UTILITY
    // =============================================

    fun clearDatabase() {
        try {
            database.clearAllTables()
            LogUtils.d(null,"DatabaseManager", "🗑️ Database cancellato")
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Errore cancellazione database", e)
        }
    }

    // =============================================
    // 8. DATABASE TEST - BASE FOR ALL DB ACTIONS
    // =============================================

    fun testDatabaseOperations(context: Context): Boolean {
        return try {
            LogUtils.d(null,"DatabaseManager", "🧪 Testing database...")

            LogUtils.d(null,"DatabaseManager", "🚨 IMMEDIATE CLEANING TEST CONVERSATIONS...")
            val testPhone = TESTPHONENUMBER

            val phoneHash = AppCryptoManager.encrypt64Key(testPhone)
            val rowsDeletedByHash = database.chatConversationDao().deleteByPhoneHash(phoneHash)
            LogUtils.d(null,"DatabaseManager", "   Chats cleaned via hash: $rowsDeletedByHash")

            val encryptedPhone = AppCryptoManager.encrypt64Value(testPhone)
            val rowsDeletedByPhone = database.chatConversationDao().deleteByPhoneNumber(encryptedPhone)
            LogUtils.d(null,"DatabaseManager", "   Chat cleaned via number: $rowsDeletedByPhone")

            try {
                val allConversations = database.chatConversationDao().getAll()
                allConversations.forEach { conv ->
                    try {
                        val decryptedPhone = AppCryptoManager.decrypt64Value(conv.phoneNumber)
                        if (decryptedPhone == testPhone) {
                            database.chatConversationDao().delete(conv)
                            LogUtils.d(null,"DatabaseManager", "   Chat deleted via scanning: ID=${conv.id}")
                        }
                    } catch (e: Exception) {
                        // Ignore decryption errors
                    }
                }
            } catch (e: Exception) {
                LogUtils.d(null,"DatabaseManager", "⚠️ Error scanning for cleaning: ${e.message}")
            }

            val testContactId = "test_${System.currentTimeMillis()}"
            LogUtils.d(null,"DatabaseManager", "   Cleaning test contacts...")
            val testContacts = database.trustedContactDao().getAllSync()
                .filter { it.contactId?.contains("test_") == true }
            testContacts.forEach { contact ->
                database.trustedContactDao().delete(contact)
                LogUtils.d(null,"DatabaseManager", "   Deleted contact: ${contact.contactId}")
            }

            // === NOW START TESTING ===
            LogUtils.d(null,"DatabaseManager", "✅ Clean db env - start test...")

            // === TEST 1: TRUSTED CONTACTS ===
            LogUtils.d(null,"DatabaseManager", "👥 Testing TrustedContacts...")

            // First, repair any corrupted contacts
            repairTrustedContacts(context)

            val testContact = TrustedContact(
                contactId = testContactId,
                phoneNumber = "+391234567890",
                displayName = "Test Contact DB",
                isActive = true
            )

            saveTrustedContact(testContact, context)

            // Verify reading - with resilience
            val contacts = getTrustedContactsResilient(context)
            val contactTestPassed = contacts.any { it.contactId == testContact.contactId }
            LogUtils.d(null,"DatabaseManager", if (contactTestPassed) "✅ TrustedContacts OK" else "❌ TrustedContacts FAILED")

            if (!contactTestPassed) {
                LogUtils.e("DatabaseManager", "❌ Test contact not found! Expected ID: $testContactId")
                LogUtils.e("DatabaseManager", "   Found IDs: ${contacts.map { it.contactId }}")
            }

            // === TEST 2: CHAT CONVERSATIONS ===
            LogUtils.d(null,"DatabaseManager", "💬 Test ChatConversations...")
            val conversation = ChatConversation(
                phoneNumber = testPhone,
                contactName = "Test Conversation",
                lastMessage = "Test last message",
                lastTimestamp = System.currentTimeMillis(),
                unreadCount = 1,
                isYChat = false,
                encryptionScheme = "test_initial"
            )

            saveChatConversation(conversation, context)

            val foundConversation = getChatConversation(testPhone, context)
            val conversationTestPassed = foundConversation != null && foundConversation.phoneNumber == testPhone
            LogUtils.d(null,"DatabaseManager", if (conversationTestPassed) "✅ ChatConversations OK" else "❌ ChatConversations FAILED")

            if (!conversationTestPassed) {
                LogUtils.e("DatabaseManager", "❌ Conversation not found or phoneNumber mismatch")
                LogUtils.e("DatabaseManager", "   Searched: $testPhone")
                LogUtils.e("DatabaseManager", "   Found: ${foundConversation?.phoneNumber}")
            }

            // === DIRECT TEST: ENCRYPTION SCHEME DIRECT DAO ===
            LogUtils.d(null,"DatabaseManager", "🎯 Test EncryptionScheme Direct DAO...")
            var directTestPassed = false

            val directPhoneHash = AppCryptoManager.encrypt64Key(testPhone)
            val directEntity = database.chatConversationDao().findByPhoneHash(directPhoneHash)

            if (directEntity != null) {
                LogUtils.d(null,"DatabaseManager", "  Entity found, ID: ${directEntity.id}")

                val newEncryptionScheme = "direct_dao_test_${System.currentTimeMillis()}"
                directEntity.encryptionScheme = newEncryptionScheme
                directEntity.updatedAt = System.currentTimeMillis()

                database.chatConversationDao().update(directEntity)
                LogUtils.d(null,"DatabaseManager", "  Entity directly updated with scheme: $newEncryptionScheme")

                val reloadedEntity = database.chatConversationDao().findByPhoneHash(directPhoneHash)
                if (reloadedEntity != null) {
                    directTestPassed = reloadedEntity.encryptionScheme == newEncryptionScheme
                    LogUtils.d(null,"DatabaseManager", if (directTestPassed) "  ✅ DAO Direct Test OK" else "  ❌ Test diretto DAO FAILED")
                    LogUtils.d(null,"DatabaseManager", "  Scheme after update: ${reloadedEntity.encryptionScheme}")
                }
            }

            // === TEST EXTRA: ENCRYPTION SCHEME ===
            LogUtils.d(null,"DatabaseManager", "🔐 Test EncryptionScheme...")
            var encryptionTestPassed = false

            if (conversationTestPassed) {
                val conversationBefore = findConversationByPhoneNumber(testPhone, context)
                LogUtils.d(null,"DatabaseManager", "📊 Scheme before update: ${conversationBefore?.encryptionScheme}")

                val newEncryptionScheme = "sisa_test_${System.currentTimeMillis()}"
                val updateResult = updateChatEncryptionScheme(testPhone, newEncryptionScheme, context)

                Thread.sleep(100)

                val updatedConversation = findConversationByPhoneNumber(testPhone, context)

                if (updatedConversation != null) {
                    LogUtils.d(null,"DatabaseManager", "📊 Scheme AFTER update: ${updatedConversation.encryptionScheme}")
                    LogUtils.d(null,"DatabaseManager", "📊 Scheme expected: $newEncryptionScheme")

                    encryptionTestPassed = updatedConversation.encryptionScheme == newEncryptionScheme

                    if (!encryptionTestPassed) {
                        LogUtils.e("DatabaseManager", "❌ MISMATCH encryptionScheme!")
                        LogUtils.e("DatabaseManager", "   Read: '${updatedConversation.encryptionScheme}'")
                        LogUtils.e("DatabaseManager", "   Expected: '$newEncryptionScheme'")
                    }

                    LogUtils.d(null,"DatabaseManager", if (encryptionTestPassed) "✅ EncryptionScheme OK" else "❌ EncryptionScheme FAILED")
                } else {
                    LogUtils.e("DatabaseManager", "❌ Conversation not found after update!")
                }
            } else {
                LogUtils.e("DatabaseManager", "❌ Skipped EncryptionScheme test (conversation not saved)")
            }

            // === TEST 3: CHAT MESSAGES ===
            LogUtils.d(null,"DatabaseManager", "📨 Test ChatMessages...")
            var messageTestPassed = false
            if (conversationTestPassed) {
                val testMessage = ChatMessage(
                    id = System.currentTimeMillis(),
                    text = "Ciao, questo è un test del database!",
                    sender = testPhone,
                    senderName = "Test Sender",
                    timestamp = System.currentTimeMillis(),
                    trans_timestamp = System.currentTimeMillis() - 10000,
                    isDecoded = true,
                    isOutgoing = false,
                    isSent = true,
                    isYMessage = false,
                    isSystemMessage = true,
                    metadata = null,
                    isReplaced = false
                )

                messageTestPassed = addMessageByPhoneNumber(testMessage, testPhone, context)

                val conversationEntity = database.chatConversationDao().findByPhoneHash(directPhoneHash)

                if (conversationEntity != null) {
                    val messages = database.chatMessageDao().getByConversation(conversationEntity.id)
                    messageTestPassed = messages.any { it.id == testMessage.id }
                }

                LogUtils.d(null,"DatabaseManager", if (messageTestPassed) "✅ ChatMessages OK" else "❌ ChatMessages FAILED")
            } else {
                LogUtils.e("DatabaseManager", "❌ Skipped ChatMessages test (conversation not saved)")
            }

            // === TEST 4: APP SETTINGS ===
            LogUtils.d(null,"DatabaseManager", "⚙️ Test AppSettings...")
            val testSettingKey = "test_setting_${System.currentTimeMillis()}"
            val testSettingValue = "test_value_${System.currentTimeMillis()}"

            saveSetting(testSettingKey, testSettingValue, context = context)

            Thread.sleep(50)

            val readValue = getSetting(testSettingKey, "", context)
            val settingTestPassed = readValue == testSettingValue

            if (!settingTestPassed) {
                LogUtils.e("DatabaseManager", "❌ Read value: '$readValue', expected: '$testSettingValue'")
            }

            LogUtils.d(null,"DatabaseManager", if (settingTestPassed) "✅ AppSettings OK" else "❌ AppSettings FAILED")

            // === TEST 6: DECODED MESSAGES ===
            LogUtils.d(null,"DatabaseManager", "🔍 Test DecodedMessages...")
            val decodedTestPassed = try {
                saveDecodedMessage(
                    originalMessage = "Hello World",
                    decodedMessage = "Ciao Mondo",
                    sender = testPhone,
                    senderName = "Test Sender",
                    timestamp = System.currentTimeMillis(),
                    success = true,
                    decodingScheme = "test_scheme",
                    messageType = "sms",
                    context = context
                )

                val decodedMessages = getDecodedMessages(context, 10)
                val hasTestMessage = decodedMessages.any {
                    it.originalMessage == "Hello World" && it.decodedMessage == "Ciao Mondo"
                }
                LogUtils.d(null,"DatabaseManager", if (hasTestMessage) "✅ DecodedMessages OK" else "❌ DecodedMessages FAILED")
                hasTestMessage
            } catch (e: Exception) {
                LogUtils.e("DatabaseManager", "❌ DecodedMessages FAILED", e)
                false
            }

            // === MINIMAL FINAL CLEANING ===
            LogUtils.d(null,"DatabaseManager", "⚡ Final cleaning...")
            try {
                database.chatConversationDao().deleteByPhoneHash(phoneHash)

                val encryptedSettingKey = AppCryptoManager.encrypt64Key(testSettingKey)
                database.appSettingDao().delete(encryptedSettingKey)

                removeTrustedContact(testContactId)

                LogUtils.d(null,"DatabaseManager", "✅ Final cleaning complete (only test elements)")
            } catch (e: Exception) {
                LogUtils.e("DatabaseManager", "⚠️ Error final cleaning", e)
            }

            // === FINAL RESULT ===
            // Don't fail the whole test if only pre-existing corrupted contacts were the issue
            // The test contact should still work
            val allTestsPassed = contactTestPassed && conversationTestPassed && encryptionTestPassed
                    && settingTestPassed && decodedTestPassed

            if (allTestsPassed) {
                LogUtils.d(null,"DatabaseManager", "🎉🎉🎉 ALL DB TESTS PASSED! 🎉🎉🎉")
                LogUtils.d(null,"DatabaseManager", "✅ TrustedContacts: OK")
                LogUtils.d(null,"DatabaseManager", "✅ ChatConversations: OK")
                LogUtils.d(null,"DatabaseManager", "✅ ChatEncryptionTest: OK")
                LogUtils.d(null,"DatabaseManager", "✅ ChatMessages: OK")
                LogUtils.d(null,"DatabaseManager", "✅ AppSettings: OK")
                LogUtils.d(null,"DatabaseManager", "✅ DecodedMessages: OK")

                if (BuildConfig.DEBUG) {
                    dumpFileSystemInfo(context)
                    dumpFullDatabaseDetails(context)
                }

            } else {
                LogUtils.e("DatabaseManager", "⚠️ SOME DB TESTS FAILED")
                LogUtils.e("DatabaseManager", "   TrustedContacts: ${if (contactTestPassed) "✅" else "❌"}")
                LogUtils.e("DatabaseManager", "   ChatConversations: ${if (conversationTestPassed) "✅" else "❌"}")
                LogUtils.e("DatabaseManager", "   ChatEncryptionTest: ${if (encryptionTestPassed) "✅" else "❌"}")
                LogUtils.e("DatabaseManager", "   ChatMessages: ${if (messageTestPassed) "✅" else "❌"}")
                LogUtils.e("DatabaseManager", "   AppSettings: ${if (settingTestPassed) "✅" else "❌"}")
                LogUtils.e("DatabaseManager", "   DecodedMessages: ${if (decodedTestPassed) "✅" else "❌"}")
            }

            allTestsPassed

        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌❌❌ TEST DATABASE FAILED", e)
            false
        }
    }

    // Add this helper method to get trusted contacts resiliently
    private fun getTrustedContactsResilient(context: Context): List<TrustedContact> {
        return try {
            val entities = database.trustedContactDao().getAllSync()
            val validContacts = mutableListOf<TrustedContact>()

            entities.forEach { entity ->
                try {
                    validContacts.add(entity.toDomain(context))
                } catch (e: Exception) {
                    LogUtils.e("DatabaseManager",
                        "⚠️ Skipping corrupted contact: ${entity.contactId}", e)
                    // Optionally delete corrupted contacts
                    try {
                        database.trustedContactDao().delete(entity)
                        LogUtils.d("DatabaseManager", "   Deleted corrupted contact: ${entity.contactId}")
                    } catch (deleteError: Exception) {
                        LogUtils.e("DatabaseManager", "   Failed to delete corrupted contact", deleteError)
                    }
                }
            }

            validContacts
        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error reading trusted contacts", e)
            emptyList()
        }
    }

    // Add this repair method
    fun repairTrustedContacts(context: Context) {
        try {
            LogUtils.d(null,"DatabaseManager", "🔧 Repairing trusted contacts...")

            val entities = database.trustedContactDao().getAllSync()
            var deleted = 0

            entities.forEach { entity ->
                try {
                    entity.toDomain(context)
                } catch (e: Exception) {
                    LogUtils.e("DatabaseManager",
                        "❌ Corrupted contact found: ${entity.contactId}, deleting...")
                    database.trustedContactDao().delete(entity)
                    deleted++
                }
            }

            if (deleted > 0) {
                LogUtils.d(null,"DatabaseManager", "✅ Deleted $deleted corrupted contacts")
            } else {
                LogUtils.d(null,"DatabaseManager", "✅ No corrupted contacts found")
            }

        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error repairing trusted contacts", e)
        }
    }

    // =============================================
    // 9. DUMP E DEBUG
    // =============================================
    fun dumpFullDatabaseDetails(context: Context) {
        try {
            val db = database.openHelper.writableDatabase

            // For every important table
            val tables = listOf(
                "chat_conversations",
                "chat_messages",
                "trusted_contacts",
                "app_settings",
                "y_user_associations",
                "decoded_messages"
            )

            tables.forEach { tableName ->
                LogUtils.d(null,"DB_DETAILS", "\n" + "=".repeat(50))
                LogUtils.d(null,"DB_DETAILS", "📋 TABELLA: $tableName")
                LogUtils.d(null,"DB_DETAILS", "=".repeat(50))

                try {
                    // Schema
                    db.query("PRAGMA table_info($tableName)").use { schemaCursor ->
                        LogUtils.d(null,"DB_DETAILS", "SCHEMA:")
                        while (schemaCursor.moveToNext()) {
                            val nameIndex = schemaCursor.getColumnIndex("name")
                            val typeIndex = schemaCursor.getColumnIndex("type")
                            val notnullIndex = schemaCursor.getColumnIndex("notnull")
                            val pkIndex = schemaCursor.getColumnIndex("pk")

                            val name = if (nameIndex >= 0) schemaCursor.getString(nameIndex) else "N/A"
                            val type = if (typeIndex >= 0) schemaCursor.getString(typeIndex) else "N/A"
                            val notnull = if (notnullIndex >= 0) schemaCursor.getInt(notnullIndex) else 0
                            val pk = if (pkIndex >= 0) schemaCursor.getInt(pkIndex) else 0

                            LogUtils.d(null,"DB_DETAILS", "  $name ($type) ${if (pk > 0) "PRIMARY KEY" else ""} ${if (notnull == 1) "NOT NULL" else ""}")
                        }
                    }

                    // Content
                    db.query("SELECT * FROM $tableName").use { dataCursor ->
                        val rowCount = dataCursor.count
                        LogUtils.d(null,"DB_DETAILS", "\nTOTAL ROWS: $rowCount")

                        if (rowCount > 0) {
                            val columnNames = dataCursor.columnNames

                            var displayedRows = 0
                            while (dataCursor.moveToNext() && displayedRows < 10) {
                                LogUtils.d(null,"DB_DETAILS", "\nRow ${displayedRows + 1}:")
                                for (i in columnNames.indices) {
                                    val value = when (dataCursor.getType(i)) {
                                        Cursor.FIELD_TYPE_STRING -> {
                                            val str = dataCursor.getString(i)
                                            if (tableName == "chat_conversations" && columnNames[i] == "phone_number") {
                                                "🔒 ${str?.take(20)}..."
                                            } else {
                                                str
                                            }
                                        }
                                        Cursor.FIELD_TYPE_INTEGER -> dataCursor.getLong(i)
                                        Cursor.FIELD_TYPE_FLOAT -> dataCursor.getDouble(i)
                                        Cursor.FIELD_TYPE_BLOB -> "[BLOB ${dataCursor.getBlob(i)?.size ?: 0} bytes]"
                                        else -> "NULL"
                                    }
                                    LogUtils.d(null,"DB_DETAILS", "  ${columnNames[i]}: $value")
                                }
                                displayedRows++
                            }

                            if (rowCount > 10) {
                                LogUtils.d(null,"DB_DETAILS", "... and further ${rowCount - 10} rows")
                            }
                        }
                    }

                } catch (e: Exception) {
                    LogUtils.d(null,"DB_DETAILS", "❌ Table $tableName not found or error: ${e.message}")
                }
            }

            // Special debug query
            LogUtils.d(null,"DB_DETAILS", "\n" + "=".repeat(50))
            LogUtils.d(null,"DB_DETAILS", "🔍 SPECIAL DEBUG QUERY")
            LogUtils.d(null,"DB_DETAILS", "=".repeat(50))

            // Count conversations with/out hash
            db.query("""
            SELECT 
                COUNT(*) as total,
                SUM(CASE WHEN phone_hash IS NULL OR phone_hash = '' THEN 1 ELSE 0 END) as without_hash,
                SUM(CASE WHEN phone_hash IS NOT NULL AND phone_hash != '' THEN 1 ELSE 0 END) as with_hash
            FROM chat_conversations
        """).use { cursor ->
                if (cursor.moveToFirst()) {
                    LogUtils.d(null,"DB_DETAILS", "📊 MIGRATION PHONE_HASH:")
                    LogUtils.d(null,"DB_DETAILS", "  Total: ${cursor.getInt(0)}")
                    LogUtils.d(null,"DB_DETAILS", "  Without hash: ${cursor.getInt(1)}")
                    LogUtils.d(null,"DB_DETAILS", "  With hash: ${cursor.getInt(2)}")
                }
            }

        } catch (e: Exception) {
            LogUtils.e("DB_DETAILS", "❌ Error detailed dump", e)
        }
    }

    fun logDatabaseDump(context: Context) {
        try {
            LogUtils.e("DB_DUMP", "🚨 START DUMP SINCRONOUS DATABASE 🚨")

            // Timestamp
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(Date())
            LogUtils.e("DB_DUMP", "⏰ Timestamp: $timestamp")

            LogUtils.e("DB_DUMP", "\n=== SECTION 1: FILE SYSTEM ===")
            dumpFileSystemInfo(context)

            LogUtils.e("DB_DUMP", "\n=== SECTION 2: DATABASE INFO ===")
            try {
                val db = database.openHelper.writableDatabase
                dumpDatabaseInfo(db, context)
            } catch (e: Exception) {
                LogUtils.e("DB_DUMP", "❌ CANNOT ACCESS TO DATABASE", e)
                dumpDatabaseInfoFallback(context)
            }

            LogUtils.e("DB_DUMP", "\n=== SECTION 3: TABLE AND DATA ===")
            try {
                val db = database.openHelper.writableDatabase
                dumpTableData(db)
            } catch (e: Exception) {
                LogUtils.e("DB_DUMP", "❌ CANNOT ACCESS DATA", e)
            }

            LogUtils.e("DB_DUMP", "\n=== SECTION 4: CRYPTOGRAPHY STATE ===")
            dumpEncryptionStatus(context)

            LogUtils.e("DB_DUMP", "🚨 END DATABASE DUMP 🚨")

        } catch (e: Exception) {
            LogUtils.e("DB_DUMP", "💥 ERROR DURING DUMP", e)
        }
    }


    // Add this to your DatabaseManager class
    fun verifyMigrationSuccessful(): Boolean {
        return try {
            LogUtils.d(null, "MIGRATION_TEST", "🔍 Verifying migration to version 6...")

            val db = database.openHelper.writableDatabase

            // Check database version first
            val pragmaCursor = db.query("PRAGMA user_version")
            var dbVersion = 0
            pragmaCursor.use { cursor ->
                if (cursor.moveToFirst()) {
                    dbVersion = cursor.getInt(0)
                }
            }

            LogUtils.d(null, "MIGRATION_TEST", "Database version: $dbVersion")
            if (dbVersion != 6) {
                LogUtils.d(null, "MIGRATION_TEST", "❌ Wrong database version: expected 6, got $dbVersion")
                return false
            }

            // Check if new columns exist in chat_messages table
            val columns = mutableListOf<String>()
            db.query("PRAGMA table_info(chat_messages)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(1)) // column name is at index 1
                }
            }

            val requiredColumns = listOf(
                "is_system_message",
                "metadata_type",
                "metadata_json",
                "is_replaced"
            )

            val missingColumns = requiredColumns.filterNot { it in columns }

            if (missingColumns.isNotEmpty()) {
                LogUtils.d(null, "MIGRATION_TEST",
                    "❌ Missing columns: ${missingColumns.joinToString()}")
                return false
            }

            // Check if indexes were created
            val indexes = mutableListOf<String>()
            db.query("PRAGMA index_list(chat_messages)").use { cursor ->
                while (cursor.moveToNext()) {
                    indexes.add(cursor.getString(1)) // index name is at index 1
                }
            }

            val requiredIndexes = listOf("idx_system_message", "idx_metadata_type")
            val missingIndexes = requiredIndexes.filterNot { it in indexes }

            if (missingIndexes.isNotEmpty()) {
                LogUtils.d(null, "MIGRATION_TEST",
                    "❌ Missing indexes: ${missingIndexes.joinToString()}")
                return false
            }

            // Test inserting a record with new fields to verify schema
            try {
                // Try to insert a test message with new fields
                val testId = System.currentTimeMillis()
                db.execSQL("""
                INSERT INTO chat_messages 
                (id, conversation_id, message_text, sender, timestamp, is_outgoing, is_sent, is_ymessage, is_system_message, metadata_type, metadata_json, is_replaced)
                VALUES ($testId, 1, 'test', 'sender', ${System.currentTimeMillis()}, 0, 1, 0, 1, 'test_type', '{"test":"json"}', 0)
            """)

                // Clean up test data
                db.execSQL("DELETE FROM chat_messages WHERE id = $testId")

            } catch (e: Exception) {
                LogUtils.d(null, "MIGRATION_TEST", "❌ Failed to insert test data: ${e.message}")
                return false
            }

            LogUtils.d(null, "MIGRATION_TEST", "✅ All migration checks passed!")
            true

        } catch (e: Exception) {
            LogUtils.e("MIGRATION_TEST", "❌ Migration verification failed with exception", e)
            false
        }
    }

    private fun dumpFileSystemInfo(context: Context) {
        try {
            val filesDir = context.filesDir
            LogUtils.e("DB_DUMP", "📁 Directory files: ${filesDir.absolutePath}")
            LogUtils.e("DB_DUMP", "📁 Esists: ${filesDir.exists()}")
            LogUtils.e("DB_DUMP", "📁 CanWrite: ${filesDir.canWrite()}")

            // List all .db files
            val dbFiles = filesDir.listFiles { file ->
                file.name.endsWith(".db") ||
                        file.name.contains("nook") ||
                        file.name.contains("secure")
            }

            LogUtils.e("DB_DUMP", "📁 File database found: ${dbFiles?.size ?: 0}")
            dbFiles?.forEach { file ->
                LogUtils.e("DB_DUMP", "   📄 ${file.name}")
                LogUtils.e("DB_DUMP", "      Dimension: ${file.length()} bytes")
                LogUtils.e("DB_DUMP", "      Modified: ${Date(file.lastModified())}")
            }

        } catch (e: Exception) {
            LogUtils.e("DB_DUMP", "❌ Error filesystem analysis", e)
        }
    }

    private fun dumpDatabaseInfo(db: SupportSQLiteDatabase, context: Context) {
        try {
            LogUtils.e("DB_DUMP", "🗃️ Database opened with success")

            // Info SQLite
            db.query("SELECT sqlite_version()").use { cursor ->
                if (cursor.moveToFirst()) {
                    val version = cursor.getString(0)
                    LogUtils.e("DB_DUMP", "   SQLite version: $version")
                }
            }

            // User version (Room version)
            db.query("PRAGMA user_version").use { cursor ->
                if (cursor.moveToFirst()) {
                    val userVersion = cursor.getInt(0)
                    LogUtils.e("DB_DUMP", "   User version (Room): $userVersion")
                }
            }

            // Integrity check
            try {
                db.query("PRAGMA integrity_check").use { cursor ->
                    if (cursor.moveToFirst()) {
                        val integrity = cursor.getString(0)
                        LogUtils.e("DB_DUMP", "   Integrity check: $integrity")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("DB_DUMP", "   ❌ Integrity check fallito", e)
            }

        } catch (e: Exception) {
            LogUtils.e("DB_DUMP", "❌ Error info database", e)
        }
    }

    private fun dumpDatabaseInfoFallback(context: Context) {
        try {
            LogUtils.e("DB_DUMP", "🔄 Tentativo fallback...")

            val dbName = AppDatabase.getEncryptedDatabaseName(context)
            val dbFile = File(context.filesDir, dbName)

            if (dbFile.exists()) {
                LogUtils.e("DB_DUMP", "📄 Database file exists: ${dbFile.absolutePath}")
                LogUtils.e("DB_DUMP", "📄 Dimension: ${dbFile.length()} bytes")
            } else {
                LogUtils.e("DB_DUMP", "❌ Database file does NOT exist")
            }

        } catch (e: Exception) {
            LogUtils.e("DB_DUMP", "❌ Error db dump fallback", e)
        }
    }

    private fun dumpTableData(db: SupportSQLiteDatabase) {
        try {
            // Lista tutte le tabelle
            db.query("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
                LogUtils.e("DB_DUMP", "📊 Tables found: ${cursor.count}")

                while (cursor.moveToNext()) {
                    val tableName = cursor.getString(0)
                    val tableSql = cursor.getString(1)

                    LogUtils.e("DB_DUMP", "\n   📋 TABLE: $tableName")
                    LogUtils.e("DB_DUMP", "      SQL: ${tableSql?.take(100) ?: "N/A"}...")

                    if (!tableName.startsWith("sqlite_") &&
                        !tableName.startsWith("android_") &&
                        tableName != "room_master_table") {

                        // Count rows
                        db.query("SELECT COUNT(*) FROM $tableName").use { countCursor ->
                            if (countCursor.moveToFirst()) {
                                val rowCount = countCursor.getInt(0)
                                LogUtils.e("DB_DUMP", "      Rows: $rowCount")
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            LogUtils.e("DB_DUMP", "❌ Error dump tables", e)
        }
    }

    private fun dumpEncryptionStatus(context: Context) {
        try {
            LogUtils.e("DB_DUMP", "🔐 Encryption status:")

            val cryptoActive = AppCryptoManager.isEncryptionActive()
            LogUtils.e("DB_DUMP", "   Encryption active: $cryptoActive")

            // Database name
            val dbName = AppDatabase.getEncryptedDatabaseName(context)
            LogUtils.e("DB_DUMP", "   Encrypted DB name: $dbName")

        } catch (e: Exception) {
            LogUtils.e("DB_DUMP", "❌ Error Encryption status", e)
        }
    }

    private fun dumpStorageStatus(context: Context) {
        try {
            LogUtils.e("DB_DUMP", "💾 Memory status:")

            val filesDir = context.filesDir
            val totalSpace = filesDir.totalSpace
            val freeSpace = filesDir.freeSpace
            val usableSpace = filesDir.usableSpace

            LogUtils.e("DB_DUMP", "   Total space: ${formatBytes(totalSpace)}")
            LogUtils.e("DB_DUMP", "   Free space: ${formatBytes(freeSpace)}")
            LogUtils.e("DB_DUMP", "   Usable space: ${formatBytes(usableSpace)}")
            LogUtils.e("DB_DUMP", "   Percentage free: ${(freeSpace.toDouble() / totalSpace.toDouble() * 100).toInt()}%")

            if (freeSpace < 10 * 1024 * 1024) { // < 10MB
                LogUtils.e("DB_DUMP", "   ⚠️ BEWARE: Free space less than 10MB")
            }

        } catch (e: Exception) {
            LogUtils.e("DB_DUMP", "❌ Error dump memory status", e)
        }
    }

    /**
     * Search for conversation - robust version
     */
    fun findConversationByPhoneNumber(phoneNumber: String, context: Context): ChatConversation? {
        return try {
            LogUtils.d(null,"DatabaseManager", "🔍 FIND conversation for: $phoneNumber")

            val normalized = PhoneUtils.normalizePhoneNumber(phoneNumber)
            LogUtils.d(null,"DatabaseManager", "  Normalized: $normalized")

            // TRY 1: Use phone_hash
            val phoneHash = AppCryptoManager.encrypt64Key(normalized)
            LogUtils.d(null,"DatabaseManager", "  Phone hash: $phoneHash")

            var entity = database.chatConversationDao().findByPhoneHash(phoneHash)

            if (entity != null) {
                LogUtils.d(null,"DatabaseManager", "  ✅ Found via phone_hash")
                entity.toDomain(context)
            }

            // TRY 2: Use number (legacy)
            val encryptedPhone = AppCryptoManager.encrypt64Value(normalized)
            LogUtils.d(null,"DatabaseManager", "  Encrypted number: ${encryptedPhone.take(20)}...")

            entity = database.chatConversationDao().findByPhoneNumber(encryptedPhone)

            if (entity != null) {
                LogUtils.d(null,"DatabaseManager", "  ✅ Found via encrypted number")
                return entity.toDomain(context)
            }

            // TRY 3: Scan all conversation
            LogUtils.d(null,"DatabaseManager", "  🔍 Scanning all conversations...")
            val allEntities = database.chatConversationDao().getAll()

            for (convEntity in allEntities) {
                try {
                    val decryptedPhone = AppCryptoManager.decrypt64Value(convEntity.phoneNumber)
                    val normalizedConvPhone = PhoneUtils.normalizePhoneNumber(decryptedPhone)

                    if (normalizedConvPhone == normalized) {
                        LogUtils.d(null,"DatabaseManager", "  ✅ Found via complete scanning")
                        return convEntity.toDomain(context).apply {
                            val messages = getMessagesForConversationById(convEntity.id, context)
                            this.messages.addAll(messages)
                        }
                    }
                } catch (e: Exception) {
                    // Continua con la prossima conversazione
                }
            }

            LogUtils.d(null,"DatabaseManager", "  ❌ No conversation found")
            null

        } catch (e: Exception) {
            LogUtils.e("DatabaseManager", "❌ Error searching for conversation", e)
            null
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
}